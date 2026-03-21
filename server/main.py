from fastapi import FastAPI, Depends, HTTPException, Request, File, UploadFile, Form, BackgroundTasks
from sqlalchemy import or_, text, func
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session
from typing import List, Optional
import shutil
import os
from server import models
from server import schemas
from server import ai_service
from server.database import engine, get_db, SessionLocal
from sqlalchemy.dialects.postgresql import insert as pg_insert
import datetime
import re
import asyncio
import cloudinary
import cloudinary.uploader
import tempfile

app = FastAPI(title="SalesMind AI CRM API")

# Create tables if they don't exist
models.Base.metadata.create_all(bind=engine)

# Ensure unique indexes for duplicate prevention (Postgres specific)
with engine.connect() as conn:
    # 1. Clean up existing DUPLICATES first - absolutely required to create a UNIQUE index
    # We keep only one record per duplicated group.
    conn.execute(text("""
        DELETE FROM calls 
        WHERE id NOT IN (
            SELECT MIN(id) FROM calls GROUP BY contact_id, timestamp
        );
    """))
    conn.execute(text("""
        DELETE FROM messages 
        WHERE id NOT IN (
            SELECT MIN(id) FROM messages GROUP BY contact_id, timestamp, content
        );
    """))
    # 2. Clean up recordings (Handle existing records by populating original_filename if empty)
    conn.execute(text("UPDATE recordings SET original_filename = substring(file_path from '[^/]+$') WHERE original_filename IS NULL;"))
    conn.execute(text("""
        DELETE FROM recordings 
        WHERE id NOT IN (
            SELECT MIN(id) FROM recordings GROUP BY contact_id, original_filename
        );
    """))

    # 3. Now create the unique constraint/index
    conn.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS uix_call_contact_timestamp ON calls (contact_id, timestamp);"))
    conn.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS uix_msg_contact_timestamp_content ON messages (contact_id, timestamp, content);"))
    conn.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS uix_recording_contact_filename ON recordings (contact_id, original_filename);"))
    conn.commit()

# Mount static files
static_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")
if not os.path.exists(static_dir):
    os.makedirs(static_dir)
app.mount("/static", StaticFiles(directory=static_dir), name="static")

@app.get("/")
def read_root():
    return FileResponse(os.path.join(static_dir, "index.html"))

# Recording storage (Local fallback)
RECORDINGS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "recordings")
if not os.path.exists(RECORDINGS_DIR):
    os.makedirs(RECORDINGS_DIR)

# Cloudinary Setup (For Cloud Deployment)
CLOUDINARY_CLOUD_NAME = os.getenv("CLOUDINARY_CLOUD_NAME")
CLOUDINARY_API_KEY = os.getenv("CLOUDINARY_API_KEY")
CLOUDINARY_API_SECRET = os.getenv("CLOUDINARY_API_SECRET")

if CLOUDINARY_CLOUD_NAME and CLOUDINARY_API_KEY and CLOUDINARY_API_SECRET:
    cloudinary.config(
        cloud_name=CLOUDINARY_CLOUD_NAME,
        api_key=CLOUDINARY_API_KEY,
        api_secret=CLOUDINARY_API_SECRET,
        secure=True
    )
    print("--- [INFO] Cloudinary initialized successfully ---")
else:
    print("--- [WARNING] Cloudinary not configured. Using local storage. ---")

# --- Global Stats ---
@app.get("/stats/")
def get_global_stats(db: Session = Depends(get_db)):
    return {
        "total_contacts": db.query(models.Contact).count(),
        "total_calls": db.query(models.Call).count(),
        "total_messages": db.query(models.Message).count(),
        "total_recordings": db.query(models.Recording).count()
    }

# --- Contact Endpoints ---

@app.post("/contacts/bulk/", response_model=List[schemas.Contact])
def create_contacts_bulk(contacts: List[schemas.ContactCreate], db: Session = Depends(get_db)):
    """
    Saves multiple contacts at once. If phone_number exists, it quietly skips (UPSERT).
    """
    if not contacts:
        return []

    # 1. Normalize phone numbers and prepare values
    values = []
    normalized_phones = []
    for c in contacts:
        # Normalize: Remove all non-digit characters
        clean_phone = "".join(filter(str.isdigit, c.phone_number))
        if clean_phone:
            values.append({
                "name": c.name,
                "phone_number": clean_phone,
                "organization": c.organization
            })
            normalized_phones.append(clean_phone)

    # 2. Execute Bulk Insert with PostgreSQL UPSERT logic
    stmt = pg_insert(models.Contact).values(values)
    stmt = stmt.on_conflict_do_nothing(index_elements=['phone_number'])
    
    db.execute(stmt)
    db.commit()

    # 3. Return the processed contacts
    return db.query(models.Contact).filter(models.Contact.phone_number.in_(normalized_phones)).all()

@app.post("/contacts/", response_model=schemas.Contact)
def create_contact(contact: schemas.ContactCreate, db: Session = Depends(get_db)):
    db_contact = models.Contact(**contact.dict())
    db.add(db_contact)
    db.commit()
    db.refresh(db_contact)
    return db_contact

@app.get("/contacts/", response_model=List[schemas.Contact])
def get_contacts(q: Optional[str] = None, skip: int = 0, limit: int = 100, db: Session = Depends(get_db)):
    query = db.query(models.Contact)
    if q:
        search_q = q.strip()
        print(f"--- [DEBUG] GET /contacts/ q='{search_q}' ---")
        query = query.filter(
            or_(
                models.Contact.name.ilike(f"%{search_q}%"),
                models.Contact.phone_number.contains(search_q),
                models.Contact.id.contains(search_q)
            )
        )
    
    results = query.offset(skip).limit(limit).all()
    
    # Enrich results with last contact date
    enriched_results = []
    for c in results:
        # Find latest call or message
        last_call = db.query(models.Call).filter(models.Call.contact_id == c.id).order_by(models.Call.timestamp.desc()).first()
        last_msg = db.query(models.Message).filter(models.Message.contact_id == c.id).order_by(models.Message.timestamp.desc()).first()
        
        last_date = None
        if last_call and last_msg:
            last_date = max(last_call.timestamp, last_msg.timestamp)
        elif last_call:
            last_date = last_call.timestamp
        elif last_msg:
            last_date = last_msg.timestamp
            
        # Manually create the enriched object including last_contact
        c_dict = {
            "id": c.id,
            "name": c.name,
            "phone_number": c.phone_number,
            "organization": c.organization,
            "created_at": c.created_at,
            "last_contact": last_date
        }
        enriched_results.append(c_dict)

    if q:
        print(f"--- [DEBUG] 검색어 '{q}' 탐색 결과: {len(enriched_results)}건 발견 ---")
            
    return enriched_results

@app.get("/contacts/map/")
def get_contacts_map(db: Session = Depends(get_db)):
    """
    앱 동기화 전용 초경량 API.
    id와 phone_number만 반환하고 추가 쿼리가 없어서 수천 건도 빠르게 처리됩니다.
    """
    contacts = db.query(models.Contact.id, models.Contact.phone_number).all()
    return [{"id": c.id, "phone_number": c.phone_number} for c in contacts]

@app.get("/contacts/phone/{phone_number}", response_model=schemas.Contact)
def get_contact_by_phone(phone_number: str, db: Session = Depends(get_db)):
    contact = db.query(models.Contact).filter(models.Contact.phone_number == phone_number).first()
    if not contact:
        raise HTTPException(status_code=404, detail="Contact not found")
    return contact

# --- Call Endpoints ---

@app.post("/calls/", response_model=schemas.Call)
def log_call(call: schemas.CallCreate, db: Session = Depends(get_db)):
    db_call = models.Call(**call.dict())
    db.add(db_call)
    db.commit()
    db.refresh(db_call)
    return db_call

@app.post("/calls/bulk/")
def log_calls_bulk(calls: List[schemas.CallCreate], db: Session = Depends(get_db)):
    """Bulk create calls with conflict handling"""
    if not calls: return {"status": "empty"}
    values = [c.dict() for c in calls]
    stmt = pg_insert(models.Call).values(values)
    stmt = stmt.on_conflict_do_nothing(index_elements=['contact_id', 'timestamp'])
    db.execute(stmt)
    db.commit()
    return {"status": "success", "count": len(calls)}

# --- Message Endpoints ---
@app.get("/messages/{contact_id}", response_model=List[schemas.Message])
def get_messages(contact_id: str, db: Session = Depends(get_db)):
    return db.query(models.Message).filter(models.Message.contact_id == contact_id).all()

@app.post("/messages/", response_model=schemas.Message)
def log_message(message: schemas.MessageCreate, db: Session = Depends(get_db)):
    db_message = models.Message(**message.dict())
    db.add(db_message)
    db.commit()
    db.refresh(db_message)
    return db_message

@app.post("/messages/bulk/")
def log_messages_bulk(messages: List[schemas.MessageCreate], db: Session = Depends(get_db)):
    """Bulk create messages with conflict handling"""
    if not messages: return {"status": "empty"}
    values = [m.dict() for m in messages]
    stmt = pg_insert(models.Message).values(values)
    stmt = stmt.on_conflict_do_nothing(index_elements=['contact_id', 'timestamp', 'content'])
    db.execute(stmt)
    db.commit()
    return {"status": "success", "count": len(messages)}

# --- Timeline Endpoint ---

@app.get("/timeline/{contact_id}")
def get_timeline(contact_id: str, db: Session = Depends(get_db)):
    calls = db.query(models.Call).filter(models.Call.contact_id == contact_id).all()
    messages = db.query(models.Message).filter(models.Message.contact_id == contact_id).all()
    
    # Merge and sort by timestamp
    timeline = []
    for call in calls:
        timeline.append({"type": "call", "data": call, "timestamp": call.timestamp})
    for msg in messages:
        timeline.append({"type": "message", "data": msg, "timestamp": msg.timestamp})
        
    timeline.sort(key=lambda x: x["timestamp"], reverse=True)
    return timeline

@app.get("/ai-summary/{contact_id}")
async def get_ai_summary(contact_id: str, db: Session = Depends(get_db)):
    # 1. Fetch entire historical data for the contact
    calls = db.query(models.Call).filter(models.Call.contact_id == contact_id).all()
    messages = db.query(models.Message).filter(models.Message.contact_id == contact_id).all()
    
    # 2. Build history text for analysis
    history_entries = []
    for c in calls:
        history_entries.append(f"[Call] {c.timestamp} (Duration: {c.duration}s)")
    for m in messages:
        history_entries.append(f"[{m.direction}] {m.timestamp}: {m.content}")
    
    # Sort history entries by timestamp string (simple sort)
    history_entries.sort()
    history_text = "\n".join(history_entries)
    
    # 3. Request Gemini to analyze the interaction context
    summary_result = await ai_service.analyze_history(history_text)
    return summary_result

@app.get("/contacts/stats/", response_model=List[dict])
def get_contacts_stats(db: Session = Depends(get_db)):
    """
    Returns contacts with interaction counts and last contact dates.
    Optimized version using SUBQUERIES to prevent N+1 performance issues.
    """
    # 1. Summary of calls per contact
    call_stats = db.query(
        models.Call.contact_id,
        func.count(models.Call.id).label("c_count"),
        func.max(models.Call.timestamp).label("c_last")
    ).group_by(models.Call.contact_id).subquery()

    # 2. Summary of messages per contact
    msg_stats = db.query(
        models.Message.contact_id,
        func.count(models.Message.id).label("m_count"),
        func.max(models.Message.timestamp).label("m_last")
    ).group_by(models.Message.contact_id).subquery()

    # 3. Final Query joining Contacts with grouped stats
    results = db.query(
        models.Contact,
        call_stats.c.c_count,
        call_stats.c.c_last,
        msg_stats.c.m_count,
        msg_stats.c.m_last
    ).outerjoin(call_stats, models.Contact.id == call_stats.c.contact_id) \
     .outerjoin(msg_stats, models.Contact.id == msg_stats.c.contact_id) \
     .all()

    # 4. Summary of synced recordings per contact (for incremental sync check on client)
    all_recordings = db.query(models.Recording.contact_id, models.Recording.original_filename).all()
    rec_filename_map = {}
    for cid, original_name in all_recordings:
        if cid not in rec_filename_map: rec_filename_map[cid] = []
        rec_filename_map[cid].append(original_name)

    stats = []
    for contact, c_cnt, c_last, m_cnt, m_last in results:
        # Calculate derived values
        total_freq = (c_cnt or 0) + (m_cnt or 0)
        
        # Determine the absolute latest interaction date
        last_date = None
        if c_last and m_last: last_date = max(c_last, m_last)
        elif c_last: last_date = c_last
        elif m_last: last_date = m_last

        stats.append({
            "id": contact.id,
            "name": contact.name,
            "phone_number": contact.phone_number,
            "organization": contact.organization,
            "frequency": total_freq,
            "last_contact": last_date.strftime("%Y-%m-%d %H:%M:%S") if last_date else "N/A",
            "last_call_at": c_last.strftime("%Y-%m-%d %H:%M:%S") if c_last else None,
            "last_message_at": m_last.strftime("%Y-%m-%d %H:%M:%S") if m_last else None,
            "synced_recordings": rec_filename_map.get(contact.id, []) # NEW: used by client to skip existing files
        })
        
    return stats

# --- Background AI Task Control ---
# Limit concurrent AI analysis to avoid rate limits (e.g., 2 at a time)
ai_semaphore = asyncio.Semaphore(2)

# --- Background AI Task ---

async def process_recording_ai(recording_id: str, file_path: str):
    """
    Performs AI analysis in the background and updates the recording record.
    Uses a semaphore to prevent overloading the AI API.
    """
    async with ai_semaphore:
        print(f"--- [BG] 시작: AI 분석 진행 중 (ID: {recording_id}) ---")
        try:
            # Small throttle to be extra safe with Free tier
            await asyncio.sleep(1)
            summary, transcription = await ai_service.analyze_call_audio(file_path)
            
            # 새 세션 생성하여 업데이트
            db = SessionLocal()
            try:
                recording = db.query(models.Recording).filter(models.Recording.id == recording_id).first()
                if recording:
                    recording.summary = summary
                    recording.transcription = transcription
                    db.commit()
                    print(f"--- [BG] 완료: AI 분석 업데이트 성공 (ID: {recording_id}) ---")
            except Exception as e:
                print(f"--- [BG] 오류: DB 업데이트 실패 -> {str(e)} ---")
            finally:
                db.close()
        except Exception as e:
            print(f"--- [BG] 오류: AI 분석 프로세스 실패 -> {str(e)} ---")

@app.post("/recordings/upload/")
async def upload_recording(
    background_tasks: BackgroundTasks,
    file: UploadFile = File(...),
    phone_number: Optional[str] = Form(None),
    contact_name: Optional[str] = Form(None),
    timestamp: Optional[str] = Form(None),
    db: Session = Depends(get_db)
):
    """
    Uploads a call recording file, matches it to a contact by phone_number OR contact_name,
    and delegates AI analysis to a background task to prevent timeouts.
    """
    if not phone_number and not contact_name:
        raise HTTPException(status_code=400, detail="phone_number or contact_name is required")

    # 1. Parsing the custom timestamp (Priority: Form > Filename > Now)
    rec_time = datetime.datetime.utcnow()
    
    if timestamp:
        try:
            rec_time = datetime.datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        except:
            pass
    else:
        # A. Try pattern: _YYMMDD_HHMMSS (User's new format)
        sync_match = re.search(r'_(\d{2})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})', file.filename)
        if sync_match:
            try:
                yy, mm, dd, h, m, s = map(int, sync_match.groups())
                year = 2000 + yy # 26 -> 2026
                rec_time = datetime.datetime(year, mm, dd, h, m, s)
                print(f"--- [DEBUG] 신규 형식 날짜 추출: {rec_time} ---")
            except:
                pass
        
        # B. Try pattern: YYYYMMDDHHMMSS (Old format)
        if rec_time.date() == datetime.datetime.utcnow().date(): # Match not found above
            pattern_match = re.search(r'^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})', file.filename)
            if pattern_match:
                try:
                    Y, M, D, h, m, s = map(int, pattern_match.groups())
                    rec_time = datetime.datetime(Y, M, D, h, m, s)
                    print(f"--- [DEBUG] 14자리 날짜 추출: {rec_time} ---")
                except:
                    pass
        
        # C. Fallback: 8 digit date search
        if rec_time.date() == datetime.datetime.utcnow().date():
            date_match = re.search(r'(\d{4})(\d{2})(\d{2})', file.filename)
            if date_match:
                try:
                    year, month, day = map(int, date_match.groups())
                    rec_time = datetime.datetime(year, month, day, 12, 0, 0)
                except:
                    pass

    # 2. Find or Create the contact (phone_number 우선, 없으면 contact_name으로 검색)
    contact = None
    clean_phone = "".join(filter(str.isdigit, phone_number)) if phone_number else ""

    if clean_phone:
        contact = db.query(models.Contact).filter(models.Contact.phone_number == clean_phone).first()
        if not contact and len(clean_phone) >= 10:
            suffix = clean_phone[-10:]
            contact = db.query(models.Contact).filter(models.Contact.phone_number.like(f"%{suffix}")).first()

    # 전화번호로 못 찾으면 이름으로 검색 (파일명 기반)
    if not contact and contact_name:
        contact = db.query(models.Contact).filter(
            models.Contact.name.ilike(f"%{contact_name}%")
        ).first()

    if not contact:
        # 그래도 없으면 자동 생성 (전화번호 없으면 이름 기반으로 생성)
        identifier = clean_phone if clean_phone else (contact_name or "unknown")
        display_name = contact_name if contact_name else f"자동생성({clean_phone[-4:] if clean_phone else '?'})"
        fake_phone = clean_phone if clean_phone else f"NAME_{identifier[:20]}"
        try:
            contact = models.Contact(
                name=display_name,
                phone_number=fake_phone,
                organization="알 수 없음"
            )
            db.add(contact)
            db.commit()
            db.refresh(contact)
        except:
            db.rollback()
            if clean_phone:
                contact = db.query(models.Contact).filter(models.Contact.phone_number == clean_phone).first()
            if not contact and contact_name:
                contact = db.query(models.Contact).filter(models.Contact.name.ilike(f"%{contact_name}%")).first()

    if not contact:
        raise HTTPException(status_code=400, detail="연락처를 찾거나 생성할 수 없습니다.")

    # 3. Save the file (Cloud or Local)
    is_cloud = bool(os.getenv("CLOUDINARY_CLOUD_NAME"))
    file_path = ""

    if is_cloud:
        try:
            print(f"--- [INFO] Uploading {file.filename} to Cloudinary... ---")
            # Upload to Cloudinary with explicit properties
            upload_result = cloudinary.uploader.upload(
                file.file,
                resource_type="auto",
                folder="sales-crm-recordings",
                public_id=f"{clean_phone}_{file.filename.split('.')[0]}_{int(datetime.datetime.now().timestamp())}"
            )
            file_path = upload_result.get("secure_url") # Use public web URL
            print(f"--- [INFO] Cloudinary upload success: {file_path} ---")
        except Exception as e:
            print(f"--- [ERROR] Cloudinary upload failed, falling back to local: {str(e)} ---")
            is_cloud = False

    if not is_cloud:
        # Local Fallback (Temporary storage on Render, permanent on local PC)
        file_path = os.path.join(RECORDINGS_DIR, f"{clean_phone}_{file.filename}")
        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)

    # 4. Check for DUPLICATES before saving and starting AI analysis
    existing = db.query(models.Recording).filter(
        models.Recording.contact_id == contact.id,
        models.Recording.original_filename == file.filename
    ).first()
    
    if existing:
        print(f"--- [INFO] {file.filename} is already synced. Skipping... ---")
        return {"id": existing.id, "status": "skipped", "message": "Already exists"}

    # 5. Create database entry
    recording_entry = models.Recording(
        contact_id=contact.id,
        original_filename=file.filename,
        file_path=file_path,
        timestamp=rec_time,
        summary="AI 분석 진행 중...", # Initialize summary
        transcription="대기 중" # Initialize transcription
    )
    db.add(recording_entry)
    db.commit()
    db.refresh(recording_entry)

    # 6. AI Processing (Asynchronous)
    background_tasks.add_task(process_recording_ai, recording_entry.id, file_path)

    return {
        "id": recording_entry.id,
        "status": "Success",
        "message": "File uploaded. AI analysis started in background."
    }

@app.get("/recordings/{contact_id}", response_model=List[schemas.Recording])
def get_recordings(contact_id: str, db: Session = Depends(get_db)):
    return db.query(models.Recording).filter(models.Recording.contact_id == contact_id).all()

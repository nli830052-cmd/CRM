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
# 참고: 이 작업은 최초 1회만 실행되면 되며 계속 두면 부팅 시간을 잡아먹어 Render가 강제 종료하게 됩니다.
# 이미 인덱스가 생성되었으므로 부팅 속도를 위해 비활성화합니다.
try:
    with engine.connect() as conn:
        conn.execute(text("ALTER TABLE recordings ADD COLUMN IF NOT EXISTS original_filename VARCHAR;"))
        conn.execute(text("ALTER TABLE recordings ADD COLUMN IF NOT EXISTS file_hash VARCHAR;"))
        conn.commit()
except Exception as e:
    print(f"Skipping DB updates: {e}")

# Mount static files
static_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")
if not os.path.exists(static_dir):
    os.makedirs(static_dir)
app.mount("/static", StaticFiles(directory=static_dir), name="static")

@app.get("/")
def read_root():
    return FileResponse(os.path.join(static_dir, "index.html"))

# Recording storage (Move outside 'server/' to prevent Uvicorn reloader loops)
# C:\CRM\server\main.py -> C:\CRM\recordings
RECORDINGS_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "recordings")
if not os.path.exists(RECORDINGS_DIR):
    os.makedirs(RECORDINGS_DIR)

# Cloudinary Setup (For Cloud Deployment)
CLOUDINARY_URL = os.getenv("CLOUDINARY_URL")
if CLOUDINARY_URL:
    # Use standard URL config if available
    import cloudinary
    cloudinary.config(cloudinary_url=CLOUDINARY_URL)
    print("--- [INFO] Cloudinary initialized via URL ---")
else:
    # Fallback to individual keys
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
        print("--- [INFO] Cloudinary initialized via keys ---")
    else:
        print("--- [WARNING] Cloudinary not configured. Using local storage at:", RECORDINGS_DIR)

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

@app.get("/contacts/{contact_id}", response_model=schemas.Contact)
def get_contact(contact_id: str, db: Session = Depends(get_db)):
    contact = db.query(models.Contact).filter(models.Contact.id == contact_id).first()
    if not contact:
        raise HTTPException(status_code=404, detail="Contact not found")
    return contact

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

def ensure_contact_id(db: Session, contact_id: Optional[str], phone_number: Optional[str]):
    """Helper to resolve or create a contact by phone if ID is missing."""
    if contact_id: return contact_id
    if not phone_number: return None
    
    clean_phone = "".join(filter(str.isdigit, phone_number))
    if not clean_phone: return None
    
    contact = db.query(models.Contact).filter(models.Contact.phone_number == clean_phone).first()
    if not contact:
        contact = models.Contact(
            name=f"자동생성({clean_phone[-4:]})",
            phone_number=clean_phone,
            organization="알 수 없음"
        )
        db.add(contact)
        db.commit()
        db.refresh(contact)
    return contact.id

@app.post("/calls/bulk/")
def log_calls_bulk(calls: List[schemas.CallCreate], db: Session = Depends(get_db)):
    """Bulk create calls with automatic contact resolution"""
    if not calls: return {"status": "empty"}
    
    processed_calls = []
    for c in calls:
        cid = ensure_contact_id(db, c.contact_id, c.phone_number)
        if cid:
            call_dict = c.dict()
            call_dict['contact_id'] = cid
            call_dict.pop('phone_number', None)
            processed_calls.append(call_dict)
            
    if not processed_calls: return {"status": "no_valid_contacts"}

    stmt = pg_insert(models.Call).values(processed_calls)
    stmt = stmt.on_conflict_do_nothing(index_elements=['contact_id', 'timestamp'])
    db.execute(stmt)
    db.commit()
    return {"status": "success", "count": len(processed_calls)}

# --- Message Endpoints ---
@app.get("/messages/{contact_id}", response_model=List[schemas.Message])
def get_messages(contact_id: str, db: Session = Depends(get_db)):
    return db.query(models.Message).filter(models.Message.contact_id == contact_id).all()

@app.post("/messages/bulk/")
def log_messages_bulk(messages: List[schemas.MessageCreate], db: Session = Depends(get_db)):
    """Bulk create messages with automatic contact resolution"""
    if not messages: return {"status": "empty"}
    
    processed_msgs = []
    for m in messages:
        cid = ensure_contact_id(db, m.contact_id, m.phone_number)
        if cid:
            msg_dict = m.dict()
            msg_dict['contact_id'] = cid
            msg_dict.pop('phone_number', None)
            processed_msgs.append(msg_dict)
            
    if not processed_msgs: return {"status": "no_valid_contacts"}

    stmt = pg_insert(models.Message).values(processed_msgs)
    stmt = stmt.on_conflict_do_nothing(index_elements=['contact_id', 'timestamp', 'content'])
    db.execute(stmt)
    db.commit()
    return {"status": "success", "count": len(processed_msgs)}

# --- Timeline Endpoint ---

@app.get("/timeline/all/")
def get_global_timeline(limit: int = 200, db: Session = Depends(get_db)):
    """Unified timeline of all interactions: Calls, Messages, and AI-Analyzed Recordings."""
    # 1. Fetch latest raw records
    calls = db.query(models.Call, models.Contact.name, models.Contact.phone_number) \
              .join(models.Contact, models.Call.contact_id == models.Contact.id) \
              .order_by(models.Call.timestamp.desc()).limit(limit).all()
              
    messages = db.query(models.Message, models.Contact.name, models.Contact.phone_number) \
                 .join(models.Contact, models.Message.contact_id == models.Contact.id) \
                 .order_by(models.Message.timestamp.desc()).limit(limit).all()

    recordings = db.query(models.Recording, models.Contact.name, models.Contact.phone_number) \
                   .join(models.Contact, models.Recording.contact_id == models.Contact.id) \
                   .order_by(models.Recording.timestamp.desc()).limit(limit).all()
    
    # 2. Merge and sort
    combined = []
    for c, name, phone in calls:
        combined.append({
            "type": "call",
            "contact_id": c.contact_id,
            "name": name,
            "phone_number": phone,
            "timestamp": c.timestamp.strftime("%Y-%m-%d %H:%M:%S"),
            "data": { "direction": c.direction, "duration": c.duration }
        })
    for m, name, phone in messages:
        combined.append({
            "type": "message",
            "contact_id": m.contact_id,
            "name": name,
            "phone_number": phone,
            "timestamp": m.timestamp.strftime("%Y-%m-%d %H:%M:%S"),
            "data": { "direction": m.direction, "content": m.content }
        })
    for r, name, phone in recordings:
        combined.append({
            "type": "recording",
            "contact_id": r.contact_id,
            "name": name,
            "phone_number": phone,
            "timestamp": r.timestamp.strftime("%Y-%m-%d %H:%M:%S"),
            "data": { "summary": r.summary, "file_path": r.file_path }
        })
        
    combined.sort(key=lambda x: x["timestamp"], reverse=True)
    return combined[:limit]

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
async def get_ai_summary(contact_id: str, refresh: bool = False, db: Session = Depends(get_db)):
    # 1. Check for existing cached summary
    cached = db.query(models.AISummary).filter(models.AISummary.contact_id == contact_id).first()
    
    # 2. Get latest interaction timestamp
    last_call = db.query(func.max(models.Call.timestamp)).filter(models.Call.contact_id == contact_id).scalar()
    last_msg = db.query(func.max(models.Message.timestamp)).filter(models.Message.contact_id == contact_id).scalar()
    last_rec = db.query(func.max(models.Recording.timestamp)).filter(models.Recording.contact_id == contact_id).scalar()
    
    dates = [d for d in [last_call, last_msg, last_rec] if d is not None]
    latest_interaction = max(dates) if dates else datetime.datetime.min
    
    # Cache Check (Skip if refresh=True for LangSmith testing)
    if not refresh and cached and cached.updated_at >= latest_interaction:
        return {"summary": cached.summary, "status": cached.status, "next_action": cached.next_action}
    
    # 3. Fetch data if cache is missing or stale
    calls = db.query(models.Call).filter(models.Call.contact_id == contact_id).all()
    messages = db.query(models.Message).filter(models.Message.contact_id == contact_id).all()
    recordings = db.query(models.Recording).filter(models.Recording.contact_id == contact_id).all()
    
    # 2. Build history text for analysis
    history_entries = []
    for c in calls: history_entries.append(f"[CallLog] {c.timestamp} ({'수신' if c.direction=='IN' else '발신'})")
    for m in messages: history_entries.append(f"[{'SMS-수신' if m.direction=='INBOX' else 'SMS-발신'}] {m.timestamp}: {m.content}")
    for r in recordings:
        if r.summary and "분석 진행 중" not in r.summary:
            history_entries.append(f"[CallAnalysis] {r.timestamp}: {r.summary}")
    
    history_entries.sort()
    
    if not history_entries:
        return {"summary": "아직 대화 기록이 충분하지 않습니다.", "next_action": "기록 대기 중", "status": "empty"}

    history_text = "\n".join(history_entries)
    
    # 3. Request Gemini to analyze the interaction context (Regulated by sync semaphore)
    async with ai_semaphore:
        summary_result = await ai_service.analyze_history(history_text)
    
    # 4. Save Cache
    if not cached:
        cached = models.AISummary(contact_id=contact_id)
        db.add(cached)
    
    # Ensure values are strings before saving to DB columns (Prevents dict conversion errors)
    sum_val = summary_result.get("summary", "")
    if isinstance(sum_val, dict):
        sum_val = "\n".join([f"{k} {v}" for k, v in sum_val.items()])
    
    action_val = summary_result.get("next_action", "")
    if isinstance(action_val, dict):
        action_val = "\n".join([f"{k} {v}" for k, v in action_val.items()])

    cached.summary = str(sum_val)
    cached.status = str(summary_result.get("status", "WARM"))
    cached.next_action = str(action_val)
    cached.updated_at = datetime.datetime.utcnow()
    db.commit()
    
    # Return normalized result to UI
    summary_result["summary"] = sum_val
    summary_result["next_action"] = action_val
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

    # 3. Summary of recordings per contact
    rec_stats = db.query(
        models.Recording.contact_id,
        func.max(models.Recording.timestamp).label("r_last"),
        func.array_agg(models.Recording.original_filename).label("r_files")
    ).group_by(models.Recording.contact_id).subquery()

    # 4. Final Query joining Contacts with all stats
    results = db.query(
        models.Contact,
        call_stats.c.c_count,
        call_stats.c.c_last,
        msg_stats.c.m_count,
        msg_stats.c.m_last,
        rec_stats.c.r_last,
        rec_stats.c.r_files
    ).outerjoin(call_stats, models.Contact.id == call_stats.c.contact_id) \
     .outerjoin(msg_stats, models.Contact.id == msg_stats.c.contact_id) \
     .outerjoin(rec_stats, models.Contact.id == rec_stats.c.contact_id) \
     .all()

    stats = []
    for contact, c_cnt, c_last, m_cnt, m_last, r_last, r_files in results:
        # Calculate derived values
        total_freq = (c_cnt or 0) + (m_cnt or 0)
        
        # Determine the absolute latest interaction date (Calls, Messages, OR Recordings)
        dates = [d for d in [c_last, m_last, r_last] if d is not None]
        last_date = max(dates) if dates else None

        stats.append({
            "id": contact.id,
            "name": contact.name,
            "phone_number": contact.phone_number,
            "organization": contact.organization,
            "frequency": total_freq,
            "last_contact": last_date.strftime("%Y-%m-%d %H:%M:%S") if last_date else "N/A",
            "last_call_at": c_last.strftime("%Y-%m-%d %H:%M:%S") if c_last else None,
            "last_message_at": m_last.strftime("%Y-%m-%d %H:%M:%S") if m_last else None,
            "synced_recordings": r_files or [] 
        })
        
    # Sort by last_contact descending (Newest first)
    stats.sort(key=lambda x: x["last_contact"] if x["last_contact"] != "N/A" else "", reverse=True)
    
    return stats

# Limit concurrent AI analysis to avoid rate limits (Stricter: 1 at a time for Free tier)
ai_semaphore = asyncio.Semaphore(1)

# --- Background AI Task ---

async def process_recording_ai(recording_id: str, file_path: str):
    """
    Performs AI analysis in the background and updates the recording record.
    Uses a semaphore to prevent overloading the AI API.
    """
    async with ai_semaphore:
        print(f"--- [BG] 시작: AI 분석 진행 중 (ID: {recording_id}) ---")
        try:
            # Throttle to be safe with Free tier (3 seconds delay between tasks)
            await asyncio.sleep(3)
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

import hashlib

def calculate_md5(file_obj):
    """Calculates MD5 hash of a file-like object in chunks."""
    md5 = hashlib.md5()
    # Reset file pointer to beginning
    file_obj.seek(0)
    for chunk in iter(lambda: file_obj.read(4096), b""):
        md5.update(chunk)
    # Reset file pointer for subsequent use
    file_obj.seek(0)
    return md5.hexdigest()

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
    Uploads a call recording file with CONTENT-BASED duplicate detection.
    """
    # 0. Content Integrity Check (Fingerprinting)
    file_hash = calculate_md5(file.file)
    
    # Check for DUPLICATES by HASH (Absolute duplicate prevention)
    existing_by_hash = db.query(models.Recording).filter(models.Recording.file_hash == file_hash).first()
    if existing_by_hash:
        print(f"--- [INFO] Found duplicate recording by CONTENT (Hash: {file_hash}). Skipping... ---")
        return {"id": existing_by_hash.id, "status": "skipped", "message": "Content already exists"}

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
        file_hash=file_hash, # Fingerprint stored
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

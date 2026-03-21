import asyncio
import os
import re
import datetime
from sqlalchemy.orm import Session
from server.database import SessionLocal
from server import models, ai_service

async def reprocess_all():
    print("--- [REPROCESS] AI 분석 누락 항목 복구 시작 ---")
    db = SessionLocal()
    try:
        # 1. 먼저 날짜부터 전수 교정 (파일명 기반)
        recordings = db.query(models.Recording).all()
        date_fix_count = 0
        for rec in recordings:
            filename = os.path.basename(rec.file_path)
            # 패턴: _YYMMDD_HHMMSS
            match = re.search(r'_(\d{2})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})', filename)
            if match:
                yy, mm, dd, h, m, s = map(int, match.groups())
                rec.timestamp = datetime.datetime(2000 + yy, mm, dd, h, m, s)
                date_fix_count += 1
        db.commit()
        print(f"> {date_fix_count}개의 데이터 날짜를 파일명 기준으로 교정했습니다.")

        # 2. 분석 안 된 항목들 추출
        targets = db.query(models.Recording).filter(
            (models.Recording.summary == "AI 분석 진행 중...") | 
            (models.Recording.summary.is_(None)) |
            (models.Recording.summary == "")
        ).all()

        print(f"> 총 {len(targets)}개의 파일이 분석 대기 중입니다.")
        
        # 3. 순차 분석 (Semaphore 적용)
        semaphore = asyncio.Semaphore(2)

        async def analyze_task(rec):
            async with semaphore:
                print(f"[JOB] 분석 시작: {rec.id} ({os.path.basename(rec.file_path)})")
                try:
                    summary, transcription = await ai_service.analyze_call_audio(rec.file_path)
                    
                    # 별도 세션으로 업데이트 (안정성)
                    inner_db = SessionLocal()
                    try:
                        r = inner_db.query(models.Recording).filter(models.Recording.id == rec.id).first()
                        if r:
                            r.summary = summary
                            r.transcription = transcription
                            inner_db.commit()
                            print(f"[OK] 분석 완료: {rec.id}")
                    finally:
                        inner_db.close()
                except Exception as e:
                    print(f"[ERR] 분석 실패 ({rec.id}): {str(e)}")

        # 병렬 실행 (하지만 세마포어로 인해 실제로는 2개씩)
        tasks = [analyze_task(target) for target in targets]
        await asyncio.gather(*tasks)

        print("--- [REPROCESS] 모든 복구 작업이 완료되었습니다! ---")
    except Exception as e:
        print(f"❌ 치명적 오류: {str(e)}")
    finally:
        db.close()

if __name__ == "__main__":
    asyncio.run(reprocess_all())

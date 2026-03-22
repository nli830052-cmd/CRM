import os
import google.generativeai as genai
from dotenv import load_dotenv
import json
import requests
import tempfile
from langsmith import traceable

load_dotenv()

# Configure Gemini API
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY")
genai.configure(api_key=GEMINI_API_KEY)

# Use the latest Gemini 2.0 Flash
model = genai.GenerativeModel('models/gemini-2.0-flash')

# Monitor AI sessions with LangSmith
@traceable
async def analyze_history(history_text: str):
    if not history_text:
        return {
            "summary": "상대 고객과 주고받은 데이터가 없습니다.",
            "status": "UNKNOWN",
            "next_action": "첫 연락을 시도해 보세요."
        }

    prompt = f"""
    당신은 숙련된 영업 비서입니다. 아래 고객과의 모든 대화 이력을 날짜별로 빠짐없이 요약하여 보고하세요.
    [데이터]: {history_text}
    [요구사항]:
    1. summary 필드는 반드시 【날짜별 요약】 이라는 헤더로 시작하세요.
    2. 모든 대화 날짜를 하나씩 나열하되, 반드시 **가장 최근(최신) 날짜가 맨 위에 오도록 역순(내림차순)으로 정렬**하세요.
    3. 각 날짜별로 무슨 일이 있었는지 1~2문장으로 핵심을 요약하세요.
    4. 다른 부수적인 분석(불만사항, 업무현황 등)은 모두 제외하고 오직 날짜별 타임라인 히스토리에만 집중하세요.
    5. 모든 항목 앞에 불렛 기호(•)를 사용하고 가독성 좋게 줄바꿈하세요.
    5. 별표(*)와 같은 특수문자는 절대 사용하지 마세요.
    형식: JSON {{"summary": "...", "status": "진행 상태", "next_action": "추천 할 일"}}
    """
    
    try:
        response = model.generate_content(prompt)
        text = response.text.strip()
        if "```json" in text:
            text = text.split("```json")[1].split("```")[0].strip()
        return json.loads(text)
    except:
        return {"summary": "AI 분석 오류", "status": "ERROR", "next_action": "로그 확인"}

@traceable
async def analyze_call_audio(file_path: str):
    """
    오디오 분석: 로컬 파일 및 URL(Cloudinary) 지원
    """
    temp_local_path = None
    is_url = file_path.startswith("http")
    path_to_upload = file_path

    try:
        # 1. URL인 경우 임시 다운로드
        if is_url:
            print(f"--- [INFO] AI 분석용 오디오 다운로드 중: {file_path} ---")
            ext = ".m4a"
            if ".mp3" in file_path.lower(): ext = ".mp3"
            elif ".wav" in file_path.lower(): ext = ".wav"
            
            tfile = tempfile.NamedTemporaryFile(delete=False, suffix=ext)
            resp = requests.get(file_path)
            tfile.write(resp.content)
            tfile.close()
            temp_local_path = tfile.name
            path_to_upload = temp_local_path
        else:
            if not os.path.exists(file_path):
                return "파일 없음", "분석 실패"

        # 2. Gemini에 파일 업로드 및 분석
        sample_file = genai.upload_file(path=path_to_upload, display_name="Call Recording")
        prompt = """이 통화 녹음을 분석하여 다음 형식을 엄격히 지켜 답변하세요:
        [전사] (각 문장은 반드시 '상대방:' 또는 '나:'로 시작하는 대화체로 작성하세요.)
        [요약] 
        【고객문의】: 
        (여기에 고객의 문의 사항을 불렛 기호(•)를 써서 나열하세요.)
        【나의 응대】: 
        (여기에 나의 답변 내용을 불렛 기호(•)를 써서 나열하세요.)
        (주의: 별표(*) 기호는 어떤 상황에서도 절대 사용하지 마세요.)
        [할일] 
        (이후 조치 사항)
        """
        response = model.generate_content([prompt, sample_file])
        
        result = response.text
        transcription = "전사 실패"
        summary = "요약 실패"
        
        if "[전사]" in result and "[요약]" in result:
            parts = result.split("[요약]")
            transcription = parts[0].replace("[전사]", "").strip()
            summary = parts[1].strip()
            
        return summary, transcription
        
    except Exception as e:
        print(f"AI Audio Error: {str(e)}")
        return f"오류: {str(e)}", "분석 불가"
    finally:
        if temp_local_path and os.path.exists(temp_local_path):
            os.remove(temp_local_path)

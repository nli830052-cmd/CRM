import os
import google.generativeai as genai
from dotenv import load_dotenv
import json
import requests
import tempfile
import urllib.parse

load_dotenv()

# Configure Gemini API
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY")
genai.configure(api_key=GEMINI_API_KEY)

# Use the latest Gemini 2.0 Flash
model = genai.GenerativeModel('models/gemini-2.0-flash')

async def analyze_history(history_text: str):
    if not history_text:
        return {
            "summary": "상대 고객과 주고받은 데이터가 없습니다.",
            "status": "UNKNOWN",
            "next_action": "첫 연락을 시도해 보세요."
        }

    prompt = f"""
    당신은 숙련된 영업 관리자입니다. 아래 고객과의 대화 이력을 분석하세요.
    [데이터]: {history_text}
    [요구사항]:
    1. summary 필드는 반드시 다음 구조를 포함하여 구조화된 보고서 형태로 작성하세요 (줄바꿈 포함): 
       고객문의: (주요 니즈 및 문의사항)
       나의 응대: (현재까지의 대응 내용)
       결론: (현재의 핵심 상태)
    2. 수치나 단계가 포함된 내용은 반드시 줄바꿈을 사용하여 가독성을 높이세요.
    3. next_action 필드는 '1. ', '2. ' 처럼 숫자를 붙여 한 줄씩 명확한 단계별 행동 지침으로 작성하세요.
    4. 모든 내용은 한국어로 작성하세요.
    형식: JSON {{"summary": "...", "status": "...", "next_action": "..."}}
    """
    
    try:
        response = model.generate_content(prompt)
        text = response.text.strip()
        if "```json" in text:
            text = text.split("```json")[1].split("```")[0].strip()
        return json.loads(text)
    except:
        return {"summary": "AI 분석 오류", "status": "ERROR", "next_action": "로그 확인"}

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
        [전사] (중요: 각 문장은 반드시 '상대방:' 또는 '나:'로 시작하는 대화체로 한 줄씩 작성하세요.)
        [요약] 
        고객문의: (고객이 요구하거나 궁금해한 사항)
        나의 응대: (나의 답변 및 대응 내용)
        결론: (통화의 최종 결과 및 합의된 내용)
        (참고: 숫자나 나열이 필요한 경우 리스트 형태로 줄바꿈을 적극 활용하세요.)
        [할일] (이후 조치해야 할 구체적인 사항)
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

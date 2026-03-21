import os
import google.generativeai as genai
from dotenv import load_dotenv
import json

load_dotenv()

# Configure Gemini API
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY")
genai.configure(api_key=GEMINI_API_KEY)

# Use the latest Gemini 2.0 Flash (as supported by user's API key)
model = genai.GenerativeModel('models/gemini-2.0-flash')

async def analyze_history(history_text: str):
    """
    Analyzes customer interaction history data and returns summary and action recommendations.
    """
    if not history_text:
        return {
            "summary": "상대 고객과 주고받은 데이터가 없습니다.",
            "status": "UNKNOWN",
            "next_action": "첫 연락을 시도해 보세요."
        }

    prompt = f"""
    당신은 전문적인 영업 관리자이자 데이터 분석가입니다. 
    아래 고객과의 타임라인(통화 및 문자 이력)을 분석하여 다음 세 가지 항목을 도출하세요:
    
    1. 대화 요약: 현재까지의 영업 진행 상황을 2-3문장으로 명확히 요약.
    2. 고객 상태: HOT(매우 관심), WARM(보통 관심), COLD(관심 낮음) 중 하나 선택.
    3. 다음 추천 행동: 영업 성공을 위해 다음에 취해야 할 구체적인 행동 제안.
    
    데이터:
    {history_text}
    
    반드시 아래 JSON 형식으로만 답변하세요 (절대 다른 설명 붙이지 마세요):
    {{
        "summary": "...",
        "status": "...",
        "next_action": "..."
    }}
    """
    
    try:
        response = model.generate_content(prompt)
        # Parse result (Gemini often returns markdown blocks, clean up if needed)
        result_text = response.text.strip()
        if result_text.startswith("```json"):
            result_text = result_text.replace("```json", "").replace("```", "").strip()
            
        return json.loads(result_text)
    except Exception as e:
        print(f"!!! AI Analysis Error !!!: {str(e)}")
        # Diagnostic: List available models if 404
        try:
            available = [m.name for m in genai.list_models() if 'generateContent' in m.supported_generation_methods]
            print(f"Available models for your API key: {available}")
        except:
            pass

        return {
            "summary": "AI 분석 중 오류가 발생했습니다. 서버 로그를 확인해 주세요.",
            "status": "ERROR",
            "next_action": "모델 명칭 또는 API 키 권한을 확인해야 합니다."
        }

async def analyze_call_audio(file_path: str):
    """
    Analyzes call recording audio file using Gemini 1.5 Multimodal capabilities.
    """
    if not os.path.exists(file_path):
        return "파일을 찾을 수 없습니다.", "인식 실패"

    try:
        # 1. Upload file to Google Gemini API
        sample_file = genai.upload_file(path=file_path, display_name="Call Recording")
        print(f"Uploaded file '{sample_file.display_name}' as: {sample_file.uri}")

        # 2. Prompt for both transcription and summary
        prompt = """
        당신은 유능한 비서이자 영업 분석가입니다. 
        첨부된 통화 녹음 파일을 분석하여 다음 작업을 수행하세요:
        1. 전체 대화 내용 전사 (Transcription)
        2. 통화 핵심 내용 요약 (2-3문장)
        3. 주요 고객 요구 사항 및 향후 약속/할 일 정리
        
        한국어로 답변해 주세요.
        형식:
        [전사]
        ...
        [요약]
        ...
        [할일]
        ...
        """
        
        response = model.generate_content([prompt, sample_file])
        
        result = response.text
        # Simple extraction logic (can be improved)
        transcription = "전사 데이터 없음"
        summary = "요약 실패"
        
        if "[전사]" in result and "[요약]" in result:
            parts = result.split("[요약]")
            transcription = parts[0].replace("[전사]", "").strip()
            summary = parts[1].strip()
            
        return summary, transcription
        
    except Exception as e:
        print(f"Audio AI Analysis Error: {str(e)}")
        return f"분석 중 오류 발생: {str(e)}", "분석 불가"

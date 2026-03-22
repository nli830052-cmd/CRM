import os
import psycopg2
from sqlalchemy import create_engine
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
from dotenv import load_dotenv

# -----------------------------------------------------------------------
# Windows 한글 경로 인코딩 버그 방지
# libpq(psycopg2 내부 C라이브러리)가 pgpass 파일을 탐색할 때
# 한글 Windows 사용자 폴더 경로(예: C:\Users\홍길동\...)를
# UTF-8로 읽으려다 UnicodeDecodeError가 발생하는 것을 차단합니다.
# NUL은 Windows의 /dev/null 에 해당합니다.
os.environ["PGPASSFILE"] = "NUL"
os.environ["PGSSLCERT"] = ""
os.environ["PGSSLKEY"] = ""
# -----------------------------------------------------------------------

# .env 파일 경로를 명시적으로 지정 (server/.env)
_env_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
load_dotenv(dotenv_path=_env_path, encoding="utf-8")

# 연결 파라미터 개별 추출 (URL 문자열 방식 대신 개별 파라미터 방식 사용)
_DB_HOST = os.getenv("DB_HOST", "127.0.0.1")
_DB_PORT = int(os.getenv("DB_PORT", "5432"))
_DB_NAME = os.getenv("DB_NAME", "salesmind")
_DB_USER = os.getenv("DB_USER", "postgres")
_DB_PASS = os.getenv("DB_PASS", "1234")

# DATABASE_URL이 있으면 파싱해서 사용
_DATABASE_URL = os.getenv("DATABASE_URL", "")
if _DATABASE_URL:
    from urllib.parse import urlparse
    _parsed = urlparse(_DATABASE_URL)
    _DB_HOST = _parsed.hostname or _DB_HOST
    _DB_PORT = _parsed.port or _DB_PORT
    _DB_NAME = _parsed.path.lstrip("/") or _DB_NAME
    _DB_USER = _parsed.username or _DB_USER
    _DB_PASS = _parsed.password or _DB_PASS


def _get_connection():
    """
    psycopg2 연결 생성 함수.
    URL 방식 대신 개별 파라미터로 연결하여 Windows 인코딩 문제를 우회합니다.
    """
    return psycopg2.connect(
        host=str(_DB_HOST),
        port=int(_DB_PORT),
        dbname=str(_DB_NAME),
        user=str(_DB_USER),
        password=str(_DB_PASS),
        client_encoding="utf8",
        passfile="NUL",  # pgpass 파일 탐색 완전 비활성화
    )


# SQLAlchemy 엔진 생성 (커넥션 풀 최적화: Supabase 무료 티어 제한 대응)
engine = create_engine(
    "postgresql+psycopg2://",
    creator=_get_connection,
    pool_pre_ping=True,   # 연결 유효성 매번 확인
    pool_size=5,          # 상시 유지되는 연결 수 (기존 20에서 축소)
    max_overflow=10,      # 필요할 때 추가로 열 수 있는 최대 연결 (기존 50에서 축소)
    pool_timeout=30       # 연결 확보 대기 시간 (기존 60에서 축소)
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

Base = declarative_base()


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

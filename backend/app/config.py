import os
from typing import Optional

from dotenv import load_dotenv

# Every module that needs an env var imports `settings` from here rather than
# reading os.environ directly -- that guarantees .env is loaded exactly once,
# regardless of which module happens to get imported first.
load_dotenv()


class Settings:
    database_url: str = os.environ.get("DATABASE_URL", "sqlite:///./scamguard.db")
    aws_region: str = os.environ.get("AWS_REGION", "us-east-1")
    bedrock_model_id: Optional[str] = os.environ.get("BEDROCK_MODEL_ID")
    safe_browsing_api_key: Optional[str] = os.environ.get("SAFE_BROWSING_API_KEY")
    telegram_bot_token: Optional[str] = os.environ.get("TELEGRAM_BOT_TOKEN")


settings = Settings()

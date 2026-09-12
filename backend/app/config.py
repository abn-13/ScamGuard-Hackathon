import os
from typing import Optional

from dotenv import load_dotenv

# Every module that needs an env var imports `settings` from here rather than
# reading os.environ directly -- that guarantees .env is loaded exactly once,
# regardless of which module happens to get imported first.
load_dotenv()


def _env_flag(name: str, default: bool = False) -> bool:
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


class Settings:
    database_url: str = os.environ.get("DATABASE_URL", "sqlite:///./scamguard.db")
    aws_region: str = os.environ.get("AWS_REGION", "us-east-1")
    bedrock_model_id: Optional[str] = os.environ.get("BEDROCK_MODEL_ID")
    safe_browsing_api_key: Optional[str] = os.environ.get("SAFE_BROWSING_API_KEY")
    brave_search_api_key: Optional[str] = os.environ.get("BRAVE_SEARCH_API_KEY")
    domain_intelligence_enabled: bool = _env_flag("DOMAIN_INTELLIGENCE_ENABLED")
    certspotter_api_token: Optional[str] = os.environ.get("CERTSPOTTER_API_TOKEN")
    telegram_bot_token: Optional[str] = os.environ.get("TELEGRAM_BOT_TOKEN")
    # Match exact receiving-service IDs; this is a filter, not proof of origin.
    trusted_authserv_ids: tuple[str, ...] = tuple(
        value.strip().lower()
        for value in os.environ.get("TRUSTED_AUTHSERV_IDS", "mx.google.com").split(",")
        if value.strip()
    )


settings = Settings()

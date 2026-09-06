import os

from dotenv import load_dotenv

load_dotenv()


class Settings:
    database_url: str = os.environ.get("DATABASE_URL", "sqlite:///./scamguard.db")
    aws_region: str = os.environ.get("AWS_REGION", "us-east-1")


settings = Settings()

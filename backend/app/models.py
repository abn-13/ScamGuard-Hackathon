from datetime import datetime, timezone
from enum import Enum
from typing import Optional

from sqlmodel import Field, SQLModel


class RiskLevel(str, Enum):
    low = "low"
    medium = "medium"
    high = "high"


class MessageSource(str, Enum):
    sms = "sms"
    email = "email"


class User(SQLModel, table=True):
    """The protected person -- whoever's SMS/Gmail is being read on-device."""

    id: Optional[int] = Field(default=None, primary_key=True)
    username: str = Field(index=True, unique=True)
    phone_number: str
    gmail: str
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class FamilyMember(SQLModel, table=True):
    """A guardian to alert when one of a user's messages is flagged."""

    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(foreign_key="user.id")
    username: str
    phone_number: str
    # Populated once they've messaged the bot with telegram_link_code and it's been
    # matched (see app/telegram_link.py) -- registration itself doesn't require them to
    # have done that yet.
    telegram_chat_id: Optional[str] = None
    # Set at creation (see main.py::create_family_member) unless telegram_chat_id was
    # already provided directly. Cleared once linking succeeds -- a spent code shouldn't
    # match a future, unrelated /start message reusing the same text by coincidence.
    telegram_link_code: Optional[str] = Field(default=None, index=True)
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class Message(SQLModel, table=True):
    """One SMS or email that's been (or is being) checked. Doubles as the audit log."""

    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(foreign_key="user.id")
    source: MessageSource
    sender: str
    subject: Optional[str] = None
    body_text: str
    is_known_sender: bool = False
    received_at: datetime
    risk_level: Optional[RiskLevel] = None
    reason: Optional[str] = None
    checked_at: Optional[datetime] = None

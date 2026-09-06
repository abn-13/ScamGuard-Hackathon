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
    display_name: str
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class FamilyMember(SQLModel, table=True):
    """A guardian to alert when one of a user's messages is flagged."""

    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(foreign_key="user.id")
    display_name: str
    telegram_chat_id: Optional[str] = None
    phone_number: Optional[str] = None
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

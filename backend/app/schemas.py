from datetime import datetime
from typing import Optional

from pydantic import BaseModel

from .models import MessageSource, RiskLevel


class IncomingMessage(BaseModel):
    """What the Android app sends for every SMS/email it wants checked."""

    user_id: int
    source: MessageSource
    sender: str
    body_text: str
    subject: Optional[str] = None
    # Computed on-device (Contacts lookup for SMS, Gmail history/People API for email) --
    # the backend has no access to either, so this arrives as a plain fact, not a tool call.
    is_known_sender: bool = False
    received_at: datetime


class CheckResponse(BaseModel):
    message_id: int
    risk_level: RiskLevel
    reason: str


class UserCreate(BaseModel):
    display_name: str


class FamilyMemberCreate(BaseModel):
    user_id: int
    display_name: str
    telegram_chat_id: Optional[str] = None
    phone_number: Optional[str] = None

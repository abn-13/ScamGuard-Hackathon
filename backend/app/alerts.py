import requests
from sqlmodel import Session, select

from .config import settings
from .models import FamilyMember

TELEGRAM_API = "https://api.telegram.org/bot{token}/sendMessage"


def send_family_alert(session: Session, user_id: int, sender: str, reason: str) -> None:
    """Notify every registered family member for this user about a flagged message."""
    members = session.exec(
        select(FamilyMember).where(FamilyMember.user_id == user_id)
    ).all()
    if not members:
        return

    token = settings.telegram_bot_token
    text = f"⚠️ ScamGuard flagged a message.\nFrom: {sender}\nWhy: {reason}"

    for member in members:
        if not member.telegram_chat_id:
            continue
        if not token:
            # TODO(Task 2 owner): remove this stub once TELEGRAM_BOT_TOKEN is set in .env.
            print(f"[alerts stub] would notify {member.display_name}: {text}")
            continue
        requests.post(
            TELEGRAM_API.format(token=token),
            json={"chat_id": member.telegram_chat_id, "text": text},
            timeout=10,
        )

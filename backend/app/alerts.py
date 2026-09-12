import requests
from sqlmodel import Session, select

from .config import settings
from .models import FamilyMember

TELEGRAM_API = "https://api.telegram.org/bot{token}/sendMessage"


def send_family_alert(session: Session, user_id: int, sender: str, reason: str) -> None:
    """Notify every registered family member for this user about a flagged message.

    Best-effort: a delivery failure here (bad token, no network, Telegram down) should
    never blow up the /check-message response -- the message is already judged and
    logged by the time this runs, that result matters more than the notification.
    """
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
            print(f"[alerts stub] would notify {member.username}: {text}")
            continue
        try:
            response = requests.post(
                TELEGRAM_API.format(token=token),
                json={"chat_id": member.telegram_chat_id, "text": text},
                timeout=10,
            )
            response.raise_for_status()
        except requests.exceptions.RequestException as exc:
            # Log and move on -- don't let one bad chat_id/token/network blip 500 the
            # whole /check-message call, and don't stop notifying the other members.
            print(f"[alerts] failed to notify {member.username}: {exc}")

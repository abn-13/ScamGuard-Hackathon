from sqlmodel import Session, select

from .models import FamilyMember
from .telegram_link import send_telegram_message


def send_family_alert(session: Session, user_id: int, sender: str, reason: str) -> None:
    """Notify every registered family member for this user about a flagged message.

    Best-effort: a delivery failure here (bad token, no network, Telegram down) should
    never blow up the /check-message response -- the message is already judged and
    logged by the time this runs, that result matters more than the notification. Members
    with no telegram_chat_id yet (never linked -- see app/telegram_link.py) are silently
    skipped; the on-device notification and guardian SMS (Android side) don't depend on
    this at all.
    """
    members = session.exec(
        select(FamilyMember).where(FamilyMember.user_id == user_id)
    ).all()
    if not members:
        return

    text = f"⚠️ ScamGuard flagged a message.\nFrom: {sender}\nWhy: {reason}"

    for member in members:
        if not member.telegram_chat_id:
            continue
        send_telegram_message(member.telegram_chat_id, text)

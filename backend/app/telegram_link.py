"""Links a FamilyMember's Telegram chat_id to their record, and sends them messages.

No public HTTPS endpoint exists for this backend (local dev, no webhook), so linking
can't use Telegram's push-style webhook mechanism. Instead: at registration, each family
member gets a short random code; they send it (or tap a t.me deep link that pre-fills
"/start <code>") to the bot; the app then calls POST /family-members/{id}/link-telegram
on demand, which pulls Telegram's `getUpdates` once, matches the code, and saves the
sender's chat_id. This is deliberately on-demand (triggered by one request) rather than a
continuously-polling background task, to keep this a plain request/response feature with
no long-running process to manage.
"""

import secrets
from typing import Optional

import requests
from sqlmodel import Session, select

from .config import settings
from .models import FamilyMember

TELEGRAM_API_BASE = "https://api.telegram.org/bot{token}"

# Tracks how far into Telegram's update backlog we've already consumed, so repeated
# checks don't keep re-fetching the same messages forever. In-memory and per-process --
# resets on restart, which just means the next check re-scans whatever Telegram still has
# buffered (it retains unconfirmed updates for a limited time). Fine for a single-process
# hackathon backend; a multi-instance deployment would need this shared (e.g. in the DB).
_last_update_id: Optional[int] = None


def generate_link_code() -> str:
    """Short, easy-to-type-by-hand code in case someone can't use the deep link."""
    return secrets.token_hex(4).upper()


def build_link_url(code: str) -> Optional[str]:
    """None if TELEGRAM_BOT_USERNAME isn't configured -- the app falls back to showing
    the raw code for the family member to send manually in that case."""
    if not settings.telegram_bot_username:
        return None
    return f"https://t.me/{settings.telegram_bot_username}?start={code}"


def send_telegram_message(chat_id: str, text: str) -> bool:
    """Best-effort single send. Shared by the linking confirmation here and by
    alerts.send_family_alert -- one place that knows how to talk to the Telegram API."""
    token = settings.telegram_bot_token
    if not token:
        print(f"[telegram stub] would send to {chat_id}: {text}")
        return False
    try:
        response = requests.post(
            f"{TELEGRAM_API_BASE.format(token=token)}/sendMessage",
            json={"chat_id": chat_id, "text": text},
            timeout=10,
        )
        response.raise_for_status()
        return True
    except requests.exceptions.RequestException as exc:
        print(f"[telegram] failed to send to {chat_id}: {exc}")
        return False


def poll_and_link_pending(session: Session) -> list[FamilyMember]:
    """Fetch Telegram updates since the last check (short poll, not long-poll -- this
    runs inline in an HTTP request, so it must return immediately) and link any pending
    family member whose code appears in them. Consumes every fetched update (advances the
    offset) whether or not it matched anything, so nothing is re-processed on the next
    call. Returns the family members newly linked by this call.
    """
    global _last_update_id

    token = settings.telegram_bot_token
    if not token:
        return []

    params = {"timeout": 0}
    if _last_update_id is not None:
        params["offset"] = _last_update_id
    try:
        response = requests.get(
            f"{TELEGRAM_API_BASE.format(token=token)}/getUpdates", params=params, timeout=15
        )
        response.raise_for_status()
        updates = response.json().get("result", [])
    except requests.exceptions.RequestException as exc:
        print(f"[telegram] getUpdates failed: {exc}")
        return []

    # A guardian sending "/start ABCD1234" (via the deep link) or just "ABCD1234"
    # (typed by hand) both resolve to the same code -- take the last whitespace-separated
    # token of the message text either way.
    codes_seen: dict[str, str] = {}
    for update in updates:
        _last_update_id = max(_last_update_id or 0, update["update_id"] + 1)
        message = update.get("message") or {}
        text = (message.get("text") or "").strip()
        chat = message.get("chat") or {}
        if not text or "id" not in chat:
            continue
        code = text.split()[-1].upper()
        codes_seen[code] = str(chat["id"])

    if not codes_seen:
        return []

    pending = session.exec(
        select(FamilyMember).where(FamilyMember.telegram_link_code.in_(codes_seen.keys()))
    ).all()
    if not pending:
        return []

    for member in pending:
        member.telegram_chat_id = codes_seen[member.telegram_link_code]
        member.telegram_link_code = None
        session.add(member)
    session.commit()

    for member in pending:
        session.refresh(member)
        send_telegram_message(
            member.telegram_chat_id,
            f"✅ You're linked to receive ScamGuard alerts for {member.username}.",
        )

    return pending

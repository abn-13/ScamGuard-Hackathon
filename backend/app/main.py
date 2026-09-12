from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import Depends, FastAPI, HTTPException
from sqlmodel import Session

from .agent import run_pipeline
from .alerts import send_family_alert
from .db import engine, get_session, init_db
from .models import FamilyMember, Message, RiskLevel, User
from .schemas import (
    CheckResponse,
    FamilyMemberCreate,
    FamilyMemberOut,
    IncomingMessage,
    TelegramLinkStatus,
    UserCreate,
)
from .telegram_link import build_link_url, generate_link_code, poll_and_link_pending


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    yield


app = FastAPI(title="ScamGuard Backend", lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/users")
def create_user(payload: UserCreate, session: Session = Depends(get_session)):
    user = User(
        username=payload.username,
        phone_number=payload.phone_number,
        gmail=payload.gmail,
    )
    session.add(user)
    session.commit()
    session.refresh(user)
    return user


@app.post("/family-members", response_model=FamilyMemberOut)
def create_family_member(
    payload: FamilyMemberCreate, session: Session = Depends(get_session)
):
    if not session.get(User, payload.user_id):
        raise HTTPException(status_code=404, detail=f"No user with id {payload.user_id}")
    member = FamilyMember(**payload.model_dump())
    # Only needed if a chat_id wasn't already provided directly (e.g. a manual Swagger
    # call already carrying one) -- see app/telegram_link.py for how this gets consumed.
    if not member.telegram_chat_id:
        member.telegram_link_code = generate_link_code()
    session.add(member)
    session.commit()
    session.refresh(member)
    return FamilyMemberOut(
        **member.model_dump(),
        telegram_link_url=build_link_url(member.telegram_link_code)
        if member.telegram_link_code
        else None,
    )


@app.post("/family-members/{family_member_id}/link-telegram", response_model=TelegramLinkStatus)
def link_telegram(family_member_id: int, session: Session = Depends(get_session)):
    """On-demand check: call this after the family member has sent their link code (or
    tapped the deep link) to the bot. Short-polls Telegram once for new messages and links
    this family member if their code shows up -- see app/telegram_link.py for why this is
    on-demand rather than a continuous background poll.
    """
    member = session.get(FamilyMember, family_member_id)
    if not member:
        raise HTTPException(
            status_code=404, detail=f"No family member with id {family_member_id}"
        )
    if member.telegram_chat_id:
        return TelegramLinkStatus(linked=True)

    poll_and_link_pending(session)
    session.refresh(member)
    return TelegramLinkStatus(linked=member.telegram_chat_id is not None)


@app.post("/check-message", response_model=CheckResponse)
def check_message(payload: IncomingMessage):
    # Deliberately not using the `Depends(get_session)` pattern here: run_pipeline()
    # below can block for several seconds (real Bedrock call, serialized behind
    # _agent_lock against every other in-flight check). Holding a pooled DB session
    # for that whole span -- as the old Depends-based version did -- lets a burst of
    # concurrent /check-message calls (e.g. checking a whole inbox at once) exhaust
    # SQLAlchemy's connection pool (5 + 10 overflow) before any of them reach Bedrock,
    # so later ones fail with a pool-checkout TimeoutError instead of a clean result.
    # Two short-lived sessions -- one before, one after -- keep a connection held only
    # for actual DB work.
    with Session(engine) as session:
        if not session.get(User, payload.user_id):
            raise HTTPException(status_code=404, detail=f"No user with id {payload.user_id}")

    try:
        verdict = run_pipeline(payload)
    except Exception as exc:  # noqa: BLE001 -- deliberately broad: any missing
        # credential/config (Bedrock, model access, etc.) should degrade to a
        # clear 503 instead of a bare 500, so teammates aren't blocked on each
        # other's setup while developing.
        raise HTTPException(
            status_code=503, detail=f"Agent pipeline not ready: {exc}"
        ) from exc

    with Session(engine) as session:
        message = Message(
            user_id=payload.user_id,
            source=payload.source,
            sender=payload.sender,
            subject=payload.subject,
            body_text=payload.body_text,
            is_known_sender=payload.is_known_sender,
            received_at=payload.received_at,
            risk_level=verdict.risk_level,
            reason=verdict.reason,
            checked_at=datetime.now(timezone.utc),
        )
        session.add(message)
        session.commit()
        session.refresh(message)

        if verdict.risk_level in (RiskLevel.medium, RiskLevel.high):
            send_family_alert(session, payload.user_id, payload.sender, verdict.reason)

        return CheckResponse(
            message_id=message.id, risk_level=verdict.risk_level, reason=verdict.reason
        )

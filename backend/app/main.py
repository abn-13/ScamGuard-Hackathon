from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import Depends, FastAPI, HTTPException
from sqlmodel import Session

from .agent import run_pipeline
from .alerts import send_family_alert
from .db import get_session, init_db
from .models import FamilyMember, Message, RiskLevel, User
from .schemas import (
    CheckResponse,
    FamilyMemberCreate,
    IncomingMessage,
    UserCreate,
)


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
    user = User(display_name=payload.display_name)
    session.add(user)
    session.commit()
    session.refresh(user)
    return user


@app.post("/family-members")
def create_family_member(
    payload: FamilyMemberCreate, session: Session = Depends(get_session)
):
    if not session.get(User, payload.user_id):
        raise HTTPException(status_code=404, detail=f"No user with id {payload.user_id}")
    member = FamilyMember(**payload.model_dump())
    session.add(member)
    session.commit()
    session.refresh(member)
    return member


@app.post("/check-message", response_model=CheckResponse)
def check_message(payload: IncomingMessage, session: Session = Depends(get_session)):
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

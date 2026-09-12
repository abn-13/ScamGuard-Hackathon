from unittest.mock import Mock, patch

import pytest
from sqlmodel import Session, SQLModel, create_engine

from app import telegram_link
from app.config import settings
from app.models import FamilyMember, User
from app.telegram_link import (
    build_link_url,
    generate_link_code,
    poll_and_link_pending,
    send_telegram_message,
)


@pytest.fixture()
def session():
    # Self-contained in-memory DB, isolated from test_main.py's file-based one (which
    # relies on DATABASE_URL being set before app.main is imported) -- this module never
    # imports app.main, so it doesn't need to play that ordering game.
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False})
    SQLModel.metadata.create_all(engine)
    with Session(engine) as s:
        yield s


@pytest.fixture(autouse=True)
def reset_update_offset():
    # Module-level cursor into Telegram's update backlog -- reset around every test so
    # one test's fake `offset` doesn't leak into the next.
    telegram_link._last_update_id = None
    yield
    telegram_link._last_update_id = None


def test_generate_link_code_is_short_and_uppercase():
    code = generate_link_code()
    assert code == code.upper()
    assert len(code) == 8
    assert generate_link_code() != generate_link_code()


def test_build_link_url_none_without_bot_username(monkeypatch):
    monkeypatch.setattr(settings, "telegram_bot_username", None)
    assert build_link_url("ABCD1234") is None


def test_build_link_url_with_bot_username(monkeypatch):
    monkeypatch.setattr(settings, "telegram_bot_username", "ScamGuardAlertBot")
    assert (
        build_link_url("ABCD1234")
        == "https://t.me/ScamGuardAlertBot?start=ABCD1234"
    )


def test_poll_skips_network_call_without_bot_token(monkeypatch):
    monkeypatch.setattr(settings, "telegram_bot_token", None)
    with patch("app.telegram_link.requests.get") as get:
        result = poll_and_link_pending(Mock())
    get.assert_not_called()
    assert result == []


def test_send_telegram_message_stub_without_token(monkeypatch):
    monkeypatch.setattr(settings, "telegram_bot_token", None)
    with patch("app.telegram_link.requests.post") as post:
        sent = send_telegram_message("12345", "hello")
    post.assert_not_called()
    assert sent is False


def _updates_response(*, update_id: int, text: str, chat_id: int) -> Mock:
    response = Mock()
    response.raise_for_status.return_value = None
    response.json.return_value = {
        "result": [
            {
                "update_id": update_id,
                "message": {"text": text, "chat": {"id": chat_id}},
            }
        ]
    }
    return response


def test_poll_links_matching_family_member(session, monkeypatch):
    monkeypatch.setattr(settings, "telegram_bot_token", "test-token")

    user = User(username="parent", phone_number="+15550000001", gmail="parent@gmail.com")
    session.add(user)
    session.commit()
    session.refresh(user)

    member = FamilyMember(
        user_id=user.id,
        username="child",
        phone_number="+15550000002",
        telegram_link_code="ABCD1234",
    )
    session.add(member)
    session.commit()
    session.refresh(member)

    response = _updates_response(update_id=1, text="/start ABCD1234", chat_id=999)
    with patch("app.telegram_link.requests.get", return_value=response), \
         patch("app.telegram_link.requests.post") as post:
        post.return_value.raise_for_status.return_value = None
        linked = poll_and_link_pending(session)

    assert len(linked) == 1
    assert linked[0].id == member.id
    session.refresh(member)
    assert member.telegram_chat_id == "999"
    assert member.telegram_link_code is None
    post.assert_called_once()  # confirmation message sent


def test_poll_ignores_non_matching_code(session, monkeypatch):
    monkeypatch.setattr(settings, "telegram_bot_token", "test-token")

    user = User(username="parent2", phone_number="+15550000003", gmail="parent2@gmail.com")
    session.add(user)
    session.commit()
    session.refresh(user)

    member = FamilyMember(
        user_id=user.id,
        username="child2",
        phone_number="+15550000004",
        telegram_link_code="ABCD1234",
    )
    session.add(member)
    session.commit()
    session.refresh(member)

    response = _updates_response(update_id=1, text="hi there", chat_id=999)
    with patch("app.telegram_link.requests.get", return_value=response), \
         patch("app.telegram_link.requests.post") as post:
        linked = poll_and_link_pending(session)

    assert linked == []
    post.assert_not_called()
    session.refresh(member)
    assert member.telegram_chat_id is None
    assert member.telegram_link_code == "ABCD1234"

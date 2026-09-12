from unittest.mock import patch

import pytest

from fastapi.testclient import TestClient  # noqa: E402

from app.agent import Verdict  # noqa: E402
from app.main import app  # noqa: E402
from app.models import RiskLevel  # noqa: E402


@pytest.fixture()
def client(monkeypatch, tmp_path):
    from sqlmodel import create_engine
    from app import db, main

    # Patch both references: main also opens short-lived sessions directly.
    # A fresh database per test avoids import-order leaks and old local schemas.
    test_engine = create_engine(
        f"sqlite:///{(tmp_path / 'test.db').as_posix()}",
        connect_args={"check_same_thread": False},
    )
    monkeypatch.setattr(db, "engine", test_engine)
    monkeypatch.setattr(main, "engine", test_engine)
    # Lifespan (which creates the DB tables) only runs when TestClient is
    # used as a context manager -- a plain TestClient(app) skips it.
    try:
        with TestClient(app) as c:
            yield c
    finally:
        test_engine.dispose()


def test_health(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


def test_check_message_flow_flags_high_risk_and_alerts_family(client):
    user_resp = client.post(
        "/users",
        json={
            "username": "test_parent",
            "phone_number": "+15550000001",
            "gmail": "test.parent@gmail.com",
        },
    )
    assert user_resp.status_code == 200
    user_id = user_resp.json()["id"]

    family_resp = client.post(
        "/family-members",
        json={
            "user_id": user_id,
            "username": "test_child",
            "phone_number": "+15550000002",
            "telegram_chat_id": "12345",
        },
    )
    assert family_resp.status_code == 200

    fake_verdict = Verdict(
        risk_level=RiskLevel.high,
        reason="Impersonates a bank and asks for a one-time password.",
    )
    with patch("app.main.run_pipeline", return_value=fake_verdict) as mock_pipeline, \
         patch("app.main.send_family_alert") as mock_alert:
        resp = client.post(
            "/check-message",
            json={
                "user_id": user_id,
                "source": "sms",
                "sender": "+15551234567",
                "body_text": "Your bank account is suspended, reply with your OTP to verify.",
                "is_known_sender": False,
                "received_at": "2026-01-01T00:00:00Z",
            },
        )

    assert resp.status_code == 200
    body = resp.json()
    assert body["risk_level"] == "high"
    assert "OTP" not in body["reason"]  # sanity: reason comes from the (mocked) verdict
    mock_pipeline.assert_called_once()
    mock_alert.assert_called_once()


def test_check_message_unknown_user_returns_404(client):
    resp = client.post(
        "/check-message",
        json={
            "user_id": 999999,
            "source": "email",
            "sender": "someone@example.com",
            "body_text": "hello",
            "is_known_sender": True,
            "received_at": "2026-01-01T00:00:00Z",
        },
    )
    assert resp.status_code == 404


def test_email_accepts_optional_task3b_identity_headers(client):
    user_resp = client.post(
        "/users",
        json={
            "username": "email_test_user",
            "phone_number": "+15550000003",
            "gmail": "email.test.user@gmail.com",
        },
    )
    user_id = user_resp.json()["id"]
    fake_verdict = Verdict(risk_level=RiskLevel.low, reason="No strong warning found.")

    with patch("app.main.run_pipeline", return_value=fake_verdict) as pipeline:
        resp = client.post(
            "/check-message",
            json={
                "user_id": user_id,
                "source": "email",
                "sender": "Notice <notice@example.com>",
                "reply_to": "help@support.example.com",
                "authentication_results": (
                    "mx.google.com; spf=pass; dkim=pass; "
                    "dmarc=pass header.from=example.com"
                ),
                "body_text": "A routine account notice.",
                "is_known_sender": False,
                "received_at": "2026-01-01T00:00:00Z",
            },
        )

    assert resp.status_code == 200
    incoming = pipeline.call_args.args[0]
    assert incoming.reply_to == "help@support.example.com"
    assert "dmarc=pass" in incoming.authentication_results


def test_create_family_member_without_chat_id_gets_a_link_code(client):
    user_id = client.post(
        "/users",
        json={
            "username": "telegram_test_parent",
            "phone_number": "+15550000004",
            "gmail": "telegram.parent@gmail.com",
        },
    ).json()["id"]

    resp = client.post(
        "/family-members",
        json={
            "user_id": user_id,
            "username": "telegram_test_child",
            "phone_number": "+15550000005",
        },
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body["telegram_chat_id"] is None
    assert body["telegram_link_code"] is not None
    assert len(body["telegram_link_code"]) == 8
    # No TELEGRAM_BOT_USERNAME configured in the test environment -- the app is expected
    # to fall back to showing the raw code rather than a deep link in that case.
    assert body["telegram_link_url"] is None


def test_family_member_created_with_chat_id_skips_link_code(client):
    user_id = client.post(
        "/users",
        json={
            "username": "telegram_test_parent2",
            "phone_number": "+15550000006",
            "gmail": "telegram.parent2@gmail.com",
        },
    ).json()["id"]

    resp = client.post(
        "/family-members",
        json={
            "user_id": user_id,
            "username": "telegram_test_child2",
            "phone_number": "+15550000007",
            "telegram_chat_id": "555",
        },
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body["telegram_chat_id"] == "555"
    assert body["telegram_link_code"] is None


def test_link_telegram_already_linked_short_circuits_without_polling(client):
    user_id = client.post(
        "/users",
        json={
            "username": "telegram_test_parent3",
            "phone_number": "+15550000008",
            "gmail": "telegram.parent3@gmail.com",
        },
    ).json()["id"]
    family_member_id = client.post(
        "/family-members",
        json={
            "user_id": user_id,
            "username": "telegram_test_child3",
            "phone_number": "+15550000009",
            "telegram_chat_id": "777",
        },
    ).json()["id"]

    with patch("app.main.poll_and_link_pending") as poll:
        resp = client.post(f"/family-members/{family_member_id}/link-telegram")

    assert resp.status_code == 200
    assert resp.json() == {"linked": True}
    poll.assert_not_called()  # already linked -- no need to hit Telegram at all


def test_link_telegram_polls_and_reports_still_unlinked(client):
    user_id = client.post(
        "/users",
        json={
            "username": "telegram_test_parent4",
            "phone_number": "+15550000010",
            "gmail": "telegram.parent4@gmail.com",
        },
    ).json()["id"]
    family_member_id = client.post(
        "/family-members",
        json={
            "user_id": user_id,
            "username": "telegram_test_child4",
            "phone_number": "+15550000011",
        },
    ).json()["id"]

    with patch("app.main.poll_and_link_pending", return_value=[]) as poll:
        resp = client.post(f"/family-members/{family_member_id}/link-telegram")

    assert resp.status_code == 200
    assert resp.json() == {"linked": False}
    poll.assert_called_once()


def test_link_telegram_unknown_family_member_returns_404(client):
    resp = client.post("/family-members/999999/link-telegram")
    assert resp.status_code == 404

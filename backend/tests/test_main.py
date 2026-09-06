import os
from unittest.mock import patch

import pytest

os.environ["DATABASE_URL"] = "sqlite:///./test_scamguard.db"

from fastapi.testclient import TestClient  # noqa: E402

from app.agent import Verdict  # noqa: E402
from app.main import app  # noqa: E402
from app.models import RiskLevel  # noqa: E402


@pytest.fixture()
def client():
    # Lifespan (which creates the DB tables) only runs when TestClient is
    # used as a context manager -- a plain TestClient(app) skips it.
    with TestClient(app) as c:
        yield c


def test_health(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


def test_check_message_flow_flags_high_risk_and_alerts_family(client):
    user_resp = client.post("/users", json={"display_name": "Test Parent"})
    assert user_resp.status_code == 200
    user_id = user_resp.json()["id"]

    family_resp = client.post(
        "/family-members",
        json={
            "user_id": user_id,
            "display_name": "Test Child",
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

from datetime import datetime, timezone
from types import SimpleNamespace
from unittest.mock import Mock, patch

from app.agent import SYSTEM_PROMPT, Verdict, _build_agent, run_pipeline
from app.config import settings
from app.models import MessageSource, RiskLevel
from app.schemas import IncomingMessage


def _message(source: MessageSource, sender: str) -> IncomingMessage:
    return IncomingMessage(
        user_id=1,
        source=source,
        sender=sender,
        subject="Account notice" if source == MessageSource.email else None,
        body_text="Please review this message.",
        is_known_sender=False,
        received_at=datetime(2026, 1, 1, tzinfo=timezone.utc),
    )


def _fake_agent() -> Mock:
    verdict = Verdict(risk_level=RiskLevel.medium, reason="Test verdict.")
    return Mock(return_value=SimpleNamespace(structured_output=verdict))


def _low_risk_agent() -> Mock:
    verdict = Verdict(risk_level=RiskLevel.low, reason="The message appears routine.")
    return Mock(return_value=SimpleNamespace(structured_output=verdict))


def test_email_pipeline_includes_precomputed_domain_assessment():
    agent = _fake_agent()

    with patch("app.agent.get_agent", return_value=agent):
        verdict = run_pipeline(
            _message(MessageSource.email, "PayPal <verify@paypa1-verify.com>")
        )

    prompt = agent.call_args.args[0]
    assert verdict.risk_level == RiskLevel.medium
    assert "Sender domain assessment (computed by ScamGuard):" in prompt
    assert '"verdict": "suspicious"' in prompt
    assert '"matched_brand": "PayPal"' in prompt
    assert "Receiver email authentication assessment" in prompt
    assert '"status": "not_provided"' in prompt
    assert agent.call_args.kwargs["structured_output_model"] is Verdict


def test_email_pipeline_includes_dmarc_failure_even_for_official_domain():
    agent = _fake_agent()
    message = _message(MessageSource.email, "PayPal <notice@paypal.com>")
    message.authentication_results = (
        "mx.example; spf=fail; dkim=fail; dmarc=fail header.from=paypal.com"
    )

    with patch("app.agent.get_agent", return_value=agent):
        run_pipeline(message)

    prompt = agent.call_args.args[0]
    assert '"verdict": "official"' in prompt
    assert '"status": "fail"' in prompt
    assert '"dmarc": "fail"' in prompt


def test_dmarc_failure_enforces_medium_risk_floor():
    agent = _low_risk_agent()
    message = _message(MessageSource.email, "PayPal <notice@paypal.com>")
    message.authentication_results = (
        "mx.example; spf=fail; dkim=fail; dmarc=fail header.from=paypal.com"
    )

    with patch("app.agent.get_agent", return_value=agent):
        verdict = run_pipeline(message)

    assert verdict.risk_level == RiskLevel.medium
    assert "failed" in verdict.reason
    assert "Do not click or pay" in verdict.reason


def test_misaligned_authentication_result_is_not_applied_to_visible_sender():
    agent = _low_risk_agent()
    message = _message(MessageSource.email, "PayPal <notice@paypal.com>")
    message.authentication_results = (
        "mx.example; dmarc=fail header.from=unrelated.example.net"
    )

    with patch("app.agent.get_agent", return_value=agent):
        verdict = run_pipeline(message)

    prompt = agent.call_args.args[0]
    assert '"status": "misaligned"' in prompt
    assert verdict.risk_level == RiskLevel.low


def test_suspicious_lookalike_enforces_medium_risk_floor():
    agent = _low_risk_agent()

    with patch("app.agent.get_agent", return_value=agent):
        verdict = run_pipeline(
            _message(MessageSource.email, "USPS <parcel@usps-redelivery.example>")
        )

    assert verdict.risk_level == RiskLevel.medium
    assert "United States Postal Service" in verdict.reason


def test_unknown_domain_does_not_raise_low_risk_verdict():
    agent = _low_risk_agent()

    with patch("app.agent.get_agent", return_value=agent):
        verdict = run_pipeline(_message(MessageSource.email, "hello@example.org"))

    assert verdict.risk_level == RiskLevel.low
    assert verdict.reason == "The message appears routine."


def test_sms_pipeline_does_not_run_email_domain_assessment():
    agent = _fake_agent()

    with patch("app.agent.get_agent", return_value=agent), patch(
        "app.agent.assess_sender_domain"
    ) as assessment, patch("app.agent.assess_email_authentication") as auth_assessment:
        with patch("app.agent.assess_reply_to_alignment") as reply_to_assessment:
            run_pipeline(_message(MessageSource.sms, "+15551234567"))

    prompt = agent.call_args.args[0]
    assessment.assert_not_called()
    auth_assessment.assert_not_called()
    reply_to_assessment.assert_not_called()
    assert "Sender domain assessment" not in prompt
    assert "Receiver email authentication assessment" not in prompt


def test_agent_registers_all_security_tools(monkeypatch):
    monkeypatch.setattr(settings, "bedrock_model_id", "local-test-model")

    agent = _build_agent()

    assert set(agent.tool_registry.registry) == {
        "check_sender_domain",
        "check_url_reputation",
        "search_official_domain_candidates",
        "check_domain_intelligence",
    }
    assert "precomputed sender-domain assessment" in SYSTEM_PROMPT
    assert "unverified candidates" in SYSTEM_PROMPT
    assert "DMARC failure is strong spoofing evidence" in SYSTEM_PROMPT
    assert "marked `misaligned`" in SYSTEM_PROMPT
    assert "different\nregistrable Reply-To domain is a weak warning" in SYSTEM_PROMPT
    assert "check_domain_intelligence" in SYSTEM_PROMPT
    assert "initial emphasis on\nNorth America" in SYSTEM_PROMPT
    assert "one\nimmediate safe action" in SYSTEM_PROMPT


def test_email_pipeline_includes_reply_to_mismatch():
    agent = _fake_agent()
    message = _message(MessageSource.email, "Billing <notice@paypal.com>")
    message.reply_to = "refund-agent@example.net"

    with patch("app.agent.get_agent", return_value=agent):
        run_pipeline(message)

    prompt = agent.call_args.args[0]
    assert "From/Reply-To alignment assessment" in prompt
    assert '"status": "mismatch"' in prompt
    assert '"risk_signal": "weak"' in prompt

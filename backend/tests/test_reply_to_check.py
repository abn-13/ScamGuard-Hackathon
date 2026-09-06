from app.tools.reply_to_check import assess_reply_to_alignment


def test_missing_reply_to_is_neutral():
    result = assess_reply_to_alignment("notice@example.co.uk", None)

    assert result["status"] == "not_provided"
    assert result["risk_signal"] == "none"
    assert result["sender_registrable_domain"] == "example.co.uk"


def test_subdomain_reply_to_is_aligned_by_public_suffix():
    result = assess_reply_to_alignment(
        "notice@mail.example.co.uk", "Support <help@reply.example.co.uk>"
    )

    assert result["status"] == "aligned"
    assert result["reply_to_registrable_domain"] == "example.co.uk"


def test_different_reply_to_domain_is_weak_signal():
    result = assess_reply_to_alignment(
        "Bank <notice@trusted-bank.com>", "agent@refund-example.net"
    )

    assert result["status"] == "mismatch"
    assert result["risk_signal"] == "weak"


def test_invalid_reply_to_does_not_create_risk_signal():
    result = assess_reply_to_alignment("notice@example.com", "broken-address")

    assert result["status"] == "invalid"
    assert result["risk_signal"] == "none"


def test_any_external_address_in_multi_reply_to_causes_mismatch():
    result = assess_reply_to_alignment(
        "notice@example.com",
        "Support <help@example.com>, Escalation <agent@external-support.net>",
    )

    assert result["status"] == "mismatch"
    assert result["reply_to_domains"] == ["example.com", "external-support.net"]

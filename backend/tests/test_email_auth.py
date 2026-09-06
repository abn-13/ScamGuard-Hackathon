import pytest

from app.tools.email_auth import assess_email_authentication


def test_missing_authentication_result_is_neutral():
    result = assess_email_authentication(None, "notice@paypal.com")

    assert result["status"] == "not_provided"
    assert result["sender_domain"] == "paypal.com"
    assert result["dmarc"] is None


def test_dmarc_pass_is_reported():
    result = assess_email_authentication(
        "mx.example; spf=pass smtp.mailfrom=paypal.com; "
        "dkim=pass header.d=paypal.com; dmarc=pass header.from=paypal.com",
        "PayPal <notice@paypal.com>",
    )

    assert result["status"] == "pass"
    assert result["dmarc"] == "pass"
    assert result["spf"] == "pass"
    assert result["dkim"] == "pass"
    assert result["header_from"] == "paypal.com"
    assert result["header_from_aligned"] is True


def test_dmarc_pass_for_different_header_from_is_not_accepted():
    result = assess_email_authentication(
        "mx.example; dmarc=pass header.from=attacker.example.net",
        "PayPal <notice@paypal.com>",
    )

    assert result["status"] == "misaligned"
    assert result["header_from_aligned"] is False
    assert "cannot be used" in result["reason"]


def test_dmarc_relaxed_alignment_accepts_same_registrable_domain():
    result = assess_email_authentication(
        "mx.example; dmarc=pass header.from=mailer.example.co.uk",
        "Notice <notice@example.co.uk>",
    )

    assert result["status"] == "pass"
    assert result["header_from_aligned"] is True


@pytest.mark.parametrize(
    ("authentication_results", "sender"),
    [
        ("mx.example; dmarc=pass header.from=invalid_domain", "notice@paypal.com"),
        ("mx.example; dmarc=pass header.from=paypal.com", "PayPal Support"),
        ("mx.example; dmarc=pass", "PayPal Support"),
    ],
)
def test_dmarc_is_not_accepted_without_an_associable_visible_sender(
    authentication_results, sender
):
    result = assess_email_authentication(authentication_results, sender)

    assert result["status"] == "misaligned"
    assert result["header_from_aligned"] is False


@pytest.mark.parametrize("dmarc_result", ["fail", "permerror", "policy"])
def test_dmarc_failure_is_strong_spoofing_evidence(dmarc_result):
    result = assess_email_authentication(
        f"mx.example; dmarc={dmarc_result} header.from=paypal.com",
        "notice@paypal.com",
    )

    assert result["status"] == "fail"
    assert result["dmarc"] == dmarc_result


def test_both_underlying_methods_failing_is_failure():
    result = assess_email_authentication(
        "mx.example; SPF=FAIL smtp.mailfrom=example.test; DKIM=FAIL",
        "notice@example.test",
    )

    assert result["status"] == "fail"
    assert result["spf"] == "fail"
    assert result["dkim"] == "fail"


def test_one_underlying_pass_without_dmarc_is_inconclusive():
    result = assess_email_authentication(
        "mx.example; spf=pass smtp.mailfrom=mailer.test; dkim=fail",
        "notice@example.test",
    )

    assert result["status"] == "unknown"


def test_unrecognized_value_is_inconclusive():
    result = assess_email_authentication("mx.example; arc=pass", "notice@example.test")

    assert result["status"] == "unknown"
    assert result["dmarc"] is None

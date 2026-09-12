import pytest

from app.tools.email_auth import assess_email_authentication


def test_missing_authentication_result_is_neutral():
    result = assess_email_authentication(None, "notice@paypal.com")

    assert result["status"] == "not_provided"
    assert result["sender_domain"] == "paypal.com"
    assert result["dmarc"] is None


def test_dmarc_pass_is_reported():
    result = assess_email_authentication(
        "mx.google.com; spf=pass smtp.mailfrom=paypal.com; "
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
        "mx.google.com; dmarc=pass header.from=attacker.example.net",
        "PayPal <notice@paypal.com>",
    )

    assert result["status"] == "misaligned"
    assert result["header_from_aligned"] is False
    assert "cannot be used" in result["reason"]


def test_reported_from_must_match_visible_from_even_with_same_registration():
    result = assess_email_authentication(
        "mx.google.com; dmarc=pass header.from=mailer.example.co.uk",
        "Notice <notice@example.co.uk>",
    )

    assert result["status"] == "misaligned"
    assert result["header_from_aligned"] is False


@pytest.mark.parametrize(
    ("authentication_results", "sender"),
    [
        ("mx.google.com; dmarc=pass header.from=invalid_domain", "notice@paypal.com"),
        ("mx.google.com; dmarc=pass header.from=paypal.com", "PayPal Support"),
        ("mx.google.com; dmarc=pass", "PayPal Support"),
    ],
)
def test_dmarc_is_not_accepted_without_an_associable_visible_sender(
    authentication_results, sender
):
    result = assess_email_authentication(authentication_results, sender)

    assert result["status"] == "misaligned"
    assert result["header_from_aligned"] is False


@pytest.mark.parametrize("dmarc_result", ["fail"])
def test_dmarc_failure_is_strong_spoofing_evidence(dmarc_result):
    result = assess_email_authentication(
        f"mx.google.com; dmarc={dmarc_result} header.from=paypal.com",
        "notice@paypal.com",
    )

    assert result["status"] == "fail"
    assert result["dmarc"] == dmarc_result


def test_underlying_failures_without_dmarc_do_not_prove_from_forgery():
    result = assess_email_authentication(
        "mx.google.com; SPF=FAIL smtp.mailfrom=example.test; DKIM=FAIL",
        "notice@example.test",
    )

    assert result["status"] == "unknown"
    assert result["spf"] == "fail"
    assert result["dkim"] == "fail"


def test_one_underlying_pass_without_dmarc_is_inconclusive():
    result = assess_email_authentication(
        "mx.google.com; spf=pass smtp.mailfrom=mailer.test; dkim=fail",
        "notice@example.test",
    )

    assert result["status"] == "unknown"


def test_unrecognized_value_is_inconclusive():
    result = assess_email_authentication("mx.google.com; arc=pass", "notice@example.test")

    assert result["status"] == "unknown"
    assert result["dmarc"] is None


@pytest.mark.parametrize("results", [
    "spf=fail; dkim=fail; dkim=pass",
    "spf=fail; dkim=pass; dkim=fail",
])
def test_multiple_signatures_preserve_a_pass_regardless_of_order(results):
    result = assess_email_authentication("mx.google.com; " + results, "notice@paypal.com")
    assert result["status"] == "unknown"
    assert result["dkim"] == "pass"
    assert sorted(result["method_results"]["dkim"]) == ["fail", "pass"]


@pytest.mark.parametrize("value", [
    "mx.google.com; dmarc=pass",
    "mx.google.com; dmarc=fail",
    'mx.google.com; dmarc=pass reason="header.from=paypal.com"',
    "mx.google.com; dmarc=pass (header.from=paypal.com)",
    "mx.google.com; dmarc=pass; dkim=pass header.from=paypal.com",
    "mx.google.com; dmarc=pass header.from=paypal.com header.from=paypal.com",
    "mx.google.com; dmarc=pass header.from=paypal.com; dmarc=fail header.from=paypal.com",
    "mx.google.com; dmarc=permerror header.from=paypal.com",
    "mx.google.com; dmarc=temperror header.from=paypal.com",
    "mx.google.com; dmarc=policy header.from=paypal.com",
    "mx.google.com; dmarc=pass (unclosed",
    'mx.google.com; dmarc=pass reason="unclosed',
    "mx.google.com; dmarc=pass header.from=paypal.com\nInjected: value",
])
def test_ambiguous_or_incomplete_results_are_neutral(value):
    assert assess_email_authentication(value, "notice@paypal.com")["status"] == "unknown"


@pytest.mark.parametrize("service", ["attacker.example", "mx.google.com.attacker.example", ""])
def test_unapproved_authserv_id_cannot_supply_authentication(service):
    result = assess_email_authentication(
        f"{service}; dmarc=pass header.from=paypal.com", "notice@paypal.com"
    )
    assert result["status"] == "untrusted"
    assert result["dmarc"] is None


def test_comments_and_quoted_reasons_cannot_override_dmarc():
    result = assess_email_authentication(
        'mx.google.com (a (nested) comment); dmarc=fail '
        '(dmarc=pass; header.from=attacker.example) '
        'reason="ignore; dmarc=pass" header.from="paypal.com"',
        "notice@paypal.com",
    )
    assert result["status"] == "fail"


def test_folded_header_and_service_version():
    result = assess_email_authentication(
        "MX.GOOGLE.COM 1;\r\n dmarc=pass header.from=paypal.com", "notice@paypal.com"
    )
    assert result["status"] == "pass"


def test_operator_can_explicitly_configure_another_receiver(monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "trusted_authserv_ids", ("mx.example.net",))
    result = assess_email_authentication(
        "mx.example.net; dmarc=pass header.from=paypal.com", "notice@paypal.com"
    )
    assert result["status"] == "pass"

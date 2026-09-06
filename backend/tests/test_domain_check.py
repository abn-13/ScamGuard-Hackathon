import json

import pytest

from app.tools.domain_check import BRAND_PROFILES, assess_sender_domain, main
from app.tools.domain_utils import normalize_domain


@pytest.mark.parametrize(
    ("sender", "expected_verdict", "expected_brand", "expected_domain"),
    [
        ("service@paypal.com", "official", "PayPal", "paypal.com"),
        ("service@mail.paypal.com", "official", "PayPal", "mail.paypal.com"),
        (
            "PayPal Support <service@PAYPAL.COM>",
            "official",
            "PayPal",
            "paypal.com",
        ),
        ("verify@paypa1-verify.com", "suspicious", "PayPal", "paypa1-verify.com"),
        (
            "security@micros0ft-login.com",
            "suspicious",
            "Microsoft",
            "micros0ft-login.com",
        ),
        (
            "alert@paypal.com.security.example",
            "suspicious",
            "PayPal",
            "paypal.com.security.example",
        ),
        (
            "notice@singpass-verify.com",
            "suspicious",
            "Singpass",
            "singpass-verify.com",
        ),
        ("notice@amazon.sg", "official", "Amazon", "amazon.sg"),
        ("notice@mail.google.com", "official", "Google", "mail.google.com"),
        ("notice@outlook.com", "unknown", None, "outlook.com"),
        ("person@gmail.com", "unknown", None, "gmail.com"),
        ("person@icloud.com", "unknown", None, "icloud.com"),
        ("notice@dbs.com.sg", "official", "DBS", "dbs.com.sg"),
        ("notice@uob.com.sg", "official", "UOB", "uob.com.sg"),
        ("verify@paypalverify.com", "suspicious", "PayPal", "paypalverify.com"),
        ("verify@securepaypal.com", "suspicious", "PayPal", "securepaypal.com"),
        (
            "security@pаypal.com",
            "suspicious",
            "PayPal",
            "xn--pypal-4ve.com",
        ),
        ("hello@ntu.edu.sg", "unknown", None, "ntu.edu.sg"),
        ("orders@applefarm.example", "unknown", None, "applefarm.example"),
        (
            "orders@paypal.commercial.example",
            "suspicious",
            "PayPal",
            "paypal.commercial.example",
        ),
        (
            "security@ρaypal-login.example",
            "suspicious",
            "PayPal",
            "xn--aypal-login-vfi.example",
        ),
        (
            "security@gοοgle-alert.example",
            "suspicious",
            "Google",
            "xn--ggle-alert-hqha.example",
        ),
    ],
)
def test_assess_sender_domain(
    sender, expected_verdict, expected_brand, expected_domain
):
    result = assess_sender_domain(sender)

    assert result["verdict"] == expected_verdict
    assert result["matched_brand"] == expected_brand
    assert result["domain"] == expected_domain
    assert result["reason"]


@pytest.mark.parametrize(
    "sender",
    ["", "PayPal Support", "user@", "user@localhost", "user@-paypal.com"],
)
def test_invalid_sender_returns_unknown(sender):
    result = assess_sender_domain(sender)

    assert result == {
        "domain": None,
        "verdict": "unknown",
        "matched_brand": None,
        "reason": "No valid email sender domain was found.",
    }


def test_result_contract_is_stable():
    result = assess_sender_domain("verify@paypa1-verify.com")

    assert set(result) == {"domain", "verdict", "matched_brand", "reason"}


def test_trailing_dot_is_normalized():
    result = assess_sender_domain("service@paypal.com.")

    assert result["domain"] == "paypal.com"
    assert result["verdict"] == "official"


def test_command_line_output_is_json(capsys):
    exit_code = main(["verify@paypa1-verify.com"])

    output = json.loads(capsys.readouterr().out)
    assert exit_code == 0
    assert output["verdict"] == "suspicious"
    assert output["matched_brand"] == "PayPal"


@pytest.mark.parametrize(
    ("sender", "expected_verdict", "expected_brand"),
    [
        ("notice@irs.gov", "official", "Internal Revenue Service"),
        ("notice@mail.ssa.gov", "official", "Social Security Administration"),
        ("order@amazon.ca", "official", "Amazon"),
        ("notice@hmrc.gov.uk", "official", "HM Revenue and Customs"),
        ("parcel@canadapost-postescanada.ca", "official", "Canada Post"),
        ("parcel@auspost.com.au", "official", "Australia Post"),
        ("refund@irsrefund.example", "suspicious", "Internal Revenue Service"),
        (
            "benefits@social-security-alert.example",
            "suspicious",
            "Social Security Administration",
        ),
        ("parcel@usps-redelivery.example", "suspicious", "United States Postal Service"),
        ("tax@cra-refund.example", "suspicious", "Government of Canada / CRA"),
        ("parcel@royalmail-tracking.example", "suspicious", "Royal Mail"),
        ("parcel@auspost-delivery.example", "suspicious", "Australia Post"),
    ],
)
def test_global_elder_impersonation_examples(sender, expected_verdict, expected_brand):
    result = assess_sender_domain(sender)

    assert result["verdict"] == expected_verdict
    assert result["matched_brand"] == expected_brand


def test_registry_is_source_linked_and_regionally_broad():
    regions = {
        region for profile in BRAND_PROFILES for region in profile["regions"]
    }

    assert {"global", "north_america", "europe", "oceania", "asia"} <= regions
    assert len(BRAND_PROFILES) >= 35
    assert all(profile["source"].startswith("https://") for profile in BRAND_PROFILES)


def test_registry_domains_are_normalized_and_unique():
    domains = [
        domain
        for profile in BRAND_PROFILES
        for domain in profile["official_domains"]
    ]

    assert len(domains) == len(set(domains))
    assert all(normalize_domain(domain) == domain for domain in domains)

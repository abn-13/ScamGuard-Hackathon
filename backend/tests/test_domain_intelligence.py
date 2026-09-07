from datetime import datetime, timezone
from unittest.mock import Mock, patch

import pytest
import requests

from app.config import settings
from app.tools.domain_intelligence import (
    CERTIFICATE_ENDPOINT,
    DNS_ENDPOINT,
    _bootstrap_cache,
    _certificate_summary,
    _dns_summary,
    _domain_cache,
    _rdap_summary,
    lookup_domain_intelligence,
)


@pytest.fixture(autouse=True)
def clear_domain_intelligence_caches(monkeypatch):
    monkeypatch.setattr(settings, "domain_intelligence_enabled", True)
    _domain_cache.clear()
    _bootstrap_cache.clear()
    yield
    _domain_cache.clear()
    _bootstrap_cache.clear()


def test_disabled_enrichment_does_not_call_network(monkeypatch):
    monkeypatch.setattr(settings, "domain_intelligence_enabled", False)

    with patch("app.tools.domain_intelligence.requests.get") as get:
        result = lookup_domain_intelligence("example.com")

    get.assert_not_called()
    assert result["status"] == "not_configured"
    assert result["signals"] == []


def test_invalid_domain_does_not_call_network():
    with patch("app.tools.domain_intelligence.requests.get") as get:
        result = lookup_domain_intelligence("not-a-public-domain")

    get.assert_not_called()
    assert result["status"] == "invalid"


def test_dns_mx_summary():
    response = Mock()
    response.raise_for_status.return_value = None
    response.json.return_value = {
        "Status": 0,
        "AD": True,
        "Answer": [{"name": "example.com.", "type": 15, "data": "10 mx.example.com."}],
    }
    with patch("app.tools.domain_intelligence.requests.get", return_value=response) as get:
        result = _dns_summary("example.com")

    assert result["status"] == "resolved"
    assert result["has_mx"] is True
    assert result["dnssec_authenticated"] is True
    assert get.call_args.args[0] == DNS_ENDPOINT


def test_rdap_age_marks_new_domain():
    response = Mock()
    response.raise_for_status.return_value = None
    response.json.return_value = {
        "events": [{"eventAction": "registration", "eventDate": "2026-08-20T00:00:00Z"}]
    }
    with patch(
        "app.tools.domain_intelligence._rdap_base_url",
        return_value="https://rdap.example/",
    ), patch("app.tools.domain_intelligence.requests.get", return_value=response):
        result = _rdap_summary(
            "new-domain.com", now=datetime(2026, 9, 6, tzinfo=timezone.utc)
        )

    assert result["status"] == "found"
    assert result["age_days"] == 17
    assert result["is_newly_registered"] is True


def test_certificate_lookup_can_run_without_token(monkeypatch):
    monkeypatch.setattr(settings, "certspotter_api_token", None)
    response = Mock()
    response.raise_for_status.return_value = None
    response.json.return_value = [{"dns_names": ["example.com"]}]
    with patch("app.tools.domain_intelligence.requests.get", return_value=response) as get:
        result = _certificate_summary("example.com")

    assert result["status"] == "found"
    assert result["certificate_count"] == 1
    assert get.call_args.args[0] == CERTIFICATE_ENDPOINT
    assert "Authorization" not in get.call_args.kwargs["headers"]


def test_combined_lookup_adds_moderate_signals_and_caches():
    dns = {
        "status": "nxdomain",
        "has_mx": False,
        "dnssec_authenticated": False,
        "reason": "missing",
    }
    registration = {
        "status": "found",
        "created_at": "2026-09-01T00:00:00Z",
        "age_days": 5,
        "is_newly_registered": True,
        "reason": "new",
    }
    certificate = {
        "status": "not_found",
        "certificate_count": 0,
        "has_certificates": False,
        "reason": "none",
    }
    with patch("app.tools.domain_intelligence._dns_summary", return_value=dns) as dns_call, patch(
        "app.tools.domain_intelligence._rdap_summary", return_value=registration
    ) as rdap_call, patch(
        "app.tools.domain_intelligence._certificate_summary", return_value=certificate
    ) as cert_call:
        first = lookup_domain_intelligence("mail.new-domain.co.uk")
        second = lookup_domain_intelligence("reply.new-domain.co.uk")

    assert first["registrable_domain"] == "new-domain.co.uk"
    assert {item["type"] for item in first["signals"]} == {
        "domain_not_in_dns",
        "newly_registered_domain",
    }
    assert first["cached"] is False
    assert second["cached"] is True
    dns_call.assert_called_once_with("new-domain.co.uk")
    rdap_call.assert_called_once_with("new-domain.co.uk")
    cert_call.assert_called_once_with("new-domain.co.uk")


def test_external_failures_degrade_to_unknown_evidence():
    with patch(
        "app.tools.domain_intelligence.requests.get",
        side_effect=requests.Timeout("slow"),
    ):
        result = lookup_domain_intelligence("example.com")

    assert result["status"] == "unavailable"
    assert result["signals"] == []

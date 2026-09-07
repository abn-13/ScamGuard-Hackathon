import json
from unittest.mock import Mock, patch

import pytest
import requests

from app.config import settings
from app.tools.official_domain_search import (
    HTTP_USER_AGENT,
    MAX_RESULTS,
    SEARCH_ENDPOINT,
    _search_cache,
    discover_official_domain_candidates,
    main,
)


@pytest.fixture(autouse=True)
def clear_search_cache():
    _search_cache.clear()
    yield
    _search_cache.clear()


def test_unconfigured_search_degrades_without_network(monkeypatch):
    monkeypatch.setattr(settings, "brave_search_api_key", None)

    with patch("app.tools.official_domain_search.requests.get") as get:
        result = discover_official_domain_candidates("Stripe", "stripe-alert.test")

    get.assert_not_called()
    assert result["status"] == "not_configured"
    assert result["candidate_domains"] == []


def test_search_returns_unique_candidate_domains(monkeypatch):
    monkeypatch.setattr(settings, "brave_search_api_key", "test-key")
    response = Mock()
    response.raise_for_status.return_value = None
    response.json.return_value = {
        "web": {
            "results": [
                {"url": "https://www.stripe.com/"},
                {"url": "https://stripe.com/docs"},
                {"url": "https://support.stripe.com/questions"},
                {"url": "not-a-url"},
            ]
        }
    }

    with patch(
        "app.tools.official_domain_search.requests.get", return_value=response
    ) as get:
        result = discover_official_domain_candidates(" Stripe ", "STRIPE-ALERT.TEST.")

    assert result["status"] == "candidates"
    assert result["candidate_domains"] == ["stripe.com", "support.stripe.com"]
    assert result["sender_domain"] == "stripe-alert.test"
    assert result["country_code"] is None
    assert result["sender_matches_candidate"] is False
    assert result["cached"] is False
    assert result["candidate_evidence"] == [
        {"domain": "stripe.com", "url": "https://www.stripe.com/"},
        {"domain": "support.stripe.com", "url": "https://support.stripe.com/questions"},
    ]
    get.assert_called_once_with(
        SEARCH_ENDPOINT,
        headers={
            "Accept": "application/json",
            "User-Agent": HTTP_USER_AGENT,
            "X-Subscription-Token": "test-key",
        },
        params={
            "q": '"Stripe" official website',
            "count": MAX_RESULTS,
            "safesearch": "strict",
        },
        timeout=8,
    )


def test_successful_search_is_cached(monkeypatch):
    monkeypatch.setattr(settings, "brave_search_api_key", "test-key")
    response = Mock()
    response.raise_for_status.return_value = None
    response.json.return_value = {
        "web": {"results": [{"url": "https://www.stripe.com/"}]}
    }

    with patch(
        "app.tools.official_domain_search.requests.get", return_value=response
    ) as get:
        first = discover_official_domain_candidates("Stripe", "bad.example")
        second = discover_official_domain_candidates("Stripe", "mail.stripe.com")

    get.assert_called_once()
    assert first["cached"] is False
    assert second["cached"] is True
    assert second["sender_matches_candidate"] is True


def test_empty_results_are_neutral(monkeypatch):
    monkeypatch.setattr(settings, "brave_search_api_key", "test-key")
    response = Mock()
    response.raise_for_status.return_value = None
    response.json.return_value = {"web": {"results": []}}

    with patch("app.tools.official_domain_search.requests.get", return_value=response):
        result = discover_official_domain_candidates("Unknown Company")

    assert result["status"] == "not_found"
    assert "not evidence" in result["reason"]


def test_unexpected_response_shape_is_treated_as_no_results(monkeypatch):
    monkeypatch.setattr(settings, "brave_search_api_key", "test-key")
    response = Mock()
    response.raise_for_status.return_value = None
    response.json.return_value = {"web": {"results": "unexpected"}}

    with patch("app.tools.official_domain_search.requests.get", return_value=response):
        result = discover_official_domain_candidates("Unknown Company")

    assert result["status"] == "not_found"
    assert result["candidate_domains"] == []


def test_country_filter_is_optional_and_explicit(monkeypatch):
    monkeypatch.setattr(settings, "brave_search_api_key", "test-key")
    response = Mock()
    response.raise_for_status.return_value = None
    response.json.return_value = {
        "web": {"results": [{"url": "https://www.canada.ca/"}]}
    }

    with patch(
        "app.tools.official_domain_search.requests.get", return_value=response
    ) as get:
        result = discover_official_domain_candidates(
            "Canada Revenue Agency", "mail.canada.ca", country_code="CA"
        )

    assert result["country_code"] == "ca"
    assert result["sender_matches_candidate"] is True
    assert result["matching_candidate"] == "canada.ca"
    assert get.call_args.kwargs["params"]["country"] == "ca"


def test_invalid_country_does_not_call_network(monkeypatch):
    monkeypatch.setattr(settings, "brave_search_api_key", "test-key")

    with patch("app.tools.official_domain_search.requests.get") as get:
        result = discover_official_domain_candidates(
            "Canada Revenue Agency", country_code="Canada"
        )

    get.assert_not_called()
    assert result["status"] == "invalid"


def test_api_error_degrades_gracefully(monkeypatch):
    monkeypatch.setattr(settings, "brave_search_api_key", "test-key")

    with patch(
        "app.tools.official_domain_search.requests.get",
        side_effect=requests.Timeout("slow"),
    ):
        result = discover_official_domain_candidates("Stripe")

    assert result["status"] == "unavailable"
    assert result["candidate_domains"] == []
    assert "Timeout" in result["reason"]


def test_invalid_brand_does_not_call_network(monkeypatch):
    monkeypatch.setattr(settings, "brave_search_api_key", "test-key")

    with patch("app.tools.official_domain_search.requests.get") as get:
        result = discover_official_domain_candidates(" ")

    get.assert_not_called()
    assert result["status"] == "invalid"


def test_command_line_output_is_json_when_unconfigured(monkeypatch, capsys):
    monkeypatch.setattr(settings, "brave_search_api_key", None)

    exit_code = main(["Stripe", "stripe-alert.test"])

    output = json.loads(capsys.readouterr().out)
    assert exit_code == 0
    assert output["status"] == "not_configured"
    assert output["brand_name"] == "Stripe"

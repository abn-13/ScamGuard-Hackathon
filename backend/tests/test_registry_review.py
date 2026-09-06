import json
from datetime import datetime, timezone

import pytest

from app.tools.domain_check import _load_brand_profiles
from app.tools.registry_review import (
    approve_registry_candidate,
    build_registry_update_proposal,
)


def test_search_builds_review_proposal_without_trusting_candidate(monkeypatch):
    monkeypatch.setattr(
        "app.tools.registry_review.discover_official_domain_candidates",
        lambda *args, **kwargs: {
            "status": "candidates",
            "candidate_domains": ["stripe.com"],
            "candidate_evidence": [
                {"domain": "stripe.com", "url": "https://stripe.com/"}
            ],
        },
    )

    result = build_registry_update_proposal(
        "Stripe", now=datetime(2026, 9, 6, tzinfo=timezone.utc)
    )

    assert result["status"] == "pending_review"
    assert result["candidate_domains"] == ["stripe.com"]
    assert result["comparison_tokens"] == ["stripe"]
    assert result["regions"] == ["global"]
    assert "unverified" in result["warning"]


def test_approved_candidate_is_written_to_separate_additions_file(tmp_path):
    proposal = {
        "proposal_id": "2026-09-06-paypal",
        "status": "pending_review",
        "brand": "PayPal",
        "candidate_domains": ["paypal-new.com"],
        "comparison_tokens": ["paypal"],
        "regions": ["global"],
    }
    additions = tmp_path / "additions.json"

    approved = approve_registry_candidate(
        proposal,
        "paypal-new.com",
        "https://www.paypal-new.com/security",
        additions_path=additions,
        now=datetime(2026, 9, 6, tzinfo=timezone.utc),
    )

    assert approved["status"] == "approved"
    assert json.loads(additions.read_text(encoding="utf-8"))[0]["domain"] == "paypal-new.com"


def test_approval_rejects_unrelated_review_source(tmp_path):
    proposal = {
        "proposal_id": "test",
        "status": "pending_review",
        "brand": "PayPal",
        "candidate_domains": ["paypal-new.com"],
        "comparison_tokens": ["paypal"],
        "regions": ["global"],
    }

    with pytest.raises(ValueError, match="hosted on the approved domain"):
        approve_registry_candidate(
            proposal,
            "paypal-new.com",
            "https://attacker.example/claim",
            additions_path=tmp_path / "additions.json",
        )


def test_domain_loader_merges_only_approved_additions(tmp_path):
    registry = tmp_path / "registry.json"
    additions = tmp_path / "additions.json"
    registry.write_text(
        json.dumps(
            [
                {
                    "brand": "Example",
                    "official_domains": ["example.com"],
                    "comparison_tokens": ["example"],
                    "regions": ["global"],
                    "source": "https://example.com",
                }
            ]
        ),
        encoding="utf-8",
    )
    additions.write_text(
        json.dumps(
            [
                {"brand": "Example", "domain": "example.org", "status": "approved"},
                {"brand": "Example", "domain": "evil.example", "status": "pending_review"},
            ]
        ),
        encoding="utf-8",
    )

    profiles = _load_brand_profiles(registry, additions)

    assert profiles[0]["official_domains"] == ["example.com", "example.org"]


def test_domain_loader_adds_a_reviewed_new_brand(tmp_path):
    registry = tmp_path / "registry.json"
    additions = tmp_path / "additions.json"
    registry.write_text("[]", encoding="utf-8")
    additions.write_text(
        json.dumps(
            [
                {
                    "brand": "Stripe",
                    "domain": "stripe.com",
                    "comparison_tokens": ["stripe"],
                    "regions": ["global"],
                    "source": "https://stripe.com",
                    "status": "approved",
                }
            ]
        ),
        encoding="utf-8",
    )

    profiles = _load_brand_profiles(registry, additions)

    assert profiles == (
        {
            "brand": "Stripe",
            "official_domains": ["stripe.com"],
            "comparison_tokens": ["stripe"],
            "regions": ["global"],
            "source": "https://stripe.com",
        },
    )

"""Optional official-domain candidate discovery through Brave Search."""

import argparse
import json
import re
from typing import Dict, List, Optional, Sequence
from urllib.parse import urlsplit

import requests
from strands import tool

from ..config import settings
from .domain_utils import normalize_domain
from .ttl_cache import TTLCache


SEARCH_ENDPOINT = "https://api.search.brave.com/res/v1/web/search"
MAX_RESULTS = 5
REQUEST_TIMEOUT_SECONDS = 8
SEARCH_CACHE_TTL_SECONDS = 6 * 60 * 60
HTTP_USER_AGENT = (
    "ScamGuard-Hackathon/Task3b "
    "(https://github.com/abn-13/ScamGuard-Hackathon)"
)
_COUNTRY_CODE_RE = re.compile(r"^[a-z]{2}$")
_search_cache: TTLCache[dict] = TTLCache(SEARCH_CACHE_TTL_SECONDS)


def _candidate_domain(url: str) -> Optional[str]:
    """Extract a normalized hostname from one HTTPS/HTTP search result."""
    try:
        parsed = urlsplit(url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            return None
        domain = parsed.hostname.lower().rstrip(".")
        if domain.startswith("www."):
            domain = domain[4:]
        return normalize_domain(domain)
    except (AttributeError, TypeError, ValueError):
        return None


def _normalize_domain(domain: str) -> str:
    return normalize_domain(domain) or ""


def _matching_candidate(sender_domain: str, candidates: List[str]) -> Optional[str]:
    """Find an exact or parent-domain candidate for the sender hostname."""
    for candidate in candidates:
        if sender_domain == candidate or sender_domain.endswith("." + candidate):
            return candidate
    return None


def _result(
    brand_name: str,
    sender_domain: str,
    status: str,
    candidate_domains: List[str],
    reason: str,
    country_code: str = "",
    candidate_evidence: Optional[List[dict]] = None,
    cached: bool = False,
) -> Dict[str, object]:
    matching_candidate = _matching_candidate(sender_domain, candidate_domains)
    return {
        "brand_name": brand_name,
        "sender_domain": sender_domain,
        "country_code": country_code or None,
        "status": status,
        "candidate_domains": candidate_domains,
        "candidate_evidence": candidate_evidence or [],
        "sender_matches_candidate": matching_candidate is not None,
        "matching_candidate": matching_candidate,
        "cached": cached,
        "reason": reason,
    }


def discover_official_domain_candidates(
    brand_name: str, sender_domain: str = "", country_code: str = ""
) -> Dict[str, object]:
    """Search for candidate official domains without treating them as trusted.

    This function deliberately returns only hostnames, not page text or search
    snippets. A search-engine result is supporting evidence for the Agent and
    is never written into the local verified-brand registry automatically.
    """
    normalized_brand = " ".join((brand_name or "").split())
    normalized_sender = _normalize_domain(sender_domain)
    normalized_country = (country_code or "").strip().lower()

    if len(normalized_brand) < 2 or len(normalized_brand) > 80:
        return _result(
            normalized_brand,
            normalized_sender,
            "invalid",
            [],
            "Provide an organization or brand name between 2 and 80 characters.",
            normalized_country,
        )

    if normalized_country and not _COUNTRY_CODE_RE.fullmatch(normalized_country):
        return _result(
            normalized_brand,
            normalized_sender,
            "invalid",
            [],
            "Country code must be an optional two-letter code such as us, ca, gb, or au.",
            normalized_country,
        )

    if not settings.brave_search_api_key:
        return _result(
            normalized_brand,
            normalized_sender,
            "not_configured",
            [],
            "Official-domain search is optional and BRAVE_SEARCH_API_KEY is not configured.",
            normalized_country,
        )

    cache_key = (normalized_brand.casefold(), normalized_country)
    cached_search = _search_cache.get(cache_key)
    if cached_search is not None:
        return _result(
            normalized_brand,
            normalized_sender,
            cached_search["status"],
            cached_search["candidate_domains"],
            cached_search["reason"],
            normalized_country,
            cached_search["candidate_evidence"],
            cached=True,
        )

    search_params = {
        "q": f'"{normalized_brand}" official website',
        "count": MAX_RESULTS,
        "safesearch": "strict",
    }
    if normalized_country:
        search_params["country"] = normalized_country

    try:
        response = requests.get(
            SEARCH_ENDPOINT,
            headers={
                "Accept": "application/json",
                "User-Agent": HTTP_USER_AGENT,
                "X-Subscription-Token": settings.brave_search_api_key,
            },
            params=search_params,
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
        response.raise_for_status()
        payload = response.json()
    except (requests.RequestException, ValueError) as exc:
        return _result(
            normalized_brand,
            normalized_sender,
            "unavailable",
            [],
            f"Official-domain search is temporarily unavailable: {type(exc).__name__}.",
            normalized_country,
        )

    web_results = []
    if isinstance(payload, dict) and isinstance(payload.get("web"), dict):
        possible_results = payload["web"].get("results", [])
        if isinstance(possible_results, list):
            web_results = possible_results

    candidates: List[str] = []
    evidence: List[dict] = []
    for item in web_results:
        if not isinstance(item, dict):
            continue
        domain = _candidate_domain(item.get("url", ""))
        if domain and domain not in candidates:
            candidates.append(domain)
            evidence.append({"domain": domain, "url": item["url"]})
        if len(candidates) == MAX_RESULTS:
            break

    if not candidates:
        result = _result(
            normalized_brand,
            normalized_sender,
            "not_found",
            [],
            (
                "No candidate official domain was found; this is not evidence "
                "that the sender is malicious."
            ),
            normalized_country,
        )
        _search_cache.set(cache_key, result)
        return result

    result = _result(
        normalized_brand,
        normalized_sender,
        "candidates",
        candidates,
        (
            "These are unverified search candidates. Compare multiple signals "
            "and never treat one result as proof of authenticity."
        ),
        normalized_country,
        evidence,
    )
    _search_cache.set(cache_key, result)
    return result


@tool
def search_official_domain_candidates(
    brand_name: str, sender_domain: str = "", country_code: str = ""
) -> dict:
    """Find possible official domains for an organization not in the local list.

    Use this only for an email whose local sender-domain verdict is unknown and
    whose message or display name clearly claims an organization. Results are
    unverified candidates, never an allowlist or proof that an email is safe.

    Args:
        brand_name: Organization or brand claimed by the email.
        sender_domain: Domain from the precomputed sender-domain assessment.
        country_code: Optional two-letter country code when the message provides
            clear regional context. Leave blank for globally neutral search.
    """
    return discover_official_domain_candidates(
        brand_name, sender_domain, country_code=country_code
    )


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Search for unverified official-domain candidates."
    )
    parser.add_argument("brand_name", help='Claimed organization, e.g. "Stripe"')
    parser.add_argument("sender_domain", nargs="?", default="")
    parser.add_argument(
        "--country", default="", help="Optional two-letter regional search code"
    )
    args = parser.parse_args(argv)
    print(
        json.dumps(
            discover_official_domain_candidates(
                args.brand_name, args.sender_domain, country_code=args.country
            ),
            indent=2,
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

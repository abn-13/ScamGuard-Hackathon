"""Optional DNS, RDAP age, and certificate-transparency domain enrichment."""

import argparse
import json
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from typing import Optional, Sequence
from urllib.parse import quote, urljoin

import requests
from strands import tool

from ..config import settings
from .domain_check import extract_sender_domain
from .domain_utils import normalize_domain, registrable_domain
from .ttl_cache import TTLCache


DNS_ENDPOINT = "https://dns.google/resolve"
IANA_RDAP_BOOTSTRAP = "https://data.iana.org/rdap/dns.json"
CERTIFICATE_ENDPOINT = "https://api.certspotter.com/v1/issuances"
REQUEST_TIMEOUT_SECONDS = 5
DOMAIN_CACHE_TTL_SECONDS = 6 * 60 * 60
BOOTSTRAP_CACHE_TTL_SECONDS = 24 * 60 * 60
NEW_DOMAIN_DAYS = 60
HTTP_USER_AGENT = (
    "ScamGuard-Hackathon/Task3b "
    "(https://github.com/abn-13/ScamGuard-Hackathon)"
)

_domain_cache: TTLCache[dict] = TTLCache(DOMAIN_CACHE_TTL_SECONDS)
_bootstrap_cache: TTLCache[dict] = TTLCache(BOOTSTRAP_CACHE_TTL_SECONDS, max_entries=1)


def _dns_summary(domain: str) -> dict:
    try:
        response = requests.get(
            DNS_ENDPOINT,
            params={"name": domain, "type": "MX", "cd": "false", "do": "false"},
            headers={"Accept": "application/json", "User-Agent": HTTP_USER_AGENT},
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict):
            raise ValueError("unexpected DNS response")
    except (requests.RequestException, ValueError) as exc:
        return {
            "status": "unavailable",
            "has_mx": None,
            "dnssec_authenticated": None,
            "reason": f"DNS lookup unavailable: {type(exc).__name__}.",
        }

    dns_status = payload.get("Status")
    answers = payload.get("Answer") if isinstance(payload.get("Answer"), list) else []
    has_mx = any(item.get("type") == 15 for item in answers if isinstance(item, dict))
    if dns_status == 3:
        status = "nxdomain"
        reason = "The registrable domain does not currently exist in DNS."
    elif dns_status == 0:
        status = "resolved" if has_mx else "no_mx"
        reason = (
            "The domain publishes an MX mail record."
            if has_mx
            else "The domain resolved but no MX mail record was returned."
        )
    else:
        status = "dns_error"
        reason = f"The DNS service returned response status {dns_status}."
    return {
        "status": status,
        "has_mx": has_mx,
        "dnssec_authenticated": bool(payload.get("AD")),
        "reason": reason,
    }


def _rdap_base_url(domain: str) -> Optional[str]:
    bootstrap = _bootstrap_cache.get("dns")
    if bootstrap is None:
        response = requests.get(
            IANA_RDAP_BOOTSTRAP,
            headers={"Accept": "application/json", "User-Agent": HTTP_USER_AGENT},
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
        response.raise_for_status()
        bootstrap = response.json()
        if not isinstance(bootstrap, dict):
            raise ValueError("unexpected RDAP bootstrap response")
        _bootstrap_cache.set("dns", bootstrap)

    labels = domain.split(".")
    best_match: tuple[int, str] = (0, "")
    for service in bootstrap.get("services", []):
        if not isinstance(service, list) or len(service) != 2:
            continue
        suffixes, urls = service
        if not isinstance(suffixes, list) or not isinstance(urls, list):
            continue
        for suffix in suffixes:
            suffix_labels = str(suffix).lower().split(".")
            if (
                labels[-len(suffix_labels) :] == suffix_labels
                and len(suffix_labels) > best_match[0]
            ):
                https_url = next(
                    (url for url in urls if isinstance(url, str) and url.startswith("https://")),
                    "",
                )
                if https_url:
                    best_match = (len(suffix_labels), https_url)
    return best_match[1] or None


def _parse_rdap_datetime(value: str) -> Optional[datetime]:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (AttributeError, TypeError, ValueError):
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _rdap_summary(domain: str, now: Optional[datetime] = None) -> dict:
    try:
        base_url = _rdap_base_url(domain)
        if not base_url:
            return {
                "status": "not_supported",
                "created_at": None,
                "age_days": None,
                "is_newly_registered": None,
                "reason": "No authoritative RDAP service is listed for this suffix.",
            }
        response = requests.get(
            urljoin(base_url.rstrip("/") + "/", "domain/" + quote(domain, safe="")),
            headers={
                "Accept": "application/rdap+json, application/json",
                "User-Agent": HTTP_USER_AGENT,
            },
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict):
            raise ValueError("unexpected RDAP response")
    except (requests.RequestException, ValueError) as exc:
        return {
            "status": "unavailable",
            "created_at": None,
            "age_days": None,
            "is_newly_registered": None,
            "reason": f"Registration-age lookup unavailable: {type(exc).__name__}.",
        }

    created = None
    for event in payload.get("events", []):
        if isinstance(event, dict) and event.get("eventAction") == "registration":
            created = _parse_rdap_datetime(event.get("eventDate"))
            if created:
                break
    if not created:
        return {
            "status": "no_registration_date",
            "created_at": None,
            "age_days": None,
            "is_newly_registered": None,
            "reason": "RDAP responded but did not publish a registration date.",
        }

    current = now or datetime.now(timezone.utc)
    if current.tzinfo is None:
        current = current.replace(tzinfo=timezone.utc)
    age_days = max(0, (current.astimezone(timezone.utc) - created).days)
    is_new = age_days < NEW_DOMAIN_DAYS
    return {
        "status": "found",
        "created_at": created.isoformat().replace("+00:00", "Z"),
        "age_days": age_days,
        "is_newly_registered": is_new,
        "reason": (
            f"The domain was registered {age_days} days ago."
            + (" A very new domain can increase impersonation concern." if is_new else "")
        ),
    }


def _certificate_summary(domain: str) -> dict:
    headers = {"Accept": "application/json", "User-Agent": HTTP_USER_AGENT}
    if settings.certspotter_api_token:
        headers["Authorization"] = f"Bearer {settings.certspotter_api_token}"
    try:
        response = requests.get(
            CERTIFICATE_ENDPOINT,
            params={
                "domain": domain,
                "include_subdomains": "false",
                "expand": "dns_names",
            },
            headers=headers,
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, list):
            raise ValueError("unexpected certificate response")
    except (requests.RequestException, ValueError) as exc:
        return {
            "status": "unavailable",
            "certificate_count": None,
            "has_certificates": None,
            "reason": f"Certificate-transparency lookup unavailable: {type(exc).__name__}.",
        }

    count = len(payload)
    return {
        "status": "found" if count else "not_found",
        "certificate_count": count,
        "has_certificates": count > 0,
        "reason": (
            f"Certificate Transparency contains {count} returned issuance record(s). "
            "Certificate presence does not prove that an email is legitimate."
        ),
    }


def _normalize_input(value: str) -> Optional[str]:
    if "@" in (value or ""):
        return extract_sender_domain(value)
    return normalize_domain(value)


def lookup_domain_intelligence(value: str) -> dict:
    """Collect bounded, cached domain metadata without assigning final risk."""
    domain = _normalize_input(value)
    root = registrable_domain(domain or "")
    if not domain or not root:
        return {
            "status": "invalid",
            "domain": domain,
            "registrable_domain": root,
            "cached": False,
            "dns": None,
            "registration": None,
            "certificate_transparency": None,
            "signals": [],
            "reason": "Provide a valid public sender domain or email address.",
        }

    if not settings.domain_intelligence_enabled:
        return {
            "status": "not_configured",
            "domain": domain,
            "registrable_domain": root,
            "cached": False,
            "dns": None,
            "registration": None,
            "certificate_transparency": None,
            "signals": [],
            "reason": (
                "External domain intelligence is disabled. Set "
                "DOMAIN_INTELLIGENCE_ENABLED=true to opt in."
            ),
        }

    cached = _domain_cache.get(root)
    if cached is not None:
        cached["cached"] = True
        return cached

    with ThreadPoolExecutor(max_workers=3) as executor:
        dns_future = executor.submit(_dns_summary, root)
        rdap_future = executor.submit(_rdap_summary, root)
        certificate_future = executor.submit(_certificate_summary, root)
        dns = dns_future.result()
        registration = rdap_future.result()
        certificates = certificate_future.result()

    signals = []
    if dns["status"] == "nxdomain":
        signals.append(
            {
                "type": "domain_not_in_dns",
                "strength": "moderate",
                "reason": "The domain does not currently exist in DNS.",
            }
        )
    if registration.get("is_newly_registered") is True:
        signals.append(
            {
                "type": "newly_registered_domain",
                "strength": "moderate",
                "reason": f"The domain is only {registration['age_days']} days old.",
            }
        )

    available = (
        dns.get("status") in {"resolved", "no_mx", "nxdomain"}
        or registration.get("status") in {"found", "no_registration_date"}
        or certificates.get("status") in {"found", "not_found"}
    )
    result = {
        "status": "complete" if available else "unavailable",
        "domain": domain,
        "registrable_domain": root,
        "cached": False,
        "dns": dns,
        "registration": registration,
        "certificate_transparency": certificates,
        "signals": signals,
        "reason": (
            "External domain metadata is supporting evidence only. Missing DNS, a young "
            "domain, or no certificate must be interpreted with the message and identity checks."
        ),
    }
    _domain_cache.set(root, result)
    return result


@tool
def check_domain_intelligence(domain: str) -> dict:
    """Check DNS, registration age, and certificate records for an email domain.

    Use this only when local checks leave an organization-claiming email unknown
    or when another signal is suspicious. Results are supporting evidence, never
    proof that the sender is safe or malicious.

    Args:
        domain: Sender email address or domain from the local assessment.
    """
    return lookup_domain_intelligence(domain)


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Collect optional domain intelligence.")
    parser.add_argument("domain", help="Sender email address or domain")
    args = parser.parse_args(argv)
    print(json.dumps(lookup_domain_intelligence(args.domain), indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

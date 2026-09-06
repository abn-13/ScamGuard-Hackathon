"""Generate and explicitly approve official-domain registry update proposals."""

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional, Sequence
from urllib.parse import urlsplit

from .domain_utils import domains_share_registration, normalize_domain
from .official_domain_search import discover_official_domain_candidates


DEFAULT_ADDITIONS_PATH = (
    Path(__file__).resolve().parent.parent / "data" / "reviewed_domain_additions.json"
)
_SAFE_NAME_RE = re.compile(r"[^a-z0-9]+")
_COMPARISON_TOKEN_RE = re.compile(r"^[a-z0-9]{3,40}$")


def _timestamp(now: Optional[datetime] = None) -> str:
    current = now or datetime.now(timezone.utc)
    if current.tzinfo is None:
        current = current.replace(tzinfo=timezone.utc)
    return current.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def build_registry_update_proposal(
    brand_name: str,
    sender_domain: str = "",
    country_code: str = "",
    now: Optional[datetime] = None,
) -> dict:
    """Use search to build a review queue item; never change trusted data."""
    search = discover_official_domain_candidates(
        brand_name, sender_domain, country_code=country_code
    )
    created_at = _timestamp(now)
    slug = _SAFE_NAME_RE.sub("-", brand_name.casefold()).strip("-") or "brand"
    compact_token = _SAFE_NAME_RE.sub("", brand_name.casefold())
    comparison_tokens = [compact_token] if _COMPARISON_TOKEN_RE.fullmatch(compact_token) else []
    return {
        "proposal_id": f"{created_at[:19].replace(':', '').replace('-', '')}-{slug}",
        "status": "pending_review" if search["candidate_domains"] else "no_candidates",
        "brand": brand_name.strip(),
        "created_at": created_at,
        "candidate_domains": search["candidate_domains"],
        "candidate_evidence": search["candidate_evidence"],
        "comparison_tokens": comparison_tokens,
        "regions": [country_code.strip().lower() or "global"],
        "search_status": search["status"],
        "warning": (
            "Search results are unverified. Confirm ownership on an independently "
            "obtained official source before approval."
        ),
    }


def approve_registry_candidate(
    proposal: dict,
    domain: str,
    source_url: str,
    additions_path: Path = DEFAULT_ADDITIONS_PATH,
    now: Optional[datetime] = None,
) -> dict:
    """Persist one human-reviewed candidate without modifying the base registry."""
    normalized_domain = normalize_domain(domain)
    candidates = proposal.get("candidate_domains", [])
    if not normalized_domain or normalized_domain not in candidates:
        raise ValueError("The domain must be one of this proposal's candidates.")
    if proposal.get("status") != "pending_review":
        raise ValueError("Only a pending review proposal can be approved.")
    brand = str(proposal.get("brand", "")).strip()
    comparison_tokens = proposal.get("comparison_tokens", [])
    if not brand or not isinstance(comparison_tokens, list) or not comparison_tokens:
        raise ValueError("The proposal needs a brand and at least one comparison token.")
    if any(not _COMPARISON_TOKEN_RE.fullmatch(str(token)) for token in comparison_tokens):
        raise ValueError("Comparison tokens must be 3-40 lowercase letters or digits.")

    source = urlsplit(source_url)
    if source.scheme != "https" or not source.hostname:
        raise ValueError("The independent review source must be a valid HTTPS URL.")
    if not domains_share_registration(source.hostname, normalized_domain):
        raise ValueError("The review source must be hosted on the approved domain.")

    if additions_path.exists():
        additions = json.loads(additions_path.read_text(encoding="utf-8"))
    else:
        additions = []
    if not isinstance(additions, list):
        raise ValueError("The reviewed additions file must contain a JSON list.")

    existing = next(
        (
            item
            for item in additions
            if item.get("brand") == brand
            and item.get("domain") == normalized_domain
        ),
        None,
    )
    if existing:
        return existing

    approved = {
        "brand": brand,
        "domain": normalized_domain,
        "comparison_tokens": comparison_tokens,
        "regions": proposal.get("regions", ["global"]),
        "source": source_url,
        "status": "approved",
        "approved_at": _timestamp(now),
        "proposal_id": proposal.get("proposal_id"),
    }
    additions.append(approved)
    additions_path.write_text(
        json.dumps(additions, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    return approved


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Create or approve a reviewed official-domain update."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    propose = subparsers.add_parser("propose")
    propose.add_argument("brand_name")
    propose.add_argument("sender_domain", nargs="?", default="")
    propose.add_argument("--country", default="")
    propose.add_argument("--output", type=Path)

    approve = subparsers.add_parser("approve")
    approve.add_argument("proposal", type=Path)
    approve.add_argument("domain")
    approve.add_argument("source_url")
    approve.add_argument("--additions", type=Path, default=DEFAULT_ADDITIONS_PATH)
    approve.add_argument("--confirm-reviewed", action="store_true")

    args = parser.parse_args(argv)
    if args.command == "propose":
        result = build_registry_update_proposal(
            args.brand_name, args.sender_domain, country_code=args.country
        )
        rendered = json.dumps(result, indent=2, ensure_ascii=False) + "\n"
        if args.output:
            args.output.write_text(rendered, encoding="utf-8")
        else:
            print(rendered, end="")
        return 0

    if not args.confirm_reviewed:
        parser.error("approve requires --confirm-reviewed after independent verification")
    proposal = json.loads(args.proposal.read_text(encoding="utf-8"))
    approved = approve_registry_candidate(
        proposal, args.domain, args.source_url, additions_path=args.additions
    )
    print(json.dumps(approved, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

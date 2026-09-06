"""Offline email sender-domain lookalike detection for ScamGuard."""

import argparse
import json
import re
import unicodedata
from difflib import SequenceMatcher
from email.utils import parseaddr
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

import idna
from confusable_homoglyphs import confusables
from strands import tool

from .domain_utils import normalize_domain


_REGISTRY_PATH = Path(__file__).resolve().parent.parent / "data" / "protected_brands.json"
_ADDITIONS_PATH = (
    Path(__file__).resolve().parent.parent / "data" / "reviewed_domain_additions.json"
)


def _load_brand_profiles(
    registry_path: Path = _REGISTRY_PATH,
    additions_path: Path = _ADDITIONS_PATH,
) -> Tuple[dict, ...]:
    """Load the reviewed, source-linked global registry bundled with the app."""
    with registry_path.open(encoding="utf-8") as registry_file:
        profiles = json.load(registry_file)

    profiles_by_brand = {profile["brand"]: profile for profile in profiles}
    if additions_path.exists():
        with additions_path.open(encoding="utf-8") as additions_file:
            additions = json.load(additions_file)
        for addition in additions:
            if addition.get("status") != "approved":
                continue
            profile = profiles_by_brand.get(addition.get("brand"))
            domain = normalize_domain(addition.get("domain", ""))
            if not domain:
                continue
            if profile and domain not in profile["official_domains"]:
                profile["official_domains"].append(domain)
            elif not profile:
                comparison_tokens = addition.get("comparison_tokens", [])
                if not comparison_tokens:
                    continue
                profile = {
                    "brand": addition.get("brand"),
                    "official_domains": [domain],
                    "comparison_tokens": comparison_tokens,
                    "regions": addition.get("regions", ["global"]),
                    "source": addition.get("source"),
                }
                profiles.append(profile)
                profiles_by_brand[profile["brand"]] = profile
    return tuple(profiles)


BRAND_PROFILES = _load_brand_profiles()
# Kept as a public compatibility view for callers that only need domains.
BRAND_DOMAINS = {
    profile["brand"]: tuple(profile["official_domains"])
    for profile in BRAND_PROFILES
}

SIMILARITY_THRESHOLD = 0.84
_TOKEN_SPLIT_RE = re.compile(r"[.-]+")
_COMMON_SUBSTITUTIONS = str.maketrans(
    {
        "0": "o",
        "1": "l",
        "3": "e",
        "5": "s",
        # Common Cyrillic lookalikes used in internationalized-domain attacks.
        "а": "a",
        "е": "e",
        "і": "i",
        "ӏ": "l",
        "о": "o",
        "р": "p",
        "с": "c",
        "у": "y",
        "х": "x",
    }
)
_LURE_WORDS = {
    "account",
    "alert",
    "banking",
    "benefits",
    "billing",
    "claim",
    "customs",
    "delivery",
    "invoice",
    "login",
    "parcel",
    "payment",
    "pension",
    "prize",
    "redelivery",
    "refund",
    "secure",
    "security",
    "support",
    "tax",
    "tracking",
    "update",
    "verification",
    "verify",
}


def _extract_domain(sender: str) -> Tuple[str, str]:
    """Return the normalized ASCII domain and a Unicode comparison form."""
    _, address = parseaddr(sender or "")
    local_part, separator, raw_domain = address.rpartition("@")
    raw_domain = raw_domain.strip().lower().rstrip(".")

    if not separator or not local_part or not raw_domain or "." not in raw_domain:
        return "", ""

    ascii_domain = normalize_domain(raw_domain)
    if not ascii_domain:
        return "", ""

    try:
        unicode_domain = idna.decode(ascii_domain)
    except idna.IDNAError:
        return "", ""

    return ascii_domain, unicode_domain


def extract_sender_domain(sender: str) -> Optional[str]:
    """Return the normalized ASCII sender domain, or None if it is invalid."""
    domain, _ = _extract_domain(sender)
    return domain or None


def _is_official_domain(domain: str, official_domain: str) -> bool:
    """Return True for an official domain or one of its real subdomains."""
    return domain == official_domain or domain.endswith("." + official_domain)


def _contains_misleading_official_domain(domain: str, official_domain: str) -> bool:
    """Detect an official domain used before an attacker-controlled suffix."""
    domain_labels = domain.split(".")
    official_labels = official_domain.split(".")
    window_size = len(official_labels)

    for index in range(len(domain_labels) - window_size + 1):
        if (
            domain_labels[index : index + window_size] == official_labels
            and index + window_size < len(domain_labels)
        ):
            return True
    return False


def _ascii_confusable(character: str) -> str:
    """Map one non-ASCII glyph to a short ASCII UTS #39-style skeleton."""
    normalized = "".join(
        item
        for item in unicodedata.normalize("NFKD", character).casefold()
        if not unicodedata.combining(item)
    )
    if normalized and normalized.isascii() and normalized.isalnum():
        return normalized

    for candidate in confusables.confusables_data.get(character, []):
        replacement = unicodedata.normalize("NFKC", candidate.get("c", "")).casefold()
        if (
            replacement
            and replacement.isascii()
            and replacement.isalnum()
            and len(replacement) <= 3
        ):
            return replacement
    return character.casefold()


def unicode_confusable_skeleton(value: str) -> str:
    """Build a comparison skeleton using Unicode confusable data."""
    return "".join(
        character if character.isascii() else _ascii_confusable(character)
        for character in unicodedata.normalize("NFKC", value).casefold()
    )


def _comparison_tokens(domain: str) -> List[str]:
    """Return individual and compact hostname tokens for brand comparison."""
    tokens = _TOKEN_SPLIT_RE.split(domain)
    tokens.extend(label.replace("-", "") for label in domain.split(".") if "-" in label)
    return [
        unicode_confusable_skeleton(token).translate(_COMMON_SUBSTITUTIONS)
        for token in tokens
        if token
    ]


def _looks_like_brand(comparison_tokens: Sequence[str], brand_token: str) -> bool:
    """Detect an obvious exact, lure-word, substituted, or near brand token."""
    for normalized in comparison_tokens:
        if normalized == brand_token:
            return True

        if normalized.startswith(brand_token):
            suffix = normalized[len(brand_token) :]
            if suffix in _LURE_WORDS:
                return True
        if normalized.endswith(brand_token):
            prefix = normalized[: -len(brand_token)]
            if prefix in _LURE_WORDS:
                return True

        similar_length = abs(len(normalized) - len(brand_token)) <= 2
        if (
            len(brand_token) >= 5
            and similar_length
            and SequenceMatcher(None, normalized, brand_token).ratio()
            >= SIMILARITY_THRESHOLD
        ):
            return True
    return False


def _result(
    domain: Optional[str],
    verdict: str,
    matched_brand: Optional[str],
    reason: str,
) -> Dict[str, Optional[str]]:
    """Build the stable result contract returned to tests and the Agent."""
    return {
        "domain": domain,
        "verdict": verdict,
        "matched_brand": matched_brand,
        "reason": reason,
    }


def assess_sender_domain(sender: str) -> Dict[str, Optional[str]]:
    """Classify an email sender domain as official, suspicious, or unknown.

    This is the deterministic core used by unit tests. It performs no network
    calls and does not assign the final ScamGuard message risk level.
    """
    domain, comparison_domain = _extract_domain(sender)
    if not domain:
        return _result(
            domain=None,
            verdict="unknown",
            matched_brand=None,
            reason="No valid email sender domain was found.",
        )

    for profile in BRAND_PROFILES:
        brand = profile["brand"]
        for official_domain in profile["official_domains"]:
            if _is_official_domain(domain, official_domain):
                return _result(
                    domain=domain,
                    verdict="official",
                    matched_brand=brand,
                    reason=f"The sender uses an official {brand} domain.",
                )

    comparison_tokens = _comparison_tokens(comparison_domain)
    for profile in BRAND_PROFILES:
        brand = profile["brand"]
        misleading_domain = any(
            _contains_misleading_official_domain(domain, official_domain)
            for official_domain in profile["official_domains"]
        )
        looks_like_brand = any(
            _looks_like_brand(comparison_tokens, token)
            for token in profile["comparison_tokens"]
        )
        if misleading_domain or looks_like_brand:
            return _result(
                domain=domain,
                verdict="suspicious",
                matched_brand=brand,
                reason=(
                    f"The sender domain resembles {brand} branding but is not "
                    "one of its verified domains."
                ),
            )

    return _result(
        domain=domain,
        verdict="unknown",
        matched_brand=None,
        reason="The sender domain does not match a protected brand in the local list.",
    )


@tool
def check_sender_domain(sender: str) -> dict:
    """Check whether an email sender domain impersonates a protected brand.

    Call this for the Sender value of email messages only. A suspicious result
    is evidence of impersonation. Official and unknown results do not prove the
    complete message is safe.

    Args:
        sender: The raw email sender, such as "PayPal <notice@example.com>".

    Returns:
        A dict containing domain, verdict, matched_brand, and reason.
    """
    return assess_sender_domain(sender)


def main(argv: Optional[Sequence[str]] = None) -> int:
    """Run a local sender-domain check from the command line."""
    parser = argparse.ArgumentParser(
        description="Check whether an email sender domain looks like a protected brand."
    )
    parser.add_argument("sender", help='Email sender, e.g. "PayPal <user@example.com>"')
    args = parser.parse_args(argv)
    print(json.dumps(assess_sender_domain(args.sender), indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

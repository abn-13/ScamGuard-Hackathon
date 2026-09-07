"""Shared domain normalization and public-suffix-aware comparison helpers."""

import re
from typing import Optional

import idna
import tldextract


_ASCII_LABEL_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
_PSL_EXTRACTOR = tldextract.TLDExtract(
    suffix_list_urls=(),
    include_psl_private_domains=True,
)


def normalize_domain(value: str) -> Optional[str]:
    """Return a validated lower-case IDNA hostname, or None."""
    raw_domain = (value or "").strip().lower().rstrip(".")
    if not raw_domain or "." not in raw_domain:
        return None
    try:
        ascii_domain = idna.encode(raw_domain, uts46=True).decode("ascii")
    except idna.IDNAError:
        return None
    if len(ascii_domain) > 253:
        return None
    if any(not _ASCII_LABEL_RE.fullmatch(label) for label in ascii_domain.split(".")):
        return None
    return ascii_domain


def registrable_domain(value: str) -> Optional[str]:
    """Return the eTLD+1 using the bundled Public Suffix List snapshot."""
    domain = normalize_domain(value)
    if not domain:
        return None
    extracted = _PSL_EXTRACTOR(domain)
    return extracted.top_domain_under_public_suffix or None


def domains_share_registration(left: str, right: str) -> bool:
    """Whether two hostnames belong to the same registrable domain."""
    left_root = registrable_domain(left)
    right_root = registrable_domain(right)
    return bool(left_root and right_root and left_root == right_root)

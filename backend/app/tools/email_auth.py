"""Parse receiver-generated email authentication results for Agent evidence."""

import re
from typing import Dict, Optional

from .domain_check import extract_sender_domain
from .domain_utils import domains_share_registration, normalize_domain


_METHOD_RESULT_RE = re.compile(
    r"\b(dmarc|spf|dkim)\s*=\s*([a-z][a-z0-9_-]*)", re.IGNORECASE
)
_HEADER_FROM_RE = re.compile(r"\bheader\.from\s*=\s*([^\s;()]+)", re.IGNORECASE)
_FAIL_RESULTS = {"fail", "permerror", "policy", "softfail"}


def _normalized_header_domain(value: str) -> Optional[str]:
    return normalize_domain((value or "").strip().strip('"\''))


def _result(
    status: str,
    sender_domain: Optional[str],
    dmarc: Optional[str],
    spf: Optional[str],
    dkim: Optional[str],
    header_from: Optional[str],
    header_from_aligned: Optional[bool],
    reason: str,
) -> Dict[str, object]:
    return {
        "status": status,
        "sender_domain": sender_domain,
        "dmarc": dmarc,
        "spf": spf,
        "dkim": dkim,
        "header_from": header_from,
        "header_from_aligned": header_from_aligned,
        "reason": reason,
    }


def assess_email_authentication(
    authentication_results: Optional[str], sender: str
) -> Dict[str, object]:
    """Summarize SPF, DKIM, and DMARC results supplied by the mail provider.

    The caller must supply the Authentication-Results value added by the
    receiving provider inside its trusted boundary. An arbitrary header copied
    from an untrusted part of the message must not be treated as authoritative.
    """
    sender_domain = extract_sender_domain(sender)
    if not authentication_results or not authentication_results.strip():
        return _result(
            "not_provided",
            sender_domain,
            None,
            None,
            None,
            None,
            None,
            "No trusted receiver authentication result was provided.",
        )

    methods = {}
    for method, result in _METHOD_RESULT_RE.findall(authentication_results):
        methods.setdefault(method.lower(), result.lower())

    header_match = _HEADER_FROM_RE.search(authentication_results)
    header_from = (
        _normalized_header_domain(header_match.group(1)) if header_match else None
    )
    dmarc = methods.get("dmarc")
    spf = methods.get("spf")
    dkim = methods.get("dkim")
    header_from_aligned = None
    if header_match and (not sender_domain or not header_from):
        header_from_aligned = False
    elif sender_domain and header_from:
        header_from_aligned = sender_domain == header_from or domains_share_registration(
            sender_domain, header_from
        )

    if header_from_aligned is False:
        return _result(
            "misaligned",
            sender_domain,
            dmarc,
            spf,
            dkim,
            header_from,
            False,
            (
                "The authentication result has an invalid or different From domain "
                "and cannot be used to authenticate this sender."
            ),
        )

    if dmarc and not sender_domain:
        return _result(
            "misaligned",
            sender_domain,
            dmarc,
            spf,
            dkim,
            header_from,
            False,
            "The visible sender has no valid domain, so its DMARC result cannot be associated.",
        )

    if dmarc == "pass":
        return _result(
            "pass",
            sender_domain,
            dmarc,
            spf,
            dkim,
            header_from,
            header_from_aligned,
            "The receiving mail service reports that DMARC passed for the message.",
        )

    if dmarc in _FAIL_RESULTS:
        return _result(
            "fail",
            sender_domain,
            dmarc,
            spf,
            dkim,
            header_from,
            header_from_aligned,
            (
                "The receiving mail service reports that DMARC failed; the "
                "visible From domain was not authenticated."
            ),
        )

    if spf in _FAIL_RESULTS and dkim in _FAIL_RESULTS:
        return _result(
            "fail",
            sender_domain,
            dmarc,
            spf,
            dkim,
            header_from,
            header_from_aligned,
            "Both SPF and DKIM failed and no passing DMARC result was provided.",
        )

    if not methods:
        reason = "The provided value contains no recognized SPF, DKIM, or DMARC result."
    else:
        reason = (
            "Email authentication is inconclusive because no definitive DMARC "
            "result was provided."
        )
    return _result(
        "unknown",
        sender_domain,
        dmarc,
        spf,
        dkim,
        header_from,
        header_from_aligned,
        reason,
    )

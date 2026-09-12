"""Conservative parsing of one trusted receiver Authentication-Results value.

This is an evidence parser, not SPF/DKIM/DMARC verification or API authentication.
The intake must obtain the outer header from the receiving provider. A matching
authserv-id is an additional filter, not cryptographic provenance.
"""

import re
from typing import Optional

from ..config import settings
from .domain_check import extract_sender_domain
from .domain_utils import normalize_domain

_METHOD = re.compile(r"^(dmarc|spf|dkim)\s*=\s*([a-z][a-z0-9_-]*)\b", re.I)
_FROM = re.compile(r'\bheader\.from\s*=\s*("[^"]*"|[^\s;]+)', re.I)


def _clauses(value: str) -> Optional[list[str]]:
    """Split on unquoted semicolons; discard nested RFC comments.

    Comments/quoted reasons can contain fake method tokens or semicolons.
    Malformed comments, quotes, or raw line breaks degrade to unknown.
    This deliberately accepts a bounded subset of RFC 8601, not every extension.
    """
    if len(value) > 16384:
        return None
    value = re.sub(r"\r?\n[ \t]+", " ", value)
    if "\r" in value or "\n" in value:
        return None
    parts, current = [], []
    depth, quoted, escaped = 0, False, False
    for char in value:
        if escaped:
            if not depth:
                current.append(char)
            escaped = False
        elif char == "\\" and (depth or quoted):
            escaped = True
            if not depth:
                current.append(char)
        elif depth:
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
        elif char == '"':
            quoted = not quoted
            current.append(char)
        elif char == "(" and not quoted:
            depth = 1
            current.append(" ")
        elif char == ")" and not quoted:
            return None
        elif char == ";" and not quoted:
            parts.append("".join(current).strip())
            current = []
        else:
            current.append(char)
    if depth or quoted or escaped:
        return None
    parts.append("".join(current).strip())
    return parts


def assess_email_authentication(
    authentication_results: Optional[str], sender: str
) -> dict:
    sender_domain = extract_sender_domain(sender)
    result = {
        "status": "unknown", "sender_domain": sender_domain,
        "dmarc": None, "spf": None, "dkim": None, "header_from": None,
        "header_from_aligned": None, "authserv_id": None,
        "method_results": {"dmarc": [], "spf": [], "dkim": []},
        "reason": "Authentication results are inconclusive.",
    }

    def finish(status: str, reason: str) -> dict:
        result.update(status=status, reason=reason)
        return result

    if not authentication_results or not authentication_results.strip():
        return finish("not_provided", "No trusted receiver authentication result was provided.")
    clauses = _clauses(authentication_results)
    if not clauses or len(clauses) < 2:
        return finish("unknown", "Malformed or unsupported authentication result; ignored.")
    # RFC 8601 permits an optional version following authserv-id; accept version 1.
    match = re.fullmatch(r"([a-z0-9.-]+)(?:\s+1)?", clauses[0].lower())
    service = match.group(1) if match else None
    result["authserv_id"] = service
    if service not in settings.trusted_authserv_ids:
        return finish("untrusted", "Authentication service is not an approved receiving provider; ignored.")

    dmarc_clauses = []
    for clause in clauses[1:]:
        match = _METHOD.match(clause)
        if match:
            method, value = (v.lower() for v in match.groups())
            result["method_results"][method].append(value)
            if method == "dmarc":
                dmarc_clauses.append(clause)
    for method, values in result["method_results"].items():
        # Any passing signature prevents an erroneous "all signatures failed".
        result[method] = "pass" if "pass" in values else (
            values[0] if values and len(set(values)) == 1 else ("mixed" if values else None)
        )
    if len(dmarc_clauses) > 1:
        result["dmarc"] = "ambiguous"
        return finish("unknown", "Multiple DMARC results cannot be uniquely associated; ignored.")
    if not dmarc_clauses:
        return finish("unknown", "No definitive DMARC result; SPF/DKIM alone do not authenticate the visible From domain.")
    if not sender_domain:
        result["header_from_aligned"] = False
        return finish("misaligned", "The visible sender has no valid domain; DMARC cannot be associated.")

    # Mask other quoted values: reason="header.from=paypal.com" is not a property.
    clause = re.sub(
        r'(\b[\w.]+\s*=\s*)"(?:\\.|[^"\\])*"',
        lambda m: m.group(0) if re.fullmatch(r"header\.from\s*=\s*", m.group(1), re.I) else "",
        dmarc_clauses[0],
    )
    from_values = _FROM.findall(clause)
    if len(from_values) != 1:
        return finish("unknown", "DMARC lacks one unambiguous header.from; ignored.")
    header_from = normalize_domain(from_values[0].strip('"'))
    result["header_from"] = header_from
    # Compare reported visible From with actual visible From. This is NOT
    # DKIM/SPF relaxed organizational alignment (already performed by DMARC).
    result["header_from_aligned"] = bool(header_from and header_from == sender_domain)
    if not result["header_from_aligned"]:
        return finish("misaligned", "The reported From domain is invalid or different and cannot be used to authenticate this sender.")
    if result["dmarc"] == "pass":
        return finish("pass", "The receiving mail service reports DMARC passed; message content is not validated.")
    if result["dmarc"] == "fail":
        return finish("fail", "The receiving mail service reports DMARC failed for the visible From domain.")
    return finish("unknown", "DMARC is missing, unavailable, or inconclusive; an error is not a confirmed failure.")

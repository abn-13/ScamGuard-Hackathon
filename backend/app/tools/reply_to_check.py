"""Compare visible From and Reply-To addresses without over-trusting either."""

from email.utils import getaddresses
from typing import Optional

from .domain_check import extract_sender_domain
from .domain_utils import domains_share_registration, registrable_domain


def assess_reply_to_alignment(sender: str, reply_to: Optional[str]) -> dict:
    """Return a weak identity signal when Reply-To leaves the sender's domain.

    A mismatch is deliberately not a final scam verdict: newsletters, support
    platforms, and delegated mail services can use a legitimate different
    Reply-To domain.
    """
    sender_domain = extract_sender_domain(sender)
    sender_root = registrable_domain(sender_domain or "")

    if not reply_to or not reply_to.strip():
        return {
            "status": "not_provided",
            "sender_domain": sender_domain,
            "reply_to_domain": None,
            "reply_to_domains": [],
            "sender_registrable_domain": sender_root,
            "reply_to_registrable_domain": None,
            "reply_to_registrable_domains": [],
            "risk_signal": "none",
            "reason": "No Reply-To address was provided.",
        }

    reply_to_domains = []
    for _, address in getaddresses([reply_to]):
        domain = extract_sender_domain(address)
        if domain and domain not in reply_to_domains:
            reply_to_domains.append(domain)
    reply_to_domain = reply_to_domains[0] if reply_to_domains else None
    reply_to_roots = []
    for domain in reply_to_domains:
        root = registrable_domain(domain)
        if root and root not in reply_to_roots:
            reply_to_roots.append(root)
    reply_to_root = reply_to_roots[0] if reply_to_roots else None
    if not sender_domain or not reply_to_domain or not sender_root or not reply_to_root:
        return {
            "status": "invalid",
            "sender_domain": sender_domain,
            "reply_to_domain": reply_to_domain,
            "reply_to_domains": reply_to_domains,
            "sender_registrable_domain": sender_root,
            "reply_to_registrable_domain": reply_to_root,
            "reply_to_registrable_domains": reply_to_roots,
            "risk_signal": "none",
            "reason": "The From or Reply-To address does not contain a valid public domain.",
        }

    all_aligned = all(
        domains_share_registration(sender_domain, domain) for domain in reply_to_domains
    )
    if all_aligned:
        return {
            "status": "aligned",
            "sender_domain": sender_domain,
            "reply_to_domain": reply_to_domain,
            "reply_to_domains": reply_to_domains,
            "sender_registrable_domain": sender_root,
            "reply_to_registrable_domain": reply_to_root,
            "reply_to_registrable_domains": reply_to_roots,
            "risk_signal": "none",
            "reason": "The From and Reply-To addresses use the same registrable domain.",
        }

    return {
        "status": "mismatch",
        "sender_domain": sender_domain,
        "reply_to_domain": reply_to_domain,
        "reply_to_domains": reply_to_domains,
        "sender_registrable_domain": sender_root,
        "reply_to_registrable_domain": reply_to_root,
        "reply_to_registrable_domains": reply_to_roots,
        "risk_signal": "weak",
        "reason": (
            "Replies would go to a different organization-level domain. This can be "
            "legitimate, but should be checked together with the message and authentication."
        ),
    }

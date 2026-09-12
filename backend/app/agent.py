import json

from pydantic import BaseModel, Field
from strands import Agent
from strands.models import BedrockModel

from .config import settings
from .models import RiskLevel
from .schemas import IncomingMessage
from .tools.domain_check import assess_sender_domain, check_sender_domain
from .tools.domain_intelligence import check_domain_intelligence
from .tools.email_auth import assess_email_authentication
from .tools.link_check import check_url_reputation
from .tools.official_domain_search import search_official_domain_candidates
from .tools.reply_to_check import assess_reply_to_alignment


class Verdict(BaseModel):
    """The agent's judgement about one message. Kept separate from the DB model
    (models.Message) since this is what the LLM returns, not what's stored."""

    risk_level: RiskLevel = Field(description="low, medium, or high")
    reason: str = Field(
        description=(
            "One or two plain-language sentences a non-technical person could "
            "understand; for medium/high risk include one immediate safe action"
        )
    )


SYSTEM_PROMPT = """
You are ScamGuard, an assistant that reads one SMS or email at a time and judges
whether it's a scam attempt targeting a vulnerable person.

Consider: fake urgency, impersonation of a bank/government/company, requests for
passwords/payment/personal info, and whether any link in the message is malicious
-- use the check_url_reputation tool on every URL you find in the message body.

For email messages, use the precomputed sender-domain assessment included with
the message. The check_sender_domain tool is also available if the assessment is
missing. Treat a suspicious lookalike domain as impersonation evidence. An
official or unknown domain result does not prove the complete message is safe.
Do not use this tool for SMS senders.

The protected person may be anywhere in the world, with initial emphasis on
North America. Do not assume Singapore, the United States, or any other country
unless the message provides clear regional evidence. Tax, social-benefit, bank,
healthcare, and parcel-delivery organizations vary by country.

For email messages, also use the precomputed receiver authentication assessment
when provided. A DMARC failure is strong spoofing evidence even if the visible
From address uses an official domain. A DMARC pass means the domain was
authorized to send the message; it does not prove the content, links, or payment
request are safe. A missing or inconclusive result is neutral.
If the authentication result's `header.from` belongs to a different
registrable domain, the result is marked `misaligned` and must not be used as
authentication evidence for the visible sender.

Also use the precomputed From/Reply-To alignment assessment. A different
registrable Reply-To domain is a weak warning, not proof of a scam, because
legitimate mailing and support services sometimes use separate domains.

If an email's precomputed sender-domain verdict is unknown and its display name
or message clearly claims an organization, you may call
search_official_domain_candidates with that organization and the sender domain.
Pass a two-letter country code only when the email gives clear regional context;
otherwise leave it blank so the search is not biased toward one country.
Use it only when the comparison could change your verdict. Its search results
are unverified candidates: compare multiple signals, never treat one candidate
as proof that the sender is official, and never add it to a trusted list. If the
search is not configured, unavailable, or finds nothing, continue with the
other evidence instead of assuming the message is malicious.

For an unknown or otherwise suspicious sender domain, you may call
check_domain_intelligence to inspect public-suffix-aware DNS, authoritative RDAP
registration age, and Certificate Transparency metadata. Use external metadata
only as supporting evidence. A newly registered or nonexistent domain increases
concern; an old domain, DNS record, or certificate never proves the email is safe.
If this optional tool is disabled or unavailable, continue using local evidence.

You are also told whether the sender is already known to the recipient (a saved
contact, or someone they've corresponded with before). An unknown sender is not
automatically suspicious -- banks, couriers, and OTP codes normally come from
unknown senders. A KNOWN sender suddenly asking for money, passwords, or personal
details is a stronger signal, not a weaker one -- it usually means either an
impersonation attempt or an account compromise.

Always explain your reasoning in one or two plain-language sentences a
non-technical person could understand. For medium or high risk, include one
immediate safe action, such as not clicking or paying and contacting the
organization through a separately obtained official app, website, or phone
number. Never ask the user to interpret DMARC/SPF/DKIM themselves. Never just
say "blocked" or "flagged" with no reason.
""".strip()

# TODO(Task 3 owner): this is where prompt quality gets tuned -- feed it real
# scam examples *and* real legitimate messages so it doesn't cry wolf, and
# adjust the wording above until verdicts look right for both.


def _build_agent() -> Agent:
    if not settings.bedrock_model_id:
        raise RuntimeError("BEDROCK_MODEL_ID not configured (check .env)")
    model = BedrockModel(
        model_id=settings.bedrock_model_id,
        region_name=settings.aws_region,
        temperature=0.2,
    )
    return Agent(
        model=model,
        tools=[
            check_url_reputation,
            check_sender_domain,
            search_official_domain_candidates,
            check_domain_intelligence,
        ],
        system_prompt=SYSTEM_PROMPT,
    )


def get_agent() -> Agent:
    """A fresh Agent per call, deliberately not cached/shared -- a Strands Agent
    holds per-invocation state and can't handle concurrent calls on the same
    instance. Building one is cheap (no network call happens until it's actually
    invoked), so this lets concurrent /check-message requests run independently
    instead of queuing behind a shared, single-flight instance."""
    return _build_agent()


def _apply_sender_identity_floor(
    verdict: Verdict, domain_assessment: dict, authentication_assessment: dict
) -> Verdict:
    """Ensure strong deterministic spoof evidence cannot receive a low verdict."""
    if verdict.risk_level != RiskLevel.low:
        return verdict

    if authentication_assessment.get("status") == "fail":
        return Verdict(
            risk_level=RiskLevel.medium,
            reason=(
                "The visible sender failed the receiving mail service's identity "
                "checks and may be forged. Do not click or pay; contact the "
                "organization through a trusted app, website, or phone number."
            ),
        )

    if domain_assessment.get("verdict") == "suspicious":
        brand = domain_assessment.get("matched_brand") or "a trusted organization"
        return Verdict(
            risk_level=RiskLevel.medium,
            reason=(
                f"The sender domain may be impersonating {brand}. Do not click or "
                "pay; contact the organization through a trusted app, website, or "
                "phone number."
            ),
        )

    return verdict


def run_pipeline(message: IncomingMessage) -> Verdict:
    """Run one message through the reasoning agent and return a structured verdict."""
    agent = get_agent()
    domain_context = ""
    domain_assessment = {}
    authentication_assessment = {}
    reply_to_assessment = {}
    if message.source.value == "email":
        domain_assessment = assess_sender_domain(message.sender)
        authentication_assessment = assess_email_authentication(
            message.authentication_results, message.sender
        )
        reply_to_assessment = assess_reply_to_alignment(message.sender, message.reply_to)
        domain_context = (
            "Sender domain assessment (computed by ScamGuard):\n"
            f"{json.dumps(domain_assessment, ensure_ascii=False)}\n"
            "Receiver email authentication assessment (computed by ScamGuard):\n"
            f"{json.dumps(authentication_assessment, ensure_ascii=False)}\n"
            "From/Reply-To alignment assessment (computed by ScamGuard):\n"
            f"{json.dumps(reply_to_assessment, ensure_ascii=False)}\n"
        )

    prompt = (
        f"Source: {message.source.value}\n"
        f"Sender: {message.sender}\n"
        f"Known sender: {message.is_known_sender}\n"
        + (f"Subject: {message.subject}\n" if message.subject else "")
        + domain_context
        + f"Message:\n{message.body_text}"
    )
    result = agent(prompt, structured_output_model=Verdict)
    return _apply_sender_identity_floor(
        result.structured_output, domain_assessment, authentication_assessment
    )

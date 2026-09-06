from typing import Optional

from pydantic import BaseModel, Field
from strands import Agent
from strands.models import BedrockModel

from .config import settings
from .models import RiskLevel
from .schemas import IncomingMessage
from .tools.link_check import check_url_reputation


class Verdict(BaseModel):
    """The agent's judgement about one message. Kept separate from the DB model
    (models.Message) since this is what the LLM returns, not what's stored."""

    risk_level: RiskLevel = Field(description="low, medium, or high")
    reason: str = Field(
        description="One or two plain-language sentences a non-technical person could understand"
    )


SYSTEM_PROMPT = """
You are ScamGuard, an assistant that reads one SMS or email at a time and judges
whether it's a scam attempt targeting a vulnerable person.

Consider: fake urgency, impersonation of a bank/government/company, requests for
passwords/payment/personal info, and whether any link in the message is malicious
-- use the check_url_reputation tool on every URL you find in the message body.

You are also told whether the sender is already known to the recipient (a saved
contact, or someone they've corresponded with before). An unknown sender is not
automatically suspicious -- banks, couriers, and OTP codes normally come from
unknown senders. A KNOWN sender suddenly asking for money, passwords, or personal
details is a stronger signal, not a weaker one -- it usually means either an
impersonation attempt or an account compromise.

Always explain your reasoning in one or two plain-language sentences a
non-technical person could understand. Never just say "blocked" or "flagged"
with no reason.
""".strip()

# TODO(Task 3 owner): this is where prompt quality gets tuned -- feed it real
# scam examples *and* real legitimate messages so it doesn't cry wolf, and
# adjust the wording above until verdicts look right for both.

_agent: Optional[Agent] = None


def _build_agent() -> Agent:
    if not settings.bedrock_model_id:
        raise RuntimeError("BEDROCK_MODEL_ID not configured (check .env)")
    model = BedrockModel(
        model_id=settings.bedrock_model_id,
        region_name=settings.aws_region,
        temperature=0.2,
    )
    return Agent(model=model, tools=[check_url_reputation], system_prompt=SYSTEM_PROMPT)


def get_agent() -> Agent:
    global _agent
    if _agent is None:
        _agent = _build_agent()
    return _agent


def run_pipeline(message: IncomingMessage) -> Verdict:
    """Run one message through the reasoning agent and return a structured verdict."""
    agent = get_agent()
    prompt = (
        f"Source: {message.source.value}\n"
        f"Sender: {message.sender}\n"
        f"Known sender: {message.is_known_sender}\n"
        + (f"Subject: {message.subject}\n" if message.subject else "")
        + f"Message:\n{message.body_text}"
    )
    result = agent(prompt, structured_output_model=Verdict)
    return result.structured_output

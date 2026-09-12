# ScamGuard Backend

The reasoning pipeline both the SMS and Email intake sources feed into: read message → check any links → judge risk → log it → alert family if it's bad. Runs as one FastAPI server, using [Strands Agents SDK](https://strandsagents.com) + Claude via AWS Bedrock.

## Architecture

```
Android app (sms/, email/) --HTTP--> /check-message --> Strands agent (Claude via Bedrock)
                                           |                    |
                                           |              check_url_reputation tool
                                           v                    (Google Safe Browsing)
                                      SQLite DB
                                    (users, family_members, messages)
                                           |
                                    risk >= medium?
                                           v
                                  Telegram alert to family
```

Three tables, matching what we agreed on: `users` (the protected person), `family_members` (who to alert, FK to a user), `messages` (every SMS/email checked, doubles as the audit log).

## Running it locally

```
cd backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env   # fill in whatever you have -- see "what's already wired up" below
uvicorn app.main:app --reload
```

Then open **http://localhost:8000/docs** — FastAPI's auto-generated Swagger UI, lets you call every endpoint from the browser without writing curl commands.

Run the test suite any time with `pytest` (from inside `backend/`, with the venv active) — it runs fully offline, no AWS/Telegram/Safe Browsing credentials needed.

## What's already built (the plumbing)

Don't need to touch these unless something's actually broken:
- `app/models.py`, `app/db.py` — the three DB tables
- `app/schemas.py` — request/response shapes
- `app/main.py` — the three routes (`/users`, `/family-members`, `/check-message`)
- `app/agent.py` — the Strands agent wiring (model, tools, structured output) — the *shape* of the pipeline is done; the prompt itself is Task 3 below

**Important design note:** "is this sender known?" is computed on the Android side (Contacts lookup for SMS, Gmail history for email) and sent as a plain `is_known_sender` boolean in the request — the backend has no access to either data source directly, so this isn't a backend task.

**Also important:** every credential-gated piece below has a stub that makes it degrade gracefully (returns "unknown"/logs instead of sending) when its env var isn't set yet. That means you can pick up any task below without waiting on someone else's AWS/Telegram/API-key setup to land first.

## Tasks

### Task 1 — Real Google Safe Browsing link check ✅ done
**File:** `app/tools/link_check.py`
Reused the same Google Cloud project as the Gmail OAuth setup — just needed "Safe Browsing API" enabled + a plain API key (no OAuth needed for this one). Verified live: known test malware/phishing URLs come back "malicious," google.com comes back "clean," and a full `/check-message` call with a phishing email correctly returns high risk with the link result reflected in the reasoning.

### Task 2 — Real Telegram family alerts
**File:** `app/alerts.py`
Create a bot via [@BotFather](https://t.me/BotFather) on Telegram (a couple of minutes, gives you a token). Set `TELEGRAM_BOT_TOKEN` in `.env`. Have a family member message the bot once, then hit `https://api.telegram.org/bot<TOKEN>/getUpdates` to read their chat ID out of the response. Register them via `POST /family-members`.
**Done when:** a `/check-message` call that comes back medium/high risk actually messages that person on Telegram.

### Task 3 — split across two people

**3a — Reasoning prompt quality — local implementation complete; live acceptance pending**
**File:** `app/agent.py` (the `SYSTEM_PROMPT` string)
The prompt now defines low/medium/high risk, distinguishes normal OTP delivery from credential requests, handles conflicting evidence conservatively, and treats message content as untrusted data. Local evaluation includes 24 synthetic cases, an optional real-Bedrock runner, and the saved baseline prompt. These fixtures are regression scenarios, not a substitute for reviewed real messages.
**Done when:** you've got a handful of test messages (obvious scams, obviously-fine ones, ambiguous ones) and the risk_level + reason look right for all of them.

Run `python -m evaluations.task3_eval` for offline evidence checks. With team AWS access, run `python -m evaluations.task3_eval --live --repeat 3` and review both risk and explanation quality. Reports do not mark live acceptance complete automatically.

**3b — Domain-spoof check tool**
**Primary files:** `app/tools/domain_check.py`, `app/tools/email_auth.py`,
`app/tools/domain_intelligence.py`, and `app/tools/reply_to_check.py`
Build a Strands `@tool` that flags an email sender's domain as a lookalike of a well-known brand — e.g. `paypa1-verify.com` impersonating PayPal — using a source-linked global registry, Public Suffix List boundaries, Unicode confusable data, and deterministic typo/lookalike rules. Task 3b compares From and Reply-To domains, parses receiver-supplied SPF/DKIM/DMARC results, and can optionally inspect cached Brave Search candidates plus DNS, authoritative RDAP registration age, and Certificate Transparency metadata. External evidence never proves safety. Search-based registry updates go through a generated review proposal and an explicit approval command; they are never silently auto-trusted.
**Done when:** global lookalike, official, unrelated, authentication, Reply-To, public-suffix, cached search, external-intelligence, and reviewed-registry-update cases pass offline tests and the Agent uses each result with the documented trust level.

Contributor and deployment documentation: [`TASK_3B_EMAIL_SENDER_SECURITY.md`](TASK_3B_EMAIL_SENDER_SECURITY.md).

Local follow-up: receiver IDs are checked against `TRUSTED_AUTHSERV_IDS`; multiple DKIM results are preserved; incomplete, conflicting, or errored DMARC evidence is neutral. Gmail intake selects a single matching outer receiver header rather than trusting the first header's position. Live Gmail provenance still needs team end-to-end verification.

### Task 4 — Android → backend wiring
**Files:** `app/src/main/java/com/scamguard/spike/sms/` and `.../email/` in the Android app (separate repo folder, not this one)
Two things per module: (a) compute `is_known_sender` — SMS via `ContactsContract`, email via Gmail thread history/People API — and (b) POST the message to `/check-message` instead of just displaying it, then show the returned risk_level + reason in the UI. For email, also forward the receiving provider's trusted `Authentication-Results` and `Reply-To` values when Gmail exposes them; both backend fields are optional for compatibility.
**Done when:** sending yourself a test SMS/email shows a real verdict from the backend, not just the raw message.

### Task 5 — Confirm AWS Bedrock access ✅ done
Verified working on the team account (`us-east-1`) with `BEDROCK_MODEL_ID=us.anthropic.claude-haiku-4-5-20251001-v1:0` — already the default in `.env.example`. If you're using `aws login` instead of a static IAM key, make sure `botocore[crt]` is installed (it's in `requirements.txt`) — without it boto3 can't read the login session's temporary credentials.

**Gotcha to know about:** a model showing up in `aws bedrock list-foundation-models` or `list-inference-profiles` does **not** mean your account has access to it — that's requested separately per model in the Bedrock console. Sonnet 5 listed fine but returned `AccessDeniedException` on an actual call; Haiku 4.5 worked. Test with a real request, not just the listing command, before assuming a different model ID works for you.

## Notes
- Never commit `.env` — already gitignored.
- `backend/*.db` (the local SQLite file) is also gitignored — everyone gets their own local data.

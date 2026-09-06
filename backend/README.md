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

### Task 1 — Real Google Safe Browsing link check
**File:** `app/tools/link_check.py`
Get an API key: Cloud Console → APIs & Services → Library → enable "Safe Browsing API" → Credentials → Create API key. Set `SAFE_BROWSING_API_KEY` in `.env`. The real API call is already written — just confirm it works.
**Done when:** a `/check-message` call with a link to Google's [test malware URL](https://testsafebrowsing.appspot.com/) comes back "malicious" instead of "unknown."

### Task 2 — Real Telegram family alerts
**File:** `app/alerts.py`
Create a bot via [@BotFather](https://t.me/BotFather) on Telegram (a couple of minutes, gives you a token). Set `TELEGRAM_BOT_TOKEN` in `.env`. Have a family member message the bot once, then hit `https://api.telegram.org/bot<TOKEN>/getUpdates` to read their chat ID out of the response. Register them via `POST /family-members`.
**Done when:** a `/check-message` call that comes back medium/high risk actually messages that person on Telegram.

### Task 3 — Reasoning prompt quality
**File:** `app/agent.py` (the `SYSTEM_PROMPT` string)
This is prompt-engineering, not infra — no new files. Feed it real scam examples *and* real legitimate messages (so it doesn't cry wolf on normal OTPs/delivery notices), and tune the wording until verdicts look right.
**Done when:** you've got a handful of test messages (obvious scams, obviously-fine ones, ambiguous ones) and the risk_level + reason look right for all of them.

### Task 4 — Android → backend wiring
**Files:** `app/src/main/java/com/scamguard/spike/sms/` and `.../email/` in the Android app (separate repo folder, not this one)
Two things per module: (a) compute `is_known_sender` — SMS via `ContactsContract`, email via Gmail thread history/People API — and (b) POST the message to `/check-message` instead of just displaying it, then show the returned risk_level + reason in the UI.
**Done when:** sending yourself a test SMS/email shows a real verdict from the backend, not just the raw message.

### Task 5 — Confirm AWS Bedrock access
No new file — just your `.env`. Fill in `BEDROCK_MODEL_ID` using the command from the "AWS Bedrock Setup" doc, and confirm `/check-message` returns a real verdict instead of the 503 stub error.

## Notes
- Never commit `.env` — already gitignored.
- `backend/*.db` (the local SQLite file) is also gitignored — everyone gets their own local data.

import os
from pathlib import Path

# Must run before ANY test module imports app.config (directly or transitively) --
# conftest.py is loaded by pytest before test collection, so this is the one place that
# reliably wins the race. Previously this line lived only in test_main.py, which worked
# when running that file alone but silently no-op'd when running the full suite: pytest
# collects test files alphabetically, and test_agent_domain_integration.py /
# test_domain_check.py (both earlier) already import app.config transitively -- Settings
# reads DATABASE_URL as a class attribute at that first import, so by the time
# test_main.py's own os.environ assignment ran, the default (real dev scamguard.db) was
# already locked in. Confirmed live: a full `pytest` run was silently writing
# test_check_message_flow_flags_high_risk_and_alerts_family's rows into the real
# backend/scamguard.db instead of a throwaway file.
_TEST_DB_PATH = Path(__file__).resolve().parent.parent / "test_scamguard.db"
os.environ.setdefault("DATABASE_URL", f"sqlite:///{_TEST_DB_PATH.as_posix()}")

# Delete any leftover file from a previous run -- test_main.py's fixtures assume a fresh
# DB (fixed usernames/emails with UNIQUE constraints), so a stale file from an earlier
# `pytest` invocation would fail every subsequent run with IntegrityErrors that have
# nothing to do with whatever's actually being changed.
_TEST_DB_PATH.unlink(missing_ok=True)

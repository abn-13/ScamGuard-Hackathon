"""Offline regression tests must not depend on operator credentials or network."""

import socket
import tempfile
from pathlib import Path

import pytest


@pytest.hookimpl(tryfirst=True)
def pytest_configure(config):
    if config.option.basetemp is None:
        # Sandboxed and interactive Windows accounts can share TEMP and the
        # reported username, but cannot access each other's mode-0700 pytest
        # directories. Give each run its own freshly owned parent instead.
        # The child does not exist, so pytest won't clear an existing directory.
        parent = Path(tempfile.mkdtemp(prefix="scamguard-pytest-"))
        config.option.basetemp = str(parent / "run")


@pytest.fixture(autouse=True)
def offline_environment(monkeypatch):
    from app.config import settings

    monkeypatch.setenv("AWS_EC2_METADATA_DISABLED", "true")
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "offline-test-only")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "offline-test-only")
    monkeypatch.setattr(settings, "trusted_authserv_ids", ("mx.google.com",))
    monkeypatch.setattr(settings, "safe_browsing_api_key", None)
    monkeypatch.setattr(settings, "brave_search_api_key", None)
    monkeypatch.setattr(settings, "domain_intelligence_enabled", False)
    monkeypatch.setattr(settings, "telegram_bot_token", None)

    original_connect = socket.socket.connect

    def no_network(sock, address):
        # Windows asyncio uses a loopback socket pair for its event loop.
        if isinstance(address, tuple) and address[0] in {"127.0.0.1", "::1"}:
            return original_connect(sock, address)
        raise AssertionError("Offline tests must mock network calls.")

    monkeypatch.setattr(socket.socket, "connect", no_network)

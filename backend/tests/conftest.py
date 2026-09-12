"""Offline regression tests must not depend on operator credentials or network."""

import socket

import pytest


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

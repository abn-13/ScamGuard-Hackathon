import requests
from strands import tool

from ..config import settings

SAFE_BROWSING_URL = "https://safebrowsing.googleapis.com/v4/threatMatches:find"


@tool
def check_url_reputation(url: str) -> dict:
    """Check a URL against Google Safe Browsing's threat database.

    Call this once for every link found in the message body, before judging
    overall risk -- a malicious link is a strong signal on its own.

    Args:
        url: the full URL to check, e.g. "http://bit.ly/abc123"

    Returns:
        A dict with "url", "verdict" ("clean" | "malicious" | "unknown"), and
        "threat_types" when malicious.
    """
    api_key = settings.safe_browsing_api_key
    if not api_key:
        # TODO(Task 1 owner): remove this stub once SAFE_BROWSING_API_KEY is set in .env.
        return {
            "url": url,
            "verdict": "unknown",
            "reason": "SAFE_BROWSING_API_KEY not configured yet",
        }

    payload = {
        "client": {"clientId": "scamguard-hackathon", "clientVersion": "1.0"},
        "threatInfo": {
            "threatTypes": [
                "MALWARE",
                "SOCIAL_ENGINEERING",
                "UNWANTED_SOFTWARE",
                "POTENTIALLY_HARMFUL_APPLICATION",
            ],
            "platformTypes": ["ANY_PLATFORM"],
            "threatEntryTypes": ["URL"],
            "threatEntries": [{"url": url}],
        },
    }
    response = requests.post(
        SAFE_BROWSING_URL, params={"key": api_key}, json=payload, timeout=10
    )
    response.raise_for_status()
    matches = response.json().get("matches", [])
    if matches:
        return {
            "url": url,
            "verdict": "malicious",
            "threat_types": [m["threatType"] for m in matches],
        }
    return {"url": url, "verdict": "clean"}

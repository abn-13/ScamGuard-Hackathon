package com.scamguard.spike.backend

/**
 * Points the app at the ScamGuard FastAPI backend (see `backend/` at the repo root).
 * Kept as a plain constant for the spike -- no build variants/flavors yet.
 */
object BackendConfig {

    /**
     * Points at the always-on backend deployed to AWS Lightsail Containers (see
     * backend/README.md "Cloud deployment"), so any teammate's physical device can use
     * the app without your dev machine running. For local backend development instead,
     * temporarily switch to "http://10.0.2.2:8000" (emulator) or your LAN IP (physical
     * device on the same Wi-Fi as `uvicorn app.main:app --reload`).
     */
    const val BASE_URL = "https://scamguard-backend.xnwn17mgscevj.us-east-1.cs.amazonlightsail.com"
}

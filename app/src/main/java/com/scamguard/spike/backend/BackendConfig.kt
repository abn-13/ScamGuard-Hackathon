package com.scamguard.spike.backend

/**
 * Points the app at the ScamGuard FastAPI backend (see `backend/` at the repo root).
 * Kept as plain constants for the spike -- no build variants/flavors yet.
 */
object BackendConfig {

    /**
     * 10.0.2.2 is the Android emulator's alias for the host machine's localhost, so this
     * works out of the box against `uvicorn app.main:app --reload` running on your dev
     * machine. Testing on a physical device? Point this at your machine's LAN IP instead,
     * e.g. "http://192.168.1.23:8000" (same Wi-Fi network, backend reachable on that port).
     */
    const val BASE_URL = "http://10.0.2.2:8000"

    /**
     * The backend requires an existing `users` row -- /check-message 404s otherwise.
     * Create one once via the Swagger UI at "$BASE_URL/docs" -> POST /users, then put its
     * returned `id` here. Defaults to 1, the id you get from the very first user created
     * against a fresh local DB.
     */
    const val USER_ID = 1L
}

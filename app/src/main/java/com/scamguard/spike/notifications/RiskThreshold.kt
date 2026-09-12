package com.scamguard.spike.notifications

/**
 * Single source of truth for "is this risk level worth acting on" -- shared by
 * [RiskNotifier] (on-device notification) and [GuardianAlerter] (SMS to the registered
 * family member), so both fire for exactly the same set of messages. Mirrors the
 * backend's own threshold for alerting family over Telegram (see backend/app/main.py:
 * `verdict.risk_level in (RiskLevel.medium, RiskLevel.high)`).
 */
internal fun isRiskyEnoughToAlert(riskLevel: String): Boolean =
    riskLevel.lowercase() in setOf("medium", "high")

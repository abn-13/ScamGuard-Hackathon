package com.scamguard.spike.notifications

import android.content.Context
import android.util.Log
import com.scamguard.spike.backend.MessageSource

/**
 * TEMPORARY STUB. SmsListActivity/EmailListActivity/MainActivity already reference this
 * class (notification-feature commit), but the real RiskNotifier.kt wasn't actually in that
 * push -- likely just not `git add`ed -- so the app didn't compile. This exists only to
 * restore a working build; replace it entirely once the real implementation lands rather
 * than merging into it.
 */
object RiskNotifier {
    fun notifyIfRisky(
        context: Context,
        source: MessageSource,
        sender: String,
        riskLevel: String,
        reason: String
    ) {
        Log.d(
            "RiskNotifier",
            "[stub -- no real notification shown yet] $riskLevel from $sender ($source): $reason"
        )
    }
}

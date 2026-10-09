package io.github.tuscani712.lanyard.core

/**
 * The pure rule behind the "Keep screen on during transfers" setting.
 *
 * The activity window should hold `FLAG_KEEP_SCREEN_ON` exactly while the
 * setting is on *and* at least one transfer is still live. Everything here is
 * Android-free, so the rule and its logging reasons are unit-tested in :core;
 * `MainActivity` only applies the result to the window.
 */
object KeepScreenOn {
    /** The setting is on and a transfer is live: hold the screen awake. */
    const val REASON_TRANSFER_LIVE = "transfer-live"

    /** The setting is on but no transfer is live: let the screen sleep. */
    const val REASON_DRAINED = "drained"

    /** The setting is off: never hold the screen awake, whatever is running. */
    const val REASON_SETTING_OFF = "setting-off"

    /** Clear reason used by the activity when it stops. */
    const val REASON_ACTIVITY_STOP = "activity-stop"

    /**
     * True while any row counts as a live transfer. Preparing (spooling) counts,
     * as do running or queued sends, receives and downloads.
     */
    fun anyLiveTransfer(rows: List<TransferRecord>): Boolean = rows.any { it.state.isLive }

    /**
     * The decision: keep the screen on only when the setting is enabled and there
     * is live transfer work. A finished row (Done/Failed/Cancelled) never counts.
     */
    fun decide(settingEnabled: Boolean, anyLiveTransfer: Boolean): Boolean =
        settingEnabled && anyLiveTransfer

    /** Why [decide] returned what it did, for the `[transfer] keep-screen-on=` line. */
    fun reason(settingEnabled: Boolean, anyLiveTransfer: Boolean): String = when {
        !settingEnabled -> REASON_SETTING_OFF
        anyLiveTransfer -> REASON_TRANSFER_LIVE
        else -> REASON_DRAINED
    }

    /** The diagnostics line written on each keep-screen-on state change. */
    fun logLine(keepOn: Boolean, reason: String): String =
        "[transfer] keep-screen-on=${if (keepOn) "on" else "off"} reason=$reason"
}

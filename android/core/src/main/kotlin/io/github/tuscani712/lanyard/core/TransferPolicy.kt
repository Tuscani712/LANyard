package io.github.tuscani712.lanyard.core

/** Reports whether the device's active network is metered or mobile. */
fun interface MeteredNetwork {
    fun isMetered(): Boolean
}

object TransferPolicy {
    /**
     * The message to show when a transfer is refused because only Wi-Fi is
     * allowed, or null when it may proceed. Kept pure so a test can drive both
     * sides of the gate with a fake [MeteredNetwork].
     */
    fun wifiOnlyRefusal(wifiOnly: Boolean, metered: Boolean): String? =
        if (wifiOnly && metered) "Wi-Fi only is on. Connect to Wi-Fi to send or receive." else null
}

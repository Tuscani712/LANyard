import Foundation

/// Reports whether the device's active network is metered or mobile.
protocol MeteredNetwork {
    func isMetered() -> Bool
}

enum TransferPolicy {
    /// The message to show when a transfer is refused because only Wi-Fi is
    /// allowed, or nil when it may proceed. Kept pure so a test can drive both
    /// sides of the gate with a fake `MeteredNetwork`.
    static func wifiOnlyRefusal(wifiOnly: Bool, metered: Bool) -> String? {
        if wifiOnly && metered {
            return "Wi-Fi only is on. Connect to Wi-Fi to send or receive."
        }
        return nil
    }
}

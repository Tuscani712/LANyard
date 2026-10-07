import Foundation

/// Discovery helpers kept pure so the "is this me?" rule can be unit-tested
/// without Android.
///
/// mDNS gives each device a short id: the first 16 hex characters of its
/// certificate fingerprint. A phone that hears its own advertisement must not
/// list itself as a nearby device.
enum SelfFilter {
    /// The short id a device advertises for itself, from its full Device ID.
    static func ownShortId(_ deviceId: String) -> String {
        String(deviceId.trimmingCharacters(in: .whitespacesAndNewlines).prefix(16))
    }

    /// True when `shortId` is this device's own advertised id. A blank
    /// `ownShortId` (identity not loaded yet) matches nothing, so no real device
    /// is ever hidden by mistake.
    static func isSelf(_ shortId: String, _ ownShortId: String) -> Bool {
        let own = ownShortId.trimmingCharacters(in: .whitespacesAndNewlines)
        if own.isEmpty { return false }
        return shortId.caseInsensitiveCompare(own) == .orderedSame
    }
}

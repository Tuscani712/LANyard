// PairingServices.swift — composition root for the Phase 4 Devices/pairing UI.
//
// WRITTEN, NOT COMPILED (see LanyardApp.swift). The app is not a SwiftPM target,
// so nothing here is part of `swift build`.
//
// RootView owns one `PairingServices` and injects its two models into the
// Devices tab. The two models share ONE trust store, so a pairing confirmed in
// `PairFlowModel` appears in `DevicesModel` rows.

import Foundation
import LanyardCore
import LanyardNet

@MainActor
final class PairingServices: ObservableObject {
    let devices: DevicesModel
    let pairFlow: PairFlowModel

    init() {
        let trust = LanyardTrustStore.applicationSupportStore()

        // MAC-SPIKE: if the Keychain identity cannot be loaded (first run on a
        // locked device), the fingerprint is empty and self-filtering finds
        // nothing. A retry once the app is foregrounded is the intended fix.
        let identity = try? DeviceIdentity.load()
        let fingerprint = identity?.fingerprint ?? ""

        // MAC-SPIKE: the advertised invite port must be the bound PeerListener
        // port, which `AppServices.ServerLifecycle` owns. 0 means the invite QR
        // carries no dialable address until this is wired to the live listener.
        let advertisedPort = 0

        self.devices = DevicesModel(trust: trust, selfFingerprint: { fingerprint })

        let flow = PairFlow(trust: trust, selfFingerprint: { fingerprint })
        self.pairFlow = PairFlowModel(flow: flow, linkBuilder: { token in
            let addresses = DeviceIdentity.localAddresses().map { "\($0):\(advertisedPort)" }
            return PairLinkBuilder.build(
                fingerprint: fingerprint,
                name: ProcessInfo.processInfo.hostName,
                addresses: addresses,
                nonce: token
            )
        })
    }
}

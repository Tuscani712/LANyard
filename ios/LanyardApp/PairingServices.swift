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

        // The advertised invite port must be the bound PeerListener port, which
        // `AppServices.ServerLifecycle` owns. Until that is wired, the provider
        // returns nil and `PairFlow` stays `.starting`: it shows no invite/QR,
        // so the link can never carry a dialable-less `addr=` at port 0.
        let advertisedPort: () -> Int? = {
            // TODO: return AppServices.ServerLifecycle.shared.listenerPort
            nil
        }

        self.devices = DevicesModel(trust: trust, selfFingerprint: { fingerprint })

        let flow = PairFlow(
            trust: trust,
            selfFingerprint: { fingerprint },
            portProvider: ClosurePortProvider(advertisedPort)
        )
        self.pairFlow = PairFlowModel(flow: flow, linkBuilder: { token in
            let addresses = flow.inviteAddresses(localAddresses: DeviceIdentity.localAddresses())
            return PairLinkBuilder.build(
                fingerprint: fingerprint,
                name: ProcessInfo.processInfo.hostName,
                addresses: addresses,
                nonce: token
            )
        })
    }
}

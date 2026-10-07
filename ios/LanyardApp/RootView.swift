// RootView.swift — the tab shell.
//
// WRITTEN, NOT COMPILED (see LanyardApp.swift). Landing the final SwiftUI
// layer: the Settings tab (`SettingsView` + `SettingsModelVM`), the
// Troubleshoot tab (`TroubleshootView` + `TroubleshootModel`), and the Devices
// tab (Phase 4). The extension hands off through a `lanyard://share` link.
//
// The receive prompt is driven by `ReceiveApprovalModel.pending`.

import SwiftUI
import LanyardCore
import LanyardNet

struct RootView: View {
    @EnvironmentObject private var approval: ReceiveApprovalModel
    @EnvironmentObject private var settings: SettingsModelVM
    @EnvironmentObject private var troubleshoot: TroubleshootModel
    // The live TN3179 Local Network outcome, fed to the Troubleshoot model.
    @EnvironmentObject private var localNetwork: LocalNetworkAccessBox

    // Phase 4: the Devices tab's models. One shared trust store backs both, so a
    // pairing confirmed in PairView shows up in the device list.
    @StateObject private var pairing = PairingServices()

    var body: some View {
        TabView {
            DevicesView()
                .environmentObject(pairing.devices)
                .environmentObject(pairing.pairFlow)
                .tabItem { Label("Devices", systemImage: "laptopcomputer.and.iphone") }

            SendView()
                .tabItem { Label("Send", systemImage: "paperplane") }

            ShareView()
                .tabItem { Label("Share", systemImage: "square.and.arrow.up") }

            BrowseView()
                .tabItem { Label("Browse", systemImage: "folder") }

            TransfersView()
                .tabItem { Label("Transfers", systemImage: "arrow.left.arrow.right") }

            SettingsView()
                .environmentObject(settings)
                .tabItem { Label("Settings", systemImage: "gear") }

            TroubleshootView()
                .environmentObject(troubleshoot)
                .tabItem { Label("Troubleshoot", systemImage: "stethoscope") }
        }
        // The offer source fills `pending`; the sheet presents from any tab.
        .sheet(item: $approval.pending) { offer in
            ReceiveView(
                offer: offer,
                onAccept: { approval.answer(true) },
                onDecline: { approval.answer(false) }
            )
        }
        // The Devices tab owns the only `NWBrowser`, so it is the only observer
        // of the TN3179 permission prompt. Mirror its outcome into the box the
        // Troubleshoot tab reads, so the iOS rows are live.
        .onChange(of: pairing.devices.permission) { state in
            localNetwork.access = state.diagAccess
        }
    }
}

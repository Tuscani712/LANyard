// RootView.swift — the four-tab shell.
//
// WRITTEN, NOT COMPILED (see LanyardApp.swift). Phase 3 delivered push receive,
// the Transfers screen and the Settings inbox-folder row; Phase 4 delivers the
// real Devices/pairing tab (DevicesView + PairView, backed by PairingServices).
// Troubleshoot (Phase 6) is still a stub so the tab structure is real today.
//
// The receive prompt is driven by `ReceiveApprovalModel.pending`.

import SwiftUI
import LanyardCore
import LanyardNet

struct RootView: View {
    @EnvironmentObject private var approval: ReceiveApprovalModel

    // Phase 4: the Devices tab's models. One shared trust store backs both, so a
    // pairing confirmed in PairView shows up in the device list.
    @StateObject private var pairing = PairingServices()

    var body: some View {
        TabView {
            DevicesView()
                .environmentObject(pairing.devices)
                .environmentObject(pairing.pairFlow)
                .tabItem { Label("Devices", systemImage: "laptopcomputer.and.iphone") }

            TransfersView()
                .tabItem { Label("Transfers", systemImage: "arrow.left.arrow.right") }

            SettingsView()
                .tabItem { Label("Settings", systemImage: "gear") }

            TroubleshootView()
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
    }
}

/// Phase stub: renders a single "Coming in Phase N" line.
private struct PhaseStub: View {
    let title: String
    let systemImage: String
    let phase: Int

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.largeTitle)
                    .foregroundStyle(.secondary)
                Text(title).font(.title2)
                Text("Coming in Phase \(phase)")
                    .foregroundStyle(.secondary)
            }
            .navigationTitle(title)
        }
    }
}

/// Troubleshoot (the server event log) lands in Phase 6.
private struct TroubleshootView: View {
    var body: some View {
        PhaseStub(title: "Troubleshoot", systemImage: "stethoscope", phase: 6)
    }
}

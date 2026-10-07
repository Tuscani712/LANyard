// LocalNetworkPermissionView.swift — the TN3179 recovery screen.
//
// WRITTEN, NOT COMPILED (see LanyardApp.swift). Shown when `DiscoveryService`
// reports `LocalNetworkPermissionState.denied`, i.e. the person tapped "Don't
// Allow" on the Local Network prompt (or the browse hit a DNS policy error).
//
// The prompt itself is triggered by the first `NWBrowser` browse; once denied it
// cannot be re-shown programmatically, so the only fix is Settings. This screen
// is explanatory and deep-links there.

import SwiftUI
import UIKit

struct LocalNetworkPermissionView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Image(systemName: "wifi.exclamationmark")
                    .font(.system(size: 44))
                    .foregroundStyle(.orange)

                Text("Local Network access is off")
                    .font(.title3.weight(.semibold))

                Text("LANyard finds your other devices over your local network. Without Local Network access, no devices can be discovered.")
                    .font(.body)
                    .foregroundStyle(.secondary)

                VStack(alignment: .leading, spacing: 8) {
                    Label("Open Settings → LANyard", systemImage: "1.circle")
                    Label("Turn on Local Network", systemImage: "2.circle")
                    Label("Return here — devices appear automatically", systemImage: "3.circle")
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.top, 4)

                Spacer()

                Button {
                    openSettings()
                } label: {
                    Label("Open Settings", systemImage: "gear")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()
            .navigationTitle("Local Network")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

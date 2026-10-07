// ReceiveView.swift — the offer approval prompt.
//
// WRITTEN, NOT COMPILED (see LanyardApp.swift). Presented as a sheet from
// RootView whenever `ReceiveApprovalModel.pending` is non-nil. Accept/decline
// drive `ReceiveApprovalModel.answer(_:)`, which resolves the callback the
// receive path is waiting on. The view holds no logic: it reads the offer and
// the current destination, and forwards taps.
//
// When the destination is not writable, the core's `refusalMessage` is shown and
// Accept is disabled — the sender is refused by `InboxReceiver.offer` either
// way, but the person can see why.

import SwiftUI
import LanyardCore
import LanyardNet

struct ReceiveView: View {
    let offer: ReceiveOffer
    let onAccept: () -> Void
    let onDecline: () -> Void

    @EnvironmentObject private var inbox: InboxModel

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text(offer.peerName.isEmpty ? "A paired device" : offer.peerName)
                    .font(.headline)

                Text("\(offer.files) file\(offer.files == 1 ? "" : "s") · \(humanSize(offer.total))")
                    .foregroundStyle(.secondary)

                if !offer.names.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(offer.names, id: \.self) { name in
                            Text(name)
                                .font(.system(.footnote, design: .monospaced))
                                .lineLimit(1)
                        }
                        if offer.files > offer.names.count {
                            Text("…").font(.footnote)
                        }
                    }
                }

                if !inbox.isWritable {
                    Text(inbox.refusalMessage)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }

                Spacer()

                HStack {
                    Button("Decline", role: .cancel, action: onDecline)
                        .buttonStyle(.bordered)
                    Spacer()
                    Button("Accept", action: onAccept)
                        .buttonStyle(.borderedProminent)
                        .disabled(!inbox.isWritable)
                }
            }
            .padding()
            .navigationTitle("Receive files?")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private func humanSize(_ bytes: Int64) -> String {
    if bytes < 1024 { return "\(bytes) bytes" }
    let units = ["KB", "MB", "GB", "TB"]
    var value = Double(bytes) / 1024
    var unit = 0
    while value >= 1024 && unit < units.count - 1 {
        value /= 1024
        unit += 1
    }
    return String(format: "%.1f %@", value, units[unit])
}

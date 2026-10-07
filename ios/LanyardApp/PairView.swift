// PairView.swift — the pairing screen (Phase 4).
//
// WRITTEN, NOT COMPILED (see LanyardApp.swift). It renders whatever
// `PairFlowModel.state` is:
//
//   idle                  -> "show your invite" / one-off Connect banner
//   generating            -> the invite QR with a live "expires in mm:ss"
//   awaitingConfirmation  -> the grouped SAS, the browse/push toggles, Confirm/Decline
//   paired                -> the saved peer's fingerprint
//   declined / expired    -> the terminal outcome
//
// The QR is rendered with CoreImage's `CIQRCodeGenerator` (no external dep).
// Nothing here handles a typed address; Connect is a core-driven action.

import SwiftUI
import CoreImage.CIFilterBuiltins
import LanyardCore
import LanyardNet

struct PairView: View {
    /// Set when the sheet was opened by a row's Connect button.
    var connectingTo: DeviceRow? = nil

    @EnvironmentObject private var pairFlow: PairFlowModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    content
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle("Pair")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear { pairFlow.tick() }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch pairFlow.state {
        case .idle:
            idleBody
        case .generating:
            generatingBody
        case .awaitingConfirmation:
            confirmationBody
        case .paired(let peer):
            pairedBody(peer)
        case .declined:
            outcomeBody("Declined", systemImage: "xmark.circle", tint: .secondary)
        case .expired:
            outcomeBody("Invite expired", systemImage: "clock.badge.exclamationmark", tint: .orange)
        }
    }

    // MARK: - Idle

    private var idleBody: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let row = connectingTo {
                Label("Connecting to \(row.name.isEmpty ? row.shortId : row.name)…", systemImage: "arrow.triangle.2.circlepath")
                    .font(.headline)
                Text("Confirm the code shown on the other device. This device is the initiator; the responder shows the SAS.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                Text("Pair a nearby device")
                    .font(.title3.weight(.semibold))
                Text("Show this device's invite and let the other device scan it, or add a device by its link.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Button {
                pairFlow.startInvite()
            } label: {
                Label("Show my invite", systemImage: "qrcode")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
        }
    }

    // MARK: - Generating (invite QR + countdown)

    private var generatingBody: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Scan to pair")
                .font(.title3.weight(.semibold))
            Text("On the other device, choose Add by link → Scan QR code.")
                .font(.footnote)
                .foregroundStyle(.secondary)

            QRCodeView(text: pairFlow.inviteLink ?? "")
                .frame(width: 220, height: 220)
                .frame(maxWidth: .infinity)

            HStack(spacing: 6) {
                Image(systemName: "clock")
                Text("expires in \(mmss(pairFlow.secondsRemaining))")
                    .monospacedDigit()
            }
            .font(.callout.weight(.medium))
            .foregroundStyle(pairFlow.secondsRemaining <= 15 ? .orange : .secondary)
            .frame(maxWidth: .infinity)

            if pairFlow.secondsRemaining == 0 {
                Button("New invite") { pairFlow.startInvite() }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    // MARK: - Awaiting confirmation (SAS + permissions)

    private var confirmationBody: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let offer = pairFlow.offer {
                Text(offer.peerName.isEmpty ? "A device wants to pair" : "\(offer.peerName) wants to pair")
                    .font(.title3.weight(.semibold))
                if !offer.peerDevice.isEmpty || !offer.peerHost.isEmpty {
                    Text([offer.peerDevice, offer.peerHost].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            Text("Confirm this code matches the other device:")
                .font(.footnote)
                .foregroundStyle(.secondary)

            Text(pairFlow.formattedSas ?? "—")
                .font(.system(size: 40, weight: .bold, design: .monospaced))
                .frame(maxWidth: .infinity)
                .accessibilityLabel((pairFlow.sas ?? "").map(String.init).joined(separator: " "))

            Text("Permissions to grant")
                .font(.headline)

            Toggle("Let them browse my shared files", isOn: Binding(
                get: { pairFlow.permissions.browse },
                set: { pairFlow.setBrowse($0) }
            ))
            Toggle("Let them push files to me", isOn: Binding(
                get: { pairFlow.permissions.push },
                set: { pairFlow.setPush($0) }
            ))

            HStack {
                Button("Decline", role: .cancel) { pairFlow.decline() }
                    .buttonStyle(.bordered)
                Spacer()
                Button("Confirm") { pairFlow.confirmSas() }
                    .buttonStyle(.borderedProminent)
            }
            .padding(.top, 4)
        }
    }

    // MARK: - Paired

    private func pairedBody(_ peer: PairedPeer) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("Paired", systemImage: "checkmark.seal.fill")
                .font(.title3.weight(.semibold))
                .foregroundStyle(.green)
            Text(peer.name)
                .font(.headline)
            Text(grouped(peer.fingerprint))
                .font(.system(.footnote, design: .monospaced))
                .foregroundStyle(.secondary)
            Button("Done") { dismiss() }
                .buttonStyle(.borderedProminent)
        }
    }

    private func outcomeBody(_ title: String, systemImage: String, tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Label(title, systemImage: systemImage)
                .font(.title3.weight(.semibold))
                .foregroundStyle(tint)
            Button("Start over") { pairFlow.startInvite() }
                .buttonStyle(.bordered)
        }
    }

    // MARK: - Formatting

    private func mmss(_ seconds: Int64) -> String {
        let clamped = max(0, seconds)
        return String(format: "%02d:%02d", clamped / 60, clamped % 60)
    }

    /// Uppercase groups of four, matching LanyardCore's `Display.groupedHex`.
    private func grouped(_ hex: String) -> String {
        let upper = hex.uppercased()
        var groups: [String] = []
        var index = upper.startIndex
        while index < upper.endIndex {
            let end = upper.index(index, offsetBy: 4, limitedBy: upper.endIndex) ?? upper.endIndex
            groups.append(String(upper[index..<end]))
            index = end
        }
        return groups.joined(separator: " ")
    }
}

// MARK: - QR rendering

/// Renders `text` as a QR code with CoreImage's `CIQRCodeGenerator`.
struct QRCodeView: View {
    let text: String

    var body: some View {
        if let image = QRCodeView.makeImage(text) {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .accessibilityLabel("Pairing QR code")
        } else {
            RoundedRectangle(cornerRadius: 8)
                .strokeBorder(.secondary)
                .overlay(Text("QR unavailable").font(.footnote).foregroundStyle(.secondary))
        }
    }

    static func makeImage(_ string: String) -> UIImage? {
        guard !string.isEmpty, let data = string.data(using: .utf8) else { return nil }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = data
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        // CIQRCodeGenerator emits a tiny image; scale it up so it is crisp at the
        // displayed size (interpolation is disabled above).
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let context = CIContext()
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

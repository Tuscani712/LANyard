// DevicesView.swift — the Devices tab (Phase 4).
//
// WRITTEN, NOT COMPILED (see LanyardApp.swift). Presentational: it lists the
// `DeviceRow`s the `DevicesModel` publishes, shows each row's status, and offers
// Connect / Browse exactly where the core's `connectEnabled` / `browseEnabled`
// say they are available. All logic lives in the models and LanyardCore.
//
// There are no typed addresses or paths: adding a peer is either the paste field
// (a `lanyard://pair?...` link) or the system QR scanner. Received files are
// browsed through the paired peer's share list (Phase 5).

import SwiftUI
import LanyardCore
import LanyardNet

struct DevicesView: View {
    @EnvironmentObject private var devices: DevicesModel
    @EnvironmentObject private var pairFlow: PairFlowModel

    @State private var showPair = false
    @State private var showAddLink = false
    @State private var showPermissionHelp = false

    var body: some View {
        NavigationStack {
            List {
                if devices.rows.isEmpty {
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(devices.isScanning ? "Looking for devices…" : "No devices yet")
                                .font(.headline)
                            Text("Put another LANyard device on the same network, or add one by pasting its invite link.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 4)
                    }
                } else {
                    ForEach(devices.rows) { row in
                        DeviceRowView(
                            row: row,
                            onConnect: {
                                devices.connect(row)
                                showPair = true
                            },
                            onBrowse: { devices.browse(row) },
                            onUnpair: { devices.unpair(row) }
                        )
                    }
                }
            }
            .navigationTitle("Devices")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        showAddLink = true
                    } label: {
                        Label("Add by link", systemImage: "link")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        pairFlow.startInvite()
                        showPair = true
                    } label: {
                        Label("Show my invite", systemImage: "qrcode")
                    }
                }
            }
            .onAppear { devices.start() }
            .onDisappear { devices.stop() }
            // Present the shared pairing flow from the model's state.
            .sheet(isPresented: $showPair) {
                PairView()
                    .environmentObject(pairFlow)
            }
            .sheet(item: $devices.pendingConnect) { row in
                PairView(connectingTo: row)
                    .environmentObject(pairFlow)
                    .onDisappear { devices.clearPendingConnect() }
            }
            .sheet(isPresented: $showAddLink) {
                AddByLinkView()
                    .environmentObject(pairFlow)
            }
            .onChange(of: devices.permission) { state in
                if state == .denied { showPermissionHelp = true }
            }
            .sheet(isPresented: $showPermissionHelp) {
                LocalNetworkPermissionView()
            }
        }
    }
}

// MARK: - Row

private struct DeviceRowView: View {
    let row: DeviceRow
    let onConnect: () -> Void
    let onBrowse: () -> Void
    let onUnpair: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(displayName)
                        .font(.headline)
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                StatusBadge(status: row.status)
            }

            HStack(spacing: 12) {
                Button("Connect", action: onConnect)
                    .buttonStyle(.bordered)
                    .disabled(!row.connectEnabled)

                Button("Browse", action: onBrowse)
                    .buttonStyle(.bordered)
                    .disabled(!row.browseEnabled)

                Spacer()

                if row.isPaired {
                    Button("Unpair", role: .destructive, action: onUnpair)
                        .buttonStyle(.borderless)
                        .font(.footnote)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private var displayName: String {
        if !row.name.isEmpty { return row.name }
        if !row.deviceLabel.isEmpty { return row.deviceLabel }
        return row.shortId
    }

    private var subtitle: String {
        var parts: [String] = []
        if !row.os.isEmpty { parts.append(row.os) }
        if !row.deviceLabel.isEmpty && row.deviceLabel != displayName { parts.append(row.deviceLabel) }
        if !row.shortId.isEmpty { parts.append(row.shortId) }
        return parts.joined(separator: " · ")
    }
}

private struct StatusBadge: View {
    let status: DeviceStatus

    var body: some View {
        Text(label)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(color.opacity(0.15), in: Capsule())
            .foregroundStyle(color)
    }

    private var label: String {
        switch status {
        case .paired: return "Online"
        case .online: return "Found"
        case .offline: return "Offline"
        }
    }

    private var color: Color {
        switch status {
        case .paired: return .green
        case .online: return .blue
        case .offline: return .secondary
        }
    }
}

// MARK: - Add by link (paste or scan only)

/// The manual-add sheet. Paste field only — there is deliberately no host/port
/// entry — plus the system QR scanner.
struct AddByLinkView: View {
    @EnvironmentObject private var pairFlow: PairFlowModel
    @Environment(\.dismiss) private var dismiss

    @State private var showScanner = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("lanyard://pair?…", text: $pairFlow.pasteText, axis: .vertical)
                        .lineLimit(3...6)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.body, design: .monospaced))
                        .onSubmit { add() }
                } header: {
                    Text("Paste invite link")
                } footer: {
                    Text("LANyard does not accept typed addresses. Ask the other device to show its invite QR or share its pairing link.")
                }

                if let error = pairFlow.pasteError {
                    Section {
                        Text(error).foregroundStyle(.red).font(.footnote)
                    }
                }

                if let link = pairFlow.pastedLink {
                    Section("Link looks valid") {
                        LabeledContent("Device", value: link.name.isEmpty ? "Unknown" : link.name)
                        LabeledContent("Fingerprint", value: String(link.fingerprint.prefix(16)) + "…")
                    }
                }

                Section {
                    Button {
                        showScanner = true
                    } label: {
                        Label("Scan QR code", systemImage: "qrcamera")
                    }
                }
            }
            .navigationTitle("Add a device")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add", action: add)
                        .disabled(pairFlow.pasteText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .sheet(isPresented: $showScanner) {
                QRScannerSheet { code in
                    showScanner = false
                    _ = pairFlow.pasteLink(code)
                }
            }
        }
    }

    private func add() {
        let text = pairFlow.pasteText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        _ = pairFlow.pasteLink(text)
    }
}

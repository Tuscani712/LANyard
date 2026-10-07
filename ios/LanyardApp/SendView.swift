// SendView.swift — the Send tab (Phase 5, push send).
//
// WRITTEN, NOT COMPILED (see LanyardApp.swift). Presentational: it lists the
// destination `ShareTarget`s the `SendModel` publishes, picks files and folders
// with the system document picker (`.fileImporter`, which is `UIDocumentPicker`
// — there are no typed paths anywhere), shows per-file progress, and offers
// Cancel / Resend / Dismiss / Clear history exactly where the core's job state
// allows.
//
// The bytes are not carried in the view: a pick becomes a `SendFile` descriptor
// and the app resolves it lazily when the send runs. MAC-SPIKE: a URL handed
// back by the picker is security-scoped; the app must spool it into durable
// storage (Application Support) before enqueue, or the run-time resolution will
// fail once the picker's scope lapses. `SendFileScanner` only reads metadata.

import SwiftUI
import UniformTypeIdentifiers
import LanyardCore
import LanyardNet

struct SendView: View {
    @EnvironmentObject private var send: SendModel
    @Environment(\.scenePhase) private var scenePhase

    @State private var selectedFingerprint: String?
    @State private var picked: [SendFileScanner.Picked] = []
    @State private var pickedLabel = ""
    @State private var showFilePicker = false
    @State private var showFolderPicker = false

    var body: some View {
        NavigationStack {
            List {
                destinationSection
                selectionSection
                queueSection
            }
            .navigationTitle("Send")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Clear history") { send.clearFinished() }
                        .disabled(!send.hasFinished)
                }
            }
            .onAppear { send.refreshTargets() }
            .onChange(of: scenePhase) { phase in
                if phase == .active { send.refreshTargets() }
            }
            .fileImporter(
                isPresented: $showFilePicker,
                allowedContentTypes: [.item],
                allowsMultipleSelection: true,
                onCompletion: handleFiles
            )
            .fileImporter(
                isPresented: $showFolderPicker,
                allowedContentTypes: [.folder],
                allowsMultipleSelection: false,
                onCompletion: handleFolder
            )
        }
    }

    // MARK: - Destination

    private var destinationSection: some View {
        Section("Send to") {
            if send.targets.isEmpty {
                Text("Pair a device to send files.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(send.targets, id: \.peer.fingerprint) { target in
                    DestinationRow(
                        target: target,
                        selected: selectedFingerprint == target.peer.fingerprint,
                        onSelect: { selectedFingerprint = target.peer.fingerprint }
                    )
                }
            }
        }
    }

    // MARK: - Files / folders

    private var selectionSection: some View {
        Section {
            Button {
                showFilePicker = true
            } label: {
                Label("Choose files", systemImage: "doc")
            }
            Button {
                showFolderPicker = true
            } label: {
                Label("Choose a folder", systemImage: "folder")
            }

            if picked.isEmpty {
                Text("No files chosen.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(picked, id: \.file.id) { pick in
                    HStack {
                        Image(systemName: "doc")
                            .foregroundStyle(.secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(pick.file.relPath)
                                .font(.subheadline)
                                .lineLimit(1)
                            Text(humanSize(pick.file.size))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        } header: {
            Text("Files")
        } footer: {
            if !picked.isEmpty {
                Text("\(picked.count) file(s), \(humanSize(totalPicked)) total.")
            }
        }
    }

    // MARK: - Queue

    private var queueSection: some View {
        Section {
            Button {
                enqueue()
            } label: {
                Label("Send", systemImage: "paperplane")
            }
            .disabled(selectedPeer == nil || picked.isEmpty)

            if send.jobs.isEmpty {
                Text("No sends yet.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(send.jobs) { job in
                    SendJobRow(
                        job: job,
                        onCancel: { send.cancel(job.id) },
                        onResend: { send.resend(job.id) },
                        onDismiss: { send.dismiss(job.id) }
                    )
                }
            }
        } header: {
            Text("Queue")
        }
    }

    // MARK: - Actions

    private var selectedPeer: PairedPeer? {
        guard let selectedFingerprint else { return nil }
        return send.targets.first { $0.peer.fingerprint == selectedFingerprint }?.peer
    }

    private var totalPicked: Int64 {
        picked.reduce(0) { $0 + $1.file.size }
    }

    private func enqueue() {
        guard let peer = selectedPeer, !picked.isEmpty else { return }
        let label = pickedLabel.isEmpty ? "\(picked.count) file(s)" : pickedLabel
        for pick in picked {
            send.registerSource(pick.url, for: pick.file.relPath)
        }
        _ = send.enqueue(peer: peer, files: picked.map(\.file), label: label)
        picked = []
        pickedLabel = ""
    }

    private func handleFiles(_ result: Result<[URL], Error>) {
        guard case let .success(urls) = result else { return }
        picked = urls.compactMap { SendFileScanner.pickedFile(at: $0) }
        pickedLabel = ""
    }

    private func handleFolder(_ result: Result<[URL], Error>) {
        guard case let .success(urls) = result, let folder = urls.first else { return }
        let label = SendFileScanner.folderLabel(folder)
        picked = SendFileScanner.pickedFolder(at: folder, label: label)
        pickedLabel = label
    }
}

// MARK: - Destination row

private struct DestinationRow: View {
    let target: ShareTarget
    let selected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            HStack {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(selected ? Color.accentColor : .secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(target.peer.name.isEmpty ? shortFingerprint : target.peer.name)
                        .font(.subheadline.weight(.semibold))
                    if let reason = target.reason {
                        Text(reason)
                            .font(.caption)
                            .foregroundStyle(target.enabled ? .secondary : .orange)
                    }
                }
                Spacer()
                if !target.enabled {
                    Image(systemName: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                }
            }
        }
        .buttonStyle(.plain)
    }

    private var shortFingerprint: String {
        String(target.peer.fingerprint.prefix(16)) + "…"
    }
}

// MARK: - Job row

private struct SendJobRow: View {
    let job: SendJob
    let onCancel: () -> Void
    let onResend: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(job.label)
                        .font(.subheadline.weight(.semibold))
                    Text(job.peerName)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text(Self.stateLabel(job))
                    .font(.caption)
                    .foregroundStyle(job.state == .failed ? .red : .secondary)
            }

            if job.total > 0 {
                ProgressView(value: progress)
                Text("\(humanSize(job.done)) / \(humanSize(job.total))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            // Per-file rows. `SendJob` carries an aggregate `done`, so the
            // per-file split is derived in file order; it is an estimate, not a
            // separate progress channel.
            ForEach(Array(job.files.enumerated()), id: \.element.id) { index, file in
                PerFileProgress(
                    name: file.relPath,
                    size: file.size,
                    sent: sentBytes(for: index)
                )
            }

            HStack {
                Spacer()
                if job.state.isActive {
                    Button("Cancel", action: onCancel)
                        .buttonStyle(.bordered)
                } else {
                    if job.state == .failed || job.state == .cancelled {
                        Button("Resend", action: onResend)
                            .buttonStyle(.borderedProminent)
                    }
                    Button("Dismiss", action: onDismiss)
                        .buttonStyle(.bordered)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private var progress: Double {
        guard job.total > 0 else { return 0 }
        return min(1, max(0, Double(job.done) / Double(job.total)))
    }

    /// Bytes attributed to file `index` from the aggregate `done`, filling files
    /// in order.
    private func sentBytes(for index: Int) -> Int64 {
        var remaining = job.done
        for (i, file) in job.files.enumerated() {
            if i == index { return min(max(remaining, 0), file.size) }
            remaining -= file.size
            if remaining <= 0 { return 0 }
        }
        return 0
    }

    static func stateLabel(_ job: SendJob) -> String {
        switch job.state {
        case .queued: return "Waiting"
        case .running: return "Sending"
        case .done: return "Sent"
        case .cancelled: return "Cancelled"
        case .failed:
            let message = job.message ?? ""
            return message.isEmpty ? "Failed" : message
        }
    }
}

private struct PerFileProgress: View {
    let name: String
    let size: Int64
    let sent: Int64

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: sent >= size && size > 0 ? "checkmark.circle.fill" : "arrow.up.circle")
                .foregroundStyle(sent >= size && size > 0 ? .green : .secondary)
                .font(.caption)
            Text(name)
                .font(.caption)
                .lineLimit(1)
            Spacer()
            Text(humanSize(size))
                .font(.caption2)
                .foregroundStyle(.secondary)
            ProgressView(value: size > 0 ? min(1, Double(sent) / Double(size)) : 0)
                .frame(width: 60)
        }
    }
}

// MARK: - Shared formatting (file-private; TransfersView has its own)

private func humanSize(_ bytes: Int64) -> String {
    if bytes < 1024 { return "\(bytes) B" }
    let units = ["KB", "MB", "GB", "TB"]
    var value = Double(bytes) / 1024
    var unit = 0
    while value >= 1024 && unit < units.count - 1 {
        value /= 1024
        unit += 1
    }
    return String(format: "%.1f %@", value, units[unit])
}

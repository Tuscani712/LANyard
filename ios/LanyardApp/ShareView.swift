// ShareView.swift — the Share tab (Phase 5, share/serve).
//
// WRITTEN, NOT COMPILED (see LanyardApp.swift). Presentational: it lists what
// the `SharesModel` is currently serving, adds a picked file or folder with a
// lifetime choice, stops one share or all, and surfaces the server-side `503` /
// `Retry-After` concurrency-gate state.
//
// Adding uses `.fileImporter` (UIDocumentPicker); the model starts the
// security-scoped access and persists a bookmark. MAC-SPIKE: the share catalog
// has no core `add` API (see `ShareRegistrar`), so the concrete
// `AppShareSource` behind `SharesModel` owns the mutation.

import SwiftUI
import UniformTypeIdentifiers
import LanyardCore
import LanyardNet

struct ShareView: View {
    @EnvironmentObject private var shares: SharesModel
    @Environment(\.scenePhase) private var scenePhase

    @State private var lifetime: ShareLifetimeType = .untilStopped
    @State private var pickedURL: URL?
    @State private var pickedLabel = ""
    @State private var showFilePicker = false
    @State private var showFolderPicker = false
    @State private var addFailed = false

    /// `ShareLifetimeType` is not `CaseIterable`, so the picker enumerates it.
    private let lifetimes: [ShareLifetimeType] = [.untilStopped, .timed, .oneTime, .persistent]

    var body: some View {
        NavigationStack {
            List {
                if shares.busy {
                    Section {
                        BusyBanner(retryAfterSeconds: shares.retryAfterSeconds)
                    }
                }

                addSection
                sharedSection
            }
            .navigationTitle("Share")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Stop all", role: .destructive) { shares.stopAll() }
                        .disabled(!shares.hasShares)
                }
            }
            .onAppear { shares.refresh() }
            .onChange(of: scenePhase) { phase in
                if phase == .active { shares.refresh() }
            }
            .fileImporter(
                isPresented: $showFilePicker,
                allowedContentTypes: [.item],
                allowsMultipleSelection: false,
                onCompletion: handlePick
            )
            .fileImporter(
                isPresented: $showFolderPicker,
                allowedContentTypes: [.folder],
                allowsMultipleSelection: false,
                onCompletion: handlePick
            )
            .alert("Could not share that item", isPresented: $addFailed) {
                Button("OK", role: .cancel) {}
            }
        }
    }

    // MARK: - Add

    private var addSection: some View {
        Section {
            Button {
                showFilePicker = true
            } label: {
                Label("Share a file", systemImage: "doc.badge.plus")
            }
            Button {
                showFolderPicker = true
            } label: {
                Label("Share a folder", systemImage: "folder.badge.plus")
            }

            Picker("Lifetime", selection: $lifetime) {
                ForEach(lifetimes, id: \.rawValue) { value in
                    Text(Self.lifetimeLabel(value)).tag(value)
                }
            }

            if let pickedURL {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(pickedLabel.isEmpty ? pickedURL.lastPathComponent : pickedLabel)
                            .font(.subheadline)
                        Text(Self.lifetimeLabel(lifetime))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Share") { commit(pickedURL) }
                        .buttonStyle(.borderedProminent)
                }
            }
        } header: {
            Text("Add a share")
        } footer: {
            Text("A share ends when you stop it, when its lifetime runs out, or after a one-time download.")
        }
    }

    // MARK: - Shared

    private var sharedSection: some View {
        Section("Shared") {
            if shares.shares.isEmpty {
                Text("Nothing is shared.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(shares.shares, id: \.id) { share in
                    ShareRow(share: share, onStop: { shares.stop(share.id) })
                }
            }
        }
    }

    // MARK: - Actions

    private func handlePick(_ result: Result<[URL], Error>) {
        guard case let .success(urls) = result, let url = urls.first else { return }
        pickedURL = url
        pickedLabel = url.lastPathComponent
    }

    private func commit(_ url: URL) {
        let ok = shares.add(url: url, label: pickedLabel, lifetime: lifetime)
        if ok {
            pickedURL = nil
            pickedLabel = ""
        } else {
            addFailed = true
        }
    }

    static func lifetimeLabel(_ value: ShareLifetimeType) -> String {
        switch value {
        case .persistent: return "Always"
        case .untilStopped: return "Until I stop it"
        case .timed: return "For 1 hour"
        case .oneTime: return "Once (one download)"
        }
    }
}

// MARK: - Rows

private struct BusyBanner: View {
    let retryAfterSeconds: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label("Server busy", systemImage: "hourglass")
                .font(.subheadline.weight(.semibold))
            Text("Peers are being asked to retry after \(retryAfterSeconds) seconds (503 / Retry-After).")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

private struct ShareRow: View {
    let share: ShareInfo
    let onStop: () -> Void

    var body: some View {
        HStack {
            Image(systemName: share.kind == "file" ? "doc" : "folder")
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(share.label)
                    .font(.subheadline.weight(.semibold))
                HStack(spacing: 6) {
                    Text(share.kind)
                    if share.size > 0 { Text(humanSize(share.size)) }
                    Text(ShareView.lifetimeLabel(share.lifetime))
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Spacer()
            Button("Stop", role: .destructive, action: onStop)
                .buttonStyle(.bordered)
                .font(.footnote)
        }
    }
}

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

// BrowseView.swift — the Browse tab (Phase 5, pull/download).
//
// WRITTEN, NOT COMPILED (see LanyardApp.swift). Presentational: pick a paired
// peer, list the shares it is serving, walk a share's folder tree, and pull
// files. Downloads resume (`DownloadTarget.existingSize` + `openExisting`) and
// verify each SHA-256 in `DownloadSession`; progress and Cancel come from the
// `BrowseModel`.
//
// MAC-SPIKE: this uses the foreground mTLS path (`LanyardBrowseClient` /
// `LanyardShareReader`). Off to the side, `BackgroundDownloader` is the
// background-session sketch, but our pinned custom mTLS cannot be reused there
// (see that file).

import SwiftUI
import LanyardCore
import LanyardNet

struct BrowseView: View {
    @EnvironmentObject private var browse: BrowseModel

    var body: some View {
        NavigationStack {
            List {
                peerSection
                if !browse.shares.isEmpty { sharesSection }
                if let shareId = browse.currentShareId, !shareId.isEmpty { treeSection }
                downloadSection
            }
            .navigationTitle("Browse")
            .onAppear { browse.refreshPeers() }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if browse.currentShareId != nil {
                        Button("Back to shares") {
                            browse.loadShares()
                        }
                    }
                }
            }
        }
    }

    // MARK: - Peers

    private var peerSection: some View {
        Section("Paired devices") {
            if browse.peers.isEmpty {
                Text("Pair a device to browse its shares.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(browse.peers, id: \.fingerprint) { peer in
                    Button {
                        browse.select(peer)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(peer.name.isEmpty ? shortFingerprint(peer.fingerprint) : peer.name)
                                .font(.subheadline.weight(.semibold))
                            Text(peer.browse ? "Browsing allowed" : "Browsing not allowed")
                                .font(.caption)
                                .foregroundStyle(peer.browse ? .secondary : .orange)
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(!peer.browse)
                }
            }
        }
    }

    // MARK: - Shares

    private var sharesSection: some View {
        Section("Shares") {
            if browse.shares.isEmpty {
                Text("No shares.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(browse.shares, id: \.id) { share in
                    Button {
                        browse.open(share: share)
                    } label: {
                        HStack {
                            Image(systemName: share.kind == "file" ? "doc" : "folder")
                                .foregroundStyle(.secondary)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(share.label.isEmpty ? share.name : share.label)
                                    .font(.subheadline.weight(.semibold))
                                if share.size > 0 {
                                    Text(humanSize(share.size))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: - Tree

    private var treeSection: some View {
        Section("\(browse.currentShareLabel) — \(browse.currentPath.isEmpty ? "/" : browse.currentPath)") {
            Button {
                browse.download(path: browse.currentPath, include: nil, to: Self.downloadDirectory())
            } label: {
                Label("Download this folder", systemImage: "arrow.down.circle")
            }
            .disabled(browse.isDownloading)

            if browse.entries.isEmpty {
                Text("Empty folder.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(browse.entries, id: \.path) { entry in
                    if entry.isDir {
                        Button {
                            browse.openFolder(entry)
                        } label: {
                            EntryRow(entry: entry, trailing: "chevron.right")
                        }
                        .buttonStyle(.plain)
                    } else {
                        Button {
                            browse.download(path: entry.path, include: [entry.path], to: Self.downloadDirectory())
                        } label: {
                            EntryRow(entry: entry, trailing: "arrow.down")
                        }
                        .buttonStyle(.plain)
                        .disabled(browse.isDownloading)
                    }
                }
            }
        }
    }

    // MARK: - Progress

    @ViewBuilder
    private var downloadSection: some View {
        if browse.isDownloading {
            Section("Download") {
                ProgressView(value: browse.downloadProgress)
                HStack {
                    Text("\(Int(browse.downloadProgress * 100))%")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("Cancel", role: .destructive) { browse.cancelDownload() }
                        .buttonStyle(.bordered)
                }
                Text("Partial files resume where they left off.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } else if let result = browse.lastResult {
            Section("Last download") {
                Text(result).font(.subheadline)
            }
        }
        if let error = browse.error {
            Section {
                Text(error).foregroundStyle(.red).font(.footnote)
            }
        }
    }

    // MARK: - Helpers

    private func shortFingerprint(_ fingerprint: String) -> String {
        String(fingerprint.prefix(16)) + "…"
    }

    static func downloadDirectory() -> URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let directory = documents.appendingPathComponent("LANyard Downloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}

private struct EntryRow: View {
    let entry: BrowseEntry
    let trailing: String

    var body: some View {
        HStack {
            Image(systemName: entry.isDir ? "folder" : "doc")
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name)
                    .font(.subheadline)
                if !entry.isDir, entry.size > 0 {
                    Text(humanSize(entry.size))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            Image(systemName: trailing)
                .font(.caption)
                .foregroundStyle(.tertiary)
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

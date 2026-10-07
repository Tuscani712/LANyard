// InboxDestinationAdapter.swift — the iOS `ReceiveDestination`.
//
// WRITTEN, NOT COMPILED. On Linux `canImport(Combine)` is false, so this file
// compiles to nothing and `swift build`/`swift test` stay green. It is compiled
// for real only on a Mac/iOS toolchain.
//
// Resolves the destination folder for received files, in line with the real
// `LanyardCore.ReceiveDestination` protocol: a security-scoped override folder
// chosen in a `UIDocumentPicker` when one is set and still writable, otherwise
// `<Documents>/LANyard` (created on first use by the core's
// `DefaultReceiveDestination`).
//
// The adapter deliberately owns *only* where files go. The core owns the actual
// placement: `InboxDestination` recreates sub-folders, de-duplicates names with
// `name (1).ext`, and produces `refusalMessage`. Nothing here duplicates that.
//
// The override is persisted as a security-scoped bookmark in UserDefaults and
// access is held for the lifetime of the adapter via
// `startAccessingSecurityScopedResource()`.

#if canImport(Combine)
import Foundation
import LanyardCore

/// Errors surfaced to Settings as a readable message.
public enum InboxDestinationError: LocalizedError {
    case notWritable
    case bookmarkFailed

    public var errorDescription: String? {
        switch self {
        case .notWritable:
            return "The download folder is not writable."
        case .bookmarkFailed:
            return "Could not remember that folder. Choose it again."
        }
    }
}

/// The app's Documents directory + "LANyard" as a `ReceiveDestination`, with an
/// optional security-scoped override chosen in a `UIDocumentPicker`.
public final class InboxDestinationAdapter: ReceiveDestination {
    /// UserDefaults key for the persisted security-scoped bookmark.
    public static let bookmarkDefaultsKey = "io.github.tuscani712.lanyard.inboxBookmark"

    private let defaults: UserDefaults

    /// The override folder, if one was chosen, and whether this adapter holds a
    /// security-scoped access on it.
    private var overrideURL: URL?
    private var isAccessingScoped = false

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        restoreOverride()
    }

    deinit {
        releaseScopedAccess()
    }

    // MARK: - Location

    /// The app's Documents directory. Private to the app, but visible in the
    /// Files app because of the two Info.plist keys.
    public static func documentsDirectory() -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    /// Documents/LANyard, the default inbox. The core's
    /// `DefaultReceiveDestination` creates it on first use.
    public static func defaultInboxDirectory() -> URL {
        documentsDirectory().appendingPathComponent(DefaultReceiveDestination.folderName, isDirectory: true)
    }

    // MARK: - ReceiveDestination

    /// The security-scoped override when one is set and writable, otherwise
    /// Documents/LANyard.
    public func writableRoot() -> URL? {
        if let overrideURL, InboxDestination.isWritableDirectory(overrideURL) {
            return overrideURL
        }
        return DefaultReceiveDestination(documents: { Self.documentsDirectory() }).writableRoot()
    }

    // MARK: - Override (UIDocumentPicker)

    public var isUsingOverride: Bool { overrideURL != nil }

    /// A human-readable description for Settings; never a free-text path.
    public var displayPath: String {
        if let overrideURL {
            return overrideURL.lastPathComponent
        }
        return "Documents/\(DefaultReceiveDestination.folderName)"
    }

    /// Drops the override and falls back to Documents/LANyard.
    public func useDefault() {
        releaseScopedAccess()
        overrideURL = nil
        defaults.removeObject(forKey: Self.bookmarkDefaultsKey)
    }

    /// Persists a security-scoped bookmark for a folder chosen in the picker
    /// and holds access on it. Throws if the URL cannot be accessed or resolved.
    public func setOverride(from url: URL) throws {
        // The picker hands back a security-scoped URL; access must be started
        // before a bookmark can be made useful across launches.
        guard url.startAccessingSecurityScopedResource() else {
            throw InboxDestinationError.notWritable
        }
        let bookmark: Data
        do {
            // MAC-SPIKE: `withSecurityScope` is a macOS-only creation option.
            // On iOS the bookmark for a document-picker URL is security-scoped
            // implicitly; verify behavior on the first Mac (SPIKE.md step 5).
            #if os(macOS)
            bookmark = try url.bookmarkData(
                options: [.withSecurityScope],
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            )
            #else
            bookmark = try url.bookmarkData(
                options: [],
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            )
            #endif
        } catch {
            url.stopAccessingSecurityScopedResource()
            throw InboxDestinationError.bookmarkFailed
        }

        releaseScopedAccess()
        defaults.set(bookmark, forKey: Self.bookmarkDefaultsKey)
        overrideURL = url
        isAccessingScoped = true
    }

    // MARK: - Bookmark restore

    private func restoreOverride() {
        guard let data = defaults.data(forKey: Self.bookmarkDefaultsKey) else { return }
        var stale = false
        let resolved: URL?
        do {
            // MAC-SPIKE: `withSecurityScope` resolution option is likewise
            // macOS-only; iOS resolves the bookmark and then grants access.
            #if os(macOS)
            resolved = try URL(
                resolvingBookmarkData: data,
                options: [.withSecurityScope],
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            )
            #else
            resolved = try URL(
                resolvingBookmarkData: data,
                options: [],
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            )
            #endif
        } catch {
            // A stale or broken bookmark just falls back to the default folder.
            defaults.removeObject(forKey: Self.bookmarkDefaultsKey)
            return
        }
        guard let url = resolved else { return }
        isAccessingScoped = url.startAccessingSecurityScopedResource()
        if stale || !isAccessingScoped {
            // Re-resolving will not help; drop it and use the default.
            url.stopAccessingSecurityScopedResource()
            defaults.removeObject(forKey: Self.bookmarkDefaultsKey)
            overrideURL = nil
            isAccessingScoped = false
            return
        }
        overrideURL = url
    }

    private func releaseScopedAccess() {
        if isAccessingScoped {
            overrideURL?.stopAccessingSecurityScopedResource()
        }
        isAccessingScoped = false
    }
}
#endif

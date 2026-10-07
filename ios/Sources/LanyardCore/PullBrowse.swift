import Foundation

/// One share a peer offers, as the browse list shows it. Mirrors the Kotlin app
/// `ShareItem` (`DevicesViewModel.kt`), reduced to the fields the pull model
/// needs.
public struct BrowseShare: Equatable, Identifiable {
    public var id: String
    public var label: String
    public var name: String
    public var kind: String
    public var size: Int64
    public var lifetime: String

    public init(id: String, label: String, name: String = "", kind: String = "", size: Int64 = 0, lifetime: String = "until_stopped") {
        self.id = id
        self.label = label
        self.name = name
        self.kind = kind
        self.size = size
        self.lifetime = lifetime
    }
}

/// One entry in a share's folder tree. Mirrors the Kotlin app `TreeItem`.
public struct BrowseEntry: Equatable {
    public var name: String
    public var path: String
    public var isDir: Bool
    public var size: Int64

    public init(name: String, path: String, isDir: Bool, size: Int64) {
        self.name = name
        self.path = path
        self.isDir = isDir
        self.size = size
    }
}

/// The browse operations a pull needs (list a peer's shares, list a folder).
/// The app implements it over `PeerClient`; tests supply fakes. `DownloadSession`
/// already owns the manifest/file/hash half via `ShareReader`.
///
/// Package because it is a transport seam alongside `ShareReader`.
package protocol BrowseClient: AnyObject {
    func listShares() throws -> Data
    func tree(shareId: String, path: String) throws -> Data
}

/// The pull/browse model: list a peer's shares, walk a share's tree, pick files
/// or folders, then download the selection with resume and SHA-256 verification.
///
/// Built over the existing `DownloadSession` seams rather than reimplementing
/// them: `ShareReader` moves bytes/hashes, `BrowseClient` lists, and the pure
/// choose/parse rules live here. The whole-share / subpath download is
/// `DownloadSession.download`; a filtered selection is
/// `DownloadSession.downloadFiles`, so resume, verification and cancel are the
/// same code path exercised by `DownloadSessionTests`.
public final class PullBrowse {
    private let reader: ShareReader
    private let browser: BrowseClient
    private let session: DownloadSession

    package init(reader: ShareReader, browser: BrowseClient, throttle: Throttle = NoThrottle.shared) {
        self.reader = reader
        self.browser = browser
        self.session = DownloadSession(reader: reader, throttle: throttle)
    }

    // MARK: - Browse

    /// The peer's visible shares.
    public func shares() throws -> [BrowseShare] {
        Self.parseShares(try browser.listShares())
    }

    /// The children of `path` (`""` = the share root).
    public func tree(shareId: String, path: String) throws -> [BrowseEntry] {
        Self.parseTree(try browser.tree(shareId: shareId, path: path))
    }

    /// The parsed manifest of `path`, before any selection is applied.
    ///
    /// Package because it returns the internal `ManifestFile` shared with
    /// `DownloadSession`; the app (same package) consumes it.
    package func manifest(shareId: String, path: String) throws -> [ManifestFile] {
        DownloadSession.parseManifest(try reader.manifestFiles(shareId: shareId, path: path))
    }

    // MARK: - Selection

    /// Narrows a manifest to the chosen relative paths. `include == nil` keeps
    /// every file (the whole share/folder); an empty set keeps none. Paths not in
    /// the manifest are ignored, so a stale selection can never add a file.
    package func pick(_ files: [ManifestFile], include: Set<String>?) -> [ManifestFile] {
        guard let include else { return files }
        return files.filter { include.contains($0.path) }
    }

    // MARK: - Download

    /// Downloads `path` (or the chosen subset) using `DownloadSession`, with the
    /// same resume, SHA-256 verification and cancel semantics. A 410 manifest
    /// becomes `.shareEnded`, any other transport error `.peerUnreachable`.
    ///
    /// Package because `DownloadTarget` is the internal download seam.
    package func download(
        shareId: String,
        path: String,
        include: Set<String>? = nil,
        targetFor: (ManifestFile) -> DownloadTarget,
        onProgress: (_ index: Int, _ file: ManifestFile, _ received: Int64, _ total: Int64) -> Void = { _, _, _, _ in },
        isCancelled: () -> Bool = { false }
    ) -> DownloadResult {
        let manifestData: Data
        do {
            manifestData = try reader.manifestFiles(shareId: shareId, path: path)
        } catch let e as PeerHttpException {
            return e.code == 410 ? .shareEnded : .peerUnreachable
        } catch {
            return .peerUnreachable
        }
        let selected = pick(DownloadSession.parseManifest(manifestData), include: include)
        return session.downloadFiles(
            shareId: shareId,
            files: selected,
            targetFor: targetFor,
            onProgress: onProgress,
            isCancelled: isCancelled
        )
    }

    // MARK: - Parsing

    static func parseShares(_ data: Data) -> [BrowseShare] {
        guard let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { return [] }
        return array.compactMap { element in
            guard let obj = element as? [String: Any] else { return nil }
            return BrowseShare(
                id: str(obj, "share_id"),
                label: str(obj, "label"),
                name: str(obj, "name"),
                kind: str(obj, "kind"),
                size: long(obj, "size"),
                lifetime: str(obj, "lifetime").isEmpty ? "until_stopped" : str(obj, "lifetime")
            )
        }
    }

    static func parseTree(_ data: Data) -> [BrowseEntry] {
        guard let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { return [] }
        return array.compactMap { element in
            guard let obj = element as? [String: Any] else { return nil }
            return BrowseEntry(
                name: str(obj, "name"),
                path: str(obj, "path"),
                isDir: bool(obj, "is_dir"),
                size: long(obj, "size")
            )
        }
    }

    private static func str(_ obj: [String: Any], _ key: String) -> String {
        guard let v = obj[key], !(v is NSNull) else { return "" }
        return v as? String ?? ""
    }

    private static func long(_ obj: [String: Any], _ key: String) -> Int64 {
        guard let v = obj[key], !(v is NSNull) else { return 0 }
        if let n = v as? NSNumber { return n.int64Value }
        if let i = v as? Int64 { return i }
        if let i = v as? Int { return Int64(i) }
        return 0
    }

    private static func bool(_ obj: [String: Any], _ key: String) -> Bool {
        guard let v = obj[key], !(v is NSNull) else { return false }
        if let b = v as? Bool { return b }
        if let n = v as? NSNumber { return n.boolValue }
        return false
    }
}

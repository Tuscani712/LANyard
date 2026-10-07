// ShareServerAdapter.swift — the share/serve side of Phase 5.
//
// WRITTEN, NOT COMPILED. Guarded with `#if canImport(Network) && canImport(Security)`
// so on Linux it compiles to nothing and the package build/test stay green.
//
// Three pieces:
//   * `AppShareSource` — the app's concrete `ShareSource`: a persisted catalog of
//     security-scoped share roots (added from `UIDocumentPicker`), scanned to
//     serve `list`/`children`/`resolve`. It also conforms to `ShareRegistrar` so
//     `SharesModel` can add/remove without assuming a core `add` API.
//   * `ShareRouter` — maps the `/api/v1/shares/*` routes onto `ShareList`
//     (`list`/`manifest`/`tree`/`hash`/`file`/`complete`), including the `410`
//     ended-share and `503` concurrency-gate surfaces.
//   * `ShareServer` — an `NWListener`-backed server (through the existing
//     `PeerListener`, which owns the TLS 1.3 mTLS `NWListener`) routing share
//     traffic. In production these routes should be folded into the single
//     `PeerServerAdapter` listener so share requests run behind the same
//     per-request authorization; see MAC-SPIKE below.
//   * `LanyardBrowseClient` — the pull-side `BrowseClient` over `PeerConnector`.

#if canImport(Network) && canImport(Security)
import Foundation
import Network
import Security
import LanyardCore

// MARK: - Identity bridge

extension PeerIdentity {
    /// Builds the pairing/pull identity view from the app's `DeviceIdentity`.
    init(device: DeviceIdentity, name: String) {
        self.deviceId = device.fingerprint
        self.name = name
        self.identity = device.identity
    }
}

// MARK: - App share source

/// The persisted, security-scoped catalog of what this app shares.
///
/// The core `ShareSource` is read/serve only, so the app owns the mutation: each
/// entry is a bookmark to a picked file or folder, resolved and security-scoped
/// at load. The `ShareList` in front of it applies lifetime/stop/expiry rules.
public final class AppShareSource: ShareSource, ShareRegistrar {
    /// One persisted share root.
    private struct ShareRecord: Codable {
        var id: String
        var label: String
        var kind: String
        var lifetime: String
        var expiresAt: Int64?
        var createdAt: Int64
        var size: Int64
        var bookmark: Data
    }

    private let bookmarksFile: URL
    private let selfName: String
    private let lock = NSLock()
    private var records: [ShareRecord] = []
    private var roots: [String: URL] = [:]
    private var scoped: [String: Bool] = [:]

    public init(bookmarksFile: URL, selfName: String) {
        self.bookmarksFile = bookmarksFile
        self.selfName = selfName
        load()
    }

    deinit {
        releaseAll()
    }

    // MARK: - ShareSource

    public func list() -> [ShareInfo] {
        lock.lock(); defer { lock.unlock() }
        return records.map { record in
            ShareInfo(
                id: record.id,
                label: record.label,
                name: record.label,
                kind: record.kind,
                size: record.size,
                lifetime: ShareLifetimeType(rawValue: record.lifetime) ?? .untilStopped,
                expiresAt: record.expiresAt,
                createdAt: record.createdAt
            )
        }
    }

    public func children(shareId: String, rel: String) -> [ShareChild]? {
        guard SafePath.validRel(rel), let root = root(for: shareId) else { return nil }
        let base = rel.isEmpty ? root : root.appendingPathComponent(rel)
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: base,
            includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        ) else { return nil }

        var children: [ShareChild] = []
        for url in urls {
            let values = try? url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey])
            let isDir = values?.isDirectory ?? false
            let name = url.lastPathComponent
            guard SafePath.validSegment(name) else { continue }
            children.append(ShareChild(
                name: name,
                path: SafePath.childPath(rel, name),
                isDir: isDir,
                size: Int64(values?.fileSize ?? 0),
                mtimeMillis: Int64((values?.contentModificationDate ?? Date()).timeIntervalSince1970 * 1000)
            ))
        }
        return children
    }

    public func resolve(shareId: String, rel: String) -> ResolvedShareFile? {
        guard SafePath.validRel(rel), !rel.isEmpty, let root = root(for: shareId) else { return nil }
        let url = root.appendingPathComponent(rel)
        guard isInsideRoot(url, root: root),
              let values = try? url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey]),
              values.isDirectory != true else { return nil }
        let name = url.lastPathComponent
        let size = Int64(values.fileSize ?? 0)
        let mtime = Int64((values.contentModificationDate ?? Date()).timeIntervalSince1970 * 1000)
        return ResolvedShareFile(
            name: name,
            size: size,
            mtimeMillis: mtime,
            openAt: { offset in SecurityScopedByteSource(url: url, offset: offset) }
        )
    }

    public func ended(shareId: String) -> String? {
        // Stop/expiry are applied centrally by `ShareList.gate`; the source has
        // no independent end state.
        return nil
    }

    // MARK: - ShareRegistrar

    @discardableResult
    public func addShare(url: URL, label: String, lifetime: ShareLifetimeType) throws -> ShareInfo {
        // The picker hands back a security-scoped URL; access must be started
        // before a bookmark is useful across launches.
        let started = url.startAccessingSecurityScopedResource()
        do {
            let bookmark = try makeBookmark(url)
            let id = Self.newId()
            let values = try? url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
            let isDir = values?.isDirectory ?? false
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let record = ShareRecord(
                id: id,
                label: label.isEmpty ? url.lastPathComponent : label,
                kind: isDir ? "folder" : "file",
                lifetime: lifetime.rawValue,
                expiresAt: Self.expiry(for: lifetime, now: now),
                createdAt: now,
                size: isDir ? 0 : Int64(values?.fileSize ?? 0),
                bookmark: bookmark
            )
            lock.lock()
            records.append(record)
            roots[id] = url
            scoped[id] = started
            persistLocked()
            lock.unlock()
            return ShareInfo(
                id: id, label: record.label, name: record.label, kind: record.kind,
                size: record.size, lifetime: lifetime, expiresAt: record.expiresAt, createdAt: record.createdAt
            )
        } catch {
            if started { url.stopAccessingSecurityScopedResource() }
            throw error
        }
    }

    public func removeShare(id: String) {
        lock.lock()
        records.removeAll { $0.id == id }
        let url = roots.removeValue(forKey: id)
        let wasScoped = scoped.removeValue(forKey: id) ?? false
        persistLocked()
        lock.unlock()
        if wasScoped { url?.stopAccessingSecurityScopedResource() }
    }

    // MARK: - Persistence

    private func load() {
        guard let data = try? Data(contentsOf: bookmarksFile),
              let decoded = try? JSONDecoder().decode([ShareRecord].self, from: data) else {
            return
        }
        var resolvedRecords: [ShareRecord] = []
        for record in decoded {
            var stale = false
            let url: URL?
            do {
                // MAC-SPIKE: `withSecurityScope` is the macOS spelling; on iOS
                // the document-picker bookmark is security-scoped implicitly.
                #if os(macOS)
                url = try URL(resolvingBookmarkData: record.bookmark,
                              options: [.withSecurityScope],
                              relativeTo: nil, bookmarkDataIsStale: &stale)
                #else
                url = try URL(resolvingBookmarkData: record.bookmark,
                              options: [],
                              relativeTo: nil, bookmarkDataIsStale: &stale)
                #endif
            } catch {
                url = nil
            }
            guard !stale, let url else { continue }
            let started = url.startAccessingSecurityScopedResource()
            roots[record.id] = url
            scoped[record.id] = started
            resolvedRecords.append(record)
        }
        records = resolvedRecords
    }

    private func makeBookmark(_ url: URL) throws -> Data {
        #if os(macOS)
        return try url.bookmarkData(options: [.withSecurityScope], includingResourceValuesForKeys: nil, relativeTo: nil)
        #else
        return try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        #endif
    }

    private func persistLocked() {
        guard let data = try? JSONEncoder().encode(records) else { return }
        try? FileManager.default.createDirectory(
            at: bookmarksFile.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try? data.write(to: bookmarksFile, options: .atomic)
    }

    private func root(for shareId: String) -> URL? {
        lock.lock(); defer { lock.unlock() }
        return roots[shareId]
    }

    private func releaseAll() {
        lock.lock(); defer { lock.unlock() }
        for (id, url) in roots where scoped[id] == true {
            url.stopAccessingSecurityScopedResource()
        }
        roots.removeAll()
        scoped.removeAll()
    }

    /// True when `url` resolves inside `root` (no `..` escape, no sibling with a
    /// shared name prefix).
    private func isInsideRoot(_ url: URL, root: URL) -> Bool {
        let rootPath = root.standardizedFileURL.path
        let path = url.standardizedFileURL.path
        return path == rootPath || path.hasPrefix(rootPath + "/")
    }

    private static func expiry(for lifetime: ShareLifetimeType, now: Int64) -> Int64? {
        switch lifetime {
        case .persistent, .untilStopped: return nil
        case .timed: return now + 60 * 60 * 1000
        case .oneTime: return now + ShareInfo.oneTimeSafetyExpiryMillis
        }
    }

    private static func newId() -> String {
        let hex = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        return "s_" + String(hex.prefix(16))
    }
}

// MARK: - Browse client

/// The pull-side `BrowseClient`: list a peer's shares and walk a share's tree
/// over the pinned mTLS `PeerConnector` (the same client the `ShareReader` uses).
final class LanyardBrowseClient: BrowseClient {
    private let connector: PeerConnector

    init(host: String, port: Int, identity: PeerIdentity, expectedFingerprint: String) {
        self.connector = PeerConnector(
            host: host, port: port, identity: identity.identity,
            expectedFingerprint: expectedFingerprint
        )
    }

    func listShares() throws -> Data {
        try connector.request(method: "GET", path: "/api/v1/shares").bodyData
    }

    func tree(shareId: String, path: String) throws -> Data {
        try connector.request(
            method: "GET",
            path: "/api/v1/shares/\(shareEncodePath(shareId))/tree?path=\(shareEncodeQuery(path))"
        ).bodyData
    }
}

private func shareEncodePath(_ value: String) -> String {
    value.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? value
}

private func shareEncodeQuery(_ value: String) -> String {
    value.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? value
}

// MARK: - Share router

/// Routes the share endpoints into `ShareList`. Returns `nil` for any path that
/// is not a share route, so a caller (`PeerServerAdapter`) can delegate without
/// pre-matching.
final class ShareRouter {
    private let list: ShareList
    /// The bytes seam. `ShareList` keeps its `source` private and exposes only
    /// decisions (`manifest`/`tree`/`hash`), so the adapter holds the same
    /// `ShareSource` to stream file bodies.
    private let source: ShareSource

    init(list: ShareList, source: ShareSource) {
        self.list = list
        self.source = source
    }

    /// A `PeerListener`-compatible handler.
    func handler() -> (PeerRequest) -> PeerResponse {
        { [weak self] request in
            self?.route(request) ?? PeerResponse(status: 404, text: PeerServerAdapter.errorJSON("not found"))
        }
    }

    func route(_ request: PeerRequest) -> PeerResponse? {
        let path = request.path
        guard path.hasPrefix("/api/v1/shares") else { return nil }

        if request.method == "GET" && path == "/api/v1/shares" {
            return shareList()
        }
        let rest = String(path.dropFirst("/api/v1/shares/".count))
        let parts = rest.split(separator: "/").map { String($0).removingPercentEncoding ?? String($0) }
        guard let shareId = parts.first, !shareId.isEmpty else {
            return PeerResponse(status: 400, text: PeerServerAdapter.errorJSON("share id required"))
        }
        let action = parts.count > 1 ? parts[1] : ""
        let rel = PeerServerAdapter.queryParam(request.query, "path") ?? ""

        switch (request.method, action) {
        case ("GET", "manifest"):
            return withLease(request) { self.manifest(shareId: shareId, path: rel) }
        case ("GET", "tree"):
            return withLease(request) { self.tree(shareId: shareId, path: rel) }
        case ("GET", "hash"):
            return withLease(request) { self.hash(shareId: shareId, path: rel) }
        case ("GET", "file"):
            return withLease(request) { self.file(request, shareId: shareId, path: rel) }
        case ("POST", "complete"):
            return complete(request, shareId: shareId)
        default:
            return PeerResponse(status: 404, text: PeerServerAdapter.errorJSON("not found"))
        }
    }

    // MARK: Routes

    private func shareList() -> PeerResponse {
        let entries: [[String: Any]] = list.list().map { info in
            [
                "share_id": info.id,
                "label": info.label,
                "name": info.name,
                "kind": info.kind,
                "size": info.size,
                "lifetime": info.lifetime.rawValue,
                "expires_at": info.expiresAt ?? 0,
                "created_at": info.createdAt,
            ]
        }
        return PeerResponse(status: 200, text: json(entries))
    }

    private func manifest(shareId: String, path: String) -> PeerResponse {
        switch list.manifest(shareId, path: path) {
        case let .ok(files, totalBytes, lifetime):
            let entries: [[String: Any]] = files.map { file in
                [
                    "path": file.path,
                    "name": file.name,
                    "size": file.size,
                    "mtime": file.mtimeMillis,
                    "etag": file.etag,
                ]
            }
            return PeerResponse(status: 200, text: json([
                "files": entries,
                "total_bytes": totalBytes,
                "lifetime": lifetime.rawValue,
            ]))
        case .badPath:
            return PeerResponse(status: 400, text: PeerServerAdapter.errorJSON("bad path"))
        case .notFound:
            return PeerResponse(status: 404, text: PeerServerAdapter.errorJSON("no such share"))
        case let .gone(message):
            return PeerResponse(status: 410, text: PeerServerAdapter.errorJSON(message))
        case .tooMany:
            return PeerResponse(status: 413, text: PeerServerAdapter.errorJSON("manifest too large"))
        }
    }

    private func tree(shareId: String, path: String) -> PeerResponse {
        switch list.tree(shareId, path: path) {
        case let .ok(children):
            let entries: [[String: Any]] = children.map { child in
                [
                    "name": child.name,
                    "path": child.path,
                    "is_dir": child.isDir,
                    "size": child.size,
                ]
            }
            return PeerResponse(status: 200, text: json(entries))
        case .badPath:
            return PeerResponse(status: 400, text: PeerServerAdapter.errorJSON("bad path"))
        case .notFound:
            return PeerResponse(status: 404, text: PeerServerAdapter.errorJSON("no such share"))
        case let .gone(message):
            return PeerResponse(status: 410, text: PeerServerAdapter.errorJSON(message))
        }
    }

    private func hash(shareId: String, path: String) -> PeerResponse {
        switch list.hash(shareId, path: path) {
        case let .ok(sha256, size, etag):
            return PeerResponse(status: 200, text: json(["sha256": sha256, "size": size, "etag": etag]))
        case .badPath:
            return PeerResponse(status: 400, text: PeerServerAdapter.errorJSON("bad path"))
        case .notFound:
            return PeerResponse(status: 404, text: PeerServerAdapter.errorJSON("no such file"))
        case let .gone(message):
            return PeerResponse(status: 410, text: PeerServerAdapter.errorJSON(message))
        case .unreadable:
            return PeerResponse(status: 500, text: PeerServerAdapter.errorJSON("the file could not be read"))
        }
    }

    /// Serves a file body, honouring a single `Range` and `If-Range`.
    ///
    /// MAC-SPIKE: `PeerResponse` carries a buffered `Data` body only. Large-share
    /// serving needs a streaming response (a `ByteSource` body plus status/header
    /// control) on `PeerListener`; until then this buffers the whole file and only
    /// the `Content-Range` semantics are modelled via the status/shape. Also
    /// `PeerResponse` cannot emit a `Retry-After` header, so a gate refusal is a
    /// bare `503`.
    private func file(_ request: PeerRequest, shareId: String, path: String) -> PeerResponse {
        guard !path.isEmpty else {
            return PeerResponse(status: 400, text: PeerServerAdapter.errorJSON("path required"))
        }
        let resolved: ResolvedShareFile?
        switch list.gate(shareId) {
        case let .gone(message): return PeerResponse(status: 410, text: PeerServerAdapter.errorJSON(message))
        case .notFound: return PeerResponse(status: 404, text: PeerServerAdapter.errorJSON("no such share"))
        case .ok: break
        }
        guard SafePath.validRel(path) else {
            return PeerResponse(status: 400, text: PeerServerAdapter.errorJSON("bad path"))
        }
        resolved = source.resolve(shareId: shareId, rel: path)
        guard let file = resolved else {
            return PeerResponse(status: 404, text: PeerServerAdapter.errorJSON("no such file"))
        }

        let etag = ShareList.validator(size: file.size, mtimeMillis: file.mtimeMillis)
        let ifRange = request.headers["if-range"]
        switch ShareList.resolveRange(header: request.headers["range"], ifRange: ifRange, size: file.size, etag: etag) {
        case let .full(start, length):
            return PeerResponse(status: 200, body: readAll(file.openAt(start), length: length))
        case let .partial(start, length):
            // MAC-SPIKE: the 206 status is set, but `PeerResponse` cannot carry
            // `Content-Range`; the body is the requested slice.
            return PeerResponse(status: 206, body: readAll(file.openAt(start), length: length))
        case .unsatisfiable:
            return PeerResponse(status: 416, text: PeerServerAdapter.errorJSON("range not satisfiable"))
        }
    }

    private func complete(_ request: PeerRequest, shareId: String) -> PeerResponse {
        if (try? JSONSerialization.jsonObject(with: request.body)) as? [String: Any] != nil {
            let consumed = list.consume(shareId, by: request.peerFingerprint)
            return PeerResponse(status: 200, text: json(["consumed": consumed]))
        }
        return PeerResponse(status: 400, text: PeerServerAdapter.errorJSON("bad json body"))
    }

    // MARK: Gate / helpers

    /// Acquires a concurrency lease for the request; a refusal is the `503` with
    /// `Retry-After` the Android server sends (the header itself is not
    /// representable on `PeerResponse` today — see `file`).
    private func withLease(_ request: PeerRequest, _ work: () -> PeerResponse) -> PeerResponse {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        guard let lease = list.concurrency.acquire(peer: request.peerFingerprint, now: now) else {
            return PeerResponse(status: 503, text: PeerServerAdapter.errorJSON("server busy; retry later"))
        }
        defer { list.concurrency.release(lease) }
        return work()
    }

    private func readAll(_ source: ByteSource, length: Int64) -> Data {
        var out = Data()
        out.reserveCapacity(Int(max(0, min(length, Int64(64 * 1024 * 1024)))))
        var buffer = [UInt8](repeating: 0, count: 256 * 1024)
        while true {
            let n = source.read(&buffer, offset: 0, count: buffer.count)
            if n < 0 { break }
            if n == 0 { continue }
            out.append(contentsOf: buffer[0..<n])
        }
        return out
    }

    private func json(_ value: Any) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: value),
              let text = String(data: data, encoding: .utf8) else { return "{}" }
        return text
    }
}

// MARK: - Share server

/// An `NWListener`-backed share server. The listener itself is the existing
/// `PeerListener` (TLS 1.3 mTLS `NWListener`); this type owns its lifecycle and
/// plugs in the `ShareRouter`.
///
/// MAC-SPIKE: this starts a *second* listener on its own port. The real design
/// should mount `ShareRouter.route` inside the single `PeerServerAdapter`
/// handler, so `/shares/*` runs behind the same per-request authorization as
/// push/pairing and so there is one advertised port. Kept separate here because
/// `PeerServerAdapter`'s config does not yet carry a `ShareList`.
public final class ShareServer {
    private let router: ShareRouter
    private let identity: SecIdentity
    private let port: UInt16
    private var listener: PeerListener?

    // Internal: its `ShareSource` parameter is package-private, so the app
    // constructs it through `ShareServices` below.
    init(list: ShareList, source: ShareSource, identity: SecIdentity, port: UInt16 = 0) {
        self.router = ShareRouter(list: list, source: source)
        self.identity = identity
        self.port = port
    }
    /// Starts listening and returns the bound port.
    @discardableResult
    public func start() throws -> UInt16 {
        let listener = PeerListener(identity: identity, port: port, handler: router.handler())
        self.listener = listener
        return try listener.start()
    }

    public func stop() {
        listener?.stop()
        listener = nil
    }

    public var isRunning: Bool { listener != nil }
}

// MARK: - Composition

/// Builds the serve stack over one `AppShareSource` so the `SharesModel` the UI
/// observes and the `ShareServer` peers reach agree on one catalog and one
/// `ShareList` (stop/expiry/concurrency state).
public final class ShareServices {
    public let model: SharesModel
    public let server: ShareServer

    public init(identity: DeviceIdentity, bookmarksFile: URL, selfName: String, port: UInt16 = 0) {
        let source = AppShareSource(bookmarksFile: bookmarksFile, selfName: selfName)
        let list = ShareList(source: source)
        self.model = SharesModel(list: list, registrar: source)
        self.server = ShareServer(list: list, source: source, identity: identity.identity, port: port)
    }
}
#endif

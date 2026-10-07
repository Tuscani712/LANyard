import Foundation
import Crypto

/// Pure path rules for a manifest/file request against a share root.
///
/// Ported from the Kotlin `SafePath` (`ShareServer.kt`) and kept identical to the
/// Go `shares` confinement rules: an empty segment, `.`, `..`, a separator, a
/// drive/ADS `:` or an ISO control character is never served.
public enum SafePath {
    public static let maxSegment = 255

    public static func validSegment(_ s: String) -> Bool {
        if s.isEmpty || s == "." || s == ".." { return false }
        if s.count > maxSegment { return false }
        return !s.contains { c in
            c == "/" || c == "\\" || c == ":" || c == "\u{0000}" || Self.isISOControl(c)
        }
    }

    /// A safe relative path: `""` (the root) or valid segments joined by `/`.
    public static func validRel(_ rel: String) -> Bool {
        if rel.isEmpty { return true }
        if rel.hasPrefix("/") || rel.hasPrefix("\\") { return false }
        return rel.split(separator: "/", omittingEmptySubsequences: false).allSatisfy {
            validSegment(String($0))
        }
    }

    public static func childPath(_ base: String, _ name: String) -> String {
        base.isEmpty ? name : "\(base)/\(name)"
    }

    private static func isISOControl(_ c: Character) -> Bool {
        guard c.unicodeScalars.count == 1, let v = c.unicodeScalars.first?.value else { return false }
        return (0x00...0x1F).contains(v) || (0x7F...0x9F).contains(v)
    }
}

/// A share's lifecycle type (spec §6.2), matching `internal/shares`.
public enum ShareLifetimeType: String, Codable, Equatable {
    case persistent
    case untilStopped = "until_stopped"
    case timed
    case oneTime = "one_time"
}

/// A share as the peer sees it (mirrors `shares.Summary`). `expiresAt` is epoch
/// millis from the model's own clock; nil means no time-based end.
public struct ShareInfo: Codable, Equatable {
    public var id: String
    public var label: String
    public var name: String
    public var kind: String
    public var size: Int64
    public var lifetime: ShareLifetimeType
    public var expiresAt: Int64?
    public var createdAt: Int64

    public init(
        id: String,
        label: String,
        name: String = "",
        kind: String = "folder",
        size: Int64 = 0,
        lifetime: ShareLifetimeType = .untilStopped,
        expiresAt: Int64? = nil,
        createdAt: Int64 = 0
    ) {
        self.id = id
        self.label = label
        self.name = name
        self.kind = kind
        self.size = size
        self.lifetime = lifetime
        self.expiresAt = expiresAt
        self.createdAt = createdAt
    }

    /// The fallback expiry for a one-time share: 24 h, matching Go's
    /// `OneTimeSafetyExpiry`.
    public static let oneTimeSafetyExpiryMillis: Int64 = 24 * 60 * 60 * 1000
}

/// One directory entry while building a manifest/tree.
public struct ShareChild: Equatable {
    public var name: String
    public var path: String
    public var isDir: Bool
    public var size: Int64
    public var mtimeMillis: Int64

    public init(name: String, path: String, isDir: Bool, size: Int64, mtimeMillis: Int64) {
        self.name = name
        self.path = path
        self.isDir = isDir
        self.size = size
        self.mtimeMillis = mtimeMillis
    }
}

/// A resolved, readable file inside a share. `openAt` returns a `ByteSource`
/// positioned at `offset`.
///
/// Package because it carries the package `ByteSource` seam. Ported from the
/// Kotlin `ResolvedFile`.
package struct ResolvedShareFile {
    package var name: String
    package var size: Int64
    package var mtimeMillis: Int64
    package var openAt: (Int64) -> ByteSource
}

/// The phone's shares, backed by the SAF tree in the app and by a temp dir in
/// tests. Implementations MUST reject an unsafe `rel` (see `SafePath`) and MUST
/// only ever resolve a document inside the picked share root.
///
/// Ported from the Kotlin `ShareSource`; `Package` because `ResolvedShareFile`
/// carries `ByteSource`. The app implements it in `LanyardNet` (same package).
package protocol ShareSource: AnyObject {
    func list() -> [ShareInfo]

    /// Children of `rel` (`""` = the root), or nil if the share/path is missing.
    func children(shareId: String, rel: String) -> [ShareChild]?

    /// The file at `rel`, or nil if missing/unsafe.
    func resolve(shareId: String, rel: String) -> ResolvedShareFile?

    /// A reason string when the share ended/was stopped, else nil.
    func ended(shareId: String) -> String?
}

/// One file listed in a manifest.
public struct ManifestEntry: Equatable {
    public var path: String
    public var name: String
    public var size: Int64
    public var mtimeMillis: Int64
    public var etag: String
}

/// The outcome of a manifest request, with the status the adapter writes.
public enum ManifestResult: Equatable {
    case ok(files: [ManifestEntry], totalBytes: Int64, lifetime: ShareLifetimeType)
    case badPath        // 400
    case notFound       // 404
    case gone(String)   // 410
    case tooMany        // 413
}

/// The outcome of a tree request.
public enum TreeResult: Equatable {
    case ok([ShareChild])
    case badPath
    case notFound
    case gone(String)
}

/// The outcome of a hash request.
public enum HashResult: Equatable {
    case ok(sha256: String, size: Int64, etag: String)
    case badPath
    case notFound
    case gone(String)
    case unreadable
}

/// The outcome of a share gate: is the share live, missing, or ended?
public enum ShareGate: Equatable {
    case ok
    case notFound
    case gone(String)
}

/// A single-range decision, mirroring Kotlin `ShareServer.parseRange`.
public enum RangeDecision: Equatable {
    /// The whole body from 0.
    case full(start: Int64, length: Int64)
    /// A `206` body.
    case partial(start: Int64, length: Int64)
    /// A `416`.
    case unsatisfiable
}

/// A bounded, access-ordered LRU of whole-file digests, the Swift port of the
/// Kotlin `ShareServer`'s `LinkedHashMap` with `HASH_CACHE_MAX = 256`. A peer
/// asking for many hashes must not grow memory without bound.
public final class DigestCache {
    public static let defaultCapacity = 256

    public let capacity: Int
    private var order: [String] = []
    private var values: [String: String] = [:]

    public init(capacity: Int = DigestCache.defaultCapacity) {
        self.capacity = max(0, capacity)
    }

    public var count: Int { values.count }

    public func get(_ key: String) -> String? {
        guard let value = values[key] else { return nil }
        touch(key)
        return value
    }

    public func put(_ key: String, _ value: String) {
        if values[key] == nil, capacity > 0, values.count >= capacity, let oldest = order.first {
            order.removeFirst()
            values.removeValue(forKey: oldest)
        }
        values[key] = value
        touch(key)
    }

    public func removeAll() {
        order.removeAll()
        values.removeAll()
    }

    private func touch(_ key: String) {
        if let i = order.firstIndex(of: key) { order.remove(at: i) }
        order.append(key)
    }
}

/// A per-peer and global concurrency cap, the Swift port of the Kotlin
/// `ShareServer`'s two `Semaphore(maxConcurrent)`s. Beyond the cap the adapter
/// answers `503` with `Retry-After`; work is never queued, so a stalled reader
/// cannot hold a slot.
///
/// `acquire` returns a lease id (or nil when the cap is hit). `sweepStalled`
/// releases leases with no activity within `stallTimeoutMillis`, which is the
/// pure half of the Kotlin `streamWithDeadline` guard.
public final class ConcurrencyGate {
    public static let defaultMaxConcurrent = 4
    public static let defaultStallTimeoutMillis: Int64 = 30_000

    public let maxConcurrent: Int
    public let stallTimeoutMillis: Int64
    public let retryAfterSeconds = 5

    private let lock = NSLock()
    private var globalCount = 0
    private var perPeer: [String: Int] = [:]
    private var leases: [Int: (peer: String, lastActivity: Int64)] = [:]
    private var nextLease = 1

    public init(
        maxConcurrent: Int = ConcurrencyGate.defaultMaxConcurrent,
        stallTimeoutMillis: Int64 = ConcurrencyGate.defaultStallTimeoutMillis
    ) {
        self.maxConcurrent = max(1, maxConcurrent)
        self.stallTimeoutMillis = stallTimeoutMillis
    }

    /// Tries to take a file/hash slot for `peer`. Returns nil when the per-peer
    /// or global cap is reached (the caller answers 503).
    public func acquire(peer: String, now: Int64) -> Int? {
        let key = peer.lowercased()
        lock.lock(); defer { lock.unlock() }
        if globalCount >= maxConcurrent { return nil }
        if (perPeer[key] ?? 0) >= maxConcurrent { return nil }
        globalCount += 1
        perPeer[key, default: 0] += 1
        let lease = nextLease
        nextLease += 1
        leases[lease] = (key, now)
        return lease
    }

    public func noteActivity(_ lease: Int, now: Int64) {
        lock.lock(); defer { lock.unlock() }
        if var entry = leases[lease] {
            entry.lastActivity = now
            leases[lease] = entry
        }
    }

    public func release(_ lease: Int) {
        lock.lock(); defer { lock.unlock() }
        guard let entry = leases.removeValue(forKey: lease) else { return }
        globalCount = max(0, globalCount - 1)
        if let n = perPeer[entry.peer], n > 1 { perPeer[entry.peer] = n - 1 } else { perPeer.removeValue(forKey: entry.peer) }
    }

    /// Releases every lease that has gone quiet for `stallTimeoutMillis` and
    /// returns their ids. Mirrors the stall guard abandoning a stalled stream.
    @discardableResult
    public func sweepStalled(now: Int64) -> [Int] {
        lock.lock(); defer { lock.unlock() }
        var released: [Int] = []
        for (lease, entry) in leases where now - entry.lastActivity > stallTimeoutMillis {
            released.append(lease)
        }
        for lease in released {
            guard let entry = leases.removeValue(forKey: lease) else { continue }
            globalCount = max(0, globalCount - 1)
            if let n = perPeer[entry.peer], n > 1 { perPeer[entry.peer] = n - 1 } else { perPeer.removeValue(forKey: entry.peer) }
        }
        return released.sorted()
    }

    public func inFlight(peer: String) -> Int {
        lock.lock(); defer { lock.unlock() }
        return perPeer[peer.lowercased()] ?? 0
    }

    public var totalInFlight: Int {
        lock.lock(); defer { lock.unlock() }
        return globalCount
    }
}

/// The share list + serve rules: what is shared, lifetimes/expiry, stop and
/// cancel, the concurrency cap and the digest LRU.
///
/// Ported from the Kotlin `ShareServer` with the HTTP writer and the actual
/// socket I/O left to the adapter: every method returns a decision (with the
/// status code the adapter must write) rather than bytes. The clock is injected
/// so expiry is deterministic under test.
public final class ShareList {
    public static let hashCacheMax = 256
    public static let maxManifestEntriesDefault = 50_000
    public static let maxManifestDepthDefault = 32
    public static let maxManifestMillisDefault: Int64 = 20_000
    public static let graceMaxMillis: Int64 = 10 * 60 * 1000
    public static let activeWindowMillis: Int64 = 60 * 1000
    public static let goneKeepMillis: Int64 = 60 * 60 * 1000

    public let concurrency: ConcurrencyGate
    public let digests: DigestCache

    private let source: ShareSource
    private let clock: () -> Int64
    private let maxManifestEntries: Int
    private let maxManifestDepth: Int
    private let maxManifestMillis: Int64
    private var cancelled = Set<String>()
    private var consumed: [String: (at: Int64, by: String)] = [:]

    package init(
        source: ShareSource,
        maxManifestEntries: Int = ShareList.maxManifestEntriesDefault,
        maxManifestDepth: Int = ShareList.maxManifestDepthDefault,
        maxManifestMillis: Int64 = ShareList.maxManifestMillisDefault,
        maxConcurrent: Int = ConcurrencyGate.defaultMaxConcurrent,
        stallTimeoutMillis: Int64 = ConcurrencyGate.defaultStallTimeoutMillis,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.source = source
        self.clock = clock
        self.maxManifestEntries = maxManifestEntries
        self.maxManifestDepth = maxManifestDepth
        self.maxManifestMillis = maxManifestMillis
        self.concurrency = ConcurrencyGate(maxConcurrent: maxConcurrent, stallTimeoutMillis: stallTimeoutMillis)
        self.digests = DigestCache(capacity: ShareList.hashCacheMax)
    }

    // MARK: - List / stop

    /// The shares a peer may currently see. An ended or expired share is dropped.
    public func list() -> [ShareInfo] {
        source.list().filter { gate($0.id) == .ok }
    }

    /// Cancels any in-flight stream for `shareId`; later requests get 410.
    public func cancel(_ shareId: String) {
        cancelled.insert(shareId)
    }

    /// Cancels every known share ("stop all").
    public func stopAll() {
        for share in source.list() { cancelled.insert(share.id) }
    }

    public func isCancelled(_ shareId: String) -> Bool { cancelled.contains(shareId) }

    /// The reason a share ended ("expired", "completed", "stopped"), else nil.
    public func ended(_ shareId: String) -> String? {
        if consumed[shareId] != nil { return "completed" }
        if cancelled.contains(shareId) { return "stopped" }
        if let reason = source.ended(shareId: shareId) { return reason }
        if let info = source.list().first(where: { $0.id == shareId }),
           let expiresAt = info.expiresAt, clock() > expiresAt {
            return "expired"
        }
        return nil
    }

    public func gate(_ shareId: String) -> ShareGate {
        if let reason = ended(shareId) { return .gone(Self.endedMessage(reason)) }
        if !source.list().contains(where: { $0.id == shareId }) { return .notFound }
        return .ok
    }

    /// Consumes a one-time share after a verified download; true only for a
    /// one-time share that was still live. Mirrors Go's `Manager.Consume`.
    @discardableResult
    public func consume(_ shareId: String, by peer: String) -> Bool {
        guard let info = source.list().first(where: { $0.id == shareId }),
              info.lifetime == .oneTime,
              gate(shareId) == .ok else { return false }
        consumed[shareId] = (clock(), peer.lowercased())
        return true
    }

    /// The human message for an ended reason, matching `shareEndedMsg`.
    public static func endedMessage(_ reason: String) -> String {
        switch reason {
        case "expired": return "This share has expired."
        case "completed": return "This one-time share has already been downloaded."
        default: return "The sender stopped this share."
        }
    }

    // MARK: - Manifest / tree

    public func manifest(_ shareId: String, path: String) -> ManifestResult {
        switch gate(shareId) {
        case .gone(let message): return .gone(message)
        case .notFound: return .notFound
        case .ok: break
        }
        guard SafePath.validRel(path) else { return .badPath }

        let started = clock()
        var files: [ManifestEntry] = []
        var total: Int64 = 0
        var queue: [(path: String, depth: Int)] = [(path, 0)]
        var head = 0

        while head < queue.count {
            let (dir, depth) = queue[head]; head += 1
            if depth > maxManifestDepth { return .tooMany }
            guard let kids = source.children(shareId: shareId, rel: dir) else { return .notFound }
            for child in kids {
                guard SafePath.validSegment(child.name) else { continue }
                if child.isDir {
                    queue.append((child.path, depth + 1))
                } else {
                    if files.count >= maxManifestEntries || clock() - started > maxManifestMillis {
                        return .tooMany
                    }
                    files.append(ManifestEntry(
                        path: child.path,
                        name: child.name,
                        size: child.size,
                        mtimeMillis: child.mtimeMillis,
                        etag: Self.validator(size: child.size, mtimeMillis: child.mtimeMillis)
                    ))
                    total += child.size
                }
            }
        }
        return .ok(files: files, totalBytes: total, lifetime: lifetime(of: shareId))
    }

    public func tree(_ shareId: String, path: String) -> TreeResult {
        switch gate(shareId) {
        case .gone(let message): return .gone(message)
        case .notFound: return .notFound
        case .ok: break
        }
        guard SafePath.validRel(path) else { return .badPath }
        guard let kids = source.children(shareId: shareId, rel: path) else { return .notFound }
        return .ok(kids.filter { SafePath.validSegment($0.name) })
    }

    // MARK: - Hash

    /// The whole-file SHA-256 of `rel`, cached by name+etag so an unchanged file
    /// is read at most once. Ported from `ShareServer.hashResponse`.
    public func hash(_ shareId: String, path: String) -> HashResult {
        switch gate(shareId) {
        case .gone(let message): return .gone(message)
        case .notFound: return .notFound
        case .ok: break
        }
        guard SafePath.validRel(path), !path.isEmpty else { return .badPath }
        guard let file = source.resolve(shareId: shareId, rel: path) else { return .notFound }
        let etag = Self.validator(size: file.size, mtimeMillis: file.mtimeMillis)
        let key = Self.hashKey(name: file.name, etag: etag)
        let sum: String
        if let cached = digests.get(key) {
            sum = cached
        } else {
            sum = Self.sha256Hex(file.openAt(0))
            digests.put(key, sum)
        }
        return .ok(sha256: sum, size: file.size, etag: etag)
    }

    // MARK: - Ranges / validators

    public static func validator(size: Int64, mtimeMillis: Int64) -> String {
        String(format: "%x-%x", size, mtimeMillis)
    }

    public static func hashKey(name: String, etag: String) -> String { "\(name)|\(etag)" }

    /// Parses a single `bytes=START-END`; null/absent means the full body and a
    /// malformed/unsatisfiable range is a `416`. Ported from `parseRange`.
    public static func parseRange(_ header: String?, size: Int64) -> RangeDecision {
        guard let header, !header.trimmingCharacters(in: .whitespaces).isEmpty else {
            return .full(start: 0, length: size)
        }
        let h = header.trimmingCharacters(in: .whitespaces)
        guard h.hasPrefix("bytes=") else { return .unsatisfiable }
        let spec = String(h.dropFirst("bytes=".count))
        if spec.contains(",") { return .unsatisfiable }
        guard let dash = spec.firstIndex(of: "-") else { return .unsatisfiable }
        let loText = spec[spec.startIndex..<dash].trimmingCharacters(in: .whitespaces)
        let hiText = spec[spec.index(after: dash)...].trimmingCharacters(in: .whitespaces)
        guard let lo = Int64(loText), lo >= 0, lo < size else { return .unsatisfiable }
        let hi: Int64
        if hiText.isEmpty {
            hi = size - 1
        } else {
            guard let parsed = Int64(hiText), parsed >= lo else { return .unsatisfiable }
            hi = parsed
        }
        let end = min(hi, size - 1)
        return .partial(start: lo, length: end - lo + 1)
    }

    /// Applies If-Range: a range is only honoured when `ifRange` matches `etag`
    /// (`"`/space-insensitive), otherwise the whole body is served. Ported from
    /// the `fileResponse` If-Range rule.
    public static func resolveRange(header: String?, ifRange: String?, size: Int64, etag: String) -> RangeDecision {
        let effective = ifRange.map { quoteStripped($0) } == etag ? header : nil
        return parseRange(effective, size: size)
    }

    private static func quoteStripped(_ s: String) -> String {
        s.trimmingCharacters(in: CharacterSet(charactersIn: "\" "))
    }

    // MARK: - Internals

    private func lifetime(of shareId: String) -> ShareLifetimeType {
        source.list().first { $0.id == shareId }?.lifetime ?? .untilStopped
    }

    /// SHA-256 of a whole `ByteSource`, lowercase hex.
    private static func sha256Hex(_ source: ByteSource) -> String {
        var hasher = SHA256()
        var buffer = [UInt8](repeating: 0, count: 256 * 1024)
        while true {
            let n = source.read(&buffer, offset: 0, count: buffer.count)
            if n < 0 { break }
            if n == 0 { continue }
            hasher.update(data: Data(buffer[0..<n]))
        }
        return Hex.encode(hasher.finalize())
    }
}

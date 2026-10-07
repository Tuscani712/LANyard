import Foundation
import Crypto

/// One file in an accepted push.
final class PushFileState {
    let relPath: String
    let size: Int64
    let mtimeMillis: Int64
    var done: Int64
    var placedName: String?
    /// The spool part this file streams into. Set when the offer is accepted.
    var part: URL!

    init(relPath: String, size: Int64, mtimeMillis: Int64, done: Int64 = 0, placedName: String? = nil) {
        self.relPath = relPath
        self.size = size
        self.mtimeMillis = mtimeMillis
        self.done = done
        self.placedName = placedName
    }
}

/// One push being received, for the UI.
struct IncomingPush: Equatable {
    let id: String
    let peerFp: String
    let peerName: String
    let files: Int
    let total: Int64
    let done: Int64
}

/// The phone-side Inbox: validates and spools a push, verifies each file's
/// SHA-256, hands it to the `PushDestination`, and deletes the spool file
/// immediately. The spool lives under the app's private files directory (not
/// cache, which Android may clear under storage pressure), so an interrupted
/// receive can resume.
///
/// All file writes stream through a fixed buffer; no file is ever held in memory.
///
/// Ported from the Kotlin `InboxReceiver`. Java `File` becomes Foundation `URL`;
/// Java `InputStream` becomes the `ByteSource` protocol (or a `Data` convenience
/// overload). `@Synchronized` becomes an `NSRecursiveLock` to keep Java's
/// reentrant monitor semantics.
final class InboxReceiver {
    final class Session {
        let id: String
        let peerFp: String
        let peerName: String
        let files: [String: PushFileState]
        var total: Int64
        let createdAt: Int64

        init(id: String, peerFp: String, peerName: String, files: [String: PushFileState], total: Int64, createdAt: Int64) {
            self.id = id
            self.peerFp = peerFp
            self.peerName = peerName
            self.files = files
            self.total = total
            self.createdAt = createdAt
        }
    }

    private let spoolRoot: URL
    private let destination: PushDestination
    private let freeBytes: () -> Int64
    private let onChange: () -> Void
    private let onOffer: (_ pushId: String, _ peerFp: String, _ files: Int, _ total: Int64) -> Void
    private let onProgress: (_ pushId: String, _ done: Int64, _ total: Int64) -> Void
    private let onDone: (_ pushId: String, _ peerFp: String, _ files: Int, _ total: Int64) -> Void
    private let onCancelled: (_ pushId: String, _ reason: String) -> Void
    private let clock: () -> Int64
    private let destinationReady: () -> Bool

    private let lock = NSRecursiveLock()
    private var sessions: [Session] = []
    private var cancelled = Set<String>()
    private let placeLock = NSLock()

    private static let bufferSize = 256 * 1024

    init(
        spoolRoot: URL,
        destination: PushDestination,
        freeBytes: @escaping () -> Int64,
        onChange: @escaping () -> Void = {},
        onOffer: @escaping (_ pushId: String, _ peerFp: String, _ files: Int, _ total: Int64) -> Void = { _, _, _, _ in },
        onProgress: @escaping (_ pushId: String, _ done: Int64, _ total: Int64) -> Void = { _, _, _ in },
        onDone: @escaping (_ pushId: String, _ peerFp: String, _ files: Int, _ total: Int64) -> Void = { _, _, _, _ in },
        onCancelled: @escaping (_ pushId: String, _ reason: String) -> Void = { _, _ in },
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        destinationReady: @escaping () -> Bool = { true }
    ) {
        self.spoolRoot = spoolRoot
        self.destination = destination
        self.freeBytes = freeBytes
        self.onChange = onChange
        self.onOffer = onOffer
        self.onProgress = onProgress
        self.onDone = onDone
        self.onCancelled = onCancelled
        self.clock = clock
        self.destinationReady = destinationReady
    }

    @discardableResult
    func offer(
        peerFp: String,
        peerName: String,
        reqs: [PushFileRequest],
        totalBytes: Int64,
        maxBytes: Int64
    ) throws -> PushOffer {
        lock.lock()
        defer { lock.unlock() }

        if reqs.isEmpty { throw PeerHttpException(400, "no files") }
        if !destinationReady() {
            throw PeerHttpException(503, "This device cannot save received files. Choose a writable download folder in Settings.")
        }
        if reqs.count > PushProtocol.MAX_OFFER_FILES {
            throw PeerHttpException(413, "too many files in one push")
        }
        var total: Int64 = 0
        var largest: Int64 = 0
        var files = [String: PushFileState]()
        for r in reqs {
            if r.size < 0 { throw PeerHttpException(400, "negative size") }
            guard let rel = PushProtocol.sanitizeRel(r.relPath) else {
                throw PeerHttpException(400, "bad file name")
            }
            if files[rel] != nil { throw PeerHttpException(400, "duplicate file") }
            files[rel] = PushFileState(relPath: rel, size: r.size, mtimeMillis: r.mtimeMillis)
            total += r.size
            if r.size > largest { largest = r.size }
        }
        let claimed = totalBytes > 0 ? totalBytes : total
        if claimed > total { total = claimed }
        if maxBytes > 0 && total > maxBytes {
            throw PeerHttpException(413, "push exceeds the size this device allows")
        }
        let need = PushProtocol.requiredFreeSpace(total: total, largestFile: largest)
        let free = freeBytes()
        if free >= 1 && free < need { throw PeerHttpException(507, "insufficient storage") }

        // One in-flight offer per peer and relative path: two peers (or two
        // pushes) sharing a spool file would corrupt each other's data.
        for existing in sessions {
            if existing.peerFp.caseInsensitiveCompare(peerFp) == .orderedSame &&
                existing.files.keys.contains(where: { files[$0] != nil }) {
                throw PeerHttpException(409, "a push with that name is already in progress")
            }
        }

        let id = "p_" + randHex(6)
        // Parts are keyed by peer, then relative path, so different peers using
        // the same name never share a spool file, and a retry resumes.
        let peerDir = spoolRoot.appendingPathComponent(String(peerFp.prefix(16)).lowercased())
        createDirectory(peerDir)
        for (rel, st) in files {
            let part = peerDir.appendingPathComponent(rel + ".lanpart")
            st.part = part
            createDirectory(part.deletingLastPathComponent())
            if isFile(part) { st.done = min(fileSize(part), st.size) }
        }
        let sess = Session(id: id, peerFp: peerFp, peerName: Display.safeName(peerName), files: files, total: total, createdAt: clock())
        sessions.append(sess)
        onChange()
        onOffer(id, peerFp, files.count, total)
        var offsets = [String: Int64]()
        for (rel, st) in files { offsets[rel] = st.done }
        return PushOffer(pushId: id, accepted: true, maxBytes: maxBytes > 0 ? maxBytes : 0, offsets: offsets)
    }

    private func session(_ id: String, _ peerFp: String) throws -> Session {
        lock.lock()
        defer { lock.unlock() }
        if cancelled.contains(id) { throw PeerHttpException(410, "cancelled by the receiver") }
        guard let s = sessions.first(where: { $0.id == id }) else {
            throw PeerHttpException(404, "no such push")
        }
        if s.peerFp.caseInsensitiveCompare(peerFp) != .orderedSame {
            throw PeerHttpException(403, "not your push")
        }
        return s
    }

    /// Appends bytes at `offset` to a file's spool part. Streams; returns bytes written.
    @discardableResult
    func writeChunk(id: String, peerFp: String, rel: String, offset: Int64, source: ByteSource) throws -> Int64 {
        let s = try session(id, peerFp)
        guard let st = s.files[rel] else { throw PeerHttpException(404, "no such file in push") }
        return try writeStream(s, st, offset, source, nil)
    }

    /// Convenience overload for callers that already hold the bytes.
    @discardableResult
    func writeChunk(id: String, peerFp: String, rel: String, offset: Int64, data: Data) throws -> Int64 {
        try writeChunk(id: id, peerFp: peerFp, rel: rel, offset: offset, source: DataByteSource(data))
    }

    /// Whole-file fast path: one request carries the bytes and the digest.
    @discardableResult
    func receiveWhole(id: String, peerFp: String, rel: String, sha256: String, source: ByteSource) throws -> Int64 {
        let s = try session(id, peerFp)
        guard let st = s.files[rel] else { throw PeerHttpException(404, "no such file in push") }
        if st.done != 0 { throw PeerHttpException(409, "file already partly received") }
        try writeStream(s, st, 0, source, sha256)
        st.placedName = try place(st)
        lock.lock()
        st.done = st.size
        lock.unlock()
        onChange()
        return st.size
    }

    /// Convenience overload for callers that already hold the bytes.
    @discardableResult
    func receiveWhole(id: String, peerFp: String, rel: String, sha256: String, data: Data) throws -> Int64 {
        try receiveWhole(id: id, peerFp: peerFp, rel: rel, sha256: sha256, source: DataByteSource(data))
    }

    /// Verifies a streamed part against `sha256` and places it.
    @discardableResult
    func complete(id: String, peerFp: String, rel: String, sha256: String) throws -> PushFileState {
        let s = try session(id, peerFp)
        guard let st = s.files[rel] else { throw PeerHttpException(404, "no such file in push") }
        let have = fileSize(st.part)
        if have != st.size {
            throw PeerHttpException(409, "size mismatch: have \(have), expected \(st.size)")
        }
        let got = hashFile(st.part)
        if got.caseInsensitiveCompare(sha256) != .orderedSame {
            throw PeerHttpException(409, "checksum mismatch")
        }
        st.placedName = try place(st)
        lock.lock()
        st.done = st.size
        lock.unlock()
        onChange()
        return st
    }

    /// Finishes a push (the job-level "all done").
    @discardableResult
    func finish(id: String, peerFp: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let s = sessions.first(where: { $0.id == id }) else { return false }
        if s.peerFp.caseInsensitiveCompare(peerFp) != .orderedSame { return false }
        sessions.removeAll { $0.id == id }
        let peerDir = spoolRoot.appendingPathComponent(String(s.peerFp.prefix(16)).lowercased())
        for rel in s.files.keys {
            removeFile(peerDir.appendingPathComponent(rel + ".lanpart"))
        }
        onChange()
        onDone(id, peerFp, s.files.count, s.total)
        return true
    }

    @discardableResult
    func cancel(id: String, peerFp: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let s = sessions.first(where: { $0.id == id }) else { return false }
        if s.peerFp.caseInsensitiveCompare(peerFp) != .orderedSame { return false }
        cancelled.insert(id)
        sessions.removeAll { $0.id == id }
        let peerDir = spoolRoot.appendingPathComponent(String(s.peerFp.prefix(16)).lowercased())
        for rel in s.files.keys {
            removeFile(peerDir.appendingPathComponent(rel + ".lanpart"))
        }
        onChange()
        onCancelled(id, "The transfer was cancelled")
        return true
    }

    func wasCancelled(_ id: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled.contains(id)
    }

    func incoming() -> [IncomingPush] {
        lock.lock()
        defer { lock.unlock() }
        return sessions.map { s in
            IncomingPush(
                id: s.id,
                peerFp: s.peerFp,
                peerName: s.peerName,
                files: s.files.count,
                total: s.total,
                done: s.files.values.reduce(0) { $0 + $1.done }
            )
        }
    }

    func count() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return sessions.count
    }

    /// True when a partial spool file exists (an interrupted receive to resume).
    func hasPartialSpool() -> Bool {
        guard isDirectory(spoolRoot) else { return false }
        return walkFiles(spoolRoot).contains { $0.lastPathComponent.hasSuffix(".lanpart") }
    }

    /// Deletes spool parts that belong to no in-memory push (leftovers from a
    /// previous run). A push still in progress in this process is kept, so
    /// acknowledging an interruption never corrupts a live transfer. Returns how
    /// many were removed.
    @discardableResult
    func clearAbandonedSpool() -> Int {
        lock.lock()
        defer { lock.unlock() }
        if !isDirectory(spoolRoot) { return 0 }
        var active = Set<String>()
        for s in sessions {
            for f in s.files.values { active.insert(f.part.path) }
        }
        let abandoned = walkFiles(spoolRoot).filter {
            $0.lastPathComponent.hasSuffix(".lanpart") && !active.contains($0.path)
        }
        for url in abandoned { removeFile(url) }
        return abandoned.count
    }

    /// Deletes spool files older than the TTL (an abandoned push).
    func sweepStale(ttlMillis: Int64 = 24 * 60 * 60 * 1000) {
        if !isDirectory(spoolRoot) { return }
        let cutoff = clock() - ttlMillis
        for url in walkFiles(spoolRoot) where url.lastPathComponent.hasSuffix(".lanpart") {
            if modifiedMillis(url) < cutoff { removeFile(url) }
        }
    }

    /// Streams `source` into the spool part, hashing while it goes.
    @discardableResult
    private func writeStream(
        _ s: Session,
        _ st: PushFileState,
        _ offset: Int64,
        _ source: ByteSource,
        _ wantSha: String?
    ) throws -> Int64 {
        if offset < 0 || offset > st.size { throw PeerHttpException(400, "bad offset") }
        let part = st.part!
        createDirectory(part.deletingLastPathComponent())
        var digest = SHA256()
        if offset > 0 { hashPrefix(part, offset, &digest) }
        if !isFile(part) { FileManager.default.createFile(atPath: part.path, contents: nil) }
        let out = try FileHandle(forWritingTo: part)
        if offset > 0 { try out.seekToEnd() }
        let limit = st.size - offset
        var written: Int64 = 0
        var overrun = false
        do {
            var buf = [UInt8](repeating: 0, count: InboxReceiver.bufferSize)
            // Cap while reading: a client (or a chunked stream) must never write
            // more than the offered size, or it could fill the disk first and be
            // rejected only afterwards.
            while written < limit {
                if wasCancelled(s.id) { throw PushCancelledException() }
                let want = Int(min(Int64(InboxReceiver.bufferSize), limit - written))
                let n = source.read(&buf, offset: 0, count: want)
                if n < 0 { break }
                try out.write(contentsOf: Data(buf[0..<n]))
                digest.update(data: buf[0..<n])
                written += Int64(n)
                lock.lock()
                st.done = offset + written
                lock.unlock()
                onProgress(s.id, offset + written, st.size)
            }
            if written >= limit {
                var one = [UInt8](repeating: 0, count: 1)
                if source.read(&one, offset: 0, count: 1) >= 0 {
                    // One byte past the offered size: truncate the part and refuse.
                    overrun = true
                }
            }
            if !overrun { try out.synchronize() }
        } catch {
            try? out.close()
            throw error
        }
        try? out.close()
        if overrun {
            removeFile(part)
            lock.lock()
            st.done = 0
            lock.unlock()
            throw PeerHttpException(400, "file longer than offered size")
        }
        if offset == 0 && st.size > 0 && fileSize(part) != st.size && wantSha != nil {
            throw PeerHttpException(409, "size mismatch")
        }
        if let wantSha {
            let got = Hex.encode(digest.finalize())
            if got.caseInsensitiveCompare(wantSha) != .orderedSame {
                throw PeerHttpException(409, "checksum mismatch")
            }
        }
        return fileSize(part)
    }

    private func hashPrefix(_ file: URL, _ offset: Int64, _ digest: inout SHA256) {
        guard isFile(file) else { return }
        guard let fh = try? FileHandle(forReadingFrom: file) else { return }
        defer { try? fh.close() }
        var left = min(offset, fileSize(file))
        while left > 0 {
            let want = Int(min(left, Int64(InboxReceiver.bufferSize)))
            guard let chunk = try? fh.read(upToCount: want), !chunk.isEmpty else { break }
            digest.update(data: chunk)
            left -= Int64(chunk.count)
        }
    }

    /// Places one verified file, one at a time, then deletes the spool part.
    private func place(_ st: PushFileState) throws -> String {
        placeLock.lock()
        defer { placeLock.unlock() }
        let name = try destination.place(relPath: st.relPath, spool: st.part, size: st.size)
        removeFile(st.part)
        return name
    }

    private func hashFile(_ file: URL) -> String {
        var digest = SHA256()
        guard let fh = try? FileHandle(forReadingFrom: file) else {
            return Hex.encode(digest.finalize())
        }
        defer { try? fh.close() }
        while let chunk = try? fh.read(upToCount: InboxReceiver.bufferSize), !chunk.isEmpty {
            digest.update(data: chunk)
        }
        return Hex.encode(digest.finalize())
    }

    private func randHex(_ bytes: Int) -> String {
        var gen = SystemRandomNumberGenerator()
        let b = (0..<bytes).map { _ in UInt8.random(in: 0...255, using: &gen) }
        return Hex.encode(b)
    }

    // MARK: - File helpers

    private func createDirectory(_ url: URL) {
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }

    private func isFile(_ url: URL) -> Bool {
        var isDir: ObjCBool = false
        let exists = FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir)
        return exists && !isDir.boolValue
    }

    private func isDirectory(_ url: URL) -> Bool {
        var isDir: ObjCBool = false
        let exists = FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir)
        return exists && isDir.boolValue
    }

    private func fileSize(_ url: URL) -> Int64 {
        (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
    }

    private func modifiedMillis(_ url: URL) -> Int64 {
        guard let date = try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate else {
            return 0
        }
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    private func removeFile(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
    }

    /// Every regular file under `root`, recursively (Kotlin `walkTopDown`).
    private func walkFiles(_ root: URL) -> [URL] {
        guard let enumerator = FileManager.default.enumerator(
            at: root,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [],
            errorHandler: { _, _ in true }
        ) else { return [] }
        var out: [URL] = []
        for case let url as URL in enumerator {
            if (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true {
                out.append(url)
            }
        }
        return out
    }
}

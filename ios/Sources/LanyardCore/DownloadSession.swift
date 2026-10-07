import Foundation
import Crypto

/// One file in a share's manifest, with a path relative to the share root.
/// Ported from the Kotlin `ManifestFile`.
struct ManifestFile: Equatable {
    let path: String
    let name: String
    let size: Int64
    let etag: String
}

/// A write destination for one downloaded file, the Linux-friendly stand-in for
/// Java's `OutputStream`: `write` appends `count` bytes from `bytes[offset...]`,
/// `close` flushes. A reference type so the stream's state is shared.
package protocol ByteSink: AnyObject {
    func write(_ bytes: [UInt8], offset: Int, count: Int) throws
    func close() throws
}

/// The destination for one downloaded file. `existingSize` is how many bytes are
/// already present (for resume); `openAt` returns a sink positioned to receive
/// bytes starting at `offset`, and `openExisting` (optional) re-reads the present
/// bytes so a resumed file's digest still covers the whole file.
///
/// Ported from the Kotlin `DownloadTarget`.
struct DownloadTarget {
    let existingSize: Int64
    let openAt: (Int64) -> ByteSink
    let openExisting: (() -> ByteSource)?

    init(
        existingSize: Int64 = 0,
        openAt: @escaping (Int64) -> ByteSink,
        openExisting: (() -> ByteSource)? = nil
    ) {
        self.existingSize = existingSize
        self.openAt = openAt
        self.openExisting = openExisting
    }
}

/// The outcome of a download. Ported from the Kotlin `DownloadResult`.
enum DownloadResult: Equatable {
    case done(files: Int, bytes: Int64)

    /// We cancelled it locally.
    case cancelled

    /// HTTP 410: the sender stopped or expired the share.
    case shareEnded

    /// The peer could not be reached or answered unexpectedly.
    case peerUnreachable

    /// A downloaded file's SHA-256 did not match the peer's.
    case hashMismatch(String)

    /// A manifest path was absolute, traversing, or otherwise unsafe.
    case unsafePath

    case failed(String)
}

/// The share operations a download needs. The app implements it over
/// `PeerClient`; tests supply fakes so the resume/hash/path rules run on Linux.
///
/// Ported from the Kotlin `ShareReader`. `JsonObject` becomes raw `Data` so
/// `DownloadSession` owns the manifest parsing (exercised by the tests).
package protocol ShareReader: AnyObject {
    func manifestFiles(shareId: String, path: String) throws -> Data
    func openFileStream(shareId: String, path: String, rangeFrom: Int64) throws -> ByteSource
    func wholeFileHash(shareId: String, path: String) throws -> String
    func reportComplete(shareId: String, verified: [(path: String, sha256: String)]) throws -> Bool
}

/// Downloads a share (or a subpath of one) into caller-provided `DownloadTarget`s:
/// fetch the manifest, then stream each file, verifying its SHA-256 against the
/// peer's whole-file digest. Nothing is ever written outside the caller's folder
/// because every manifest path is validated first. Blocking; call off the main
/// thread.
///
/// Ported from the Kotlin `DownloadSession`.
final class DownloadSession {
    /// Stream buffer size, matching Kotlin's `256 * 1024`.
    static let bufferSize = 256 * 1024

    private let reader: ShareReader
    private let throttle: Throttle

    init(reader: ShareReader, throttle: Throttle = NoThrottle.shared) {
        self.reader = reader
        self.throttle = throttle
    }

    func download(
        shareId: String,
        path: String,
        targetFor: (ManifestFile) -> DownloadTarget,
        onProgress: (_ index: Int, _ file: ManifestFile, _ received: Int64, _ total: Int64) -> Void = { _, _, _, _ in },
        isCancelled: () -> Bool = { false }
    ) -> DownloadResult {
        let manifest: Data
        do {
            manifest = try reader.manifestFiles(shareId: shareId, path: path)
        } catch let e as PeerHttpException {
            return e.code == 410 ? .shareEnded : .peerUnreachable
        } catch {
            return .peerUnreachable
        }

        let files = parseManifest(manifest)
        for file in files {
            if !Self.isSafeRelPath(file.path) { return .unsafePath }
        }

        var totalBytes: Int64 = 0
        var verified: [(path: String, sha256: String)] = []

        for (index, file) in files.enumerated() {
            let target = targetFor(file)
            var offset = min(max(target.existingSize, 0), file.size)
            var digest = SHA256()

            do {
                if offset > 0 {
                    if let openExisting = target.openExisting {
                        try hashInto(&digest, source: openExisting())
                    } else {
                        offset = 0 // cannot hash the prefix; start over
                    }
                }

                guard let received = try transfer(
                    reader: reader,
                    shareId: shareId,
                    file: file,
                    offset: offset,
                    target: target,
                    digest: &digest,
                    index: index,
                    onProgress: onProgress,
                    isCancelled: isCancelled
                ) else {
                    return .cancelled
                }
                totalBytes += received

                let got = Hex.encode(digest.finalize())
                let want: String
                do {
                    want = try reader.wholeFileHash(shareId: shareId, path: file.path)
                } catch let e as PeerHttpException {
                    return e.code == 410 ? .shareEnded : .peerUnreachable
                } catch {
                    return .peerUnreachable
                }
                if got.caseInsensitiveCompare(want) != .orderedSame {
                    return .hashMismatch(file.path)
                }
                verified.append((path: file.path, sha256: got))
            } catch let e as PeerHttpException {
                return e.code == 410 ? .shareEnded : .peerUnreachable
            } catch {
                return .peerUnreachable
            }
        }

        do {
            _ = try reader.reportComplete(shareId: shareId, verified: verified)
        } catch {
            // A failed "complete" only affects one-time shares; the bytes are already down.
        }
        return .done(files: files.count, bytes: totalBytes)
    }

    /// Streams one file; returns bytes written, or nil if cancelled.
    private func transfer(
        reader: ShareReader,
        shareId: String,
        file: ManifestFile,
        offset: Int64,
        target: DownloadTarget,
        digest: inout SHA256,
        index: Int,
        onProgress: (Int, ManifestFile, Int64, Int64) -> Void,
        isCancelled: () -> Bool
    ) throws -> Int64? {
        let input = try reader.openFileStream(shareId: shareId, path: file.path, rangeFrom: offset)
        let output = target.openAt(offset)
        defer { try? output.close() }

        var received: Int64 = 0
        var buf = [UInt8](repeating: 0, count: Self.bufferSize)
        while true {
            if isCancelled() { return nil }
            let n = input.read(&buf, offset: 0, count: Self.bufferSize)
            if n < 0 { break }
            if n == 0 { continue }
            try output.write(buf, offset: 0, count: n)
            digest.update(data: Data(buf[0..<n]))
            received += Int64(n)
            onProgress(index, file, offset + received, file.size)
            throttle.pace(n)
        }
        return received
    }

    private func hashInto(_ digest: inout SHA256, source: ByteSource) throws {
        var buf = [UInt8](repeating: 0, count: Self.bufferSize)
        while true {
            let n = source.read(&buf, offset: 0, count: Self.bufferSize)
            if n < 0 { break }
            if n == 0 { continue }
            digest.update(data: Data(buf[0..<n]))
        }
    }

    private func parseManifest(_ data: Data) -> [ManifestFile] {
        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let arr = obj["files"] as? [Any] else {
            return []
        }
        var out: [ManifestFile] = []
        for element in arr {
            guard let f = element as? [String: Any] else { continue }
            out.append(
                ManifestFile(
                    path: Self.str(f, "path"),
                    name: Self.str(f, "name"),
                    size: Self.long(f, "size"),
                    etag: Self.str(f, "etag")
                )
            )
        }
        return out
    }

    /// Kotlin `JsonObject.str(key)`: a missing or JSON-null value is `""`.
    private static func str(_ obj: [String: Any], _ key: String) -> String {
        guard let v = obj[key], !(v is NSNull) else { return "" }
        return v as? String ?? ""
    }

    /// Kotlin `get("size")?.asLong ?: 0L`.
    private static func long(_ obj: [String: Any], _ key: String) -> Int64 {
        guard let v = obj[key], !(v is NSNull) else { return 0 }
        if let n = v as? NSNumber { return n.int64Value }
        if let i = v as? Int64 { return i }
        if let i = v as? Int { return Int64(i) }
        return 0
    }

    /// A manifest path is safe when it is relative, uses forward slashes, has
    /// no `..`, `.`, empty or drive/ADS (`:`) segments, and no backslashes.
    /// The empty string is allowed: it is a single-file share's own entry.
    ///
    /// Delegates to the package's existing `PushProtocol.isSafeRelPath`, which
    /// encodes the identical rule (and additionally rejects a NUL byte).
    static func isSafeRelPath(_ path: String) -> Bool {
        PushProtocol.isSafeRelPath(path)
    }
}

import Foundation

/// Decodes HTTP/1.1 chunked transfer coding, mirroring the Kotlin
/// `ChunkedInputStream` in `PeerServer.kt` byte for byte. Bounded so a hostile
/// peer cannot grow memory: the chunk-size line is capped at 256, a trailer line
/// at 1024, the trailer block at 32 lines / 8 KiB, malformed input throws
/// `PeerHttpException(400, …)`, and the CRLF after each chunk's data is checked.
///
/// Reads pull from a `ByteSource` — the Linux-friendly stand-in for Java's
/// `InputStream` — so the whole rule set is unit-testable without a socket.
final class ChunkedDecoder {
    static let maxChunkLine = 256
    static let maxTrailerLine = 1024
    static let maxTrailerLines = 32
    static let maxTrailerBytes = 8 * 1024

    private let source: ByteSource
    private var remaining: Int64 = 0
    private var done = false
    private var trailerLines = 0
    private var trailerBytes = 0

    init(_ source: ByteSource) {
        self.source = source
    }

    /// True once the terminal zero-length chunk and its trailers are consumed.
    var isFinished: Bool { done }

    /// Reads one decoded byte, or `-1` at end of body. Throws on malformed input.
    func readByte() throws -> Int {
        if done { return -1 }
        if remaining == 0 { try nextChunk() }
        if done { return -1 }
        let b = try source.readByte()
        if b < 0 { throw PeerHttpException(400, "truncated chunked body") }
        remaining -= 1
        if remaining == 0 { try endChunkCrc() }
        return b
    }

    /// Reads up to `max` decoded bytes. Returns fewer than `max` when a chunk
    /// boundary is reached; empty only at end of body. Throws on malformed input.
    func read(max: Int) throws -> [UInt8] {
        precondition(max > 0)
        if done { return [] }
        if remaining == 0 { try nextChunk() }
        if done { return [] }
        let want = Int(min(Int64(max), remaining))
        var buffer = [UInt8](repeating: 0, count: want)
        let n = source.read(&buffer, offset: 0, count: want)
        if n < 0 { throw PeerHttpException(400, "truncated chunked body") }
        remaining -= Int64(n)
        if remaining == 0 { try endChunkCrc() }
        if n < buffer.count { buffer.removeLast(buffer.count - n) }
        return buffer
    }

    /// Drains the whole body. Intended for tests and small bodies; large bodies
    /// should stream with `read(max:)`.
    func readAll(limit: Int = .max) throws -> [UInt8] {
        var out = [UInt8]()
        while true {
            let part = try read(max: min(8192, limit - out.count))
            if part.isEmpty { break }
            out.append(contentsOf: part)
            if out.count > limit { throw PeerHttpException(400, "chunked body too large") }
        }
        return out
    }

    private func nextChunk() throws {
        let line = try readLine(max: Self.maxChunkLine)
        let sizeText = String(line.prefix { $0 != ";" }).trimmingCharacters(in: .whitespaces)
        guard !sizeText.isEmpty, let size = Int64(sizeText, radix: 16), size >= 0 else {
            throw PeerHttpException(400, "malformed chunk size")
        }
        if size == 0 {
            while true {
                let t = try readLine(max: Self.maxTrailerLine)
                trailerLines += 1
                trailerBytes += t.utf8.count + 2
                if trailerLines > Self.maxTrailerLines || trailerBytes > Self.maxTrailerBytes {
                    throw PeerHttpException(400, "trailer too large")
                }
                if t.isEmpty { break }
            }
            done = true
            return
        }
        remaining = size
    }

    private func endChunkCrc() throws {
        let cr = try source.readByte()
        let lf = try source.readByte()
        if cr != 13 || lf != 10 {
            throw PeerHttpException(400, "malformed chunk terminator")
        }
    }

    private func readLine(max: Int) throws -> String {
        var bytes = [UInt8]()
        while true {
            let b = try source.readByte()
            if b < 0 { throw PeerHttpException(400, "truncated chunked body") }
            let c = UInt8(b)
            if c == 0x0a { return String(decoding: bytes, as: UTF8.self) }
            if c != 0x0d { bytes.append(c) }
            if bytes.count > max { throw PeerHttpException(400, "chunk line too long") }
        }
    }
}

extension ByteSource {
    /// Reads one byte, or `-1` at end of stream.
    func readByte() throws -> Int {
        var one = [UInt8](repeating: 0, count: 1)
        let n = read(&one, offset: 0, count: 1)
        return n < 0 ? -1 : (n == 0 ? -1 : Int(one[0]))
    }
}

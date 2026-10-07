import Foundation

/// Places a verified spooled file into the user's destination (the SAF download
/// folder in the app). The implementation copies the file and returns the name it
/// was saved under; it throws on failure. Called one file at a time.
///
/// Ported from the Kotlin `fun interface PushDestination`; the `File` argument
/// becomes a Foundation `URL`.
package protocol PushDestination {
    func place(relPath: String, spool: URL, size: Int64) throws -> String
}

/// One file in a push offer.
package struct PushFileRequest: Equatable {
    package let relPath: String
    package let size: Int64
    package let mtimeMillis: Int64

    package init(relPath: String, size: Int64, mtimeMillis: Int64) {
        self.relPath = relPath
        self.size = size
        self.mtimeMillis = mtimeMillis
    }
}

/// The peer's answer to a push offer.
package struct PushOffer: Equatable {
    package let pushId: String
    package let accepted: Bool
    package let maxBytes: Int64
    package let offsets: [String: Int64]

    package init(pushId: String, accepted: Bool, maxBytes: Int64, offsets: [String: Int64]) {
        self.pushId = pushId
        self.accepted = accepted
        self.maxBytes = maxBytes
        self.offsets = offsets
    }
}

/// An HTTP-level failure a handler maps to a status code and a short message.
///
/// Ported from `PeerHttpException` in the Kotlin `PairingSessions.kt`.
package final class PeerHttpException: Error, CustomStringConvertible {
    package let code: Int
    package let message: String

    package init(_ code: Int, _ message: String) {
        self.code = code
        self.message = message
    }

    package var description: String { "HTTP \(code): \(message)" }
}

/// A local cancel aborted an in-flight push before completion.
///
/// Ported from `PushCancelledException` in the Kotlin `PeerClient.kt`.
package final class PushCancelledException: Error, CustomStringConvertible {
    package init() {}

    package var description: String { "the push was cancelled" }
}

/// A pull-based byte source, standing in for Java's `InputStream` on Linux.
///
/// `read(_:offset:count:)` fills up to `count` bytes starting at `offset` in
/// `buffer` and returns the number of bytes read, or `-1` at end of stream.
/// A reference type so callers share the stream's position, matching how the
/// Kotlin tests hand a stateful `InputStream` to `writeChunk`.
package protocol ByteSource: AnyObject {
    func read(_ buffer: inout [UInt8], offset: Int, count: Int) -> Int
}

/// A `ByteSource` backed by an in-memory `Data`/`[UInt8]`, for tests and for
/// whole-file fast paths that already hold the bytes.
package final class DataByteSource: ByteSource {
    private let data: [UInt8]
    private var position = 0

    package init(_ data: Data) {
        self.data = Array(data)
    }

    package init(_ bytes: [UInt8]) {
        self.data = bytes
    }

    package func read(_ buffer: inout [UInt8], offset: Int, count: Int) -> Int {
        guard position < data.count else { return -1 }
        let n = min(count, data.count - position)
        for i in 0..<n {
            buffer[offset + i] = data[position + i]
        }
        position += n
        return n
    }
}

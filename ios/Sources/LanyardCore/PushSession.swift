import Foundation

/// One file to push. `open` returns a fresh source each call; `size` and
/// `mtimeMillis` describe the file (a `ByteSource` cannot report either).
///
/// Ported from the Kotlin `PushSource`. The Java `InputStream` becomes a
/// pull-based `ByteSource` so the session runs on Linux.
package struct PushSource {
    package let relPath: String
    package let size: Int64
    package let mtimeMillis: Int64
    package let open: () -> ByteSource

    package init(relPath: String, size: Int64, mtimeMillis: Int64, open: @escaping () -> ByteSource) {
        self.relPath = relPath
        self.size = size
        self.mtimeMillis = mtimeMillis
        self.open = open
    }
}

/// The outcome of a push. Ported from the Kotlin `PushResult`.
package enum PushResult: Equatable {
    /// Everything was sent and verified by the receiver.
    case sent(files: Int, bytes: Int64)

    /// HTTP 403: the peer is not allowed to accept our push (or declined it).
    case refused

    /// HTTP 410: the receiver cancelled the transfer.
    case cancelledByReceiver

    /// We cancelled it locally.
    case cancelled

    case failed(String)
}

/// One streamed file's result: the digest the receiver will verify, plus the
/// bytes actually sent on this call (past any resume offset).
package struct PushFileResult: Equatable {
    package let sha256: String
    package let bytes: Int64

    package init(sha256: String, bytes: Int64) {
        self.sha256 = sha256
        self.bytes = bytes
    }
}

/// The network seam `PushSession` drives. The app implements it over
/// `PeerClient` (TLS/sockets, Apple-only); the tests supply a fake so the pure
/// state machine runs on Linux. Ported from the subset of the Kotlin
/// `PeerClient` that `PushSession` touches.
package protocol PushClient: AnyObject {
    func pushOffer(_ files: [PushFileRequest]) throws -> PushOffer

    func pushFileStream(
        pushId: String,
        relPath: String,
        offset: Int64,
        total: Int64,
        source: ByteSource,
        onBytes: (Int64) -> Void,
        isCancelled: () -> Bool,
        throttle: Throttle
    ) throws -> PushFileResult

    func pushCompleteFile(_ pushId: String, _ relPath: String, _ sha256: String) throws
    func pushCompleteAll(_ pushId: String) throws
}

/// Pushes files into a paired peer's Inbox through a `PushClient`: offer, stream
/// each file, then verify with its SHA-256. Files are streamed, never buffered
/// whole. `onProgress` reports per-file bytes as they go; `isCancelled` is polled
/// between buffers so a transfer can be stopped promptly.
///
/// Ported from the Kotlin `PushSession`; the `PeerClient` dependency is the
/// `PushClient` seam, `PushCancelledException` and `PeerHttpException` are the
/// package's existing types.
final class PushSession {
    private let client: PushClient
    private let throttle: Throttle

    init(client: PushClient, throttle: Throttle = NoThrottle.shared) {
        self.client = client
        self.throttle = throttle
    }

    func push(
        sources: [PushSource],
        onProgress: (_ fileIndex: Int, _ sent: Int64, _ fileTotal: Int64) -> Void = { _, _, _ in },
        isCancelled: () -> Bool = { false }
    ) -> PushResult {
        if sources.isEmpty { return .failed("nothing to send") }

        let offer: PushOffer
        do {
            offer = try client.pushOffer(
                sources.map { PushFileRequest(relPath: $0.relPath, size: $0.size, mtimeMillis: $0.mtimeMillis) }
            )
        } catch let e as PeerHttpException {
            return statusResult(e)
        } catch {
            return .failed(message(of: error, fallback: "the offer failed"))
        }
        if !offer.accepted { return .refused }

        var overall: Int64 = 0
        do {
            for (index, source) in sources.enumerated() {
                if isCancelled() { throw PushCancelledException() }
                let offset = offer.offsets[source.relPath] ?? 0
                let result = try client.pushFileStream(
                    pushId: offer.pushId,
                    relPath: source.relPath,
                    offset: offset,
                    total: source.size,
                    source: source.open(),
                    onBytes: { sent in onProgress(index, sent, source.size) },
                    isCancelled: isCancelled,
                    throttle: throttle
                )
                try client.pushCompleteFile(offer.pushId, source.relPath, result.sha256)
                overall += result.bytes
                onProgress(index, source.size, source.size)
            }
            try client.pushCompleteAll(offer.pushId)
            return .sent(files: sources.count, bytes: overall)
        } catch is PushCancelledException {
            return .cancelled
        } catch let e as PeerHttpException {
            return statusResult(e)
        } catch {
            return .failed(message(of: error, fallback: "the push failed"))
        }
    }

    private func statusResult(_ e: PeerHttpException) -> PushResult {
        switch e.code {
        case 403: return .refused
        case 410: return .cancelledByReceiver
        default: return .failed("HTTP \(e.code)")
        }
    }

    /// Mirrors Kotlin's `e.message ?: "<fallback>"`: a `LocalizedError` supplies
    /// its description, otherwise the error's textual form, else the fallback.
    private func message(of error: Error, fallback: String) -> String {
        if let e = error as? PeerHttpException { return e.message }
        if let e = error as? LocalizedError, let d = e.errorDescription, !d.isEmpty { return d }
        let text = String(describing: error)
        return text.isEmpty ? fallback : text
    }
}

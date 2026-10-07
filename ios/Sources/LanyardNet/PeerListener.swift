// PeerListener — the server side: an NWListener with mTLS 1.3 that parses
// HTTP/1.1 and drives `LanyardCore`'s server-side state machines through
// `PeerServerAdapter`.
#if canImport(Network) && canImport(Security)
import Foundation
import Network
import LanyardCore

/// One parsed inbound request.
struct PeerRequest {
    let method: String
    let path: String
    let query: String
    let headers: [String: String]
    /// The authenticated client certificate's Device ID.
    let peerFingerprint: String
    /// The whole body for small requests; empty for a streaming file body.
    let body: Data
    /// Non-nil for a file-upload body (chunked or Content-Length); the handler
    /// must consume it before returning.
    let bodySource: ByteSource?
}

/// What the handler wants written back.
struct PeerResponse {
    let status: Int
    let body: Data

    init(status: Int, body: Data = Data()) {
        self.status = status
        self.body = body
    }

    init(status: Int, text: String) {
        self.status = status
        self.body = Data(text.utf8)
    }
}

/// Listens for peer connections and hands each parsed request to `handler`.
///
/// The header cap matches the Android `PeerServer.MAX_HEADER_BYTES` (16 KiB).
/// MAC-SPIKE: the task referenced `PeerHelloServer.MAX_HEADER_BYTES`, but no
/// such type exists in this repository snapshot; the Go/Android value is used.
final class PeerListener {
    static let maxHeaderBytes = 16 * 1024

    private let identity: SecIdentity
    private let requestedPort: UInt16
    private let handler: (PeerRequest) -> PeerResponse
    private let queue = DispatchQueue(label: "io.github.tuscani712.lanyard.listener")
    private var listener: NWListener?

    init(identity: SecIdentity, port: UInt16, handler: @escaping (PeerRequest) -> PeerResponse) {
        self.identity = identity
        self.requestedPort = port
        self.handler = handler
    }

    /// The bound port (valid after `start`).
    private(set) var port: UInt16 = 0

    /// Starts listening and returns the bound port.
    func start() throws -> UInt16 {
        let parameters = TLS.parameters(tls: TLS.serverOptions(identity: identity))
        let port: NWEndpoint.Port = requestedPort == 0 ? .any : (NWEndpoint.Port(rawValue: requestedPort) ?? .any)
        let listener = try NWListener(using: parameters, on: port)
        let ready = DispatchSemaphore(value: 0)
        var failure: Error?
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready: ready.signal()
            case .failed(let error): failure = error; ready.signal()
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.handle(connection)
        }
        listener.start(queue: queue)
        if ready.wait(timeout: .now() + 5) == .timedOut {
            listener.cancel()
            throw LanyardNetError.connectionTimeout
        }
        if let failure {
            listener.cancel()
            throw LanyardNetError.connectionFailed("\(failure)")
        }
        self.listener = listener
        self.port = listener.port?.rawValue ?? requestedPort
        return self.port
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    // MARK: - Connection handling

    private func handle(_ connection: NWConnection) {
        let ready = DispatchSemaphore(value: 0)
        var failure: Error?
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready: ready.signal()
            case .failed(let error): failure = error; ready.signal()
            case .cancelled: failure = LanyardNetError.connectionFailed("cancelled"); ready.signal()
            default: break
            }
        }
        connection.start(queue: queue)
        if ready.wait(timeout: .now() + 15) == .timedOut {
            connection.cancel()
            return
        }
        if failure != nil {
            connection.cancel()
            return
        }
        defer { connection.cancel() }

        guard let fingerprint = peerFingerprint(connection) else {
            // mTLS requires a client certificate; without one there is nobody to
            // authorize, so the connection is dropped.
            return
        }

        let source = NWConnectionByteSource(connection: connection)
        guard let head = try? readHead(source: source, maxBytes: Self.maxHeaderBytes), head.isRequest else {
            return
        }

        let fileBody = head.method == "PUT" && head.path.hasSuffix("/file")
        let request: PeerRequest
        do {
            if fileBody {
                request = PeerRequest(
                    method: head.method, path: head.path, query: head.query, headers: head.headers,
                    peerFingerprint: fingerprint, body: Data(), bodySource: streamBody(source: source, head: head)
                )
            } else {
                let body = try readBody(source: source, head: head, limit: 64 * 1024 * 1024)
                request = PeerRequest(
                    method: head.method, path: head.path, query: head.query, headers: head.headers,
                    peerFingerprint: fingerprint, body: Data(body), bodySource: nil
                )
            }
        } catch {
            send(connection, status: 400, body: Data(#"{"error":"bad request"}"#.utf8))
            return
        }

        let response = handler(request)
        send(connection, status: response.status, body: response.body)
    }

    /// The peer's Device ID, read from the TLS handshake metadata.
    ///
    /// MAC-SPIKE: confirm `sec_protocol_metadata_copy_peer_certificate_chain`
    /// and `sec_certificate_copy_ref` are the right accessors under NWListener
    /// (SPIKE.md step 3), and that the chain is available after `.ready`.
    private func peerFingerprint(_ connection: NWConnection) -> String? {
        guard let metadata = connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata else {
            return nil
        }
        let secMetadata = metadata.securityProtocolMetadata
        guard let chain = sec_protocol_metadata_copy_peer_certificate_chain(secMetadata) as? [sec_certificate_t],
              let leaf = chain.first else {
            return nil
        }
        let certificate = sec_certificate_copy_ref(leaf).takeRetainedValue()
        return TLS.fingerprint(of: certificate)
    }

    private func send(_ connection: NWConnection, status: Int, body: Data, close: Bool = true) {
        var head = "HTTP/1.1 \(status) \(PeerListener.reason(status))\r\n"
        head += "Content-Type: application/json\r\n"
        head += "Content-Length: \(body.count)\r\n"
        head += "Connection: \(close ? "close" : "keep-alive")\r\n\r\n"
        var out = Data(head.utf8)
        out.append(body)
        connection.send(content: out, completion: .contentProcessed { _ in
            if close { connection.cancel() }
        })
    }

    /// Reason phrases, matching the Android `PeerServer.reason`.
    static func reason(_ code: Int) -> String {
        switch code {
        case 200: return "OK"
        case 400: return "Bad Request"
        case 401: return "Unauthorized"
        case 403: return "Forbidden"
        case 404: return "Not Found"
        case 408: return "Request Timeout"
        case 409: return "Conflict"
        case 411: return "Length Required"
        case 413: return "Payload Too Large"
        case 429: return "Too Many Requests"
        case 500: return "Internal Server Error"
        case 503: return "Service Unavailable"
        case 507: return "Insufficient Storage"
        default: return "Error"
        }
    }
}
#endif

// PeerConnector — a pinned-mTLS client over Network.framework, plus the shared
// HTTP/1.1 and `NWConnection`-as-`ByteSource` plumbing the listener reuses.
#if canImport(Network) && canImport(Security)
import Foundation
import Network
import LanyardCore

/// A blocking `ByteSource` over an `NWConnection`. `read` issues one
/// `NWConnection.receive` and waits on a semaphore, so the synchronous protocol
/// code in `LanyardCore` (which reads byte-at-a-time) can run unchanged on top
/// of Network.framework's callback API.
///
/// Callers must start the connection before reading. A single reader thread is
/// assumed (the HTTP code here and in `PeerListener` is serial).
final class NWConnectionByteSource: ByteSource {
    private let connection: NWConnection
    private let lock = NSLock()
    private let semaphore = DispatchSemaphore(value: 0)
    private var pending: [UInt8] = []
    private var eof = false
    private var failure: Error?

    init(connection: NWConnection) {
        self.connection = connection
    }

    /// True once the peer half-closed and every buffered byte is consumed.
    var didFinish: Bool {
        lock.lock(); defer { lock.unlock() }
        return eof && pending.isEmpty
    }

    func read(_ buffer: inout [UInt8], offset: Int, count: Int) -> Int {
        guard count > 0 else { return 0 }
        while true {
            lock.lock()
            if !pending.isEmpty {
                let n = min(count, pending.count)
                for i in 0..<n { buffer[offset + i] = pending[i] }
                pending.removeFirst(n)
                lock.unlock()
                return n
            }
            if eof || failure != nil {
                lock.unlock()
                return -1
            }
            lock.unlock()
            requestReceive(max: max(count, 64 * 1024))
            semaphore.wait()
        }
    }

    private func requestReceive(max: Int) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: max) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            self.lock.lock()
            if let data, !data.isEmpty { self.pending.append(contentsOf: data) }
            if let error { self.failure = error }
            if isComplete { self.eof = true }
            self.lock.unlock()
            self.semaphore.signal()
        }
    }
}

/// A `ByteSource` over LanyardCore's `ChunkedDecoder`, so an HTTP chunked body
/// can be streamed with the same interface as a Content-Length body.
final class ChunkedBodySource: ByteSource {
    private let decoder: ChunkedDecoder
    private var buffer: [UInt8] = []

    init(_ decoder: ChunkedDecoder) { self.decoder = decoder }

    func read(_ out: inout [UInt8], offset: Int, count: Int) -> Int {
        do {
            if buffer.isEmpty {
                if decoder.isFinished { return -1 }
                buffer = try decoder.read(max: max(count, 64 * 1024))
                if buffer.isEmpty { return -1 }
            }
            let n = min(count, buffer.count)
            for i in 0..<n { out[offset + i] = buffer[i] }
            buffer.removeFirst(n)
            return n
        } catch {
            return -1
        }
    }
}

/// A parsed HTTP/1.1 start line plus headers. Request and response share it.
struct HTTPHead {
    let isRequest: Bool
    let method: String
    let target: String
    let path: String
    let query: String
    let version: String
    /// Header names lowercased; values trimmed.
    let headers: [String: String]
    let keepAlive: Bool

    var statusCode: Int? {
        guard !isRequest else { return nil }
        let parts = target.split(separator: " ")
        return parts.count >= 2 ? Int(parts[1]) : nil
    }
}

/// A buffered response.
struct HTTPResponse {
    let status: Int
    let headers: [String: String]
    let body: [UInt8]

    var bodyData: Data { Data(body) }
    var bodyText: String { String(decoding: body, as: UTF8.self) }

    func jsonObject() throws -> [String: Any] {
        let object = try JSONSerialization.jsonObject(with: bodyData)
        guard let dictionary = object as? [String: Any] else {
            throw LanyardNetError.malformedResponse("expected a JSON object")
        }
        return dictionary
    }
}

enum LanyardNetError: Error, CustomStringConvertible {
    case connectionFailed(String)
    case connectionTimeout
    case malformedResponse(String)
    case httpStatus(Int, String)
    case notConnected

    var description: String {
        switch self {
        case .connectionFailed(let message): return "connection failed: \(message)"
        case .connectionTimeout: return "connection timed out"
        case .malformedResponse(let message): return "malformed response: \(message)"
        case .httpStatus(let code, let body): return "HTTP \(code): \(String(body.prefix(200)))"
        case .notConnected: return "not connected"
        }
    }
}

/// A client connection to one peer, pinned to that peer's certificate
/// fingerprint. One `PeerConnector` maps to one `NWConnection`; requests reuse
/// it while the peer keeps the connection alive.
final class PeerConnector {
    let host: String
    let port: Int
    private let identity: SecIdentity
    /// The pin; nil means "probe mode" — accept any peer but record it.
    private let expectedFingerprint: String?
    private let onPeerFingerprint: ((String) -> Void)?
    private let queue: DispatchQueue
    private var connection: NWConnection?
    private var source: NWConnectionByteSource?

    init(
        host: String,
        port: Int,
        identity: SecIdentity,
        expectedFingerprint: String? = nil,
        onPeerFingerprint: ((String) -> Void)? = nil
    ) {
        self.host = host
        self.port = port
        self.identity = identity
        self.expectedFingerprint = expectedFingerprint
        self.onPeerFingerprint = onPeerFingerprint
        self.queue = DispatchQueue(label: "io.github.tuscani712.lanyard.connector")
    }

    deinit { connection?.cancel() }

    /// Opens the mTLS connection if not already open, waiting up to `timeout`.
    func connect(timeout: TimeInterval = 10) throws {
        if source != nil, connection?.state == .ready { return }

        let tls: NWProtocolTLS.Options
        if let expectedFingerprint {
            tls = TLS.clientOptions(identity: identity, expectedFingerprint: expectedFingerprint)
        } else {
            tls = TLS.probeOptions(identity: identity, onPeerFingerprint: { [weak self] in self?.onPeerFingerprint?($0) })
        }
        let parameters = TLS.parameters(tls: tls)
        let host = NWEndpoint.Host(self.host)
        guard let port = NWEndpoint.Port(rawValue: UInt16(clamping: self.port)) else {
            throw LanyardNetError.connectionFailed("bad port \(self.port)")
        }
        let connection = NWConnection(host: host, port: port, using: parameters)
        let ready = DispatchSemaphore(value: 0)
        var failure: String?
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready: ready.signal()
            case .failed(let error): failure = "\(error)"; ready.signal()
            case .cancelled: failure = "cancelled"; ready.signal()
            default: break
            }
        }
        connection.start(queue: queue)
        if ready.wait(timeout: .now() + timeout) == .timedOut {
            connection.cancel()
            throw LanyardNetError.connectionTimeout
        }
        if let failure {
            connection.cancel()
            throw LanyardNetError.connectionFailed(failure)
        }
        self.connection = connection
        self.source = NWConnectionByteSource(connection: connection)
    }

    func close() {
        connection?.cancel()
        connection = nil
        source = nil
    }

    /// The raw byte stream, for callers that want to frame their own data.
    func byteSource() throws -> ByteSource {
        try connect()
        guard let source else { throw LanyardNetError.notConnected }
        return source
    }

    /// A buffered request/response. Throws `LanyardNetError.httpStatus` for a
    /// non-2xx status, mirroring the Kotlin `PeerClient`.
    func request(
        method: String,
        path: String,
        headers: [String: String] = [:],
        body: Data? = nil,
        timeout: TimeInterval = 30
    ) throws -> HTTPResponse {
        let (head, stream) = try openStream(method: method, path: path, headers: headers, body: body)
        let status = head.statusCode ?? 0
        let bodyBytes = try readBody(source: stream, head: head, limit: 64 * 1024 * 1024)
        if !(200..<300).contains(status) {
            throw LanyardNetError.httpStatus(status, String(decoding: bodyBytes, as: UTF8.self))
        }
        return HTTPResponse(status: status, headers: head.headers, body: bodyBytes)
    }

    /// Sends a request and returns the response head plus a streaming body.
    func openStream(
        method: String,
        path: String,
        headers: [String: String],
        body: Data? = nil
    ) throws -> (HTTPHead, ByteSource) {
        try connect()
        guard let connection, let source else { throw LanyardNetError.notConnected }

        var head = "\(method) \(path) HTTP/1.1\r\nHost: \(host):\(port)\r\n"
        var requestHeaders = headers
        if body != nil {
            requestHeaders["Content-Length"] = "\(body!.count)"
        }
        for (key, value) in requestHeaders {
            head += "\(key): \(value)\r\n"
        }
        head += "Connection: keep-alive\r\n\r\n"

        var requestData = Data(head.utf8)
        if let body { requestData.append(body) }
        try send(connection, requestData)

        guard let responseHead = try readHead(source: source, maxBytes: 16 * 1024) else {
            throw LanyardNetError.malformedResponse("no response head")
        }
        return (responseHead, source)
    }

    /// Sends an already-framed request. Used for chunked uploads, where the body
    /// length is not known before streaming.
    func sendRaw(_ data: Data) throws {
        try connect()
        guard let connection else { throw LanyardNetError.notConnected }
        try send(connection, data)
    }

    /// Reads a full response (head + buffered body) from the connection.
    func readResponse(limit: Int = 16 * 1024 * 1024) throws -> HTTPResponse {
        guard let source else { throw LanyardNetError.notConnected }
        guard let head = try readHead(source: source, maxBytes: 16 * 1024) else {
            throw LanyardNetError.malformedResponse("no response head")
        }
        let status = head.statusCode ?? 0
        let body = try readBody(source: source, head: head, limit: limit)
        return HTTPResponse(status: status, headers: head.headers, body: body)
    }

    /// Opens a GET and returns the response head plus a streaming body source.
    func openGetStream(path: String, headers: [String: String] = [:]) throws -> (HTTPHead, ByteSource) {
        try openStream(method: "GET", path: path, headers: headers)
    }

    // MARK: - Sending

    private func send(_ connection: NWConnection, _ data: Data) throws {
        let done = DispatchSemaphore(value: 0)
        var failure: Error?
        connection.send(content: data, completion: .contentProcessed { error in
            failure = error
            done.signal()
        })
        done.wait()
        if let failure { throw LanyardNetError.connectionFailed("\(failure)") }
    }
}

// MARK: - Shared HTTP/1.1 framing

/// Reads the HTTP start line and headers, bounded by `maxBytes`.
func readHead(source: ByteSource, maxBytes: Int) throws -> HTTPHead? {
    var buffer: [UInt8] = []
    while true {
        var one = [UInt8](repeating: 0, count: 1)
        let n = source.read(&one, offset: 0, count: 1)
        if n <= 0 {
            return buffer.isEmpty ? nil : try parseHead(buffer)
        }
        buffer.append(one[0])
        if buffer.count > maxBytes { throw LanyardNetError.malformedResponse("header too large") }
        if buffer.count >= 4, buffer.suffix(4) == [13, 10, 13, 10] {
            return try parseHead(buffer)
        }
    }
}

private func parseHead(_ bytes: [UInt8]) throws -> HTTPHead {
    let text = String(decoding: bytes, as: UTF8.self)
    let lines = text.components(separatedBy: "\r\n")
    guard let start = lines.first, !start.isEmpty else {
        throw LanyardNetError.malformedResponse("empty start line")
    }
    let parts = start.split(separator: " ").map(String.init)
    guard parts.count >= 2 else { throw LanyardNetError.malformedResponse("bad start line") }
    // A response start line starts with "HTTP/"; anything else is a request.
    let isResponse = parts[0].hasPrefix("HTTP/")
    var headers: [String: String] = [:]
    for line in lines.dropFirst() {
        guard let colon = line.firstIndex(of: ":") else { continue }
        let name = line[line.startIndex..<colon].trimmingCharacters(in: .whitespaces).lowercased()
        let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        headers[name] = value
    }
    let keepAlive = !(headers["connection"]?.lowercased().contains("close") ?? false)
    if isResponse {
        return HTTPHead(isRequest: false, method: "", target: start,
                        path: "", query: "", version: parts[0], headers: headers, keepAlive: keepAlive)
    }
    let target = parts[1]
    let path = String(target.prefix { $0 != "?" })
    let query = target.contains("?") ? String(target[target.firstIndex(of: "?")!...].dropFirst()) : ""
    return HTTPHead(isRequest: true, method: parts[0].uppercased(), target: target, path: path,
                    query: query, version: parts.count > 2 ? parts[2] : "HTTP/1.1",
                    headers: headers, keepAlive: keepAlive)
}

/// Reads a whole body, honoring Content-Length or chunked transfer coding.
func readBody(source: ByteSource, head: HTTPHead, limit: Int) throws -> [UInt8] {
    if head.headers["transfer-encoding"]?.lowercased().contains("chunked") == true {
        let decoder = ChunkedDecoder(source)
        return try decoder.readAll(limit: limit)
    }
    guard let lengthText = head.headers["content-length"], let length = Int(lengthText) else {
        return []
    }
    if length <= 0 { return [] }
    if length > limit { throw LanyardNetError.malformedResponse("body too large") }
    var out: [UInt8] = []
    out.reserveCapacity(length)
    var buf = [UInt8](repeating: 0, count: 64 * 1024)
    while out.count < length {
        let want = min(buf.count, length - out.count)
        let n = source.read(&buf, offset: 0, count: want)
        if n <= 0 { throw LanyardNetError.malformedResponse("truncated body") }
        out.append(contentsOf: buf[0..<n])
    }
    return out
}

/// The streaming body of a response/request, for one file at a time.
func streamBody(source: ByteSource, head: HTTPHead) -> ByteSource {
    if head.headers["transfer-encoding"]?.lowercased().contains("chunked") == true {
        return ChunkedBodySource(ChunkedDecoder(source))
    }
    let length = Int(head.headers["content-length"] ?? "0") ?? 0
    return LimitedByteSource(source: source, remaining: length)
}

/// Caps a `ByteSource` at `remaining` bytes.
final class LimitedByteSource: ByteSource {
    private let source: ByteSource
    private var remaining: Int

    init(source: ByteSource, remaining: Int) {
        self.source = source
        self.remaining = remaining
    }

    func read(_ buffer: inout [UInt8], offset: Int, count: Int) -> Int {
        if remaining <= 0 { return -1 }
        let want = min(count, remaining)
        let n = source.read(&buffer, offset: offset, count: want)
        if n > 0 { remaining -= n }
        return n
    }
}
#endif

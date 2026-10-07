// PeerClientAdapter — concrete conformances to the Phase 1b seam protocols, so
// the pure pairing/push/download state machines in `LanyardCore` run on top of
// Network.framework.
//
// NOTE on module access. In this repository snapshot every `LanyardCore` type
// is `internal`, so a sibling target cannot see them. Before a Mac build these
// seams must be promoted to `package` (or `public`); see SPIKE.md "Prerequisites".
#if canImport(Network) && canImport(Security)
import Foundation
import Network
import CryptoKit
import LanyardCore

/// The device's identity as the pairing state machine sees it. Carries the
/// `SecIdentity` used by the TLS layer (`PairingIdentity` itself only exposes
/// `deviceId`).
struct PeerIdentity: PairingIdentity {
    let deviceId: String
    let name: String
    let identity: SecIdentity

    init(material: SecIdentityFactory.Material, name: String) {
        self.deviceId = material.deviceId
        self.name = name
        self.identity = material.identity
    }
}

/// Creates probes and pinned clients, matching `PairingTransport`.
final class LanyardPairingTransport: PairingTransport {
    /// Used when the caller hands us a `PairingIdentity` that is not a
    /// `PeerIdentity` (the seam only promises `deviceId`).
    private let fallback: PeerIdentity

    init(fallback: PeerIdentity) {
        self.fallback = fallback
    }

    func probe(host: String, port: Int, identity: PairingIdentity) -> PairingProbe {
        LanyardPairingProbe(host: host, port: port, identity: resolve(identity))
    }

    func client(host: String, port: Int, identity: PairingIdentity, expectedFingerprint: String) -> PairingClient {
        LanyardPairingClient(host: host, port: port, identity: resolve(identity), expectedFingerprint: expectedFingerprint)
    }

    private func resolve(_ identity: PairingIdentity) -> PeerIdentity {
        (identity as? PeerIdentity) ?? fallback
    }
}

/// The unpinned `GET /api/v1/hello` probe; records the presented Device ID.
final class LanyardPairingProbe: PairingProbe {
    private let connector: PeerConnector
    private var fingerprint = ""
    private let lock = NSLock()

    init(host: String, port: Int, identity: PeerIdentity) {
        self.connector = PeerConnector(
            host: host, port: port, identity: identity.identity,
            expectedFingerprint: nil,
            onPeerFingerprint: { [weak self] value in
                guard let self else { return }
                self.lock.lock(); self.fingerprint = value; self.lock.unlock()
            }
        )
    }

    func hello() throws {
        _ = try connector.request(method: "GET", path: "/api/v1/hello")
    }

    func observedFingerprint() -> String {
        lock.lock(); defer { lock.unlock() }
        return fingerprint
    }
}

/// The pinned session client (`/session/*` and `/hello`), matching `PairingClient`.
final class LanyardPairingClient: PairingClient {
    private let connector: PeerConnector

    init(host: String, port: Int, identity: PeerIdentity, expectedFingerprint: String) {
        self.connector = PeerConnector(
            host: host, port: port, identity: identity.identity,
            expectedFingerprint: expectedFingerprint
        )
    }

    func startSession(
        mode: String,
        name: String,
        deviceId: String,
        nonce: String,
        requested: Permissions,
        invite: String
    ) throws -> [String: Any] {
        var body: [String: Any] = [
            "mode": mode,
            "name": name,
            "device_id": deviceId,
            "nonce": nonce,
            "requested_permissions": requested.toJSONObject(),
        ]
        if !invite.isEmpty { body["invite"] = invite }
        let data = try JSONSerialization.data(withJSONObject: body)
        let response = try connector.request(method: "POST", path: "/api/v1/session/request",
                                             headers: ["Content-Type": "application/json"], body: data)
        return try response.jsonObject()
    }

    func sessionStatus(_ sessionId: String) throws -> [String: Any] {
        let response = try connector.request(method: "GET", path: "/api/v1/session/\(encodePath(sessionId))")
        return try response.jsonObject()
    }

    func confirmSession(_ sessionId: String) throws {
        let response = try connector.request(method: "POST", path: "/api/v1/session/\(encodePath(sessionId))/confirm",
                                             headers: ["Content-Type": "application/json"], body: Data("{}".utf8))
        _ = try response.jsonObject()
    }

    func hello() throws -> PairHello {
        let response = try connector.request(method: "GET", path: "/api/v1/hello")
        let json = try response.jsonObject()
        return PairHello(name: json["name"] as? String ?? "")
    }
}

/// The push client (`/push/*`), matching `PushClient`.
final class LanyardPushClient: PushClient {
    private let connector: PeerConnector

    init(host: String, port: Int, identity: PeerIdentity, expectedFingerprint: String) {
        self.connector = PeerConnector(
            host: host, port: port, identity: identity.identity,
            expectedFingerprint: expectedFingerprint
        )
    }

    func pushOffer(_ files: [PushFileRequest]) throws -> PushOffer {
        let wireFiles: [[String: Any]] = files.map { file in
            [
                "rel_path": file.relPath,
                "size": file.size,
                "mtime": iso8601(file.mtimeMillis),
            ]
        }
        let body = try JSONSerialization.data(withJSONObject: ["files": wireFiles])
        let response = try connector.request(method: "POST", path: "/api/v1/push/offer",
                                             headers: ["Content-Type": "application/json"], body: body)
        let json = try response.jsonObject()
        var offsets: [String: Int64] = [:]
        for entry in (json["files"] as? [[String: Any]]) ?? [] {
            if let rel = entry["rel_path"] as? String {
                offsets[rel] = (entry["offset"] as? NSNumber)?.int64Value ?? 0
            }
        }
        return PushOffer(
            pushId: json["push_id"] as? String ?? "",
            accepted: json["accepted"] as? Bool ?? false,
            maxBytes: (json["max_bytes"] as? NSNumber)?.int64Value ?? 0,
            offsets: offsets
        )
    }

    func pushFileStream(
        pushId: String,
        relPath: String,
        offset: Int64,
        total: Int64,
        source: ByteSource,
        onBytes: (Int64) -> Void,
        isCancelled: () -> Bool,
        throttle: Throttle
    ) throws -> PushFileResult {
        let path = "/api/v1/push/\(encodePath(pushId))/file?path=\(encodeQuery(relPath))"
        let end = max(offset, total - 1)
        // Chunked upload: the Go server reads chunked bodies for large files, and
        // this lets us stream without buffering the file or knowing its length.
        var head = "PUT \(path) HTTP/1.1\r\n"
        head += "Host: \(connector.host):\(connector.port)\r\n"
        head += "Content-Type: application/octet-stream\r\n"
        head += "Content-Range: bytes \(offset)-\(end)/\(total)\r\n"
        head += "Transfer-Encoding: chunked\r\n"
        head += "Connection: keep-alive\r\n\r\n"
        try connector.sendRaw(Data(head.utf8))

        var hasher = SHA256()
        var buffer = [UInt8](repeating: 0, count: 256 * 1024)

        // Hash the prefix already on the receiver so the digest covers the whole
        // file (mirrors the Kotlin `pushFileStream`).
        var skip = offset
        while skip > 0 {
            let want = min(skip, Int64(buffer.count))
            let n = source.read(&buffer, offset: 0, count: Int(want))
            if n <= 0 { throw PeerHttpException(409, "the file ended before the resume offset") }
            hasher.update(data: Data(buffer[0..<n]))
            skip -= Int64(n)
        }

        var sent: Int64 = 0
        while true {
            if isCancelled() { throw PushCancelledException() }
            let n = source.read(&buffer, offset: 0, count: buffer.count)
            if n < 0 { break }
            if n == 0 { continue }
            hasher.update(data: Data(buffer[0..<n]))
            var chunk = Data("\(String(n, radix: 16))\r\n".utf8)
            chunk.append(contentsOf: buffer[0..<n])
            chunk.append(Data("\r\n".utf8))
            try connector.sendRaw(chunk)
            sent += Int64(n)
            onBytes(sent)
            throttle.pace(n)
        }
        try connector.sendRaw(Data("0\r\n\r\n".utf8))

        let response = try connector.readResponse(limit: 4096)
        guard (200..<300).contains(response.status) else {
            throw PeerHttpException(response.status, response.bodyText)
        }
        return PushFileResult(sha256: hex(hasher.finalize()), bytes: sent)
    }

    func pushCompleteFile(_ pushId: String, _ relPath: String, _ sha256: String) throws {
        let body = try JSONSerialization.data(withJSONObject: ["rel_path": relPath, "sha256": sha256])
        _ = try connector.request(method: "POST", path: "/api/v1/push/\(encodePath(pushId))/complete",
                                  headers: ["Content-Type": "application/json"], body: body)
    }

    func pushCompleteAll(_ pushId: String) throws {
        _ = try connector.request(method: "POST", path: "/api/v1/push/\(encodePath(pushId))/complete",
                                  headers: ["Content-Type": "application/json"],
                                  body: try JSONSerialization.data(withJSONObject: ["all": true]))
    }
}

/// The share reader (`/shares/*`), matching `ShareReader`.
final class LanyardShareReader: ShareReader {
    private let connector: PeerConnector

    init(host: String, port: Int, identity: PeerIdentity, expectedFingerprint: String) {
        self.connector = PeerConnector(
            host: host, port: port, identity: identity.identity,
            expectedFingerprint: expectedFingerprint
        )
    }

    func manifestFiles(shareId: String, path: String) throws -> Data {
        let response = try connector.request(
            method: "GET",
            path: "/api/v1/shares/\(encodePath(shareId))/manifest?path=\(encodeQuery(path))"
        )
        return response.bodyData
    }

    func openFileStream(shareId: String, path: String, rangeFrom: Int64) throws -> ByteSource {
        var headers: [String: String] = [:]
        if rangeFrom > 0 { headers["Range"] = "bytes=\(rangeFrom)-" }
        let (head, source) = try connector.openGetStream(
            path: "/api/v1/shares/\(encodePath(shareId))/file?path=\(encodeQuery(path))",
            headers: headers
        )
        let status = head.statusCode ?? 0
        guard status == 200 || status == 206 else {
            throw PeerHttpException(status, "share file request failed")
        }
        return streamBody(source: source, head: head)
    }

    func wholeFileHash(shareId: String, path: String) throws -> String {
        let response = try connector.request(
            method: "GET",
            path: "/api/v1/shares/\(encodePath(shareId))/hash?path=\(encodeQuery(path))"
        )
        let json = try response.jsonObject()
        return json["sha256"] as? String ?? ""
    }

    func reportComplete(shareId: String, verified: [(path: String, sha256: String)]) throws -> Bool {
        let files = verified.map { ["path": $0.path, "sha256": $0.sha256] }
        let body = try JSONSerialization.data(withJSONObject: ["files": files])
        let response = try connector.request(
            method: "POST",
            path: "/api/v1/shares/\(encodePath(shareId))/complete",
            headers: ["Content-Type": "application/json"],
            body: body
        )
        let json = try response.jsonObject()
        return json["consumed"] as? Bool ?? false
    }
}

// MARK: - Small helpers

private func hex(_ digest: SHA256.Digest) -> String {
    digest.map { String(format: "%02x", $0) }.joined()
}

private func iso8601(_ millis: Int64) -> String {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime]
    return formatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000.0))
}

private func encodePath(_ value: String) -> String {
    value.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? value
}

private func encodeQuery(_ value: String) -> String {
    value.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? value
}
#endif

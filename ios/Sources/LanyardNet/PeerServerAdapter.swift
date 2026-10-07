// PeerServerAdapter — routes parsed HTTP requests into the LanyardCore server
// state machines (`PairingSessions`, `InboxReceiver`, `PairInvites`), mirroring
// the Android `PeerServer.route`.
#if canImport(Network) && canImport(Security)
import Foundation
import LanyardCore

/// Everything the router needs from the app. The state machines themselves are
/// pure and already tested on Linux; this only wires them to the wire formats.
struct PeerServerConfig {
    let sessions: PairingSessions
    let receiver: InboxReceiver
    let invites: PairInvites
    /// The stored trust entry for a fingerprint, or nil if not paired.
    let isPaired: (String) -> PairedPeer?
    /// Called when an authenticated peer revokes its own pairing.
    let onUnpair: (String) -> Void
    /// The `/hello` body (device_id, fingerprint, name, os, version, port).
    let hello: () -> [String: Any]
    /// Ask the person before accepting a large push. Returns false to decline.
    let approval: ((_ peerFp: String, _ peerName: String, _ files: Int, _ total: Int64) -> Bool)?
    /// True when the device is on a metered network and "Wi-Fi only" is on.
    let blockedByWifiOnly: () -> Bool

    init(
        sessions: PairingSessions,
        receiver: InboxReceiver,
        invites: PairInvites,
        isPaired: @escaping (String) -> PairedPeer?,
        onUnpair: @escaping (String) -> Void = { _ in },
        hello: @escaping () -> [String: Any],
        approval: ((String, String, Int, Int64) -> Bool)? = nil,
        blockedByWifiOnly: @escaping () -> Bool = { false }
    ) {
        self.sessions = sessions
        self.receiver = receiver
        self.invites = invites
        self.isPaired = isPaired
        self.onUnpair = onUnpair
        self.hello = hello
        self.approval = approval
        self.blockedByWifiOnly = blockedByWifiOnly
    }
}

/// The router. Conform `PeerListener`'s handler to `handle(_:)`.
final class PeerServerAdapter {
    private let config: PeerServerConfig

    init(config: PeerServerConfig) {
        self.config = config
    }

    /// A handler suitable for `PeerListener`.
    func handler() -> (PeerRequest) -> PeerResponse {
        { [weak self] request in
            self?.handle(request) ?? PeerResponse(status: 500, text: Self.errorJSON("server unavailable"))
        }
    }

    func handle(_ request: PeerRequest) -> PeerResponse {
        do {
            return try route(request)
        } catch let error as PeerHttpException {
            return PeerResponse(status: error.code, text: Self.errorJSON(error.message))
        } catch {
            return PeerResponse(status: 500, text: Self.errorJSON("internal error"))
        }
    }

    private func route(_ request: PeerRequest) throws -> PeerResponse {
        let method = request.method
        let path = request.path
        let fp = request.peerFingerprint

        if method == "GET" && path == "/api/v1/hello" {
            return PeerResponse(status: 200, text: Self.json(config.hello()))
        }
        if method == "POST" && path == "/api/v1/session/request" {
            return try sessionRequest(request)
        }
        if method == "POST" && path == "/api/v1/trust/revoke" {
            guard config.isPaired(fp) != nil else { throw PeerHttpException(403, "not paired") }
            config.onUnpair(fp)
            return PeerResponse(status: 200, text: #"{"ok":true}"#)
        }
        if path.hasPrefix("/api/v1/session/") {
            return try session(request)
        }
        if method == "POST" && path == "/api/v1/push/offer" {
            return try pushOffer(request)
        }
        if path.hasPrefix("/api/v1/push/") && path.hasSuffix("/file") {
            return try pushFile(request)
        }
        if path.hasPrefix("/api/v1/push/") && path.hasSuffix("/complete") {
            return try pushComplete(request)
        }
        return PeerResponse(status: 404, text: Self.errorJSON("not found"))
    }

    // MARK: - Sessions

    private func sessionRequest(_ request: PeerRequest) throws -> PeerResponse {
        let json = try parseObject(request.body)
        let fp = request.peerFingerprint
        if let claimed = json["fingerprint"] as? String, !claimed.isEmpty,
           claimed.compare(fp, options: .caseInsensitive) != .orderedSame {
            throw PeerHttpException(400, "fingerprint does not match the certificate")
        }
        if let claimedId = json["device_id"] as? String, claimedId.count == 64,
           claimedId.allSatisfy({ $0.isHexDigit }),
           claimedId.compare(fp, options: .caseInsensitive) != .orderedSame {
            throw PeerHttpException(400, "device id does not match the certificate")
        }
        let invite = (json["invite"] as? String) ?? ""
        let view = try config.sessions.createIncoming(
            mode: (json["mode"] as? String) ?? "",
            peerFp: fp,
            peerName: (json["name"] as? String) ?? "",
            peerDevice: (json["device_id"] as? String) ?? "",
            peerHost: "",
            peerNonce: (json["nonce"] as? String) ?? "",
            requested: Self.permissions(from: json["requested_permissions"]),
            consumeInvite: invite.isEmpty ? nil : { [invites = config.invites] in invites.consume(invite) }
        )
        return PeerResponse(status: 200, text: Self.json([
            "session_id": view.id,
            "nonce": view.nonce,
            "status": view.status,
        ]))
    }

    private func session(_ request: PeerRequest) throws -> PeerResponse {
        let rest = String(request.path.dropFirst("/api/v1/session/".count))
        let fp = request.peerFingerprint
        if request.method == "GET" && !rest.contains("/") {
            let view = try config.sessions.statusFor(rest, callerFp: fp)
            return PeerResponse(status: 200, text: Self.json([
                "status": view.status,
                "mode": view.mode,
                "nonce": view.nonce,
                "sas": view.sas,
                "granted": view.granted.toJSONObject(),
            ]))
        }
        if request.method == "POST" && rest.hasSuffix("/confirm") {
            let view = try config.sessions.confirm(String(rest.dropLast("/confirm".count)), callerFp: fp)
            return PeerResponse(status: 200, text: Self.json(["status": view.status]))
        }
        if request.method == "POST" && rest.hasSuffix("/close") {
            _ = try config.sessions.close(String(rest.dropLast("/close".count)), callerFp: fp)
            return PeerResponse(status: 200, text: #"{"closed":true}"#)
        }
        return PeerResponse(status: 404, text: Self.errorJSON("not found"))
    }

    // MARK: - Push

    private func pushOffer(_ request: PeerRequest) throws -> PeerResponse {
        let peer = try requirePush(request.peerFingerprint)
        if config.blockedByWifiOnly() {
            throw PeerHttpException(403, "Wi-Fi only is on. Connect to Wi-Fi to receive files.")
        }
        let json = try parseObject(request.body)
        guard let files = json["files"] as? [[String: Any]], !files.isEmpty else {
            throw PeerHttpException(400, "no files")
        }
        let reqs = files.map { file -> PushFileRequest in
            PushFileRequest(
                relPath: (file["rel_path"] as? String) ?? "",
                size: (file["size"] as? NSNumber)?.int64Value ?? 0,
                mtimeMillis: Self.parseMtime(file["mtime"] as? String)
            )
        }
        let total = (json["total_bytes"] as? NSNumber)?.int64Value ?? 0
        let offer = try config.receiver.offer(
            peerFp: peer.fingerprint,
            peerName: peer.name,
            reqs: reqs,
            totalBytes: total,
            maxBytes: peer.pushMaxBytes
        )
        let realTotal = total > 0 ? total : reqs.reduce(0) { $0 + $1.size }
        if peer.askOver > 0, realTotal > peer.askOver, let approval = config.approval {
            if !approval(peer.fingerprint, peer.name, reqs.count, realTotal) {
                config.receiver.cancel(id: offer.pushId, peerFp: peer.fingerprint)
                throw PeerHttpException(403, "the transfer was declined")
            }
        }
        var fileList: [[String: Any]] = []
        for (rel, offset) in offer.offsets {
            fileList.append(["rel_path": rel, "offset": offset])
        }
        return PeerResponse(status: 200, text: Self.json([
            "push_id": offer.pushId,
            "accepted": offer.accepted,
            "max_bytes": offer.maxBytes,
            "files": fileList,
        ]))
    }

    private func pushFile(_ request: PeerRequest) throws -> PeerResponse {
        let peer = try requirePush(request.peerFingerprint)
        guard let bodySource = request.bodySource else {
            throw PeerHttpException(411, "content-length required")
        }
        let id = try idBetween(request.path, prefix: "/api/v1/push/", suffix: "/file")
        guard let rel = Self.queryParam(request.query, "path"), !rel.isEmpty else {
            throw PeerHttpException(400, "path required")
        }
        let offset = try Self.parseContentRange(request.headers["content-range"])
        let sha = request.headers["x-lanyard-sha256"]
        if let sha, !sha.isEmpty, offset == 0 {
            let written = try config.receiver.receiveWhole(id: id, peerFp: peer.fingerprint, rel: rel, sha256: sha, source: bodySource)
            return PeerResponse(status: 200, text: Self.json([
                "written": written, "offset": written, "done": true,
            ]))
        }
        let written = try config.receiver.writeChunk(id: id, peerFp: peer.fingerprint, rel: rel, offset: offset, source: bodySource)
        return PeerResponse(status: 200, text: Self.json([
            "written": written, "offset": offset + written,
        ]))
    }

    private func pushComplete(_ request: PeerRequest) throws -> PeerResponse {
        let peer = try requirePush(request.peerFingerprint)
        let id = try idBetween(request.path, prefix: "/api/v1/push/", suffix: "/complete")
        let json = try parseObject(request.body)
        if (json["all"] as? Bool) == true {
            _ = config.receiver.finish(id: id, peerFp: peer.fingerprint)
            return PeerResponse(status: 200, text: #"{"done":true}"#)
        }
        let rel = (json["rel_path"] as? String) ?? ""
        let sha = (json["sha256"] as? String) ?? ""
        let state = try config.receiver.complete(id: id, peerFp: peer.fingerprint, rel: rel, sha256: sha)
        return PeerResponse(status: 200, text: Self.json(["rel_path": state.relPath, "done": true]))
    }

    private func requirePush(_ fingerprint: String) throws -> PairedPeer {
        guard let peer = config.isPaired(fingerprint) else {
            throw PeerHttpException(403, "not paired")
        }
        guard peer.push else { throw PeerHttpException(403, "push not permitted") }
        return peer
    }

    // MARK: - JSON helpers

    private func parseObject(_ data: Data) throws -> [String: Any] {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw PeerHttpException(400, "bad json body")
        }
        return object
    }

    static func json(_ value: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: value),
              let text = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return text
    }

    static func errorJSON(_ message: String) -> String {
        json(["error": message])
    }

    static func permissions(from value: Any?) -> Permissions {
        guard let object = value as? [String: Any] else { return Permissions() }
        return Permissions(
            browse: object["browse"] as? Bool ?? false,
            push: object["push"] as? Bool ?? false,
            pushMaxBytes: (object["push_max_bytes"] as? NSNumber)?.int64Value ?? 0,
            askOver: (object["ask_over"] as? NSNumber)?.int64Value ?? 0
        )
    }

    /// RFC3339 (the Go `inbox.FileReq.MTime` wire form) to epoch millis.
    static func parseMtime(_ text: String?) -> Int64 {
        guard let text, !text.isEmpty else { return 0 }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: text) {
            return Int64(date.timeIntervalSince1970 * 1000)
        }
        formatter.formatOptions = [.withInternetDateTime]
        guard let date = formatter.date(from: text) else { return 0 }
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    /// `bytes START-END/TOTAL` -> START (0 when absent).
    static func parseContentRange(_ header: String?) throws -> Int64 {
        guard let header, !header.isEmpty else { return 0 }
        let trimmed = header.trimmingCharacters(in: .whitespaces)
        guard trimmed.hasPrefix("bytes ") else { throw PeerHttpException(400, "bad Content-Range") }
        let range = trimmed.dropFirst("bytes ".count).split(separator: "/").first.map(String.init) ?? ""
        guard let dash = range.firstIndex(of: "-") else { throw PeerHttpException(400, "bad Content-Range") }
        let start = String(range[range.startIndex..<dash]).trimmingCharacters(in: .whitespaces)
        guard let value = Int64(start), value >= 0 else { throw PeerHttpException(400, "bad Content-Range") }
        return value
    }

    static func queryParam(_ query: String, _ name: String) -> String? {
        guard !query.isEmpty else { return nil }
        for part in query.split(separator: "&") {
            let pair = part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard pair.first.map(String.init) == name else { continue }
            let value = pair.count > 1 ? String(pair[1]) : ""
            return value.removingPercentEncoding ?? value
        }
        return nil
    }

    private func idBetween(_ path: String, prefix: String, suffix: String) throws -> String {
        guard path.hasPrefix(prefix), path.hasSuffix(suffix) else {
            throw PeerHttpException(404, "not found")
        }
        return String(path.dropFirst(prefix.count).dropLast(suffix.count))
    }
}
#endif

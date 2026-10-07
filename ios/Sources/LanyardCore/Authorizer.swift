import Foundation

/// What an authenticated peer certificate may do, resolved at the moment a
/// request is read. Mirrors the Go `trust.Access` and the Kotlin
/// `isPaired`/`peer.browse`/`peer.push` lookups.
///
/// - `paired`: the fingerprint is in the trust store.
/// - `browse` / `push`: the matching permission on that trust entry.
/// - `sessionId` / `offeredShares`: a live one-off **Connect** session (not a
///   pairing) grants access to exactly the shares it offered.
public struct PeerAccess: Equatable, Sendable {
    public var paired: Bool
    public var browse: Bool
    public var push: Bool
    /// Non-nil for a live (accepted/active) one-off Connect session.
    public var sessionId: String?
    /// Only these share IDs are visible to a live Connect session.
    public var offeredShares: Set<String>

    public init(
        paired: Bool = false,
        browse: Bool = false,
        push: Bool = false,
        sessionId: String? = nil,
        offeredShares: Set<String> = []
    ) {
        self.paired = paired
        self.browse = browse
        self.push = push
        self.sessionId = sessionId
        self.offeredShares = offeredShares
    }

    /// Nobody: the default for an unknown certificate.
    public static let none = PeerAccess()

    /// Whether a live one-off Connect session exists for this peer.
    public var hasLiveSession: Bool { !(sessionId ?? "").isEmpty }
}

/// The verdict the authorizer returns for one request.
public enum AuthorizationDecision: Equatable, Sendable {
    case allow
    /// No authenticated client certificate (HTTP 401).
    case unauthorized(reason: String)
    /// Authenticated but not permitted (HTTP 403).
    case forbidden(reason: String)

    public var isAllowed: Bool {
        if case .allow = self { return true }
        return false
    }

    public var statusCode: Int {
        switch self {
        case .allow: return 200
        case .unauthorized: return 401
        case .forbidden: return 403
        }
    }

    /// The human-readable reason, or nil when allowed.
    public var reason: String? {
        switch self {
        case .allow: return nil
        case .unauthorized(let reason): return reason
        case .forbidden(let reason): return reason
        }
    }
}

/// Per-request authorization as pure rules.
///
/// Authorization is evaluated for every request, at the moment it is read, never
/// once per connection: a keep-alive connection opened before this peer paired
/// must be treated as paired as soon as it is (Task 30). Because this type holds
/// only an `access` closure over the *current* trust/session state, that
/// re-evaluation falls out for free.
///
/// Rules (ported from the Kotlin `PeerServer` and the Go `peerapi`):
///  - `GET /api/v1/hello` and `POST /api/v1/session/request` are open.
///  - `POST /api/v1/trust/revoke` requires a paired fingerprint (it only ever
///    removes the caller's own entry).
///  - Push endpoints require a paired fingerprint with the `push` permission.
///  - Share (pull) endpoints require a paired fingerprint with the `browse`
///    permission, or a live one-off Connect session whose offered shares
///    include the requested one.
///  - Any other request requires a paired fingerprint.
public struct Authorizer {
    public typealias AccessProvider = (String) -> PeerAccess
    public typealias Access = AccessProvider

    private let access: AccessProvider

    public init(access: @escaping AccessProvider) {
        self.access = access
    }

    public func authorize(method: String, path: String, fingerprint: String?) -> AuthorizationDecision {
        guard let fingerprint, !fingerprint.isEmpty else {
            return .unauthorized(reason: "client certificate required")
        }
        let method = method.uppercased()
        let path = Authorizer.pathOnly(path)

        if method == "GET" && path == "/api/v1/hello" { return .allow }
        if method == "POST" && path == "/api/v1/session/request" { return .allow }
        // Session status/confirm/close are per-session: the session store
        // enforces that the caller owns the session, so the authorizer lets them
        // through.
        if path.hasPrefix("/api/v1/session/") { return .allow }

        let peer = access(fingerprint)

        if method == "POST" && path == "/api/v1/trust/revoke" {
            return peer.paired ? .allow : .forbidden(reason: "not paired")
        }

        if Authorizer.isPushPath(method, path) {
            guard peer.paired else { return .forbidden(reason: "not paired") }
            guard peer.push else { return .forbidden(reason: "push not permitted") }
            return .allow
        }

        if Authorizer.isPullPath(path) {
            if peer.paired {
                return peer.browse ? .allow : .forbidden(reason: "pull not permitted")
            }
            if peer.hasLiveSession, Authorizer.sessionOffers(peer, path: path) {
                return .allow
            }
            return .forbidden(reason: "not paired")
        }

        // Every other request needs a paired fingerprint.
        return peer.paired ? .allow : .forbidden(reason: "not paired")
    }

    // MARK: - Path classification

    private static func pathOnly(_ target: String) -> String {
        guard let q = target.firstIndex(of: "?") else { return target }
        return String(target[..<q])
    }

    private static func isPushPath(_ method: String, _ path: String) -> Bool {
        if method == "POST" && path == "/api/v1/push/offer" { return true }
        if path.hasPrefix("/api/v1/push/") && (path.hasSuffix("/file") || path.hasSuffix("/complete")) {
            return true
        }
        return false
    }

    private static func isPullPath(_ path: String) -> Bool {
        path == "/api/v1/shares" || path.hasPrefix("/api/v1/shares/")
    }

    /// Whether a live Connect session may see the requested share. The list
    /// endpoint is visible when any share was offered; a specific share is only
    /// visible when it is in the offered set.
    private static func sessionOffers(_ peer: PeerAccess, path: String) -> Bool {
        if path == "/api/v1/shares" { return !peer.offeredShares.isEmpty }
        let prefix = "/api/v1/shares/"
        guard path.hasPrefix(prefix) else { return false }
        let rest = path.dropFirst(prefix.count)
        let id = rest.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
        return !id.isEmpty && peer.offeredShares.contains(id)
    }
}

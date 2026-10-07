import Foundation

/// Supplies the TCP port the peer server is currently listening on, or nil
/// while it is unknown. The invite QR/link must advertise a dialable
/// `host:port`, so the pairing screen gates the QR on a valid port; without
/// this seam the invite carries port 0 and cannot be dialed.
public protocol PortProvider {
    func currentPort() -> Int?
}

/// A `PortProvider` returning a fixed port (nil = unknown). For tests and for
/// callers that already hold the listener port.
public struct FixedPortProvider: PortProvider {
    public let port: Int?

    public init(_ port: Int?) {
        self.port = port
    }

    public func currentPort() -> Int? { port }
}

/// A closure-backed `PortProvider`, so the app can read the live listener's
/// port (which changes across restarts) instead of freezing it at init.
public struct ClosurePortProvider: PortProvider {
    private let provider: () -> Int?

    public init(_ provider: @escaping () -> Int?) {
        self.provider = provider
    }

    public func currentPort() -> Int? { provider() }
}

/// The pairing screen's states.
///
/// This is the local UI state machine around pairing, distinct from the
/// client-side `PairingFlow` (which drives a session against a peer) and the
/// responder-side `PairingSessions` (which the server owns). It models what the
/// person sees:
///
///  - `idle` — nothing in progress;
///  - `starting` — an invite has been requested but the listener port is still
///    unknown (or 0), so no QR/link is shown yet; `tick(now:)` promotes it to
///    `generating` once the port is known;
///  - `generating(invite:expiresAt:)` — this device showed its QR/link and is
///    waiting for someone to use it (2-minute validity);
///  - `awaitingConfirmation(offer:sas:)` — a peer used the invite and the person
///    must toggle permissions and confirm the SAS;
///  - `paired(PairedPeer)` — saved to the `TrustStore`;
///  - `declined` / `expired` — terminal outcomes.
public enum PairFlowState: Equatable {
    case idle
    case starting
    case generating(invite: String, expiresAt: Int64)
    case awaitingConfirmation(offer: PairOffer, sas: String)
    case paired(PairedPeer)
    case declined
    case expired
}

/// One incoming pairing request as the confirmation screen shows it.
public struct PairOffer: Equatable {
    public let peerFingerprint: String
    public let peerName: String
    public let peerDevice: String
    public let peerHost: String
    /// The nonce the peer sent; feeds the SAS.
    public let peerNonce: String
    public let requested: Permissions
    public let viaQr: Bool
    public let receivedAt: Int64

    public init(
        peerFingerprint: String,
        peerName: String = "",
        peerDevice: String = "",
        peerHost: String = "",
        peerNonce: String,
        requested: Permissions = Permissions(),
        viaQr: Bool = true,
        receivedAt: Int64 = 0
    ) {
        self.peerFingerprint = peerFingerprint
        self.peerName = peerName
        self.peerDevice = peerDevice
        self.peerHost = peerHost
        self.peerNonce = peerNonce
        self.requested = requested
        self.viaQr = viaQr
        self.receivedAt = receivedAt
    }
}

/// A pairing link pasted by hand. The manual-add path accepts only a
/// `lanyard://pair?...` link — there is no free-text host/port entry — and the
/// parsing itself is `PairLink`'s, so the same length/hex/address caps apply.
public struct ManualLink: Equatable {
    public let fingerprint: String
    public let name: String
    public let addresses: [String]
    public let nonce: String

    public init(fingerprint: String, name: String, addresses: [String], nonce: String) {
        self.fingerprint = fingerprint
        self.name = name
        self.addresses = addresses
        self.nonce = nonce
    }
}

/// The pairing UI state machine.
///
/// The clock is injectable so the 2-minute invite window is deterministic under
/// test. All state lives here; the transport/session work is elsewhere.
public final class PairFlow {
    /// Invite validity, matching `PairInvites` and `PairingSessions`.
    public static let inviteTTLMillis: Int64 = 2 * 60 * 1000

    public private(set) var state: PairFlowState = .idle

    /// The permissions this device will grant when it confirms. Defaults mirror
    /// the Android `PairingFlow` request (browse and push on); the person may
    /// toggle them on the permission screen before accepting.
    public private(set) var permissions = Permissions(browse: true, push: true)

    /// The token/expiry this device advertised, while generating. Nil while
    /// `starting` (no dialable port yet) and outside a live invite window.
    public var currentInvite: (token: String, expiresAt: Int64)? {
        if case let .generating(invite, expiresAt) = state { return (invite, expiresAt) }
        return nil
    }

    /// Whether a dialable invite is currently shown: true only in `generating`,
    /// i.e. once the listener port is known and valid. The QR/link UI must not
    /// render anything until this is true.
    public var readyToInvite: Bool {
        if case .generating = state { return true }
        return false
    }

    private let trust: TrustStore
    private let selfFingerprint: () -> String
    private let clock: () -> Int64
    private let invites: PairInvites
    private let portProvider: PortProvider
    /// The invite minted at `start()`; kept so a `starting` flow that later
    /// learns its port promotes to `generating` with the same token.
    private var pendingInvite: PairInvites.Invite?

    public init(
        trust: TrustStore,
        selfFingerprint: @escaping () -> String,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        portProvider: PortProvider = FixedPortProvider(nil)
    ) {
        self.trust = trust
        self.selfFingerprint = selfFingerprint
        self.clock = clock
        self.portProvider = portProvider
        // The invite store must share this flow's clock, so expiry is consistent.
        self.invites = PairInvites(clock: clock)
    }

    // MARK: - Lifecycle

    /// Begins generating a fresh invite. Allowed from `idle`, `declined`,
    /// `expired`, or `paired` (re-pairing). Resets the permission toggles.
    ///
    /// The invite is only advertised once `portProvider.currentPort()` returns a
    /// valid 1...65535 port; until then the flow is `starting` and exposes no
    /// token/addresses, so the QR is never built with port 0.
    @discardableResult
    public func start() -> (token: String, expiresAt: Int64) {
        permissions = Permissions(browse: true, push: true)
        let invite = invites.mint()
        pendingInvite = invite
        if Self.validPort(portProvider.currentPort()) {
            state = .generating(invite: invite.token, expiresAt: invite.expiresAt)
        } else {
            state = .starting
        }
        return (invite.token, invite.expiresAt)
    }

    /// The dialable `host:port` invite addresses, composed from the given local
    /// host strings and the current listener port. Returns `[]` until the flow is
    /// `generating` (so the app renders no QR). A bare IPv6 host is bracketed.
    public func inviteAddresses(localAddresses: [String]) -> [String] {
        guard case .generating = state,
              let port = portProvider.currentPort(),
              Self.validPort(port) else { return [] }
        return localAddresses.compactMap { host in
            let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return nil }
            if trimmed.contains(":") && !trimmed.hasPrefix("[") {
                return "[\(trimmed)]:\(port)"
            }
            return "\(trimmed):\(port)"
        }
    }

    private static func validPort(_ port: Int?) -> Bool {
        guard let port else { return false }
        return (1...65535).contains(port)
    }

    /// Records an incoming request against the still-valid invite and computes
    /// the SAS. A request arriving after the invite expired moves to `expired`.
    @discardableResult
    public func offer(_ offer: PairOffer) -> Bool {
        guard case let .generating(invite, expiresAt) = state else { return false }
        let now = clock()
        if now >= expiresAt {
            state = .expired
            return false
        }
        let sas = Sas.code(
            fpA: selfFingerprint(),
            fpB: offer.peerFingerprint,
            nonceA: invite,
            nonceB: offer.peerNonce
        )
        state = .awaitingConfirmation(offer: offer, sas: sas)
        return true
    }

    /// The person confirms the SAS and grants the toggled permissions. Only then
    /// is the peer written to the trust store. Returns the saved peer, or nil if
    /// there was nothing to confirm.
    @discardableResult
    public func confirmSas() -> PairedPeer? {
        guard case let .awaitingConfirmation(offer, _) = state else { return nil }
        let peer = PairedPeer(
            fingerprint: offer.peerFingerprint.lowercased(),
            name: offer.peerName.isEmpty ? Display.groupedHex(offer.peerFingerprint) : offer.peerName,
            host: offer.peerHost,
            port: 0,
            browse: permissions.browse,
            push: permissions.push,
            pairedAt: clock()
        )
        trust.save(peer)
        state = .paired(peer)
        return peer
    }

    /// The person declines the request; nothing is written.
    @discardableResult
    public func decline() -> Bool {
        guard case .awaitingConfirmation = state else { return false }
        state = .declined
        return true
    }

    // MARK: - Permissions toggles

    public func setBrowse(_ enabled: Bool) { permissions.browse = enabled }
    public func setPush(_ enabled: Bool) { permissions.push = enabled }

    // MARK: - Clock-driven expiry

    /// Expires a stale `generating` invite or an unconfirmed `awaitingConfirmation`
    /// prompt after the 2-minute window, and promotes `starting` to `generating`
    /// once the listener port becomes known. `now` defaults to the injected clock.
    public func tick(now: Int64? = nil) {
        let now = now ?? clock()
        switch state {
        case .starting:
            guard let pending = pendingInvite else {
                // No invite was ever minted (e.g. an externally driven start);
                // mint one now that a port may be available.
                if Self.validPort(portProvider.currentPort()) {
                    let invite = invites.mint()
                    pendingInvite = invite
                    state = .generating(invite: invite.token, expiresAt: invite.expiresAt)
                }
                return
            }
            if now >= pending.expiresAt {
                pendingInvite = nil
                state = .expired
            } else if Self.validPort(portProvider.currentPort()) {
                state = .generating(invite: pending.token, expiresAt: pending.expiresAt)
            }
        case let .generating(_, expiresAt):
            if now >= expiresAt { state = .expired }
        case let .awaitingConfirmation(offer, _):
            if now - offer.receivedAt > Self.inviteTTLMillis { state = .expired }
        default:
            break
        }
    }

    /// Whole seconds left on the invite window (rounded up), or 0 when there is
    /// no live window. Drives the countdown label.
    public func secondsRemaining(now: Int64? = nil) -> Int64 {
        let now = now ?? clock()
        let deadline: Int64?
        switch state {
        case let .generating(_, expiresAt):
            deadline = expiresAt
        case let .awaitingConfirmation(offer, _):
            deadline = offer.receivedAt + Self.inviteTTLMillis
        default:
            deadline = nil
        }
        guard let deadline else { return 0 }
        let remaining = deadline - now
        if remaining <= 0 { return 0 }
        return (remaining + 999) / 1000
    }

    // MARK: - Unpair / manual add

    /// Removes a pairing from the trust store. If the flow is showing that peer,
    /// it returns to `idle`.
    public func unpair(fingerprint: String) {
        trust.remove(fingerprint)
        if case let .paired(peer) = state,
           peer.fingerprint.caseInsensitiveCompare(fingerprint) == .orderedSame {
            state = .idle
        }
    }

    /// Parses a pasted pairing link. Paste-a-link only: there is deliberately no
    /// host/port overload, and the parse is the shared `PairLink` one.
    public static func parseManualLink(_ text: String) -> ManualLink? {
        guard let payload = try? PairLink.parse(text) else { return nil }
        return ManualLink(
            fingerprint: payload.fingerprint,
            name: payload.name,
            addresses: payload.addrs,
            nonce: payload.nonce
        )
    }
}

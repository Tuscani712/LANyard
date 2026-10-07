import Foundation

/// The pairing screen's states.
///
/// This is the local UI state machine around pairing, distinct from the
/// client-side `PairingFlow` (which drives a session against a peer) and the
/// responder-side `PairingSessions` (which the server owns). It models what the
/// person sees:
///
///  - `idle` — nothing in progress;
///  - `generating(invite:expiresAt:)` — this device showed its QR/link and is
///    waiting for someone to use it (2-minute validity);
///  - `awaitingConfirmation(offer:sas:)` — a peer used the invite and the person
///    must toggle permissions and confirm the SAS;
///  - `paired(PairedPeer)` — saved to the `TrustStore`;
///  - `declined` / `expired` — terminal outcomes.
public enum PairFlowState: Equatable {
    case idle
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

    /// The token/expiry this device advertised, while generating.
    public var currentInvite: (token: String, expiresAt: Int64)? {
        if case let .generating(invite, expiresAt) = state { return (invite, expiresAt) }
        return nil
    }

    private let trust: TrustStore
    private let selfFingerprint: () -> String
    private let clock: () -> Int64
    private let invites: PairInvites

    public init(
        trust: TrustStore,
        selfFingerprint: @escaping () -> String,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.trust = trust
        self.selfFingerprint = selfFingerprint
        self.clock = clock
        // The invite store must share this flow's clock, so expiry is consistent.
        self.invites = PairInvites(clock: clock)
    }

    // MARK: - Lifecycle

    /// Begins generating a fresh invite. Allowed from `idle`, `declined`,
    /// `expired`, or `paired` (re-pairing). Resets the permission toggles.
    @discardableResult
    public func start() -> (token: String, expiresAt: Int64) {
        permissions = Permissions(browse: true, push: true)
        let invite = invites.mint()
        state = .generating(invite: invite.token, expiresAt: invite.expiresAt)
        return (invite.token, invite.expiresAt)
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
    /// prompt after the 2-minute window. `now` defaults to the injected clock.
    public func tick(now: Int64? = nil) {
        let now = now ?? clock()
        switch state {
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

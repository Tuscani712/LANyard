// DevicesModels.swift — the Devices-tab and pairing view models.
//
// WRITTEN, NOT COMPILED. Guarded with `#if canImport(Combine)` (the outer guard
// the Phase-4 brief asks for); the Network.framework-backed discovery is nested
// behind `#if canImport(Network)` so this file compiles to nothing on Linux and
// `swift build`/`swift test` stay green. On a Mac/iOS toolchain it is real.
//
// These are thin `@MainActor ObservableObject` wrappers over the pure
// `LanyardCore` types — `Devices`/`DeviceRegistry` for the row list and
// `PairFlow` for pairing. They reuse the same pattern as ObservableModels.swift:
// the core stays platform-free and testable; the model republishes its state on
// the main actor for SwiftUI.
//
// The REAL core API is bound exactly:
//   Devices.build(discovered:paired:onlineFingerprints:now:selfFingerprint:)
//   Devices.selfFilter(_:selfFingerprint:)
//   DeviceRegistry(ttlMillis:removeGraceMillis:maxMisses:) { observe/miss/sweep/... }
//   PairFlow(trust:selfFingerprint:clock:) { start/offer/confirmSas/decline/tick/... }
// There is no assumed view-model surface in LanyardCore.

#if canImport(Combine)
import Foundation
import Combine
import LanyardCore

// MARK: - Devices

/// Publishes `[DeviceRow]` for the Devices tab.
///
/// Sources of truth:
///   * `DiscoveryService` (LanyardNet, Apple-only) feeds mDNS/beacon peers;
///   * `DeviceRegistry` tracks last-seen and ages peers out;
///   * the `TrustStore` supplies paired peers;
///   * `Devices.build` merges them, self-filters by certificate fingerprint and
///     orders the rows.
@MainActor
public final class DevicesModel: ObservableObject {
    @Published public private(set) var rows: [DeviceRow] = []
    @Published public private(set) var isScanning = false
    /// Set by `connect(_:)`; the Devices view presents `PairView` for it.
    @Published public var pendingConnect: DeviceRow?

    #if canImport(Network)
    /// TN3179 Local Network prompt outcome, for `LocalNetworkPermissionView`.
    @Published public private(set) var permission: LocalNetworkPermissionState = .unknown
    #endif

    /// Called by `browse(_:)`; the Phase-5 download browser attaches here.
    /// MAC-SPIKE: the shares browser UI is Phase 5, so this is a callback seam.
    public var onBrowse: ((DeviceRow) -> Void)?

    private let trust: TrustStore
    private let selfFingerprint: () -> String
    private let registry: DeviceRegistry
    private let clock: () -> Int64

    private var discovered: [DiscoveredPeer] = []
    private var lastShortIds: Set<String> = []

    #if canImport(Network)
    private var discovery: DiscoveryService?
    #endif

    public init(
        trust: TrustStore,
        selfFingerprint: @escaping () -> String,
        registry: DeviceRegistry = DeviceRegistry(),
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.trust = trust
        self.selfFingerprint = selfFingerprint
        self.registry = registry
        self.clock = clock
        refresh()
    }

    deinit {
        #if canImport(Network)
        discovery?.stop()
        #endif
    }

    // MARK: - Lifecycle

    /// Starts discovery and begins publishing rows.
    public func start() {
        #if canImport(Network)
        if discovery == nil {
            let service = DiscoveryService()
            service.onPeers = { [weak self] peers in
                Task { @MainActor in self?.apply(peers) }
            }
            service.onPermission = { [weak self] state in
                Task { @MainActor in self?.permission = state }
            }
            discovery = service
        }
        discovery?.start()
        #endif
        isScanning = true
        refresh()
    }

    public func stop() {
        #if canImport(Network)
        discovery?.stop()
        #endif
        isScanning = false
    }

    // MARK: - Actions

    /// Offers a one-off Connect with an unpaired device (spec §4.2). The Devices
    /// view presents `PairView` when `pendingConnect` is set.
    public func connect(_ row: DeviceRow) {
        guard row.connectEnabled else { return }
        pendingConnect = row
    }

    public func clearPendingConnect() {
        pendingConnect = nil
    }

    /// Opens the paired peer's shared files (spec §4.3). MAC-SPIKE: Phase 5 owns
    /// the shares browser; here it is only forwarded to `onBrowse`.
    public func browse(_ row: DeviceRow) {
        guard row.browseEnabled else { return }
        onBrowse?(row)
    }

    /// Removes a pairing and rebuilds the list.
    public func unpair(_ row: DeviceRow) {
        guard let fingerprint = row.fingerprint else { return }
        trust.remove(fingerprint)
        registry.remove(row.shortId)
        refresh()
    }

    /// Rebuilds `rows` from the current trust store, discovered peers and the
    /// online set. Safe to call at any time.
    public func refresh() {
        let paired = trust.list()
        let online = registry.onlineShortIds().union(Set(discovered.map { $0.shortId }))
        rows = Devices.build(
            discovered: Devices.selfFilter(discovered, selfFingerprint: selfFingerprint()),
            paired: paired,
            onlineFingerprints: online,
            now: clock(),
            selfFingerprint: selfFingerprint()
        )
    }

    // MARK: - Discovery

    private func apply(_ peers: [DiscoveredPeer]) {
        let now = clock()
        let current = Set(peers.map { Self.normalize($0.shortId) })

        // Peers that disappeared since the last change set get a miss; two misses
        // mark them offline and the registry sweeps them after the grace period.
        for shortId in lastShortIds.subtracting(current) {
            registry.miss(shortId, now: now)
        }
        for peer in peers {
            registry.observe(peer.shortId, now: now)
        }
        registry.sweep(now: now)

        discovered = peers
        lastShortIds = current
        refresh()
    }

    /// Ages the registry on a clock tick (call from a timer while the tab is
    /// visible so a peer that stops advertising eventually greys out).
    public func sweep() {
        registry.sweep(now: clock())
        refresh()
    }

    private static func normalize(_ shortId: String) -> String {
        shortId.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}

// MARK: - Pairing

/// Wraps `PairFlow` for SwiftUI: publishes the state machine's state, the
/// permission toggles, the invite countdown and the parsed paste/scan result.
@MainActor
public final class PairFlowModel: ObservableObject {
    @Published public private(set) var state: PairFlowState
    @Published public private(set) var permissions: Permissions
    @Published public private(set) var secondsRemaining: Int64 = 0
    /// Result of `pasteLink(_:)`; drives the "Add by link" field.
    @Published public private(set) var pasteError: String?
    @Published public private(set) var pastedLink: ManualLink?
    @Published public var pasteText: String = ""

    private let flow: PairFlow
    /// Builds the full `lanyard://pair?...` invite link from an invite token.
    /// Injected so the app owns address/port advertisement.
    private let linkBuilder: (String) -> String
    private var timer: Timer?

    public init(flow: PairFlow, linkBuilder: @escaping (String) -> String) {
        self.flow = flow
        self.linkBuilder = linkBuilder
        self.state = flow.state
        self.permissions = flow.permissions
    }

    deinit {
        timer?.invalidate()
    }

    // MARK: - Derived state

    /// The link encoded in the invite QR, while generating.
    public var inviteLink: String? {
        guard case let .generating(invite, _) = state else { return nil }
        return linkBuilder(invite)
    }

    /// The raw 6-digit SAS while awaiting confirmation.
    public var sas: String? {
        guard case let .awaitingConfirmation(_, sas) = state else { return nil }
        return sas
    }

    /// The SAS grouped for the confirmation screen, e.g. "482 913".
    public var formattedSas: String? {
        guard let sas else { return nil }
        guard sas.count == 6 else { return sas }
        let middle = sas.index(sas.startIndex, offsetBy: 3)
        return "\(sas[..<middle]) \(sas[middle...])"
    }

    public var offer: PairOffer? {
        guard case let .awaitingConfirmation(offer, _) = state else { return nil }
        return offer
    }

    public var pairedPeer: PairedPeer? {
        guard case let .paired(peer) = state else { return nil }
        return peer
    }

    /// True while a live invite or confirmation window is open.
    public var isBusy: Bool {
        switch state {
        case .generating, .awaitingConfirmation: return true
        default: return false
        }
    }

    // MARK: - Actions

    /// Mints a fresh 2-minute invite and starts the countdown.
    @discardableResult
    public func startInvite() -> String? {
        let invite = flow.start()
        refresh()
        startTimer()
        return invite.token
    }

    /// Confirms the SAS and grants the toggled permissions; the peer is saved to
    /// the trust store.
    public func confirmSas() {
        _ = flow.confirmSas()
        refresh()
        stopTimer()
    }

    public func decline() {
        _ = flow.decline()
        refresh()
        stopTimer()
    }

    public func setBrowse(_ enabled: Bool) {
        flow.setBrowse(enabled)
        refresh()
    }

    public func setPush(_ enabled: Bool) {
        flow.setPush(enabled)
        refresh()
    }

    public func unpair(fingerprint: String) {
        flow.unpair(fingerprint: fingerprint)
        refresh()
    }

    /// Parses a pasted or scanned pairing link. Paste-a-link only: no host/port
    /// overload. Returns true when the text was a valid link.
    @discardableResult
    public func pasteLink(_ text: String) -> Bool {
        guard let link = PairFlow.parseManualLink(text) else {
            pastedLink = nil
            pasteError = "That is not a LANyard pairing link."
            return false
        }
        pastedLink = link
        pasteError = nil
        pasteText = ""
        return true
    }

    public func clearPastedLink() {
        pastedLink = nil
        pasteError = nil
    }

    // MARK: - Countdown

    /// Advances the flow's clock and republishes. The view may also call this on
    /// appear; the timer calls it automatically.
    public func tick() {
        flow.tick()
        refresh()
        if !isBusy { stopTimer() }
    }

    public func refresh() {
        state = flow.state
        permissions = flow.permissions
        secondsRemaining = flow.secondsRemaining()
    }

    public func stop() {
        stopTimer()
    }

    private func startTimer() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }
}
#endif

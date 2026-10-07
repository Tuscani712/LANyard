// DiscoveryAdapters — the Network.framework discovery layer for Phase 4.
//
// WRITTEN, NOT COMPILED. Guarded with `#if canImport(Network)` so on Linux
// (where Network.framework does not exist) this file compiles to nothing and
// `swift build`/`swift test` stay green. On a Mac/iOS toolchain it is compiled
// for real.
//
// This is the "browse" half of the Devices tab:
//   * `DiscoveryService` runs an `NWBrowser` for `_lanyard._tcp`, parses each
//     result's TXT record through the REAL `DiscoveryTxt.parse(dictionary:
//     instanceName:resolvedPort:)` (the same parser the Linux tests exercise),
//     and emits `DiscoveredPeer`s. It also resolves each service's host by
//     dialing it once (Network.framework does not expose mDNS A/AAAA records).
//   * `BeaconListener` is the UDP fallback for networks that block mDNS.
//   * `LocalNetworkPermissionState` exposes TN3179's prompt outcome so the app
//     can show `LocalNetworkPermissionView`.
//
// The models that consume this (`DevicesModel`) live in DevicesModels.swift
// under `#if canImport(Combine)`; this file owns all Network.framework types so
// the callback seam between the two is plain Foundation.

#if canImport(Network)
import Foundation
import Network
import LanyardCore

// MARK: - Local Network permission (TN3179)

/// TN3179: on iOS 14+ the first `NWBrowser` (or `NWListener`) triggers the system
/// "… would like to find and connect to devices on your local network" prompt.
/// The app must declare `NSLocalNetworkUsageDescription` and
/// `NSBonjourServices = ["_lanyard._tcp"]` or the browse silently returns nothing.
public enum LocalNetworkPermissionState: Equatable, Sendable {
    /// No browse has run yet; the prompt has not been seen.
    case unknown
    /// A browse is running and the prompt may be up.
    case prompting
    /// Browse reached `.ready`: permission is granted.
    case granted
    /// The browse failed/waiting with a DNS policy error: permission was denied.
    case denied
}

/// Browses `_lanyard._tcp` and parses each result's TXT record into a
/// `DiscoveredPeer`.
///
/// MAC-SPIKE: the browse result metadata spelling (`.bonjour(service)` with
/// `service.txtRecord`) is the same one BonjourBrowser.swift already uses; confirm
/// against the current SDK (SPIKE.md step 5). MAC-SPIKE: the `NWError.dns` codes
/// used to recognise a denied Local Network prompt (kDNSServiceErr_PolicyDenied /
/// kDNSServiceErr_NoAuth) should be confirmed on-device; TN3179 does not promise
/// a stable error.
public final class DiscoveryService {
    public static let serviceType = DiscoveryTxt.serviceType // "_lanyard._tcp"
    public static let domain = DiscoveryTxt.serviceDomain     // "local."

    /// Called with the full current set whenever discovery changes. Delivered on
    /// an internal queue; `DevicesModel` marshals to the main actor.
    public var onPeers: (([DiscoveredPeer]) -> Void)?
    /// Called when the Local Network permission state changes.
    public var onPermission: ((LocalNetworkPermissionState) -> Void)?

    public private(set) var permission: LocalNetworkPermissionState = .unknown {
        didSet {
            if permission != oldValue { onPermission?(permission) }
        }
    }

    private let queue = DispatchQueue(label: "io.github.tuscani712.lanyard.discovery")
    private var browser: NWBrowser?
    /// Latest announcement per DNS-SD instance name, so a resolve can patch the
    /// host without dropping the rest of the record.
    private var announcements: [String: DiscoveredPeer] = [:]
    /// Instance names whose host is currently being resolved.
    private var resolving: Set<String> = []
    private var started = false

    public init() {}

    public func start() {
        guard !started else { return }
        started = true
        permission = .prompting

        let parameters = NWParameters()
        parameters.includePeerToPeer = false
        let browser = NWBrowser(
            for: .bonjourWithTXTRecord(type: Self.serviceType, domain: Self.domain),
            using: parameters
        )
        browser.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.permission = .granted
            case .failed(let error):
                if Self.isPolicyDenied(error) { self.permission = .denied }
                NSLog("LANyard discovery failed: %@", String(describing: error))
            case .waiting(let error):
                // `.waiting` is normal while the prompt is up; a DNS policy error
                // means the person tapped "Don't Allow".
                if Self.isPolicyDenied(error) { self.permission = .denied }
            default:
                break
            }
        }
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            self?.handle(results)
        }
        self.browser = browser
        browser.start(queue: queue)
    }

    public func stop() {
        browser?.cancel()
        browser = nil
        started = false
        announcements.removeAll()
        resolving.removeAll()
    }

    // MARK: - Result handling

    private func handle(_ results: Set<NWBrowser.Result>) {
        var current: [String: DiscoveredPeer] = [:]
        for result in results {
            guard let (name, parsed) = Self.parse(result) else { continue }
            var peer = DiscoveredPeer(
                shortId: parsed.shortId,
                name: parsed.name,
                os: parsed.os,
                deviceLabel: parsed.deviceLabel,
                host: "",
                port: parsed.port,
                lastSeen: Self.nowMillis()
            )
            // Keep an already-resolved host across browse updates.
            if let existing = announcements[name], !existing.host.isEmpty {
                peer.host = existing.host
            }
            current[name] = peer
        }
        announcements = current
        emit()

        for (name, peer) in current where peer.host.isEmpty && !resolving.contains(name) {
            resolving.insert(name)
            resolve(name: name) { [weak self] host in
                guard let self else { return }
                self.resolving.remove(name)
                guard let host, var known = self.announcements[name] else { return }
                known.host = host
                known.lastSeen = Self.nowMillis()
                self.announcements[name] = known
                self.emit()
            }
        }
    }

    private func emit() {
        onPeers?(Array(announcements.values))
    }

    /// Parses one browse result through the real `DiscoveryTxt` parser.
    ///
    /// MAC-SPIKE: `NWBrowser.Result` does not expose the service port, so the
    /// `resolvedPort` fallback is `nil` and the TXT `p` key must be present for
    /// the record to be usable (`DiscoveryTxt.Parsed.isUsable`). If a platform
    /// omits `p`, feed the resolved port here instead.
    static func parse(_ result: NWBrowser.Result) -> (name: String, parsed: DiscoveryTxt.Parsed)? {
        guard case let .service(name, _, _, _) = result.endpoint else { return nil }
        var dictionary: [String: String] = [:]
        if case let .bonjour(service) = result.metadata, let record = service.txtRecord {
            dictionary = DiscoveryTxt.parseRecord(record)
        }
        guard let parsed = DiscoveryTxt.parse(dictionary: dictionary, instanceName: name, resolvedPort: nil),
              parsed.isUsable else {
            return nil
        }
        return (name, parsed)
    }

    /// Resolves a service's host by dialing it once. Network.framework does not
    /// expose the mDNS A/AAAA records directly; the remote address appears on the
    /// connection's current path.
    ///
    /// MAC-SPIKE: this dials every discovered peer (the peer's TLS listener sees
    /// and drops a non-TLS connection). A production build should reuse the
    /// address from the connection it was going to open anyway (SPIKE.md step 5).
    private func resolve(name: String, completion: @escaping (String?) -> Void) {
        let endpoint = NWEndpoint.service(
            name: name,
            type: Self.serviceType,
            domain: Self.domain,
            interface: nil
        )
        let connection = NWConnection(to: endpoint, using: .tcp)
        let lock = NSLock()
        var reported = false
        func report(_ host: String?) {
            lock.lock()
            if reported { lock.unlock(); return }
            reported = true
            lock.unlock()
            connection.cancel()
            completion(host)
        }
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                report(Self.host(of: connection.currentPath?.remoteEndpoint))
            case .failed, .cancelled:
                report(nil)
            default:
                break
            }
        }
        connection.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 3) { report(nil) }
    }

    private static func host(of endpoint: NWEndpoint?) -> String? {
        guard case let .hostPort(host, _) = endpoint else { return nil }
        // IPv6 link-local descriptions may carry a "%en0" zone; PairLink/connect
        // want the bare address.
        return "\(host)".split(separator: "%").first.map(String.init)
    }

    /// Recognises a denied Local Network prompt. TN3179 does not guarantee a
    /// specific code, so both the policy-denied and no-auth DNS errors are
    /// treated as denial.
    static func isPolicyDenied(_ error: NWError) -> Bool {
        if case let .dns(code) = error {
            return code == -65570 || code == -65555
        }
        return false
    }

    static func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

// MARK: - UDP beacon fallback

/// Listens for the Go desktop's UDP broadcast beacon (`config.BeaconPort`, 47801;
/// `internal/discovery/beacon.go`) and parses each datagram with the real
/// `BeaconMessage.parse`.
///
/// MAC-SPIKE: the Go beacon is a **broadcast** datagram, and Network.framework's
/// support for receiving IPv4 broadcast via `NWListener(using: .udp, ...)` is not
/// guaranteed. This is the intended wiring; confirm on-device and fall back to a
/// BSD socket + `SO_BROADCAST`/`SO_REUSEPORT` if broadcast datagrams never
/// arrive. MAC-SPIKE: probe replies are **not** sent yet — the Go side replies to
/// `"probe"` and this listener only consumes. Sending needs the local bound port
/// and the sender's address, which is a later phase.
public final class BeaconListener {
    /// Matches Go `config.BeaconPort`.
    public static let port: UInt16 = 47801

    /// Called for each parsed datagram: (announcement, sender host).
    public var onAnnouncement: ((DiscoveryTxt.Parsed, String) -> Void)?
    public var onStateChange: ((NWListener.State) -> Void)?

    private let queue = DispatchQueue(label: "io.github.tuscani712.lanyard.beacon")
    private var listener: NWListener?

    public init() {}

    public func start() {
        guard listener == nil else { return }
        do {
            let parameters = NWParameters.udp
            parameters.allowLocalEndpointReuse = true
            let listener = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: Self.port) ?? .any)
            listener.stateUpdateHandler = { [weak self] state in
                self?.onStateChange?(state)
                if case .failed(let error) = state {
                    NSLog("LANyard beacon listener failed: %@", String(describing: error))
                }
            }
            // Each inbound broadcast source yields a connection carrying its
            // datagrams.
            listener.newConnectionHandler = { [weak self] connection in
                self?.receive(on: connection)
            }
            listener.start(queue: queue)
            self.listener = listener
        } catch {
            NSLog("LANyard beacon listener unavailable: %@", String(describing: error))
        }
    }

    public func stop() {
        listener?.cancel()
        listener = nil
    }

    private func receive(on connection: NWConnection) {
        connection.start(queue: queue)
        connection.receiveMessage { [weak self] data, _, _, error in
            guard let self else { return }
            if let data, let message = BeaconMessage.parse(data) {
                let host = Self.host(of: connection.currentPath?.remoteEndpoint) ?? ""
                self.onAnnouncement?(message.announcement, host)
            }
            if error == nil {
                self.receive(on: connection)
            } else {
                connection.cancel()
            }
        }
    }

    private static func host(of endpoint: NWEndpoint?) -> String? {
        guard case let .hostPort(host, _) = endpoint else { return nil }
        return "\(host)".split(separator: "%").first.map(String.init)
    }
}
#endif

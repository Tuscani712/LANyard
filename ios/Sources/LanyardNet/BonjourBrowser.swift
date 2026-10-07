// BonjourBrowser — discovers peers advertised as `_lanyard._tcp` and reads
// their TXT records (`v,id,did,n,os,p`), matching `internal/discovery/mdns.go`.
#if canImport(Network)
import Foundation
import Network

/// One discovered LANyard node.
struct LanyardAnnouncement: Equatable {
    var shortId: String = ""
    var deviceLabel: String = ""
    var name: String = ""
    var os: String = ""
    var port: Int = 0
    var version: String = ""
    /// Filled in by `BonjourBrowser.resolve` once the service is dialed.
    var addresses: [String] = []
}

/// Browses `_lanyard._tcp` on the local domain and parses each result's TXT
/// record. Results are delivered on `onResults` (main queue).
///
/// **Local Network permission (TN3179).** On iOS 14+ the first browse (or
/// listen) triggers the system "… would like to find and connect to devices on
/// your local network" prompt. The app must declare:
///   - `NSLocalNetworkUsageDescription` — the prompt text, and
///   - `NSBonjourServices = ["_lanyard._tcp"]` — else the browse silently fails.
/// On macOS the app must additionally have the `com.apple.security.network.client`
/// entitlement, and the person must approve the prompt.
///
/// MAC-SPIKE: confirm the `NWBrowser.Result.metadata` spelling for the TXT
/// record (`.bonjour(let service)` with `service.txtRecord`) against the current
/// SDK; see SPIKE.md step 5.
final class BonjourBrowser {
    static let serviceType = "_lanyard._tcp"
    static let domain = "local."

    /// Called with the current set whenever discovery changes.
    var onResults: (([LanyardAnnouncement]) -> Void)?

    private let queue = DispatchQueue(label: "io.github.tuscani712.lanyard.bonjour")
    private var browser: NWBrowser?
    private var latest: [LanyardAnnouncement] = []

    init() {
        let parameters = NWParameters()
        parameters.includePeerToPeer = false
        browser = NWBrowser(
            for: .bonjourWithTXTRecord(type: Self.serviceType, domain: Self.domain),
            using: parameters
        )
    }

    func start() {
        guard let browser else { return }
        browser.stateUpdateHandler = { state in
            // .waiting is normal while permission/route settles; surface failures
            // for diagnostics.
            if case .failed(let error) = state {
                NSLog("LANyard Bonjour browse failed: %@", String(describing: error))
            }
        }
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else { return }
            self.latest = results.compactMap { Self.announcement(from: $0) }
            self.onResults?(self.latest)
        }
        browser.start(queue: queue)
    }

    func stop() {
        browser?.cancel()
        browser = nil
    }

    /// Parses one browse result. The instance name is the advertised short id.
    static func announcement(from result: NWBrowser.Result) -> LanyardAnnouncement? {
        guard case let .service(name, _, _, _) = result.endpoint else { return nil }
        var announcement = LanyardAnnouncement()
        var txt: [String: String] = [:]
        if case let .bonjour(service) = result.metadata, let record = service.txtRecord {
            txt = parseTXT(record)
        }
        announcement.shortId = txt["id"] ?? name
        announcement.deviceLabel = txt["did"] ?? ""
        announcement.name = txt["n"] ?? ""
        announcement.os = txt["os"] ?? ""
        announcement.version = txt["v"] ?? ""
        announcement.port = txt["p"].flatMap(Int.init) ?? 0
        // Go accepts a record only when the TXT instance matches the id and the
        // protocol version matches (`parseTXT`), so mirror that filter.
        guard announcement.shortId == name else { return nil }
        return announcement
    }

    /// Resolves a discovered service's IP addresses by dialing it once. Network
    /// .framework does not expose the mDNS A/AAAA records directly; the remote
    /// address appears on the connection's current path.
    func resolve(_ announcement: LanyardAnnouncement, timeout: TimeInterval = 3, completion: @escaping ([String]) -> Void) {
        let endpoint = NWEndpoint.service(name: announcement.shortId, type: Self.serviceType,
                                          domain: Self.domain, interface: nil)
        // A plain TCP connect is enough to make mDNS resolve A/AAAA; the peer's
        // TLS listener will reject the non-TLS bytes, but the remote address is
        // already known by then. This is intrusive (it dials every peer), so a
        // production build should instead read addresses from the connection it
        // was going to open anyway. MAC-SPIKE: SPIKE.md step 5.
        let connection = NWConnection(to: endpoint, using: .tcp)
        let finished = DispatchSemaphore(value: 0)
        let lock = NSLock()
        var addresses: [String] = []
        var reported = false

        func report(_ result: [String]) {
            lock.lock()
            if reported { lock.unlock(); return }
            reported = true
            lock.unlock()
            connection.cancel()
            completion(result)
            finished.signal()
        }

        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                if let remote = connection.currentPath?.remoteEndpoint,
                   case let .hostPort(host, _) = remote {
                    report(["\(host)"])
                } else {
                    report([])
                }
            case .failed, .cancelled:
                report([])
            default:
                break
            }
        }
        connection.start(queue: queue)
        queue.asyncAfter(deadline: .now() + timeout) {
            lock.lock(); let already = reported; lock.unlock()
            if !already { report([]) }
        }
    }

    /// Parses a raw DNS-SD TXT record (length-prefixed `key=value` strings).
    static func parseTXT(_ data: Data) -> [String: String] {
        var out: [String: String] = [:]
        let bytes = [UInt8](data)
        var index = 0
        while index < bytes.count {
            let length = Int(bytes[index])
            index += 1
            guard index + length <= bytes.count else { break }
            let entry = String(decoding: bytes[index..<(index + length)], as: UTF8.self)
            index += length
            guard let separator = entry.firstIndex(of: "=") else { continue }
            let key = String(entry[entry.startIndex..<separator])
            let value = String(entry[entry.index(after: separator)...])
            out[key] = value
        }
        return out
    }
}
#endif

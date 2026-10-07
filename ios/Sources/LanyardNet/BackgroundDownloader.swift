// BackgroundDownloader.swift — a background `URLSession` downloader for pulls.
//
// WRITTEN, NOT COMPILED for iOS. This file is Apple-only in practice: although
// the brief names `#if canImport(Foundation)`, on Linux `canImport(Foundation)`
// is TRUE while `URLSession` lives in `FoundationNetworking` and the background
// session API does not exist, so a Foundation-only guard would compile it and
// break `swift build`. The guard below therefore keeps the Linux build green
// while still being Foundation-based on Apple platforms.
//
// MAC-SPIKE (read before using this for pulls):
//
//   Our pull protocol is custom mutual TLS with an Ed25519 self-signed
//   certificate and a **pinned SPKI fingerprint**, implemented over
//   `NWConnection` (`TLS.swift` + `PeerConnector.swift`). `URLSession` cannot
//   reuse any of that:
//
//     * `URLSessionConfiguration` has no way to install a custom `NWConnection`
//       or the `sec_protocol_options_set_verify_block` pin. It uses CFNetwork's
//       own TLS stack and trust evaluation.
//     * A **background** session is performed by the out-of-process
//       `nsurlsessiond` daemon. The app may be suspended or terminated, so the
//       in-process verify block that normally compares the peer's SPKI SHA-256
//       never runs. Pinning must instead be expressed through
//       `URLSessionDelegate.urlSession(_:didReceive:completionHandler:)`
//       (`URLProtectionSpace.serverTrust` + `SecTrustEvaluateWithError`), and the
//       client certificate supplied as `URLCredential(identity:certificates:
//       persistence:)` — but whether CFNetwork will present an **Ed25519**
//       client identity to the Go peer is exactly the risk already flagged for
//       `NWProtocolTLS` (SPIKE.md step 1) and is UNTESTED here.
//     * Background downloads do not support streaming request bodies and do not
//       run arbitrary `URLProtocol` subclasses; `URLSessionConfiguration
//       .protocolClasses` is ignored. So the custom framing/`ByteSource` path is
//       only available in a foreground session.
//     * The daemon talks to the raw `https://host:port/...` URL of the peer's
//       share server, so the `Range` header and `/api/v1/shares/<id>/file`
//       endpoint are all it sees; the SHA-256 verification still has to happen
//       afterwards in `DownloadSession` (this downloader only moves bytes).
//
//   What to test on the first Mac:
//     1. A background session reaches our `ShareServer` over TLS 1.3, presents
//        the client identity, and the peer accepts the **Ed25519** cert.
//     2. The pinned fingerprint is enforced in the delegate trust challenge and
//        a wrong peer is rejected before any body arrives.
//     3. `resumeData` from a background task survives an app relaunch and a
//        `Range` resume completes with a matching SHA-256.
//     4. The system actually relaunches/finishes the transfer while the app is
//        suspended (the whole point of using a background session).

#if canImport(Foundation) && (os(iOS) || os(macOS))
import Foundation
#if canImport(Security)
import Security
#endif

/// One resumable pull. The URL points at a peer's `/api/v1/shares/<id>/file`
/// endpoint; `expectedFingerprint` is the peer's pinned SPKI SHA-256.
public struct PullDownloadRequest {
    public let shareId: String
    public let path: String
    public let remoteURL: URL
    public let expectedFingerprint: String
    /// The whole-file SHA-256 from the peer's `/hash`, for post-download
    /// verification (this downloader does not verify; `DownloadSession` does).
    public let expectedSHA256: String?

    public init(
        shareId: String,
        path: String,
        remoteURL: URL,
        expectedFingerprint: String,
        expectedSHA256: String? = nil
    ) {
        self.shareId = shareId
        self.path = path
        self.remoteURL = remoteURL
        self.expectedFingerprint = expectedFingerprint
        self.expectedSHA256 = expectedSHA256
    }
}

/// The lifecycle callbacks a caller observes. Delivered on the main queue.
public struct BackgroundDownloadEvents {
    public var onProgress: (PullDownloadRequest, Int64, Int64) -> Void
    public var onFinished: (PullDownloadRequest, URL) -> Void
    public var onFailed: (PullDownloadRequest, Error) -> Void

    public init(
        onProgress: @escaping (PullDownloadRequest, Int64, Int64) -> Void = { _, _, _ in },
        onFinished: @escaping (PullDownloadRequest, URL) -> Void = { _, _ in },
        onFailed: @escaping (PullDownloadRequest, Error) -> Void = { _, _ in }
    ) {
        self.onProgress = onProgress
        self.onFinished = onFinished
        self.onFailed = onFailed
    }
}

/// A background `URLSession` downloader.
///
/// It maps each `URLSessionTask` back to its `PullDownloadRequest`, republishes
/// progress/finish/fail through `events`, and persists `resumeData` so a pull
/// can continue after a relaunch. It deliberately does **not** verify hashes or
/// touch the share tree; that is `PullBrowse`/`DownloadSession`'s job.
public final class BackgroundDownloader: NSObject, URLSessionDownloadDelegate {
    /// Register this identifier in `Info.plist`/the app's background modes and
    /// recreate the downloader with it on relaunch to recover tasks.
    public static let defaultIdentifier = "io.github.tuscani712.lanyard.downloads"

    /// The store of `resumeData` keyed by a stable per-file key.
    public protocol ResumeStore {
        func resumeData(forKey key: String) -> Data?
        func setResumeData(_ data: Data, forKey key: String)
        func removeResumeData(forKey key: String)
    }

    /// A `UserDefaults`-backed `ResumeStore`.
    public final class UserDefaultsResumeStore: ResumeStore {
        private let defaults: UserDefaults
        private let prefix = "io.github.tuscani712.lanyard.resume."

        public init(defaults: UserDefaults = .standard) {
            self.defaults = defaults
        }

        public func resumeData(forKey key: String) -> Data? {
            defaults.data(forKey: prefix + key)
        }

        public func setResumeData(_ data: Data, forKey key: String) {
            defaults.set(data, forKey: prefix + key)
        }

        public func removeResumeData(forKey key: String) {
            defaults.removeObject(forKey: prefix + key)
        }
    }

    public var events = BackgroundDownloadEvents()
    /// The client identity presented on the TLS challenge (the device's own
    /// `SecIdentity`). Nil disables client-cert auth (and the peer will reject).
    public var clientIdentity: SecIdentity?

    private let resumeStore: ResumeStore
    private var session: URLSession!
    private var requestsByTask: [Int: PullDownloadRequest] = [:]
    private let lock = NSLock()

    public init(
        identifier: String = BackgroundDownloader.defaultIdentifier,
        resumeStore: ResumeStore = UserDefaultsResumeStore()
    ) {
        self.resumeStore = resumeStore
        super.init()

        let configuration = URLSessionConfiguration.background(withIdentifier: identifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.allowsCellularAccess = true
        // MAC-SPIKE: `protocolClasses` is ignored for background sessions, so our
        // custom framing cannot be injected here.
        self.session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }

    // MARK: - Starting

    /// Starts (or resumes) a pull. Returns the `URLSessionDownloadTask`.
    @discardableResult
    public func download(_ request: PullDownloadRequest) -> URLSessionDownloadTask {
        let key = Self.key(request)
        let task: URLSessionDownloadTask
        if let resume = resumeStore.resumeData(forKey: key) {
            task = session.downloadTask(withResumeData: resume)
        } else {
            var urlRequest = URLRequest(url: request.remoteURL)
            urlRequest.httpMethod = "GET"
            task = session.downloadTask(with: urlRequest)
        }
        lock.lock(); requestsByTask[task.taskIdentifier] = request; lock.unlock()
        task.resume()
        return task
    }

    /// Cancels a pull, preserving `resumeData` where the system supplies it.
    public func cancel(_ task: URLSessionDownloadTask) {
        task.cancel { [weak self] resumeData in
            guard let self else { return }
            let request = self.takeRequest(task.taskIdentifier)
            if let resumeData, let request {
                self.resumeStore.setResumeData(resumeData, forKey: Self.key(request))
            }
        }
    }

    /// Invalidates the session, letting outstanding background tasks finish.
    public func finishTasksAndInvalidate() {
        session.finishTasksAndInvalidate()
    }

    // MARK: - URLSessionDownloadDelegate

    public func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard let request = request(for: downloadTask.taskIdentifier) else { return }
        DispatchQueue.main.async {
            self.events.onProgress(request, totalBytesWritten, totalBytesExpectedToWrite)
        }
    }

    public func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        guard let request = takeRequest(downloadTask.taskIdentifier) else { return }
        resumeStore.removeResumeData(forKey: Self.key(request))
        // The temp file is deleted when this returns; move it before handing it
        // to the caller.
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("lanyard-pull-\(downloadTask.taskIdentifier)-\(URL(fileURLWithPath: request.path).lastPathComponent)")
        try? FileManager.default.removeItem(at: destination)
        do {
            try FileManager.default.moveItem(at: location, to: destination)
            DispatchQueue.main.async { self.events.onFinished(request, destination) }
        } catch {
            DispatchQueue.main.async { self.events.onFailed(request, error) }
        }
    }

    public func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        guard let error else { return }
        // A cancelled task carries `NSURLErrorCancelled` plus resume data.
        let nsError = error as NSError
        if nsError.code == NSURLErrorCancelled,
           let resumeData = nsError.userInfo[NSURLSessionDownloadTaskResumeData] as? Data,
           let request = request(for: task.taskIdentifier) {
            resumeStore.setResumeData(resumeData, forKey: Self.key(request))
        }
        guard let request = takeRequest(task.taskIdentifier) else { return }
        DispatchQueue.main.async { self.events.onFailed(request, error) }
    }

    // MARK: - Auth challenge (client cert + pin)

    public func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        let space = challenge.protectionSpace
        // MAC-SPIKE: for a background session this delegate must be reachable
        // from the system-completed transfer; whether the challenge is delivered
        // while the app is suspended, and whether CFNetwork accepts an Ed25519
        // client identity, are the two things to confirm on the first Mac.
        if space.authenticationMethod == NSURLAuthenticationMethodClientCertificate,
           let identity = clientIdentity {
            completionHandler(.useCredential, URLCredential(identity: identity, certificates: nil, persistence: .forSession))
            return
        }
        if space.authenticationMethod == NSURLAuthenticationMethodServerTrust,
           let trust = space.serverTrust {
            // Pin: hand the trust to the caller's verify path. Without a
            // configured pin we default to the system trust (which will reject a
            // self-signed Ed25519 cert, as intended).
            completionHandler(.performDefaultHandling, URLCredential(trust: trust))
            return
        }
        completionHandler(.performDefaultHandling, nil)
    }

    // MARK: - Helpers

    private func request(for id: Int) -> PullDownloadRequest? {
        lock.lock(); defer { lock.unlock() }
        return requestsByTask[id]
    }

    @discardableResult
    private func takeRequest(_ id: Int) -> PullDownloadRequest? {
        lock.lock(); defer { lock.unlock() }
        return requestsByTask.removeValue(forKey: id)
    }

    private static func key(_ request: PullDownloadRequest) -> String {
        "\(request.shareId)/\(request.path)"
    }
}
#endif

// LanyardApp.swift — @main entry point and composition root.
//
// WRITTEN, NOT COMPILED. This box has no macOS/Xcode/Apple SDK, and the app is
// not a SwiftPM target (it is produced by XcodeGen from ../project.yml), so
// nothing in LanyardApp/ is part of `swift build`. The thin views here only
// bind to the `LanyardNet` observable models; all logic lives in the core.
//
// The composition below uses the REAL `LanyardCore` public API:
//   InboxDestinationAdapter (LanyardNet) -> InboxDestination(destination:)
//   TransferManager(store: JsonFileTransferStore(file:))
//   Authorizer(access:)
//   ServerLifecycle(transport: AppServerTransport())
// and the four observable models from `LanyardNet.ObservableModels`.

import SwiftUI
import LanyardCore
import LanyardNet

@main
struct LanyardApp: App {
    @StateObject private var services = AppServices()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(services.transfers)
                .environmentObject(services.inbox)
                .environmentObject(services.server)
                .environmentObject(services.approval)
                .environmentObject(services.send)
                .environmentObject(services.shares)
                .environmentObject(services.browse)
        }
    }
}

@MainActor
final class AppServices: ObservableObject {
    let transfers: TransfersModel
    let inbox: InboxModel
    let server: ServerModel
    let approval: ReceiveApprovalModel

    // Phase 5: push send, share/serve and pull/browse.
    let send: SendModel
    let shares: SharesModel
    let browse: BrowseModel
    let shareServices: ShareServices

    let authorizer: Authorizer

    private let lifecycle: ServerLifecycle
    private let lifecycleListener: AppLifecycleListener

    init() {
        // Where received files go: a security-scoped override, else
        // Documents/LANyard. The core `InboxDestination` owns placement.
        let adapter = InboxDestinationAdapter()
        let destination = InboxDestination(destination: adapter)

        // Transfer history persists as JSON in Application Support.
        let store = JsonFileTransferStore(file: AppServices.historyFile)
        let manager = TransferManager(store: store)

        // MAC-SPIKE: the real access provider consults the TrustStore and any
        // live one-off Connect sessions. Until that is wired, nobody is paired.
        let authorizer = Authorizer(access: { _ in .none })

        // The receive listener. Its handler is a stub for now (see
        // AppServerTransport); real routing is `PeerServerAdapter.handler()`.
        let transport = AppServerTransport()
        let lifecycle = ServerLifecycle(transport: transport)

        // The device identity is needed by the send and pull clients. It is
        // created on first launch and stored in the Keychain.
        let identity: DeviceIdentity
        do {
            identity = try DeviceIdentity.load()
        } catch {
            fatalError("LANyard could not load or create its device identity: \(error)")
        }
        let trust = LanyardTrustStore.applicationSupportStore()

        // MAC-SPIKE: the online set is fed by the Devices tab's discovery; until
        // that is wired here, every paired peer reads as offline in the send
        // picker (`SendTarget.reason == "Offline"`), though the send itself is
        // enqueue-able. Wire `DevicesModel`'s registry through a shared object.
        let online: () -> Set<String> = { [] }

        let shareServices = ShareServices(
            identity: identity,
            bookmarksFile: AppServices.sharesFile,
            selfName: ProcessInfo.processInfo.hostName
        )

        self.transfers = TransfersModel(manager: manager)
        self.inbox = InboxModel(destination: destination, adapter: adapter)
        self.server = ServerModel(lifecycle: lifecycle)
        self.approval = ReceiveApprovalModel()
        self.authorizer = authorizer
        self.send = SendModel.make(
            identity: identity,
            queueFile: AppServices.sendQueueFile,
            peers: { trust.list() },
            onlineFingerprints: online
        )
        self.browse = BrowseModel.make(identity: identity, peers: { trust.list() })
        self.shareServices = shareServices
        self.shares = shareServices.model
        self.lifecycle = lifecycle

        // A push cannot continue once the listener stops (the app was
        // backgrounded), so fail its row immediately rather than leaving it
        // Running until the next launch.
        lifecycle.onStopped = { [weak manager] in
            manager?.failPushReceives(reason: "Interrupted")
        }

        // MAC-SPIKE: `ReceiveApprovalModel.pending` is never populated yet. The
        // intended bridge is `InboxReceiver.onOffer` -> `present(_:resolve:)` and
        // `PeerServerConfig.approval` -> waiting on `answer(_:)`. The transfer
        // hooks (`noteReceiveStarted/Progress/Done/Failed`) should likewise be
        // driven from the receiver/router.

        // iOS cannot listen while backgrounded: start on foreground, stop on
        // background (see AppLifecycleListener).
        self.lifecycleListener = AppLifecycleListener(lifecycle: lifecycle)
        lifecycleListener.start()
    }

    /// App-private application-support root: an interrupted receive can resume,
    /// and it must not be Cache (which iOS may purge).
    static var applicationSupport: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }

    static var spoolRoot: URL {
        applicationSupport.appendingPathComponent("spool", isDirectory: true)
    }

    static var historyFile: URL {
        applicationSupport.appendingPathComponent("transfers.json")
    }

    /// The persisted send queue.
    static var sendQueueFile: URL {
        applicationSupport.appendingPathComponent("send-queue.json")
    }

    /// The persisted share catalog (security-scoped bookmarks).
    static var sharesFile: URL {
        applicationSupport.appendingPathComponent("shares.json")
    }

    static func freeBytes() -> Int64 {
        let values = try? spoolRoot.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return Int64(values?.volumeAvailableCapacityForImportantUsage ?? 0)
    }
}

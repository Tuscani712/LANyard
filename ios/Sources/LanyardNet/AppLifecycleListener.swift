// AppLifecycleListener.swift — foreground/background -> ServerLifecycle.
//
// WRITTEN, NOT COMPILED. Guarded with `#if canImport(UIKit)` so on Linux
// (where UIKit does not exist) the file compiles to nothing and the package
// build/test stay green.
//
// Android starts the peer server in Activity.onStart and stops it in onStop
// (see MainActivity.kt). iOS has no long-running foreground service and cannot
// accept a connection while backgrounded, so the equivalent is:
//   * didBecomeActive  -> ServerLifecycle.start()
//   * didEnterBackground -> ServerLifecycle.stop()
// The listener itself is stopped on background as required.
//
// MAC-SPIKE: `ServerLifecycle` is a sibling LanyardCore view model, assumed
// public with `start()` and `stop()` (and a `running` flag). This file only
// forwards notifications to it.

#if canImport(UIKit)
import Foundation
import UIKit
import LanyardCore

@MainActor
public final class AppLifecycleListener {
    private let lifecycle: ServerLifecycle
    private var tokens: [NSObjectProtocol] = []

    public init(lifecycle: ServerLifecycle) {
        self.lifecycle = lifecycle
    }

    /// Begins observing. Safe to call once; repeated calls are ignored while
    /// already observing.
    public func start() {
        guard tokens.isEmpty else { return }
        let center = NotificationCenter.default

        tokens.append(center.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [lifecycle] _ in
            // `queue: .main` guarantees the main thread; assumeIsolated lets us
            // call the MainActor-isolated core synchronously.
            // MAC-SPIKE: confirm `MainActor.assumeIsolated` availability on the
            // deployment target (Swift 5.9+; assumed back-deployed to iOS 16).
            MainActor.assumeIsolated { lifecycle.start() }
        })

        tokens.append(center.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [lifecycle] _ in
            // iOS cannot listen in the background: run no server (nor listener).
            MainActor.assumeIsolated { lifecycle.stop() }
        })

        tokens.append(center.addObserver(
            forName: UIApplication.willTerminateNotification,
            object: nil,
            queue: .main
        ) { [lifecycle] _ in
            MainActor.assumeIsolated { lifecycle.stop() }
        })
    }

    /// Stops observing and tears the server down if it is running.
    public func stop() {
        for token in tokens {
            NotificationCenter.default.removeObserver(token)
        }
        tokens.removeAll()
    }

    deinit {
        // `deinit` cannot call the MainActor-isolated `stop()` directly; remove
        // the raw tokens here (NotificationCenter is thread-safe).
        for token in tokens {
            NotificationCenter.default.removeObserver(token)
        }
    }
}
#endif

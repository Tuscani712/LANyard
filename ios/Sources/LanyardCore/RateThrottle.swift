import Foundation

/// A pace control for a streaming loop: call `pace` after emitting `bytes`.
protocol Throttle: AnyObject {
    func pace(_ bytes: Int)
}

/// A `Throttle` that does nothing (unlimited).
final class NoThrottle: Throttle, Sendable {
    static let shared = NoThrottle()
    private init() {}
    func pace(_ bytes: Int) {}
}

enum Bandwidth {
    /// Anything above this is treated as unlimited, so a bad setting can't hang.
    static let MAX_MBPS = 10_000

    static func clampMBps(_ mbps: Int) -> Int {
        min(max(mbps, 0), MAX_MBPS)
    }

    static func bytesPerSecond(_ mbps: Int) -> Int64 {
        Int64(clampMBps(mbps)) * 1024 * 1024
    }
}

/// A token-bucket `Throttle`. Tokens refill at `rateBytesPerSecond` up to a small
/// burst capacity; `pace` sleeps until the emitted bytes are paid for, so a
/// streaming loop runs at roughly the requested rate. The clock and sleep are
/// injectable so a test can measure the achieved rate without real waiting.
final class RateThrottle: Throttle {
    private let rateBytesPerSecond: Int64
    private let nanoTime: () -> Int64
    private let sleep: (Int64) -> Void
    private let capacity: Int64
    private var tokens: Double
    private var lastNanos: Int64 = 0
    private var started = false

    init(
        rateBytesPerSecond: Int64,
        nanoTime: @escaping () -> Int64 = { Int64(ProcessInfo.processInfo.systemUptime * 1_000_000_000) },
        sleep: @escaping (Int64) -> Void = { ms in Thread.sleep(forTimeInterval: Double(ms) / 1000.0) }
    ) {
        self.rateBytesPerSecond = rateBytesPerSecond
        self.nanoTime = nanoTime
        self.sleep = sleep
        self.capacity = min(max(rateBytesPerSecond / 8, 1), 64 * 1024)
        self.tokens = Double(capacity)
    }

    func pace(_ bytes: Int) {
        if rateBytesPerSecond <= 0 { return }
        let now = nanoTime()
        if !started {
            lastNanos = now
            started = true
        }
        tokens = min(Double(capacity), tokens + Double(now - lastNanos) / 1e9 * Double(rateBytesPerSecond))
        lastNanos = now

        tokens -= Double(bytes)
        while tokens < 0 {
            let waitNanos = max(Int64(-tokens / Double(rateBytesPerSecond) * 1e9), 1_000_000)
            sleep(waitNanos / 1_000_000)
            let after = nanoTime()
            tokens = min(Double(capacity), tokens + Double(after - lastNanos) / 1e9 * Double(rateBytesPerSecond))
            lastNanos = after
        }
    }

    /// Builds a throttle for a MB/s setting; 0 or below yields `NoThrottle`.
    static func fromMBps(
        _ mbps: Int,
        nanoTime: @escaping () -> Int64 = { Int64(ProcessInfo.processInfo.systemUptime * 1_000_000_000) },
        sleep: @escaping (Int64) -> Void = { ms in Thread.sleep(forTimeInterval: Double(ms) / 1000.0) }
    ) -> Throttle {
        let bps = Bandwidth.bytesPerSecond(mbps)
        return bps <= 0 ? NoThrottle.shared : RateThrottle(rateBytesPerSecond: bps, nanoTime: nanoTime, sleep: sleep)
    }
}

import XCTest
@testable import LanyardCore

final class RateThrottleTests: XCTestCase {
    func testPaceRunsAtRoughlyTheRequestedRate() {
        var virtualNanos: Int64 = 0
        let throttle = RateThrottle(
            rateBytesPerSecond: 1_000_000,
            nanoTime: { virtualNanos },
            sleep: { ms in virtualNanos += ms * 1_000_000 }
        )

        let chunk = 32 * 1024
        var sent: Int64 = 0
        for _ in 0..<64 {
            throttle.pace(chunk)
            sent += Int64(chunk)
        }

        let seconds = Double(virtualNanos) / 1e9
        let achieved = Double(sent) / seconds
        XCTAssertTrue(achieved >= 850_000.0 && achieved <= 1_150_000.0, "achieved=\(achieved) bytes/s over \(seconds) s")
    }

    func testZeroAndClamps() {
        XCTAssertTrue(RateThrottle.fromMBps(0) === NoThrottle.shared)
        XCTAssertTrue(RateThrottle.fromMBps(-5) === NoThrottle.shared)
        XCTAssertEqual(Bandwidth.MAX_MBPS, Bandwidth.clampMBps(99_999_999))
        XCTAssertEqual(0, Bandwidth.clampMBps(-1))
        XCTAssertEqual(5, Bandwidth.clampMBps(5))
    }
}

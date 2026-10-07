import XCTest
@testable import LanyardCore

// Ports the lifecycle behaviour of `PeerService.start`/`stop` (start while
// foregrounded, stop when backgrounded, and `TransferManager.failPushReceives`
// on stop). No Kotlin unit test exists for `PeerService`, so these cases are
// newly written from the documented behaviour and the Kotlin source.

private final class FakeTransport: ServerTransport {
    var startCalls = 0
    var stopCalls = 0
    var portToReturn = 4040
    var errorToThrow: Error?

    func startListening() throws -> Int {
        startCalls += 1
        if let errorToThrow { throw errorToThrow }
        return portToReturn
    }

    func stopListening() {
        stopCalls += 1
    }
}

private struct StubError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

private final class MemoryTransferStore: TransferStore {
    var saved: [TransferRecord] = []
    func load() -> [TransferRecord] { saved }
    func save(_ records: [TransferRecord]) { saved = records }
}

final class ServerLifecycleTests: XCTestCase {
    private func lifecycle(_ transport: FakeTransport) -> (ServerLifecycle, () -> [ServerState]) {
        let machine = ServerLifecycle(transport: transport)
        var states: [ServerState] = []
        machine.onStateChange = { states.append($0) }
        return (machine, { states })
    }

    func testStartTransitionsToRunningAndRecordsPort() {
        let transport = FakeTransport()
        let (machine, states) = lifecycle(transport)
        machine.start()
        XCTAssertEqual([.starting, .running], states())
        XCTAssertEqual(.running, machine.state)
        XCTAssertEqual(4040, machine.port)
        XCTAssertNil(machine.lastError)
        XCTAssertEqual(1, transport.startCalls)
    }

    func testStartWhenRunningIsNoOp() {
        let transport = FakeTransport()
        let (machine, states) = lifecycle(transport)
        machine.start()
        machine.start()
        XCTAssertEqual(1, transport.startCalls, "safe to call on every foreground")
        XCTAssertEqual([.starting, .running], states())
    }

    func testStopTransitionsToStopped() {
        let transport = FakeTransport()
        let (machine, states) = lifecycle(transport)
        machine.start()
        machine.stop()
        XCTAssertEqual([.starting, .running, .stopping, .stopped], states())
        XCTAssertEqual(.stopped, machine.state)
        XCTAssertEqual(0, machine.port)
        XCTAssertEqual(1, transport.stopCalls)
    }

    func testStopWhenStoppedIsNoOp() {
        let transport = FakeTransport()
        let (machine, _) = lifecycle(transport)
        machine.stop()
        XCTAssertEqual(0, transport.stopCalls)
        XCTAssertEqual(.stopped, machine.state)
    }

    func testStartFailureSurfacesErrorAndStops() {
        let transport = FakeTransport()
        transport.errorToThrow = StubError(message: "address already in use")
        let (machine, states) = lifecycle(transport)
        var errors: [String] = []
        machine.onError = { errors.append($0) }

        machine.start()

        XCTAssertEqual([.starting, .stopped], states())
        XCTAssertEqual(.stopped, machine.state)
        XCTAssertEqual(0, machine.port)
        XCTAssertEqual("address already in use", machine.lastError)
        XCTAssertEqual(["address already in use"], errors)
    }

    func testStartCanRetryAfterAFailure() {
        let transport = FakeTransport()
        transport.errorToThrow = StubError(message: "busy")
        let (machine, _) = lifecycle(transport)
        machine.start()
        transport.errorToThrow = nil
        machine.start()
        XCTAssertEqual(.running, machine.state)
        XCTAssertEqual(2, transport.startCalls)
        XCTAssertNil(machine.lastError)
    }

    func testBackgroundStopsAndForegroundRestarts() {
        let transport = FakeTransport()
        let (machine, states) = lifecycle(transport)
        machine.foreground()
        XCTAssertEqual(.running, machine.state)
        machine.background()
        XCTAssertEqual(.stopped, machine.state)
        machine.foreground()
        XCTAssertEqual(.running, machine.state)
        XCTAssertEqual(2, transport.startCalls)
        XCTAssertEqual(1, transport.stopCalls)
        XCTAssertEqual([.starting, .running, .stopping, .stopped, .starting, .running], states())
    }

    func testStoppedCallbackFiresOnBackgroundStop() {
        let transport = FakeTransport()
        let (machine, _) = lifecycle(transport)
        var stoppedCount = 0
        machine.onStopped = { stoppedCount += 1 }
        machine.start()
        machine.stop()
        machine.background() // already stopped: no second callback
        XCTAssertEqual(1, stoppedCount)
    }

    func testStoppingFailsPushReceivesLikePeerServiceStop() {
        // Integration: PeerService.stop() stops the listener and then
        // TransferManager.failPushReceives("Interrupted").
        let transport = FakeTransport()
        let (machine, _) = lifecycle(transport)
        let store = MemoryTransferStore()
        let transfers = TransferManager(store: store, clock: { 0 })
        transfers.noteReceiveStarted(id: "p_1", peerName: "Desk", peerFp: "AB", label: "Inbox", total: 5)
        machine.onStopped = { transfers.failPushReceives(reason: "Interrupted") }

        machine.start()
        machine.background()

        XCTAssertEqual(.failed, transfers.record("p_1")?.state)
        XCTAssertEqual("Interrupted", transfers.record("p_1")?.message)
    }

    func testNonLocalizedErrorStillSurfaces() {
        struct Plain: Error {}
        let transport = FakeTransport()
        transport.errorToThrow = Plain()
        let (machine, _) = lifecycle(transport)
        machine.start()
        XCTAssertNotNil(machine.lastError)
        XCTAssertEqual(.stopped, machine.state)
    }
}

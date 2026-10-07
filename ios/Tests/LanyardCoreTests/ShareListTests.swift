import XCTest
import Crypto
@testable import LanyardCore

/// The share list + serve rules.
///
/// Ported from the Kotlin `ShareServerTest` (lists, manifest/etags, traversal,
/// 404/410, digest-cache bound, changed-file miss, manifest cap, concurrency 503)
/// and the pure half of `ShareStallTest` (a stalled reader frees its slot). The
/// HTTP status line/`Content-Length`/`Range`/body assertions of the Kotlin test
/// live in the socket adapter and are not reproduced here; the decisions they
/// encode are (`RangeDecision`, `ShareGate`, `ManifestResult`). The Kotlin
/// `ShareBrowseTest` is a live Go-peer test and is not ported.
final class ShareListTests: XCTestCase {

    private final class FakeSource: ShareSource {
        var shares: [ShareInfo] = []
        var childLists: [String: [ShareChild]] = [:]
        var resolved: [String: (name: String, size: Int64, mtime: Int64, bytes: [UInt8])] = [:]
        var endedReasons: [String: String] = [:]

        func list() -> [ShareInfo] { shares }
        func children(shareId: String, rel: String) -> [ShareChild]? { childLists["\(shareId)|\(rel)"] }
        func resolve(shareId: String, rel: String) -> ResolvedShareFile? {
            guard let f = resolved["\(shareId)|\(rel)"] else { return nil }
            return ResolvedShareFile(name: f.name, size: f.size, mtimeMillis: f.mtime, openAt: { _ in DataByteSource(f.bytes) })
        }
        func ended(shareId: String) -> String? { endedReasons[shareId] }
    }

    private var now: Int64 = 1_000_000

    private func share(_ id: String = "s1", lifetime: ShareLifetimeType = .untilStopped, expiresAt: Int64? = nil) -> ShareInfo {
        ShareInfo(id: id, label: "x", kind: "folder", lifetime: lifetime, expiresAt: expiresAt)
    }

    private func addDir(_ s: FakeSource, _ shareId: String, _ rel: String) {
        let slash = rel.lastIndex(of: "/")
        let parent = slash.map { String(rel[..<$0]) } ?? ""
        let name = slash.map { String(rel[rel.index(after: $0)...]) } ?? rel
        if (s.childLists["\(shareId)|\(parent)"] ?? []).contains(where: { $0.path == rel }) { return }
        s.childLists["\(shareId)|\(parent)", default: []].append(
            ShareChild(name: name, path: rel, isDir: true, size: 0, mtimeMillis: 5)
        )
    }

    private func addFile(_ s: FakeSource, _ shareId: String, _ rel: String, _ bytes: [UInt8], mtime: Int64 = 5) {
        let slash = rel.lastIndex(of: "/")
        let parent = slash.map { String(rel[..<$0]) } ?? ""
        if !parent.isEmpty { addDir(s, shareId, parent) }
        let name = slash.map { String(rel[rel.index(after: $0)...]) } ?? rel
        s.childLists["\(shareId)|\(parent)", default: []].append(
            ShareChild(name: name, path: rel, isDir: false, size: Int64(bytes.count), mtimeMillis: mtime)
        )
        s.resolved["\(shareId)|\(rel)"] = (name, Int64(bytes.count), mtime, bytes)
    }

    private func list(_ s: FakeSource, maxEntries: Int = ShareList.maxManifestEntriesDefault) -> ShareList {
        ShareList(source: s, maxManifestEntries: maxEntries, clock: { self.now })
    }

    private func sha(_ bytes: [UInt8]) -> String { Hex.encode(SHA256.hash(data: Data(bytes))) }

    // MARK: - Path safety

    func testSafePathRules() {
        XCTAssertTrue(SafePath.validRel(""))
        XCTAssertTrue(SafePath.validRel("a/b.txt"))
        XCTAssertFalse(SafePath.validRel("/abs"))
        XCTAssertFalse(SafePath.validRel("a//b"))
        XCTAssertFalse(SafePath.validRel("../a"))
        XCTAssertFalse(SafePath.validRel("a/./b"))
        XCTAssertFalse(SafePath.validRel("a\\b"))
        XCTAssertFalse(SafePath.validRel("a/C:/b"))
        XCTAssertFalse(SafePath.validSegment(String(repeating: "x", count: 256)))
        XCTAssertFalse(SafePath.validSegment("a:b"))
        XCTAssertEqual("base/child", SafePath.childPath("base", "child"))
        XCTAssertEqual("child", SafePath.childPath("", "child"))
    }

    // MARK: - List

    func testListsOnlyLiveShares() {
        let s = FakeSource()
        s.shares = [share("s1"), share("s2")]
        s.endedReasons["s2"] = "stopped"
        XCTAssertEqual(list(s).list().map(\.id), ["s1"])
    }

    // MARK: - Manifest

    func testManifestListsNestedFilesWithEtags() {
        let s = FakeSource()
        s.shares = [share()]
        addFile(s, "s1", "a.bin", [UInt8](repeating: 0, count: 10))
        addFile(s, "s1", "sub/b.bin", [UInt8](repeating: 0, count: 20))

        guard case let .ok(files, total, lifetime) = list(s).manifest("s1", path: "") else {
            return XCTFail("expected manifest ok")
        }
        XCTAssertEqual(files.count, 2)
        XCTAssertEqual(total, 30)
        XCTAssertEqual(lifetime, .untilStopped)
        XCTAssertEqual(files.first { $0.path == "sub/b.bin" }?.name, "b.bin")
        XCTAssertEqual(files.first { $0.path == "sub/b.bin" }?.etag, ShareList.validator(size: 20, mtimeMillis: 5))
    }

    func testManifestRejectsTraversal() {
        let s = FakeSource()
        s.shares = [share()]
        addFile(s, "s1", "a.bin", [1, 2, 3])
        XCTAssertEqual(list(s).manifest("s1", path: "../a.bin"), .badPath)
        XCTAssertEqual(list(s).manifest("s1", path: "/etc/passwd"), .badPath)
        XCTAssertEqual(list(s).manifest("s1", path: "a\\b"), .badPath)
    }

    func testManifestCapIsEnforced() {
        let s = FakeSource()
        s.shares = [share()]
        addFile(s, "s1", "a.bin", [1])
        addFile(s, "s1", "b.bin", [1])
        XCTAssertEqual(list(s, maxEntries: 1).manifest("s1", path: ""), .tooMany)
    }

    func testUnknownShareIsNotFoundAndEndedShareIsGone() {
        let s = FakeSource()
        s.shares = [share()]
        XCTAssertEqual(list(s).manifest("nope", path: ""), .notFound)
        s.endedReasons["s1"] = "stopped"
        XCTAssertEqual(list(s).manifest("s1", path: ""), .gone("The sender stopped this share."))
    }

    func testCancelledShareIsGone() {
        let s = FakeSource()
        s.shares = [share()]
        addFile(s, "s1", "a.bin", [1])
        let l = list(s)
        l.cancel("s1")
        XCTAssertEqual(l.manifest("s1", path: ""), .gone("The sender stopped this share."))
        XCTAssertTrue(l.list().isEmpty)
    }

    func testStopAllCancelsEveryShare() {
        let s = FakeSource()
        s.shares = [share("s1"), share("s2")]
        let l = list(s)
        l.stopAll()
        XCTAssertTrue(l.list().isEmpty)
        XCTAssertEqual(l.gate("s1"), .gone("The sender stopped this share."))
    }

    // MARK: - Lifetimes

    func testTimedShareExpiresWithTheClock() {
        let s = FakeSource()
        s.shares = [share(lifetime: .timed, expiresAt: now + 30_000)]
        let l = list(s)
        XCTAssertEqual(l.gate("s1"), .ok)
        now += 30_001
        XCTAssertEqual(l.gate("s1"), .gone("This share has expired."))
        XCTAssertTrue(l.list().isEmpty)
    }

    func testConsumeEndsAOneTimeShareForEveryone() {
        let s = FakeSource()
        s.shares = [share(lifetime: .oneTime, expiresAt: now + ShareInfo.oneTimeSafetyExpiryMillis)]
        let l = list(s)
        XCTAssertTrue(l.consume("s1", by: "aa"))
        XCTAssertEqual(l.gate("s1"), .gone("This one-time share has already been downloaded."))
        XCTAssertFalse(l.consume("s1", by: "bb"), "a consumed share cannot be consumed again")
    }

    func testConsumeIgnoresNonOneTimeShares() {
        let s = FakeSource()
        s.shares = [share(lifetime: .persistent)]
        XCTAssertFalse(list(s).consume("s1", by: "aa"))
    }

    // MARK: - Hash

    func testHashMatchesAndIsCached() {
        let s = FakeSource()
        s.shares = [share()]
        let bytes: [UInt8] = [1, 2, 3, 4]
        addFile(s, "s1", "a.bin", bytes)
        let l = list(s)
        guard case let .ok(sum, size, etag) = l.hash("s1", path: "a.bin") else { return XCTFail("hash ok") }
        XCTAssertEqual(sum, sha(bytes))
        XCTAssertEqual(size, 4)
        XCTAssertEqual(etag, ShareList.validator(size: 4, mtimeMillis: 5))
        _ = l.hash("s1", path: "a.bin")
        XCTAssertEqual(l.digests.count, 1, "a repeated hash must be served from the cache")
    }

    func testChangedFileMissesTheCache() {
        let s = FakeSource()
        s.shares = [share()]
        addFile(s, "s1", "a.bin", [1, 2, 3, 4], mtime: 5)
        let l = list(s)
        guard case let .ok(first, _, _) = l.hash("s1", path: "a.bin") else { return XCTFail("hash ok") }
        addFile(s, "s1", "a.bin", [9, 9, 9, 9], mtime: 6)
        guard case let .ok(second, _, _) = l.hash("s1", path: "a.bin") else { return XCTFail("hash ok") }
        XCTAssertNotEqual(first, second, "a size/mtime change must invalidate the cached digest")
        XCTAssertEqual(l.digests.count, 2)
    }

    func testHashCacheIsBounded() {
        let cache = DigestCache(capacity: ShareList.hashCacheMax)
        for i in 0..<1000 { cache.put("k\(i)", "v\(i)") }
        XCTAssertLessThanOrEqual(cache.count, ShareList.hashCacheMax)
        XCTAssertEqual(cache.count, 256)
    }

    func testDigestCacheKeepsRecentlyUsed() {
        let cache = DigestCache(capacity: 2)
        cache.put("a", "1")
        cache.put("b", "2")
        _ = cache.get("a") // a is now most-recent
        cache.put("c", "3") // evicts b
        XCTAssertEqual(cache.get("a"), "1")
        XCTAssertNil(cache.get("b"))
        XCTAssertEqual(cache.get("c"), "3")
    }

    // MARK: - Ranges

    func testParseRange() {
        XCTAssertEqual(ShareList.parseRange(nil, size: 100), .full(start: 0, length: 100))
        XCTAssertEqual(ShareList.parseRange("bytes=10-19", size: 100), .partial(start: 10, length: 10))
        XCTAssertEqual(ShareList.parseRange("bytes=90-", size: 100), .partial(start: 90, length: 10))
        XCTAssertEqual(ShareList.parseRange("bytes=999-1000", size: 10), .unsatisfiable)
        XCTAssertEqual(ShareList.parseRange("bytes=10-19,20-30", size: 100), .unsatisfiable)
        XCTAssertEqual(ShareList.parseRange("nonsense", size: 100), .unsatisfiable)
    }

    func testIfRangeWithChangedEtagServesTheWholeBody() {
        XCTAssertEqual(
            ShareList.resolveRange(header: "bytes=10-19", ifRange: "\"deadbeef-1\"", size: 100, etag: "64-5"),
            .full(start: 0, length: 100)
        )
        XCTAssertEqual(
            ShareList.resolveRange(header: "bytes=10-19", ifRange: "\"64-5\"", size: 100, etag: "64-5"),
            .partial(start: 10, length: 10)
        )
    }

    // MARK: - Concurrency + stall

    func testConcurrencyBeyondTheCapIsRefused() {
        let gate = ConcurrencyGate(maxConcurrent: 1)
        let first = gate.acquire(peer: "AA", now: 0)
        XCTAssertNotNil(first)
        XCTAssertNil(gate.acquire(peer: "AA", now: 0), "a second transfer for the same peer is refused")
        XCTAssertNil(gate.acquire(peer: "bb", now: 0), "the global cap is also one")
        gate.release(first!)
        XCTAssertNotNil(gate.acquire(peer: "bb", now: 0), "the slot is reusable after release")
        XCTAssertEqual(gate.retryAfterSeconds, 5)
    }

    func testStalledReaderFreesItsSlot() {
        let gate = ConcurrencyGate(maxConcurrent: 1, stallTimeoutMillis: 400)
        let lease = gate.acquire(peer: "aa", now: 0)!
        gate.noteActivity(lease, now: 100)
        XCTAssertTrue(gate.sweepStalled(now: 300).isEmpty, "not yet stalled")
        XCTAssertEqual(gate.sweepStalled(now: 1000), [lease], "the quiet lease is dropped")
        XCTAssertNotNil(gate.acquire(peer: "aa", now: 1001), "the freed slot is usable")
    }

    // MARK: - Tree

    func testTreeReturnsValidChildren() {
        let s = FakeSource()
        s.shares = [share()]
        addFile(s, "s1", "a.bin", [1])
        guard case let .ok(children) = list(s).tree("s1", path: "") else { return XCTFail("tree ok") }
        XCTAssertEqual(children.map(\.name), ["a.bin"])
        XCTAssertEqual(list(s).tree("nope", path: ""), .notFound)
        XCTAssertEqual(list(s).tree("s1", path: "../"), .badPath)
    }
}

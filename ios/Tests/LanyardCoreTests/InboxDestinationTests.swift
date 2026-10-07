import XCTest
@testable import LanyardCore

// Ports the destination logic from `InboxDestinations.kt` and
// `SafInboxDestination.kt` (default folder, unique naming, writable check,
// refusal message) adapted to the iOS Documents directory. No Kotlin unit test
// exists for those files, so these cases are newly written from the documented
// behaviour and the Kotlin source.

private struct NilDestination: ReceiveDestination {
    func writableRoot() -> URL? { nil }
}

final class InboxDestinationTests: XCTestCase {
    private var scratch: URL!

    override func setUpWithError() throws {
        scratch = FileManager.default.temporaryDirectory.appendingPathComponent("inbox-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: scratch)
    }

    private func spool(_ contents: String) throws -> URL {
        let url = scratch.appendingPathComponent("spool-\(UUID().uuidString).part")
        try contents.data(using: .utf8)?.write(to: url)
        return url
    }

    // MARK: - Default destination

    func testDefaultDestinationIsDocumentsLANyard() {
        let docs = scratch.appendingPathComponent("Documents")
        let root = DefaultReceiveDestination(documents: { docs }).writableRoot()
        XCTAssertEqual(docs.appendingPathComponent("LANyard", isDirectory: true).standardizedFileURL, root?.standardizedFileURL)
        var isDir: ObjCBool = false
        XCTAssertTrue(FileManager.default.fileExists(atPath: root!.path, isDirectory: &isDir))
        XCTAssertTrue(isDir.boolValue)
    }

    func testDefaultDestinationFolderNameMatchesAndroid() {
        XCTAssertEqual("LANyard", DefaultReceiveDestination.folderName)
    }

    func testDefaultDestinationNilWhenNoDocumentsDirectory() {
        XCTAssertNil(DefaultReceiveDestination(documents: { nil }).writableRoot())
    }

    // MARK: - Writable check

    func testWritableCheck() throws {
        XCTAssertTrue(InboxDestination.isWritableDirectory(scratch))
        XCTAssertFalse(InboxDestination.isWritableDirectory(nil))
        XCTAssertFalse(InboxDestination.isWritableDirectory(scratch.appendingPathComponent("nope")))
        let file = try spool("x")
        XCTAssertFalse(InboxDestination.isWritableDirectory(file), "a regular file is not a writable directory")
    }

    // MARK: - Unique naming

    func testUniqueNameReturnsOriginalWhenFree() {
        XCTAssertEqual("photo.jpg", InboxDestination.uniqueName("photo.jpg") { _ in false })
    }

    func testUniqueNameInsertsCounterBeforeExtension() {
        let taken: Set<String> = ["photo.jpg"]
        XCTAssertEqual("photo (1).jpg", InboxDestination.uniqueName("photo.jpg") { taken.contains($0) })
    }

    func testUniqueNameIncrementsPastExistingCounters() {
        let taken: Set<String> = ["photo.jpg", "photo (1).jpg", "photo (2).jpg"]
        XCTAssertEqual("photo (3).jpg", InboxDestination.uniqueName("photo.jpg") { taken.contains($0) })
    }

    func testUniqueNameWithoutExtension() {
        let taken: Set<String> = ["README"]
        XCTAssertEqual("README (1)", InboxDestination.uniqueName("README") { taken.contains($0) })
    }

    func testUniqueNameTreatsMultiDotExtensionAsOne() {
        let taken: Set<String> = ["archive.tar.gz"]
        XCTAssertEqual("archive.tar (1).gz", InboxDestination.uniqueName("archive.tar.gz") { taken.contains($0) })
    }

    func testUniqueNameLeadingDotIsNotAnExtensionSeparator() {
        let taken: Set<String> = [".bashrc"]
        XCTAssertEqual(".bashrc (1)", InboxDestination.uniqueName(".bashrc") { taken.contains($0) })
    }

    // MARK: - Placing files

    func testPlaceCopiesFileAndRecreatesSubdirectories() throws {
        let destination = InboxDestination(destination: DefaultReceiveDestination(documents: { self.scratch }))
        let written = try destination.place(relPath: "sub/dir/report.txt", spool: try spool("hello"), size: 5)
        XCTAssertEqual("report.txt", written)
        let placed = scratch.appendingPathComponent("LANyard/sub/dir/report.txt")
        XCTAssertEqual("hello", try String(contentsOf: placed, encoding: .utf8))
    }

    func testPlaceRenamesCollision() throws {
        let destination = InboxDestination(destination: DefaultReceiveDestination(documents: { self.scratch }))
        let first = try destination.place(relPath: "report.txt", spool: try spool("one"), size: 3)
        let second = try destination.place(relPath: "report.txt", spool: try spool("two"), size: 3)
        XCTAssertEqual("report.txt", first)
        XCTAssertEqual("report (1).txt", second)
        let root = scratch.appendingPathComponent("LANyard")
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("report.txt").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("report (1).txt").path))
    }

    func testPlaceUsesDefaultNameWhenRelPathHasNoFile() throws {
        let destination = InboxDestination(destination: DefaultReceiveDestination(documents: { self.scratch }))
        let written = try destination.place(relPath: "nested/", spool: try spool("x"), size: 1)
        XCTAssertEqual("received-file", written)
    }

    func testPlaceThrowsRefusalWhenNotWritable() throws {
        let destination = InboxDestination(destination: NilDestination())
        XCTAssertThrowsError(try destination.place(relPath: "a.txt", spool: try spool("x"), size: 1)) { error in
            guard let destinationError = error as? ReceiveDestinationError else {
                return XCTFail("expected ReceiveDestinationError, got \(error)")
            }
            XCTAssertEqual(InboxDestination.refusalMessage, destinationError.message)
        }
        XCTAssertEqual(
            "This device cannot save received files. Choose a writable download folder in Settings.",
            InboxDestination.refusalMessage
        )
    }

    // MARK: - Override (opaque bookmark token)

    func testBookmarkOverrideResolvesToWritableRoot() {
        let token = Data([0x01, 0x02, 0x03])
        let destination = BookmarkReceiveDestination(token: token) { _ in self.scratch }
        XCTAssertEqual(scratch.standardizedFileURL, destination.writableRoot()?.standardizedFileURL)
    }

    func testBookmarkOverrideNilWhenResolveFails() {
        let token = Data([0x01])
        XCTAssertNil(BookmarkReceiveDestination(token: token) { _ in nil }.writableRoot())
    }

    func testBookmarkOverrideIgnoresEmptyToken() {
        XCTAssertNil(BookmarkReceiveDestination(token: Data()) { _ in self.scratch }.writableRoot())
    }

    func testBookmarkOverrideRejectsUnwritableResolvedURL() {
        let token = Data([0x01])
        let missing = scratch.appendingPathComponent("gone")
        XCTAssertNil(BookmarkReceiveDestination(token: token) { _ in missing }.writableRoot())
    }

    func testPlaceUsesBookmarkOverride() throws {
        let override = scratch.appendingPathComponent("picked", isDirectory: true)
        try FileManager.default.createDirectory(at: override, withIntermediateDirectories: true)
        let token = Data([0xAA, 0xBB])
        let destination = InboxDestination(
            destination: BookmarkReceiveDestination(token: token) { _ in override }
        )
        let written = try destination.place(relPath: "a.txt", spool: try spool("hi"), size: 2)
        XCTAssertEqual("a.txt", written)
        XCTAssertTrue(FileManager.default.fileExists(atPath: override.appendingPathComponent("a.txt").path))
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: scratch.appendingPathComponent("LANyard/a.txt").path),
            "the override wins over the default"
        )
    }

    func testBookmarkTokenIsOpaqueAndUnmodified() {
        let token = Data([0xDE, 0xAD, 0xBE, 0xEF])
        let destination = BookmarkReceiveDestination(token: token) { _ in nil }
        XCTAssertEqual(token, destination.token)
    }
}

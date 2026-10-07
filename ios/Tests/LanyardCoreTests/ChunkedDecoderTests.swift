import XCTest
@testable import LanyardCore

/// Focused unit tests for the chunked decoder, covering the same contract and
/// caps as the Kotlin `ChunkedInputStream` (which is private and only exercised
/// indirectly through `PeerServerTest`): chunk extensions, upper-case sizes,
/// CRLF validation, truncation, and the 256 / 1024 / 32 / 8 KiB bounds.
final class ChunkedDecoderTests: XCTestCase {
    private func bytes(_ s: String) -> [UInt8] { Array(s.utf8) }
    private func decode(_ s: String, max: Int = 8192) throws -> [UInt8] {
        try ChunkedDecoder(DataByteSource(bytes(s))).readAll(limit: 1 << 20)
    }

    func testSingleChunk() throws {
        XCTAssertEqual(try decode("4\r\nWiki\r\n0\r\n\r\n"), bytes("Wiki"))
    }

    func testMultipleChunksAndExtensions() throws {
        XCTAssertEqual(try decode("5;foo=bar\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"), bytes("hello world"))
    }

    func testUpperAndMixedCaseSizes() throws {
        XCTAssertEqual(try decode("A\r\n0123456789\r\n0\r\n\r\n"), bytes("0123456789"))
        XCTAssertEqual(try decode("a\r\n0123456789\r\n0\r\n\r\n"), bytes("0123456789"))
    }

    func testEmptyBody() throws {
        XCTAssertEqual(try decode("0\r\n\r\n"), [])
    }

    func testTrailersAreConsumed() throws {
        XCTAssertEqual(try decode("4\r\nWiki\r\n0\r\nX-Checksum: abc\r\nY: z\r\n\r\n"), bytes("Wiki"))
    }

    func testReadInSmallIncrementsReassembles() throws {
        let d = ChunkedDecoder(DataByteSource(bytes("5\r\nhello\r\n2\r\n!!\r\n0\r\n\r\n")))
        var out = [UInt8]()
        while true {
            let part = try d.read(max: 1)
            if part.isEmpty { break }
            out.append(contentsOf: part)
        }
        XCTAssertEqual(out, bytes("hello!!"))
    }

    func testTruncatedDataThrows() {
        XCTAssertThrowsError(try decode("4\r\nWi")) { assertBadRequest($0) }
    }

    func testMalformedSizeThrows() {
        XCTAssertThrowsError(try decode("zz\r\nx\r\n0\r\n\r\n")) { assertBadRequest($0) }
        XCTAssertThrowsError(try decode("\r\n0\r\n\r\n")) { assertBadRequest($0) }
    }

    func testNegativeSizeThrows() {
        XCTAssertThrowsError(try decode("-1\r\nx\r\n0\r\n\r\n")) { assertBadRequest($0) }
    }

    func testBadChunkTerminatorThrows() {
        // Data "abc" then 'X' where CRLF is required.
        XCTAssertThrowsError(try decode("3\r\nabcXX")) { assertBadRequest($0) }
    }

    func testChunkLineTooLongThrows() {
        let long = String(repeating: "a", count: ChunkedDecoder.maxChunkLine + 1)
        XCTAssertThrowsError(try decode("\(long)\r\n0\r\n\r\n")) { assertBadRequest($0) }
    }

    func testTrailerLineTooLongThrows() {
        let long = String(repeating: "t", count: ChunkedDecoder.maxTrailerLine + 1)
        XCTAssertThrowsError(try decode("0\r\n\(long)\r\n\r\n")) { assertBadRequest($0) }
    }

    func testTrailerTooManyLinesThrows() {
        let trailers = (0...ChunkedDecoder.maxTrailerLines).map { "X\($0): v" }.joined(separator: "\r\n")
        XCTAssertThrowsError(try decode("0\r\n\(trailers)\r\n\r\n")) { assertBadRequest($0) }
    }

    func testTrailerTooManyBytesThrows() {
        // 10 trailer lines, each legal (< 1024), whose total exceeds 8 KiB while
        // staying under the 32-line cap, so only the byte cap can trip.
        let value = String(repeating: "v", count: 900)
        let line = "X: \(value)" // 903 bytes + 2 = 905; 10 lines = 9050 > 8192
        let body = "0\r\n" + Array(repeating: line, count: 10).joined(separator: "\r\n") + "\r\n\r\n"
        XCTAssertThrowsError(try decode(body)) { assertBadRequest($0) }
    }

    private func assertBadRequest(_ error: Error, file: StaticString = #filePath, line: UInt = #line) {
        guard let e = error as? PeerHttpException else {
            return XCTFail("expected PeerHttpException, got \(error)", file: file, line: line)
        }
        XCTAssertEqual(e.code, 400, file: file, line: line)
    }
}

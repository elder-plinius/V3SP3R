import XCTest
@testable import VesperCore

final class DiffEngineTests: XCTestCase {
    func testFrequencyChange() {
        let diff = DiffEngine.compute(original: "Frequency: 390000000\nProtocol: RAW", replacement: "Frequency: 315000000\nProtocol: RAW")
        XCTAssertEqual(diff.linesAdded, 1)
        XCTAssertEqual(diff.linesRemoved, 1)
        XCTAssertTrue(diff.unifiedDiff.contains("315000000"))
    }

    func testIdenticalContentHasEmptyDiff() {
        let diff = DiffEngine.compute(original: "same", replacement: "same")
        XCTAssertEqual(diff.linesAdded, 0)
        XCTAssertEqual(diff.linesRemoved, 0)
    }
}

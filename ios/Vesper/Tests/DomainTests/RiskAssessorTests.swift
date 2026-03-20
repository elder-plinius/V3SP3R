// RiskAssessorTests.swift
// Vesper - Unit tests for RiskAssessor

import XCTest
@testable import Vesper

// MARK: - Mock Settings Store

/// A mock SettingsStore conforming to the SettingsStore protocol for testing.
private struct MockSettingsStore: SettingsStoreProtocol, @unchecked Sendable {
    var autoApproveMedium: Bool = false
    var autoApproveHigh: Bool = false
    var unlockedPaths: Set<String> = []
    var scopedPaths: Set<String> = []

    func isProtectedPathUnlocked(_ path: String) -> Bool {
        unlockedPaths.contains(path)
    }

    func isPathInScope(_ path: String) -> Bool {
        // If no scoped paths configured, treat /ext/ paths as in-scope
        if scopedPaths.isEmpty {
            return path.hasPrefix("/ext/")
        }
        return scopedPaths.contains(where: { path.hasPrefix($0) })
    }
}

// MARK: - Test Helpers

private func makeCommand(
    action: CommandAction,
    path: String? = nil,
    destinationPath: String? = nil,
    content: String? = nil,
    newName: String? = nil,
    recursive: Bool = false,
    command: String? = nil,
    artifactType: String? = nil,
    prompt: String? = nil,
    appName: String? = nil
) -> ExecuteCommand {
    ExecuteCommand(
        action: action,
        args: CommandArgs(
            command: command,
            path: path,
            destinationPath: destinationPath,
            content: content,
            newName: newName,
            recursive: recursive,
            artifactType: artifactType,
            prompt: prompt,
            appName: appName
        ),
        justification: "test",
        expectedEffect: "test"
    )
}

// MARK: - Tests

final class RiskAssessorTests: XCTestCase {

    private var assessor: RiskAssessor!
    private var settingsStore: MockSettingsStore!

    override func setUp() {
        super.setUp()
        settingsStore = MockSettingsStore()
        assessor = RiskAssessor(settingsStore: settingsStore)
    }

    override func tearDown() {
        assessor = nil
        settingsStore = nil
        super.tearDown()
    }

    // MARK: - LOW Risk Tests

    func testListDirectoryIsLow() {
        let cmd = makeCommand(action: .listDirectory, path: "/ext/subghz")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .low)
        XCTAssertFalse(result.requiresDiff)
        XCTAssertFalse(result.requiresConfirmation)
    }

    func testReadFileIsLow() {
        let cmd = makeCommand(action: .readFile, path: "/ext/subghz/test.sub")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .low)
    }

    func testGetDeviceInfoIsLow() {
        let cmd = makeCommand(action: .getDeviceInfo)
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .low)
    }

    func testGetStorageInfoIsLow() {
        let cmd = makeCommand(action: .getStorageInfo)
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .low)
    }

    func testSearchFaphubIsLow() {
        let cmd = makeCommand(action: .searchFaphub)
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .low)
    }

    func testBrowseRepoIsLow() {
        let cmd = makeCommand(action: .browseRepo)
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .low)
    }

    func testGithubSearchIsLow() {
        let cmd = makeCommand(action: .githubSearch)
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .low)
    }

    func testSearchResourcesIsLow() {
        let cmd = makeCommand(action: .searchResources)
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .low)
    }

    func testListVaultIsLow() {
        let cmd = makeCommand(action: .listVault)
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .low)
    }

    func testRequestPhotoIsLow() {
        let cmd = makeCommand(action: .requestPhoto)
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .low)
    }

    func testLedControlIsLow() {
        let cmd = makeCommand(action: .ledControl)
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .low)
    }

    func testVibroControlIsLow() {
        let cmd = makeCommand(action: .vibroControl)
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .low)
    }

    // MARK: - MEDIUM Risk Tests (default / in-scope)

    func testWriteFileIsMediumWhenInScope() {
        let cmd = makeCommand(action: .writeFile, path: "/ext/test.txt", content: "hello")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .medium)
        XCTAssertTrue(result.requiresDiff)
        XCTAssertFalse(result.requiresConfirmation)
    }

    func testWriteFileIsHighWhenOutOfScope() {
        settingsStore = MockSettingsStore(scopedPaths: ["/ext/project/"])
        assessor = RiskAssessor(settingsStore: settingsStore)

        let cmd = makeCommand(action: .writeFile, path: "/ext/other/test.txt", content: "hello")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .high)
        XCTAssertTrue(result.requiresDiff)
        XCTAssertTrue(result.requiresConfirmation)
    }

    func testCreateDirectoryIsMediumWhenInScope() {
        let cmd = makeCommand(action: .createDirectory, path: "/ext/newdir")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .medium)
        XCTAssertFalse(result.requiresConfirmation)
    }

    func testCreateDirectoryIsHighWhenOutOfScope() {
        settingsStore = MockSettingsStore(scopedPaths: ["/ext/project/"])
        assessor = RiskAssessor(settingsStore: settingsStore)

        let cmd = makeCommand(action: .createDirectory, path: "/ext/other/newdir")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .high)
        XCTAssertTrue(result.requiresConfirmation)
    }

    func testCopyIsMediumWhenDestInScope() {
        let cmd = makeCommand(action: .copy, path: "/ext/a.txt", destinationPath: "/ext/b.txt")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .medium)
    }

    func testCopyIsHighWhenDestOutOfScope() {
        settingsStore = MockSettingsStore(scopedPaths: ["/ext/project/"])
        assessor = RiskAssessor(settingsStore: settingsStore)

        let cmd = makeCommand(action: .copy, path: "/ext/project/a.txt", destinationPath: "/ext/other/b.txt")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .high)
        XCTAssertTrue(result.requiresConfirmation)
    }

    func testForgePayloadIsMedium() {
        let cmd = makeCommand(action: .forgePayload, prompt: "create a sub-ghz signal")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .medium)
    }

    func testDownloadResourceIsMedium() {
        let cmd = makeCommand(action: .downloadResource)
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .medium)
    }

    func testLaunchAppIsMedium() {
        let cmd = makeCommand(action: .launchApp, appName: "nfc")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .medium)
    }

    func testSubghzTransmitIsMedium() {
        let cmd = makeCommand(action: .subghzTransmit, path: "/ext/subghz/test.sub")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .medium)
    }

    func testIrTransmitIsMedium() {
        let cmd = makeCommand(action: .irTransmit, path: "/ext/infrared/tv.ir")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .medium)
    }

    func testNfcEmulateIsMedium() {
        let cmd = makeCommand(action: .nfcEmulate, path: "/ext/nfc/card.nfc")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .medium)
    }

    func testRfidEmulateIsMedium() {
        let cmd = makeCommand(action: .rfidEmulate, path: "/ext/lfrfid/tag.rfid")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .medium)
    }

    func testIbuttonEmulateIsMedium() {
        let cmd = makeCommand(action: .ibuttonEmulate, path: "/ext/ibutton/key.ibtn")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .medium)
    }

    func testBleSpamIsMedium() {
        let cmd = makeCommand(action: .bleSpam)
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .medium)
    }

    // MARK: - HIGH Risk Tests

    func testDeleteIsHigh() {
        let cmd = makeCommand(action: .delete, path: "/ext/test.txt")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .high)
        XCTAssertTrue(result.requiresConfirmation)
    }

    func testDeleteRecursiveIsHighWithRecursiveReason() {
        let cmd = makeCommand(action: .delete, path: "/ext/subghz", recursive: true)
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .high)
        XCTAssertTrue(result.reason.contains("Recursive"))
    }

    func testMoveIsHigh() {
        let cmd = makeCommand(action: .move, path: "/ext/a.txt", destinationPath: "/ext/b.txt")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .high)
        XCTAssertTrue(result.requiresConfirmation)
    }

    func testRenameIsHigh() {
        let cmd = makeCommand(action: .rename, path: "/ext/a.txt", newName: "b.txt")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .high)
        XCTAssertTrue(result.requiresConfirmation)
    }

    func testBadusbExecuteIsHigh() {
        let cmd = makeCommand(action: .badusbExecute, path: "/ext/badusb/script.txt")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .high)
        XCTAssertTrue(result.requiresConfirmation)
    }

    func testInstallFaphubAppIsHigh() {
        let cmd = makeCommand(action: .installFaphubApp)
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .high)
        XCTAssertTrue(result.requiresConfirmation)
    }

    // MARK: - BLOCKED Risk Tests

    func testProtectedPathIsBlocked() {
        let cmd = makeCommand(action: .readFile, path: "/int/manifest.txt")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .blocked)
        XCTAssertNotNil(result.blockedReason)
    }

    func testProtectedPathUnlockedBypasses() {
        settingsStore = MockSettingsStore(unlockedPaths: ["/int/manifest.txt"])
        assessor = RiskAssessor(settingsStore: settingsStore)

        let cmd = makeCommand(action: .readFile, path: "/int/manifest.txt")
        let result = assessor.assess(cmd)
        // Should fall through to the action-based assessment (readFile -> LOW)
        XCTAssertEqual(result.level, .low)
    }

    // MARK: - CLI Command Risk Tests

    func testCliHelpIsLow() {
        let cmd = makeCommand(action: .executeCli, command: "help")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .low)
    }

    func testCliStorageListIsLow() {
        let cmd = makeCommand(action: .executeCli, command: "storage list /ext/")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .low)
    }

    func testCliLoaderOpenIsMedium() {
        let cmd = makeCommand(action: .executeCli, command: "loader open nfc")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .medium)
    }

    func testCliRmIsHigh() {
        let cmd = makeCommand(action: .executeCli, command: "rm /ext/test.txt")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .high)
    }

    func testCliUnknownCommandIsHigh() {
        let cmd = makeCommand(action: .executeCli, command: "storage format /ext")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .high)
    }

    func testCliVersionIsLow() {
        let cmd = makeCommand(action: .executeCli, command: "version")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .low)
    }

    func testCliSubghzTxIsMedium() {
        let cmd = makeCommand(action: .executeCli, command: "subghz tx 433920000 10 1000")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .medium)
    }

    func testCliEmptyIsHigh() {
        // Empty CLI command is not recognized as safe or medium, so it falls to high
        let cmd = makeCommand(action: .executeCli, command: "")
        let result = assessor.assess(cmd)
        XCTAssertEqual(result.level, .high)
    }

    // MARK: - Mass Operation Tests

    func testMassOperationRecursiveDelete() {
        let cmd = makeCommand(action: .delete, path: "/ext/subghz", recursive: true)
        XCTAssertTrue(assessor.isMassOperation(cmd))
    }

    func testMassOperationNonRecursiveDeleteIsNot() {
        let cmd = makeCommand(action: .delete, path: "/ext/test.txt")
        XCTAssertFalse(assessor.isMassOperation(cmd))
    }

    func testMassOperationCliStorageFormat() {
        let cmd = makeCommand(action: .executeCli, command: "storage format /ext")
        XCTAssertTrue(assessor.isMassOperation(cmd))
    }

    func testMassOperationReadFileIsNot() {
        let cmd = makeCommand(action: .readFile, path: "/ext/test.txt")
        XCTAssertFalse(assessor.isMassOperation(cmd))
    }

    // MARK: - Affected Paths Tests

    func testAffectedPathsIncludesPath() {
        let cmd = makeCommand(action: .readFile, path: "/ext/test.txt")
        let result = assessor.assess(cmd)
        XCTAssertTrue(result.affectedPaths.contains("/ext/test.txt"))
    }

    func testAffectedPathsIncludesBothPaths() {
        let cmd = makeCommand(action: .move, path: "/ext/a.txt", destinationPath: "/ext/b.txt")
        let result = assessor.assess(cmd)
        XCTAssertTrue(result.affectedPaths.contains("/ext/a.txt"))
        XCTAssertTrue(result.affectedPaths.contains("/ext/b.txt"))
    }

    func testAffectedPathsExtractsCliPaths() {
        let cmd = makeCommand(action: .executeCli, command: "storage read /ext/subghz/test.sub")
        let result = assessor.assess(cmd)
        XCTAssertTrue(result.affectedPaths.contains("/ext/subghz/test.sub"))
    }
}

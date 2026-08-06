import XCTest
@testable import VesperCore

final class CommandExecutorTests: XCTestCase {
    func testBlockedCommandNeverReachesRPC() async {
        let rpc = FakeRPC()
        let executor = CommandExecutor(rpc: rpc, audit: MemoryAuditRecorder())
        let command = ExecuteCommand(action: .readFile, args: .init(path: "/int/manifest.txt"), justification: "test", expectedEffect: "test")
        let result = await executor.execute(command)
        XCTAssertFalse(result.success)
        let blockedCalls = await rpc.callCount
        XCTAssertEqual(blockedCalls, 0)
    }

    func testHighRiskWaitsForApprovalThenExecutesOnce() async throws {
        let rpc = FakeRPC()
        let executor = CommandExecutor(rpc: rpc, audit: MemoryAuditRecorder())
        let command = ExecuteCommand(action: .delete, args: .init(path: "/ext/test.txt"), justification: "test", expectedEffect: "test")
        let pending = await executor.execute(command)
        XCTAssertTrue(pending.requiresConfirmation)
        let callsBeforeApproval = await rpc.callCount
        XCTAssertEqual(callsBeforeApproval, 0)
        let approved = await executor.approve(try XCTUnwrap(pending.pendingApprovalID))
        XCTAssertTrue(approved.success)
        let callsAfterApproval = await rpc.callCount
        XCTAssertEqual(callsAfterApproval, 1)
    }

    func testRejectedCommandNeverExecutes() async throws {
        let rpc = FakeRPC()
        let executor = CommandExecutor(rpc: rpc, audit: MemoryAuditRecorder())
        let command = ExecuteCommand(action: .badusbExecute, args: .init(path: "/ext/badusb/test.txt"), justification: "test", expectedEffect: "test")
        let pending = await executor.execute(command)
        _ = await executor.reject(try XCTUnwrap(pending.pendingApprovalID))
        let rejectedCalls = await rpc.callCount
        XCTAssertEqual(rejectedCalls, 0)
    }
}

private actor FakeRPC: FlipperRPCClient {
    var callCount = 0
    func getDeviceInfo() async throws -> DeviceInfo { callCount += 1; return .init(name: "Flipper", firmwareVersion: "1", hardwareVersion: "1", batteryLevel: 50, isCharging: false) }
    func getStorageInfo() async throws -> StorageInfo { callCount += 1; return .init(internalTotal: 1, internalFree: 1, externalTotal: 1, externalFree: 1, hasSDCard: true) }
    func listDirectory(_ path: String) async throws -> [FileEntry] { callCount += 1; return [] }
    func readFile(_ path: String) async throws -> Data { callCount += 1; return Data("old".utf8) }
    func writeFile(_ path: String, data: Data) async throws -> UInt64 { callCount += 1; return UInt64(data.count) }
    func createDirectory(_ path: String) async throws { callCount += 1 }
    func delete(_ path: String, recursive: Bool) async throws { callCount += 1 }
    func move(_ source: String, to destination: String) async throws { callCount += 1 }
    func copy(_ source: String, to destination: String) async throws { callCount += 1 }
    func executeCLI(_ command: String) async throws -> String { callCount += 1; return "ok" }
    func diagnostics() async -> String { "ready" }
}

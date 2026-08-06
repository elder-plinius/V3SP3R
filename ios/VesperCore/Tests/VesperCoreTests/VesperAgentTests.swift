import XCTest
@testable import VesperCore

final class VesperAgentTests: XCTestCase {
    func testDuplicateToolCallIDDoesNotReplayMutation() async {
        let rpc = AgentFakeRPC()
        let call = LLMToolCall(
            id: "same-call",
            name: "execute_command",
            arguments: #"{"action":"create_directory","args":{"path":"/ext/apps_data/vesper/a"},"justification":"test","expected_effect":"directory"}"#
        )
        let llm = SequenceLLM([
            LLMResponse(content: "", toolCalls: [call], model: "fake"),
            LLMResponse(content: "", toolCalls: [call], model: "fake"),
            LLMResponse(content: "done", model: "fake")
        ])
        let executor = CommandExecutor(
            rpc: rpc,
            audit: MemoryAuditRecorder(),
            grants: [PermissionGrant(pathPrefix: "/ext/apps_data/vesper", expiresAt: .distantFuture)]
        )
        let agent = VesperAgent(llm: llm, executor: executor)

        _ = await agent.send("create it")

        let mutations = await rpc.mutationCount
        XCTAssertEqual(mutations, 1)
    }

    func testBlockedActionNeverReachesTransportThroughAgent() async {
        let rpc = AgentFakeRPC()
        let call = LLMToolCall(
            id: "blocked-call",
            name: "execute_command",
            arguments: #"{"action":"read_file","args":{"path":"/int/manifest.txt"},"justification":"test","expected_effect":"contents"}"#
        )
        let llm = SequenceLLM([
            LLMResponse(content: "", toolCalls: [call], model: "fake"),
            LLMResponse(content: "blocked", model: "fake")
        ])
        let agent = VesperAgent(llm: llm, executor: CommandExecutor(rpc: rpc, audit: MemoryAuditRecorder()))

        _ = await agent.send("read it")

        let calls = await rpc.totalCalls
        XCTAssertEqual(calls, 0)
    }

    func testBackgroundingWhileModelIsRunningPreventsBLEExecution() async {
        let rpc = AgentFakeRPC()
        let call = LLMToolCall(
            id: "late-call",
            name: "execute_command",
            arguments: #"{"action":"create_directory","args":{"path":"/ext/apps_data/vesper/late"},"justification":"test","expected_effect":"directory"}"#
        )
        let llm = PausingLLM(response: LLMResponse(content: "", toolCalls: [call], model: "fake"))
        let executor = CommandExecutor(
            rpc: rpc,
            audit: MemoryAuditRecorder(),
            grants: [PermissionGrant(pathPrefix: "/ext/apps_data/vesper", expiresAt: .distantFuture)]
        )
        let agent = VesperAgent(llm: llm, executor: executor)
        let sendTask = Task { await agent.send("create later") }

        await llm.waitUntilStarted()
        await agent.appDidEnterBackground()
        await llm.release()
        _ = await sendTask.value

        let calls = await rpc.totalCalls
        XCTAssertEqual(calls, 0)
    }
}

private actor SequenceLLM: LLMClient {
    private var responses: [LLMResponse]
    init(_ responses: [LLMResponse]) { self.responses = responses }
    func complete(messages: [AgentMessage], tools: [LLMToolDefinition]) async throws -> LLMResponse {
        responses.isEmpty ? LLMResponse(content: "done", model: "fake") : responses.removeFirst()
    }
}

private actor PausingLLM: LLMClient {
    private let response: LLMResponse
    private var started = false
    private var continuation: CheckedContinuation<Void, Never>?
    init(response: LLMResponse) { self.response = response }
    func complete(messages: [AgentMessage], tools: [LLMToolDefinition]) async throws -> LLMResponse {
        started = true
        await withCheckedContinuation { continuation = $0 }
        return response
    }
    func waitUntilStarted() async {
        while !started { await Task.yield() }
    }
    func release() {
        continuation?.resume()
        continuation = nil
    }
}

private actor AgentFakeRPC: FlipperRPCClient {
    var totalCalls = 0
    var mutationCount = 0
    func getDeviceInfo() async throws -> DeviceInfo { totalCalls += 1; return .init(name: "F", firmwareVersion: "1", hardwareVersion: "1", batteryLevel: 1, isCharging: false) }
    func getStorageInfo() async throws -> StorageInfo { totalCalls += 1; return .init(internalTotal: 1, internalFree: 1, externalTotal: 1, externalFree: 1, hasSDCard: true) }
    func listDirectory(_ path: String) async throws -> [FileEntry] { totalCalls += 1; return [] }
    func readFile(_ path: String) async throws -> Data { totalCalls += 1; return Data() }
    func writeFile(_ path: String, data: Data) async throws -> UInt64 { totalCalls += 1; mutationCount += 1; return UInt64(data.count) }
    func createDirectory(_ path: String) async throws { totalCalls += 1; mutationCount += 1 }
    func delete(_ path: String, recursive: Bool) async throws { totalCalls += 1; mutationCount += 1 }
    func move(_ source: String, to destination: String) async throws { totalCalls += 1; mutationCount += 1 }
    func copy(_ source: String, to destination: String) async throws { totalCalls += 1; mutationCount += 1 }
    func executeCLI(_ command: String) async throws -> String { totalCalls += 1; mutationCount += 1; return "ok" }
    func diagnostics() async -> String { "ready" }
}

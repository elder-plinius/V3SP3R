import Foundation

public struct CommandParser: Sendable {
    private let decoder = JSONDecoder()

    public init() {}

    public func parse(_ input: String) throws -> ExecuteCommand {
        let repaired = repair(input)
        guard let data = repaired.data(using: .utf8),
              var object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw VesperCoreError.invalidCommand("Expected a JSON object")
        }

        if let parameters = object["parameters"], object["args"] == nil { object["args"] = parameters }
        if object["args"] == nil || object["args"] is NSNull { object["args"] = [String: Any]() }
        if let text = object["args"] as? String {
            object["args"] = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) ?? [:]
        }
        guard var args = object["args"] as? [String: Any] else {
            throw VesperCoreError.invalidCommand("args must be an object")
        }
        alias("cmd", to: "command", in: &args)
        alias("file_path", to: "path", in: &args)
        alias("signal_file", to: "path", in: &args)
        alias("dest", to: "destination_path", in: &args)
        alias("state", to: "enabled", in: &args)
        alias("payload_spec", to: "prompt", in: &args)
        alias("repoId", to: "repo_id", in: &args)
        alias("photoPrompt", to: "photo_prompt", in: &args)
        if let color = (args["color"] as? String)?.lowercased(), args["red"] == nil, args["green"] == nil, args["blue"] == nil {
            let rgb: [Int]
            switch color {
            case "red": rgb = [255, 0, 0]
            case "green": rgb = [0, 255, 0]
            case "blue": rgb = [0, 0, 255]
            default: rgb = [0, 0, 0]
            }
            args["red"] = rgb[0]; args["green"] = rgb[1]; args["blue"] = rgb[2]
        }
        for key in ["command", "path", "destination_path", "content", "new_name"] where args[key] == nil {
            args[key] = object[key]
        }
        object["args"] = args
        if let raw = object["action"] as? String { object["action"] = normalizeAction(raw) }
        object.removeValue(forKey: "id")
        let normalized = try JSONSerialization.data(withJSONObject: object)
        do { return try decoder.decode(ExecuteCommand.self, from: normalized) }
        catch { throw VesperCoreError.invalidCommand(error.localizedDescription) }
    }

    private func repair(_ input: String) -> String {
        var value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.hasPrefix("```"), let firstBreak = value.firstIndex(of: "\n") {
            value = String(value[value.index(after: firstBreak)...])
            if let fence = value.range(of: "```", options: .backwards) { value.removeSubrange(fence) }
        }
        if value.hasPrefix("["), value.hasSuffix("]"),
           let data = value.data(using: .utf8),
           let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]], array.count == 1,
           let normalized = try? JSONSerialization.data(withJSONObject: array[0]),
           let text = String(data: normalized, encoding: .utf8) { return text }
        return value
    }

    private func alias(_ source: String, to destination: String, in object: inout [String: Any]) {
        if object[destination] == nil, let value = object[source] { object[destination] = value }
    }

    private func normalizeAction(_ raw: String) -> String {
        let snake = raw
            .replacingOccurrences(of: "-", with: "_")
            .replacingOccurrences(of: " ", with: "_")
            .replacingOccurrences(of: "([a-z0-9])([A-Z])", with: "$1_$2", options: .regularExpression)
            .lowercased()
        if snake == "execute_command" { return CommandAction.executeCLI.rawValue }
        return snake
    }
}

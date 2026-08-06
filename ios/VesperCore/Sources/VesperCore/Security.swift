import Foundation

public enum PathSecurity {
    public static let maximumLength = 512
    public static let maximumContentBytes = 10 * 1024 * 1024
    public static let systemPaths = ["/int/", "/int/.region", "/int/manifest.txt", "/ext/.region"]
    public static let firmwarePaths = ["/int/update/", "/ext/update/"]
    public static let sensitiveExtensions = [".key", ".priv", ".secret"]

    public static func normalize(_ path: String) throws -> String {
        guard !path.isEmpty, path.utf8.count <= maximumLength, !path.contains("\0") else {
            throw VesperCoreError.invalidPath(path)
        }
        var components: [Substring] = []
        for component in path.replacingOccurrences(of: "\\", with: "/").split(separator: "/") {
            if component == "." || component.isEmpty { continue }
            guard component != "..", component != "~" else { throw VesperCoreError.invalidPath(path) }
            components.append(component)
        }
        let normalized = "/" + components.joined(separator: "/")
        guard normalized == "/ext" || normalized == "/int" || normalized.hasPrefix("/ext/") || normalized.hasPrefix("/int/") else {
            throw VesperCoreError.invalidPath(path)
        }
        return normalized
    }

    public static func isProtected(_ path: String) -> Bool {
        systemPaths.contains(where: path.hasPrefix)
            || firmwarePaths.contains(where: path.hasPrefix)
            || sensitiveExtensions.contains(where: { path.lowercased().hasSuffix($0) })
    }

    public static func blockedReason(for path: String) -> String {
        if systemPaths.contains(where: path.hasPrefix) { return "System path requires an explicit settings unlock" }
        if firmwarePaths.contains(where: path.hasPrefix) { return "Firmware path requires an explicit settings unlock" }
        return "Sensitive file type requires an explicit settings unlock"
    }
}

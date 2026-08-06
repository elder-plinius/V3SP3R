import Foundation

public enum DiffEngine {
    public static func compute(original: String?, replacement: String) -> FileDiff {
        let old = original?.components(separatedBy: "\n") ?? []
        let new = replacement.components(separatedBy: "\n")
        let difference = new.difference(from: old)
        var added = 0
        var removed = 0
        var lines = ["--- original", "+++ modified"]
        for change in difference {
            switch change {
            case .insert(let offset, let element, _):
                added += 1
                lines.append("+\(offset + 1) \(element)")
            case .remove(let offset, let element, _):
                removed += 1
                lines.append("-\(offset + 1) \(element)")
            }
        }
        return FileDiff(originalContent: original, newContent: replacement, linesAdded: added, linesRemoved: removed, unifiedDiff: lines.joined(separator: "\n"))
    }
}

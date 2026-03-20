// DiffService.swift
// Vesper - AI-powered Flipper Zero controller
// Computes unified diffs for file write previews

import Foundation

/// Computes unified diffs between original and new file content.
/// Used for write-file previews in the approval UI.
enum DiffService {

    /// Compute a unified diff between original content (nil for new files) and new content.
    /// Returns a `FileDiff` with line counts and a unified diff string suitable for display.
    static func computeDiff(original: String?, new: String) -> FileDiff {
        let originalLines = original.map { $0.components(separatedBy: "\n") } ?? []
        let newLines = new.components(separatedBy: "\n")

        let lcs = longestCommonSubsequence(originalLines, newLines)

        var linesAdded = 0
        var linesRemoved = 0

        let unifiedDiff = buildUnifiedDiff(
            originalLines: originalLines,
            newLines: newLines,
            lcs: lcs,
            linesAdded: &linesAdded,
            linesRemoved: &linesRemoved,
            originalName: original != nil ? "a/file" : "/dev/null",
            newName: "b/file"
        )

        return FileDiff(
            originalContent: original,
            newContent: new,
            linesAdded: linesAdded,
            linesRemoved: linesRemoved,
            unifiedDiff: unifiedDiff
        )
    }

    // MARK: - LCS (Myers-style, O(ND) simplified)

    /// Computes the longest common subsequence table.
    /// Returns an array of pairs (oldIndex, newIndex) that are common.
    private static func longestCommonSubsequence(
        _ old: [String],
        _ new: [String]
    ) -> [(Int, Int)] {
        let m = old.count
        let n = new.count

        // Build LCS length table
        var table = Array(repeating: Array(repeating: 0, count: n + 1), count: m + 1)
        for i in 1...max(m, 1) {
            guard i <= m else { break }
            for j in 1...max(n, 1) {
                guard j <= n else { break }
                if old[i - 1] == new[j - 1] {
                    table[i][j] = table[i - 1][j - 1] + 1
                } else {
                    table[i][j] = max(table[i - 1][j], table[i][j - 1])
                }
            }
        }

        // Backtrack to find the actual LCS pairs
        var result: [(Int, Int)] = []
        var i = m
        var j = n
        while i > 0 && j > 0 {
            if old[i - 1] == new[j - 1] {
                result.append((i - 1, j - 1))
                i -= 1
                j -= 1
            } else if table[i - 1][j] > table[i][j - 1] {
                i -= 1
            } else {
                j -= 1
            }
        }

        return result.reversed()
    }

    // MARK: - Unified Diff Builder

    private static func buildUnifiedDiff(
        originalLines: [String],
        newLines: [String],
        lcs: [(Int, Int)],
        linesAdded: inout Int,
        linesRemoved: inout Int,
        originalName: String,
        newName: String
    ) -> String {
        // Build edit script from LCS
        var edits: [DiffEdit] = []

        var oldIdx = 0
        var newIdx = 0

        for (lcsOld, lcsNew) in lcs {
            // Lines removed from old (before this LCS match)
            while oldIdx < lcsOld {
                edits.append(.remove(line: originalLines[oldIdx], oldLineNumber: oldIdx))
                linesRemoved += 1
                oldIdx += 1
            }
            // Lines added in new (before this LCS match)
            while newIdx < lcsNew {
                edits.append(.add(line: newLines[newIdx], newLineNumber: newIdx))
                linesAdded += 1
                newIdx += 1
            }
            // Context line (matched)
            edits.append(.context(line: originalLines[oldIdx], oldLineNumber: oldIdx, newLineNumber: newIdx))
            oldIdx += 1
            newIdx += 1
        }

        // Remaining lines after the last LCS match
        while oldIdx < originalLines.count {
            edits.append(.remove(line: originalLines[oldIdx], oldLineNumber: oldIdx))
            linesRemoved += 1
            oldIdx += 1
        }
        while newIdx < newLines.count {
            edits.append(.add(line: newLines[newIdx], newLineNumber: newIdx))
            linesAdded += 1
            newIdx += 1
        }

        // Group edits into hunks with context
        let contextLines = 3
        let hunks = groupIntoHunks(edits: edits, contextLines: contextLines)

        // Format output
        var output = "--- \(originalName)\n"
        output += "+++ \(newName)\n"

        for hunk in hunks {
            let header = formatHunkHeader(hunk)
            output += header + "\n"
            for edit in hunk.edits {
                switch edit {
                case .context(let line, _, _):
                    output += " \(line)\n"
                case .remove(let line, _):
                    output += "-\(line)\n"
                case .add(let line, _):
                    output += "+\(line)\n"
                }
            }
        }

        return output
    }

    // MARK: - Hunk Grouping

    private struct DiffHunk {
        var edits: [DiffEdit]
        var oldStart: Int
        var oldCount: Int
        var newStart: Int
        var newCount: Int
    }

    private enum DiffEdit {
        case context(line: String, oldLineNumber: Int, newLineNumber: Int)
        case remove(line: String, oldLineNumber: Int)
        case add(line: String, newLineNumber: Int)

        var isChange: Bool {
            switch self {
            case .context: return false
            case .remove, .add: return true
            }
        }
    }

    private static func groupIntoHunks(edits: [DiffEdit], contextLines: Int) -> [DiffHunk] {
        guard !edits.isEmpty else { return [] }

        // Find indices of change edits
        let changeIndices = edits.enumerated()
            .filter { $0.element.isChange }
            .map { $0.offset }

        guard !changeIndices.isEmpty else { return [] }

        // Group changes that are close together (within 2 * contextLines)
        var groups: [[Int]] = []
        var currentGroup: [Int] = [changeIndices[0]]

        for i in 1..<changeIndices.count {
            let gap = changeIndices[i] - changeIndices[i - 1]
            if gap <= contextLines * 2 + 1 {
                currentGroup.append(changeIndices[i])
            } else {
                groups.append(currentGroup)
                currentGroup = [changeIndices[i]]
            }
        }
        groups.append(currentGroup)

        // Build hunks from groups
        var hunks: [DiffHunk] = []
        for group in groups {
            guard let firstChange = group.first, let lastChange = group.last else { continue }

            let startIdx = max(0, firstChange - contextLines)
            let endIdx = min(edits.count - 1, lastChange + contextLines)

            let hunkEdits = Array(edits[startIdx...endIdx])

            var oldStart = 0
            var newStart = 0
            var oldCount = 0
            var newCount = 0

            // Determine starting line numbers from the first edit in the hunk
            switch hunkEdits.first! {
            case .context(_, let o, let n):
                oldStart = o + 1
                newStart = n + 1
            case .remove(_, let o):
                oldStart = o + 1
                // Find the new line start from context before/after
                newStart = findNewLineStart(edits: edits, around: startIdx)
            case .add(_, let n):
                newStart = n + 1
                oldStart = findOldLineStart(edits: edits, around: startIdx)
            }

            for edit in hunkEdits {
                switch edit {
                case .context:
                    oldCount += 1
                    newCount += 1
                case .remove:
                    oldCount += 1
                case .add:
                    newCount += 1
                }
            }

            hunks.append(DiffHunk(
                edits: hunkEdits,
                oldStart: oldStart,
                oldCount: oldCount,
                newStart: newStart,
                newCount: newCount
            ))
        }

        return hunks
    }

    private static func findNewLineStart(edits: [DiffEdit], around index: Int) -> Int {
        // Search backward then forward for a context or add edit
        for i in stride(from: index, through: 0, by: -1) {
            switch edits[i] {
            case .context(_, _, let n): return n + 1
            case .add(_, let n): return n + 1
            default: continue
            }
        }
        return 1
    }

    private static func findOldLineStart(edits: [DiffEdit], around index: Int) -> Int {
        for i in stride(from: index, through: 0, by: -1) {
            switch edits[i] {
            case .context(_, let o, _): return o + 1
            case .remove(_, let o): return o + 1
            default: continue
            }
        }
        return 1
    }

    private static func formatHunkHeader(_ hunk: DiffHunk) -> String {
        return "@@ -\(hunk.oldStart),\(hunk.oldCount) +\(hunk.newStart),\(hunk.newCount) @@"
    }
}

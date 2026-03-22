import Foundation

enum SkillManagerError: LocalizedError {
    case invalidSkillName
    case skillNotFound(String)
    case trashEntryNotFound(String)
    case skillAlreadyExists(String)
    case operationFailed(String)

    var errorDescription: String? {
        switch self {
        case .invalidSkillName:
            return "目录名必须是小写 slug，且长度不超过 64 个字符"
        case .skillNotFound(let name):
            return "未找到 Skill: \(name)"
        case .trashEntryNotFound(let entry):
            return "未找到回收站条目: \(entry)"
        case .skillAlreadyExists(let name):
            return "目录已存在: \(name)"
        case .operationFailed(let message):
            return message
        }
    }
}

final class SkillManager {
    static let shared = SkillManager()

    private let fileManager = FileManager.default
    private let skillNamePattern = "^[a-z0-9][a-z0-9-]{0,63}$"
    private let trashTimestampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMddHHmmssSSS"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter
    }()

    private init() {}

    var rootURL: URL {
        fileManager.homeDirectoryForCurrentUser
            .appendingPathComponent(".claude", isDirectory: true)
            .appendingPathComponent("skills", isDirectory: true)
    }

    private var trashURL: URL {
        rootURL.appendingPathComponent(".trash", isDirectory: true)
    }

    func listSkills() throws -> [ClaudeSkillSummary] {
        try ensureRootDirectory()

        let directories = try fileManager.contentsOfDirectory(
            at: rootURL,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )

        return try directories
            .filter { url in
                var isDirectory = ObjCBool(false)
                guard fileManager.fileExists(atPath: url.path, isDirectory: &isDirectory) else { return false }
                return isDirectory.boolValue && url.lastPathComponent != ".trash"
            }
            .map { try readSkillDetail(from: $0).asSummary() }
            .sorted { $0.updatedAt > $1.updatedAt }
    }

    func loadSkill(folderName: String) throws -> ClaudeSkillDetail {
        try validateSkillName(folderName)
        let url = rootURL.appendingPathComponent(folderName, isDirectory: true)
        guard directoryExists(url) else {
            throw SkillManagerError.skillNotFound(folderName)
        }
        return try readSkillDetail(from: url)
    }

    func renameSkill(folderName: String, newFolderName: String) throws -> ClaudeSkillSummary {
        try validateSkillName(folderName)
        try validateSkillName(newFolderName)

        let sourceURL = rootURL.appendingPathComponent(folderName, isDirectory: true)
        let targetURL = rootURL.appendingPathComponent(newFolderName, isDirectory: true)
        guard directoryExists(sourceURL) else {
            throw SkillManagerError.skillNotFound(folderName)
        }
        guard !directoryExists(targetURL) else {
            throw SkillManagerError.skillAlreadyExists(newFolderName)
        }

        do {
            try fileManager.moveItem(at: sourceURL, to: targetURL)
            return try readSkillDetail(from: targetURL).asSummary()
        } catch {
            throw SkillManagerError.operationFailed("重命名失败")
        }
    }

    func moveToTrash(folderName: String) throws {
        try validateSkillName(folderName)
        try ensureTrashDirectory()

        let sourceURL = rootURL.appendingPathComponent(folderName, isDirectory: true)
        guard directoryExists(sourceURL) else {
            throw SkillManagerError.skillNotFound(folderName)
        }

        let timestamp = trashTimestampFormatter.string(from: Date())
        let targetURL = trashURL.appendingPathComponent("\(timestamp)__\(folderName)", isDirectory: true)

        do {
            try fileManager.moveItem(at: sourceURL, to: targetURL)
        } catch {
            throw SkillManagerError.operationFailed("移入回收站失败")
        }
    }

    func listTrash() throws -> [ClaudeTrashSkillEntry] {
        try ensureTrashDirectory()

        let directories = try fileManager.contentsOfDirectory(
            at: trashURL,
            includingPropertiesForKeys: [.isDirectoryKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        )

        return try directories
            .filter { directoryExists($0) }
            .map { try mapTrashEntry(from: $0) }
            .sorted { $0.deletedAt > $1.deletedAt }
    }

    func restoreTrash(trashEntry: String) throws -> ClaudeSkillSummary {
        let sourceURL = trashURL.appendingPathComponent(trashEntry, isDirectory: true)
        guard directoryExists(sourceURL) else {
            throw SkillManagerError.trashEntryNotFound(trashEntry)
        }

        let originalName = parseTrashEntryName(trashEntry).originalFolderName
        let restoredName = nextAvailableRestoreName(for: originalName)
        let targetURL = rootURL.appendingPathComponent(restoredName, isDirectory: true)

        do {
            try fileManager.moveItem(at: sourceURL, to: targetURL)
            return try readSkillDetail(from: targetURL).asSummary()
        } catch {
            throw SkillManagerError.operationFailed("恢复 Skill 失败")
        }
    }

    private func ensureRootDirectory() throws {
        do {
            try fileManager.createDirectory(at: rootURL, withIntermediateDirectories: true, attributes: nil)
        } catch {
            throw SkillManagerError.operationFailed("无法访问 Skills 根目录")
        }
    }

    private func ensureTrashDirectory() throws {
        try ensureRootDirectory()
        do {
            try fileManager.createDirectory(at: trashURL, withIntermediateDirectories: true, attributes: nil)
        } catch {
            throw SkillManagerError.operationFailed("无法访问回收站目录")
        }
    }

    private func validateSkillName(_ folderName: String) throws {
        guard folderName.range(of: skillNamePattern, options: .regularExpression) != nil else {
            throw SkillManagerError.invalidSkillName
        }
    }

    private func directoryExists(_ url: URL) -> Bool {
        var isDirectory = ObjCBool(false)
        return fileManager.fileExists(atPath: url.path, isDirectory: &isDirectory) && isDirectory.boolValue
    }

    private func readSkillDetail(from directoryURL: URL) throws -> ClaudeSkillDetail {
        let folderName = directoryURL.lastPathComponent
        let skillMdURL = directoryURL.appendingPathComponent("SKILL.md")
        let scriptsURL = directoryURL.appendingPathComponent("scripts", isDirectory: true)
        let referencesURL = directoryURL.appendingPathComponent("references", isDirectory: true)
        let assetsURL = directoryURL.appendingPathComponent("assets", isDirectory: true)

        var warnings: [String] = []
        let hasSkillMd = fileManager.fileExists(atPath: skillMdURL.path)
        let hasLicense = hasVisibleLicense(in: directoryURL)
        let hasScripts = directoryExists(scriptsURL)
        let hasReferences = directoryExists(referencesURL)
        let hasAssets = directoryExists(assetsURL)

        var skillMdContent: String?
        var frontmatter: [String: String] = [:]
        if hasSkillMd {
            do {
                skillMdContent = try String(contentsOf: skillMdURL, encoding: .utf8)
                frontmatter = parseFrontmatter(from: skillMdContent ?? "", warnings: &warnings)
            } catch {
                warnings.append("Failed to read SKILL.md")
            }
        } else {
            warnings.append("Missing SKILL.md")
        }

        let stats = try computeStats(for: directoryURL)
        let displayName = firstNonEmpty(frontmatter["name"], fallback: folderName)
        let description = frontmatter["description"]

        return ClaudeSkillDetail(
            folderName: folderName,
            displayName: displayName,
            absolutePath: directoryURL.path,
            description: description,
            status: hasSkillMd ? .valid : .invalid,
            hasSkillMd: hasSkillMd,
            hasLicense: hasLicense,
            hasScripts: hasScripts,
            hasReferences: hasReferences,
            hasAssets: hasAssets,
            fileCount: stats.fileCount,
            sizeBytes: stats.sizeBytes,
            updatedAt: stats.updatedAt,
            warnings: warnings,
            skillMdContent: skillMdContent,
            frontmatter: frontmatter,
            files: try listFiles(in: directoryURL)
        )
    }

    private func computeStats(for directoryURL: URL) throws -> (fileCount: Int, sizeBytes: Int64, updatedAt: Date) {
        let resourceKeys: Set<URLResourceKey> = [.isDirectoryKey, .isHiddenKey, .contentModificationDateKey, .fileSizeKey]
        guard let enumerator = fileManager.enumerator(
            at: directoryURL,
            includingPropertiesForKeys: Array(resourceKeys),
            options: [.skipsHiddenFiles]
        ) else {
            return (0, 0, directoryModificationDate(directoryURL))
        }

        var fileCount = 0
        var sizeBytes: Int64 = 0
        var updatedAt = directoryModificationDate(directoryURL)

        for case let fileURL as URL in enumerator {
            let values = try fileURL.resourceValues(forKeys: resourceKeys)
            if values.isHidden == true {
                continue
            }
            if let modified = values.contentModificationDate, modified > updatedAt {
                updatedAt = modified
            }
            if values.isDirectory != true {
                fileCount += 1
                sizeBytes += Int64(values.fileSize ?? 0)
            }
        }

        return (fileCount, sizeBytes, updatedAt)
    }

    private func listFiles(in directoryURL: URL) throws -> [ClaudeSkillFileEntry] {
        let resourceKeys: Set<URLResourceKey> = [.isDirectoryKey, .isHiddenKey, .contentModificationDateKey, .fileSizeKey]
        guard let enumerator = fileManager.enumerator(
            at: directoryURL,
            includingPropertiesForKeys: Array(resourceKeys),
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }

        var files: [ClaudeSkillFileEntry] = []
        for case let fileURL as URL in enumerator {
            let values = try fileURL.resourceValues(forKeys: resourceKeys)
            if values.isHidden == true {
                continue
            }
            let relativePath = fileURL.path.replacingOccurrences(of: directoryURL.path + "/", with: "")
            files.append(
                ClaudeSkillFileEntry(
                    relativePath: relativePath,
                    type: values.isDirectory == true ? .directory : .file,
                    sizeBytes: Int64(values.fileSize ?? 0),
                    updatedAt: values.contentModificationDate ?? directoryModificationDate(fileURL)
                )
            )
        }

        return files.sorted { $0.relativePath.localizedCaseInsensitiveCompare($1.relativePath) == .orderedAscending }
    }

    private func hasVisibleLicense(in directoryURL: URL) -> Bool {
        guard let contents = try? fileManager.contentsOfDirectory(atPath: directoryURL.path) else {
            return false
        }
        return contents.contains { item in
            !item.hasPrefix(".") && item.uppercased().hasPrefix("LICENSE")
        }
    }

    private func parseFrontmatter(from content: String, warnings: inout [String]) -> [String: String] {
        var result: [String: String] = [:]

        guard content.hasPrefix("---") else {
            warnings.append("Missing frontmatter block")
            return result
        }

        let lines = content.components(separatedBy: .newlines)
        guard lines.count >= 3 else {
            warnings.append("Unclosed frontmatter block")
            return result
        }

        var collectedLines: [String] = []
        var closingIndex: Int?
        for index in 1..<lines.count {
            if lines[index] == "---" {
                closingIndex = index
                break
            }
            collectedLines.append(lines[index])
        }

        guard closingIndex != nil else {
            warnings.append("Unclosed frontmatter block")
            return result
        }

        if collectedLines.allSatisfy({ $0.trimmingCharacters(in: .whitespaces).isEmpty }) {
            warnings.append("Empty frontmatter block")
            return result
        }

        for line in collectedLines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty || trimmed.hasPrefix("#") {
                continue
            }
            guard let separator = trimmed.firstIndex(of: ":") else {
                warnings.append("Skipped malformed frontmatter line: \(trimmed)")
                continue
            }
            let key = String(trimmed[..<separator]).trimmingCharacters(in: .whitespaces)
            let rawValue = String(trimmed[trimmed.index(after: separator)...]).trimmingCharacters(in: .whitespaces)
            let value = stripQuotes(rawValue)
            if ["name", "description", "license", "compatibility"].contains(key), !value.isEmpty {
                result[key] = value
            }
        }

        return result
    }

    private func stripQuotes(_ value: String) -> String {
        guard value.count >= 2 else { return value }
        if (value.hasPrefix("\"") && value.hasSuffix("\"")) || (value.hasPrefix("'") && value.hasSuffix("'")) {
            return String(value.dropFirst().dropLast()).trimmingCharacters(in: .whitespaces)
        }
        return value
    }

    private func mapTrashEntry(from directoryURL: URL) throws -> ClaudeTrashSkillEntry {
        let parsed = parseTrashEntryName(directoryURL.lastPathComponent)
        return ClaudeTrashSkillEntry(
            trashEntry: directoryURL.lastPathComponent,
            originalFolderName: parsed.originalFolderName,
            absolutePath: directoryURL.path,
            deletedAt: parsed.deletedAt
        )
    }

    private func parseTrashEntryName(_ trashEntry: String) -> (originalFolderName: String, deletedAt: Date) {
        let parts = trashEntry.components(separatedBy: "__")
        if parts.count == 2, let date = trashTimestampFormatter.date(from: parts[0]) {
            return (parts[1], date)
        }
        return (trashEntry, directoryModificationDate(trashURL.appendingPathComponent(trashEntry, isDirectory: true)))
    }

    private func nextAvailableRestoreName(for originalFolderName: String) -> String {
        let candidateURL = rootURL.appendingPathComponent(originalFolderName, isDirectory: true)
        guard !directoryExists(candidateURL) else {
            var suffix = 1
            while directoryExists(rootURL.appendingPathComponent("\(originalFolderName)-restored-\(suffix)", isDirectory: true)) {
                suffix += 1
            }
            return "\(originalFolderName)-restored-\(suffix)"
        }
        return originalFolderName
    }

    private func directoryModificationDate(_ url: URL) -> Date {
        let values = try? url.resourceValues(forKeys: [.contentModificationDateKey])
        return values?.contentModificationDate ?? Date.distantPast
    }

    private func firstNonEmpty(_ value: String?, fallback: String) -> String {
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return fallback
        }
        return value
    }
}

private extension ClaudeSkillDetail {
    func asSummary() -> ClaudeSkillSummary {
        ClaudeSkillSummary(
            folderName: folderName,
            displayName: displayName,
            absolutePath: absolutePath,
            description: description,
            status: status,
            hasSkillMd: hasSkillMd,
            hasLicense: hasLicense,
            hasScripts: hasScripts,
            hasReferences: hasReferences,
            hasAssets: hasAssets,
            fileCount: fileCount,
            sizeBytes: sizeBytes,
            updatedAt: updatedAt,
            warnings: warnings
        )
    }
}

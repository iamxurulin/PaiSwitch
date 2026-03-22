import Foundation

enum ClaudeSkillStatus: String, Codable, CaseIterable {
    case valid
    case invalid
}

struct ClaudeSkillSummary: Identifiable, Hashable {
    let folderName: String
    let displayName: String
    let absolutePath: String
    let description: String?
    let status: ClaudeSkillStatus
    let hasSkillMd: Bool
    let hasLicense: Bool
    let hasScripts: Bool
    let hasReferences: Bool
    let hasAssets: Bool
    let fileCount: Int
    let sizeBytes: Int64
    let updatedAt: Date
    let warnings: [String]

    var id: String { folderName }
}

struct ClaudeSkillDetail: Identifiable, Hashable {
    let folderName: String
    let displayName: String
    let absolutePath: String
    let description: String?
    let status: ClaudeSkillStatus
    let hasSkillMd: Bool
    let hasLicense: Bool
    let hasScripts: Bool
    let hasReferences: Bool
    let hasAssets: Bool
    let fileCount: Int
    let sizeBytes: Int64
    let updatedAt: Date
    let warnings: [String]
    let skillMdContent: String?
    let frontmatter: [String: String]
    let files: [ClaudeSkillFileEntry]

    var id: String { folderName }
}

struct ClaudeSkillFileEntry: Identifiable, Hashable {
    let relativePath: String
    let type: FileType
    let sizeBytes: Int64
    let updatedAt: Date

    var id: String { relativePath }

    enum FileType: String, Hashable {
        case file
        case directory
    }
}

struct ClaudeTrashSkillEntry: Identifiable, Hashable {
    let trashEntry: String
    let originalFolderName: String
    let absolutePath: String
    let deletedAt: Date

    var id: String { trashEntry }
}

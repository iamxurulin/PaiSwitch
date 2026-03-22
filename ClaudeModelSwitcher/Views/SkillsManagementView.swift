import AppKit
import SwiftUI

struct SkillsManagementView: View {
    @State private var skills: [ClaudeSkillSummary] = []
    @State private var trashEntries: [ClaudeTrashSkillEntry] = []
    @State private var selectedSkill: ClaudeSkillDetail?
    @State private var selectedTrash: ClaudeTrashSkillEntry?
    @State private var activeTab: Tab = .skills
    @State private var searchText = ""
    @State private var statusFilter: StatusFilter = .all
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var showingRenameSheet = false

    private let manager = SkillManager.shared

    enum Tab: String, CaseIterable {
        case skills
        case trash
    }

    enum StatusFilter: String, CaseIterable {
        case all
        case valid
        case invalid
    }

    private var filteredSkills: [ClaudeSkillSummary] {
        skills.filter { skill in
            let matchesStatus: Bool
            switch statusFilter {
            case .all:
                matchesStatus = true
            case .valid:
                matchesStatus = skill.status == .valid
            case .invalid:
                matchesStatus = skill.status == .invalid
            }

            guard matchesStatus else { return false }
            guard !searchText.isEmpty else { return true }

            let keyword = searchText.lowercased()
            return [
                skill.folderName.lowercased(),
                skill.displayName.lowercased(),
                (skill.description ?? "").lowercased()
            ].contains { $0.contains(keyword) }
        }
    }

    private var filteredTrash: [ClaudeTrashSkillEntry] {
        guard !searchText.isEmpty else { return trashEntries }
        let keyword = searchText.lowercased()
        return trashEntries.filter { entry in
            [entry.trashEntry, entry.originalFolderName, entry.absolutePath]
                .contains { $0.lowercased().contains(keyword) }
        }
    }

    private var selectedSkillFolderBinding: Binding<String?> {
        Binding(
            get: { selectedSkill?.folderName },
            set: { newValue in
                guard let newValue else { return }
                loadSkillDetail(folderName: newValue)
            }
        )
    }

    private var selectedTrashEntryBinding: Binding<String?> {
        Binding(
            get: { selectedTrash?.trashEntry },
            set: { newValue in
                guard let newValue else { return }
                selectedTrash = filteredTrash.first(where: { $0.trashEntry == newValue })
            }
        )
    }

    @ViewBuilder
    private var detailContent: some View {
        if activeTab == .skills {
            SkillDetailView(
                skill: selectedSkill,
                onCopyPath: { copyToPasteboard($0) },
                onReveal: { revealInFinder($0) },
                onRename: { showingRenameSheet = true },
                onTrash: moveSelectedSkillToTrash
            )
        } else {
            TrashDetailView(
                entry: selectedTrash,
                onCopyPath: { copyToPasteboard($0) },
                onReveal: { revealInFinder($0) },
                onRestore: restoreSelectedTrash
            )
        }
    }

    var body: some View {
        HSplitView {
            sidebar
                .frame(minWidth: 320, idealWidth: 360, maxWidth: 420)

            detailContent
            .frame(minWidth: 520)
        }
        .navigationTitle("Skills")
        .toolbar {
            ToolbarItemGroup(placement: .automatic) {
                Button {
                    loadAll()
                } label: {
                    Label("刷新", systemImage: "arrow.clockwise")
                }

                Button {
                    copyToPasteboard(manager.rootURL.path)
                } label: {
                    Label("复制根目录", systemImage: "doc.on.doc")
                }
            }
        }
        .sheet(isPresented: $showingRenameSheet) {
            RenameSkillSheet(
                currentName: selectedSkill?.folderName ?? "",
                onSave: renameSelectedSkill
            )
        }
        .task {
            loadAll()
        }
        .onChange(of: activeTab) { newTab in
            searchText = ""
            if newTab == .skills {
                selectedTrash = nil
                if selectedSkill == nil {
                    selectFirstSkillIfNeeded()
                }
            } else {
                selectedSkill = nil
                selectedTrash = filteredTrash.first
            }
        }
        .onChange(of: skills) { _ in
            if activeTab == .skills {
                selectFirstSkillIfNeeded()
            }
        }
        .onChange(of: trashEntries) { _ in
            if activeTab == .trash {
                selectedTrash = filteredTrash.first
            }
        }
    }

    private var sidebar: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Claude Code Skills")
                        .font(.system(size: 26, weight: .bold, design: .rounded))
                    Text("管理本机 ~/.claude/skills，支持查看目录、预览 SKILL.md、重命名和软删除恢复。")
                        .foregroundStyle(.secondary)
                }

                Picker("Tab", selection: $activeTab) {
                    Text("主目录").tag(Tab.skills)
                    Text("回收站").tag(Tab.trash)
                }
                .pickerStyle(.segmented)

                TextField(activeTab == .skills ? "搜索 Skill 名称、目录名、描述" : "搜索回收站条目", text: $searchText)
                    .textFieldStyle(.roundedBorder)

                if activeTab == .skills {
                    Picker("状态", selection: $statusFilter) {
                        Text("全部").tag(StatusFilter.all)
                        Text("有效").tag(StatusFilter.valid)
                        Text("异常").tag(StatusFilter.invalid)
                    }
                    .pickerStyle(.segmented)
                }

                summaryPanel
            }
            .padding(20)

            Divider()

            if let errorMessage {
                Text(errorMessage)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .padding(.horizontal, 20)
                    .padding(.top, 12)
            }

            Group {
                if activeTab == .skills {
                    if filteredSkills.isEmpty && !isLoading {
                        EmptyStateView(
                            title: "没有可展示的 Skills",
                            systemImage: "square.stack.3d.up.slash",
                            description: "可以检查 ~/.claude/skills 目录，或者切换筛选条件。"
                        )
                    } else {
                        List(filteredSkills, selection: selectedSkillFolderBinding) { skill in
                            SkillRowView(skill: skill)
                                .tag(skill.folderName)
                        }
                        .listStyle(.sidebar)
                    }
                } else {
                    if filteredTrash.isEmpty && !isLoading {
                        EmptyStateView(
                            title: "回收站为空",
                            systemImage: "trash.slash",
                            description: "移入回收站的目录会出现在这里。"
                        )
                    } else {
                        List(filteredTrash, selection: selectedTrashEntryBinding) { entry in
                            TrashRowView(entry: entry)
                                .tag(entry.trashEntry)
                        }
                        .listStyle(.sidebar)
                    }
                }
            }
            .overlay {
                if isLoading {
                    ProgressView()
                        .controlSize(.regular)
                }
            }
        }
    }

    private var summaryPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            SummaryChip(label: "主目录", value: "\(skills.count)", tint: .blue)
            SummaryChip(label: "异常目录", value: "\(skills.filter { $0.status == .invalid }.count)", tint: .orange)
            SummaryChip(label: "回收站", value: "\(trashEntries.count)", tint: .gray)
            SummaryChip(label: "根路径", value: manager.rootURL.path, tint: .purple)
        }
    }

    private func loadAll() {
        isLoading = true
        errorMessage = nil

        do {
            skills = try manager.listSkills()
            trashEntries = try manager.listTrash()
            if activeTab == .skills {
                selectFirstSkillIfNeeded()
            } else {
                selectedTrash = filteredTrash.first
            }
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    private func selectFirstSkillIfNeeded() {
        guard !filteredSkills.isEmpty else {
            selectedSkill = nil
            return
        }

        if let current = selectedSkill,
           filteredSkills.contains(where: { $0.folderName == current.folderName }) {
            return
        }

        loadSkillDetail(folderName: filteredSkills[0].folderName)
    }

    private func loadSkillDetail(folderName: String) {
        do {
            selectedSkill = try manager.loadSkill(folderName: folderName)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func renameSelectedSkill(_ newFolderName: String) {
        guard let selectedSkill else { return }

        do {
            let renamed = try manager.renameSkill(folderName: selectedSkill.folderName, newFolderName: newFolderName)
            skills = try manager.listSkills()
            self.selectedSkill = try manager.loadSkill(folderName: renamed.folderName)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func moveSelectedSkillToTrash() {
        guard let selectedSkill else { return }
        do {
            try manager.moveToTrash(folderName: selectedSkill.folderName)
            skills = try manager.listSkills()
            trashEntries = try manager.listTrash()
            self.selectedSkill = nil
            selectFirstSkillIfNeeded()
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func restoreSelectedTrash() {
        guard let selectedTrash else { return }
        do {
            let restored = try manager.restoreTrash(trashEntry: selectedTrash.trashEntry)
            skills = try manager.listSkills()
            trashEntries = try manager.listTrash()
            activeTab = .skills
            self.selectedTrash = nil
            self.selectedSkill = try manager.loadSkill(folderName: restored.folderName)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func copyToPasteboard(_ value: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(value, forType: .string)
    }

    private func revealInFinder(_ path: String) {
        NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
    }
}

private struct SummaryChip: View {
    let label: String
    let value: String
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(label == "根路径" ? .system(.caption, design: .monospaced) : .headline)
                .foregroundStyle(tint)
                .lineLimit(label == "根路径" ? 2 : 1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(tint.opacity(0.08), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct SkillRowView: View {
    let skill: ClaudeSkillSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(skill.displayName)
                    .font(.headline)
                Spacer()
                Text(skill.status.rawValue)
                    .font(.caption2)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(skill.status == .valid ? Color.green.opacity(0.15) : Color.orange.opacity(0.18))
                    .foregroundStyle(skill.status == .valid ? .green : .orange)
                    .clipShape(Capsule())
            }

            Text(skill.description ?? "没有 description，建议后续补齐 frontmatter。")
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)

            HStack(spacing: 10) {
                Text(skill.folderName)
                Text("\(skill.fileCount) files")
                Text(ByteCountFormatter.string(fromByteCount: skill.sizeBytes, countStyle: .file))
            }
            .font(.caption2)
            .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 6)
    }
}

private struct TrashRowView: View {
    let entry: ClaudeTrashSkillEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(entry.originalFolderName)
                .font(.headline)
            Text(entry.trashEntry)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Text(entry.deletedAt.formatted(date: .abbreviated, time: .shortened))
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 6)
    }
}

struct SkillDetailView: View {
    let skill: ClaudeSkillDetail?
    let onCopyPath: (String) -> Void
    let onReveal: (String) -> Void
    let onRename: () -> Void
    let onTrash: () -> Void

    var body: some View {
        Group {
            if let skill {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        header(skill)

                        HStack(spacing: 10) {
                            FeatureBadge(title: "SKILL.md", active: skill.hasSkillMd)
                            FeatureBadge(title: "License", active: skill.hasLicense)
                            FeatureBadge(title: "Scripts", active: skill.hasScripts)
                            FeatureBadge(title: "References", active: skill.hasReferences)
                            FeatureBadge(title: "Assets", active: skill.hasAssets)
                        }

                        infoGrid(skill)

                        codeSection(skill)

                        filesSection(skill)
                    }
                    .padding(24)
                }
            } else {
                EmptyStateView(
                    title: "选择一个 Skill",
                    systemImage: "square.stack.3d.up",
                    description: "左侧列表会展示主目录下的 Skills 和异常目录。"
                )
            }
        }
    }

    private func header(_ skill: ClaudeSkillDetail) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(skill.folderName.uppercased())
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(skill.displayName)
                .font(.system(size: 30, weight: .bold, design: .rounded))
            Text(skill.description ?? "这个 Skill 还没有清晰的 description，当前更适合作为需要整理 metadata 的候选项。")
                .foregroundStyle(.secondary)

            HStack {
                Button("复制路径") { onCopyPath(skill.absolutePath) }
                Button("在 Finder 中显示") { onReveal(skill.absolutePath) }
                Button("重命名") { onRename() }
                Button("移入回收站", role: .destructive) { onTrash() }
            }
            .buttonStyle(.bordered)
        }
    }

    private func infoGrid(_ skill: ClaudeSkillDetail) -> some View {
        HStack(alignment: .top, spacing: 16) {
            DetailCard(title: "概览") {
                MetaLine(label: "路径", value: skill.absolutePath)
                MetaLine(label: "状态", value: skill.status.rawValue)
                MetaLine(label: "最后更新", value: skill.updatedAt.formatted(date: .abbreviated, time: .shortened))
                MetaLine(label: "文件数", value: "\(skill.fileCount)")
                MetaLine(label: "大小", value: ByteCountFormatter.string(fromByteCount: skill.sizeBytes, countStyle: .file))
            }

            DetailCard(title: "Frontmatter") {
                if skill.frontmatter.isEmpty {
                    Text("没有可识别的 frontmatter 字段。")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(skill.frontmatter.keys.sorted(), id: \.self) { key in
                        MetaLine(label: key, value: skill.frontmatter[key] ?? "")
                    }
                }
            }

            DetailCard(title: "Warnings") {
                if skill.warnings.isEmpty {
                    Text("这个 Skill 的基础结构看起来正常。")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(skill.warnings, id: \.self) { warning in
                        Text("• \(warning)")
                            .foregroundStyle(.orange)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
    }

    private func codeSection(_ skill: ClaudeSkillDetail) -> some View {
        DetailCard(title: "SKILL.md 预览") {
            ScrollView(.horizontal) {
                Text(skill.skillMdContent ?? "未找到 SKILL.md")
                    .font(.system(.body, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            .frame(maxHeight: 360)
            .padding()
            .background(Color.black.opacity(0.9), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .foregroundStyle(Color.white)
        }
    }

    private func filesSection(_ skill: ClaudeSkillDetail) -> some View {
        DetailCard(title: "文件清单") {
            if skill.files.isEmpty {
                Text("这个目录当前没有可展示的文件。")
                    .foregroundStyle(.secondary)
            } else {
                VStack(spacing: 10) {
                    ForEach(skill.files) { file in
                        HStack(alignment: .top) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(file.relativePath)
                                    .fontWeight(.medium)
                                Text(file.type == .directory ? "目录" : ByteCountFormatter.string(fromByteCount: file.sizeBytes, countStyle: .file))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(file.updatedAt.formatted(date: .abbreviated, time: .shortened))
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                        .padding(12)
                        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                }
            }
        }
    }
}

private struct TrashDetailView: View {
    let entry: ClaudeTrashSkillEntry?
    let onCopyPath: (String) -> Void
    let onReveal: (String) -> Void
    let onRestore: () -> Void

    var body: some View {
        Group {
            if let entry {
                VStack(alignment: .leading, spacing: 20) {
                    Text("Trash Entry")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(entry.originalFolderName)
                        .font(.system(size: 30, weight: .bold, design: .rounded))
                    Text("软删除后的目录会保留在这里。恢复时如果主目录已有同名项，应用会自动追加 -restored-n，避免覆盖。")
                        .foregroundStyle(.secondary)

                    HStack {
                        Button("复制路径") { onCopyPath(entry.absolutePath) }
                        Button("在 Finder 中显示") { onReveal(entry.absolutePath) }
                        Button("恢复到主目录") { onRestore() }
                    }
                    .buttonStyle(.bordered)

                    DetailCard(title: "回收站信息") {
                        MetaLine(label: "原目录名", value: entry.originalFolderName)
                        MetaLine(label: "回收站条目", value: entry.trashEntry)
                        MetaLine(label: "删除时间", value: entry.deletedAt.formatted(date: .abbreviated, time: .shortened))
                        MetaLine(label: "路径", value: entry.absolutePath)
                    }

                    Spacer()
                }
                .padding(24)
            } else {
                EmptyStateView(
                    title: "选择一个回收站条目",
                    systemImage: "trash",
                    description: "回收站中的目录不会混入主目录列表。"
                )
            }
        }
    }
}

private struct EmptyStateView: View {
    let title: String
    let systemImage: String
    let description: String

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: systemImage)
                .font(.system(size: 34))
                .foregroundStyle(.secondary)
            Text(title)
                .font(.headline)
            Text(description)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(24)
    }
}

private struct DetailCard<Content: View>: View {
    let title: String
    let content: Content

    init(title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title)
                .font(.headline)
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.black.opacity(0.06), lineWidth: 1)
        )
    }
}

private struct MetaLine: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label.uppercased())
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text(value)
                .textSelection(.enabled)
        }
    }
}

private struct FeatureBadge: View {
    let title: String
    let active: Bool

    var body: some View {
        Text(title)
            .font(.caption)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(active ? Color.green.opacity(0.16) : Color.secondary.opacity(0.12))
            .foregroundStyle(active ? .green : .secondary)
            .clipShape(Capsule())
    }
}

struct RenameSkillSheet: View {
    let currentName: String
    let onSave: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var newFolderName = ""
    @State private var errorMessage: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("重命名 Skill")
                .font(.title3.bold())
            Text("只会修改目录名，不会改写 SKILL.md 内容。")
                .foregroundStyle(.secondary)

            TextField("新目录名", text: $newFolderName)
                .textFieldStyle(.roundedBorder)

            Text("当前目录: \(currentName)")
                .font(.caption)
                .foregroundStyle(.secondary)

            if let errorMessage {
                Text(errorMessage)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            HStack {
                Spacer()
                Button("取消") { dismiss() }
                Button("保存") {
                    let trimmed = newFolderName.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmed.isEmpty else {
                        errorMessage = "请输入新的目录名"
                        return
                    }
                    onSave(trimmed)
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(24)
        .frame(width: 420)
        .onAppear {
            newFolderName = currentName
        }
    }
}

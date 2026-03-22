<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useSkillStore } from '@/stores/skill'
import { useToastStore } from '@/stores/toast'
import type { SkillSummary, TrashSkillEntry } from '@/types'

const skillStore = useSkillStore()
const toastStore = useToastStore()

const activeTab = ref<'skills' | 'trash'>('skills')
const search = ref('')
const statusFilter = ref<'all' | 'valid' | 'invalid'>('all')
const selectedTrash = ref<TrashSkillEntry | null>(null)
const isPreviewExpanded = ref(false)

const skills = computed(() => skillStore.skillList?.skills ?? [])
const stats = computed(() => skillStore.skillList)
const shouldShowPreviewToggle = computed(() => (skillStore.selectedSkill?.skillMdContent?.length ?? 0) > 900)

const filteredSkills = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  return skills.value.filter((skill) => {
    const matchesStatus = statusFilter.value === 'all' || skill.status === statusFilter.value
    if (!matchesStatus) return false
    if (!keyword) return true

    const frontmatterName = skill.displayName.toLowerCase()
    const description = (skill.description || '').toLowerCase()
    const folderName = skill.folderName.toLowerCase()
    return [frontmatterName, description, folderName].some((value) => value.includes(keyword))
  })
})

const filteredTrash = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  if (!keyword) return skillStore.trashEntries

  return skillStore.trashEntries.filter((entry) => {
    return [entry.trashEntry, entry.originalFolderName, entry.absolutePath]
      .some((value) => value.toLowerCase().includes(keyword))
  })
})

const selectedSkillBadges = computed(() => {
  const skill = skillStore.selectedSkill
  if (!skill) return []
  return [
    { label: 'SKILL.md', active: skill.hasSkillMd },
    { label: 'License', active: skill.hasLicense },
    { label: 'Scripts', active: skill.hasScripts },
    { label: 'References', active: skill.hasReferences },
    { label: 'Assets', active: skill.hasAssets }
  ]
})

const selectedTrashMeta = computed(() => {
  if (!selectedTrash.value) return []
  return [
    { label: '原目录名', value: selectedTrash.value.originalFolderName },
    { label: '回收站条目', value: selectedTrash.value.trashEntry },
    { label: '删除时间', value: formatDate(selectedTrash.value.deletedAt) }
  ]
})

watch(activeTab, async (tab) => {
  search.value = ''
  if (tab === 'skills') {
    selectedTrash.value = null
    if (!skillStore.selectedSkill && filteredSkills.value[0]) {
      await skillStore.loadSkillDetail(filteredSkills.value[0].folderName)
    }
  } else {
    skillStore.clearSelection()
    selectedTrash.value = filteredTrash.value[0] ?? null
  }
})

watch(filteredSkills, async (nextSkills) => {
  if (activeTab.value !== 'skills') return
  if (!nextSkills.length) {
    skillStore.clearSelection()
    return
  }
  const current = skillStore.selectedSkill?.folderName
  if (!current || !nextSkills.some((skill) => skill.folderName === current)) {
    await skillStore.loadSkillDetail(nextSkills[0].folderName)
  }
})

watch(filteredTrash, (entries) => {
  if (activeTab.value !== 'trash') return
  if (!entries.length) {
    selectedTrash.value = null
    return
  }
  if (!selectedTrash.value || !entries.some((entry) => entry.trashEntry === selectedTrash.value?.trashEntry)) {
    selectedTrash.value = entries[0]
  }
})

watch(() => skillStore.selectedSkill?.folderName, () => {
  isPreviewExpanded.value = false
})

onMounted(async () => {
  try {
    await skillStore.init()
    if (skills.value[0]) {
      await skillStore.loadSkillDetail(skills.value[0].folderName)
    }
    selectedTrash.value = skillStore.trashEntries[0] ?? null
  } catch (error) {
    handleError(error, '加载 Skills 失败')
  }
})

async function selectSkill(skill: SkillSummary) {
  try {
    await skillStore.loadSkillDetail(skill.folderName)
  } catch (error) {
    handleError(error, '加载技能详情失败')
  }
}

async function refreshData() {
  try {
    await skillStore.init()
    if (activeTab.value === 'skills') {
      if (skillStore.selectedSkill) {
        await skillStore.refreshCurrentDetail()
      } else if (filteredSkills.value[0]) {
        await skillStore.loadSkillDetail(filteredSkills.value[0].folderName)
      }
    } else {
      selectedTrash.value = filteredTrash.value[0] ?? null
    }
    toastStore.success('Skills 列表已刷新')
  } catch (error) {
    handleError(error, '刷新失败')
  }
}

async function renameSelectedSkill() {
  const current = skillStore.selectedSkill
  if (!current) return

  const nextName = window.prompt('输入新的目录名（小写 slug）', current.folderName)
  if (!nextName || nextName === current.folderName) return

  try {
    const renamed = await skillStore.renameSkill(current.folderName, nextName.trim())
    toastStore.success(`已重命名为 ${renamed.folderName}`)
  } catch (error) {
    handleError(error, '重命名失败')
  }
}

async function trashSelectedSkill() {
  const current = skillStore.selectedSkill
  if (!current) return
  if (!window.confirm(`确定将 ${current.displayName} 移入回收站吗？`)) return

  try {
    await skillStore.moveSkillToTrash(current.folderName)
    toastStore.success(`${current.displayName} 已移入回收站`)
  } catch (error) {
    handleError(error, '移入回收站失败')
  }
}

async function restoreSelectedTrash() {
  const current = selectedTrash.value
  if (!current) return

  try {
    const restored = await skillStore.restoreTrashEntry(current.trashEntry)
    activeTab.value = 'skills'
    toastStore.success(`${restored.displayName} 已恢复`)
  } catch (error) {
    handleError(error, '恢复失败')
  }
}

async function copyPath(value: string, label: string) {
  try {
    await navigator.clipboard.writeText(value)
    toastStore.success(`${label}已复制`)
  } catch {
    toastStore.error('复制失败，请检查浏览器权限')
  }
}

function formatDate(value?: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function formatBytes(value?: number) {
  if (!value) return '0 B'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / (1024 * 1024)).toFixed(1)} MB`
}

function handleError(error: unknown, fallback: string) {
  const err = error as { response?: { data?: { message?: string } }; message?: string }
  toastStore.error(err.response?.data?.message || err.message || fallback)
}
</script>

<template>
  <div class="skills-shell">
    <section class="skills-hero">
      <div>
        <p class="skills-kicker">Claude Code Skills</p>
        <h2 class="skills-title">把本机 `~/.claude/skills` 变成可搜索、可整理、可恢复的技能库</h2>
        <p class="skills-subtitle">
          这一页专门管理本机 Skills 目录，支持查看 metadata、预览 SKILL.md、重命名目录，以及软删除到回收站后再恢复。
        </p>
      </div>

      <div class="skills-hero-actions">
        <button class="skills-primary-btn" @click="refreshData" :disabled="skillStore.loadingList || skillStore.acting">
          刷新
        </button>
        <button
          class="skills-secondary-btn"
          @click="copyPath(stats?.rootPath || '', '根目录')"
          :disabled="!stats?.rootPath"
        >
          复制根目录
        </button>
      </div>
    </section>

    <section class="skills-metrics">
      <article class="metric-card">
        <span class="metric-label">主目录 Skills</span>
        <strong class="metric-value">{{ stats?.totalSkills ?? 0 }}</strong>
      </article>
      <article class="metric-card metric-card--warning">
        <span class="metric-label">异常目录</span>
        <strong class="metric-value">{{ stats?.invalidSkills ?? 0 }}</strong>
      </article>
      <article class="metric-card metric-card--muted">
        <span class="metric-label">回收站</span>
        <strong class="metric-value">{{ stats?.trashedSkills ?? 0 }}</strong>
      </article>
      <article class="metric-card metric-card--path">
        <span class="metric-label">当前根路径</span>
        <strong class="metric-path">{{ stats?.rootPath || '~/.claude/skills' }}</strong>
      </article>
    </section>

    <section class="skills-workspace">
      <aside class="skills-sidebar">
        <div class="sidebar-toolbar">
          <div class="tab-switcher">
            <button
              class="tab-switcher__btn"
              :class="{ 'tab-switcher__btn--active': activeTab === 'skills' }"
              @click="activeTab = 'skills'"
            >
              主目录
            </button>
            <button
              class="tab-switcher__btn"
              :class="{ 'tab-switcher__btn--active': activeTab === 'trash' }"
              @click="activeTab = 'trash'"
            >
              回收站
            </button>
          </div>

          <input
            v-model="search"
            class="toolbar-input"
            :placeholder="activeTab === 'skills' ? '搜索目录名 / 名称 / 描述' : '搜索回收站条目'"
          />

          <div v-if="activeTab === 'skills'" class="toolbar-select-wrap">
            <select
              v-model="statusFilter"
              class="toolbar-select"
            >
              <option value="all">全部状态</option>
              <option value="valid">有效 Skill</option>
              <option value="invalid">异常目录</option>
            </select>
            <span class="toolbar-select-icon" aria-hidden="true">
              <svg viewBox="0 0 20 20" fill="none">
                <path d="M5 7.5L10 12.5L15 7.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </span>
          </div>
        </div>

        <div v-if="activeTab === 'skills'" class="sidebar-list">
          <button
            v-for="skill in filteredSkills"
            :key="skill.folderName"
            class="skill-card"
            :class="{ 'skill-card--active': skillStore.selectedSkill?.folderName === skill.folderName }"
            @click="selectSkill(skill)"
          >
            <div class="skill-card__header">
              <span class="skill-card__title">{{ skill.displayName }}</span>
              <span class="skill-status" :class="`skill-status--${skill.status}`">{{ skill.status }}</span>
            </div>
            <p class="skill-card__desc">{{ skill.description || '没有 frontmatter description，建议后续补齐。' }}</p>
            <div class="skill-card__meta">
              <span>{{ skill.folderName }}</span>
              <span>{{ formatBytes(skill.sizeBytes) }}</span>
              <span>{{ skill.fileCount }} files</span>
            </div>
          </button>

          <div v-if="!filteredSkills.length && !skillStore.loadingList" class="empty-card">
            <strong>没有匹配的 Skills</strong>
            <span>试试切换状态筛选，或者检查本机 `~/.claude/skills` 是否存在内容。</span>
          </div>
        </div>

        <div v-else class="sidebar-list">
          <button
            v-for="entry in filteredTrash"
            :key="entry.trashEntry"
            class="skill-card skill-card--trash"
            :class="{ 'skill-card--active': selectedTrash?.trashEntry === entry.trashEntry }"
            @click="selectedTrash = entry"
          >
            <div class="skill-card__header">
              <span class="skill-card__title">{{ entry.originalFolderName }}</span>
              <span class="skill-status skill-status--trash">trash</span>
            </div>
            <p class="skill-card__desc">{{ entry.absolutePath }}</p>
            <div class="skill-card__meta">
              <span>{{ entry.trashEntry }}</span>
              <span>{{ formatDate(entry.deletedAt) }}</span>
            </div>
          </button>

          <div v-if="!filteredTrash.length" class="empty-card">
            <strong>回收站是空的</strong>
            <span>移入回收站的 skill 会在这里保留，便于恢复。</span>
          </div>
        </div>
      </aside>

      <section class="skills-detail">
        <template v-if="activeTab === 'skills' && skillStore.selectedSkill">
          <div class="detail-panel">
            <header class="detail-header">
              <div>
                <p class="detail-kicker">{{ skillStore.selectedSkill.folderName }}</p>
                <h3 class="detail-title">{{ skillStore.selectedSkill.displayName }}</h3>
                <p class="detail-copy">
                  {{ skillStore.selectedSkill.description || '这个 skill 还没有清晰的 description，管理台会把它当作需要补齐 metadata 的候选项。' }}
                </p>
              </div>

              <div class="detail-actions">
                <button class="skills-secondary-btn" @click="copyPath(skillStore.selectedSkill.absolutePath, '技能路径')">
                  复制路径
                </button>
                <button class="skills-secondary-btn" @click="renameSelectedSkill" :disabled="skillStore.acting">
                  重命名
                </button>
                <button class="skills-danger-btn" @click="trashSelectedSkill" :disabled="skillStore.acting">
                  移入回收站
                </button>
              </div>
            </header>

            <div class="badge-row">
              <span
                v-for="badge in selectedSkillBadges"
                :key="badge.label"
                class="feature-badge"
                :class="{ 'feature-badge--active': badge.active }"
              >
                {{ badge.label }}
              </span>
            </div>

            <article class="info-card info-card--code info-card--document">
              <div class="document-toolbar">
                <div class="document-title-block">
                  <p class="document-kicker">
                    <span class="document-kicker-dot"></span>
                    Markdown Preview
                  </p>
                  <div class="document-title-row">
                    <h4>SKILL.md 预览</h4>
                    <span class="document-state">
                      {{ shouldShowPreviewToggle && !isPreviewExpanded ? '折叠预览' : '完整预览' }}
                    </span>
                  </div>
                  <p class="document-note">
                    {{ shouldShowPreviewToggle
                      ? '默认先展示开头片段，避免长文档一下子撑满整个详情区。'
                      : '当前文档长度适中，直接展示完整正文。' }}
                  </p>
                </div>

                <div class="document-actions">
                  <span class="preview-mode-badge">{{ skillStore.selectedSkill.skillMdContent ? '原文模式' : '文件缺失' }}</span>
                  <button
                    v-if="shouldShowPreviewToggle"
                    class="preview-toggle"
                    type="button"
                    @click="isPreviewExpanded = !isPreviewExpanded"
                  >
                    {{ isPreviewExpanded ? '收起预览' : '展开全文' }}
                  </button>
                </div>
              </div>
              <div class="code-preview-shell" :class="{ 'code-preview-shell--collapsed': shouldShowPreviewToggle && !isPreviewExpanded }">
                <div class="code-frame-bar">
                  <span class="code-frame-dot code-frame-dot--red"></span>
                  <span class="code-frame-dot code-frame-dot--yellow"></span>
                  <span class="code-frame-dot code-frame-dot--green"></span>
                  <small>{{ isPreviewExpanded ? 'Full document' : 'Preview snippet' }}</small>
                </div>
                <pre class="code-preview" :class="{ 'code-preview--collapsed': shouldShowPreviewToggle && !isPreviewExpanded }">
{{ skillStore.selectedSkill.skillMdContent || '未找到 SKILL.md' }}</pre>
              </div>
            </article>

            <div class="meta-strip">
              <article class="info-card meta-card">
                <h4>概览</h4>
                <dl class="info-list info-list--compact">
                  <div>
                    <dt>绝对路径</dt>
                    <dd>{{ skillStore.selectedSkill.absolutePath }}</dd>
                  </div>
                  <div>
                    <dt>最后更新</dt>
                    <dd>{{ formatDate(skillStore.selectedSkill.updatedAt) }}</dd>
                  </div>
                  <div>
                    <dt>文件总数</dt>
                    <dd>{{ skillStore.selectedSkill.fileCount }}</dd>
                  </div>
                  <div>
                    <dt>目录大小</dt>
                    <dd>{{ formatBytes(skillStore.selectedSkill.sizeBytes) }}</dd>
                  </div>
                </dl>
              </article>

              <article class="info-card meta-card">
                <h4>Frontmatter</h4>
                <dl v-if="Object.keys(skillStore.selectedSkill.frontmatter).length" class="info-list info-list--compact">
                  <div v-for="(value, key) in skillStore.selectedSkill.frontmatter" :key="key">
                    <dt>{{ key }}</dt>
                    <dd>{{ value }}</dd>
                  </div>
                </dl>
                <p v-else class="empty-inline">没有可识别的 frontmatter 字段。</p>
              </article>

              <article class="info-card info-card--warnings meta-card">
                <h4>Warnings</h4>
                <div v-if="skillStore.selectedSkill.warnings.length" class="warning-inline-list">
                  <span v-for="warning in skillStore.selectedSkill.warnings" :key="warning" class="warning-pill">
                    {{ warning }}
                  </span>
                </div>
                <p v-else class="empty-inline">这个 skill 的基础结构看起来正常。</p>
              </article>
            </div>

            <article class="info-card info-card--files">
              <div class="section-head">
                <div>
                  <h4>文件清单</h4>
                  <p class="section-caption">保留完整结构索引，方便对照 `scripts`、`references` 和资源目录。</p>
                </div>
                <span>{{ skillStore.selectedSkill.files.length }} 项</span>
              </div>
              <div v-if="skillStore.selectedSkill.files.length" class="file-list">
                <div v-for="file in skillStore.selectedSkill.files" :key="file.relativePath" class="file-row">
                  <div>
                    <strong>{{ file.relativePath }}</strong>
                    <p>{{ file.type === 'directory' ? '目录' : formatBytes(file.sizeBytes) }}</p>
                  </div>
                  <span>{{ formatDate(file.updatedAt) }}</span>
                </div>
              </div>
              <p v-else class="empty-inline">这个目录目前没有可展示的文件。</p>
            </article>
          </div>
        </template>

        <template v-else-if="activeTab === 'trash' && selectedTrash">
          <div class="detail-panel">
            <header class="detail-header">
              <div>
                <p class="detail-kicker">Trash Entry</p>
                <h3 class="detail-title">{{ selectedTrash.originalFolderName }}</h3>
                <p class="detail-copy">
                  这里保留了软删除后的目录副本。恢复时如果主目录已有同名技能，系统会自动附加 `-restored-n`。
                </p>
              </div>

              <div class="detail-actions">
                <button class="skills-secondary-btn" @click="copyPath(selectedTrash.absolutePath, '回收站路径')">
                  复制路径
                </button>
                <button class="skills-primary-btn" @click="restoreSelectedTrash" :disabled="skillStore.acting">
                  恢复到主目录
                </button>
              </div>
            </header>

            <div class="detail-grid">
              <article class="info-card">
                <h4>回收站信息</h4>
                <dl class="info-list">
                  <div v-for="item in selectedTrashMeta" :key="item.label">
                    <dt>{{ item.label }}</dt>
                    <dd>{{ item.value }}</dd>
                  </div>
                  <div>
                    <dt>绝对路径</dt>
                    <dd>{{ selectedTrash.absolutePath }}</dd>
                  </div>
                </dl>
              </article>

              <article class="info-card info-card--warnings">
                <h4>恢复说明</h4>
                <ul class="warning-list">
                  <li>恢复后会重新出现在主目录列表中。</li>
                  <li>如果同名目录已存在，系统会自动改名避免覆盖。</li>
                  <li>Web 管理台不会直接打开 Finder，仅提供路径复制。</li>
                </ul>
              </article>
            </div>
          </div>
        </template>

        <div v-else class="detail-empty">
          <strong>{{ activeTab === 'skills' ? '选择一个 Skill 查看详情' : '选择一个回收站条目查看恢复信息' }}</strong>
          <span>
            {{ activeTab === 'skills'
              ? '左侧会展示主目录下的所有 skill 和异常目录。'
              : '回收站中的条目不会混入主目录列表。' }}
          </span>
        </div>
      </section>
    </section>
  </div>
</template>

<style scoped>
.skills-shell {
  --skills-surface: #ffffff;
  --skills-surface-soft: #f6f8fb;
  --skills-surface-muted: #eef2f7;
  --skills-border: rgba(15, 23, 42, 0.08);
  --skills-border-strong: rgba(15, 23, 42, 0.14);
  --skills-shadow: 0 14px 32px rgba(15, 23, 42, 0.06);
  --skills-shadow-strong: 0 16px 36px rgba(15, 23, 42, 0.09);
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.skills-hero {
  position: relative;
  display: flex;
  justify-content: space-between;
  gap: 2rem;
  padding: 2rem;
  border-radius: 28px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  background:
    radial-gradient(circle at top left, rgba(251, 191, 36, 0.18), transparent 28%),
    radial-gradient(circle at bottom right, rgba(14, 165, 233, 0.14), transparent 30%),
    linear-gradient(135deg, #fffdf7 0%, #ffffff 44%, #f8fbff 100%);
  box-shadow: 0 20px 50px rgba(15, 23, 42, 0.08);
}

.skills-kicker,
.detail-kicker {
  margin: 0 0 0.45rem;
  font-size: 0.78rem;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: #9a3412;
}

.skills-title,
.detail-title {
  margin: 0;
  max-width: 52rem;
  font-size: 2rem;
  line-height: 1.15;
  color: #111827;
}

.skills-subtitle,
.detail-copy {
  margin: 0.85rem 0 0;
  max-width: 48rem;
  color: #4b5563;
  line-height: 1.7;
}

.skills-hero-actions,
.detail-actions {
  display: flex;
  gap: 0.75rem;
  align-items: flex-start;
  flex-wrap: wrap;
}

.skills-primary-btn,
.skills-secondary-btn,
.skills-danger-btn {
  border: 0;
  border-radius: 999px;
  padding: 0.8rem 1.15rem;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.18s ease, opacity 0.18s ease;
}

.skills-primary-btn {
  color: white;
  background: linear-gradient(135deg, #111827 0%, #374151 100%);
  box-shadow: 0 14px 24px rgba(17, 24, 39, 0.2);
}

.skills-secondary-btn {
  color: #111827;
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid rgba(17, 24, 39, 0.08);
}

.skills-danger-btn {
  color: #fff7ed;
  background: linear-gradient(135deg, #c2410c 0%, #ea580c 100%);
}

.skills-primary-btn:hover,
.skills-secondary-btn:hover,
.skills-danger-btn:hover {
  transform: translateY(-1px);
}

.skills-primary-btn:disabled,
.skills-secondary-btn:disabled,
.skills-danger-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
  transform: none;
}

.skills-metrics {
  display: grid;
  gap: 1rem;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.metric-card {
  padding: 1.15rem 1.25rem;
  border-radius: 22px;
  background: #fff;
  border: 1px solid rgba(15, 23, 42, 0.06);
  box-shadow: 0 12px 24px rgba(15, 23, 42, 0.05);
}

.metric-card--warning {
  background: linear-gradient(135deg, #fff7ed 0%, #fffbeb 100%);
}

.metric-card--muted {
  background: linear-gradient(135deg, #f8fafc 0%, #fdfdfd 100%);
}

.metric-card--path {
  background: linear-gradient(135deg, #f8fbff 0%, #ffffff 100%);
}

.metric-label {
  display: block;
  color: #6b7280;
  font-size: 0.85rem;
}

.metric-value,
.metric-path {
  display: block;
  margin-top: 0.45rem;
  color: #111827;
}

.metric-value {
  font-size: 1.7rem;
}

.metric-path {
  font-size: 0.94rem;
  line-height: 1.6;
  word-break: break-all;
}

.skills-workspace {
  display: grid;
  grid-template-columns: minmax(300px, 380px) minmax(0, 1.25fr);
  gap: 1.25rem;
  min-height: 62vh;
}

.skills-sidebar,
.skills-detail {
  min-height: 100%;
}

.skills-sidebar {
  display: flex;
  flex-direction: column;
  gap: 0.9rem;
  padding: 1.1rem;
  border-radius: 24px;
  background: linear-gradient(180deg, #fdfefe 0%, #f7f9fc 100%);
  border: 1px solid var(--skills-border);
  box-shadow: var(--skills-shadow);
}

.sidebar-toolbar {
  display: flex;
  flex-direction: column;
  gap: 0.8rem;
  padding: 0.25rem;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(255, 255, 255, 0.7);
}

.tab-switcher {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  padding: 0.22rem;
  border-radius: 999px;
  background: linear-gradient(180deg, #eef2f7 0%, #e8edf5 100%);
  border: 1px solid rgba(15, 23, 42, 0.05);
}

.tab-switcher__btn {
  border: 0;
  background: transparent;
  padding: 0.78rem 1rem;
  border-radius: 999px;
  font-size: 0.96rem;
  font-weight: 700;
  color: #6b7280;
  cursor: pointer;
  transition: background-color 0.18s ease, color 0.18s ease, box-shadow 0.18s ease;
}

.tab-switcher__btn--active {
  color: #111827;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 10px 18px rgba(15, 23, 42, 0.07);
}

.toolbar-input,
.toolbar-select {
  width: 100%;
  border-radius: 18px;
  border: 1px solid rgba(15, 23, 42, 0.09);
  background: rgba(255, 255, 255, 0.96);
  color: #111827;
  padding: 1rem 1.05rem;
  outline: none;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.75);
  transition: border-color 0.18s ease, box-shadow 0.18s ease, background-color 0.18s ease;
}

.toolbar-select-wrap {
  position: relative;
}

.toolbar-select {
  appearance: none;
  -webkit-appearance: none;
  -moz-appearance: none;
  padding-right: 3.4rem;
  line-height: 1.25;
}

.toolbar-select-icon {
  position: absolute;
  right: 1rem;
  top: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.15rem;
  height: 1.15rem;
  color: #374151;
  transform: translateY(-50%);
  pointer-events: none;
}

.toolbar-select-icon svg {
  width: 100%;
  height: 100%;
}

.toolbar-input:focus,
.toolbar-select:focus {
  border-color: rgba(59, 130, 246, 0.24);
  box-shadow: 0 0 0 4px rgba(59, 130, 246, 0.08);
}

.sidebar-list {
  display: flex;
  flex-direction: column;
  gap: 0.85rem;
  min-height: 0;
  overflow: auto;
  padding-right: 0.2rem;
}

.skill-card,
.empty-card {
  text-align: left;
  border-radius: 20px;
  border: 1px solid rgba(15, 23, 42, 0.07);
  background: rgba(255, 255, 255, 0.98);
  padding: 1.05rem 1rem;
}

.skill-card {
  cursor: pointer;
  transition: transform 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease, background-color 0.18s ease;
}

.skill-card:hover {
  transform: translateY(-1px);
  border-color: var(--skills-border-strong);
  box-shadow: var(--skills-shadow-strong);
  background: linear-gradient(180deg, #ffffff 0%, #fbfcff 100%);
}

.skill-card--active {
  border-color: var(--skills-border-strong);
  box-shadow: var(--skills-shadow-strong);
  background: linear-gradient(180deg, #ffffff 0%, #fbfcff 100%);
}

.skill-card--trash {
  background: linear-gradient(135deg, #fffbeb 0%, #fff7ed 100%);
}

.skill-card__header,
.section-head {
  display: flex;
  justify-content: space-between;
  gap: 0.9rem;
  align-items: flex-start;
}

.skill-card__title {
  flex: 1;
  min-width: 0;
  font-weight: 700;
  font-size: 1rem;
  line-height: 1.25;
  letter-spacing: -0.02em;
  color: #111827;
  word-break: break-word;
}

.skill-card__desc {
  margin: 0.72rem 0 0;
  color: #4b5563;
  font-size: 0.97rem;
  line-height: 1.62;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.skill-card__meta {
  margin-top: 0.95rem;
  display: flex;
  gap: 0.55rem;
  flex-wrap: wrap;
  color: #6b7280;
  font-size: 0.83rem;
}

.skill-status,
.feature-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border-radius: 999px;
  padding: 0.34rem 0.78rem;
  font-size: 0.71rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.skill-status--valid,
.feature-badge--active {
  background: #dcfce7;
  color: #166534;
}

.skill-status--invalid {
  background: #fee2e2;
  color: #991b1b;
}

.skill-status--trash,
.feature-badge {
  background: #f3f4f6;
  color: #4b5563;
}

.skills-detail {
  border-radius: 24px;
  background: linear-gradient(180deg, #ffffff 0%, #fcfdff 100%);
  border: 1px solid var(--skills-border);
  box-shadow: var(--skills-shadow);
}

.detail-panel,
.detail-empty {
  padding: 1.5rem;
}

.detail-panel {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  gap: 1.25rem;
  align-items: flex-start;
}

.badge-row {
  display: flex;
  gap: 0.65rem;
  flex-wrap: wrap;
}

.meta-strip {
  display: flex;
  gap: 1rem;
  flex-wrap: wrap;
  align-items: stretch;
}

.detail-grid {
  display: flex;
  gap: 1rem;
  flex-wrap: wrap;
  align-items: stretch;
}

.detail-grid > .info-card {
  flex: 1 1 260px;
}

.info-card {
  padding: 1.1rem;
  border-radius: 20px;
  background: #fff;
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.info-card h4 {
  margin: 0 0 0.9rem;
  font-size: 0.98rem;
  color: #111827;
}

.info-card--warnings {
  background: linear-gradient(135deg, #fffaf0 0%, #fff 100%);
}

.info-card--code {
  background: linear-gradient(180deg, #fff 0%, #fafaf9 100%);
}

.info-card--document {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.info-card--files {
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.meta-card {
  flex: 1 1 260px;
}

.info-list {
  display: grid;
  gap: 0.8rem;
  margin: 0;
}

.info-list--compact > div {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  align-items: flex-start;
}

.info-list dt {
  font-size: 0.76rem;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: #9ca3af;
}

.info-list dd {
  margin: 0.25rem 0 0;
  color: #111827;
  line-height: 1.65;
  word-break: break-word;
}

.info-list--compact dd {
  margin: 0;
  text-align: right;
  max-width: 68%;
}

.document-toolbar {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  align-items: flex-start;
  padding-bottom: 1rem;
  border-bottom: 1px solid rgba(15, 23, 42, 0.08);
}

.document-title-block {
  display: flex;
  flex-direction: column;
  gap: 0.45rem;
}

.document-kicker {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  margin: 0;
  color: #9a3412;
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.document-kicker-dot {
  width: 0.5rem;
  height: 0.5rem;
  border-radius: 999px;
  background: linear-gradient(135deg, #f97316 0%, #f59e0b 100%);
  box-shadow: 0 0 0 4px rgba(249, 115, 22, 0.12);
}

.document-title-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.document-title-row h4 {
  margin: 0;
  font-size: 1.4rem;
  letter-spacing: -0.03em;
}

.document-state {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 0.35rem 0.72rem;
  background: linear-gradient(135deg, #fff7ed 0%, #fffbeb 100%);
  color: #9a3412;
  font-size: 0.76rem;
  font-weight: 700;
}

.document-note {
  margin: 0;
  max-width: 42rem;
  color: #6b7280;
  font-size: 0.9rem;
  line-height: 1.55;
}

.document-actions {
  display: inline-flex;
  align-items: center;
  gap: 0.6rem;
  flex-wrap: wrap;
  padding: 0.35rem;
  border-radius: 999px;
  background: linear-gradient(135deg, #f8fafc 0%, #f3f4f6 100%);
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.preview-mode-badge {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 0.5rem 0.82rem;
  background: rgba(255, 255, 255, 0.92);
  color: #4b5563;
  font-size: 0.82rem;
  font-weight: 700;
}

.preview-toggle {
  border: 0;
  border-radius: 999px;
  padding: 0.56rem 0.95rem;
  background: linear-gradient(135deg, #111827 0%, #374151 100%);
  color: #f9fafb;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.18);
  transition: transform 0.18s ease, box-shadow 0.18s ease, opacity 0.18s ease;
}

.preview-toggle:hover {
  transform: translateY(-1px);
  box-shadow: 0 14px 28px rgba(15, 23, 42, 0.22);
}

.section-caption {
  margin: 0.35rem 0 0;
  color: #6b7280;
  font-size: 0.86rem;
  line-height: 1.55;
}

.code-preview-shell {
  position: relative;
  margin-top: 1rem;
  padding: 0.7rem 0.7rem 0.55rem;
  border-radius: 24px;
  background: linear-gradient(180deg, #1f2937 0%, #111827 100%);
  box-shadow: 0 18px 36px rgba(15, 23, 42, 0.18);
}

.code-frame-bar {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  padding: 0 0.25rem 0.65rem;
  color: rgba(226, 232, 240, 0.72);
}

.code-frame-bar small {
  margin-left: 0.4rem;
  font-size: 0.74rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.code-frame-dot {
  width: 0.68rem;
  height: 0.68rem;
  border-radius: 999px;
}

.code-frame-dot--red {
  background: #fb7185;
}

.code-frame-dot--yellow {
  background: #fbbf24;
}

.code-frame-dot--green {
  background: #34d399;
}

.code-preview-shell--collapsed::after {
  content: '';
  position: absolute;
  inset: auto 0.7rem 0.55rem;
  height: 88px;
  border-radius: 0 0 18px 18px;
  background: linear-gradient(180deg, rgba(17, 24, 39, 0) 0%, rgba(17, 24, 39, 0.94) 100%);
  pointer-events: none;
}

.code-preview {
  margin: 0;
  border-radius: 18px;
  padding: 1.2rem 1.3rem;
  min-height: clamp(420px, 58vh, 760px);
  overflow: auto;
  background: rgba(15, 23, 42, 0.92);
  color: #f9fafb;
  font-size: 0.9rem;
  line-height: 1.72;
  white-space: pre-wrap;
  word-break: break-word;
  border: 1px solid rgba(255, 255, 255, 0.06);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.05);
}

.code-preview--collapsed {
  min-height: 0;
  max-height: 320px;
}

.warning-inline-list {
  display: flex;
  flex-wrap: wrap;
  gap: 0.6rem;
}

.warning-pill {
  display: inline-flex;
  max-width: 100%;
  border-radius: 14px;
  padding: 0.55rem 0.8rem;
  background: rgba(251, 146, 60, 0.16);
  color: #9a3412;
  line-height: 1.55;
}

.file-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  max-height: 360px;
  overflow: auto;
}

.file-row {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  align-items: flex-start;
  border-radius: 16px;
  background: #f9fafb;
  padding: 0.85rem 0.95rem;
}

.file-row strong {
  color: #111827;
}

.file-row p {
  margin: 0.25rem 0 0;
  color: #6b7280;
  font-size: 0.84rem;
}

.file-row span {
  color: #6b7280;
  font-size: 0.82rem;
  white-space: nowrap;
}

.empty-card,
.empty-inline,
.detail-empty span {
  color: #6b7280;
}

.empty-card {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}

.detail-empty {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  text-align: center;
  gap: 0.75rem;
}

@media (max-width: 1280px) {
  .skills-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 960px) {
  .skills-shell {
    gap: 1rem;
  }

  .skills-hero,
  .detail-header,
  .skills-workspace {
    grid-template-columns: 1fr;
    flex-direction: column;
  }

  .skills-metrics {
    grid-template-columns: 1fr;
  }

  .skills-workspace {
    display: flex;
    flex-direction: column;
  }

  .skills-sidebar,
  .skills-detail {
    border-radius: 22px;
  }

  .skills-sidebar,
  .detail-panel,
  .detail-empty {
    padding: 1rem;
  }

  .sidebar-toolbar {
    padding: 0;
    background: transparent;
    border: 0;
  }

  .tab-switcher {
    width: 100%;
  }

  .tab-switcher__btn {
    padding: 0.7rem 0.75rem;
    font-size: 0.92rem;
  }

  .toolbar-input,
  .toolbar-select {
    padding: 0.92rem 0.95rem;
    border-radius: 16px;
  }

  .skill-card,
  .empty-card,
  .info-card,
  .file-row {
    border-radius: 18px;
  }

  .skill-card__header {
    align-items: center;
  }

  .skill-card__desc {
    font-size: 0.94rem;
    -webkit-line-clamp: 3;
  }

  .meta-strip {
    flex-direction: column;
  }

  .document-toolbar {
    flex-direction: column;
  }

  .document-actions {
    align-self: flex-start;
  }

  .info-list--compact > div {
    flex-direction: column;
    gap: 0.25rem;
  }

  .info-list--compact dd {
    max-width: 100%;
    text-align: left;
  }
}
</style>

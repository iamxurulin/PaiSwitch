import { defineStore } from 'pinia'
import { ref } from 'vue'
import { skillApi } from '@/api'
import type {
  SkillDetail,
  SkillListResponse,
  TrashSkillEntry
} from '@/types'

export const useSkillStore = defineStore('skill', () => {
  const skillList = ref<SkillListResponse | null>(null)
  const trashEntries = ref<TrashSkillEntry[]>([])
  const selectedSkill = ref<SkillDetail | null>(null)
  const loadingList = ref(false)
  const loadingDetail = ref(false)
  const acting = ref(false)

  async function fetchSkills() {
    loadingList.value = true
    try {
      skillList.value = await skillApi.list()
    } finally {
      loadingList.value = false
    }
  }

  async function fetchTrash() {
    trashEntries.value = await skillApi.listTrash()
  }

  async function loadSkillDetail(folderName: string) {
    loadingDetail.value = true
    try {
      selectedSkill.value = await skillApi.getDetail(folderName)
      return selectedSkill.value
    } finally {
      loadingDetail.value = false
    }
  }

  async function init() {
    await Promise.all([fetchSkills(), fetchTrash()])
  }

  async function refreshCurrentDetail() {
    if (!selectedSkill.value) return null
    return loadSkillDetail(selectedSkill.value.folderName)
  }

  async function renameSkill(folderName: string, newFolderName: string) {
    acting.value = true
    try {
      const renamed = await skillApi.rename(folderName, { newFolderName })
      await fetchSkills()
      await loadSkillDetail(renamed.folderName)
      return renamed
    } finally {
      acting.value = false
    }
  }

  async function moveSkillToTrash(folderName: string) {
    acting.value = true
    try {
      await skillApi.moveToTrash(folderName)
      if (selectedSkill.value?.folderName === folderName) {
        selectedSkill.value = null
      }
      await Promise.all([fetchSkills(), fetchTrash()])
    } finally {
      acting.value = false
    }
  }

  async function restoreTrashEntry(trashEntry: string) {
    acting.value = true
    try {
      const restored = await skillApi.restore(trashEntry)
      await Promise.all([fetchSkills(), fetchTrash()])
      await loadSkillDetail(restored.folderName)
      return restored
    } finally {
      acting.value = false
    }
  }

  function clearSelection() {
    selectedSkill.value = null
  }

  return {
    skillList,
    trashEntries,
    selectedSkill,
    loadingList,
    loadingDetail,
    acting,
    fetchSkills,
    fetchTrash,
    loadSkillDetail,
    init,
    refreshCurrentDetail,
    renameSkill,
    moveSkillToTrash,
    restoreTrashEntry,
    clearSelection
  }
})

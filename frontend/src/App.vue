<script setup>
import { onMounted, ref } from 'vue'
import { AnalyzeButton, DashboardView, DatasourcePanel, api } from './modules/dashboard/index.js'

const datasourceId = ref(null)
const dashboards = ref([])
const current = ref(null)
const error = ref('')
const confirmDelete = ref(null)

async function refresh() {
  dashboards.value = await api.listDashboards()
}

async function open(id) {
  try {
    current.value = await api.getDashboard(id)
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}

async function onSaved(id) {
  try {
    await refresh()
  } catch (e) {
    error.value = e.message
    return
  }
  await open(id)
}

function askDelete(d) {
  confirmDelete.value = d
}

async function confirmRemove() {
  const d = confirmDelete.value
  confirmDelete.value = null
  try {
    await api.deleteDashboard(d.id)
    if (current.value?.id === d.id) current.value = null
    await refresh()
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}

onMounted(() => refresh().catch((e) => (error.value = e.message)))
</script>

<template>
  <v-app>
    <v-navigation-drawer permanent width="380">
      <DatasourcePanel :selected-id="datasourceId" @select="(id) => (datasourceId = id)" />
      <div class="px-4 pb-4">
        <AnalyzeButton :datasource-id="datasourceId" @saved="onSaved" />
      </div>
      <v-divider />
      <v-list density="compact">
        <v-list-subheader>我的儀表板</v-list-subheader>
        <v-list-item
          v-for="d in dashboards"
          :key="d.id"
          :title="d.name"
          :active="current?.id === d.id"
          @click="open(d.id)"
        >
          <template #append>
            <v-btn
              icon="mdi-delete"
              size="x-small"
              variant="text"
              aria-label="刪除儀表板"
              @click.stop="askDelete(d)"
            />
          </template>
        </v-list-item>
      </v-list>
    </v-navigation-drawer>
    <v-main>
      <v-container fluid>
        <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
        <!-- spec 10.2：換一份儀表板就整個重建，卡片不會從上一份的位置滑過來；預覽裡的 DashboardView 不加 key -->
        <DashboardView v-if="current" :key="current.id" :config="current" />
        <div v-else class="text-medium-emphasis">選一份儀表板，或上傳資料後按「分析」。</div>
      </v-container>
    </v-main>
    <v-dialog :model-value="!!confirmDelete" max-width="400" @update:model-value="(v) => !v && (confirmDelete = null)">
      <v-card v-if="confirmDelete">
        <v-card-text class="pt-4">確定刪除「{{ confirmDelete.name }}」？刪除後無法復原。</v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="confirmDelete = null">取消</v-btn>
          <v-btn color="error" @click="confirmRemove">刪除</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-app>
</template>

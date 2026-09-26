<script setup>
import { onMounted, ref } from 'vue'
import { AnalyzeButton, DashboardView, DatasourcePanel, api } from './modules/dashboard/index.js'

const datasourceId = ref(null)
// 側欄勾選想看的欄位；只屬於目前選的資料源，換資料源就清空
const pickedFields = ref([])

function selectDatasource(id) {
  if (id !== datasourceId.value) pickedFields.value = []
  datasourceId.value = id
}
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
    <!-- spec 3.3：側欄跟主區同底色，只靠右框線分開 -->
    <v-navigation-drawer permanent width="380" color="background">
      <DatasourcePanel v-model:picked="pickedFields" :selected-id="datasourceId" @select="selectDatasource" />
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
              size="small"
              density="default"
              variant="text"
              aria-label="刪除儀表板"
              @click.stop="askDelete(d)"
            />
          </template>
        </v-list-item>
      </v-list>
      <!-- 資料源一多，「分析」就被擠到畫面外；固定在側欄底部，勾完欄位不必往下捲 -->
      <template #append>
        <div class="analyze-dock pa-4">
          <AnalyzeButton :datasource-id="datasourceId" :fields="pickedFields" @saved="onSaved" />
        </div>
      </template>
    </v-navigation-drawer>
    <v-main>
      <v-container fluid>
        <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
        <template v-if="current">
          <h1 class="tc-page-bar__title mb-1">{{ current.name }}</h1>
          <!-- spec 10.2：換一份儀表板就整個重建，卡片不會從上一份的位置滑過來；預覽裡的 DashboardView 不加 key -->
          <DashboardView :key="current.id" :config="current" />
        </template>
        <div v-else class="tc-empty empty-start">
          <v-icon icon="mdi-view-dashboard-outline" size="40" class="mb-3" />
          <h1 class="tc-page-bar__title mb-3">還沒有打開儀表板</h1>
          <ol class="empty-steps">
            <li>在左側上傳 CSV / Excel，或註冊外部 API。</li>
            <li>點選資料源，想指定欄位就勾起來。</li>
            <li>按左下角的「分析」，預覽滿意再存檔。</li>
          </ol>
          <p v-if="dashboards.length" class="mt-3">已經存過的儀表板在左側「我的儀表板」。</p>
        </div>
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

<style scoped>
/* 固定在底部的「分析」區塊跟上方捲動的清單用一條框線分開 */
.analyze-dock {
  border-top: 1px solid var(--tc-border-subtle);
}
.empty-start {
  max-width: 36rem;
  margin: 12vh auto 0;
}
.empty-steps {
  display: inline-block;
  text-align: left;
  line-height: 1.8;
  color: var(--tc-text-2);
}
</style>

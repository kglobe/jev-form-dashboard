<script setup>
import { onMounted, ref } from 'vue'
import { AnalyzeButton, DashboardView, DatasourcePanel, api } from './modules/dashboard/index.js'

const datasourceId = ref(null)
const dashboards = ref([])
const current = ref(null)
const error = ref('')

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

async function remove(id) {
  try {
    await api.deleteDashboard(id)
    if (current.value?.id === id) current.value = null
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
            <v-btn icon="mdi-delete" size="x-small" variant="text" @click.stop="remove(d.id)" />
          </template>
        </v-list-item>
      </v-list>
    </v-navigation-drawer>
    <v-main>
      <v-container fluid>
        <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
        <DashboardView v-if="current" :config="current" />
        <div v-else class="text-medium-emphasis">選一份儀表板，或上傳資料後按「分析」。</div>
      </v-container>
    </v-main>
  </v-app>
</template>

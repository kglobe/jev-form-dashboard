<script setup>
import { computed, ref } from 'vue'
import { api } from './api.js'
import DashboardView from './DashboardView.vue'

const props = defineProps({ datasourceId: { type: String, default: null } })
const emit = defineEmits(['saved'])

const preview = ref(null)
const open = ref(false)
const busy = ref(false)
const saving = ref(false)
const error = ref('')

const nameInvalid = computed(() => !preview.value?.name || !preview.value.name.trim())

async function analyze() {
  busy.value = true
  error.value = ''
  try {
    preview.value = await api.analyze(props.datasourceId)
    open.value = true
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function save() {
  if (saving.value || nameInvalid.value) return
  saving.value = true
  try {
    const saved = await api.createDashboard(preview.value)
    open.value = false
    emit('saved', saved.id)
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-btn color="primary" block :loading="busy" :disabled="!datasourceId" @click="analyze">分析</v-btn>
  <v-alert v-if="error && !open" type="error" density="compact" class="mt-2">{{ error }}</v-alert>
  <v-dialog v-model="open" fullscreen>
    <v-card>
      <v-toolbar title="儀表板預覽">
        <v-text-field
          v-if="preview"
          v-model="preview.name"
          label="儀表板名稱"
          density="compact"
          hide-details="auto"
          :error-messages="nameInvalid ? ['請輸入儀表板名稱'] : []"
          class="mx-4"
          style="max-width: 320px"
        />
        <v-btn @click="open = false">取消</v-btn>
        <v-btn
          color="primary"
          variant="flat"
          class="mr-2"
          :loading="saving"
          :disabled="nameInvalid || saving"
          @click="save"
        >存為我的儀表板</v-btn>
      </v-toolbar>
      <v-card-text>
        <v-alert v-if="error" type="error" density="compact" class="mb-2">{{ error }}</v-alert>
        <DashboardView v-if="preview" :config="preview" preview />
      </v-card-text>
    </v-card>
  </v-dialog>
</template>

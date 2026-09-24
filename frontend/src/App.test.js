// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import App from './App.vue'
import { api } from './modules/dashboard/index.js'
import { vuetify } from './modules/dashboard/test-vuetify.js'

vi.mock('./modules/dashboard/index.js', () => ({
  api: {
    listDashboards: vi.fn(),
    getDashboard: vi.fn(),
    deleteDashboard: vi.fn(),
  },
  AnalyzeButton: { name: 'AnalyzeButton', template: '<div class="analyze-button-stub" />' },
  DashboardView: { name: 'DashboardView', props: ['config'], template: '<div class="dashboard-view-stub" />' },
  DatasourcePanel: { name: 'DatasourcePanel', props: ['selectedId'], template: '<div class="datasource-panel-stub" />' },
}))

const dashboards = [{ id: 'd1', name: 'Dash One' }]

function findDashboardItem(wrapper) {
  return wrapper.findAll('.v-list-item').find((w) => w.text().includes('Dash One'))
}

describe('App', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.listDashboards.mockResolvedValue(dashboards)
  })

  it('shows the error message when opening a dashboard fails', async () => {
    api.getDashboard.mockRejectedValue(new Error('找不到儀表板'))

    const wrapper = mount(App, { global: { plugins: [vuetify] } })
    await flushPromises()

    await findDashboardItem(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('找不到儀表板')
  })

  it('shows the error message when deleting a dashboard fails', async () => {
    api.deleteDashboard.mockRejectedValue(new Error('刪除失敗'))

    const wrapper = mount(App, { global: { plugins: [vuetify] } })
    await flushPromises()

    const deleteBtn = findDashboardItem(wrapper).find('button')
    await deleteBtn.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('刪除失敗')
  })
})

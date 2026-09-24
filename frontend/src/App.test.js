// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
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

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('shows the error message when opening a dashboard fails', async () => {
    api.getDashboard.mockRejectedValue(new Error('找不到儀表板'))

    const wrapper = mount(App, { global: { plugins: [vuetify] } })
    await flushPromises()

    await findDashboardItem(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('找不到儀表板')
  })

  it('delete icon button has an accessible name and opens a confirm dialog instead of deleting immediately', async () => {
    const wrapper = mount(App, { global: { plugins: [vuetify] } })
    await flushPromises()

    const deleteBtn = findDashboardItem(wrapper).find('button')
    expect(deleteBtn.attributes('aria-label')).toBe('刪除儀表板')

    await deleteBtn.trigger('click')
    await flushPromises()

    expect(api.deleteDashboard).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('確定刪除「Dash One」？刪除後無法復原。')
  })

  it('confirm dialog: 取消 closes without calling deleteDashboard', async () => {
    const wrapper = mount(App, { global: { plugins: [vuetify] } })
    await flushPromises()

    const deleteBtn = findDashboardItem(wrapper).find('button')
    await deleteBtn.trigger('click')
    await flushPromises()

    const cancelBtn = [...document.body.querySelectorAll('button')].find((b) => b.textContent.trim() === '取消')
    cancelBtn.click()
    await flushPromises()

    expect(api.deleteDashboard).not.toHaveBeenCalled()
  })

  it('confirm dialog: 刪除 calls deleteDashboard for that dashboard', async () => {
    api.deleteDashboard.mockResolvedValue(null)
    const wrapper = mount(App, { global: { plugins: [vuetify] } })
    await flushPromises()

    const deleteBtn = findDashboardItem(wrapper).find('button')
    await deleteBtn.trigger('click')
    await flushPromises()

    const confirmBtn = [...document.body.querySelectorAll('button')].find((b) => b.textContent.trim() === '刪除')
    confirmBtn.click()
    await flushPromises()

    expect(api.deleteDashboard).toHaveBeenCalledWith('d1')
  })

  it('shows the error message when deleting a dashboard fails (after confirming)', async () => {
    api.deleteDashboard.mockRejectedValue(new Error('刪除失敗'))

    const wrapper = mount(App, { global: { plugins: [vuetify] } })
    await flushPromises()

    const deleteBtn = findDashboardItem(wrapper).find('button')
    await deleteBtn.trigger('click')
    await flushPromises()

    const confirmBtn = [...document.body.querySelectorAll('button')].find((b) => b.textContent.trim() === '刪除')
    confirmBtn.click()
    await flushPromises()

    expect(wrapper.text()).toContain('刪除失敗')
  })
})

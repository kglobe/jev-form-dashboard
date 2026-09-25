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

  it('side drawer shares the canvas colour, and the delete button is size small so it is big enough to hit', async () => {
    const wrapper = mount(App, { global: { plugins: [vuetify] } })
    await flushPromises()

    expect(wrapper.get('.v-navigation-drawer').classes()).toContain('bg-background')
    expect(findDashboardItem(wrapper).get('button[aria-label="刪除儀表板"]').classes()).toContain('v-btn--size-small')
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

  it('re-creates DashboardView when another saved dashboard is opened (keyed by id), so cards never slide over from the previous one', async () => {
    api.listDashboards.mockResolvedValue([{ id: 'd1', name: 'Dash One' }, { id: 'd2', name: 'Dash Two' }])
    api.getDashboard.mockImplementation(async (id) => ({ id, name: id, datasourceId: 'ds1', layout: [] }))
    const wrapper = mount(App, { global: { plugins: [vuetify] } })
    await flushPromises()
    const item = (name) => wrapper.findAll('.v-list-item').find((w) => w.text().includes(name))

    await item('Dash One').trigger('click')
    await flushPromises()
    const first = wrapper.findComponent({ name: 'DashboardView' }).vm

    await item('Dash Two').trigger('click')
    await flushPromises()
    const second = wrapper.findComponent({ name: 'DashboardView' })
    expect(second.props('config').id).toBe('d2')
    expect(second.vm).not.toBe(first)
  })
})

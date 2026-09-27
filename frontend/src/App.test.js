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
  ChartBuilder: {
    name: 'ChartBuilder',
    props: ['active', 'request'],
    emits: ['saved'],
    template: '<div class="chart-builder-stub" />',
  },
  DashboardView: { name: 'DashboardView', props: ['config'], template: '<div class="dashboard-view-stub" />' },
  DatasourcePanel: {
    name: 'DatasourcePanel',
    props: ['selectedId'],
    emits: ['select', 'build'],
    template: '<div class="datasource-panel-stub" />',
  },
}))

const dashboards = [{ id: 'd1', name: 'Dash One' }]

function mountApp() {
  return mount(App, { global: { plugins: [vuetify] } })
}

function findDashboardItem(wrapper) {
  return wrapper.findAll('.v-list-item').find((w) => w.text().includes('Dash One'))
}

function railItem(wrapper, label) {
  return wrapper.findAll('.cc-rail__item').find((w) => w.text() === label)
}

function currentView(wrapper) {
  return wrapper.find('.cc-rail__item[aria-current="page"]').text()
}

function findButton(wrapper, label) {
  return wrapper.findAll('button').find((w) => w.text() === label)
}

function dialogButton(label) {
  return [...document.body.querySelectorAll('button')].find((b) => b.textContent.trim() === label)
}

function pressKey(init) {
  const e = new KeyboardEvent('keydown', { cancelable: true, ...init })
  window.dispatchEvent(e)
  return e
}

describe('App', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.listDashboards.mockResolvedValue(dashboards)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
    // 主題切換會動到共用的 vuetify、<html> 和 localStorage，還原成預設
    vuetify.theme.change('light')
    delete document.documentElement.dataset.theme
    localStorage.clear()
  })

  it('the theme button toggles dark/light: the Vuetify theme, <html data-theme> and localStorage follow, and the label names the other theme', async () => {
    vuetify.theme.change('dark')
    const wrapper = mountApp()
    await flushPromises()
    const themeButton = () => wrapper.get('.cc-rail__theme')
    expect(themeButton().attributes('aria-label')).toBe('切換成淺色主題')
    expect(themeButton().text()).toBe('淺色')

    await themeButton().trigger('click')
    expect(vuetify.theme.name.value).toBe('light')
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(localStorage.getItem('jev-theme')).toBe('light')
    expect(themeButton().attributes('aria-label')).toBe('切換成深色主題')
    expect(themeButton().text()).toBe('深色')

    await themeButton().trigger('click')
    expect(vuetify.theme.name.value).toBe('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem('jev-theme')).toBe('dark')
  })

  it('the theme still switches when localStorage refuses to save (private window, blocked site data)', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vuetify.theme.change('dark')
    const wrapper = mountApp()
    await flushPromises()

    await wrapper.get('.cc-rail__theme').trigger('click')
    expect(vuetify.theme.name.value).toBe('light')
    expect(document.documentElement.dataset.theme).toBe('light')
  })

  it('rail has three workspaces, 儀表板 is active by default, and clicking one switches the view with aria-current following', async () => {
    const wrapper = mountApp()
    await flushPromises()

    const items = wrapper.findAll('.cc-rail__nav .cc-rail__item')
    expect(items.map((w) => w.text())).toEqual(['資料', '製圖', '儀表板'])
    expect(items.map((w) => w.attributes('aria-keyshortcuts'))).toEqual(['Alt+1', 'Alt+2', 'Alt+3'])
    expect(items[2].classes()).toContain('is-active')
    expect(items[2].attributes('aria-current')).toBe('page')
    expect(items[0].attributes('aria-current')).toBeUndefined()
    expect(wrapper.get('h1').text()).toBe('儀表板')
    expect(wrapper.findComponent({ name: 'DatasourcePanel' }).exists()).toBe(false)

    await items[0].trigger('click')

    expect(items[0].classes()).toContain('is-active')
    expect(items[0].attributes('aria-current')).toBe('page')
    expect(items[2].classes()).not.toContain('is-active')
    expect(items[2].attributes('aria-current')).toBeUndefined()
    expect(wrapper.findComponent({ name: 'DatasourcePanel' }).exists()).toBe(true)
    expect(wrapper.find('h1').exists()).toBe(false)
  })

  it('Alt+1/2/3 switch workspaces; ctrl+alt, meta+alt and other keys are ignored; the listener is removed on unmount', async () => {
    const addSpy = vi.spyOn(window, 'addEventListener')
    const removeSpy = vi.spyOn(window, 'removeEventListener')
    const wrapper = mountApp()
    await flushPromises()

    const e = pressKey({ key: '1', altKey: true })
    await flushPromises()
    expect(currentView(wrapper)).toBe('資料')
    expect(e.defaultPrevented).toBe(true)

    pressKey({ key: '2', altKey: true })
    await flushPromises()
    expect(currentView(wrapper)).toBe('製圖')

    pressKey({ key: '3', altKey: true, ctrlKey: true })
    pressKey({ key: '3', altKey: true, metaKey: true })
    pressKey({ key: '3' })
    pressKey({ key: '4', altKey: true })
    await flushPromises()
    expect(currentView(wrapper)).toBe('製圖')

    pressKey({ key: '3', altKey: true })
    await flushPromises()
    expect(currentView(wrapper)).toBe('儀表板')

    const handler = addSpy.mock.calls.find(([type]) => type === 'keydown')[1]
    wrapper.unmount()
    expect(removeSpy).toHaveBeenCalledWith('keydown', handler)
  })

  it('ChartBuilder stays mounted in every view but is only active in the build view', async () => {
    const wrapper = mountApp()
    await flushPromises()

    for (const label of ['資料', '製圖', '儀表板']) {
      await railItem(wrapper, label).trigger('click')
      const builder = wrapper.findComponent({ name: 'ChartBuilder' })
      expect(builder.exists()).toBe(true)
      expect(builder.props('active')).toBe(label === '製圖')
    }
  })

  it('DatasourcePanel select sets the selected id; its build event opens the build view with a new request object each time', async () => {
    const wrapper = mountApp()
    await flushPromises()

    await railItem(wrapper, '資料').trigger('click')
    const panel = wrapper.findComponent({ name: 'DatasourcePanel' })
    expect(panel.props('selectedId')).toBeNull()
    panel.vm.$emit('select', 'ds1')
    await flushPromises()
    expect(panel.props('selectedId')).toBe('ds1')

    panel.vm.$emit('build', 'ds1')
    await flushPromises()
    expect(currentView(wrapper)).toBe('製圖')
    expect(wrapper.findComponent({ name: 'DatasourcePanel' }).exists()).toBe(false)
    const first = wrapper.findComponent({ name: 'ChartBuilder' }).props('request')
    expect(first).toEqual({ id: 'ds1' })

    await railItem(wrapper, '資料').trigger('click')
    wrapper.findComponent({ name: 'DatasourcePanel' }).vm.$emit('build', 'ds1')
    await flushPromises()
    const second = wrapper.findComponent({ name: 'ChartBuilder' }).props('request')
    expect(second).toEqual({ id: 'ds1' })
    expect(second).not.toBe(first)
  })

  it('ChartBuilder saved switches to the dashboards view, refreshes the list and opens that dashboard', async () => {
    api.getDashboard.mockResolvedValue({ id: 'd2', name: 'New Dash', datasourceId: 'ds1', layout: [] })
    const wrapper = mountApp()
    await flushPromises()
    await railItem(wrapper, '製圖').trigger('click')

    api.listDashboards.mockResolvedValue([...dashboards, { id: 'd2', name: 'New Dash' }])
    wrapper.findComponent({ name: 'ChartBuilder' }).vm.$emit('saved', 'd2')
    await flushPromises()

    expect(currentView(wrapper)).toBe('儀表板')
    expect(api.listDashboards).toHaveBeenCalledTimes(2)
    expect(api.getDashboard).toHaveBeenCalledWith('d2')
    expect(wrapper.get('h1').text()).toBe('New Dash')
    expect(wrapper.findComponent({ name: 'DashboardView' }).props('config').id).toBe('d2')
    expect(wrapper.findAll('.v-list-item').some((w) => w.text().includes('New Dash'))).toBe(true)
  })

  it('empty state 前往資料 / 開始製圖 and the 新增儀表板 button switch views', async () => {
    const wrapper = mountApp()
    await flushPromises()

    await findButton(wrapper, '前往資料').trigger('click')
    expect(currentView(wrapper)).toBe('資料')

    await railItem(wrapper, '儀表板').trigger('click')
    await findButton(wrapper, '開始製圖').trigger('click')
    expect(currentView(wrapper)).toBe('製圖')

    await railItem(wrapper, '儀表板').trigger('click')
    await findButton(wrapper, '新增儀表板').trigger('click')
    expect(currentView(wrapper)).toBe('製圖')
  })

  it('shows the error message when opening a dashboard fails', async () => {
    api.getDashboard.mockRejectedValue(new Error('找不到儀表板'))

    const wrapper = mountApp()
    await flushPromises()

    await findDashboardItem(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('找不到儀表板')
  })

  it('delete icon button has an accessible name, is big enough to hit, and opens a confirm dialog instead of deleting immediately', async () => {
    const wrapper = mountApp()
    await flushPromises()

    const deleteBtn = findDashboardItem(wrapper).get('button[aria-label="刪除儀表板"]')
    expect(deleteBtn.find('.mdi-delete-outline').exists()).toBe(true)
    expect(deleteBtn.classes()).toContain('v-btn--size-small')
    // 全域 density: 'compact' 會把 size small 的按鈕從 28px 縮到 20px，這裡要蓋回 default 才過得了 24×24
    expect(deleteBtn.classes()).toContain('v-btn--density-default')

    await deleteBtn.trigger('click')
    await flushPromises()

    expect(api.deleteDashboard).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('確定刪除「Dash One」？刪除後無法復原。')
    expect(document.body.querySelector('.v-card-title').textContent.trim()).toBe('刪除儀表板')
    // v-card-actions 預設是 text 按鈕；兩顆都要是 flat，刪除用 error 色
    expect(dialogButton('取消').classList.contains('v-btn--variant-flat')).toBe(true)
    expect(dialogButton('刪除').classList.contains('v-btn--variant-flat')).toBe(true)
    expect(dialogButton('刪除').classList.contains('bg-error')).toBe(true)
  })

  it('confirm dialog: 取消 closes without calling deleteDashboard', async () => {
    const wrapper = mountApp()
    await flushPromises()

    await findDashboardItem(wrapper).get('button[aria-label="刪除儀表板"]').trigger('click')
    await flushPromises()

    dialogButton('取消').click()
    await flushPromises()

    expect(api.deleteDashboard).not.toHaveBeenCalled()
    expect(document.body.textContent).not.toContain('確定刪除「Dash One」')
  })

  it('confirm dialog: 刪除 calls deleteDashboard, closes the open dashboard and refreshes the list', async () => {
    api.getDashboard.mockResolvedValue({ id: 'd1', name: 'Dash One', datasourceId: 'ds1', layout: [] })
    api.deleteDashboard.mockResolvedValue(null)
    const wrapper = mountApp()
    await flushPromises()

    await findDashboardItem(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'DashboardView' }).exists()).toBe(true)

    api.listDashboards.mockResolvedValue([])
    await findDashboardItem(wrapper).get('button[aria-label="刪除儀表板"]').trigger('click')
    await flushPromises()

    dialogButton('刪除').click()
    await flushPromises()

    expect(api.deleteDashboard).toHaveBeenCalledWith('d1')
    expect(api.listDashboards).toHaveBeenCalledTimes(2)
    expect(wrapper.findComponent({ name: 'DashboardView' }).exists()).toBe(false)
    expect(wrapper.get('h1').text()).toBe('儀表板')
    expect(findDashboardItem(wrapper)).toBeUndefined()
  })

  it('shows the error message when deleting a dashboard fails (after confirming)', async () => {
    api.deleteDashboard.mockRejectedValue(new Error('刪除失敗'))

    const wrapper = mountApp()
    await flushPromises()

    await findDashboardItem(wrapper).get('button[aria-label="刪除儀表板"]').trigger('click')
    await flushPromises()

    dialogButton('刪除').click()
    await flushPromises()

    expect(wrapper.text()).toContain('刪除失敗')
  })

  it('re-creates DashboardView when another saved dashboard is opened (keyed by id), so cards never slide over from the previous one', async () => {
    api.listDashboards.mockResolvedValue([{ id: 'd1', name: 'Dash One' }, { id: 'd2', name: 'Dash Two' }])
    api.getDashboard.mockImplementation(async (id) => ({ id, name: id, datasourceId: 'ds1', layout: [] }))
    const wrapper = mountApp()
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

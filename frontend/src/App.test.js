// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import App from './App.vue'
import { api, onUnauthorized } from './modules/dashboard/index.js'
import { vuetify } from './modules/dashboard/test-vuetify.js'

// 每個測試結束就卸載 App，前一個測試的 hashchange listener 才不會留在 window 上
enableAutoUnmount(afterEach)

vi.mock('./modules/dashboard/index.js', () => ({
  api: {
    listDashboards: vi.fn(),
    getDashboard: vi.fn(),
    deleteDashboard: vi.fn(),
    me: vi.fn(),
    sharedDashboards: vi.fn(),
  },
  onUnauthorized: vi.fn(),
  ChartBuilder: {
    name: 'ChartBuilder',
    props: ['active', 'request', 'dashboards'],
    emits: ['saved', 'dashboards-changed'],
    template: '<div class="chart-builder-stub" />',
  },
  DashboardView: { name: 'DashboardView', props: ['config', 'readonly'], emits: ['updated'], template: '<div class="dashboard-view-stub" />' },
  DatasourcePanel: {
    name: 'DatasourcePanel',
    props: ['selectedId'],
    emits: ['select', 'build'],
    template: '<div class="datasource-panel-stub" />',
  },
}))

vi.mock('./modules/auth/index.js', () => ({
  LoginPage: { name: 'LoginPage', props: ['error', 'isLight'], emits: ['logged-in', 'retry', 'toggle-theme'], template: '<div class="login-page-stub">{{ error }}</div>' },
  ReloginDialog: { name: 'ReloginDialog', props: ['modelValue', 'me'], emits: ['done'], template: '<div v-if="modelValue" class="relogin-stub" />' },
  UserMenu: { name: 'UserMenu', props: ['me'], template: '<div class="user-menu-stub"><slot name="activator" :props="{}" /></div>' },
  ShareDialog: { name: 'ShareDialog', props: ['modelValue', 'dashboard'], template: '<div v-if="modelValue" class="share-stub" />' },
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
    api.me.mockResolvedValue({ username: 'u1', admin: false, password: true })
    api.sharedDashboards.mockResolvedValue([])
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
    // 主題切換會動到共用的 vuetify、<html> 和 localStorage，還原成預設
    vuetify.theme.change('light')
    delete document.documentElement.dataset.theme
    localStorage.clear()
    history.replaceState(null, '', '/')
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

  it('passes the dashboard list to ChartBuilder and reloads it when ChartBuilder says it changed', async () => {
    const wrapper = mountApp()
    await flushPromises()
    const builder = wrapper.findComponent({ name: 'ChartBuilder' })
    expect(builder.props('dashboards')).toEqual(dashboards)

    api.listDashboards.mockResolvedValue([])
    builder.vm.$emit('dashboards-changed')
    await flushPromises()
    expect(api.listDashboards).toHaveBeenCalledTimes(2)
    expect(builder.props('dashboards')).toEqual([])
  })

  it('DashboardView updated replaces the open config in place (same view, not re-created)', async () => {
    api.getDashboard.mockResolvedValue({ id: 'd1', name: 'Dash One', layout: [{ chartId: 'c1' }, { chartId: 'c2' }] })
    const wrapper = mountApp()
    await flushPromises()
    await findDashboardItem(wrapper).trigger('click')
    await flushPromises()
    const before = wrapper.findComponent({ name: 'DashboardView' })
    const first = before.vm

    before.vm.$emit('updated', { id: 'd1', name: 'Dash One', layout: [{ chartId: 'c2' }] })
    await flushPromises()
    const after = wrapper.findComponent({ name: 'DashboardView' })
    expect(after.props('config').layout).toEqual([{ chartId: 'c2' }])
    expect(after.vm).toBe(first)
  })

  it('me 還沒回來時只有 spinner，其他都不掛，也還沒抓清單', async () => {
    api.me.mockReturnValue(new Promise(() => {}))
    const wrapper = mountApp()
    await flushPromises()
    expect(wrapper.find('.v-progress-circular').exists()).toBe(true)
    expect(wrapper.find('nav[aria-label="主要導覽"]').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'LoginPage' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'ChartBuilder' }).exists()).toBe(false)
    expect(api.listDashboards).not.toHaveBeenCalled()
  })

  it('me 回 401 時只顯示登入頁；登入後再打 me，接著照原本的流程載入', async () => {
    api.me.mockRejectedValueOnce(Object.assign(new Error('請先登入'), { status: 401 }))
    const wrapper = mountApp()
    await flushPromises()
    const login = wrapper.findComponent({ name: 'LoginPage' })
    expect(login.exists()).toBe(true)
    expect(login.props('error')).toBeNull()
    expect(wrapper.find('nav[aria-label="主要導覽"]').exists()).toBe(false)
    expect(api.listDashboards).not.toHaveBeenCalled()

    login.vm.$emit('logged-in')
    await flushPromises()
    expect(api.me).toHaveBeenCalledTimes(2)
    expect(wrapper.findComponent({ name: 'LoginPage' }).exists()).toBe(false)
    expect(wrapper.find('nav[aria-label="主要導覽"]').exists()).toBe(true)
    expect(api.listDashboards).toHaveBeenCalledTimes(1)
  })

  it('me 回其他錯誤時，登入頁拿到錯誤訊息；retry 會再打一次 me', async () => {
    api.me.mockRejectedValueOnce(new Error('無法連線到伺服器'))
    const wrapper = mountApp()
    await flushPromises()
    const login = wrapper.findComponent({ name: 'LoginPage' })
    expect(login.props('error')).toBe('無法連線到伺服器')
    expect(api.listDashboards).not.toHaveBeenCalled()

    login.vm.$emit('retry')
    await flushPromises()
    expect(api.me).toHaveBeenCalledTimes(2)
    expect(wrapper.find('nav[aria-label="主要導覽"]').exists()).toBe(true)
  })

  it('API 回 401 時跳出重新登入，兩個 401 同時回來只開一次，畫面上的元件不卸載', async () => {
    const wrapper = mountApp()
    await flushPromises()
    const builder = wrapper.findComponent({ name: 'ChartBuilder' }).vm
    expect(wrapper.find('.relogin-stub').exists()).toBe(false)

    const handler = onUnauthorized.mock.calls[0][0]
    handler()
    handler()
    await flushPromises()
    expect(wrapper.findAll('.relogin-stub')).toHaveLength(1)
    expect(wrapper.findComponent({ name: 'ReloginDialog' }).props('me')).toEqual({ username: 'u1', admin: false, password: true })
    expect(wrapper.findComponent({ name: 'ChartBuilder' }).vm).toBe(builder)
    expect(wrapper.find('nav[aria-label="主要導覽"]').exists()).toBe(true)
  })

  it('重新登入的是同一個帳號：關掉對話框，重抓清單，重新打開目前那份', async () => {
    api.getDashboard.mockResolvedValue({ id: 'd1', name: 'Dash One', datasourceId: 'ds1', layout: [] })
    const wrapper = mountApp()
    await flushPromises()
    await findDashboardItem(wrapper).trigger('click')
    await flushPromises()
    expect(api.getDashboard).toHaveBeenCalledTimes(1)

    onUnauthorized.mock.calls[0][0]()
    await flushPromises()
    expect(wrapper.find('.relogin-stub').exists()).toBe(true)

    wrapper.findComponent({ name: 'ReloginDialog' }).vm.$emit('done')
    await flushPromises()
    expect(wrapper.find('.relogin-stub').exists()).toBe(false)
    expect(api.listDashboards).toHaveBeenCalledTimes(2)
    expect(api.getDashboard).toHaveBeenCalledTimes(2)
    expect(api.getDashboard).toHaveBeenLastCalledWith('d1')
  })

  it('導覽列的帳號鈕排在主題鈕前面，title 是帳號名稱', async () => {
    const wrapper = mountApp()
    await flushPromises()
    const account = wrapper.get('button[title="u1"]')
    expect(account.text()).toBe('帳號')
    const theme = wrapper.get('button[aria-label^="切換成"]')
    expect(account.element.compareDocumentPosition(theme.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(wrapper.findComponent({ name: 'UserMenu' }).props('me')).toEqual({ username: 'u1', admin: false, password: true })
  })

  describe('分享給我的、唯讀檢視和 #/d/<id> 網址', () => {
    const ID = '11111111-2222-3333-4444-555555555555'
    const NOT_FOUND = '找不到這份儀表板，可能已經刪除或取消分享'
    const sharedItem = { id: ID, name: '各區營收', owner: 'boss', updatedAt: '2026-09-30T08:00:00Z' }
    // happy-dom 20 的 replaceState 用 setTimeout 補發 hashchange（瀏覽器不會），數 getDashboard 之前先等它發完
    const settle = async () => {
      await new Promise((r) => setTimeout(r))
      await flushPromises()
    }
    const item = (wrapper, name) => wrapper.findAll('.v-list-item').find((w) => w.text().includes(name))
    const goTo = (url) => {
      history.replaceState(null, '', url)
      // 照瀏覽器的行為手動發一次；happy-dom 會再補發一次
      window.dispatchEvent(new HashChangeEvent('hashchange'))
    }

    it('「分享給我的 · N」列出分享給我的儀表板，副標是「來自 擁有者」，沒有刪除鈕；沒有時整段不顯示', async () => {
      const wrapper = mountApp()
      await flushPromises()
      expect(api.sharedDashboards).toHaveBeenCalledTimes(1)
      expect(wrapper.text()).not.toContain('分享給我的')
      expect(wrapper.find('[aria-label="分享給我的"]').exists()).toBe(false)

      api.sharedDashboards.mockResolvedValue([sharedItem])
      wrapper.findComponent({ name: 'ChartBuilder' }).vm.$emit('dashboards-changed')
      await flushPromises()
      expect(wrapper.text()).toContain('分享給我的 · 1')
      const items = wrapper.get('[aria-label="分享給我的"]').findAll('.v-list-item')
      expect(items).toHaveLength(1)
      expect(items[0].get('.v-list-item-title').text()).toBe('各區營收')
      expect(items[0].get('.v-list-item-subtitle').text()).toBe('來自 boss')
      expect(items[0].find('button').exists()).toBe(false)
    })

    it('只有分享給我的時，空白狀態是「從左邊選一份儀表板」，沒有製圖步驟', async () => {
      api.listDashboards.mockResolvedValue([])
      api.sharedDashboards.mockResolvedValue([sharedItem])
      const wrapper = mountApp()
      await flushPromises()
      expect(wrapper.get('.empty-start h2').text()).toBe('從左邊選一份儀表板')
      expect(wrapper.find('.empty-steps').exists()).toBe(false)
      expect(findButton(wrapper, '前往資料')).toBeDefined()
      expect(findButton(wrapper, '開始製圖')).toBeDefined()
    })

    it('檢視者：說明文字換掉，DashboardView 是 readonly', async () => {
      api.sharedDashboards.mockResolvedValue([sharedItem])
      api.getDashboard.mockResolvedValue({ id: ID, name: '各區營收', createdBy: 'boss', layout: [] })
      const wrapper = mountApp()
      await flushPromises()

      await item(wrapper, '各區營收').trigger('click')
      await flushPromises()
      expect(api.getDashboard).toHaveBeenCalledWith(ID)
      expect(wrapper.get('.cc-desc').text()).toBe('「boss」分享給你的儀表板，只能檢視。')
      expect(wrapper.findComponent({ name: 'DashboardView' }).props('readonly')).toBe(true)
    })

    it('擁有者：說明文字照舊，DashboardView 不是 readonly', async () => {
      api.getDashboard.mockResolvedValue({ id: 'd1', name: 'Dash One', createdBy: 'u1', layout: [] })
      const wrapper = mountApp()
      await flushPromises()

      await findDashboardItem(wrapper).trigger('click')
      await flushPromises()
      expect(wrapper.get('.cc-desc').text()).toBe('卡片拖標題列可以移動、拉右下角可以縮放，版面會自動存。')
      expect(wrapper.findComponent({ name: 'DashboardView' }).props('readonly')).toBe(false)
    })

    it('打開儀表板時網址變成 #/d/<id>；刪除目前那份才拿掉', async () => {
      api.listDashboards.mockResolvedValue([{ id: 'd1', name: 'Dash One' }, { id: 'd2', name: 'Dash Two' }])
      api.getDashboard.mockResolvedValue({ id: 'd1', name: 'Dash One', createdBy: 'u1', layout: [] })
      api.deleteDashboard.mockResolvedValue(null)
      const wrapper = mountApp()
      await flushPromises()

      await item(wrapper, 'Dash One').trigger('click')
      await flushPromises()
      expect(location.hash).toBe('#/d/d1')
      // d1 不是 UUID：open() 自己改的 hash 不能被 openFromHash 當成格式不對
      await settle()
      expect(api.getDashboard).toHaveBeenCalledTimes(1)
      expect(wrapper.findComponent({ name: 'DashboardView' }).exists()).toBe(true)
      expect(wrapper.text()).not.toContain(NOT_FOUND)

      // spec 7.9：切到「資料」「製圖」時 hash 不動
      pressKey({ altKey: true, key: '1' })
      await flushPromises()
      expect(location.hash).toBe('#/d/d1')
      pressKey({ altKey: true, key: '3' })
      await flushPromises()

      // 刪掉的不是目前那份：hash 留著
      await item(wrapper, 'Dash Two').get('button[aria-label="刪除儀表板"]').trigger('click')
      await flushPromises()
      dialogButton('刪除').click()
      await flushPromises()
      expect(location.hash).toBe('#/d/d1')

      await item(wrapper, 'Dash One').get('button[aria-label="刪除儀表板"]').trigger('click')
      await flushPromises()
      dialogButton('刪除').click()
      await flushPromises()
      expect(location.hash).toBe('')
      expect(location.pathname).toBe('/')
      await settle()
      expect(api.getDashboard).toHaveBeenCalledTimes(1)
    })

    it('網址有 #/d/<id> 時，開機就打開那份', async () => {
      history.replaceState(null, '', `/#/d/${ID}`)
      api.getDashboard.mockResolvedValue({ id: ID, name: '各區營收', createdBy: 'boss', layout: [] })
      const wrapper = mountApp()
      await settle()
      expect(api.getDashboard).toHaveBeenCalledTimes(1)
      expect(api.getDashboard).toHaveBeenCalledWith(ID)
      expect(currentView(wrapper)).toBe('儀表板')
      expect(wrapper.findComponent({ name: 'DashboardView' }).props('config').id).toBe(ID)
    })

    it('打開失敗回 404 時清掉畫面，顯示「找不到這份儀表板，可能已經刪除或取消分享」，hash 是失敗的那份', async () => {
      api.listDashboards.mockResolvedValue([{ id: 'd1', name: 'Dash One' }, { id: 'd2', name: 'Dash Two' }])
      api.getDashboard.mockResolvedValueOnce({ id: 'd1', name: 'Dash One', createdBy: 'u1', layout: [] })
      const wrapper = mountApp()
      await flushPromises()
      await item(wrapper, 'Dash One').trigger('click')
      await flushPromises()
      expect(wrapper.findComponent({ name: 'DashboardView' }).exists()).toBe(true)

      api.getDashboard.mockRejectedValueOnce(Object.assign(new Error('找不到儀表板'), { status: 404 }))
      await item(wrapper, 'Dash Two').trigger('click')
      await flushPromises()
      expect(wrapper.findComponent({ name: 'DashboardView' }).exists()).toBe(false)
      expect(wrapper.get('h1').text()).toBe('儀表板')
      expect(wrapper.get('.v-alert').text()).toBe(NOT_FOUND)
      // 重新整理會再試 d2，不是回到 d1
      expect(location.hash).toBe('#/d/d2')
      await settle()
      expect(api.getDashboard).toHaveBeenCalledTimes(2)
    })

    it('格式不對的 #/d/… 不打 API，直接顯示找不到', async () => {
      history.replaceState(null, '', '/#/d/abc')
      const wrapper = mountApp()
      await settle()
      expect(api.getDashboard).not.toHaveBeenCalled()
      expect(wrapper.get('.v-alert').text()).toBe(NOT_FOUND)
    })

    it('hashchange 到合法的 #/d/<id> 時切到儀表板頁並打開；變成空的或其他 hash 什麼都不做', async () => {
      api.getDashboard.mockResolvedValue({ id: ID, name: '各區營收', createdBy: 'boss', layout: [] })
      const wrapper = mountApp()
      await flushPromises()

      pressKey({ altKey: true, key: '1' })
      await flushPromises()
      expect(currentView(wrapper)).toBe('資料')
      goTo(`/#/d/${ID}`)
      await settle()
      expect(currentView(wrapper)).toBe('儀表板')
      expect(api.getDashboard).toHaveBeenCalledTimes(1)
      expect(api.getDashboard).toHaveBeenCalledWith(ID)

      for (const hash of ['', '#/other']) {
        pressKey({ altKey: true, key: '1' })
        await flushPromises()
        goTo(`/${hash}`)
        await settle()
        expect(currentView(wrapper)).toBe('資料')
        expect(api.getDashboard).toHaveBeenCalledTimes(1)
      }
    })

    it('卸載時拿掉 hashchange 的 listener', async () => {
      const addSpy = vi.spyOn(window, 'addEventListener')
      const removeSpy = vi.spyOn(window, 'removeEventListener')
      const wrapper = mountApp()
      await flushPromises()

      const handler = addSpy.mock.calls.find(([type]) => type === 'hashchange')[1]
      wrapper.unmount()
      expect(removeSpy).toHaveBeenCalledWith('hashchange', handler)
    })

    it('擁有者打開自己的儀表板時「新增儀表板」左邊有「分享」，按了打開分享對話框', async () => {
      const config = { id: 'd1', name: 'Dash One', createdBy: 'u1', layout: [] }
      api.getDashboard.mockResolvedValue(config)
      const wrapper = mountApp()
      await flushPromises()
      await findDashboardItem(wrapper).trigger('click')
      await flushPromises()

      const share = findButton(wrapper, '分享')
      expect(share.classes()).toContain('v-btn--variant-tonal')
      expect(share.find('.mdi-share-variant-outline').exists()).toBe(true)
      const add = findButton(wrapper, '新增儀表板')
      expect(share.element.compareDocumentPosition(add.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(wrapper.find('.share-stub').exists()).toBe(false)

      await share.trigger('click')
      expect(wrapper.find('.share-stub').exists()).toBe(true)
      expect(wrapper.findComponent({ name: 'ShareDialog' }).props('dashboard')).toEqual(config)
    })

    it('檢視者和還沒打開儀表板時沒有「分享」', async () => {
      api.sharedDashboards.mockResolvedValue([sharedItem])
      api.getDashboard.mockResolvedValue({ id: ID, name: '各區營收', createdBy: 'boss', layout: [] })
      const wrapper = mountApp()
      await flushPromises()
      expect(findButton(wrapper, '分享')).toBeUndefined()
      expect(wrapper.findComponent({ name: 'ShareDialog' }).exists()).toBe(false)

      await item(wrapper, '各區營收').trigger('click')
      await flushPromises()
      expect(wrapper.findComponent({ name: 'DashboardView' }).props('readonly')).toBe(true)
      expect(findButton(wrapper, '分享')).toBeUndefined()
      expect(findButton(wrapper, '新增儀表板')).toBeDefined()
    })
  })
})

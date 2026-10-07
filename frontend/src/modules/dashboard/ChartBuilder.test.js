// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import ChartBuilder from './ChartBuilder.vue'
import { api } from './api.js'
import { vuetify } from './test-vuetify.js'

vi.mock('./api.js', () => ({
  api: {
    listDatasources: vi.fn(),
    analyze: vi.fn(),
    createDashboard: vi.fn(),
    appendCharts: vi.fn(),
  },
}))

vi.mock('./DashboardView.vue', () => ({
  default: {
    name: 'DashboardView',
    props: { config: Object, preview: Boolean, busy: Boolean },
    emits: ['remove'],
    // 每張卡片一個「✕」，測「復原」之後焦點回到哪裡
    template: `<div class="dashboard-view-stub">
      <div v-for="s in config.layout" :key="s.chartId" :data-chart-id="s.chartId"><button aria-label="移除這張圖" /></div>
    </div>`,
  },
}))

function byText(root, selector, text) {
  return [...root.querySelectorAll(selector)].find((el) => el.textContent.trim() === text)
}

const sources = () => [
  { id: 'ds1', name: '銷售', schema: [{ name: 'region', type: 'string', label: '地區' }, { name: 'amount', type: 'number' }] },
  { id: 'ds2', name: '退貨', schema: [{ name: 'product', type: 'string' }] },
]

const wrappers = []

// 掛到 document.body：「復原」之後的焦點要在文件裡才看得到
async function mountBuilder(props) {
  const wrapper = mount(ChartBuilder, { props, attachTo: document.body, global: { plugins: [vuetify] } })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

const pos = (n) => ({ x: (n % 2) * 6, y: Math.floor(n / 2) * 4, w: 6, h: 4 })
const bar = (chartId, title, n, options) => ({
  chartId, type: 'bar', title, gridPos: pos(n), mapping: { x: 'region', value: 'amount' }, ...(options && { options }),
})
const table = (chartId, y) => ({ chartId, type: 'table', title: '明細', gridPos: { x: 0, y, w: 12, h: 5 }, mapping: { columns: ['region'] } })
const view = (wrapper) => wrapper.findComponent({ name: 'DashboardView' })
const select = (wrapper) => wrapper.findComponent({ name: 'VSelect' })
const field = (wrapper, label) => wrapper.findAllComponents({ name: 'VTextField' }).find((f) => f.props('label') === label)
const errorAlert = (wrapper) => wrapper.findAllComponents({ name: 'VAlert' }).find((a) => a.props('type') === 'error')
const button = (text) => byText(document.body, 'button', text)
// 「分析」按鈕裡還有一個 aria-hidden 的 <kbd>，用它來找按鈕、比對文字時拿掉
const analyzeButton = () => [...document.body.querySelectorAll('button')].find((b) => b.querySelector('kbd'))
const analyzeLabel = () => analyzeButton().textContent.replace('Ctrl ⏎', '').trim()
const chips = () => [...document.body.querySelectorAll('[role="checkbox"]')]
const isEmpty = () => document.body.textContent.includes('圖表會出現在這裡')
const historyItems = () => [...document.body.querySelectorAll('.tc-intents li')].map((li) => li.textContent.trim())
const layout4 = () => [bar('c1', 'A', 0), bar('c2', 'B', 1), bar('c3', 'C', 2), table('c4', 8)]
const region = () => document.body.querySelector('.undo-toast-region')
const toastEl = () => document.body.querySelector('.undo-toast')
const menuItems = () => [...document.body.querySelectorAll('.v-overlay-container .v-list-item')].map((el) => el.textContent.trim())
async function pickDashboard(name) {
  button('加到既有儀表板').click()
  await flushPromises()
  byText(document.body, '.v-list-item', name).click()
  await flushPromises()
}

async function runAnalyze() {
  analyzeButton().click()
  await flushPromises()
}

async function openPreview(props) {
  const wrapper = await mountBuilder(props)
  await runAnalyze()
  return wrapper
}

async function adjustWith(wrapper, sentence) {
  await field(wrapper, '想怎麼調整？').find('input').setValue(sentence)
  button('調整').click()
  await flushPromises()
}

describe('ChartBuilder', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.listDatasources.mockResolvedValue(sources())
  })

  afterEach(() => {
    wrappers.splice(0).forEach((w) => w.exists() && w.unmount())
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('loads the datasource list, picks the first one and shows its fields as chips (label, else name)', async () => {
    const wrapper = await mountBuilder()

    expect(api.listDatasources).toHaveBeenCalledTimes(1)
    expect(select(wrapper).props('modelValue')).toBe('ds1')
    expect(select(wrapper).props('items')).toEqual(sources())
    expect(chips().map((c) => c.textContent.trim())).toEqual(['地區', 'amount'])
    expect(chips().map((c) => c.getAttribute('aria-checked'))).toEqual(['false', 'false'])
    expect(isEmpty()).toBe(true)
    expect(analyzeLabel()).toBe('分析')
    expect(analyzeButton().disabled).toBe(false)
  })

  it('資料來源共享 spec 6.4：別人分享的來源，名稱後面加「（來自 帳號）」', async () => {
    const [mine, theirs] = sources()
    api.listDatasources.mockResolvedValue([{ ...mine, createdBy: 'u1' }, { ...theirs, createdBy: 'boss' }])
    const wrapper = await mountBuilder({ username: 'u1' })
    const title = select(wrapper).props('itemTitle')
    expect(select(wrapper).props('items').map(title)).toEqual(['銷售', '退貨（來自 boss）'])
  })

  it('disables 分析 when there is no datasource', async () => {
    api.listDatasources.mockResolvedValue([])
    await mountBuilder()

    expect(analyzeButton().disabled).toBe(true)
    expect(document.body.textContent).toContain('先選資料來源。')
    expect(chips()).toEqual([])
  })

  it('shows the error when the datasource list fails to load', async () => {
    api.listDatasources.mockRejectedValue(new Error('HTTP 500'))
    const wrapper = await mountBuilder()

    expect(errorAlert(wrapper).text()).toContain('HTTP 500')
  })

  it('reloads the list each time active turns back on; keeps the chosen datasource while it is still listed', async () => {
    const wrapper = await mountBuilder({ active: false })
    expect(api.listDatasources).not.toHaveBeenCalled()

    await wrapper.setProps({ active: true })
    await flushPromises()
    expect(api.listDatasources).toHaveBeenCalledTimes(1)
    expect(select(wrapper).props('modelValue')).toBe('ds1')

    select(wrapper).vm.$emit('update:modelValue', 'ds2')
    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    await flushPromises()
    expect(api.listDatasources).toHaveBeenCalledTimes(2)
    expect(select(wrapper).props('modelValue')).toBe('ds2')

    api.listDatasources.mockResolvedValue([sources()[0]]) // ds2 已經刪掉
    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    await flushPromises()
    expect(select(wrapper).props('modelValue')).toBe('ds1')
  })

  it('switching the datasource clears the picked fields and discards the preview; picking the same one does nothing', async () => {
    api.analyze.mockResolvedValue({ name: 'p', datasourceId: 'ds1', layout: layout4() })
    const wrapper = await mountBuilder()
    chips()[0].click()
    await flushPromises()
    await runAnalyze()
    expect(view(wrapper).exists()).toBe(true)

    select(wrapper).vm.$emit('update:modelValue', 'ds1')
    await flushPromises()
    expect(view(wrapper).exists()).toBe(true)
    expect(chips()[0].getAttribute('aria-checked')).toBe('true')

    select(wrapper).vm.$emit('update:modelValue', 'ds2')
    await flushPromises()
    expect(select(wrapper).props('modelValue')).toBe('ds2')
    expect(view(wrapper).exists()).toBe(false)
    expect(isEmpty()).toBe(true)
    expect(chips().map((c) => c.textContent.trim())).toEqual(['product'])
    expect(document.body.textContent).toContain('不選就由 JEV 挑')

    await runAnalyze()
    expect(api.analyze).toHaveBeenLastCalledWith('ds2')
  })

  it('a new request switches to that datasource, clears the picked fields and discards the preview', async () => {
    api.analyze.mockResolvedValue({ name: 'p', datasourceId: 'ds1', layout: layout4() })
    const wrapper = await mountBuilder()
    chips()[1].click()
    await flushPromises()
    await runAnalyze()

    await wrapper.setProps({ request: { id: 'ds1' } }) // 已經是這個來源，什麼都不變
    await flushPromises()
    expect(view(wrapper).exists()).toBe(true)
    expect(chips()[1].getAttribute('aria-checked')).toBe('true')

    await wrapper.setProps({ request: { id: 'ds2' } })
    await flushPromises()
    expect(select(wrapper).props('modelValue')).toBe('ds2')
    expect(view(wrapper).exists()).toBe(false)
    expect(isEmpty()).toBe(true)
    expect(chips().map((c) => c.getAttribute('aria-checked'))).toEqual(['false'])
  })

  it('chips toggle the picked fields (aria-checked, 已選 n, 清除); 分析 and 調整 send them', async () => {
    const first = { name: 'p', datasourceId: 'ds1', intents: ['一'], layout: [bar('c1', 'A', 0, { focus: true }), table('c2', 4)] }
    api.analyze
      .mockResolvedValueOnce(first)
      .mockResolvedValueOnce({ ...first, intents: ['一', '二'] })
    const wrapper = await mountBuilder()
    expect(button('清除')).toBeUndefined()

    chips()[0].click()
    await flushPromises()
    expect(chips()[0].getAttribute('aria-checked')).toBe('true')
    expect(document.body.textContent).toContain('已選 1')
    chips()[1].click()
    await flushPromises()
    expect(document.body.textContent).toContain('已選 2')
    chips()[1].click()
    await flushPromises()
    expect(chips().map((c) => c.getAttribute('aria-checked'))).toEqual(['true', 'false'])

    await runAnalyze()
    expect(api.analyze).toHaveBeenLastCalledWith('ds1', undefined, undefined, ['region'])
    await adjustWith(wrapper, '二')
    expect(api.analyze).toHaveBeenLastCalledWith('ds1', ['一', '二'], first.layout, ['region'])

    button('清除').click()
    await flushPromises()
    expect(chips().map((c) => c.getAttribute('aria-checked'))).toEqual(['false', 'false'])
    expect(document.body.textContent).not.toContain('已選')
    expect(button('清除')).toBeUndefined()
  })

  it('Ctrl+Enter or ⌘+Enter in the box runs 分析; plain Enter does not', async () => {
    api.analyze.mockResolvedValue({ name: 'p', datasourceId: 'ds1', layout: [] })
    const wrapper = await mountBuilder()
    const box = wrapper.find('textarea')
    await box.setValue('各區每月營收')

    await box.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(api.analyze).not.toHaveBeenCalled()

    await box.trigger('keydown', { key: 'Enter', ctrlKey: true })
    await flushPromises()
    expect(api.analyze).toHaveBeenCalledTimes(1)
    expect(api.analyze).toHaveBeenLastCalledWith('ds1', ['各區每月營收'])

    await box.trigger('keydown', { key: 'Enter', metaKey: true })
    await flushPromises()
    expect(api.analyze).toHaveBeenCalledTimes(2)
  })

  it('while analyzing with no preview the canvas shows the scan with the source name', async () => {
    api.analyze.mockImplementation(() => new Promise(() => {}))
    await mountBuilder()
    await runAnalyze()

    const scan = document.body.querySelector('.cb-scan')
    expect(scan.getAttribute('aria-busy')).toBe('true')
    expect(scan.textContent).toContain('JEV 正在分析「銷售」…')
    expect(isEmpty()).toBe(false)
    expect(analyzeButton().disabled).toBe(true)
  })

  it('analyzes, previews in the canvas, saves the previewed config, emits saved and goes back to the empty state', async () => {
    const previewConfig = { name: 'preview', layout: [] }
    api.analyze.mockResolvedValue(previewConfig)
    api.createDashboard.mockResolvedValue({ id: 'd9' })

    const wrapper = await openPreview()
    expect(api.analyze).toHaveBeenCalledWith('ds1')
    expect(isEmpty()).toBe(false)
    expect(view(wrapper).props('preview')).toBe(true)
    expect(view(wrapper).props('config')).toEqual(previewConfig)

    button('存成新儀表板').click()
    await flushPromises()

    expect(api.createDashboard).toHaveBeenCalledWith(previewConfig)
    expect(wrapper.emitted('saved')).toEqual([['d9']])
    expect(view(wrapper).exists()).toBe(false)
    expect(isEmpty()).toBe(true)
    expect(analyzeLabel()).toBe('分析')
  })

  it('a failed save keeps the preview and shows the error in the canvas', async () => {
    api.analyze.mockResolvedValue({ name: 'preview', layout: [] })
    api.createDashboard.mockRejectedValue(new Error('儲存失敗'))

    const wrapper = await openPreview()
    button('存成新儀表板').click()
    await flushPromises()

    expect(errorAlert(wrapper).text()).toContain('儲存失敗')
    expect(view(wrapper).exists()).toBe(true)
    expect(wrapper.emitted('saved')).toBeUndefined()
  })

  it('捨棄 drops the preview and goes back to the empty state', async () => {
    api.analyze.mockResolvedValue({ name: 'p', datasourceId: 'ds1', intents: ['一'], layout: layout4() })
    const wrapper = await openPreview()
    expect(analyzeLabel()).toBe('重新分析')

    button('捨棄').click()
    await flushPromises()

    expect(view(wrapper).exists()).toBe(false)
    expect(isEmpty()).toBe(true)
    expect(field(wrapper, '儀表板名稱')).toBeUndefined()
    expect(field(wrapper, '想怎麼調整？')).toBeUndefined()
    expect(analyzeLabel()).toBe('分析')
  })

  it('requires a dashboard name: labels the field, shows an error for blank/whitespace, and disables 存成新儀表板', async () => {
    api.analyze.mockResolvedValue({ name: 'preview', layout: [] })
    const wrapper = await openPreview()

    const nameField = field(wrapper, '儀表板名稱')
    expect(nameField.exists()).toBe(true)

    await nameField.find('input').setValue('   ')
    await flushPromises()

    expect(document.body.textContent).toContain('請輸入儀表板名稱')
    expect(button('存成新儀表板').disabled).toBe(true)
  })

  it('存成新儀表板 guards against double submission: only one createDashboard call goes out', async () => {
    api.analyze.mockResolvedValue({ name: 'preview', layout: [] })
    let resolveCreate
    api.createDashboard.mockImplementation(() => new Promise((resolve) => { resolveCreate = resolve }))

    const wrapper = await openPreview()
    const saveBtn = button('存成新儀表板')
    saveBtn.click()
    saveBtn.click()
    await flushPromises()

    expect(api.createDashboard).toHaveBeenCalledTimes(1)

    resolveCreate({ id: 'd9' })
    await flushPromises()

    expect(wrapper.emitted('saved')).toEqual([['d9']])
  })

  it('does not start a new analysis or adjustment while a save is pending', async () => {
    api.analyze.mockResolvedValue({ name: 'preview', intents: ['一'], layout: layout4() })
    let resolveCreate
    api.createDashboard.mockImplementation(() => new Promise((resolve) => { resolveCreate = resolve }))
    const wrapper = await openPreview()
    await field(wrapper, '想怎麼調整？').find('input').setValue('二')

    button('存成新儀表板').click()
    await flushPromises()
    expect(analyzeButton().disabled).toBe(true)
    expect(button('調整').disabled).toBe(true)
    analyzeButton().click()
    button('調整').click()
    await flushPromises()
    expect(api.analyze).toHaveBeenCalledTimes(1)

    resolveCreate({ id: 'd9' })
    await flushPromises()
    expect(wrapper.emitted('saved')).toEqual([['d9']])
  })

  it('shows the error message and no preview when analyze rejects', async () => {
    api.analyze.mockRejectedValue(new Error('未設定 TYPESAFE_API_KEY'))

    const wrapper = await openPreview()

    expect(errorAlert(wrapper).text()).toContain('未設定 TYPESAFE_API_KEY')
    expect(view(wrapper).exists()).toBe(false)
    expect(isEmpty()).toBe(true)
  })

  it('an optional box above 分析 with label, hint and a 200-character counter; 分析 sends the trimmed sentence as the first intent', async () => {
    api.analyze.mockResolvedValue({ name: 'p', datasourceId: 'ds1', layout: [] })

    const blank = await mountBuilder()
    await blank.find('textarea').setValue('   ')
    await runAnalyze()
    expect(api.analyze).toHaveBeenLastCalledWith('ds1')
    expect(document.body.querySelector('.tc-history').style.display).toBe('none') // 沒有需求紀錄就不顯示
    expect(document.body.textContent).not.toContain('找不到跟需求直接相關的欄位')
    blank.unmount()
    document.body.innerHTML = ''

    const wrapper = await mountBuilder()
    const box = wrapper.findComponent({ name: 'VTextarea' })
    expect(box.find('textarea').attributes('aria-label')).toBe('想看什麼？（選填）')
    expect(box.props('placeholder')).toBe('例如：各區每月營收、哪個產品退貨最多')
    expect(wrapper.text()).toContain('這段文字會送給 JEV 做分析')
    expect(wrapper.text()).toContain('0 / 200')
    expect(box.find('textarea').attributes('maxlength')).toBe('200')

    await box.find('textarea').setValue('  各區每月營收  ')
    expect(wrapper.text()).toContain('10 / 200')
    await runAnalyze()
    expect(api.analyze).toHaveBeenLastCalledWith('ds1', ['各區每月營收'])
  })

  it('重新分析 replaces the preview, takes the new default name and clears the adjust box', async () => {
    const layout = [bar('c1', 'A', 0, { focus: true }), table('c2', 4)]
    api.analyze
      .mockResolvedValueOnce({ name: 'A 總覽', datasourceId: 'ds1', intents: ['一'], layout })
      .mockResolvedValueOnce({ name: 'B 總覽', datasourceId: 'ds1', intents: ['一'], layout })
      .mockResolvedValueOnce({ name: 'C 總覽', datasourceId: 'ds1', intents: ['一', '二'], layout })
    const wrapper = await openPreview()

    await field(wrapper, '儀表板名稱').find('input').setValue('我的版本')
    await field(wrapper, '想怎麼調整？').find('input').setValue('還沒送出')
    await runAnalyze()
    expect(view(wrapper).props('config').name).toBe('B 總覽')
    expect(field(wrapper, '想怎麼調整？').props('modelValue')).toBe('')

    await adjustWith(wrapper, '二') // 重新分析之後名稱算沒改過
    expect(view(wrapper).props('config').name).toBe('C 總覽')
  })

  it('調整: disabled while empty or busy; sends the history plus the new sentence with the current charts; on success shows the new result, appends the sentence and clears the box', async () => {
    const first = { name: 'A 總覽', datasourceId: 'ds1', intents: ['各區每月營收'], layout: [bar('c1', 'A', 0, { focus: true }), table('c2', 4)] }
    const second = {
      name: 'A 總覽',
      datasourceId: 'ds1',
      intents: ['各區每月營收', '不要圓餅圖'],
      layout: [bar('c1', 'A', 0, { focus: true }), bar('c3', 'C', 1, { focus: true }), table('c2', 4)],
    }
    let resolveAdjust
    api.analyze
      .mockResolvedValueOnce(first)
      .mockImplementationOnce(() => new Promise((resolve) => { resolveAdjust = resolve }))

    const wrapper = await openPreview()
    expect(document.body.querySelector('.tc-history').style.display).toBe('')
    expect(historyItems()).toEqual(['各區每月營收'])
    expect(field(wrapper, '想怎麼調整？').props('placeholder')).toBe('例如：不要圓餅圖、加各產品的數量')
    expect(field(wrapper, '想怎麼調整？').find('input').attributes('maxlength')).toBe('200')
    expect(button('調整').disabled).toBe(true)

    await field(wrapper, '想怎麼調整？').find('input').setValue('  不要圓餅圖 ')
    expect(button('調整').disabled).toBe(false)
    button('調整').click()
    await flushPromises()

    expect(api.analyze).toHaveBeenLastCalledWith('ds1', ['各區每月營收', '不要圓餅圖'], first.layout)
    expect(button('調整').disabled).toBe(true)
    expect(button('調整').classList.contains('v-btn--loading')).toBe(true)
    expect(field(wrapper, '想怎麼調整？').props('disabled')).toBe(true)
    expect(view(wrapper).props('busy')).toBe(true)

    resolveAdjust(second)
    await flushPromises()
    expect(view(wrapper).props('config')).toEqual(second)
    expect(view(wrapper).props('busy')).toBe(false)
    expect(field(wrapper, '想怎麼調整？').props('modelValue')).toBe('')
    expect(historyItems()).toEqual(['各區每月營收', '不要圓餅圖'])
  })

  it('Enter in the adjust box runs 調整', async () => {
    const first = { name: 'p', datasourceId: 'ds1', intents: ['一'], layout: [bar('c1', 'A', 0, { focus: true }), table('c2', 4)] }
    api.analyze.mockResolvedValueOnce(first).mockResolvedValueOnce({ ...first, intents: ['一', '二'] })
    const wrapper = await openPreview()

    const input = field(wrapper, '想怎麼調整？').find('input')
    await input.setValue('二')
    await input.trigger('keydown', { key: 'Enter' })
    await flushPromises()

    expect(api.analyze).toHaveBeenLastCalledWith('ds1', ['一', '二'], first.layout)
    expect(historyItems()).toEqual(['一', '二'])
  })

  it('調整 failure keeps the preview as it was and shows the error in the canvas', async () => {
    const first = { name: 'A 總覽', datasourceId: 'ds1', intents: ['各區每月營收'], layout: [bar('c1', 'A', 0), table('c2', 4)] }
    api.analyze.mockResolvedValueOnce(first).mockRejectedValueOnce(new Error('JEV 呼叫失敗（HTTP 500）'))

    const wrapper = await openPreview()
    await adjustWith(wrapper, '不要圓餅圖')

    expect(view(wrapper).props('config')).toEqual(first)
    expect(errorAlert(wrapper).text()).toContain('JEV 呼叫失敗（HTTP 500）')
    expect(field(wrapper, '想怎麼調整？').props('modelValue')).toBe('不要圓餅圖')
    expect(button('調整').disabled).toBe(false)
  })

  it('調整 is disabled while the dashboard name is blank', async () => {
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', intents: ['一'], layout: [bar('c1', 'A', 0, { focus: true }), table('c2', 4)] })
    const wrapper = await openPreview()

    await field(wrapper, '想怎麼調整？').find('input').setValue('不要圓餅圖')
    expect(button('調整').disabled).toBe(false)
    await field(wrapper, '儀表板名稱').find('input').setValue('   ')
    await flushPromises()
    expect(button('調整').disabled).toBe(true)
  })

  it('存成新儀表板 is disabled while an adjust is pending', async () => {
    api.analyze
      .mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', intents: ['一'], layout: [bar('c1', 'A', 0, { focus: true }), table('c2', 4)] })
      .mockImplementationOnce(() => new Promise(() => {}))
    const wrapper = await openPreview()
    expect(button('存成新儀表板').disabled).toBe(false)

    await adjustWith(wrapper, '二')
    expect(button('存成新儀表板').disabled).toBe(true)
    expect(analyzeButton().disabled).toBe(true)
  })

  it('while 重新分析 is pending the preview stays but 調整 and 存成新儀表板 are disabled', async () => {
    api.analyze
      .mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', intents: ['一'], layout: [bar('c1', 'A', 0, { focus: true }), table('c2', 4)] })
      .mockImplementationOnce(() => new Promise(() => {}))
    const wrapper = await openPreview()
    await field(wrapper, '想怎麼調整？').find('input').setValue('二')
    expect(button('調整').disabled).toBe(false)

    await runAnalyze()
    expect(view(wrapper).exists()).toBe(true)
    expect(view(wrapper).props('busy')).toBe(true)
    expect(document.body.querySelector('.cb-scan')).toBeNull()
    expect(button('調整').disabled).toBe(true)
    expect(button('存成新儀表板').disabled).toBe(true)
  })

  it('捨棄 clears the adjust error and drops a late adjust result', async () => {
    const first = { name: 'p', datasourceId: 'ds1', intents: ['一'], layout: [bar('c1', 'A', 0, { focus: true }), table('c2', 4)] }
    let resolveAdjust
    api.analyze
      .mockResolvedValueOnce(first)
      .mockRejectedValueOnce(new Error('JEV 呼叫失敗（HTTP 500）'))
      .mockResolvedValueOnce(first)
      .mockImplementationOnce(() => new Promise((resolve) => { resolveAdjust = resolve }))
    const wrapper = await openPreview()

    await adjustWith(wrapper, '二')
    expect(errorAlert(wrapper).text()).toContain('JEV 呼叫失敗（HTTP 500）')
    button('捨棄').click()
    await flushPromises()
    expect(errorAlert(wrapper)).toBeUndefined()

    await runAnalyze()
    await adjustWith(wrapper, '三')
    button('捨棄').click()
    await flushPromises()
    resolveAdjust({ name: 'late', datasourceId: 'ds1', intents: ['一', '三'], layout: [] })
    await flushPromises()
    expect(view(wrapper).exists()).toBe(false)
    expect(isEmpty()).toBe(true)
    expect(errorAlert(wrapper)).toBeUndefined()
    expect(analyzeButton().disabled).toBe(false)
  })

  it('a late 分析 result is dropped after 捨棄 or after switching the datasource', async () => {
    const resolvers = []
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
      .mockImplementation(() => new Promise((resolve) => resolvers.push(resolve)))
    const wrapper = await openPreview()

    await runAnalyze() // 重新分析還沒回來就按「捨棄」
    button('捨棄').click()
    await flushPromises()
    expect(analyzeButton().disabled).toBe(false)
    resolvers[0]({ name: 'late', datasourceId: 'ds1', layout: layout4() })
    await flushPromises()
    expect(view(wrapper).exists()).toBe(false)
    expect(isEmpty()).toBe(true)

    await runAnalyze() // 分析還沒回來就換來源
    select(wrapper).vm.$emit('update:modelValue', 'ds2')
    await flushPromises()
    resolvers[1]({ name: 'late', datasourceId: 'ds1', layout: layout4() })
    await flushPromises()
    expect(select(wrapper).props('modelValue')).toBe('ds2')
    expect(view(wrapper).exists()).toBe(false)
    expect(analyzeButton().disabled).toBe(false)
  })

  it('when the chosen datasource disappears from the list, its picked fields and preview go with it', async () => {
    api.analyze.mockResolvedValue({ name: 'p', datasourceId: 'ds2', layout: layout4() })
    const wrapper = await mountBuilder()
    select(wrapper).vm.$emit('update:modelValue', 'ds2')
    await flushPromises()
    chips()[0].click()
    await flushPromises()
    await runAnalyze()
    expect(view(wrapper).exists()).toBe(true)

    api.listDatasources.mockResolvedValue([sources()[0]]) // ds2 已經刪掉
    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    await flushPromises()
    expect(select(wrapper).props('modelValue')).toBe('ds1')
    expect(view(wrapper).exists()).toBe(false)
    expect(chips().map((c) => c.getAttribute('aria-checked'))).toEqual(['false', 'false'])
    await runAnalyze()
    expect(api.analyze).toHaveBeenLastCalledWith('ds1') // 不會帶著 ds2 的欄位送出
  })

  it('keeps a name the user typed; otherwise takes the new default name', async () => {
    const layout = [bar('c1', 'A', 0, { focus: true }), table('c2', 4)]
    api.analyze
      .mockResolvedValueOnce({ name: 'A 總覽', datasourceId: 'ds1', intents: ['一'], layout })
      .mockResolvedValueOnce({ name: 'B 總覽', datasourceId: 'ds1', intents: ['一', '二'], layout })
      .mockResolvedValueOnce({ name: 'C 總覽', datasourceId: 'ds1', intents: ['一', '二', '三'], layout })

    const wrapper = await openPreview()
    await adjustWith(wrapper, '二')
    expect(view(wrapper).props('config').name).toBe('B 總覽')

    await field(wrapper, '儀表板名稱').find('input').setValue('我的版本')
    await adjustWith(wrapper, '三')
    expect(view(wrapper).props('config').name).toBe('我的版本')
  })

  it('warns when there is a history but no chart matches it, and drops the warning once one does', async () => {
    api.analyze
      .mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', intents: ['各區每月營收'], layout: [bar('c1', 'A', 0), table('c2', 4)] })
      .mockResolvedValueOnce({
        name: 'p',
        datasourceId: 'ds1',
        intents: ['各區每月營收', '看各產品'],
        layout: [bar('c1', 'A', 0), bar('c3', 'C', 1, { focus: true }), table('c2', 4)],
      })

    const wrapper = await openPreview()
    expect(document.body.textContent).toContain('找不到跟需求直接相關的欄位，先顯示一般的分析結果。')

    await adjustWith(wrapper, '看各產品')
    expect(document.body.textContent).not.toContain('找不到跟需求直接相關的欄位')
  })

  it('at 10 sentences the adjust box is disabled and says 需求紀錄最多 10 句', async () => {
    const intents = Array.from({ length: 10 }, (_, i) => `第 ${i + 1} 句`)
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', intents, layout: [bar('c1', 'A', 0, { focus: true }), table('c2', 4)] })

    const wrapper = await openPreview()
    expect(field(wrapper, '想怎麼調整？').props('disabled')).toBe(true)
    expect(document.body.textContent).toContain('需求紀錄最多 10 句')
    expect(button('調整').disabled).toBe(true)
  })

  it('✕ removes the card locally and re-flows the rest (two per row, table last); the next 調整 no longer sends it', async () => {
    api.analyze
      .mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
      .mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', intents: ['不要圓餅圖'], layout: [] })
    const wrapper = await openPreview()

    view(wrapper).vm.$emit('remove', 'c1')
    await flushPromises()

    expect(view(wrapper).props('config').layout.map((s) => [s.chartId, s.gridPos])).toEqual([
      ['c2', { x: 0, y: 0, w: 6, h: 4 }],
      ['c3', { x: 6, y: 0, w: 6, h: 4 }],
      ['c4', { x: 0, y: 4, w: 12, h: 5 }],
    ])
    expect(api.analyze).toHaveBeenCalledTimes(1) // 「✕」不呼叫 API
    expect(region().getAttribute('role')).toBe('status')
    expect(region().textContent).toContain('已移除「A」')
    expect(byText(region(), 'button', '復原')).toBeTruthy()

    await adjustWith(wrapper, '不要圓餅圖')
    expect(api.analyze.mock.calls[1][2].map((s) => s.chartId)).toEqual(['c2', 'c3', 'c4'])
  })

  it('KPI spec 2.3：✕ 之後 KPI 卡照樣排在最上面一排（每排 3 張、4×2），其他圖從 KPI 下面開始', async () => {
    const kpi = (chartId, n) => ({ chartId, type: 'kpi', title: chartId, gridPos: { x: n * 4, y: 0, w: 4, h: 2 },
      mapping: { x: 'date', value: 'amount', aggregate: 'sum', timeGrain: 'month' } })
    const shifted = (s) => ({ ...s, gridPos: { ...s.gridPos, y: s.gridPos.y + 2 } })
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1',
      layout: [kpi('c1', 0), kpi('c2', 1), ...[bar('c3', 'A', 0), bar('c4', 'B', 1), bar('c5', 'C', 2), table('c6', 8)].map(shifted)] })
    const wrapper = await openPreview()

    view(wrapper).vm.$emit('remove', 'c3')
    await flushPromises()
    expect(view(wrapper).props('config').layout.map((s) => [s.chartId, s.gridPos])).toEqual([
      ['c1', { x: 0, y: 0, w: 4, h: 2 }],
      ['c2', { x: 4, y: 0, w: 4, h: 2 }],
      ['c4', { x: 0, y: 2, w: 6, h: 4 }],
      ['c5', { x: 6, y: 2, w: 6, h: 4 }],
      ['c6', { x: 0, y: 6, w: 12, h: 5 }],
    ])

    view(wrapper).vm.$emit('remove', 'c1')
    view(wrapper).vm.$emit('remove', 'c2')
    await flushPromises()
    expect(view(wrapper).props('config').layout.map((s) => [s.chartId, s.gridPos])).toEqual([
      ['c4', { x: 0, y: 0, w: 6, h: 4 }],
      ['c5', { x: 6, y: 0, w: 6, h: 4 }],
      ['c6', { x: 0, y: 4, w: 12, h: 5 }],
    ])
  })

  it('復原 puts the card back in its old slot and closes the notice', async () => {
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
    const wrapper = await openPreview()

    view(wrapper).vm.$emit('remove', 'c2')
    await flushPromises()
    byText(region(), 'button', '復原').click()
    await flushPromises()

    expect(view(wrapper).props('config').layout).toEqual(layout4())
    expect(toastEl()).toBeNull()
  })

  it('復原 button keeps its full size under the global compact density', async () => {
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
    const wrapper = await openPreview()

    view(wrapper).vm.$emit('remove', 'c2')
    await flushPromises()
    expect(byText(region(), 'button', '復原').classList.contains('v-btn--density-default')).toBe(true)
  })

  it('復原 moves focus to the restored card ✕', async () => {
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
    const wrapper = await openPreview()

    view(wrapper).vm.$emit('remove', 'c2')
    await flushPromises()
    byText(region(), 'button', '復原').click()
    await nextTick()
    await nextTick()
    expect(document.activeElement).toBe(document.body.querySelector('[data-chart-id="c2"] [aria-label="移除這張圖"]'))
  })

  it('the notice closes by itself after 6 seconds', async () => {
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
    const wrapper = await openPreview()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })

    view(wrapper).vm.$emit('remove', 'c1')
    await flushPromises()
    vi.advanceTimersByTime(5999)
    await flushPromises()
    expect(toastEl()).not.toBeNull()

    vi.advanceTimersByTime(1)
    await flushPromises()
    expect(toastEl()).toBeNull()
  })

  it('hovering or focusing the notice stops the countdown; it starts over once both have left', async () => {
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
    const wrapper = await openPreview()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })

    view(wrapper).vm.$emit('remove', 'c1')
    await flushPromises()
    vi.advanceTimersByTime(3000)
    toastEl().dispatchEvent(new Event('mouseenter'))
    toastEl().dispatchEvent(new Event('focusin'))
    vi.advanceTimersByTime(10000)
    toastEl().dispatchEvent(new Event('mouseleave'))
    vi.advanceTimersByTime(10000)
    await flushPromises()
    expect(toastEl()).not.toBeNull() // 焦點還在提示裡

    toastEl().dispatchEvent(new Event('focusout'))
    vi.advanceTimersByTime(5999)
    await flushPromises()
    expect(toastEl()).not.toBeNull() // 兩個都離開後重新計 6 秒

    vi.advanceTimersByTime(1)
    await flushPromises()
    expect(toastEl()).toBeNull()
  })

  it('removing another card replaces the notice; only the latest removal can be undone', async () => {
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
    const wrapper = await openPreview()

    view(wrapper).vm.$emit('remove', 'c1')
    await flushPromises()
    view(wrapper).vm.$emit('remove', 'c2')
    await flushPromises()
    expect(region().textContent).toContain('已移除「B」')
    expect(region().textContent).not.toContain('已移除「A」')

    byText(region(), 'button', '復原').click()
    await flushPromises()
    expect(view(wrapper).props('config').layout.map((s) => s.chartId)).toEqual(['c2', 'c3', 'c4'])
  })

  it('捨棄, 重新分析 or 調整 closes the notice for good', async () => {
    api.analyze
      .mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
      .mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
      .mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
      .mockImplementationOnce(() => new Promise(() => {}))
    const wrapper = await openPreview()

    view(wrapper).vm.$emit('remove', 'c1')
    await flushPromises()
    button('捨棄').click()
    await flushPromises()
    expect(toastEl()).toBeNull()

    await runAnalyze() // 再分析一次，預覽回來
    expect(toastEl()).toBeNull()
    view(wrapper).vm.$emit('remove', 'c2')
    await flushPromises()
    expect(toastEl()).not.toBeNull()
    await runAnalyze() // 重新分析
    expect(toastEl()).toBeNull()

    view(wrapper).vm.$emit('remove', 'c3')
    await flushPromises()
    expect(toastEl()).not.toBeNull()
    await field(wrapper, '想怎麼調整？').find('input').setValue('不要圓餅圖')
    button('調整').click()
    await flushPromises()
    expect(toastEl()).toBeNull()
  })

  it('unmounting clears the notice timer', async () => {
    api.analyze.mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
    const wrapper = await openPreview()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })

    view(wrapper).vm.$emit('remove', 'c1')
    await flushPromises()
    const pending = vi.getTimerCount()
    expect(pending).toBeGreaterThan(0)

    wrapper.unmount()
    expect(vi.getTimerCount()).toBe(pending - 1)
  })

  it('加到既有儀表板 shows only when there are dashboards, and lists them in the order given', async () => {
    api.analyze.mockResolvedValue({ name: 'p', layout: layout4() })
    const wrapper = await openPreview()
    expect(button('存成新儀表板')).toBeDefined()
    expect(button('加到既有儀表板')).toBeUndefined()

    await wrapper.setProps({ dashboards: [{ id: 'd2', name: '營運月報' }, { id: 'd1', name: '高階主管週報' }] })
    button('加到既有儀表板').click()
    await flushPromises()
    expect(menuItems()).toEqual(['營運月報', '高階主管週報'])
  })

  it('picking a dashboard sends the previewed charts and intents to appendCharts (a blank name is fine), emits saved with its id and empties the canvas', async () => {
    const preview = { name: 'p', intents: ['各區退貨'], layout: layout4() }
    api.analyze.mockResolvedValue(preview)
    api.appendCharts.mockResolvedValue({ id: 'd1' })
    const wrapper = await openPreview({ dashboards: [{ id: 'd1', name: '高階主管週報' }] })
    await field(wrapper, '儀表板名稱').find('input').setValue('  ')
    await flushPromises()
    expect(button('存成新儀表板').disabled).toBe(true)
    expect(button('加到既有儀表板').disabled).toBe(false)

    await pickDashboard('高階主管週報')
    expect(api.appendCharts).toHaveBeenCalledWith('d1', { layout: layout4(), intents: ['各區退貨'] })
    expect(api.createDashboard).not.toHaveBeenCalled()
    expect(wrapper.emitted('saved')).toEqual([['d1']])
    expect(isEmpty()).toBe(true)
  })

  it('a failed append keeps the preview and shows the error; a 404 (deleted in another tab) also asks for a fresh list', async () => {
    api.analyze.mockResolvedValue({ name: 'p', layout: layout4() })
    api.appendCharts
      .mockRejectedValueOnce(new Error('無法連線到伺服器'))
      .mockRejectedValueOnce(Object.assign(new Error('找不到儀表板'), { status: 404 }))
    const wrapper = await openPreview({ dashboards: [{ id: 'd1', name: '高階主管週報' }] })

    await pickDashboard('高階主管週報')
    expect(errorAlert(wrapper).text()).toContain('無法連線到伺服器')
    expect(view(wrapper).exists()).toBe(true)
    expect(wrapper.emitted('dashboards-changed')).toBeUndefined()

    await pickDashboard('高階主管週報')
    expect(errorAlert(wrapper).text()).toContain('找不到儀表板')
    expect(wrapper.emitted('dashboards-changed')).toHaveLength(1)
    expect(wrapper.emitted('saved')).toBeUndefined()
  })

  it('加到既有儀表板 is disabled while an adjust is pending', async () => {
    api.analyze
      .mockResolvedValueOnce({ name: 'p', intents: ['一'], layout: [bar('c1', 'A', 0, { focus: true }), table('c2', 4)] })
      .mockImplementationOnce(() => new Promise(() => {}))
    const wrapper = await openPreview({ dashboards: [{ id: 'd1', name: '高階主管週報' }] })
    expect(button('加到既有儀表板').disabled).toBe(false)

    await adjustWith(wrapper, '二')
    expect(button('加到既有儀表板').disabled).toBe(true)
  })

  it('調整 sends the datasource chosen on the left (the preview itself has no datasourceId)', async () => {
    const first = { name: 'p', intents: ['一'], layout: [bar('c1', 'A', 0, { focus: true }), table('c2', 4)] }
    api.analyze.mockResolvedValueOnce(first).mockResolvedValueOnce({ ...first, intents: ['一', '二'] })
    const wrapper = await mountBuilder()
    select(wrapper).vm.$emit('update:modelValue', 'ds2')
    await flushPromises()

    await runAnalyze()
    await adjustWith(wrapper, '二')
    expect(api.analyze).toHaveBeenLastCalledWith('ds2', ['一', '二'], first.layout)
  })

  it('比率 follow the fields as % chips with the formula on hover, and can be picked as focus fields', async () => {
    const [sales, ...rest] = sources()
    api.listDatasources.mockResolvedValue([
      { ...sales, ratios: [{ name: '退貨率', numerator: '退貨·amount', denominator: '銷售·amount', sensitive: false }] },
      ...rest,
    ])
    api.analyze.mockResolvedValue({ name: 'p', datasourceId: 'ds1', layout: [table('c1', 0)] })
    await mountBuilder()

    expect(chips().map((c) => c.textContent.trim())).toEqual(['地區', 'amount', '退貨率'])
    expect(chips()[2].getAttribute('title')).toBe('退貨率（比率：退貨·amount ÷ 銷售·amount）')
    expect(chips()[2].querySelector('.mdi-percent')).not.toBeNull()

    chips()[2].click()
    await flushPromises()
    expect(chips()[2].getAttribute('aria-checked')).toBe('true')
    await runAnalyze()
    expect(api.analyze).toHaveBeenLastCalledWith('ds1', undefined, undefined, ['退貨率'])
  })
})

// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import AnalyzeButton from './AnalyzeButton.vue'
import { api } from './api.js'
import { vuetify } from './test-vuetify.js'

vi.mock('./api.js', () => ({
  api: {
    analyze: vi.fn(),
    createDashboard: vi.fn(),
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

function mountButton(datasourceId) {
  return mount(AnalyzeButton, { props: { datasourceId }, global: { plugins: [vuetify] } })
}

const pos = (n) => ({ x: (n % 2) * 6, y: Math.floor(n / 2) * 4, w: 6, h: 4 })
const bar = (chartId, title, n, options) => ({
  chartId, type: 'bar', title, gridPos: pos(n), mapping: { x: 'region', value: 'amount' }, ...(options && { options }),
})
const table = (chartId, y) => ({ chartId, type: 'table', title: '明細', gridPos: { x: 0, y, w: 12, h: 5 }, mapping: { columns: ['region'] } })
const view = (wrapper) => wrapper.findComponent({ name: 'DashboardView' })
const field = (wrapper, label) => wrapper.findAllComponents({ name: 'VTextField' }).find((f) => f.props('label') === label)
const button = (text) => byText(document.body, 'button', text)
const historyItems = () => [...document.body.querySelectorAll('.tc-intents li')].map((li) => li.textContent.trim())
const layout4 = () => [bar('c1', 'A', 0), bar('c2', 'B', 1), bar('c3', 'C', 2), table('c4', 8)]
const region = () => document.body.querySelector('.undo-toast-region')
const toastEl = () => document.body.querySelector('.undo-toast')

async function openPreview() {
  const wrapper = mountButton('ds1')
  await wrapper.get('button').trigger('click')
  await flushPromises()
  return wrapper
}

async function adjustWith(wrapper, sentence) {
  await field(wrapper, '想怎麼調整？').find('input').setValue(sentence)
  button('調整').click()
  await flushPromises()
}

describe('AnalyzeButton', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('Tactile look: the preview comes in as a sheet on the canvas colour, the toolbar is a surface bar, and only 存為我的儀表板 is primary inside it', async () => {
    api.analyze.mockResolvedValue({ name: 'n', datasourceId: 'ds1', layout: layout4() })
    const wrapper = await openPreview()

    expect(wrapper.findComponent({ name: 'VDialog' }).props('transition')).toBe('tc-sheet')
    expect(document.body.querySelector('.v-dialog .v-card').classList.contains('bg-background')).toBe(true)
    expect(document.body.querySelector('.v-dialog .v-toolbar').classList.contains('bg-surface')).toBe(true)
    // 「分析」在元件本身裡，wrapper 沒掛到 document.body，要從 wrapper 找；對話框的內容才會被 teleport 到 body
    expect(wrapper.get('button').classes()).toContain('bg-primary')
    expect(button('存為我的儀表板').classList.contains('bg-primary')).toBe(true)
    expect(button('調整').classList.contains('bg-primary')).toBe(false)
  })

  it('disables 分析 when there is no datasource, enables it once one is selected', () => {
    const w1 = mountButton(null)
    expect(w1.get('button').element.disabled).toBe(true)

    const w2 = mountButton('ds1')
    expect(w2.get('button').element.disabled).toBe(false)
  })

  it('sends the picked fields with the analysis even without an intent', async () => {
    api.analyze.mockResolvedValue({ name: 'p', datasourceId: 'ds1', layout: [] })
    const wrapper = mount(AnalyzeButton, { props: { datasourceId: 'ds1', fields: ['region'] }, global: { plugins: [vuetify] } })
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(api.analyze).toHaveBeenLastCalledWith('ds1', undefined, undefined, ['region'])
  })

  it('analyzes, previews in a dialog, and saves the previewed config', async () => {
    const previewConfig = { name: 'preview', layout: [] }
    api.analyze.mockResolvedValue(previewConfig)
    api.createDashboard.mockResolvedValue({ id: 'd9' })

    const wrapper = mountButton('ds1')
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(api.analyze).toHaveBeenCalledWith('ds1')
    expect(wrapper.findComponent({ name: 'VDialog' }).props('modelValue')).toBe(true)
    const dashboardView = wrapper.findComponent({ name: 'DashboardView' })
    expect(dashboardView.props('preview')).toBe(true)
    expect(dashboardView.props('config')).toEqual(previewConfig)

    const saveBtn = byText(document.body, 'button', '存為我的儀表板')
    saveBtn.click()
    await flushPromises()

    expect(api.createDashboard).toHaveBeenCalledWith(previewConfig)
    expect(wrapper.emitted('saved')).toEqual([['d9']])
    expect(wrapper.findComponent({ name: 'VDialog' }).props('modelValue')).toBe(false)
  })

  it('requires a dashboard name: labels the field, shows an error for blank/whitespace, and disables 存為我的儀表板', async () => {
    const previewConfig = { name: 'preview', layout: [] }
    api.analyze.mockResolvedValue(previewConfig)

    const wrapper = mountButton('ds1')
    await wrapper.get('button').trigger('click')
    await flushPromises()

    const nameField = wrapper.findComponent({ name: 'VTextField' })
    expect(nameField.props('label')).toBe('儀表板名稱')

    await nameField.find('input').setValue('   ')
    await flushPromises()

    expect(document.body.textContent).toContain('請輸入儀表板名稱')
    const saveBtn = byText(document.body, 'button', '存為我的儀表板')
    expect(saveBtn.disabled).toBe(true)
  })

  it('存為我的儀表板 guards against double submission: only one createDashboard call goes out', async () => {
    const previewConfig = { name: 'preview', layout: [] }
    api.analyze.mockResolvedValue(previewConfig)
    let resolveCreate
    api.createDashboard.mockImplementation(() => new Promise((resolve) => { resolveCreate = resolve }))

    const wrapper = mountButton('ds1')
    await wrapper.get('button').trigger('click')
    await flushPromises()

    const saveBtn = byText(document.body, 'button', '存為我的儀表板')
    saveBtn.click()
    saveBtn.click()
    await flushPromises()

    expect(api.createDashboard).toHaveBeenCalledTimes(1)

    resolveCreate({ id: 'd9' })
    await flushPromises()

    expect(wrapper.emitted('saved')).toEqual([['d9']])
  })

  it('shows the error message and keeps the dialog closed when analyze rejects', async () => {
    api.analyze.mockRejectedValue(new Error('未設定 TYPESAFE_API_KEY'))

    const wrapper = mountButton('ds1')
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('未設定 TYPESAFE_API_KEY')
    expect(wrapper.findComponent({ name: 'VDialog' }).props('modelValue')).toBe(false)
  })

  it('left panel: an optional box above 分析 with label, hint and a 200-character counter; 分析 sends the trimmed sentence as the first intent', async () => {
    api.analyze.mockResolvedValue({ name: 'p', datasourceId: 'ds1', layout: [] })

    const blank = mountButton('ds1')
    await blank.findComponent({ name: 'VTextarea' }).find('textarea').setValue('   ')
    await blank.get('button').trigger('click')
    await flushPromises()
    expect(api.analyze).toHaveBeenLastCalledWith('ds1')
    expect(document.body.querySelector('.tc-history').style.display).toBe('none') // 沒有需求紀錄就不顯示
    expect(document.body.textContent).not.toContain('找不到跟需求直接相關的欄位')
    blank.unmount()
    document.body.innerHTML = ''

    const wrapper = mountButton('ds1')
    const box = wrapper.findComponent({ name: 'VTextarea' })
    expect(box.props('label')).toBe('想看什麼？（選填）')
    expect(box.props('placeholder')).toBe('例如：各區每月營收、哪個產品退貨最多')
    expect(wrapper.text()).toContain('這段文字會送給 JEV 做分析')
    expect(wrapper.text()).toContain('0 / 200')
    expect(box.find('textarea').attributes('maxlength')).toBe('200')

    await box.find('textarea').setValue('  各區每月營收  ')
    expect(wrapper.text()).toContain('10 / 200')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(api.analyze).toHaveBeenLastCalledWith('ds1', ['各區每月營收'])
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

  it('調整 failure keeps the preview as it was and shows the error inside the preview', async () => {
    const first = { name: 'A 總覽', datasourceId: 'ds1', intents: ['各區每月營收'], layout: [bar('c1', 'A', 0), table('c2', 4)] }
    api.analyze.mockResolvedValueOnce(first).mockRejectedValueOnce(new Error('JEV 呼叫失敗（HTTP 500）'))

    const wrapper = await openPreview()
    await adjustWith(wrapper, '不要圓餅圖')

    expect(view(wrapper).props('config')).toEqual(first)
    expect(document.body.textContent).toContain('JEV 呼叫失敗（HTTP 500）')
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

  it('存為我的儀表板 is disabled while an adjust is pending', async () => {
    api.analyze
      .mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', intents: ['一'], layout: [bar('c1', 'A', 0, { focus: true }), table('c2', 4)] })
      .mockImplementationOnce(() => new Promise(() => {}))
    const wrapper = await openPreview()
    expect(button('存為我的儀表板').disabled).toBe(false)

    await adjustWith(wrapper, '二')
    expect(button('存為我的儀表板').disabled).toBe(true)
  })

  it('closing the preview clears the adjust error (no alert on the left panel) and drops a late adjust result', async () => {
    const first = { name: 'p', datasourceId: 'ds1', intents: ['一'], layout: [bar('c1', 'A', 0, { focus: true }), table('c2', 4)] }
    let resolveAdjust
    api.analyze
      .mockResolvedValueOnce(first)
      .mockRejectedValueOnce(new Error('JEV 呼叫失敗（HTTP 500）'))
      .mockResolvedValueOnce(first)
      .mockImplementationOnce(() => new Promise((resolve) => { resolveAdjust = resolve }))
    const wrapper = await openPreview()

    await adjustWith(wrapper, '二')
    expect(document.body.textContent).toContain('JEV 呼叫失敗（HTTP 500）')
    button('取消').click()
    await flushPromises()
    expect(wrapper.findComponent({ name: 'VAlert' }).exists()).toBe(false) // 左側面板沒有錯誤訊息

    await wrapper.get('button').trigger('click') // 重新分析，打開預覽
    await flushPromises()
    await adjustWith(wrapper, '三')
    button('取消').click()
    await flushPromises()
    resolveAdjust({ name: 'late', datasourceId: 'ds1', intents: ['一', '三'], layout: [] })
    await flushPromises()
    expect(view(wrapper).props('config')).toEqual(first)
    expect(field(wrapper, '想怎麼調整？').props('modelValue')).toBe('三')
    expect(wrapper.findComponent({ name: 'VAlert' }).exists()).toBe(false)
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

  it('closing the preview or pressing 調整 closes the notice for good', async () => {
    api.analyze
      .mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
      .mockResolvedValueOnce({ name: 'p', datasourceId: 'ds1', layout: layout4() })
      .mockImplementationOnce(() => new Promise(() => {}))
    const wrapper = await openPreview()

    view(wrapper).vm.$emit('remove', 'c1')
    await flushPromises()
    button('取消').click()
    await flushPromises()
    expect(toastEl()).toBeNull()

    await wrapper.get('button').trigger('click') // 再分析一次，重新打開預覽
    await flushPromises()
    expect(toastEl()).toBeNull()

    view(wrapper).vm.$emit('remove', 'c2')
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
})

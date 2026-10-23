// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { nextTick } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import DashboardView from './DashboardView.vue'
import { api } from './api.js'
import { prefersReducedMotion } from './motion.js'
import { vuetify } from './test-vuetify.js'

vi.mock('./api.js', () => ({
  api: {
    previewData: vi.fn(),
    dashboardData: vi.fn(),
    highlights: vi.fn(),
    updateDashboard: vi.fn(),
    filterOptions: vi.fn(),
    appendCharts: vi.fn(),
    me: vi.fn(),
    exportChart: vi.fn(),
  },
}))

vi.mock('./motion.js', () => ({ prefersReducedMotion: vi.fn(() => false) }))

vi.mock('./ChartCard.vue', () => ({
  default: {
    name: 'ChartCard',
    props: ['spec', 'data', 'highlight', 'removable', 'removeDisabled', 'source', 'readonly', 'filtered', 'configurable', 'download'],
    emits: ['remove', 'configure'],
    template: '<div class="chart-card-stub" :data-chart-id="spec.chartId">{{ JSON.stringify({ data, highlight }) }}</div>',
  },
}))

vi.mock('./KpiForm.vue', () => ({
  default: {
    name: 'KpiForm',
    props: ['modelValue', 'spec', 'username', 'save'],
    emits: ['update:modelValue'],
    template: '<div class="kpi-form-stub" />',
  },
}))

vi.mock('grid-layout-plus', () => ({
  GridLayout: {
    name: 'GridLayout',
    props: ['layout', 'colNum', 'rowHeight', 'isDraggable', 'isResizable', 'margin'],
    emits: ['update:layout', 'layout-ready'],
    template: '<div class="grid-layout-stub" :data-is-draggable="isDraggable" :data-is-resizable="isResizable"><slot /></div>',
  },
  GridItem: {
    name: 'GridItem',
    props: ['i', 'x', 'y', 'w', 'h'],
    emits: ['moved', 'resized'],
    template: '<div class="grid-item-stub" :data-i="i"><slot /></div>',
  },
}))

function makeConfig() {
  return {
    id: 'd1',
    name: 'n',
    datasourceId: 'ds1',
    layout: [
      { chartId: 'c1', type: 'bar', title: 'A', gridPos: { x: 0, y: 0, w: 6, h: 4 }, mapping: { x: 'region', value: 'amount' } },
      { chartId: 'c2', type: 'table', title: '明細', gridPos: { x: 0, y: 4, w: 12, h: 5 }, mapping: { columns: ['region'] } },
    ],
  }
}

function chartCardPayload(wrapper, chartId) {
  return JSON.parse(wrapper.get(`[data-chart-id="${chartId}"]`).text())
}

function mountView(props, options) {
  return mount(DashboardView, { props, global: { plugins: [vuetify] }, ...options })
}

const pos = (n) => ({ x: (n % 2) * 6, y: Math.floor(n / 2) * 4, w: 6, h: 4 })
const bar = (chartId, n) => ({ chartId, type: 'bar', title: chartId, gridPos: pos(n), mapping: { x: 'region', value: 'amount' } })
const table = (chartId, y) => ({ chartId, type: 'table', title: '明細', gridPos: { x: 0, y, w: 12, h: 5 }, mapping: { columns: ['region'] } })
const previewOf = (...layout) => ({ name: 'n', datasourceId: 'ds1', layout })
const chartCard = (wrapper, chartId) =>
  wrapper.findAllComponents({ name: 'ChartCard' }).find((c) => c.props('spec').chartId === chartId)
const dialogButton = (label) => [...document.body.querySelectorAll('button')].find((b) => b.textContent.trim() === label)
const withSource = (spec, datasourceId) => ({ ...spec, datasourceId })
const NO_FLAGS = { checked: [], highlights: [] }
const flags = (...highlights) => ({ checked: highlights.map((h) => h.chartId), highlights })

describe('DashboardView', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  it('preview mode: loads via previewData only, disables drag/resize, and never persists grid moves', async () => {
    api.previewData.mockResolvedValue([])
    const config = makeConfig()
    const wrapper = mountView({ config, preview: true })
    await flushPromises()

    expect(api.previewData).toHaveBeenCalledWith(config)
    expect(api.dashboardData).not.toHaveBeenCalled()
    expect(api.highlights).not.toHaveBeenCalled()

    const grid = wrapper.get('.grid-layout-stub')
    expect(grid.attributes('data-is-draggable')).toBe('false')
    expect(grid.attributes('data-is-resizable')).toBe('false')

    const item = wrapper.findComponent({ name: 'GridItem' })
    await item.vm.$emit('moved')
    await flushPromises()
    expect(api.updateDashboard).not.toHaveBeenCalled()

    await item.vm.$emit('resized')
    await flushPromises()
    expect(api.updateDashboard).not.toHaveBeenCalled()
  })

  it('saved mode: calls dashboardData, then highlights only after it resolves, and routes a highlight to its chart', async () => {
    let resolveData
    api.dashboardData.mockImplementation(() => new Promise((resolve) => { resolveData = resolve }))
    api.highlights.mockResolvedValue(flags({ chartId: 'c1', severity: '需要關注', noul: 0.8 }))

    const wrapper = mountView({ config: makeConfig(), preview: false })
    await flushPromises()

    expect(api.dashboardData).toHaveBeenCalledWith('d1', null)
    expect(api.highlights).not.toHaveBeenCalled()

    resolveData([{ chartId: 'c1' }, { chartId: 'c2' }])
    await flushPromises()

    expect(api.highlights).toHaveBeenCalledWith('d1', null)
    expect(chartCardPayload(wrapper, 'c1').highlight).toEqual({ chartId: 'c1', severity: '需要關注', noul: 0.8 })
  })

  it('swallows a highlights failure: charts still get their data, no card gets a highlight, no error alert, and no promise rejection escapes', async () => {
    const rejections = []
    const onRejection = (reason) => rejections.push(reason)
    process.on('unhandledRejection', onRejection)
    try {
      api.dashboardData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }])
      api.highlights.mockRejectedValue(new Error('boom'))

      const wrapper = mountView({ config: makeConfig(), preview: false })
      await flushPromises()

      expect(rejections).toEqual([])
      expect(wrapper.find('.v-alert').exists()).toBe(false)
      const c1 = chartCardPayload(wrapper, 'c1')
      expect(c1.data).toEqual({ chartId: 'c1' })
      expect(c1.highlight).toBeUndefined()
      const c2 = chartCardPayload(wrapper, 'c2')
      expect(c2.highlight).toBeUndefined()
    } finally {
      process.off('unhandledRejection', onRejection)
    }
  })

  it('shows an error alert with the rejection message when dashboardData fails, and does not render the grid', async () => {
    api.dashboardData.mockRejectedValue(new Error('找不到儀表板'))

    const wrapper = mountView({ config: makeConfig(), preview: false })
    await flushPromises()

    expect(wrapper.text()).toContain('找不到儀表板')
    expect(api.highlights).not.toHaveBeenCalled()
    expect(wrapper.find('.grid-layout-stub').exists()).toBe(false)
  })

  it('shows the error message but keeps the grid and cards rendered when a layout save fails', async () => {
    api.dashboardData.mockResolvedValue([])
    api.highlights.mockResolvedValue(NO_FLAGS)
    api.updateDashboard.mockRejectedValue(new Error('存檔失敗'))

    const wrapper = mountView({ config: makeConfig(), preview: false })
    await flushPromises()

    const [firstItem] = wrapper.findAllComponents({ name: 'GridItem' })
    await firstItem.vm.$emit('moved')
    await flushPromises()

    expect(wrapper.text()).toContain('存檔失敗')
    expect(wrapper.find('.grid-layout-stub').exists()).toBe(true)
    expect(wrapper.findAll('.chart-card-stub').length).toBe(2)
  })

  it('clears the save-error alert once a later save succeeds', async () => {
    api.dashboardData.mockResolvedValue([])
    api.highlights.mockResolvedValue(NO_FLAGS)
    api.updateDashboard
      .mockRejectedValueOnce(new Error('無法連線到伺服器'))
      .mockResolvedValueOnce(undefined)

    const wrapper = mountView({ config: makeConfig(), preview: false })
    await flushPromises()

    const [firstItem] = wrapper.findAllComponents({ name: 'GridItem' })
    await firstItem.vm.$emit('moved')
    await flushPromises()

    expect(wrapper.text()).toContain('無法連線到伺服器')
    expect(wrapper.findAll('.chart-card-stub').length).toBe(2)

    await firstItem.vm.$emit('moved')
    await flushPromises()

    expect(wrapper.find('.v-alert').exists()).toBe(false)
  })

  it('shows the no-chart notice only when every chart in the layout is a table', async () => {
    api.dashboardData.mockResolvedValue([])
    api.highlights.mockResolvedValue(NO_FLAGS)

    const tableOnly = makeConfig()
    tableOnly.layout = tableOnly.layout.filter((s) => s.type === 'table')
    const w1 = mountView({ config: tableOnly, preview: false })
    await flushPromises()
    expect(w1.text()).toContain('找不到適合的圖表')

    const w2 = mountView({ config: makeConfig(), preview: false })
    await flushPromises()
    expect(w2.text()).not.toContain('找不到適合的圖表')
  })

  it('persists moved/resized grid positions for every chart via updateDashboard', async () => {
    api.dashboardData.mockResolvedValue([])
    api.highlights.mockResolvedValue(NO_FLAGS)
    api.updateDashboard.mockResolvedValue(undefined)

    const wrapper = mountView({ config: makeConfig(), preview: false })
    await flushPromises()

    const gridLayout = wrapper.getComponent({ name: 'GridLayout' })
    await gridLayout.vm.$emit('update:layout', [
      { i: 'c1', x: 6, y: 0, w: 6, h: 4 },
      { i: 'c2', x: 0, y: 4, w: 12, h: 5 },
    ])
    const [firstItem] = wrapper.findAllComponents({ name: 'GridItem' })
    await firstItem.vm.$emit('moved')
    await flushPromises()

    expect(api.updateDashboard).toHaveBeenCalledTimes(1)
    const [id, next] = api.updateDashboard.mock.calls[0]
    expect(id).toBe('d1')
    expect(next.layout).toEqual([
      { chartId: 'c1', type: 'bar', title: 'A', gridPos: { x: 6, y: 0, w: 6, h: 4 }, mapping: { x: 'region', value: 'amount' } },
      { chartId: 'c2', type: 'table', title: '明細', gridPos: { x: 0, y: 4, w: 12, h: 5 }, mapping: { columns: ['region'] } },
    ])

    api.updateDashboard.mockClear()
    await gridLayout.vm.$emit('update:layout', [
      { i: 'c1', x: 6, y: 0, w: 6, h: 4 },
      { i: 'c2', x: 0, y: 4, w: 6, h: 3 },
    ])
    await firstItem.vm.$emit('resized')
    await flushPromises()

    expect(api.updateDashboard).toHaveBeenCalledTimes(1)
    const [resizedId, resizedNext] = api.updateDashboard.mock.calls[0]
    expect(resizedId).toBe('d1')
    expect(resizedNext.layout).toEqual([
      { chartId: 'c1', type: 'bar', title: 'A', gridPos: { x: 6, y: 0, w: 6, h: 4 }, mapping: { x: 'region', value: 'amount' } },
      { chartId: 'c2', type: 'table', title: '明細', gridPos: { x: 0, y: 4, w: 6, h: 3 }, mapping: { columns: ['region'] } },
    ])
  })

  it('stale-response guard: a late-resolving previous load does not clobber the current config’s data or highlight', async () => {
    const configA = makeConfig()
    const configB = { ...makeConfig(), id: 'd2' }

    let resolveA
    let dashboardDataCalls = 0
    api.dashboardData.mockImplementation(() => {
      dashboardDataCalls += 1
      if (dashboardDataCalls === 1) return new Promise((resolve) => { resolveA = resolve })
      return Promise.resolve([{ chartId: 'c1', tag: 'B' }, { chartId: 'c2', tag: 'B' }])
    })
    api.highlights.mockResolvedValue(flags({ chartId: 'c1', severity: 'B-severity' }))

    const wrapper = mountView({ config: configA, preview: false })
    await flushPromises()

    await wrapper.setProps({ config: configB })
    await flushPromises()

    // B has fully loaded (data + highlight) before A's stale promise settles.
    expect(chartCardPayload(wrapper, 'c1').data).toEqual({ chartId: 'c1', tag: 'B' })

    resolveA([{ chartId: 'c1', tag: 'A' }, { chartId: 'c2', tag: 'A' }])
    await flushPromises()

    const c1 = chartCardPayload(wrapper, 'c1')
    expect(c1.data).toEqual({ chartId: 'c1', tag: 'B' })
    expect(c1.highlight).toEqual({ chartId: 'c1', severity: 'B-severity' })
  })

  it('config change: a removed card keeps its exit class for 120 ms before it leaves the grid; kept cards stay mounted and keep their data until new data arrives', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    api.previewData.mockResolvedValueOnce([{ chartId: 'c1', v: 'old' }, { chartId: 'c2', v: 'old' }, { chartId: 'c3', v: 'old' }])
    const wrapper = mountView({ config: previewOf(bar('c1', 0), bar('c2', 1), table('c3', 4)), preview: true })
    await flushPromises()
    const kept = chartCard(wrapper, 'c1').vm

    let resolveNext
    api.previewData.mockImplementationOnce(() => new Promise((resolve) => { resolveNext = resolve }))
    await wrapper.setProps({ config: previewOf(bar('c1', 0), bar('c4', 1), table('c3', 4)) })

    expect(wrapper.get('[data-chart-id="c2"]').classes()).toContain('tc-card-leave')
    vi.advanceTimersByTime(119)
    await flushPromises()
    expect(wrapper.find('[data-chart-id="c2"]').exists()).toBe(true)
    expect(wrapper.find('[data-chart-id="c4"]').exists()).toBe(false)
    expect(api.previewData).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(1)
    await flushPromises()
    expect(wrapper.find('[data-chart-id="c2"]').exists()).toBe(false)
    expect(chartCard(wrapper, 'c1').vm).toBe(kept)
    expect(chartCardPayload(wrapper, 'c1').data).toEqual({ chartId: 'c1', v: 'old' })

    resolveNext([{ chartId: 'c1', v: 'new' }, { chartId: 'c4', v: 'new' }, { chartId: 'c3', v: 'new' }])
    await flushPromises()
    expect(chartCardPayload(wrapper, 'c1').data).toEqual({ chartId: 'c1', v: 'new' })
    expect(chartCardPayload(wrapper, 'c4').data).toEqual({ chartId: 'c4', v: 'new' })
  })

  it('reduced motion: a removed card leaves at once', async () => {
    prefersReducedMotion.mockReturnValue(true)
    api.previewData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }, { chartId: 'c3' }])
    const wrapper = mountView({ config: previewOf(bar('c1', 0), bar('c2', 1), table('c3', 4)), preview: true })
    await flushPromises()

    await wrapper.setProps({ config: previewOf(bar('c1', 0), table('c3', 4)) })
    await flushPromises()
    expect(wrapper.find('[data-chart-id="c2"]').exists()).toBe(false)
  })

  it('preview: nothing enters on the first display (the dialog has its own transition); cards that show up later enter, staggered after the kept cards', async () => {
    api.previewData.mockResolvedValue([])
    const wrapper = mountView({ config: previewOf(bar('c1', 0), bar('c2', 1), table('c3', 4)), preview: true })
    await flushPromises()
    expect(wrapper.findAll('.tc-card-enter')).toHaveLength(0)

    await wrapper.setProps({ config: previewOf(bar('c1', 0), bar('c2', 1), bar('c5', 2), bar('c6', 3), table('c3', 8)) })
    await flushPromises()
    const c5 = wrapper.get('[data-chart-id="c5"]')
    const c6 = wrapper.get('[data-chart-id="c6"]')
    expect(c5.classes()).toContain('tc-card-enter')
    expect(c5.element.style.getPropertyValue('--tc-i')).toBe('2')
    expect(c6.element.style.getPropertyValue('--tc-i')).toBe('3')
    expect(wrapper.get('[data-chart-id="c1"]').classes()).not.toContain('tc-card-enter')
  })

  it('adds is-settled only two frames after GridLayout reports layout-ready', async () => {
    let frame
    vi.stubGlobal('requestAnimationFrame', (cb) => {
      frame = cb
      return 1
    })
    api.previewData.mockResolvedValue([])
    const wrapper = mountView({ config: previewOf(bar('c1', 0), table('c3', 4)), preview: true })
    await flushPromises()
    const grid = wrapper.get('.grid-layout-stub')
    expect(grid.classes()).not.toContain('is-settled')

    wrapper.getComponent({ name: 'GridLayout' }).vm.$emit('layout-ready')
    await nextTick()
    expect(grid.classes()).not.toContain('is-settled')

    frame()
    await nextTick()
    expect(grid.classes()).not.toContain('is-settled')

    frame()
    await nextTick()
    expect(grid.classes()).toContain('is-settled')
  })

  it('preview: a config that only drops cards does not fetch data again', async () => {
    prefersReducedMotion.mockReturnValue(true)
    api.previewData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }, { chartId: 'c3' }])
    const wrapper = mountView({ config: previewOf(bar('c1', 0), bar('c2', 1), table('c3', 4)), preview: true })
    await flushPromises()

    await wrapper.setProps({ config: previewOf(bar('c1', 0), table('c3', 4)) })
    await flushPromises()
    expect(api.previewData).toHaveBeenCalledTimes(1)
    expect(chartCardPayload(wrapper, 'c1').data).toEqual({ chartId: 'c1' })
  })

  it('preview: a card that comes back unchanged (復原) reuses its data without fetching; the same chartId with a different chart fetches', async () => {
    prefersReducedMotion.mockReturnValue(true)
    api.previewData.mockResolvedValue([{ chartId: 'c1', v: 1 }, { chartId: 'c2', v: 2 }, { chartId: 'c3', v: 3 }])
    const wrapper = mountView({ config: previewOf(bar('c1', 0), bar('c2', 1), table('c3', 4)), preview: true })
    await flushPromises()

    await wrapper.setProps({ config: previewOf(bar('c1', 0), table('c3', 4)) })
    await flushPromises()
    await wrapper.setProps({ config: previewOf(bar('c1', 0), bar('c2', 1), table('c3', 4)) })
    await flushPromises()
    expect(api.previewData).toHaveBeenCalledTimes(1)
    expect(chartCardPayload(wrapper, 'c2').data).toEqual({ chartId: 'c2', v: 2 })

    await wrapper.setProps({ config: previewOf(bar('c1', 0), table('c3', 4)) })
    await flushPromises()
    const other = { ...bar('c2', 1), mapping: { x: 'product', value: 'qty' } } // 同一個 chartId，但已經是另一張圖
    await wrapper.setProps({ config: previewOf(bar('c1', 0), other, table('c3', 4)) })
    await flushPromises()
    expect(api.previewData).toHaveBeenCalledTimes(2)
  })

  it('unmounting during the exit wait cancels the pending load', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    api.previewData.mockResolvedValue([])
    const wrapper = mountView({ config: previewOf(bar('c1', 0), bar('c2', 1), table('c3', 4)), preview: true })
    await flushPromises()

    await wrapper.setProps({ config: previewOf(bar('c1', 0), bar('c4', 1), table('c3', 4)) })
    wrapper.unmount()
    vi.advanceTimersByTime(120)
    await flushPromises()
    expect(api.previewData).toHaveBeenCalledTimes(1)
  })

  it('preview: cards get ✕ (disabled while busy), the grid area says aria-busy, and a card’s remove is passed up with its chartId', async () => {
    api.previewData.mockResolvedValue([])
    const wrapper = mountView({ config: makeConfig(), preview: true, busy: true })
    await flushPromises()

    const cards = wrapper.findAllComponents({ name: 'ChartCard' })
    expect(cards.map((c) => [c.props('removable'), c.props('removeDisabled')])).toEqual([[true, true], [false, true]])
    expect(cards.map((c) => c.vm.$attrs['data-chart-id'])).toEqual(['c1', 'c2']) // 「復原」後找回這張卡片的「✕」
    expect(wrapper.get('.dash-grid').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('.dash-grid').classes()).toContain('is-busy')

    await wrapper.setProps({ busy: false })
    expect(wrapper.get('.dash-grid').attributes('aria-busy')).toBeUndefined()
    expect(cards[0].props('removeDisabled')).toBe(false)

    cards[0].vm.$emit('remove')
    expect(wrapper.emitted('remove')).toEqual([['c1']])
  })

  it('✕: every card of a saved dashboard has one, the table too; in a preview the table has none and no 依據 shows', async () => {
    api.dashboardData.mockResolvedValue([])
    api.highlights.mockResolvedValue(NO_FLAGS)
    const saved = mountView({ config: makeConfig(), preview: false })
    await flushPromises()
    expect(saved.findAllComponents({ name: 'ChartCard' }).map((c) => c.props('removable'))).toEqual([true, true])

    api.previewData.mockResolvedValue([])
    const inPreview = mountView({ config: { ...makeConfig(), basis: [{ datasourceId: 'ds1', intents: ['各區每月營收'] }] }, preview: true })
    await flushPromises()
    expect(inPreview.findAllComponents({ name: 'ChartCard' }).map((c) => c.props('removable'))).toEqual([true, false])
    expect(inPreview.text()).not.toContain('依據')
  })

  it('saved dashboard, first display: every card enters, staggered in on-screen (y, x) order rather than array order', async () => {
    api.dashboardData.mockResolvedValue([])
    api.highlights.mockResolvedValue(NO_FLAGS)
    // 陣列順序跟畫面順序不同：c1 在右上、c2 在左上，好比使用者拖過之後存的版面
    const config = { ...makeConfig(), layout: [bar('c1', 1), bar('c2', 0), table('c3', 4)] }
    const wrapper = mountView({ config, preview: false })
    await flushPromises()

    const card = (id) => wrapper.get(`[data-chart-id="${id}"]`)
    for (const id of ['c1', 'c2', 'c3']) expect(card(id).classes()).toContain('tc-card-enter')
    expect(['c2', 'c1', 'c3'].map((id) => card(id).element.style.getPropertyValue('--tc-i'))).toEqual(['0', '1', '2'])
  })

  it('cards sit 14px apart', async () => {
    api.previewData.mockResolvedValue([])
    const wrapper = mountView({ config: makeConfig(), preview: true })
    await flushPromises()
    expect(wrapper.getComponent({ name: 'GridLayout' }).props('margin')).toEqual([14, 14])
  })

  it('saved: ✕ asks first; 取消 does nothing; 移除 PUTs the current grid without that card and emits updated with the reply', async () => {
    api.dashboardData.mockResolvedValue([{ chartId: 'c1', title: '金額 依 地區' }, { chartId: 'c2' }])
    api.highlights.mockResolvedValue(NO_FLAGS)
    const reply = { ...makeConfig(), layout: [makeConfig().layout[1]] }
    api.updateDashboard.mockResolvedValue(reply)
    const wrapper = mountView({ config: makeConfig(), preview: false })
    await flushPromises()
    // 使用者拖過明細表、還沒重新整理：移除 c1 時要用格線上現在的位置
    await wrapper.getComponent({ name: 'GridLayout' }).vm.$emit('update:layout', [
      { i: 'c1', x: 0, y: 0, w: 6, h: 4 },
      { i: 'c2', x: 0, y: 9, w: 12, h: 5 },
    ])

    chartCard(wrapper, 'c1').vm.$emit('remove')
    await flushPromises()
    expect(document.body.querySelector('.v-card-title').textContent.trim()).toBe('移除圖表')
    expect(document.body.textContent).toContain('確定從「n」移除「金額 依 地區」？移除後無法復原，要加回來得重新分析。')
    dialogButton('取消').click()
    await flushPromises()
    expect(api.updateDashboard).not.toHaveBeenCalled()
    expect(document.body.textContent).not.toContain('確定從')
    expect(wrapper.emitted('remove')).toBeUndefined()

    chartCard(wrapper, 'c1').vm.$emit('remove')
    await flushPromises()
    expect(dialogButton('移除').classList.contains('bg-error')).toBe(true)
    dialogButton('移除').click()
    await flushPromises()
    expect(api.updateDashboard).toHaveBeenCalledTimes(1)
    const [id, next] = api.updateDashboard.mock.calls[0]
    expect(id).toBe('d1')
    expect(next.layout).toEqual([{ ...makeConfig().layout[1], gridPos: { x: 0, y: 9, w: 12, h: 5 } }])
    expect(wrapper.emitted('updated')).toEqual([[reply]])
  })

  it('saved: a failed remove shows the error, keeps the card and emits nothing', async () => {
    api.dashboardData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }])
    api.highlights.mockResolvedValue(NO_FLAGS)
    api.updateDashboard.mockRejectedValue(new Error('找不到儀表板'))
    const wrapper = mountView({ config: makeConfig(), preview: false })
    await flushPromises()

    chartCard(wrapper, 'c1').vm.$emit('remove')
    await flushPromises()
    dialogButton('移除').click()
    await flushPromises()
    expect(wrapper.text()).toContain('找不到儀表板')
    expect(wrapper.find('[data-chart-id="c1"]').exists()).toBe(true)
    expect(wrapper.emitted('updated')).toBeUndefined()
  })

  it('saved: a remove waits for an earlier layout save before sending its PUT', async () => {
    api.dashboardData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }])
    api.highlights.mockResolvedValue(NO_FLAGS)
    let resolveLayout
    const reply = { ...makeConfig(), layout: [makeConfig().layout[1]] }
    api.updateDashboard
      .mockImplementationOnce(() => new Promise((resolve) => { resolveLayout = resolve }))
      .mockResolvedValueOnce(reply)
    const wrapper = mountView({ config: makeConfig(), preview: false })
    await flushPromises()

    await wrapper.findComponent({ name: 'GridItem' }).vm.$emit('moved')
    await flushPromises()
    expect(api.updateDashboard).toHaveBeenCalledTimes(1)

    chartCard(wrapper, 'c1').vm.$emit('remove')
    await flushPromises()
    dialogButton('移除').click()
    await flushPromises()
    expect(api.updateDashboard).toHaveBeenCalledTimes(1)

    resolveLayout(makeConfig())
    await flushPromises()
    expect(api.updateDashboard).toHaveBeenCalledTimes(2)
    expect(api.updateDashboard.mock.calls[1][1].layout.map((s) => s.chartId)).toEqual(['c2'])
    expect(wrapper.emitted('updated')).toEqual([[reply]])
  })

  it('saved: when removing a card leaves the config from this view’s own PUT, the rest keep their data and highlight without fetching again', async () => {
    prefersReducedMotion.mockReturnValue(true)
    api.dashboardData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }, { chartId: 'c3' }])
    api.highlights.mockResolvedValue(flags({ chartId: 'c2', severity: '需要關注', noul: 0.8 }))
    const config = { ...makeConfig(), layout: [bar('c1', 0), bar('c2', 1), table('c3', 4)] }
    const reply = { ...config, layout: [bar('c2', 1), table('c3', 4)] }
    api.updateDashboard.mockResolvedValue(reply)
    const wrapper = mountView({ config, preview: false })
    await flushPromises()

    chartCard(wrapper, 'c1').vm.$emit('remove')
    await flushPromises()
    dialogButton('移除').click()
    await flushPromises()
    expect(wrapper.emitted('updated')).toEqual([[reply]])

    // App 把回傳的物件原封不動塞回 config（同一個參考），才算「自己這次的 PUT」
    await wrapper.setProps({ config: reply })
    await flushPromises()
    expect(wrapper.find('[data-chart-id="c1"]').exists()).toBe(false)
    expect(api.dashboardData).toHaveBeenCalledTimes(1)
    expect(api.highlights).toHaveBeenCalledTimes(1)
    expect(chartCardPayload(wrapper, 'c2')).toEqual({ data: { chartId: 'c2' }, highlight: { chartId: 'c2', severity: '需要關注', noul: 0.8 } })
  })

  it('saved: re-opening the same dashboard (a new object, same id) refetches data and highlights instead of taking the drop-a-card shortcut', async () => {
    api.dashboardData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }])
    api.highlights.mockResolvedValue(NO_FLAGS)
    const config = makeConfig()
    const wrapper = mountView({ config, preview: false })
    await flushPromises()
    expect(api.dashboardData).toHaveBeenCalledTimes(1)
    expect(api.highlights).toHaveBeenCalledTimes(1)

    // App 重新 GET 同一份儀表板：id 一樣，但不是這個元件自己 PUT 回來的那個物件
    await wrapper.setProps({ config: { ...config } })
    await flushPromises()
    expect(api.dashboardData).toHaveBeenCalledTimes(2)
    expect(api.highlights).toHaveBeenCalledTimes(2)
  })

  it('saved: removing a card while the previous highlights request is still pending asks again, so the badge still shows up', async () => {
    prefersReducedMotion.mockReturnValue(true)
    api.dashboardData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }])
    api.highlights
      .mockImplementationOnce(() => new Promise(() => {})) // 一直不回應，模擬還在等 JEV
      .mockResolvedValueOnce(flags({ chartId: 'c2', severity: '需要關注', noul: 0.8 }))
    const config = makeConfig()
    const wrapper = mountView({ config, preview: false })
    await flushPromises()
    expect(api.highlights).toHaveBeenCalledTimes(1)

    const reply = { ...config, layout: [config.layout[1]] }
    api.updateDashboard.mockResolvedValue(reply)
    chartCard(wrapper, 'c1').vm.$emit('remove')
    await flushPromises()
    dialogButton('移除').click()
    await flushPromises()
    await wrapper.setProps({ config: reply }) // App 把 PUT 的回覆塞回 config，跟 removeCard 那次 emit 對應
    await flushPromises()

    expect(api.highlights).toHaveBeenCalledTimes(2)
    expect(chartCardPayload(wrapper, 'c2').highlight).toEqual({ chartId: 'c2', severity: '需要關注', noul: 0.8 })
  })

  it('saved: while a remove PUT is in flight, ✕ is disabled and a grid move in the meantime is not persisted', async () => {
    api.dashboardData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }])
    api.highlights.mockResolvedValue(NO_FLAGS)
    let resolvePut
    api.updateDashboard.mockImplementation(() => new Promise((resolve) => { resolvePut = resolve }))
    const wrapper = mountView({ config: makeConfig(), preview: false })
    await flushPromises()

    chartCard(wrapper, 'c1').vm.$emit('remove')
    await flushPromises()
    dialogButton('移除').click()
    await flushPromises()

    expect(chartCard(wrapper, 'c2').props('removeDisabled')).toBe(true)

    const [firstItem] = wrapper.findAllComponents({ name: 'GridItem' })
    await firstItem.vm.$emit('moved')
    await flushPromises()
    expect(api.updateDashboard).toHaveBeenCalledTimes(1) // 只有移除那次 PUT，拖拉沒有多存一次

    resolvePut({ ...makeConfig(), layout: [makeConfig().layout[1]] })
    await flushPromises()
    expect(chartCard(wrapper, 'c2').props('removeDisabled')).toBe(false)
  })

  it('a saved dashboard with no cards says how to add charts back, instead of 找不到適合的圖表', async () => {
    api.dashboardData.mockResolvedValue([])
    api.highlights.mockResolvedValue(NO_FLAGS)
    const wrapper = mountView({ config: { ...makeConfig(), layout: [] }, preview: false })
    await flushPromises()
    expect(wrapper.text()).toContain('這份儀表板沒有圖表了。到「製圖」分析後，用「加到既有儀表板」把圖加回來。')
    expect(wrapper.text()).not.toContain('找不到適合的圖表')
  })

  it('依據: one line per group; one source reads 依據：a → b, several sources name each source once /data is back (no name, no brackets)', async () => {
    api.highlights.mockResolvedValue(NO_FLAGS)
    api.dashboardData.mockResolvedValue([])
    const single = mountView({ config: { ...makeConfig(), basis: [{ datasourceId: 'ds1', intents: ['各區每月營收', '不要圓餅圖'] }] }, preview: false })
    await flushPromises()
    expect(single.findAll('.dash-basis').map((l) => l.text())).toEqual(['依據：各區每月營收 → 不要圓餅圖'])

    let resolveData
    api.dashboardData.mockImplementation(() => new Promise((resolve) => { resolveData = resolve }))
    const multi = mountView({
      config: {
        id: 'd2',
        name: 'n',
        layout: [withSource(bar('c1', 0), 'ds1'), withSource(bar('c2', 1), 'ds2'), withSource(bar('c3', 2), 'ds3')],
        basis: [
          { datasourceId: 'ds1', intents: ['各區每月營收'] },
          { datasourceId: 'ds2', intents: ['各區退貨', '不要圓餅圖'] },
          { datasourceId: 'ds3', intents: ['庫存'] },
        ],
      },
      preview: false,
    })
    await flushPromises()
    expect(multi.findAll('.dash-basis')).toHaveLength(0)

    resolveData([
      { chartId: 'c1', source: '銷售 API' },
      { chartId: 'c2', source: 'R7 Excel 退貨' },
      { chartId: 'c3', error: 'SOURCE_FAILED' },
    ])
    await flushPromises()
    expect(multi.findAll('.dash-basis').map((l) => l.text())).toEqual([
      '依據（銷售 API）：各區每月營收',
      '依據（R7 Excel 退貨）：各區退貨 → 不要圓餅圖',
      '依據：庫存',
    ])
  })

  it('names the source on each card only when the dashboard mixes sources', async () => {
    api.highlights.mockResolvedValue(NO_FLAGS)
    api.dashboardData.mockResolvedValue([{ chartId: 'c1', source: '銷售' }, { chartId: 'c2', source: '退貨' }])
    const mixed = mountView({ config: { id: 'd1', name: 'n', layout: [withSource(bar('c1', 0), 'ds1'), withSource(bar('c2', 1), 'ds2')] }, preview: false })
    await flushPromises()
    expect(['c1', 'c2'].map((id) => chartCard(mixed, id).props('source'))).toEqual(['銷售', '退貨'])

    api.dashboardData.mockResolvedValue([{ chartId: 'c1', source: '銷售' }, { chartId: 'c2', source: '銷售' }])
    const single = mountView({ config: { id: 'd2', name: 'n', layout: [withSource(bar('c1', 0), 'ds1'), withSource(bar('c2', 1), 'ds1')] }, preview: false })
    await flushPromises()
    expect(['c1', 'c2'].map((id) => chartCard(single, id).props('source'))).toEqual([null, null])
  })

  it('readonly 時不能拖拉、不能縮放、卡片沒有 ✕，也把 readonly 傳給卡片', async () => {
    api.dashboardData.mockResolvedValue([])
    api.highlights.mockResolvedValue(NO_FLAGS)
    const wrapper = mountView({ config: makeConfig(), readonly: true })
    await flushPromises()

    const grid = wrapper.get('.grid-layout-stub')
    expect(grid.attributes('data-is-draggable')).toBe('false')
    expect(grid.attributes('data-is-resizable')).toBe('false')
    expect(wrapper.findAllComponents({ name: 'ChartCard' }).map((c) => [c.props('removable'), c.props('readonly')]))
      .toEqual([[false, true], [false, true]])
  })

  it('readonly 時 moved、resized 不送 PUT', async () => {
    api.dashboardData.mockResolvedValue([])
    api.highlights.mockResolvedValue(NO_FLAGS)
    api.updateDashboard.mockResolvedValue(undefined)
    const wrapper = mountView({ config: makeConfig(), readonly: true })
    await flushPromises()

    const [firstItem] = wrapper.findAllComponents({ name: 'GridItem' })
    await firstItem.vm.$emit('moved')
    await firstItem.vm.$emit('resized')
    await flushPromises()
    expect(api.updateDashboard).not.toHaveBeenCalled()
  })

  it('readonly 而且沒有圖表時顯示「這份儀表板沒有圖表。」', async () => {
    api.dashboardData.mockResolvedValue([])
    api.highlights.mockResolvedValue(NO_FLAGS)
    const wrapper = mountView({ config: { ...makeConfig(), layout: [] }, readonly: true })
    await flushPromises()
    expect(wrapper.get('.v-alert').text()).toBe('這份儀表板沒有圖表。')
  })

  describe('本期重點', () => {
    const NORTH = { period: null, fields: [{ field: '地區', values: ['北區'] }] }
    const LAYOUT = [bar('c1', 0), bar('c2', 1), bar('c3', 2), bar('c4', 3)]
    const DATA = LAYOUT.map((s) => ({ chartId: s.chartId }))
    const saved = (extra = {}) => ({ ...makeConfig(), layout: LAYOUT, ...extra })
    const flag = (chartId, severity, level) => ({
      chartId,
      severity,
      level,
      noul: 0.8,
      change: { previousPeriod: '2026-08', period: '2026-09', measure: null, series: null, previous: 100, current: 120, percent: false },
    })
    const allChecked = (...highlights) => ({ checked: LAYOUT.map((s) => s.chartId), highlights })
    const CHECK = flags(flag('c1', '值得注意', 1), flag('c2', '嚴重警示', 3), flag('c3', '正常波動', 0), flag('c4', '需要關注', 2))
    const UP = '2026-09 比 2026-08 ▲ 20%'
    const rows = (wrapper) => wrapper.findAll('.dash-digest__item').map((b) => [...b.element.children].map((s) => s.textContent.trim()))
    const panel = (wrapper) => wrapper.find('.dash-digest')
    const filterBar = (wrapper) => wrapper.findComponent({ name: 'DashboardFilters' })

    it('已存的儀表板：照等級列出，正常波動不列；按一筆捲到那張卡、焦點移到卡片上，減少動態效果時不平滑捲動', async () => {
      const scrollIntoView = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {})
      api.dashboardData.mockResolvedValue(DATA)
      api.highlights.mockResolvedValue(CHECK)
      const wrapper = mountView({ config: saved() }, { attachTo: document.body })
      await flushPromises()

      expect(panel(wrapper).get('h2').text()).toBe('本期重點')
      expect(rows(wrapper)).toEqual([['嚴重警示', 'c2', UP], ['需要關注', 'c4', UP], ['值得注意', 'c1', UP]])
      expect(wrapper.get('.dash-digest .tc-badge').classes()).toContain('tc-badge--err')

      await wrapper.findAll('.dash-digest__item')[1].trigger('click')
      const c4 = wrapper.get('[data-chart-id="c4"]').element
      expect(scrollIntoView).toHaveBeenCalledTimes(1)
      expect(scrollIntoView.mock.contexts[0]).toBe(c4)
      expect(scrollIntoView).toHaveBeenLastCalledWith({ behavior: 'smooth', block: 'nearest' })
      expect(document.activeElement).toBe(c4)

      prefersReducedMotion.mockReturnValue(true)
      await wrapper.findAll('.dash-digest__item')[0].trigger('click')
      expect(scrollIntoView).toHaveBeenLastCalledWith({ behavior: 'auto', block: 'nearest' })
      expect(document.activeElement).toBe(wrapper.get('[data-chart-id="c2"]').element)
      scrollIntoView.mockRestore()
    })

    it('/highlight 失敗、回 204、沒有卡送去檢查：沒有本期重點，也沒有徽章', async () => {
      api.dashboardData.mockResolvedValue(DATA)
      api.highlights.mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce(null).mockResolvedValueOnce(NO_FLAGS)
      for (let i = 0; i < 3; i++) {
        const wrapper = mountView({ config: saved() })
        await flushPromises()
        expect(panel(wrapper).exists()).toBe(false)
        expect(chartCardPayload(wrapper, 'c2').highlight).toBeUndefined()
      }
      expect(api.highlights).toHaveBeenCalledTimes(3)
    })

    it('檢查了 N 張、沒有符合的：寫出那句話和 N', async () => {
      api.dashboardData.mockResolvedValue(DATA)
      api.highlights.mockResolvedValue({ checked: ['c1', 'c2', 'c3'], highlights: [flag('c3', '正常波動', 0)] })
      const wrapper = mountView({ config: saved() })
      await flushPromises()
      expect(panel(wrapper).text()).toContain('本期沒有需要注意的變化（檢查了 3 張卡）')
      expect(rows(wrapper)).toEqual([])
    })

    it('改篩選：本期重點先消失，新的結果回來才出現，中間不會閃出「沒有需要注意的變化」', async () => {
      api.dashboardData.mockResolvedValueOnce(DATA)
      api.highlights.mockResolvedValueOnce(CHECK)
      const wrapper = mountView({ config: saved() })
      await flushPromises()
      expect(rows(wrapper)).toHaveLength(3)

      let resolveData
      let resolveHighlights
      api.dashboardData.mockImplementationOnce(() => new Promise((resolve) => { resolveData = resolve }))
      api.highlights.mockImplementationOnce(() => new Promise((resolve) => { resolveHighlights = resolve }))
      await filterBar(wrapper).vm.$emit('update:modelValue', NORTH)
      await flushPromises()
      expect(panel(wrapper).exists()).toBe(false)

      resolveData(DATA)
      await flushPromises()
      expect(panel(wrapper).exists()).toBe(false)

      resolveHighlights(allChecked(flag('c3', '值得注意', 1)))
      await flushPromises()
      expect(rows(wrapper)).toEqual([['值得注意', 'c3', UP]])
    })

    it('自動更新：新的結果回來前舊的留著，回來才換', async () => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
      api.dashboardData.mockResolvedValue(DATA)
      api.highlights.mockResolvedValueOnce(CHECK)
      const wrapper = mountView({ config: saved({ refreshMinutes: 5 }) })
      await flushPromises()

      let resolveHighlights
      api.highlights.mockImplementationOnce(() => new Promise((resolve) => { resolveHighlights = resolve }))
      vi.advanceTimersByTime(5 * 60_000)
      await flushPromises()
      expect(rows(wrapper)).toHaveLength(3)

      resolveHighlights(allChecked(flag('c1', '嚴重警示', 3)))
      await flushPromises()
      expect(rows(wrapper)).toEqual([['嚴重警示', 'c1', UP]])
    })

    it('拿掉一張列出的卡：卡片一開始退場那一筆就消失、張數少一，不重新檢查', async () => {
      api.dashboardData.mockResolvedValue(DATA)
      api.highlights.mockResolvedValue(allChecked(flag('c2', '需要關注', 2)))
      const config = saved()
      const reply = { ...config, layout: LAYOUT.filter((s) => s.chartId !== 'c2') }
      api.updateDashboard.mockResolvedValue(reply)
      const wrapper = mountView({ config })
      await flushPromises()
      expect(rows(wrapper).map(([, title]) => title)).toEqual(['c2'])

      chartCard(wrapper, 'c2').vm.$emit('remove')
      await flushPromises()
      dialogButton('移除').click()
      await flushPromises()
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
      await wrapper.setProps({ config: reply })

      expect(wrapper.get('[data-chart-id="c2"]').classes()).toContain('tc-card-leave')
      expect(rows(wrapper)).toEqual([])
      expect(panel(wrapper).text()).toContain('本期沒有需要注意的變化（檢查了 3 張卡）')

      vi.advanceTimersByTime(120)
      await flushPromises()
      expect(wrapper.find('[data-chart-id="c2"]').exists()).toBe(false)
      expect(panel(wrapper).text()).toContain('（檢查了 3 張卡）')
      expect(api.highlights).toHaveBeenCalledTimes(1)
    })

    it('先送出的舊 /highlight 晚到：不蓋掉新的本期重點', async () => {
      let resolveOld
      api.dashboardData.mockResolvedValue(DATA)
      api.highlights
        .mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve }))
        .mockResolvedValueOnce(allChecked(flag('c4', '值得注意', 1)))
      const wrapper = mountView({ config: saved() })
      await flushPromises()
      await filterBar(wrapper).vm.$emit('update:modelValue', NORTH)
      await flushPromises()
      expect(rows(wrapper)).toEqual([['值得注意', 'c4', UP]])

      resolveOld(CHECK)
      await flushPromises()
      expect(rows(wrapper)).toEqual([['值得注意', 'c4', UP]])
    })

    it('唯讀的檢視者、沒登入的訪客看得到；製圖頁的預覽沒有', async () => {
      api.dashboardData.mockResolvedValue(DATA)
      api.highlights.mockResolvedValue(CHECK)
      for (const props of [{ readonly: true }, { readonly: true, guest: true }]) {
        const wrapper = mountView({ config: saved(), ...props })
        await flushPromises()
        expect(rows(wrapper)).toHaveLength(3)
      }

      api.previewData.mockResolvedValue(DATA)
      const preview = mountView({ config: previewOf(...LAYOUT), preview: true })
      await flushPromises()
      expect(panel(preview).exists()).toBe(false)
    })
  })

  describe('篩選器', () => {
    const NORTH = { period: null, fields: [{ field: '地區', values: ['北區'] }] }
    const LAST_YEAR = { period: 'last_year', fields: [] }
    const filterBar = (wrapper) => wrapper.findComponent({ name: 'DashboardFilters' })
    const withDefault = (filters) => ({ ...makeConfig(), filters })

    it('打開時篩選是存著的預設，第一次 /data、/highlight 就帶著它', async () => {
      api.dashboardData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }])
      api.highlights.mockResolvedValue(NO_FLAGS)
      const wrapper = mountView({ config: withDefault(NORTH) })
      await flushPromises()

      expect(api.dashboardData).toHaveBeenCalledWith('d1', NORTH)
      expect(api.highlights).toHaveBeenCalledWith('d1', NORTH)
      expect(filterBar(wrapper).props('modelValue')).toEqual(NORTH)
      expect(filterBar(wrapper).props('saved')).toEqual(NORTH)
      expect(filterBar(wrapper).props('dashboardId')).toBe('d1')
    })

    it('篩選一變就帶著新的篩選重抓；新資料回來前卡片留著舊資料，異常標註馬上清掉', async () => {
      api.dashboardData.mockResolvedValueOnce([{ chartId: 'c1', rows: [['北區', 1]] }, { chartId: 'c2' }])
      api.highlights.mockResolvedValueOnce(flags({ chartId: 'c1', severity: '需要關注', noul: 0.8 }))
      const wrapper = mountView({ config: makeConfig() })
      await flushPromises()
      expect(chartCardPayload(wrapper, 'c1').highlight).toBeTruthy()

      let resolveData
      api.dashboardData.mockImplementationOnce(() => new Promise((resolve) => { resolveData = resolve }))
      api.highlights.mockResolvedValueOnce(NO_FLAGS)
      await filterBar(wrapper).vm.$emit('update:modelValue', NORTH)
      await flushPromises()

      expect(api.dashboardData).toHaveBeenLastCalledWith('d1', NORTH)
      expect(filterBar(wrapper).props('modelValue')).toEqual(NORTH)
      expect(chartCardPayload(wrapper, 'c1')).toEqual({ data: { chartId: 'c1', rows: [['北區', 1]] } })

      resolveData([{ chartId: 'c1', rows: [] }, { chartId: 'c2' }])
      await flushPromises()
      expect(chartCardPayload(wrapper, 'c1').data).toEqual({ chartId: 'c1', rows: [] })
      expect(api.highlights).toHaveBeenLastCalledWith('d1', NORTH)
      expect(api.highlights).toHaveBeenCalledTimes(2)
    })

    it('拖拉存檔帶的是存著的預設，不是臨時的篩選', async () => {
      api.dashboardData.mockResolvedValue([])
      api.highlights.mockResolvedValue(NO_FLAGS)
      api.updateDashboard.mockResolvedValue(undefined)
      const wrapper = mountView({ config: withDefault(LAST_YEAR) })
      await flushPromises()

      await filterBar(wrapper).vm.$emit('update:modelValue', NORTH)
      await flushPromises()
      await wrapper.findComponent({ name: 'GridItem' }).vm.$emit('moved')
      await flushPromises()

      expect(api.updateDashboard.mock.calls[0][1].filters).toEqual(LAST_YEAR)
    })

    it('存成預設：PUT 格線上目前的版面和目前的篩選，交給 App 換設定，不重抓資料', async () => {
      prefersReducedMotion.mockReturnValue(true)
      api.dashboardData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }])
      api.highlights.mockResolvedValue(NO_FLAGS)
      const config = makeConfig()
      const wrapper = mountView({ config })
      await flushPromises()
      expect(filterBar(wrapper).props('owner')).toBe(true)

      await filterBar(wrapper).vm.$emit('update:modelValue', NORTH)
      await flushPromises()
      await wrapper.getComponent({ name: 'GridLayout' }).vm.$emit('update:layout', [
        { i: 'c1', x: 6, y: 0, w: 6, h: 4 },
        { i: 'c2', x: 0, y: 4, w: 12, h: 5 },
      ])
      let resolvePut
      api.updateDashboard.mockImplementation(() => new Promise((resolve) => { resolvePut = resolve }))
      await filterBar(wrapper).vm.$emit('save')
      await flushPromises()

      const [id, sent] = api.updateDashboard.mock.calls[0]
      expect(id).toBe('d1')
      expect(sent.filters).toEqual(NORTH)
      expect(sent.layout[0].gridPos).toEqual({ x: 6, y: 0, w: 6, h: 4 })
      // 存的時候按鈕停用，拖拉不另外存，免得帶著舊的預設蓋回去
      expect(filterBar(wrapper).props('saving')).toBe(true)
      await wrapper.findComponent({ name: 'GridItem' }).vm.$emit('moved')
      expect(api.updateDashboard).toHaveBeenCalledTimes(1)

      const reply = { ...sent }
      resolvePut(reply)
      await flushPromises()
      expect(wrapper.emitted('updated')).toEqual([[reply]])
      expect(filterBar(wrapper).props('saving')).toBe(false)

      await wrapper.setProps({ config: reply })
      await flushPromises()
      expect(api.dashboardData).toHaveBeenCalledTimes(2)
      expect(api.highlights).toHaveBeenCalledTimes(2)
      expect(filterBar(wrapper).props('modelValue')).toEqual(NORTH)
      expect(filterBar(wrapper).props('saved')).toEqual(NORTH)
    })

    it('存成預設失敗時錯誤顯示在上面的錯誤區', async () => {
      api.dashboardData.mockResolvedValue([])
      api.highlights.mockResolvedValue(NO_FLAGS)
      api.updateDashboard.mockRejectedValue(new Error('存檔失敗'))
      const wrapper = mountView({ config: makeConfig() })
      await flushPromises()

      await filterBar(wrapper).vm.$emit('save')
      await flushPromises()

      expect(wrapper.get('.v-alert').text()).toBe('存檔失敗')
      expect(wrapper.emitted('updated')).toBeUndefined()
    })

    it('重新打開同一份儀表板（新的設定物件）時，臨時的篩選回到預設', async () => {
      api.dashboardData.mockResolvedValue([])
      api.highlights.mockResolvedValue(NO_FLAGS)
      const config = withDefault(LAST_YEAR)
      const wrapper = mountView({ config })
      await flushPromises()
      await filterBar(wrapper).vm.$emit('update:modelValue', NORTH)
      await flushPromises()

      await wrapper.setProps({ config: { ...config } })
      await flushPromises()
      expect(filterBar(wrapper).props('modelValue')).toEqual(LAST_YEAR)
      expect(api.dashboardData).toHaveBeenLastCalledWith('d1', LAST_YEAR)
    })

    it('移除卡片時新篩選的資料還沒回來：不走「只少了卡片」的捷徑，用目前的篩選重抓', async () => {
      prefersReducedMotion.mockReturnValue(true)
      api.dashboardData.mockResolvedValueOnce([{ chartId: 'c1' }, { chartId: 'c2' }])
      api.highlights.mockResolvedValue(NO_FLAGS)
      const config = makeConfig()
      const wrapper = mountView({ config })
      await flushPromises()

      api.dashboardData.mockImplementationOnce(() => new Promise(() => {})) // 新篩選的 /data 一直沒回來
      await filterBar(wrapper).vm.$emit('update:modelValue', NORTH)
      const reply = { ...config, layout: [config.layout[1]] }
      api.updateDashboard.mockResolvedValue(reply)
      chartCard(wrapper, 'c1').vm.$emit('remove')
      await flushPromises()
      dialogButton('移除').click()
      await flushPromises()

      api.dashboardData.mockResolvedValueOnce([{ chartId: 'c2', rows: [] }])
      await wrapper.setProps({ config: reply })
      await flushPromises()
      expect(api.dashboardData).toHaveBeenCalledTimes(3)
      expect(api.dashboardData).toHaveBeenLastCalledWith('d1', NORTH)
      expect(chartCardPayload(wrapper, 'c2').data).toEqual({ chartId: 'c2', rows: [] })
    })

    it('先送出的舊 /highlight 先回來，不會把「還在等異常標註」清掉：移除卡片時照樣重問', async () => {
      prefersReducedMotion.mockReturnValue(true)
      api.dashboardData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }])
      let resolveFirst
      api.highlights
        .mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve }))
        .mockImplementationOnce(() => new Promise(() => {})) // 換篩選之後的那次一直沒回來
        .mockResolvedValueOnce(flags({ chartId: 'c2', severity: '需要關注', noul: 0.8 }))
      const config = makeConfig()
      const wrapper = mountView({ config })
      await flushPromises()
      await filterBar(wrapper).vm.$emit('update:modelValue', NORTH)
      await flushPromises()
      expect(api.highlights).toHaveBeenCalledTimes(2)
      resolveFirst(NO_FLAGS)
      await flushPromises()

      const reply = { ...config, layout: [config.layout[1]] }
      api.updateDashboard.mockResolvedValue(reply)
      chartCard(wrapper, 'c1').vm.$emit('remove')
      await flushPromises()
      dialogButton('移除').click()
      await flushPromises()
      await wrapper.setProps({ config: reply })
      await flushPromises()

      expect(api.highlights).toHaveBeenCalledTimes(3)
      expect(api.highlights).toHaveBeenLastCalledWith('d1', NORTH)
      expect(chartCardPayload(wrapper, 'c2').highlight).toEqual({ chartId: 'c2', severity: '需要關注', noul: 0.8 })
    })

    it('移除卡片退場的 120ms 裡換了篩選：退場結束照樣套上新版面，再用新的篩選抓資料', async () => {
      api.dashboardData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }])
      api.highlights.mockResolvedValue(NO_FLAGS)
      const config = makeConfig()
      const wrapper = mountView({ config })
      await flushPromises()
      const reply = { ...config, layout: [config.layout[1]] }
      api.updateDashboard.mockResolvedValue(reply)
      chartCard(wrapper, 'c1').vm.$emit('remove')
      await flushPromises()
      dialogButton('移除').click()
      await flushPromises()

      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
      await wrapper.setProps({ config: reply })
      expect(wrapper.get('[data-chart-id="c1"]').classes()).toContain('tc-card-leave')
      api.dashboardData.mockResolvedValue([{ chartId: 'c2', rows: [] }])
      await filterBar(wrapper).vm.$emit('update:modelValue', NORTH)
      vi.advanceTimersByTime(120)
      await flushPromises()

      expect(wrapper.find('[data-chart-id="c1"]').exists()).toBe(false)
      expect(api.dashboardData).toHaveBeenLastCalledWith('d1', NORTH)
      expect(chartCardPayload(wrapper, 'c2').data).toEqual({ chartId: 'c2', rows: [] })
    })

    it('卡片的 filtered：有任何篩選對這張圖生效才是 true', async () => {
      api.dashboardData.mockResolvedValue([{ chartId: 'c1', filterSkipped: ['地區'] }, { chartId: 'c2' }])
      api.highlights.mockResolvedValue(NO_FLAGS)
      const wrapper = mountView({ config: withDefault(NORTH) })
      await flushPromises()
      expect(chartCard(wrapper, 'c1').props('filtered')).toBe(false)
      expect(chartCard(wrapper, 'c2').props('filtered')).toBe(true)

      const plain = mountView({ config: makeConfig() })
      await flushPromises()
      expect(chartCard(plain, 'c2').props('filtered')).toBe(false)
    })

    it('預覽、沒有卡片時沒有篩選列；檢視者有篩選列但沒有「存成預設」', async () => {
      api.previewData.mockResolvedValue([])
      api.dashboardData.mockResolvedValue([])
      api.highlights.mockResolvedValue(NO_FLAGS)
      expect(filterBar(mountView({ config: makeConfig(), preview: true })).exists()).toBe(false)
      expect(filterBar(mountView({ config: { ...makeConfig(), layout: [] } })).exists()).toBe(false)
      const viewer = mountView({ config: makeConfig(), readonly: true })
      await flushPromises()
      expect(filterBar(viewer).exists()).toBe(true)
      expect(filterBar(viewer).props('owner')).toBe(false)
    })
  })

  describe('自動更新', () => {
    const NORTH = { period: null, fields: [{ field: '地區', values: ['北區'] }] }
    const LAST_YEAR = { period: 'last_year', fields: [] }
    const MIN = 60_000
    const filterBar = (wrapper) => wrapper.findComponent({ name: 'DashboardFilters' })
    const refreshSelect = (wrapper) => wrapper.findAllComponents({ name: 'VSelect' }).find((s) => s.props('label') === '自動更新')
    const statusText = (wrapper) => wrapper.get('.dash-updated').text()
    const every = (refreshMinutes, extra = {}) => ({ ...makeConfig(), refreshMinutes, ...extra })
    const tick = async (ms) => {
      vi.advanceTimersByTime(ms)
      await flushPromises()
    }

    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
      vi.setSystemTime(new Date(2026, 9, 7, 14, 5))
    })

    it('時間到了照目前臨時改的篩選重抓 /data、再抓 /highlight；新的回來前卡片和標註都留著', async () => {
      api.dashboardData.mockResolvedValueOnce([{ chartId: 'c1', v: 'default' }, { chartId: 'c2' }])
      api.highlights.mockResolvedValueOnce(NO_FLAGS)
      const wrapper = mountView({ config: every(5, { filters: LAST_YEAR }) })
      await flushPromises()
      api.dashboardData.mockResolvedValueOnce([{ chartId: 'c1', v: 'north' }, { chartId: 'c2' }])
      api.highlights.mockResolvedValueOnce(flags({ chartId: 'c1', severity: '需要關注' }))
      await filterBar(wrapper).vm.$emit('update:modelValue', NORTH)
      await flushPromises()

      let resolveData
      let resolveHighlights
      api.dashboardData.mockImplementationOnce(() => new Promise((resolve) => { resolveData = resolve }))
      api.highlights.mockImplementationOnce(() => new Promise((resolve) => { resolveHighlights = resolve }))
      await tick(5 * MIN - 1)
      expect(api.dashboardData).toHaveBeenCalledTimes(2)
      await tick(1)
      expect(api.dashboardData).toHaveBeenCalledTimes(3)
      expect(api.dashboardData).toHaveBeenLastCalledWith('d1', NORTH)
      expect(chartCardPayload(wrapper, 'c1')).toEqual({ data: { chartId: 'c1', v: 'north' }, highlight: { chartId: 'c1', severity: '需要關注' } })

      resolveData([{ chartId: 'c1', v: 'fresh' }, { chartId: 'c2' }])
      await flushPromises()
      expect(api.highlights).toHaveBeenLastCalledWith('d1', NORTH)
      expect(chartCardPayload(wrapper, 'c1')).toEqual({ data: { chartId: 'c1', v: 'fresh' }, highlight: { chartId: 'c1', severity: '需要關注' } })

      resolveHighlights(NO_FLAGS)
      await flushPromises()
      expect(chartCardPayload(wrapper, 'c1')).toEqual({ data: { chartId: 'c1', v: 'fresh' } })
      expect(filterBar(wrapper).props('modelValue')).toEqual(NORTH)
    })

    it('沒設間隔就不自動更新；預覽也不排', async () => {
      api.dashboardData.mockResolvedValue([])
      api.highlights.mockResolvedValue(NO_FLAGS)
      api.previewData.mockResolvedValue([])
      mountView({ config: makeConfig() })
      mountView({ config: { ...previewOf(bar('c1', 0)), refreshMinutes: 1 }, preview: true })
      await flushPromises()
      await tick(16 * MIN)
      expect(api.dashboardData).toHaveBeenCalledTimes(1)
      expect(api.previewData).toHaveBeenCalledTimes(1)
    })

    it('改篩選也算抓了一次，從改的時候重新計時', async () => {
      api.dashboardData.mockResolvedValue([])
      api.highlights.mockResolvedValue(NO_FLAGS)
      const wrapper = mountView({ config: every(5) })
      await flushPromises()
      await tick(3 * MIN)
      await filterBar(wrapper).vm.$emit('update:modelValue', NORTH)
      await flushPromises()
      await tick(5 * MIN - 1)
      expect(api.dashboardData).toHaveBeenCalledTimes(2)
      await tick(1)
      expect(api.dashboardData).toHaveBeenCalledTimes(3)
    })

    it('分頁在背景時不抓；回到前景時已經超過間隔就馬上抓，沒超過就不抓', async () => {
      api.dashboardData.mockResolvedValue([])
      api.highlights.mockResolvedValue(NO_FLAGS)
      const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
      mountView({ config: every(1) })
      await flushPromises()

      document.dispatchEvent(new Event('visibilitychange'))
      await flushPromises()
      expect(api.dashboardData).toHaveBeenCalledTimes(1)

      visibility.mockReturnValue('hidden')
      await tick(3 * MIN)
      expect(api.dashboardData).toHaveBeenCalledTimes(1)

      visibility.mockReturnValue('visible')
      document.dispatchEvent(new Event('visibilitychange'))
      await flushPromises()
      expect(api.dashboardData).toHaveBeenCalledTimes(2)
      visibility.mockRestore()
    })

    it('自動更新失敗：卡片和標註留著，寫出「更新失敗」，下一個間隔再試，成功後換回「最後更新」', async () => {
      api.dashboardData.mockResolvedValueOnce([{ chartId: 'c1', v: 'old' }, { chartId: 'c2' }])
      api.highlights.mockResolvedValue(flags({ chartId: 'c1', severity: '需要關注' }))
      const wrapper = mountView({ config: every(1) })
      await flushPromises()
      expect(statusText(wrapper)).toBe('最後更新 14:05')
      expect(wrapper.get('[role="status"]').text()).toBe('')

      api.dashboardData.mockRejectedValueOnce(new Error('連不上後端'))
      await tick(MIN)
      expect(wrapper.find('.v-alert').exists()).toBe(false)
      expect(chartCardPayload(wrapper, 'c1')).toEqual({ data: { chartId: 'c1', v: 'old' }, highlight: { chartId: 'c1', severity: '需要關注' } })
      expect(statusText(wrapper)).toBe('更新失敗，顯示的是 14:05 的資料')
      expect(wrapper.get('.dash-updated').classes()).toContain('text-warning')
      expect(wrapper.get('[role="status"]').text()).toBe('更新失敗，顯示的是 14:05 的資料')

      api.dashboardData.mockResolvedValueOnce([{ chartId: 'c1', v: 'new' }, { chartId: 'c2' }])
      await tick(MIN)
      expect(chartCardPayload(wrapper, 'c1').data).toEqual({ chartId: 'c1', v: 'new' })
      expect(statusText(wrapper)).toBe('最後更新 14:07')
      expect(wrapper.get('.dash-updated').classes()).not.toContain('text-warning')
    })

    it('打開時就失敗的照舊顯示錯誤；有開自動更新的話下一個間隔再試，成功就把格線放回來', async () => {
      api.dashboardData.mockRejectedValueOnce(new Error('資料來源載入失敗'))
      api.highlights.mockResolvedValue(NO_FLAGS)
      const wrapper = mountView({ config: every(1) })
      await flushPromises()
      expect(wrapper.get('.v-alert').text()).toBe('資料來源載入失敗')
      expect(wrapper.find('.grid-layout-stub').exists()).toBe(false)

      api.dashboardData.mockRejectedValueOnce(new Error('還是失敗'))
      await tick(MIN)
      expect(wrapper.get('.v-alert').text()).toBe('還是失敗')

      api.dashboardData.mockResolvedValueOnce([{ chartId: 'c1' }, { chartId: 'c2' }])
      await tick(MIN)
      expect(wrapper.find('.v-alert').exists()).toBe(false)
      expect(wrapper.find('.grid-layout-stub').exists()).toBe(true)
    })

    it('不是今天的時間寫上日期', async () => {
      vi.setSystemTime(new Date(2026, 9, 6, 23, 59))
      api.dashboardData.mockResolvedValueOnce([])
      api.highlights.mockResolvedValue(NO_FLAGS)
      const wrapper = mountView({ config: every(1) })
      await flushPromises()
      api.dashboardData.mockRejectedValueOnce(new Error('x'))
      await tick(MIN)
      expect(statusText(wrapper)).toBe('更新失敗，顯示的是 10/6 23:59 的資料')
    })

    it('間隔超過 15 分鐘時，前景每 15 分鐘打一次 me 續住工作階段；背景不打，15 分鐘以內不打，卸載後停掉', async () => {
      api.dashboardData.mockResolvedValue([])
      api.highlights.mockResolvedValue(NO_FLAGS)
      api.me.mockResolvedValue({})
      const short = mountView({ config: every(15) })
      await flushPromises()
      await tick(45 * MIN)
      expect(api.me).not.toHaveBeenCalled()
      short.unmount()

      const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
      const wrapper = mountView({ config: every(60) })
      await flushPromises()
      api.dashboardData.mockClear()
      await tick(15 * MIN)
      expect(api.me).toHaveBeenCalledTimes(1)
      visibility.mockReturnValue('hidden')
      await tick(15 * MIN)
      expect(api.me).toHaveBeenCalledTimes(1)
      visibility.mockReturnValue('visible')
      await tick(15 * MIN)
      expect(api.me).toHaveBeenCalledTimes(2)
      // 到了 60 分鐘照常更新，從那時重新計時
      await tick(15 * MIN)
      expect(api.dashboardData).toHaveBeenCalledTimes(1)
      expect(api.me).toHaveBeenCalledTimes(2)
      await tick(15 * MIN)
      expect(api.me).toHaveBeenCalledTimes(3)
      wrapper.unmount()
      await tick(60 * MIN)
      expect(api.me).toHaveBeenCalledTimes(3)
      visibility.mockRestore()
    })

    it('卸載之後不再自動更新', async () => {
      api.dashboardData.mockResolvedValue([])
      api.highlights.mockResolvedValue(NO_FLAGS)
      const wrapper = mountView({ config: every(1) })
      await flushPromises()
      wrapper.unmount()
      await tick(5 * MIN)
      expect(api.dashboardData).toHaveBeenCalledTimes(1)
    })

    it('檢視者沒有選單，看到間隔和時間；擁有者看到選單和時間', async () => {
      api.dashboardData.mockResolvedValue([])
      api.highlights.mockResolvedValue(NO_FLAGS)
      const viewer = mountView({ config: every(5), readonly: true })
      const hourViewer = mountView({ config: every(180), readonly: true })
      const plainViewer = mountView({ config: makeConfig(), readonly: true })
      const owner = mountView({ config: every(5) })
      await flushPromises()

      expect(refreshSelect(viewer)).toBeUndefined()
      expect(statusText(viewer)).toBe('每 5 分鐘自動更新，最後更新 14:05')
      expect(statusText(hourViewer)).toBe('每 3 小時自動更新，最後更新 14:05')
      expect(statusText(plainViewer)).toBe('最後更新 14:05')
      expect(statusText(owner)).toBe('最後更新 14:05')
      expect(refreshSelect(owner).props('modelValue')).toBe(5)
      expect(refreshSelect(owner).props('items').map((i) => i.title))
        .toEqual(['不自動更新', '每 1 分鐘', '每 5 分鐘', '每 10 分鐘', '每 15 分鐘',
          '每 1 小時', '每 3 小時', '每 6 小時', '每 12 小時', '每 24 小時'])
    })

    it('擁有者改間隔：PUT 格線上的版面、存著的預設篩選和新的間隔，交給 App 換設定，不重抓；從那時重新計時', async () => {
      api.dashboardData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }])
      api.highlights.mockResolvedValue(NO_FLAGS)
      const wrapper = mountView({ config: every(null, { filters: LAST_YEAR }) })
      await flushPromises()
      expect(refreshSelect(wrapper).props('modelValue')).toBe(null)
      await filterBar(wrapper).vm.$emit('update:modelValue', NORTH)
      await flushPromises()

      let resolvePut
      api.updateDashboard.mockImplementation(() => new Promise((resolve) => { resolvePut = resolve }))
      await refreshSelect(wrapper).vm.$emit('update:modelValue', 5)
      await flushPromises()
      const [id, sent] = api.updateDashboard.mock.calls[0]
      expect(id).toBe('d1')
      expect(sent.refreshMinutes).toBe(5)
      expect(sent.filters).toEqual(LAST_YEAR)
      // 存的時候選單停用，「存成預設」和 ✕ 也停用、拖拉不另外存，免得帶著舊的間隔蓋回去
      expect(refreshSelect(wrapper).props('disabled')).toBe(true)
      expect(filterBar(wrapper).props('saving')).toBe(true)
      expect(chartCard(wrapper, 'c1').props('removeDisabled')).toBe(true)
      await wrapper.findComponent({ name: 'GridItem' }).vm.$emit('moved')
      expect(api.updateDashboard).toHaveBeenCalledTimes(1)

      resolvePut(sent)
      await flushPromises()
      expect(wrapper.emitted('updated')).toEqual([[sent]])
      await tick(2 * MIN)
      await wrapper.setProps({ config: sent })
      await flushPromises()
      expect(api.dashboardData).toHaveBeenCalledTimes(2)
      await tick(5 * MIN - 1)
      expect(api.dashboardData).toHaveBeenCalledTimes(2)
      await tick(1)
      expect(api.dashboardData).toHaveBeenCalledTimes(3)
      expect(api.dashboardData).toHaveBeenLastCalledWith('d1', NORTH)
    })

    it('改間隔失敗時錯誤顯示在上面，選單還是存著的值', async () => {
      api.dashboardData.mockResolvedValue([])
      api.highlights.mockResolvedValue(NO_FLAGS)
      api.updateDashboard.mockRejectedValue(new Error('自動更新只能選 1、5、10、15 分鐘或 1、3、6、12、24 小時'))
      const wrapper = mountView({ config: every(5) })
      await flushPromises()

      await refreshSelect(wrapper).vm.$emit('update:modelValue', 1)
      await flushPromises()
      expect(wrapper.get('.v-alert').text()).toBe('自動更新只能選 1、5、10、15 分鐘或 1、3、6、12、24 小時')
      expect(refreshSelect(wrapper).props('modelValue')).toBe(5)
      expect(refreshSelect(wrapper).props('disabled')).toBe(false)
      expect(wrapper.emitted('updated')).toBeUndefined()
    })
  })

  describe('KPI 卡', () => {
    const NORTH = { period: null, fields: [{ field: '地區', values: ['北區'] }] }
    const LAST_YEAR = { period: 'last_year', fields: [] }
    const kpi = {
      chartId: 'c3', type: 'kpi', title: 'amount', gridPos: { x: 0, y: 9, w: 4, h: 2 }, datasourceId: 'ds1',
      mapping: { x: 'order_date', value: 'amount', aggregate: 'sum', timeGrain: 'month' },
    }
    const withKpi = () => ({ ...makeConfig(), createdBy: 'u1', filters: LAST_YEAR, layout: [...makeConfig().layout, kpi] })
    const filterBar = (wrapper) => wrapper.findComponent({ name: 'DashboardFilters' })
    const form = (wrapper) => wrapper.findComponent({ name: 'KpiForm' })

    beforeEach(() => {
      prefersReducedMotion.mockReturnValue(true)
      api.dashboardData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }, { chartId: 'c3', rows: [] }])
      api.highlights.mockResolvedValue(NO_FLAGS)
      api.previewData.mockResolvedValue([])
    })

    it('「設定」只給擁有者看已存儀表板的 KPI 卡；檢視者、預覽沒有，也沒有表單', async () => {
      const owner = mountView({ config: withKpi() })
      await flushPromises()
      expect(chartCard(owner, 'c3').props('configurable')).toBe(true)
      expect(chartCard(owner, 'c1').props('configurable')).toBe(false)
      expect(form(owner).exists()).toBe(true)

      const viewer = mountView({ config: withKpi(), readonly: true })
      await flushPromises()
      expect(chartCard(viewer, 'c3').props('configurable')).toBe(false)
      expect(filterBar(viewer).props('owner')).toBe(false) // 篩選列也就沒有「加 KPI 卡」
      expect(form(viewer).exists()).toBe(false)

      const preview = mountView({ config: withKpi(), preview: true })
      await flushPromises()
      expect(chartCard(preview, 'c3').props('configurable')).toBe(false)
    })

    it('加 KPI 卡：打開空白的表單，存檔走「加到既有儀表板」一批一張；交給 App 換設定後，臨時的篩選不變，用它抓新卡的資料', async () => {
      const config = withKpi()
      const wrapper = mountView({ config })
      await flushPromises()
      await filterBar(wrapper).vm.$emit('update:modelValue', NORTH)
      await flushPromises()

      await filterBar(wrapper).vm.$emit('add-kpi')
      expect(form(wrapper).props('modelValue')).toBe(true)
      expect(form(wrapper).props('spec')).toBeNull()
      expect(form(wrapper).props('username')).toBe('u1')

      const card = { ...kpi, chartId: 'c1', title: 'qty', mapping: { ...kpi.mapping, value: 'qty' } }
      const reply = { ...config, layout: [...config.layout, { ...card, chartId: 'c4', gridPos: { x: 0, y: 11, w: 4, h: 2 } }] }
      api.appendCharts.mockResolvedValue(reply)
      await form(wrapper).props('save')(card)
      expect(api.appendCharts).toHaveBeenCalledWith('d1', { layout: [card] })
      expect(wrapper.emitted('updated')).toEqual([[reply]])

      await wrapper.setProps({ config: reply })
      await flushPromises()
      expect(api.dashboardData).toHaveBeenCalledTimes(3)
      expect(api.dashboardData).toHaveBeenLastCalledWith('d1', NORTH)
      expect(filterBar(wrapper).props('modelValue')).toEqual(NORTH)
    })

    it('設定：表單帶著那張卡；存檔 PUT 格線上目前的版面、換掉那張卡（位置照格線）、filters 是存著的預設；那張卡的資料重抓', async () => {
      const config = withKpi()
      const wrapper = mountView({ config })
      await flushPromises()
      await filterBar(wrapper).vm.$emit('update:modelValue', NORTH)
      await flushPromises()
      await wrapper.getComponent({ name: 'GridLayout' }).vm.$emit('update:layout', [
        { i: 'c1', x: 6, y: 0, w: 6, h: 4 },
        { i: 'c2', x: 0, y: 4, w: 12, h: 5 },
        { i: 'c3', x: 8, y: 0, w: 4, h: 2 },
      ])

      await chartCard(wrapper, 'c3').vm.$emit('configure')
      expect(form(wrapper).props('modelValue')).toBe(true)
      expect(form(wrapper).props('spec')).toEqual(kpi)

      const edited = { ...kpi, title: '營收', options: { threshold: { op: 'below', value: 5 } } }
      api.updateDashboard.mockImplementation((id, c) => Promise.resolve(c))
      await form(wrapper).props('save')(edited)
      const [id, sent] = api.updateDashboard.mock.calls[0]
      expect(id).toBe('d1')
      expect(sent.filters).toEqual(LAST_YEAR)
      expect(sent.layout.map((s) => s.gridPos)).toEqual([
        { x: 6, y: 0, w: 6, h: 4 }, { x: 0, y: 4, w: 12, h: 5 }, { x: 8, y: 0, w: 4, h: 2 }])
      expect(sent.layout[2]).toEqual({ ...edited, gridPos: { x: 8, y: 0, w: 4, h: 2 } })
      expect(wrapper.emitted('updated')).toEqual([[sent]])

      // 卡片數目沒變，但那張卡的設定變了：不能走「只少了卡片」的捷徑
      await wrapper.setProps({ config: sent })
      await flushPromises()
      expect(api.dashboardData).toHaveBeenCalledTimes(3)
      expect(api.dashboardData).toHaveBeenLastCalledWith('d1', NORTH)
      expect(chartCardPayload(wrapper, 'c3').data).toEqual({ chartId: 'c3', rows: [] })
    })

    it('存檔失敗時錯誤丟回表單，不交給 App', async () => {
      const wrapper = mountView({ config: withKpi() })
      await flushPromises()
      await filterBar(wrapper).vm.$emit('add-kpi')
      api.appendCharts.mockRejectedValue(new Error('找不到儀表板'))

      await expect(form(wrapper).props('save')(kpi)).rejects.toThrow('找不到儀表板')
      expect(wrapper.emitted('updated')).toBeUndefined()
    })
  })

  describe('匯出 spec 5.2：下載', () => {
    const NORTH = { period: null, fields: [{ field: '地區', values: ['北區'] }] }
    const filterBar = (wrapper) => wrapper.findComponent({ name: 'DashboardFilters' })
    let clicked
    beforeEach(() => {
      clicked = []
      vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x')
      vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
      vi.spyOn(HTMLElement.prototype, 'click').mockImplementation(function () {
        clicked.push({ href: this.getAttribute('href'), download: this.download })
      })
    })
    afterEach(() => {
      vi.restoreAllMocks()
    })

    it('預覽和沒登入的訪客不傳 download；已存的儀表板，擁有者和檢視者都有', async () => {
      api.previewData.mockResolvedValue([])
      api.dashboardData.mockResolvedValue([])
      api.highlights.mockResolvedValue(NO_FLAGS)
      const preview = mountView({ config: makeConfig(), preview: true })
      const guest = mountView({ config: makeConfig(), readonly: true, guest: true })
      const viewer = mountView({ config: makeConfig(), readonly: true })
      const owner = mountView({ config: makeConfig() })
      await flushPromises()

      expect(chartCard(preview, 'c1').props('download')).toBeNull()
      expect(chartCard(guest, 'c1').props('download')).toBeNull()
      expect(chartCard(viewer, 'c1').props('download')).toBeTypeOf('function')
      expect(chartCard(owner, 'c2').props('download')).toBeTypeOf('function')
    })

    it('帶著目前的篩選（包括臨時改的）呼叫 exportChart，用後端給的檔名存檔；沒有檔名就用卡片標題', async () => {
      api.dashboardData.mockResolvedValue([{ chartId: 'c1', title: '金額 依 地區', rows: [['北區', 1]] }, { chartId: 'c2', rows: [] }])
      api.highlights.mockResolvedValue(NO_FLAGS)
      const blob = new Blob(['x'])
      api.exportChart.mockResolvedValueOnce({ blob, filename: '營收.xlsx' }).mockResolvedValueOnce({ blob, filename: null })
      const wrapper = mountView({ config: makeConfig() })
      await flushPromises()
      await filterBar(wrapper).vm.$emit('update:modelValue', NORTH)
      await flushPromises()

      await chartCard(wrapper, 'c1').props('download')('xlsx')
      expect(api.exportChart).toHaveBeenLastCalledWith('d1', 'c1', 'xlsx', NORTH)
      expect(URL.createObjectURL).toHaveBeenCalledWith(blob)
      await chartCard(wrapper, 'c1').props('download')('csv')
      expect(clicked).toEqual([{ href: 'blob:x', download: '營收.xlsx' }, { href: 'blob:x', download: '金額 依 地區.csv' }])
      expect(wrapper.find('.v-alert').exists()).toBe(false)
    })

    it('下載失敗時，最上面的錯誤寫「下載失敗：」加上後端的訊息，不存檔', async () => {
      api.dashboardData.mockResolvedValue([])
      api.highlights.mockResolvedValue(NO_FLAGS)
      api.exportChart.mockRejectedValue(new Error('資料超過 Excel 的列數上限（1,048,575 列），請改下載 CSV'))
      const wrapper = mountView({ config: makeConfig() })
      await flushPromises()

      await chartCard(wrapper, 'c1').props('download')('xlsx')
      await flushPromises()
      expect(wrapper.get('.v-alert').text()).toBe('下載失敗：資料超過 Excel 的列數上限（1,048,575 列），請改下載 CSV')
      expect(clicked).toEqual([])
    })
  })
})

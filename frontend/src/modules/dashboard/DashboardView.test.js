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
  },
}))

vi.mock('./motion.js', () => ({ prefersReducedMotion: vi.fn(() => false) }))

vi.mock('./ChartCard.vue', () => ({
  default: {
    name: 'ChartCard',
    props: ['spec', 'data', 'highlight', 'removable', 'removeDisabled'],
    emits: ['remove'],
    template: '<div class="chart-card-stub" :data-chart-id="spec.chartId">{{ JSON.stringify({ data, highlight }) }}</div>',
  },
}))

vi.mock('grid-layout-plus', () => ({
  GridLayout: {
    name: 'GridLayout',
    props: ['layout', 'colNum', 'rowHeight', 'isDraggable', 'isResizable'],
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

function mountView(props) {
  return mount(DashboardView, { props, global: { plugins: [vuetify] } })
}

const pos = (n) => ({ x: (n % 2) * 6, y: Math.floor(n / 2) * 4, w: 6, h: 4 })
const bar = (chartId, n) => ({ chartId, type: 'bar', title: chartId, gridPos: pos(n), mapping: { x: 'region', value: 'amount' } })
const table = (chartId, y) => ({ chartId, type: 'table', title: '明細', gridPos: { x: 0, y, w: 12, h: 5 }, mapping: { columns: ['region'] } })
const previewOf = (...layout) => ({ name: 'n', datasourceId: 'ds1', layout })
const chartCard = (wrapper, chartId) =>
  wrapper.findAllComponents({ name: 'ChartCard' }).find((c) => c.props('spec').chartId === chartId)

describe('DashboardView', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
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
    api.highlights.mockResolvedValue([{ chartId: 'c1', severity: '需要關注', noul: 0.8 }])

    const wrapper = mountView({ config: makeConfig(), preview: false })
    await flushPromises()

    expect(api.dashboardData).toHaveBeenCalledWith('d1')
    expect(api.highlights).not.toHaveBeenCalled()

    resolveData([{ chartId: 'c1' }, { chartId: 'c2' }])
    await flushPromises()

    expect(api.highlights).toHaveBeenCalledWith('d1')
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
    api.highlights.mockResolvedValue([])
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
    api.highlights.mockResolvedValue([])
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
    api.highlights.mockResolvedValue([])

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
    api.highlights.mockResolvedValue([])
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
    api.highlights.mockResolvedValue([{ chartId: 'c1', severity: 'B-severity' }])

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

  it('cards that show up later get the enter class and their layout position for the stagger; nothing enters on the first display', async () => {
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
    expect(cards.map((c) => [c.props('removable'), c.props('removeDisabled')])).toEqual([[true, true], [true, true]])
    expect(cards.map((c) => c.vm.$attrs['data-chart-id'])).toEqual(['c1', 'c2']) // 「復原」後找回這張卡片的「✕」
    expect(wrapper.get('.dash-grid').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('.dash-grid').classes()).toContain('is-busy')

    await wrapper.setProps({ busy: false })
    expect(wrapper.get('.dash-grid').attributes('aria-busy')).toBeUndefined()
    expect(cards[0].props('removeDisabled')).toBe(false)

    cards[0].vm.$emit('remove')
    expect(wrapper.emitted('remove')).toEqual([['c1']])
  })

  it('saved dashboards: no ✕, and the intents show as 依據 joined by →; previews and dashboards without intents show no 依據', async () => {
    api.dashboardData.mockResolvedValue([])
    api.highlights.mockResolvedValue([])
    const saved = mountView({ config: { ...makeConfig(), intents: ['各區每月營收', '不要圓餅圖'] }, preview: false })
    await flushPromises()
    expect(saved.text()).toContain('依據：各區每月營收 → 不要圓餅圖')
    expect(saved.findAllComponents({ name: 'ChartCard' }).map((c) => c.props('removable'))).toEqual([false, false])

    const plain = mountView({ config: makeConfig(), preview: false })
    await flushPromises()
    expect(plain.text()).not.toContain('依據')

    api.previewData.mockResolvedValue([])
    const inPreview = mountView({ config: { ...makeConfig(), intents: ['各區每月營收'] }, preview: true })
    await flushPromises()
    expect(inPreview.text()).not.toContain('依據')
  })
})

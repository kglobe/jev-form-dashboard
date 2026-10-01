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
    props: ['spec', 'data', 'highlight', 'removable', 'removeDisabled', 'source', 'readonly'],
    emits: ['remove'],
    template: '<div class="chart-card-stub" :data-chart-id="spec.chartId">{{ JSON.stringify({ data, highlight }) }}</div>',
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

function mountView(props) {
  return mount(DashboardView, { props, global: { plugins: [vuetify] } })
}

const pos = (n) => ({ x: (n % 2) * 6, y: Math.floor(n / 2) * 4, w: 6, h: 4 })
const bar = (chartId, n) => ({ chartId, type: 'bar', title: chartId, gridPos: pos(n), mapping: { x: 'region', value: 'amount' } })
const table = (chartId, y) => ({ chartId, type: 'table', title: '明細', gridPos: { x: 0, y, w: 12, h: 5 }, mapping: { columns: ['region'] } })
const previewOf = (...layout) => ({ name: 'n', datasourceId: 'ds1', layout })
const chartCard = (wrapper, chartId) =>
  wrapper.findAllComponents({ name: 'ChartCard' }).find((c) => c.props('spec').chartId === chartId)
const dialogButton = (label) => [...document.body.querySelectorAll('button')].find((b) => b.textContent.trim() === label)
const withSource = (spec, datasourceId) => ({ ...spec, datasourceId })

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
    api.highlights.mockResolvedValue([])
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
    api.highlights.mockResolvedValue([])
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
    api.highlights.mockResolvedValue([])
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
    api.highlights.mockResolvedValue([])
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

  it('saved: when removing a card leaves the config from this view’s own PUT, the rest keep their data and highlight without fetching again', async () => {
    prefersReducedMotion.mockReturnValue(true)
    api.dashboardData.mockResolvedValue([{ chartId: 'c1' }, { chartId: 'c2' }, { chartId: 'c3' }])
    api.highlights.mockResolvedValue([{ chartId: 'c2', severity: '需要關注', noul: 0.8 }])
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
    api.highlights.mockResolvedValue([])
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
      .mockResolvedValueOnce([{ chartId: 'c2', severity: '需要關注', noul: 0.8 }])
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
    api.highlights.mockResolvedValue([])
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
    api.highlights.mockResolvedValue([])
    const wrapper = mountView({ config: { ...makeConfig(), layout: [] }, preview: false })
    await flushPromises()
    expect(wrapper.text()).toContain('這份儀表板沒有圖表了。到「製圖」分析後，用「加到既有儀表板」把圖加回來。')
    expect(wrapper.text()).not.toContain('找不到適合的圖表')
  })

  it('依據: one line per group; one source reads 依據：a → b, several sources name each source once /data is back (no name, no brackets)', async () => {
    api.highlights.mockResolvedValue([])
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
    api.highlights.mockResolvedValue([])
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
    api.highlights.mockResolvedValue([])
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
    api.highlights.mockResolvedValue([])
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
    api.highlights.mockResolvedValue([])
    const wrapper = mountView({ config: { ...makeConfig(), layout: [] }, readonly: true })
    await flushPromises()
    expect(wrapper.get('.v-alert').text()).toBe('這份儀表板沒有圖表。')
  })
})

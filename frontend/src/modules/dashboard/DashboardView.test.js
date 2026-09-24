// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DashboardView from './DashboardView.vue'
import { api } from './api.js'
import { vuetify } from './test-vuetify.js'

vi.mock('./api.js', () => ({
  api: {
    previewData: vi.fn(),
    dashboardData: vi.fn(),
    highlights: vi.fn(),
    updateDashboard: vi.fn(),
  },
}))

vi.mock('./ChartCard.vue', () => ({
  default: {
    name: 'ChartCard',
    props: ['spec', 'data', 'highlight'],
    template: '<div class="chart-card-stub" :data-chart-id="spec.chartId">{{ JSON.stringify({ data, highlight }) }}</div>',
  },
}))

vi.mock('grid-layout-plus', () => ({
  GridLayout: {
    name: 'GridLayout',
    props: ['layout', 'colNum', 'rowHeight', 'isDraggable', 'isResizable'],
    emits: ['update:layout'],
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

describe('DashboardView', () => {
  beforeEach(() => {
    vi.resetAllMocks()
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

  it('shows an error alert with the rejection message when dashboardData fails', async () => {
    api.dashboardData.mockRejectedValue(new Error('找不到儀表板'))

    const wrapper = mountView({ config: makeConfig(), preview: false })
    await flushPromises()

    expect(wrapper.text()).toContain('找不到儀表板')
    expect(api.highlights).not.toHaveBeenCalled()
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
})

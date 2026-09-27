// @vitest-environment happy-dom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createVuetify } from 'vuetify'
import ChartCard from './ChartCard.vue'
import { vuetify } from './test-vuetify.js'
import commandDark from './charts/themes/command-dark.json'
import commandLight from './charts/themes/command-light.json'

vi.mock('echarts', () => ({}))
vi.mock('vue-echarts', () => ({
  default: {
    name: 'VChart',
    props: ['option', 'autoresize', 'theme'],
    template: '<div class="v-chart-stub" />',
  },
}))

function mountCard(props) {
  return mount(ChartCard, { props, global: { plugins: [vuetify] } })
}

const barSpec = { chartId: 'c1', type: 'bar', title: 'T', mapping: {} }
const barData = { chartId: 'c1', type: 'bar', dimensions: ['region', 'amount'], rows: [['北區', 1200000]] }
const chartOf = (card) => card.getComponent({ name: 'VChart' })
const optionFor = (type) => chartOf(mountCard({ spec: { ...barSpec, type }, data: { ...barData, type }, highlight: null })).props('option')

describe('ChartCard', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('title comes from data.title (field labels applied by the backend) once loaded, spec.title before that', async () => {
    const card = mountCard({ spec: { ...barSpec, title: 'amount 依 region' }, data: null, highlight: null })
    expect(card.get('.tc-card-title').text()).toBe('amount 依 region')
    await card.setProps({ data: { ...barData, title: '金額 依 地區' } })
    expect(card.get('.tc-card-title').text()).toBe('金額 依 地區')
  })

  it('shows the FIELD_MISSING alert and renders no chart when a required field is missing', () => {
    const wrapper = mountCard({
      spec: { chartId: 'c1', type: 'bar', title: 'T', mapping: {} },
      data: { chartId: 'c1', type: 'bar', error: 'FIELD_MISSING', missingField: 'profit' },
      highlight: null,
    })
    expect(wrapper.text()).toContain('欄位遺失：profit')
    expect(wrapper.find('.v-chart-stub').exists()).toBe(false)
  })

  it('while data is null the body shows an aria-hidden skeleton and says aria-busy; both go once data arrives', async () => {
    const card = mountCard({ spec: { chartId: 'c1', type: 'bar', title: 'T', mapping: {} }, data: null, highlight: null })
    expect(card.get('.tc-skeleton').attributes('aria-hidden')).toBe('true')
    expect(card.get('.card-body').attributes('aria-busy')).toBe('true')

    await card.setProps({ data: { chartId: 'c1', type: 'bar', dimensions: ['region', 'amount'], rows: [['北區', 1]] } })
    expect(card.find('.tc-skeleton').exists()).toBe(false)
    expect(card.get('.card-body').attributes('aria-busy')).toBeUndefined()
  })

  it('renders table headers and rows, with a null cell rendered empty', () => {
    const wrapper = mountCard({
      spec: { chartId: 'c1', type: 'table', title: 'T', mapping: { columns: ['region'] } },
      data: { chartId: 'c1', type: 'table', dimensions: ['order_date', 'region'], rows: [['2024-01-05', '北區'], ['2024-01-18', null]] },
      highlight: null,
    })
    const headers = wrapper.findAll('th').map((h) => h.text())
    expect(headers).toEqual(['order_date', 'region'])
    const rows = wrapper.findAll('tbody tr').map((r) => r.findAll('td').map((c) => c.text()))
    expect(rows).toEqual([
      ['2024-01-05', '北區'],
      ['2024-01-18', ''],
    ])
  })

  it('shows a fallback message instead of throwing for an unsupported spec.type', () => {
    const wrapper = mountCard({
      spec: { chartId: 'c1', type: 'funnel', title: 'T', mapping: {} },
      data: { chartId: 'c1', type: 'funnel', values: [1, 2, 3] },
      highlight: null,
    })
    expect(wrapper.text()).toContain('不支援的圖表類型：funnel')
    expect(wrapper.find('.v-chart-stub').exists()).toBe(false)
  })

  it('shows the fallback message instead of matching inherited Object properties like toString or __proto__', () => {
    for (const type of ['toString', '__proto__']) {
      const wrapper = mountCard({
        spec: { chartId: 'c1', type, title: 'T', mapping: {} },
        data: { chartId: 'c1', type },
        highlight: null,
      })
      expect(wrapper.text()).toContain(`不支援的圖表類型：${type}`)
      expect(wrapper.find('.v-chart-stub').exists()).toBe(false)
    }
  })

  it('shows a truncation caption when data.truncated is true, and not otherwise', () => {
    const spec = { chartId: 'c1', type: 'table', title: 'T', mapping: { columns: ['region'] } }
    const truncated = mountCard({
      spec,
      data: { chartId: 'c1', type: 'table', dimensions: ['region'], rows: [['北區']], truncated: true },
      highlight: null,
    })
    expect(truncated.text()).toContain('僅顯示前 500 筆')

    const full = mountCard({
      spec,
      data: { chartId: 'c1', type: 'table', dimensions: ['region'], rows: [['北區']] },
      highlight: null,
    })
    expect(full.text()).not.toContain('僅顯示前 500 筆')
  })

  it('severity badge: each level maps to its tc-badge modifier (正常波動 stays plain grey), fades in, and there is none without a highlight', () => {
    const spec = { chartId: 'c1', type: 'bar', title: 'T', mapping: {} }
    const expected = { 正常波動: [], 值得注意: ['tc-badge--info'], 需要關注: ['tc-badge--warn'], 嚴重警示: ['tc-badge--err'] }
    for (const [severity, modifiers] of Object.entries(expected)) {
      const badge = mountCard({ spec, data: null, highlight: { chartId: 'c1', severity, noul: 0.9 } }).get('.tc-badge')
      expect(badge.text()).toBe(severity)
      expect(badge.classes().filter((c) => c.startsWith('tc-badge--'))).toEqual(modifiers)
      expect(badge.classes()).toContain('tc-fade-in')
    }
    expect(mountCard({ spec, data: null, highlight: null }).find('.tc-badge').exists()).toBe(false)
  })

  it('✕: shown only when removable (DashboardView decides which cards), named 移除這張圖, emits remove', async () => {
    const spec = { chartId: 'c1', type: 'bar', title: 'T', mapping: {} }
    const card = mountCard({ spec, data: null, highlight: null, removable: true })
    await card.get('button[aria-label="移除這張圖"]').trigger('click')
    expect(card.emitted('remove')).toHaveLength(1)

    expect(mountCard({ spec, data: null, highlight: null }).find('button[aria-label="移除這張圖"]').exists()).toBe(false)
    const tableCard = mountCard({ spec: { ...spec, type: 'table' }, data: null, highlight: null, removable: true })
    expect(tableCard.find('button[aria-label="移除這張圖"]').exists()).toBe(true)
  })

  it('✕ is size small with default density, so it still reaches 40×40 under the global compact density', () => {
    const spec = { chartId: 'c1', type: 'bar', title: 'T', mapping: {} }
    const card = mountCard({ spec, data: null, highlight: null, removable: true })
    const btn = card.get('button[aria-label="移除這張圖"]')
    expect(btn.classes()).toContain('v-btn--size-small')
    expect(btn.classes()).toContain('v-btn--density-default')
  })

  it('✕ is disabled while the preview waits for JEV', () => {
    const spec = { chartId: 'c1', type: 'bar', title: 'T', mapping: {} }
    const card = mountCard({ spec, data: null, highlight: null, removable: true, removeDisabled: true })
    expect(card.get('button[aria-label="移除這張圖"]').attributes('disabled')).toBeDefined()
  })

  it('shows 符合需求 when options.focus is true, and not otherwise', () => {
    const spec = { chartId: 'c1', type: 'bar', title: 'T', mapping: {} }
    expect(mountCard({ spec: { ...spec, options: { focus: true } }, data: null, highlight: null }).text()).toContain('符合需求')
    expect(mountCard({ spec, data: null, highlight: null }).text()).not.toContain('符合需求')
    expect(mountCard({ spec: { ...spec, options: {} }, data: null, highlight: null }).text()).not.toContain('符合需求')
  })

  it('符合需求 is a neutral tc-tag, not a chip', () => {
    const card = mountCard({
      spec: { chartId: 'c1', type: 'bar', title: 'T', mapping: {}, options: { focus: true } },
      data: null,
      highlight: null,
    })
    expect(card.get('.tc-tag').text()).toBe('符合需求')
    expect(card.find('.v-chip').exists()).toBe(false)
  })

  it('table: number cells get tc-num (tabular figures, right-aligned), text cells do not; the table fades in', () => {
    const card = mountCard({
      spec: { chartId: 'c1', type: 'table', title: 'T', mapping: { columns: ['region', 'amount'] } },
      data: { chartId: 'c1', type: 'table', dimensions: ['region', 'amount'], rows: [['北區', 1200]] },
      highlight: null,
    })
    const [text, num] = card.findAll('tbody td')
    expect(num.classes()).toContain('tc-num')
    expect(text.classes()).not.toContain('tc-num')
    expect(card.get('.v-table').classes()).toContain('tc-fade-in')
  })

  it('the FIELD_MISSING alert fades in', () => {
    const card = mountCard({
      spec: { chartId: 'c1', type: 'bar', title: 'T', mapping: {} },
      data: { chartId: 'c1', type: 'bar', error: 'FIELD_MISSING', missingField: 'profit' },
      highlight: null,
    })
    expect(card.get('.v-alert').classes()).toContain('tc-fade-in')
  })

  it('passes the chart theme; every series, the hover state and the tooltip get the tuned animation, and the tooltip keeps its settings', () => {
    const card = mountCard({
      spec: { chartId: 'c1', type: 'stacked_bar', title: 'T', mapping: {} },
      data: { chartId: 'c1', type: 'stacked_bar', dimensions: ['month', 'region', 'amount'], rows: [['1月', '北區', 1], ['1月', '南區', 2]] },
      highlight: null,
    })
    expect(chartOf(card).props('theme')).toBe(commandLight) // test-vuetify 預設是淺色
    const option = chartOf(card).props('option')
    expect(option.series).toHaveLength(2)
    for (const s of option.series) {
      expect(s).toMatchObject({ animationDuration: 300, animationEasing: 'cubicOut', animationDurationUpdate: 250, animationEasingUpdate: 'cubicOut' })
    }
    expect(option.stateAnimation).toEqual({ duration: 120, easing: 'cubicOut' })
    expect(option.tooltip).toEqual({ trigger: 'axis', transitionDuration: 0 })
    expect(option.animation).toBeUndefined()
    expect(option.aria).toBeUndefined()
  })

  it('the chart theme follows the Vuetify theme: dark gets the dark theme, light the light one', async () => {
    const themed = createVuetify({ theme: { defaultTheme: 'dark' } })
    const card = mount(ChartCard, { props: { spec: barSpec, data: barData, highlight: null }, global: { plugins: [themed] } })
    expect(chartOf(card).props('theme')).toBe(commandDark)

    themed.theme.change('light')
    await card.vm.$nextTick()
    expect(chartOf(card).props('theme')).toBe(commandLight)
  })

  it('no chart type draws decal patterns; series are told apart by the 8-colour theme', () => {
    expect(optionFor('bar').aria).toBeUndefined()
    expect(optionFor('pie').aria).toBeUndefined()
    expect(optionFor('line').aria).toBeUndefined()
  })

  it('value axes label numbers in compact zh-TW form; category axes are left as they were', () => {
    const option = optionFor('bar')
    expect(option.yAxis.type).toBe('value')
    expect(option.yAxis.axisLabel.formatter(1200000)).toBe('120萬')
    expect(option.xAxis).toEqual({ type: 'category' })
  })

  it('reduced motion: the chart does not animate and the hover state is instant', () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {} }))
    const option = optionFor('bar')
    expect(option.animation).toBe(false)
    expect(option.stateAnimation.duration).toBe(0)
  })

  it('source: the source name sits after the title in small muted text, truncated with the full name on hover; none without it', () => {
    const card = mountCard({ spec: barSpec, data: null, highlight: null, source: 'R7 Excel 退貨' })
    const tag = card.get('.tc-card-title .tc-source')
    expect(tag.text()).toBe('R7 Excel 退貨')
    expect(tag.attributes('title')).toBe('R7 Excel 退貨')
    expect(tag.classes()).toContain('text-truncate')
    expect(mountCard({ spec: barSpec, data: null, highlight: null }).find('.tc-source').exists()).toBe(false)
  })

  it('SOURCE_FAILED: a warning names the source when it is known, and no chart is drawn', () => {
    const failed = (source) => mountCard({
      spec: barSpec,
      data: { chartId: 'c1', type: 'bar', error: 'SOURCE_FAILED', ...(source && { source }) },
      highlight: null,
    })
    const named = failed('銷售 API')
    expect(named.text()).toContain('資料來源「銷售 API」載入失敗，請稍後重新整理')
    expect(named.find('.v-chart-stub').exists()).toBe(false)
    expect(failed().text()).toContain('資料來源載入失敗，請稍後重新整理')
  })
})

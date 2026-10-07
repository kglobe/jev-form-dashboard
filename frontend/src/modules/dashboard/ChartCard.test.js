// @vitest-environment happy-dom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createVuetify } from 'vuetify'
import ChartCard from './ChartCard.vue'
import { vuetify } from './test-vuetify.js'
import commandDark from './charts/themes/command-dark.json'
import commandLight from './charts/themes/command-light.json'

vi.mock('./charts/echarts.js', () => ({}))
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

  it('shows the NOT_A_DATE alert and renders no chart when the time grain sits on a field that is not a date', () => {
    const wrapper = mountCard({
      spec: { chartId: 'c1', type: 'line', title: 'T', mapping: {} },
      data: { chartId: 'c1', type: 'line', error: 'NOT_A_DATE', missingField: 'region' },
      highlight: null,
    })
    expect(wrapper.text()).toContain('欄位「region」不是日期，不能按日、月或季分組')
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

  // 系列色對卡片底（command-center.css 的 --tc-panel）都要 ≥3:1（WCAG 1.4.11），不必靠標籤或明細表才分得出來
  it.each([
    ['light', commandLight, '#ffffff'],
    ['dark', commandDark, '#17181b'],
  ])('every %s series colour has at least 3:1 contrast against the card surface', (_, theme, surface) => {
    const luminance = (hex) => {
      const [r, g, b] = hex.slice(1).match(/../g).map((x) => {
        const c = parseInt(x, 16) / 255
        return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
      })
      return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    const contrast = (a, b) => {
      const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
      return (hi + 0.05) / (lo + 0.05)
    }
    const low = theme.color.filter((c) => contrast(c, surface) < 3)
    expect(low).toEqual([])
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

  it('two Y axes (two measures far apart) both label numbers in compact zh-TW form', () => {
    const data = { chartId: 'c1', type: 'line', dimensions: ['date', '銷售', '退貨'], rows: [['2026-07', 1200000, 30000]] }
    const option = chartOf(mountCard({ spec: { ...barSpec, type: 'line' }, data, highlight: null })).props('option')
    expect(option.yAxis).toHaveLength(2)
    expect(option.yAxis.map((a) => a.axisLabel.formatter(1200000))).toEqual(['120萬', '120萬'])
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

  it('SOURCE_DELETED: a warning tells the user to remove the card, the ✕ stays, no chart is drawn', () => {
    const card = mountCard({
      spec: barSpec,
      data: { chartId: 'c1', type: 'bar', error: 'SOURCE_DELETED' },
      highlight: null,
      removable: true,
    })
    expect(card.text()).toContain('資料來源已刪除，可以按右上角 ✕ 移除這張圖')
    expect(card.find('button[aria-label="移除這張圖"]').exists()).toBe(true)
    expect(card.find('.v-chart-stub').exists()).toBe(false)
  })

  it('a percent Y axis keeps its percent labels instead of the compact number form', () => {
    const data = { chartId: 'c1', type: 'line', dimensions: ['date', '退貨率'], rows: [['2026-07', 0.052]], percent: true }
    const option = chartOf(mountCard({ spec: { ...barSpec, type: 'line' }, data, highlight: null })).props('option')
    expect(option.yAxis.axisLabel.formatter(0.052)).toBe('5.2%')
    expect(option.tooltip.valueFormatter(0.052)).toBe('5.2%')
  })

  it('a first row with no number is still data, not a header: the ratio chart keeps its first, empty period', () => {
    const data = {
      chartId: 'c1', type: 'line', dimensions: ['date', '退貨率'], rows: [['2026-01', null], ['2026-07', 0.035]], percent: true,
    }
    const option = chartOf(mountCard({ spec: { ...barSpec, type: 'line' }, data, highlight: null })).props('option')
    expect(option.dataset).toEqual({ dimensions: ['date', '退貨率'], source: [['2026-01', null], ['2026-07', 0.035]], sourceHeader: false })
  })

  it('ratioPeriod: a caption under the chart says which months the ratio counts; none without it', () => {
    const spec = { ...barSpec, title: '退貨率 依 product' }
    const data = { ...barData, dimensions: ['product', '退貨率'], rows: [['A', 0.04]], percent: true }
    const caption = (ratioPeriod) => mountCard({ spec, data: { ...data, ratioPeriod }, highlight: null }).find('.tc-ratio-period')

    expect(caption({ from: '2026-07', to: '2026-09' }).text()).toBe('只算 2026-07～2026-09（分子、分母都有資料的月份）')
    expect(caption({ from: '2026-07', to: '2026-07' }).text()).toBe('只算 2026-07（分子、分母都有資料的月份）')
    expect(caption({ from: '2026-08', to: '2026-01' }).text()).toBe('分子、分母的資料期間沒有重疊')
    expect(caption(undefined).exists()).toBe(false)
  })

  it('資料來源共享 spec 2.3：SOURCE_UNSHARED 說「資料來源已停止分享」，擁有者看時再提 ✕', () => {
    const data = { chartId: 'c1', type: 'bar', error: 'SOURCE_UNSHARED' }
    const owner = mountCard({ spec: barSpec, data, highlight: null, removable: true })
    expect(owner.get('.v-alert').text()).toBe('資料來源已停止分享，可以按右上角 ✕ 移除這張圖')
    expect(owner.find('.v-chart-stub').exists()).toBe(false)
    expect(mountCard({ spec: barSpec, data, highlight: null, readonly: true }).get('.v-alert').text()).toBe('資料來源已停止分享')
  })

  it('readonly 時 SOURCE_DELETED 只說「資料來源已刪除」，不提 ✕', () => {
    const card = mountCard({ spec: barSpec, data: { chartId: 'c1', type: 'bar', error: 'SOURCE_DELETED' }, highlight: null, readonly: true })
    expect(card.get('.v-alert').text()).toBe('資料來源已刪除')
  })

  it('readonly 時 SOURCE_FAILED 改成請聯絡擁有者', () => {
    const failed = (source) => mountCard({
      spec: barSpec,
      data: { chartId: 'c1', type: 'bar', error: 'SOURCE_FAILED', ...(source && { source }) },
      highlight: null,
      readonly: true,
    }).get('.v-alert').text()
    expect(failed('銷售')).toBe('資料來源「銷售」載入失敗，請聯絡儀表板擁有者')
    expect(failed()).toBe('資料來源載入失敗，請聯絡儀表板擁有者')
  })

  it('readonly 時標題列沒有 card-drag（沒有移動游標）', () => {
    expect(mountCard({ spec: barSpec, data: null, highlight: null, readonly: true }).get('.tc-card-title').classes()).not.toContain('card-drag')
    expect(mountCard({ spec: barSpec, data: null, highlight: null }).get('.tc-card-title').classes()).toContain('card-drag')
  })
})

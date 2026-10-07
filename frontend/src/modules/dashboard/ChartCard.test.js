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

  it('篩選器 spec 6.4：filterSkipped 有東西時，卡片最下面寫出哪些篩選沒套用；沒有就不寫', () => {
    const note = (filterSkipped) => mountCard({ spec: barSpec, data: { ...barData, filterSkipped }, highlight: null }).find('.tc-filter-note')
    expect(note(['地區', '期間']).text()).toBe('沒套用篩選：地區、期間（這張圖的來源沒有這些欄位）')
    expect(note(undefined).exists()).toBe(false)
  })

  it('篩選器 spec 6.4：一列資料都沒有時不畫空的圖；有生效的篩選寫「篩選後沒有資料」，不然寫「沒有資料」，明細表也一樣', () => {
    const empty = (type, filtered) => mountCard({ spec: { ...barSpec, type }, data: { ...barData, type, rows: [] }, highlight: null, filtered })
    for (const type of ['bar', 'table']) {
      const filteredCard = empty(type, true)
      expect(filteredCard.get('.tc-empty').text()).toBe('篩選後沒有資料')
      expect(filteredCard.find('.v-chart-stub').exists()).toBe(false)
      expect(filteredCard.find('table').exists()).toBe(false)
      expect(empty(type, false).get('.tc-empty').text()).toBe('沒有資料')
    }
    expect(mountCard({ spec: barSpec, data: barData, highlight: null, filtered: true }).find('.tc-empty').exists()).toBe(false)
  })

  const kpiSpec = { chartId: 'c1', type: 'kpi', title: 'amount', mapping: { x: 'date', value: 'amount', aggregate: 'sum', timeGrain: 'month' } }
  const kpiData = (rows, extra) => ({ chartId: 'c1', type: 'kpi', dimensions: ['日期', '金額'], rows, ...extra })
  const kpiCard = (props) => mountCard({ spec: kpiSpec, highlight: null, ...props })

  it('KPI spec 6.1：KPI 卡不畫 ECharts，大數字不換行、title 是完整數字，下面一行是比較的文字', () => {
    const card = kpiCard({ data: kpiData([['2026-08', 114000000], ['2026-09', 120000000]], { title: '金額' }) })
    expect(card.find('.v-chart-stub').exists()).toBe(false)
    expect(card.text()).not.toContain('不支援的圖表類型')
    expect(card.get('.tc-card-title').text()).toBe('金額')
    const value = card.get('.tc-kpi__value')
    expect(value.text()).toBe('1.2億')
    expect(value.attributes('title')).toBe('120,000,000')
    expect(value.classes()).toContain('text-truncate')
    expect(value.classes()).not.toContain('tc-kpi__value--alert')
    expect(card.get('.tc-kpi__note').text()).toBe('2026-09 · 比 2026-08 ▲ 5.3%')
    expect(card.find('.tc-kpi__alert').exists()).toBe(false)
  })

  it('KPI spec 2.2：碰到門檻時大數字換成警示色，多一行「低於門檻 …」；比率的門檻寫百分比', () => {
    const below = kpiCard({ spec: { ...kpiSpec, options: { threshold: { op: 'below', value: 1000000 } } }, data: kpiData([['2026-09', 800000]]) })
    expect(below.get('.tc-kpi__value').classes()).toContain('tc-kpi__value--alert')
    expect(below.get('.tc-kpi__alert').text()).toBe('低於門檻 100萬')

    const ratio = kpiCard({ spec: { ...kpiSpec, options: { threshold: { op: 'above', value: 0.05 } } }, data: kpiData([['2026-08', 0.04], ['2026-09', 0.052]], { percent: true }) })
    expect(ratio.get('.tc-kpi__value').text()).toBe('5.2%')
    expect(ratio.get('.tc-kpi__note').text()).toBe('2026-09 · 比 2026-08 ▲ 1.2 個百分點')
    expect(ratio.get('.tc-kpi__alert').text()).toBe('高於門檻 5%')
  })

  it('KPI 卡一期都還沒結束時寫「還沒有已經結束的期間」；篩選後一列都沒有時照篩選器 spec 6.4 寫「篩選後沒有資料」', () => {
    const none = kpiCard({ data: kpiData([]) })
    expect(none.get('.tc-kpi__value').text()).toBe('—')
    expect(none.get('.tc-kpi__note').text()).toBe('還沒有已經結束的期間')
    expect(none.find('.tc-empty').exists()).toBe(false)
    expect(kpiCard({ data: kpiData([]), filtered: true }).get('.tc-empty').text()).toBe('篩選後沒有資料')
  })

  it('KPI spec 6.1：「設定」只在 configurable 時有，在「✕」左邊，名稱是「KPI 卡設定」，按了 emit configure', async () => {
    const card = kpiCard({ data: null, configurable: true, removable: true })
    const buttons = card.findAll('.tc-card-title button').map((b) => b.attributes('aria-label'))
    expect(buttons).toEqual(['KPI 卡設定', '移除這張圖'])
    expect(card.get('button[aria-label="KPI 卡設定"] .mdi-cog-outline').exists()).toBe(true)
    await card.get('button[aria-label="KPI 卡設定"]').trigger('click')
    expect(card.emitted('configure')).toHaveLength(1)

    expect(kpiCard({ data: null, removable: true }).find('button[aria-label="KPI 卡設定"]').exists()).toBe(false)
  })
})

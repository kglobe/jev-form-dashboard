// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ChartCard from './ChartCard.vue'
import { vuetify } from './test-vuetify.js'

vi.mock('echarts', () => ({}))
vi.mock('vue-echarts', () => ({
  default: {
    name: 'VChart',
    props: ['option', 'autoresize'],
    template: '<div class="v-chart-stub" />',
  },
}))

function mountCard(props) {
  return mount(ChartCard, { props, global: { plugins: [vuetify] } })
}

describe('ChartCard', () => {
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

  it('✕: shown on removable chart cards only (not on the table, not when not removable), named 移除這張圖, emits remove', async () => {
    const spec = { chartId: 'c1', type: 'bar', title: 'T', mapping: {} }
    const card = mountCard({ spec, data: null, highlight: null, removable: true })
    await card.get('button[aria-label="移除這張圖"]').trigger('click')
    expect(card.emitted('remove')).toHaveLength(1)

    expect(mountCard({ spec, data: null, highlight: null }).find('button[aria-label="移除這張圖"]').exists()).toBe(false)
    const tableCard = mountCard({ spec: { ...spec, type: 'table' }, data: null, highlight: null, removable: true })
    expect(tableCard.find('button[aria-label="移除這張圖"]').exists()).toBe(false)
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
})

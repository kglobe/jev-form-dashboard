import { describe, it, expect } from 'vitest'
import { toOption } from './index.js'

const byMonth = { chartId: 'c1', type: 'line', dimensions: ['order_date', 'amount'], rows: [['2024-01', 2000], ['2024-02', 2100]] }
const byRegion = { chartId: 'c2', type: 'bar', dimensions: ['region', 'amount'], rows: [['北區', 7400], ['南區', 2400]] }
const points = { chartId: 'c3', type: 'scatter', dimensions: ['amount', 'qty'], rows: [[1200, 3], [800, 2]] }

describe('toOption', () => {
  it('bar', () => {
    const o = toOption.bar(byRegion, { x: 'region', value: 'amount' })
    expect(o.dataset.source).toEqual(byRegion.rows)
    expect(o.series[0].type).toBe('bar')
    expect(o).toMatchSnapshot()
  })

  it('line', () => {
    const o = toOption.line(byMonth, { x: 'order_date', value: 'amount', timeGrain: 'month' })
    expect(o.series[0].type).toBe('line')
    expect(o).toMatchSnapshot()
  })

  it('pie', () => {
    const o = toOption.pie(byRegion, { x: 'region', value: 'amount' })
    expect(o.series[0].encode).toEqual({ itemName: 0, value: 1 })
    expect(o).toMatchSnapshot()
  })

  it('scatter', () => {
    const o = toOption.scatter(points)
    expect(o.xAxis).toEqual({ type: 'value', name: 'amount' })
    expect(o.yAxis).toEqual({ type: 'value', name: 'qty' })
    expect(o).toMatchSnapshot()
  })

  it('table returns headers and rows for v-table', () => {
    const data = { chartId: 'c4', type: 'table', dimensions: ['order_date', 'region'], rows: [['2024-01-05', '北區'], ['2024-01-18', null]] }
    expect(toOption.table(data)).toEqual({ headers: ['order_date', 'region'], rows: data.rows })
  })

  it('radar gives each measure its own axis max', () => {
    const data = { chartId: 'c5', type: 'radar', dimensions: ['region', 'amount', 'qty'], rows: [['北區', 100, 4], ['南區', 50, 6]] }
    const o = toOption.radar(data, { series: 'region', measures: ['amount', 'qty'] })
    expect(o.radar.indicator).toEqual([{ name: 'amount', max: 110.00000000000001 }, { name: 'qty', max: 6.6000000000000005 }])
    expect(o.series[0].data).toEqual([{ name: '北區', value: [100, 4] }, { name: '南區', value: [50, 6] }])
    expect(o).toMatchSnapshot()
  })

  it('radar axis max is 1 when every value is 0', () => {
    const data = { dimensions: ['region', 'amount'], rows: [['北區', 0]] }
    expect(toOption.radar(data, {}).radar.indicator[0].max).toBe(1)
  })

  it('heatmap turns long rows into index triples', () => {
    const data = { chartId: 'c6', type: 'heatmap', dimensions: ['region', 'product', 'amount'], rows: [['北', 'A', 100], ['南', 'A', 50], ['北', 'B', 200]] }
    const o = toOption.heatmap(data, { x: 'region', y: 'product', value: 'amount' })
    expect(o.xAxis.data).toEqual(['北', '南'])
    expect(o.yAxis.data).toEqual(['A', 'B'])
    expect(o.series[0].data).toEqual([[0, 0, 100], [1, 0, 50], [0, 1, 200]])
    expect(o.visualMap.min).toBe(0)
    expect(o.visualMap.max).toBe(200)
    expect(o).toMatchSnapshot()
  })

  it('stacked_bar pivots long rows into one series per group and fills gaps with 0', () => {
    const data = { chartId: 'c7', type: 'stacked_bar', dimensions: ['order_date', 'region', 'amount'], rows: [['2024-01', '北', 10], ['2024-01', '南', 20], ['2024-02', '北', 5]] }
    const o = toOption.stacked_bar(data, { x: 'order_date', series: 'region', value: 'amount' })
    expect(o.xAxis.data).toEqual(['2024-01', '2024-02'])
    expect(o.series.map((s) => [s.name, s.stack, s.data])).toEqual([['北', 'total', [10, 5]], ['南', 'total', [20, 0]]])
    expect(o).toMatchSnapshot()
  })
})

describe('toOption with two measures (B2 spec 6.2)', () => {
  const compare = (rows, type = 'line') => ({ chartId: 'c8', type, dimensions: ['date', '銷售·amount', '退貨·amount'], rows })

  it('line and bar draw one series per measure with a legend; bars sit side by side on one shared axis', () => {
    for (const type of ['line', 'bar']) {
      const o = toOption[type](compare([['2026-07', 1000, 200], ['2026-08', 1200, 300]], type))
      expect(o.series.map((s) => [s.type, s.name, s.encode, s.stack, s.yAxisIndex])).toEqual([
        [type, '銷售·amount', { x: 0, y: 1 }, undefined, undefined],
        [type, '退貨·amount', { x: 0, y: 2 }, undefined, undefined],
      ])
      expect(o.legend).toEqual({ type: 'scroll', bottom: 0 })
      expect(o.yAxis).toEqual({ type: 'value' })
    }
  })

  it('more than 10× apart: the second measure moves to a right-hand Y axis and both axes are named', () => {
    const o = toOption.line(compare([['2026-07', 1100, 100]]))
    expect(o.yAxis).toEqual([{ type: 'value', name: '銷售·amount' }, { type: 'value', name: '退貨·amount' }])
    expect(o.series.map((s) => s.yAxisIndex)).toEqual([undefined, 1])
  })

  it('exactly 10× apart, one side all zero, or one side empty keeps one shared axis', () => {
    expect(toOption.bar(compare([['北區', 1000, 100]], 'bar')).yAxis).toEqual({ type: 'value' })
    expect(toOption.bar(compare([['北區', 1000, 0]], 'bar')).yAxis).toEqual({ type: 'value' })
    expect(toOption.line(compare([['2026-07', 1000, null]])).yAxis).toEqual({ type: 'value' })
  })

  it('negative values count by their size, and a missing side stays null so the line breaks there', () => {
    const rows = [['2026-01', -1100, null], ['2026-07', 900, 50]]
    const o = toOption.line(compare(rows))
    expect(o.yAxis).toHaveLength(2)
    expect(o.dataset.source).toEqual(rows)
  })
})

describe('toOption with a ratio (B3 spec 6.3)', () => {
  const ratio = (type) => ({
    chartId: 'c9', type, dimensions: ['date', '退貨率'], rows: [['2026-06', null], ['2026-07', 0.052]], percent: true,
  })

  it('line and bar show the Y axis and the tooltip in percent with at most one decimal, and keep null groups empty', () => {
    for (const type of ['line', 'bar']) {
      const o = toOption[type](ratio(type))
      expect(o.yAxis.axisLabel.formatter(0.052)).toBe('5.2%')
      expect(o.yAxis.axisLabel.formatter(0.05)).toBe('5%')
      expect(o.tooltip.valueFormatter(0.052)).toBe('5.2%')
      expect(o.tooltip.valueFormatter(null)).toBe('-')
      expect(o.dataset.source).toEqual([['2026-06', null], ['2026-07', 0.052]])
    }
  })

  it('without percent the Y axis and the tooltip stay as they were', () => {
    for (const [type, data] of [['line', byMonth], ['bar', byRegion]]) {
      const o = toOption[type](data)
      expect(o.yAxis).toEqual({ type: 'value' })
      expect(o.tooltip).toEqual({ trigger: 'axis' })
    }
  })
})

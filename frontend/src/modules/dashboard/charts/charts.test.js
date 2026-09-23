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
    const o = toOption.scatter(points, { x: 'amount', y: 'qty' })
    expect(o.xAxis).toEqual({ type: 'value', name: 'amount' })
    expect(o.yAxis).toEqual({ type: 'value', name: 'qty' })
    expect(o).toMatchSnapshot()
  })
})

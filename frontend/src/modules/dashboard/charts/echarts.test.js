import { describe, it, expect, vi, afterEach } from 'vitest'
import { init, use } from 'echarts/core'
import { SVGRenderer } from 'echarts/renderers'
import './echarts.js'
import { toOption } from './index.js'

// echarts.js 只註冊用得到的圖表和元件；漏註冊時 ECharts 只在 console 報錯，圖變成空白。
// 這裡用 SSR 模式在 Node 把每種圖真的畫一次，有東西沒註冊，ECharts 的開發警告就會讓測試失敗。
use([SVGRenderer])

const samples = {
  bar: { dimensions: ['region', 'amount'], rows: [['北區', 7400], ['南區', 2400]] },
  line: { dimensions: ['date', 'amount'], rows: [['2024-01', 2000], ['2024-02', 2100]] },
  pie: { dimensions: ['region', 'amount'], rows: [['北區', 7400], ['南區', 2400]] },
  scatter: { dimensions: ['amount', 'qty'], rows: [[1200, 3], [800, 2]] },
  radar: { dimensions: ['region', 'amount', 'qty'], rows: [['北區', 100, 4], ['南區', 50, 6]] },
  heatmap: { dimensions: ['region', 'product', 'amount'], rows: [['北', 'A', 100], ['南', 'A', 50], ['北', 'B', 200]] },
  stacked_bar: { dimensions: ['date', 'region', 'amount'], rows: [['2024-01', '北', 10], ['2024-01', '南', 20]] },
}
// 兩個數值差 10 倍以上走雙 Y 軸；比率是百分比軸
const variants = [
  ...Object.entries(samples).map(([type, data]) => [type, { chartId: 'c1', type, ...data }]),
  ['line', { chartId: 'c1', type: 'line', dimensions: ['date', '銷售', '退貨'], rows: [['2026-07', 10000, 20], ['2026-08', 12000, 30]] }],
  ['bar', { chartId: 'c1', type: 'bar', dimensions: ['date', '退貨率'], rows: [['2026-07', 0.035], ['2026-08', null]], percent: true }],
]

describe('echarts 按需引入', () => {
  afterEach(() => vi.restoreAllMocks())

  it.each(variants)('%s 用到的圖表和元件都有註冊', (type, data) => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {})
    const warns = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const chart = init(null, null, { renderer: 'svg', ssr: true, width: 400, height: 300 })

    chart.setOption(toOption[type](data))
    const svg = chart.renderToSVGString()
    chart.dispose()

    expect(errors.mock.calls.concat(warns.mock.calls)).toEqual([])
    expect(svg).toContain('<path')
  })
})

import { twoMeasures } from './measures.js'
import { formatPercent } from './percent.js'

export function toOption(data) {
  if (data.dimensions.length === 3) return twoMeasures(data, 'line')
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    // B3 spec 6.3：數值是比率時，Y 軸和 tooltip 用百分比；null 照原樣留著，折線在那裡斷開
    tooltip: { trigger: 'axis', ...(data.percent && { valueFormatter: formatPercent }) },
    xAxis: { type: 'category' },
    yAxis: { type: 'value', ...(data.percent && { axisLabel: { formatter: formatPercent } }) },
    series: [{ type: 'line', encode: { x: 0, y: 1 } }],
  }
}

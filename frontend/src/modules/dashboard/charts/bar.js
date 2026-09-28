import { twoMeasures } from './measures.js'

export function toOption(data) {
  if (data.dimensions.length === 3) return twoMeasures(data, 'bar')
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category' },
    yAxis: { type: 'value' },
    series: [{ type: 'bar', encode: { x: 0, y: 1 } }],
  }
}

import { twoMeasures } from './measures.js'

export function toOption(data) {
  if (data.dimensions.length === 3) return twoMeasures(data, 'line')
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category' },
    yAxis: { type: 'value' },
    series: [{ type: 'line', encode: { x: 0, y: 1 } }],
  }
}

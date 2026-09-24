export function toOption(data) {
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category' },
    yAxis: { type: 'value' },
    series: [{ type: 'line', encode: { x: 0, y: 1 } }],
  }
}

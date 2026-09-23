export function toOption(data, mapping) {
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    tooltip: { trigger: 'item' },
    xAxis: { type: 'value', name: mapping.x },
    yAxis: { type: 'value', name: mapping.y },
    series: [{ type: 'scatter', encode: { x: 0, y: 1 } }],
  }
}

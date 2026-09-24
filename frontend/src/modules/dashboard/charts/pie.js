export function toOption(data) {
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    tooltip: { trigger: 'item' },
    legend: { type: 'scroll', bottom: 0 },
    series: [{ type: 'pie', radius: '60%', encode: { itemName: 0, value: 1 } }],
  }
}

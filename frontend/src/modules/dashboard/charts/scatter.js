export function toOption(data) {
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    tooltip: { trigger: 'item' },
    // 軸名用 dimensions，後端已經換成欄位的顯示名稱
    xAxis: { type: 'value', name: data.dimensions[0] },
    yAxis: { type: 'value', name: data.dimensions[1] },
    series: [{ type: 'scatter', encode: { x: 0, y: 1 } }],
  }
}

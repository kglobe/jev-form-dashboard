export function toOption(data) {
  const xs = [...new Set(data.rows.map((r) => r[0]))]
  const ys = [...new Set(data.rows.map((r) => r[1]))]
  const values = data.rows.map((r) => r[2])
  return {
    tooltip: { position: 'top' },
    grid: { bottom: 60 },
    xAxis: { type: 'category', data: xs },
    yAxis: { type: 'category', data: ys },
    visualMap: {
      min: Math.min(0, ...values),
      max: Math.max(0, ...values),
      calculable: true,
      orient: 'horizontal',
      left: 'center',
      bottom: 0,
    },
    series: [{ type: 'heatmap', data: data.rows.map((r) => [xs.indexOf(r[0]), ys.indexOf(r[1]), r[2]]) }],
  }
}

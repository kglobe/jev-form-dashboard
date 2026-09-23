// ponytail: 每格用 find 找值，O(列數 × 格數)；後端 series 最多 10 個，夠用
export function toOption(data) {
  const xs = [...new Set(data.rows.map((r) => r[0]))]
  const names = [...new Set(data.rows.map((r) => r[1]))]
  return {
    tooltip: { trigger: 'axis' },
    legend: { type: 'scroll', bottom: 0 },
    xAxis: { type: 'category', data: xs },
    yAxis: { type: 'value' },
    series: names.map((name) => ({
      name: String(name),
      type: 'bar',
      stack: 'total',
      data: xs.map((x) => data.rows.find((r) => r[0] === x && r[1] === name)?.[2] ?? 0),
    })),
  }
}

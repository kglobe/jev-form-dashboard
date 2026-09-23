export function toOption(data) {
  const measures = data.dimensions.slice(1)
  const max = measures.map((_, i) => Math.max(...data.rows.map((r) => r[i + 1] ?? 0)) * 1.1 || 1)
  return {
    tooltip: {},
    legend: { type: 'scroll', bottom: 0 },
    radar: { indicator: measures.map((name, i) => ({ name, max: max[i] })) },
    series: [{ type: 'radar', data: data.rows.map((r) => ({ name: String(r[0]), value: r.slice(1) })) }],
  }
}

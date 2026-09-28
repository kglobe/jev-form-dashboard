// B2 spec 6.2：兩個數值的長條圖、折線圖。兩邊最大絕對值都大於 0、而且差超過 10 倍時，
// 第二個數值改用右邊的 Y 軸，兩條軸都標數值名稱。null 照原樣留著：折線斷開、長條不畫
const DUAL_AXIS_RATIO = 10

export function twoMeasures(data, type) {
  const [, a, b] = data.dimensions
  const peak = (i) => Math.max(0, ...data.rows.map((r) => Math.abs(r[i] ?? 0)))
  const pa = peak(1)
  const pb = peak(2)
  const dual = pa > 0 && pb > 0 && Math.max(pa, pb) / Math.min(pa, pb) > DUAL_AXIS_RATIO
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    tooltip: { trigger: 'axis' },
    legend: { type: 'scroll', bottom: 0 },
    xAxis: { type: 'category' },
    yAxis: dual ? [{ type: 'value', name: a }, { type: 'value', name: b }] : { type: 'value' },
    series: [
      { type, name: a, encode: { x: 0, y: 1 } },
      { type, name: b, encode: { x: 0, y: 2 }, ...(dual && { yAxisIndex: 1 }) },
    ],
  }
}

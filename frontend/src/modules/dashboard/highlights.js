import { delta } from './kpi.js'

// spec 3.5：正常波動用中性灰、不加修飾子；好消息不染綠
export const SEVERITY_CLASS = { 正常波動: '', 值得注意: 'tc-badge--info', 需要關注: 'tc-badge--warn', 嚴重警示: 'tc-badge--err' }

export function digest(check, cards) {
  if (!check) return null
  const onScreen = new Map(cards.map((card, n) => [card.spec.chartId, { ...card, n }]))
  const checkedCount = check.checked.filter((id) => onScreen.has(id)).length
  if (!checkedCount) return null
  const entries = check.highlights
    .filter((h) => {
      const data = onScreen.get(h.chartId)?.data
      return h.level >= 1 && data && !data.error
    })
    .sort((a, b) => b.level - a.level || b.noul - a.noul || onScreen.get(a.chartId).n - onScreen.get(b.chartId).n)
    .slice(0, 3)
    .map(({ chartId, severity, change: c }) => {
      const { spec, data } = onScreen.get(chartId)
      const name = c.measure != null ? data.dimensions[c.measure + 1] : c.series
      const text = `${c.period} 比 ${c.previousPeriod}${name == null ? '' : `，${name}`} ${delta(c.current, c.previous, c.percent)}`
      return { chartId, severity, title: data.title ?? spec.title, text }
    })
  return { checkedCount, entries }
}

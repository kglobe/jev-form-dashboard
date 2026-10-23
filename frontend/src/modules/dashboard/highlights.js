import { delta } from './kpi.js'

// spec 3.5：正常波動用中性灰、不加修飾子；好消息不染綠。卡片的徽章和本期重點共用
export const SEVERITY_CLASS = { 正常波動: '', 值得注意: 'tc-badge--info', 需要關注: 'tc-badge--warn', 嚴重警示: 'tc-badge--err' }

/**
 * 本期重點 spec 2.1～2.3。check 是 /highlight 的回應，沒有檢查結果（還沒回來、改了篩選、檢查失敗）時是 null；
 * cards 是畫面上的卡片 [{ spec, data }]，照畫面順序。回傳 null 時整塊不顯示，否則回傳
 * { checked: 送去檢查、還在畫面上的張數, entries: 值得注意以上的前 3 張 [{ chartId, severity, title, text }] }。
 */
export function digest(check, cards) {
  if (!check) return null
  const at = new Map(cards.map((card, n) => [card.spec.chartId, { ...card, n }]))
  const checked = check.checked.filter((id) => at.has(id)).length
  if (!checked) return null
  const entries = check.highlights
    .filter((h) => {
      // 拿掉的卡片不在 at 裡；這次資料載入失敗的卡片，畫面上沒有數字可以對
      const data = at.get(h.chartId)?.data
      return h.level >= 1 && data && !data.error
    })
    .sort((a, b) => b.level - a.level || b.noul - a.noul || at.get(a.chartId).n - at.get(b.chartId).n)
    .slice(0, 3)
    .map(({ chartId, severity, change: c }) => {
      const { spec, data } = at.get(chartId)
      // 兩個數值的圖用 /data 的顯示名稱，跟圖例一樣
      const name = c.measure != null ? data.dimensions[c.measure + 1] : c.series
      const text = `${c.period} 比 ${c.previousPeriod}${name == null ? '' : `，${name}`} ${delta(c.current, c.previous, c.percent)}`
      return { chartId, severity, title: data.title ?? spec.title, text }
    })
  return { checked, entries }
}

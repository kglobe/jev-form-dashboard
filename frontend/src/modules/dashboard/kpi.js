import { formatPercent } from './charts/percent.js'

// KPI spec 2.1：一般數字的大數字用縮寫（1.2億），title 和「增加 1,200」用完整數字
const compact = new Intl.NumberFormat('zh-TW', { notation: 'compact' })
const full = new Intl.NumberFormat('zh-TW')
const oneDecimal = new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 1 })

/** 跟上期比的文字。比率寫百分點；上期是 0 時不算百分比，寫增加或減少多少（KPI spec 2.1） */
function change(value, [period, before], percent) {
  const diff = value - before
  if (diff === 0) return `比 ${period} 持平`
  const arrow = diff > 0 ? '▲' : '▼'
  if (percent) return `比 ${period} ${arrow} ${oneDecimal.format(Math.abs(diff) * 100)} 個百分點`
  if (before === 0) return `比 ${period} ${diff > 0 ? '增加' : '減少'} ${full.format(Math.abs(diff))}`
  return `比 ${period} ${arrow} ${oneDecimal.format(Math.abs(diff / before) * 100)}%`
}

/**
 * KPI spec 2.1、2.2：KPI 卡上要顯示的字。data 是 /data 回傳的 KPI 卡，rows 是已經結束的期，照時間排，每列 [期, 值]；
 * threshold 是卡片的 options.threshold，比率的門檻跟值一樣存成小數。變化不分好壞，只有碰到門檻才有 alert。
 */
export function kpiView(data, threshold) {
  if (!data.rows.length) return { value: '—', full: null, note: '還沒有已經結束的期間', alert: null }
  const percent = !!data.percent
  const short = (v) => (percent ? formatPercent(v) : compact.format(v))
  const [period, value] = data.rows.at(-1)
  if (value == null) return { value: '—', full: null, note: period, alert: null }
  const previous = data.rows.at(-2)
  const hit = threshold && (threshold.op === 'below' ? value < threshold.value : value > threshold.value)
  return {
    value: short(value),
    full: percent ? formatPercent(value) : full.format(value),
    note: `${period} · ${previous && previous[1] != null ? change(value, previous, percent) : '沒有前一期可以比'}`,
    alert: hit ? `${threshold.op === 'below' ? '低於' : '高於'}門檻 ${short(threshold.value)}` : null,
  }
}

import { formatPercent } from './charts/percent.js'

// KPI spec 2.1：一般數字的大數字用縮寫（1.2億），title 和「增加 1,200」用完整數字
const compact = new Intl.NumberFormat('zh-TW', { notation: 'compact' })
const full = new Intl.NumberFormat('zh-TW')
const oneDecimal = new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 1 })

/**
 * 比率寫百分點；上期是 0 時不算百分比，寫增加或減少多少（KPI spec 2.1）。
 * 兩期相減常有 1e-17 這種浮點誤差，用格式化後的數字判斷：是「0」就寫持平
 */
export function delta(value, before, percent) {
  const diff = value - before
  const shown = percent
    ? oneDecimal.format(Math.abs(diff) * 100)
    : before === 0 ? full.format(Math.abs(diff)) : oneDecimal.format(Math.abs(diff / before) * 100)
  if (shown === '0') return '持平'
  const arrow = diff > 0 ? '▲' : '▼'
  if (percent) return `${arrow} ${shown} 個百分點`
  if (before === 0) return `${diff > 0 ? '增加' : '減少'} ${shown}`
  return `${arrow} ${shown}%`
}

const change = (value, [period, before], percent) => `比 ${period} ${delta(value, before, percent)}`

/**
 * KPI spec 2.1、2.2：KPI 卡上要顯示的字。data 是 /data 回傳的 KPI 卡，rows 是已經結束的期，照時間排，每列 [期, 值]；
 * threshold 是卡片的 options.threshold，比率的門檻跟值一樣存成小數。變化不分好壞，只有碰到門檻才有 alert。
 * filtered：有篩選對這張卡生效；rows 是空的時候用來分兩種說明。
 */
export function kpiView(data, threshold, filtered) {
  if (!data.rows.length) {
    return { value: '—', full: null, note: filtered ? '篩選範圍內沒有已經結束的期間' : '還沒有已經結束的期間', alert: null }
  }
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

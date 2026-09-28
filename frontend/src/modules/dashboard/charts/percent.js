// B3 spec 6.3：比率一律顯示成百分比，最多一位小數：0.052 是「5.2%」，0.05 是「5%」
const percent = new Intl.NumberFormat('zh-TW', { style: 'percent', maximumFractionDigits: 1 })

// 缺分子或分母的組是 null，tooltip 顯示「-」
export const formatPercent = (v) => (typeof v === 'number' ? percent.format(v) : '-')

// 篩選器 spec 3：篩選是 { period, fields: [{ field, values }] }，沒有篩選是 null；篩選列和儀表板共用
export const PERIODS = [
  { title: '不限', value: null },
  { title: '本月', value: 'this_month' },
  { title: '上月', value: 'last_month' },
  { title: '本季', value: 'this_quarter' },
  { title: '上季', value: 'last_quarter' },
  { title: '今年', value: 'this_year' },
  { title: '去年', value: 'last_year' },
]

/** 有幾個篩選：期間算一個，每個分類算一個 */
export const filterCount = (f) => (f?.period ? 1 : 0) + (f?.fields?.length ?? 0)

/** 比較兩組篩選用：null、沒有期間、沒有分類都是「不篩選」 */
export const filterKey = (f) => JSON.stringify({ period: f?.period ?? null, fields: f?.fields ?? [] })

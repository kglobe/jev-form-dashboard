import { describe, it, expect } from 'vitest'
import { digest } from './highlights.js'

const SEVERITY = ['正常波動', '值得注意', '需要關注', '嚴重警示']
const card = (chartId) => ({ spec: { chartId, title: chartId }, data: { chartId } })
const flag = (chartId, level, noul = 0.8, change = {}) => ({
  chartId,
  severity: SEVERITY[level],
  level,
  noul,
  change: { previousPeriod: '2026-08', period: '2026-09', measure: null, series: null, previous: 100, current: 120, percent: false, ...change },
})
const ids = (d) => d.entries.map((e) => e.chartId)

describe('digest', () => {
  it('沒有檢查結果、沒有卡片送去檢查、送去檢查的卡都不在畫面上：整塊不顯示', () => {
    expect(digest(null, [card('c1')])).toBeNull()
    expect(digest({ checked: [], highlights: [] }, [card('c1')])).toBeNull()
    expect(digest({ checked: ['c9'], highlights: [flag('c9', 2)] }, [card('c1')])).toBeNull()
  })

  it('照等級、noul 由大到小排，正常波動不列，最多 3 筆', () => {
    const cards = ['c1', 'c2', 'c3', 'c4', 'c5'].map(card)
    const d = digest({
      checked: ['c1', 'c2', 'c3', 'c4', 'c5'],
      highlights: [flag('c1', 1, 0.9), flag('c2', 0, 0.99), flag('c3', 2, 0.75), flag('c4', 1, 0.95), flag('c5', 3, 0.71)],
    }, cards)
    expect(d.checkedCount).toBe(5)
    expect(ids(d)).toEqual(['c5', 'c3', 'c4'])
  })

  it('等級和 noul 都一樣時照畫面順序，不照回應裡的順序', () => {
    const check = { checked: ['c1', 'c2'], highlights: [flag('c2', 1), flag('c1', 1)] }
    expect(ids(digest(check, [card('c1'), card('c2')]))).toEqual(['c1', 'c2'])
    expect(ids(digest(check, [card('c2'), card('c1')]))).toEqual(['c2', 'c1'])
  })

  it('前三名裡有一張被拿掉：第四名補上來，張數少一', () => {
    const check = { checked: ['c1', 'c2', 'c3', 'c4'], highlights: [flag('c1', 3), flag('c2', 2), flag('c3', 2, 0.75), flag('c4', 1)] }
    const cards = ['c1', 'c2', 'c3', 'c4'].map(card)
    const all = digest(check, cards)
    expect(all.checkedCount).toBe(4)
    expect(ids(all)).toEqual(['c1', 'c2', 'c3'])

    const left = digest(check, cards.filter((c) => c.spec.chartId !== 'c2'))
    expect(left.checkedCount).toBe(3)
    expect(ids(left)).toEqual(['c1', 'c3', 'c4'])
  })

  it('標題用 /data 的 title，沒有就用卡片設定的；兩個數值的圖用顯示名稱；系列照原樣；只有一條系列不寫名稱', () => {
    const cards = [
      { spec: { chartId: 'c1', title: '設定的標題' }, data: { chartId: 'c1', title: '營收 依 區域' } },
      { spec: { chartId: 'c2', title: '退貨率' }, data: { chartId: 'c2', title: null } },
      { spec: { chartId: 'c3', title: '銷售對照' }, data: { chartId: 'c3', dimensions: ['月份', '銷售·金額', '退貨·金額'] } },
    ]
    const d = digest({
      checked: ['c1', 'c2', 'c3'],
      highlights: [
        flag('c1', 3, 0.8, { series: '南區', previous: 1250000, current: 1025000 }),
        flag('c2', 2),
        flag('c3', 1, 0.8, { measure: 1, previous: 100, current: 50 }),
      ],
    }, cards)
    expect(d.entries).toEqual([
      { chartId: 'c1', severity: '嚴重警示', title: '營收 依 區域', text: '2026-09 比 2026-08，南區 ▼ 18%' },
      { chartId: 'c2', severity: '需要關注', title: '退貨率', text: '2026-09 比 2026-08 ▲ 20%' },
      { chartId: 'c3', severity: '值得注意', title: '銷售對照', text: '2026-09 比 2026-08，退貨·金額 ▼ 50%' },
    ])
  })

  it('變化的寫法跟 KPI 卡一樣：百分比、比率用原始值算百分點、前一期是 0、持平、季', () => {
    const text = (change) => digest({ checked: ['c1'], highlights: [flag('c1', 1, 0.8, change)] }, [card('c1')]).entries[0].text
    expect(text({ previous: 1250, current: 1000 })).toBe('2026-09 比 2026-08 ▼ 20%')
    expect(text({ previous: 0.0524, current: 0.0606, percent: true })).toBe('2026-09 比 2026-08 ▲ 0.8 個百分點')
    expect(text({ previous: 0, current: 1200 })).toBe('2026-09 比 2026-08 增加 1,200')
    expect(text({ previous: 10000, current: 10004 })).toBe('2026-09 比 2026-08 持平')
    expect(text({ previousPeriod: '2026-Q2', period: '2026-Q3', previous: 100, current: 112 })).toBe('2026-Q3 比 2026-Q2 ▲ 12%')
  })

  it('卡片這次的資料載入失敗：不列，也不丟例外', () => {
    const cards = [{ spec: { chartId: 'c1', title: 'A' }, data: { chartId: 'c1', error: 'SOURCE_FAILED' } }, card('c2')]
    expect(ids(digest({ checked: ['c1', 'c2'], highlights: [flag('c1', 3), flag('c2', 1)] }, cards))).toEqual(['c2'])
  })

  it('檢查了 N 張、沒有符合的：張數是 N，沒有任何一筆', () => {
    expect(digest({ checked: ['c1', 'c2'], highlights: [flag('c1', 0)] }, [card('c1'), card('c2')])).toEqual({ checkedCount: 2, entries: [] })
  })
})

import { describe, it, expect } from 'vitest'
import { kpiView } from './kpi.js'

const data = (rows, percent) => ({ chartId: 'c1', type: 'kpi', dimensions: ['date', 'amount'], rows, ...(percent && { percent: true }) })

describe('kpiView', () => {
  it('正常：大數字是本期的縮寫，title 是完整數字，下面寫本期和比上期漲跌幾 %', () => {
    expect(kpiView(data([['2026-07', 90], ['2026-08', 114000000], ['2026-09', 120000000]]))).toEqual({
      value: '1.2億',
      full: '120,000,000',
      note: '2026-09 · 比 2026-08 ▲ 5.3%',
      alert: null,
    })
    expect(kpiView(data([['2026-08', 1250], ['2026-09', 1000]])).note).toBe('2026-09 · 比 2026-08 ▼ 20%')
    expect(kpiView(data([['2026-08', 1000], ['2026-09', 1000]])).note).toBe('2026-09 · 比 2026-08 持平')
  })

  it('上期是 0 時不算百分比，寫增加或減少多少', () => {
    expect(kpiView(data([['2026-08', 0], ['2026-09', 1200]])).note).toBe('2026-09 · 比 2026-08 增加 1,200')
    expect(kpiView(data([['2026-08', 0], ['2026-09', -30]])).note).toBe('2026-09 · 比 2026-08 減少 30')
  })

  it('只有一期已經結束：寫「沒有前一期可以比」', () => {
    expect(kpiView(data([['2026-09', 5]]))).toEqual({ value: '5', full: '5', note: '2026-09 · 沒有前一期可以比', alert: null })
  })

  it('一期都還沒結束：大數字是「—」，寫「還沒有已經結束的期間」', () => {
    expect(kpiView(data([]))).toEqual({ value: '—', full: null, note: '還沒有已經結束的期間', alert: null })
  })

  it('本期是空值（比率的分母是 0）：大數字是「—」，不比；上期是空值也不比', () => {
    expect(kpiView(data([['2026-08', 0.05], ['2026-09', null]], true))).toEqual({ value: '—', full: null, note: '2026-09', alert: null })
    expect(kpiView(data([['2026-08', null], ['2026-09', 0.05]], true)).note).toBe('2026-09 · 沒有前一期可以比')
  })

  it('比率：大數字是百分比到小數一位，變化寫百分點', () => {
    const v = kpiView(data([['2026-08', 0.051], ['2026-09', 0.043]], true))
    expect(v.value).toBe('4.3%')
    expect(v.note).toBe('2026-09 · 比 2026-08 ▼ 0.8 個百分點')
    expect(kpiView(data([['2026-08', 0], ['2026-09', 0.012]], true)).note).toBe('2026-09 · 比 2026-08 ▲ 1.2 個百分點')
  })

  it('門檻：低於、高於；碰到才有警示，比的是本期的值；沒設就沒有', () => {
    const rows = [['2026-08', 900000], ['2026-09', 800000]]
    expect(kpiView(data(rows), { op: 'below', value: 1000000 }).alert).toBe('低於門檻 100萬')
    expect(kpiView(data(rows), { op: 'below', value: 800000 }).alert).toBeNull()
    expect(kpiView(data(rows), { op: 'above', value: 500000 }).alert).toBe('高於門檻 50萬')
    expect(kpiView(data(rows), { op: 'above', value: 850000 }).alert).toBeNull()
    expect(kpiView(data(rows)).alert).toBeNull()
    expect(kpiView(data([['2026-09', null]], true), { op: 'below', value: 0.05 }).alert).toBeNull()
  })

  it('門檻：比率的門檻存成小數，顯示成百分比', () => {
    expect(kpiView(data([['2026-09', 0.052]], true), { op: 'above', value: 0.05 }).alert).toBe('高於門檻 5%')
    expect(kpiView(data([['2026-09', 0.048]], true), { op: 'above', value: 0.05 }).alert).toBeNull()
  })
})

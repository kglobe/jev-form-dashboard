// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import KpiForm from './KpiForm.vue'
import { api } from './api.js'
import { vuetify } from './test-vuetify.js'

vi.mock('./api.js', () => ({ api: { listDatasources: vi.fn() } }))

const SOURCES = [
  {
    id: 'ds1', name: '銷售', createdBy: 'u1',
    schema: [
      { name: 'region', type: 'string' },
      { name: 'order_date', type: 'date', label: '訂單日' },
      { name: 'ship_date', type: 'date' },
      { name: 'amount', type: 'number', label: '金額' },
      { name: 'qty', type: 'number' },
    ],
    ratios: [{ name: '退貨率', numerator: 'refund', denominator: 'amount' }],
  },
  { id: 'ds2', name: '名冊', createdBy: 'u2', schema: [{ name: 'score', type: 'number' }], ratios: [] },
]

function mountForm(props = {}) {
  return mount(KpiForm, { props: { modelValue: true, username: 'u1', save: vi.fn(), ...props }, global: { plugins: [vuetify] } })
}

// 對話框 teleport 到 body，元件樹裡找得到
const select = (wrapper, label) => wrapper.findAllComponents({ name: 'VSelect' }).find((s) => s.props('label') === label)
const textField = (wrapper, label) => wrapper.findAllComponents({ name: 'VTextField' }).find((t) => t.props('label') === label)
const btn = (wrapper, label) => wrapper.findAllComponents({ name: 'VBtn' }).find((b) => b.text() === label)
const pick = (wrapper, label, value) => select(wrapper, label).vm.$emit('update:modelValue', value)

describe('KpiForm', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.listDatasources.mockResolvedValue(SOURCES)
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('加 KPI 卡：來源是自己能用的來源；選了來源才列數值（數字欄位和比率，畫面上的名稱）和日期欄位（預設第一個）', async () => {
    const wrapper = mountForm()
    await flushPromises()

    expect(document.body.textContent).toContain('加 KPI 卡')
    expect(select(wrapper, '來源').props('items')).toEqual([{ title: '銷售', value: 'ds1' }, { title: '名冊（來自 u2）', value: 'ds2' }])
    expect(select(wrapper, '數值')).toBeUndefined()
    expect(btn(wrapper, '加入').props('disabled')).toBe(true)

    await pick(wrapper, '來源', 'ds1')
    expect(select(wrapper, '數值').props('items')).toEqual([
      { title: '金額', value: 'amount' }, { title: 'qty', value: 'qty' }, { title: '退貨率', value: '退貨率' }])
    expect(select(wrapper, '日期欄位').props('items')).toEqual([
      { title: '訂單日', value: 'order_date' }, { title: 'ship_date', value: 'ship_date' }])
    expect(select(wrapper, '日期欄位').props('modelValue')).toBe('order_date')
    expect(select(wrapper, '加總方式').props('items').map((i) => i.title)).toEqual(['加總', '平均', '筆數'])
    expect(select(wrapper, '期別').props('items').map((i) => i.title)).toEqual(['月', '季'])
    expect(select(wrapper, '門檻').props('items').map((i) => i.title)).toEqual(['不設', '低於', '高於'])
  })

  it('送出的卡片設定：標題空白時用數值的名稱，門檻照填的數字；成功後關掉', async () => {
    const save = vi.fn().mockResolvedValue()
    const wrapper = mountForm({ save })
    await flushPromises()
    await pick(wrapper, '來源', 'ds1')
    await pick(wrapper, '數值', 'amount')
    await pick(wrapper, '加總方式', 'avg')
    await pick(wrapper, '期別', 'quarter')
    await pick(wrapper, '門檻', 'below')
    expect(btn(wrapper, '加入').props('disabled')).toBe(true) // 選了條件還沒填數字
    await textField(wrapper, '門檻值').vm.$emit('update:modelValue', '1000000')

    await btn(wrapper, '加入').trigger('click')
    await flushPromises()

    expect(save).toHaveBeenCalledWith({
      chartId: 'c1',
      type: 'kpi',
      title: 'amount',
      gridPos: { x: 0, y: 0, w: 4, h: 2 },
      mapping: { x: 'order_date', value: 'amount', aggregate: 'avg', timeGrain: 'quarter' },
      options: { threshold: { op: 'below', value: 1000000 } },
      datasourceId: 'ds1',
    })
    expect(wrapper.emitted('update:modelValue').at(-1)).toEqual([false])
  })

  it('選比率時不問加總方式、送 sum；門檻輸入百分比，存成小數；換來源時數值清掉', async () => {
    const save = vi.fn().mockResolvedValue()
    const wrapper = mountForm({ save })
    await flushPromises()
    await pick(wrapper, '來源', 'ds1')
    await pick(wrapper, '加總方式', 'count')
    await pick(wrapper, '數值', '退貨率')
    expect(select(wrapper, '加總方式')).toBeUndefined()
    await pick(wrapper, '門檻', 'above')
    expect(textField(wrapper, '門檻值').props('suffix')).toBe('%')
    await textField(wrapper, '門檻值').vm.$emit('update:modelValue', '5')
    await textField(wrapper, '標題').vm.$emit('update:modelValue', '  退貨率（月）  ')

    await btn(wrapper, '加入').trigger('click')
    await flushPromises()
    expect(save.mock.calls[0][0]).toMatchObject({
      title: '退貨率（月）',
      mapping: { value: '退貨率', aggregate: 'sum' },
      options: { threshold: { op: 'above', value: 0.05 } },
    })

    await pick(wrapper, '數值', 'amount')
    expect(textField(wrapper, '門檻值').props('suffix')).toBeUndefined()
    await pick(wrapper, '來源', 'ds2')
    expect(select(wrapper, '數值').props('modelValue')).toBeNull()
  })

  it('來源沒有日期欄位時寫「這個來源沒有日期欄位，不能做 KPI 卡」，「加入」停用', async () => {
    const wrapper = mountForm()
    await flushPromises()
    await pick(wrapper, '來源', 'ds2')
    await pick(wrapper, '數值', 'score')

    expect(document.body.textContent).toContain('這個來源沒有日期欄位，不能做 KPI 卡')
    expect(select(wrapper, '日期欄位')).toBeUndefined()
    expect(btn(wrapper, '加入').props('disabled')).toBe(true)
  })

  it('KPI 卡設定：帶入現在的設定，不能換來源；比率的門檻顯示成百分比；送出時留著 chartId、位置和其他 options', async () => {
    const save = vi.fn().mockResolvedValue()
    const spec = {
      chartId: 'c7', type: 'kpi', title: '退貨率', datasourceId: 'ds1', gridPos: { x: 4, y: 0, w: 4, h: 2 },
      mapping: { x: 'ship_date', value: '退貨率', aggregate: 'sum', timeGrain: 'quarter' },
      options: { focus: true, threshold: { op: 'above', value: 0.07 } },
    }
    const wrapper = mountForm({ spec, save })
    await flushPromises()

    expect(document.body.textContent).toContain('KPI 卡設定')
    expect(select(wrapper, '來源').props('disabled')).toBe(true)
    expect(select(wrapper, '數值').props('modelValue')).toBe('退貨率')
    expect(select(wrapper, '日期欄位').props('modelValue')).toBe('ship_date')
    expect(select(wrapper, '期別').props('modelValue')).toBe('quarter')
    expect(textField(wrapper, '標題').props('modelValue')).toBe('') // 標題就是數值的名稱：空白
    expect(select(wrapper, '門檻').props('modelValue')).toBe('above')
    expect(textField(wrapper, '門檻值').props('modelValue')).toBe('7')

    await pick(wrapper, '門檻', null)
    await btn(wrapper, '儲存').trigger('click')
    await flushPromises()
    expect(save).toHaveBeenCalledWith({
      chartId: 'c7', type: 'kpi', title: '退貨率', datasourceId: 'ds1', gridPos: { x: 4, y: 0, w: 4, h: 2 },
      mapping: { x: 'ship_date', value: '退貨率', aggregate: 'sum', timeGrain: 'quarter' },
      options: { focus: true },
    })
  })

  it('存檔失敗時錯誤留在對話框裡，不關掉', async () => {
    const save = vi.fn().mockRejectedValue(new Error('圖表 c1：KPI 門檻要是數字'))
    const spec = {
      chartId: 'c1', type: 'kpi', title: '我的營收', datasourceId: 'ds1', gridPos: { x: 0, y: 0, w: 4, h: 2 },
      mapping: { x: 'order_date', value: 'amount', aggregate: 'sum', timeGrain: 'month' },
    }
    const wrapper = mountForm({ spec, save })
    await flushPromises()
    expect(textField(wrapper, '標題').props('modelValue')).toBe('我的營收')

    await btn(wrapper, '儲存').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('圖表 c1：KPI 門檻要是數字')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})

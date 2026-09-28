// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import MergeForm from './MergeForm.vue'
import { api } from './api.js'
import { vuetify } from './test-vuetify.js'

vi.mock('./api.js', () => ({
  api: {
    previewMerged: vi.fn(),
    createMerged: vi.fn(),
  },
}))

const north = { id: 'n', name: '北區', kind: 'upload', schema: [{ name: 'region', type: 'string' }, { name: 'amount', type: 'number' }] }
const south = { id: 's', name: '南區', kind: 'upload', schema: [{ name: 'area', type: 'string' }, { name: 'amount', type: 'string' }] }
const returns = {
  id: 'r',
  name: '退貨',
  kind: 'upload',
  schema: [{ name: 'product', type: 'string' }, { name: 'qty', type: 'number' }, { name: 'owner', type: 'string' }],
}
const master = {
  id: 'm',
  name: '產品主檔',
  kind: 'upload',
  schema: [{ name: 'code', type: 'string' }, { name: 'category', type: 'string' }, { name: 'owner', type: 'string' }],
}

function mountForm() {
  return mount(MergeForm, { props: { sources: [north, south, returns, master] }, global: { plugins: [vuetify] } })
}

// 有 label 的找 label，表格裡的下拉和輸入框找 aria-label
const named = (c, label) => c.props('label') === label || c.vm.$attrs['aria-label'] === label
const select = (w, label) => w.findAllComponents({ name: 'VSelect' }).find((c) => named(c, label))
const field = (w, label) => w.findAllComponents({ name: 'VTextField' }).find((c) => named(c, label))
const btn = (w, label) => w.findAll('button').find((b) => b.text() === label || b.attributes('aria-label') === label)
const columnNames = (w) =>
  w.findAllComponents({ name: 'VTextField' }).filter((c) => /^第 \d+ 欄的輸出名稱$/.test(c.vm.$attrs['aria-label'] ?? ''))

async function pick(w, label, value) {
  await select(w, label).vm.$emit('update:modelValue', value)
  await flushPromises()
}

describe('MergeForm（疊加）', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('picking 北區 and 南區 lists every field name in source order, matches same names, marks type clashes, and names it 北區＋南區', async () => {
    const w = mountForm()
    await pick(w, '來源', ['n', 's'])

    expect(columnNames(w).map((c) => c.props('modelValue'))).toEqual(['region', 'amount', 'area'])
    expect(select(w, '第 1 欄在南區的欄位').props('modelValue')).toBeNull()
    expect(select(w, '第 2 欄在南區的欄位').props('modelValue')).toBe('amount')
    expect(select(w, '第 3 欄在北區的欄位').props('modelValue')).toBeNull()
    expect(columnNames(w)[1].props('errorMessages')).toBe('型別不一致：北區是數字、南區是文字')
    expect(columnNames(w)[0].props('errorMessages')).toEqual([])
    expect(field(w, '名稱').props('modelValue')).toBe('北區＋南區')
    expect(field(w, '來源欄名稱').props('modelValue')).toBe('來源')
  })

  it('預覽 sends the definition; 建立 stays off until a preview of the current definition succeeds; editing the definition drops the preview', async () => {
    api.previewMerged.mockResolvedValue({
      schema: [{ name: 'region' }, { name: '來源' }],
      rows: [{ region: '北區', 來源: '北區' }],
      rowCount: 30,
      warnings: ['「region」只有北區有，其他來源的列這欄是空的'],
    })
    api.createMerged.mockResolvedValue({ id: 'x1' })
    const w = mountForm()
    await pick(w, '來源', ['n', 's'])
    // region 對到南區的 area；刪掉多出來的 area 列，型別不同的 amount 也刪掉
    await pick(w, '第 1 欄在南區的欄位', 'area')
    await btn(w, '刪除第 3 欄').trigger('click')
    await btn(w, '刪除第 2 欄').trigger('click')
    expect(btn(w, '建立').attributes('disabled')).toBeDefined()

    await btn(w, '預覽').trigger('click')
    await flushPromises()
    expect(api.previewMerged).toHaveBeenCalledWith({
      name: '北區＋南區',
      merge: { op: 'union', inputs: ['n', 's'], columns: [{ name: 'region', from: { n: 'region', s: 'area' } }], sourceColumn: '來源' },
    })
    expect(w.text()).toContain('預覽 · 共 30 筆')
    expect(w.text()).toContain('「region」只有北區有')
    expect(w.findAll('.mf-preview tbody tr')).toHaveLength(1)
    expect(btn(w, '建立').attributes('disabled')).toBeUndefined()

    await field(w, '來源欄名稱').vm.$emit('update:modelValue', '區')
    await flushPromises()
    expect(w.text()).not.toContain('共 30 筆')
    expect(btn(w, '建立').attributes('disabled')).toBeDefined()

    await btn(w, '預覽').trigger('click')
    await flushPromises()
    await btn(w, '建立').trigger('click')
    await flushPromises()
    expect(api.createMerged).toHaveBeenCalledWith({ name: '北區＋南區', merge: expect.objectContaining({ sourceColumn: '區' }) })
    expect(w.emitted('created')).toEqual([[{ id: 'x1' }]])
  })

  it('a failed preview shows the error and keeps 建立 off', async () => {
    api.previewMerged.mockRejectedValue(new Error('「region」在北區是文字、在南區是數字，不能併成同一欄'))
    const w = mountForm()
    expect(btn(w, '預覽').attributes('disabled')).toBeDefined()
    await pick(w, '來源', ['n', 's'])
    await btn(w, '預覽').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('不能併成同一欄')
    expect(btn(w, '建立').attributes('disabled')).toBeDefined()
  })

  it('a stale preview rejection after the definition changed is ignored', async () => {
    let reject
    api.previewMerged.mockReturnValue(new Promise((_, rej) => { reject = rej }))
    const w = mountForm()
    await pick(w, '來源', ['n', 's'])
    await btn(w, '預覽').trigger('click')
    await field(w, '來源欄名稱').vm.$emit('update:modelValue', '區') // 等回應的時候定義又改了
    reject(new Error('舊預覽的錯誤'))
    await flushPromises()
    expect(w.text()).not.toContain('舊預覽的錯誤')
  })

  it('the default name follows the picked sources until the user types one', async () => {
    const w = mountForm()
    await pick(w, '來源', ['n', 's'])
    expect(field(w, '名稱').props('modelValue')).toBe('北區＋南區')
    await field(w, '名稱').vm.$emit('update:modelValue', '全公司')
    await pick(w, '來源', ['s', 'n'])
    expect(field(w, '名稱').props('modelValue')).toBe('全公司')
  })
})

const checkbox = (w, label) => w.findAllComponents({ name: 'VCheckboxBtn' }).find((c) => c.vm.$attrs['aria-label'] === label)
const keySelects = (w) =>
  w.findAllComponents({ name: 'VSelect' }).filter((c) => /^第 \d 組主來源欄位$/.test(c.vm.$attrs['aria-label'] ?? ''))

async function lookupMode(w) {
  await w.findComponent({ name: 'VRadioGroup' }).vm.$emit('update:modelValue', 'lookup')
  await flushPromises()
}

describe('MergeForm（查表）', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('主檔 skips the 主來源; 補上的欄位 keep their names (「主檔名·欄位」 on a clash); a field picked as a key gets unchecked; 預覽 sends the lookup', async () => {
    api.previewMerged.mockResolvedValue({ schema: [], rows: [], rowCount: 4, warnings: [] })
    const w = mountForm()
    await lookupMode(w)
    await pick(w, '主來源', 'r')
    expect(select(w, '主檔').props('items').map((d) => d.id)).toEqual(['n', 's', 'm'])
    await pick(w, '主檔', 'm')
    expect(field(w, '名稱').props('modelValue')).toBe('退貨（補產品主檔）')
    expect(['code', 'category', 'owner'].map((f) => field(w, `${f} 的輸出名稱`).props('modelValue')))
      .toEqual(['code', 'category', '產品主檔·owner'])
    expect(checkbox(w, '補上 code').props('modelValue')).toBe(true)
    expect(btn(w, '預覽').attributes('disabled')).toBeDefined()

    await pick(w, '第 1 組主來源欄位', 'product')
    await pick(w, '第 1 組主檔欄位', 'code')
    expect(checkbox(w, '補上 code').props('modelValue')).toBe(false)

    await btn(w, '預覽').trigger('click')
    await flushPromises()
    expect(api.previewMerged).toHaveBeenCalledWith({
      name: '退貨（補產品主檔）',
      merge: {
        op: 'lookup',
        main: 'r',
        lookup: 'm',
        keys: [{ main: 'product', lookup: 'code' }],
        bring: [{ name: 'category', from: 'category' }, { name: '產品主檔·owner', from: 'owner' }],
      },
    })
  })

  it('查表 takes 1 to 3 key pairs, and picking the 主檔 as 主來源 clears the 主檔', async () => {
    const w = mountForm()
    await lookupMode(w)
    expect(keySelects(w)).toHaveLength(1)
    expect(btn(w, '刪除第 1 組對接欄位')).toBeUndefined()
    await btn(w, '新增對接欄位').trigger('click')
    await btn(w, '新增對接欄位').trigger('click')
    expect(keySelects(w)).toHaveLength(3)
    expect(btn(w, '新增對接欄位')).toBeUndefined()
    await btn(w, '刪除第 2 組對接欄位').trigger('click')
    expect(keySelects(w)).toHaveLength(2)

    await pick(w, '主來源', 'r')
    await pick(w, '主檔', 'm')
    await pick(w, '主來源', 'm')
    expect(select(w, '主檔').props('modelValue')).toBeNull()
  })
})

describe('MergeForm（對照）', () => {
  const sales = {
    id: 'sa',
    name: '銷售',
    kind: 'upload',
    schema: [{ name: 'order_date', type: 'date' }, { name: 'region', type: 'string' }, { name: 'amount', type: 'number' }],
  }
  const refunds = {
    id: 'rf',
    name: '退貨',
    kind: 'upload',
    schema: [
      { name: 'order_date', type: 'date' },
      { name: 'region', type: 'string' },
      { name: 'amount', type: 'number' },
      { name: 'qty', type: 'number' },
    ],
  }
  const mountCompare = (sources = [sales, refunds]) => mount(MergeForm, { props: { sources }, global: { plugins: [vuetify] } })
  const way = async (w, value) => {
    await w.findComponent({ name: 'VRadioGroup' }).vm.$emit('update:modelValue', value)
    await flushPromises()
  }

  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('對照 matches dates and text by name, splits every number field per source as 「來源名·欄位」, and is named 銷售／退貨 對照', async () => {
    const w = mountCompare()
    await way(w, 'compare')
    await pick(w, '來源', ['sa', 'rf'])

    expect(columnNames(w).map((c) => c.props('modelValue'))).toEqual(['order_date', 'region', '銷售·amount', '退貨·amount', '退貨·qty'])
    expect(select(w, '第 1 欄在退貨的欄位').props('modelValue')).toBe('order_date')
    expect(select(w, '第 3 欄在銷售的欄位').props('modelValue')).toBe('amount')
    expect(select(w, '第 3 欄在退貨的欄位').props('modelValue')).toBeNull()
    expect(select(w, '第 4 欄在銷售的欄位').props('modelValue')).toBeNull()
    expect(field(w, '名稱').props('modelValue')).toBe('銷售／退貨 對照')
  })

  it('a number column mapped to two sources is marked red; 預覽 sends op union with compare: true', async () => {
    api.previewMerged.mockResolvedValue({ schema: [], rows: [], rowCount: 1, warnings: [] })
    const w = mountCompare()
    await way(w, 'compare')
    await pick(w, '來源', ['sa', 'rf'])

    await pick(w, '第 3 欄在退貨的欄位', 'amount')
    expect(columnNames(w)[2].props('errorMessages')).toBe('對照時數字欄位只能對應一個來源')
    await pick(w, '第 3 欄在退貨的欄位', null)
    expect(columnNames(w)[2].props('errorMessages')).toEqual([])

    await btn(w, '預覽').trigger('click')
    await flushPromises()
    const { name, merge } = api.previewMerged.mock.calls[0][0]
    expect(name).toBe('銷售／退貨 對照')
    expect(merge).toMatchObject({ op: 'union', compare: true, inputs: ['sa', 'rf'], sourceColumn: '來源' })
    expect(merge.columns[2]).toEqual({ name: '銷售·amount', from: { sa: 'amount' } })
  })

  it('switching from 對照 to 疊加 rebuilds the columns by name, and 疊加 sends no compare flag', async () => {
    api.previewMerged.mockResolvedValue({ schema: [], rows: [], rowCount: 1, warnings: [] })
    const w = mountCompare()
    await way(w, 'compare')
    await pick(w, '來源', ['sa', 'rf'])
    await way(w, 'union')

    expect(columnNames(w).map((c) => c.props('modelValue'))).toEqual(['order_date', 'region', 'amount', 'qty'])
    expect(field(w, '名稱').props('modelValue')).toBe('銷售＋退貨')
    await btn(w, '預覽').trigger('click')
    await flushPromises()
    expect(api.previewMerged.mock.calls[0][0].merge).not.toHaveProperty('compare')
  })

  it('the 來源 menu stops at 5: once 5 are picked the others are disabled', async () => {
    const six = Array.from({ length: 6 }, (_, i) => ({ id: `d${i}`, name: `來源${i}`, kind: 'upload', schema: [] }))
    const w = mountCompare(six)
    await pick(w, '來源', ['d0', 'd1', 'd2', 'd3'])
    expect(select(w, '來源').props('itemProps')(six[5])).toEqual({ disabled: false })
    await pick(w, '來源', ['d0', 'd1', 'd2', 'd3', 'd4'])
    const itemProps = select(w, '來源').props('itemProps')
    expect(itemProps(six[5])).toEqual({ disabled: true })
    expect(itemProps(six[0])).toEqual({ disabled: false })
  })
})

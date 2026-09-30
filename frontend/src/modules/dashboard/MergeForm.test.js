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
    suggestMapping: vi.fn(),
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

describe('MergeForm（JEV 對欄位）', () => {
  const orders = {
    id: 'o',
    name: '民國年訂單',
    kind: 'upload',
    schema: [
      { name: '訂單日期', type: 'date' },
      { name: '地區', type: 'string' },
      { name: '金額', type: 'number' },
      { name: '出貨日', type: 'date' },
    ],
  }
  const sales = {
    id: 'sa',
    name: 'sales',
    kind: 'upload',
    schema: [
      { name: 'order_date', type: 'date' },
      { name: 'region', type: 'string' },
      { name: 'amount', type: 'number' },
      { name: 'product', type: 'string' },
    ],
  }
  const THREE = {
    matches: [
      { input: 'sa', field: 'order_date', to: '訂單日期' },
      { input: 'sa', field: 'region', to: '地區' },
      { input: 'sa', field: 'amount', to: '金額' },
    ],
  }
  const mountJev = () => mount(MergeForm, { props: { sources: [orders, sales, north] }, global: { plugins: [vuetify] } })
  const tags = (w) => w.findAll('.v-chip').filter((c) => c.text() === 'JEV 建議')
  const way = async (w, value) => {
    await w.findComponent({ name: 'VRadioGroup' }).vm.$emit('update:modelValue', value)
    await flushPromises()
  }
  const ask = async (w) => {
    await btn(w, '請 JEV 對欄位').trigger('click')
    await flushPromises()
  }

  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('the button shows for 疊加 and 對照 with 2+ sources only, and sends the compare flag', async () => {
    api.suggestMapping.mockResolvedValue({ matches: [] })
    const w = mountJev()
    expect(btn(w, '請 JEV 對欄位')).toBeUndefined()
    await pick(w, '來源', ['o'])
    expect(btn(w, '請 JEV 對欄位')).toBeUndefined()

    await pick(w, '來源', ['o', 'sa'])
    await ask(w)
    expect(api.suggestMapping).toHaveBeenLastCalledWith(['o', 'sa'], false)

    await way(w, 'compare')
    expect(w.text()).not.toContain('JEV 沒有找到')
    await ask(w)
    expect(api.suggestMapping).toHaveBeenLastCalledWith(['o', 'sa'], true)

    await way(w, 'lookup')
    expect(btn(w, '請 JEV 對欄位')).toBeUndefined()
  })

  it('fills the base row, removes the extra row, tags the cell, and drops the old preview', async () => {
    api.previewMerged.mockResolvedValue({ schema: [], rows: [], rowCount: 10, warnings: [] })
    api.suggestMapping.mockResolvedValue(THREE)
    const w = mountJev()
    await pick(w, '來源', ['o', 'sa'])
    await btn(w, '預覽').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('預覽 · 共 10 筆')

    await ask(w)

    expect(columnNames(w).map((c) => c.props('modelValue'))).toEqual(['訂單日期', '地區', '金額', '出貨日', 'product'])
    expect(select(w, '第 1 欄在sales的欄位').props('modelValue')).toBe('order_date')
    expect(select(w, '第 2 欄在sales的欄位').props('modelValue')).toBe('region')
    expect(select(w, '第 3 欄在sales的欄位').props('modelValue')).toBe('amount')
    expect(tags(w)).toHaveLength(3)
    expect(w.text()).toContain('JEV 對上了 3 個欄位，建立前請先預覽確認')
    expect(w.text()).not.toContain('共 10 筆')
    expect(btn(w, '建立').attributes('disabled')).toBeDefined()
  })

  it('skips a match the user already changed by hand', async () => {
    api.suggestMapping.mockResolvedValue(THREE)
    const w = mountJev()
    await pick(w, '來源', ['o', 'sa'])
    // 手動把 order_date 對到出貨日，刪掉 order_date 自己那一列：訂單日期那格還空著，但 order_date 那列已經不在了
    await pick(w, '第 4 欄在sales的欄位', 'order_date')
    await btn(w, '刪除第 5 欄').trigger('click')

    await ask(w)

    expect(columnNames(w).map((c) => c.props('modelValue'))).toEqual(['訂單日期', '地區', '金額', '出貨日', 'product'])
    expect(select(w, '第 1 欄在sales的欄位').props('modelValue')).toBeNull()
    expect(select(w, '第 4 欄在sales的欄位').props('modelValue')).toBe('order_date')
    expect(tags(w)).toHaveLength(2)
    expect(w.text()).toContain('JEV 對上了 2 個欄位')
  })

  it('ignores a second click while waiting, and drops a response that comes back after the sources changed', async () => {
    let resolve
    api.suggestMapping.mockReturnValue(new Promise((r) => { resolve = r }))
    const w = mountJev()
    await pick(w, '來源', ['o', 'sa'])
    await btn(w, '請 JEV 對欄位').trigger('click')
    await btn(w, '請 JEV 對欄位').trigger('click')
    expect(api.suggestMapping).toHaveBeenCalledTimes(1)

    await pick(w, '來源', ['o', 'sa', 'n']) // 基準沒變，但來源換了，對應表已經重排
    resolve(THREE)
    await flushPromises()

    expect(columnNames(w)).toHaveLength(8)
    expect(tags(w)).toHaveLength(0)
    expect(w.text()).not.toContain('JEV 對上了')
  })

  it('the JEV 建議 tag goes away once the user changes that cell', async () => {
    api.suggestMapping.mockResolvedValue(THREE)
    const w = mountJev()
    await pick(w, '來源', ['o', 'sa'])
    await ask(w)
    await pick(w, '第 1 欄在sales的欄位', null)
    expect(tags(w)).toHaveLength(2)
  })

  it('says so when JEV finds nothing, and shows a failure in the error area without touching the table', async () => {
    api.suggestMapping.mockResolvedValueOnce({ matches: [] })
    const w = mountJev()
    await pick(w, '來源', ['o', 'sa'])
    await ask(w)
    expect(w.text()).toContain('JEV 沒有找到可以對上的欄位')

    api.suggestMapping.mockRejectedValueOnce(new Error('JEV 呼叫失敗（HTTP 500）'))
    await ask(w)
    expect(w.text()).toContain('JEV 呼叫失敗（HTTP 500）')
    expect(w.text()).not.toContain('JEV 沒有找到')
    expect(columnNames(w)).toHaveLength(8)
  })

  it('never overwrites a cell the user already filled', async () => {
    api.suggestMapping.mockResolvedValue({ matches: [{ input: 'sa', field: 'region', to: '地區' }] })
    const w = mountJev()
    await pick(w, '來源', ['o', 'sa'])
    await pick(w, '第 2 欄在sales的欄位', 'product')

    await ask(w)

    expect(select(w, '第 2 欄在sales的欄位').props('modelValue')).toBe('product')
    expect(columnNames(w).map((c) => c.props('modelValue'))).toContain('region')
    expect(w.text()).toContain('JEV 沒有找到可以對上的欄位')
  })

  it('drops a response that comes back after switching to 對照', async () => {
    let resolve
    api.suggestMapping.mockReturnValue(new Promise((r) => { resolve = r }))
    const w = mountJev()
    await pick(w, '來源', ['o', 'sa'])
    await btn(w, '請 JEV 對欄位').trigger('click')
    await way(w, 'compare')
    resolve(THREE)
    await flushPromises()

    const names = columnNames(w).map((c) => c.props('modelValue'))
    expect(names).toHaveLength(8)
    expect(names).toContain('region')
    expect(names).toContain('sales·amount')
    expect(tags(w)).toHaveLength(0)
    expect(w.text()).not.toContain('JEV 對上了')
  })

  it('with 3 sources, moves each matched cell and drops the shared row once it is empty', async () => {
    api.suggestMapping.mockResolvedValue({
      matches: [
        { input: 'sa', field: 'region', to: '地區' },
        { input: 'n', field: 'region', to: '地區' },
      ],
    })
    const w = mountJev()
    await pick(w, '來源', ['o', 'sa', 'n'])

    await ask(w)

    expect(select(w, '第 2 欄在sales的欄位').props('modelValue')).toBe('region')
    expect(select(w, '第 2 欄在北區的欄位').props('modelValue')).toBe('region')
    expect(columnNames(w).map((c) => c.props('modelValue'))).not.toContain('region')
    expect(tags(w)).toHaveLength(2)
    expect(w.text()).toContain('JEV 對上了 2 個欄位')
  })

  it('with 3 sources, keeps the shared row while another source still uses it', async () => {
    api.suggestMapping.mockResolvedValue({ matches: [{ input: 'sa', field: 'region', to: '地區' }] })
    const w = mountJev()
    await pick(w, '來源', ['o', 'sa', 'n'])

    await ask(w)

    expect(select(w, '第 2 欄在sales的欄位').props('modelValue')).toBe('region')
    const row = columnNames(w).map((c) => c.props('modelValue')).indexOf('region') + 1
    expect(row).toBeGreaterThan(0)
    expect(select(w, `第 ${row} 欄在北區的欄位`).props('modelValue')).toBe('region')
    expect(select(w, `第 ${row} 欄在sales的欄位`).props('modelValue')).toBeNull()
  })

  it('leaves a renamed row alone', async () => {
    api.suggestMapping.mockResolvedValue(THREE)
    const w = mountJev()
    await pick(w, '來源', ['o', 'sa'])
    await columnNames(w)[4].vm.$emit('update:modelValue', '銷售日期')
    await flushPromises()

    await ask(w)

    expect(columnNames(w).map((c) => c.props('modelValue'))).toEqual(['訂單日期', '地區', '金額', '出貨日', '銷售日期', 'product'])
    expect(select(w, '第 1 欄在sales的欄位').props('modelValue')).toBeNull()
    expect(w.text()).toContain('JEV 對上了 2 個欄位')
  })
})

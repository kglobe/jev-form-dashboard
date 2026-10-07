// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DashboardFilters from './DashboardFilters.vue'
import { api } from './api.js'
import { vuetify } from './test-vuetify.js'

vi.mock('./api.js', () => ({ api: { filterOptions: vi.fn() } }))

const OPTIONS = {
  fields: [
    { field: '地區', values: ['中區', '北區', '南區', '（空白）'] },
    { field: '產品', values: ['A', 'B'] },
  ],
}
const region = { field: '地區', values: ['北區', '南區'] }

function mountBar(props = {}) {
  return mount(DashboardFilters, { props: { dashboardId: 'd1', ...props }, global: { plugins: [vuetify] } })
}

// 選單的內容 teleport 到 body，元件樹裡找得到
const btn = (wrapper, label) => wrapper.findAllComponents({ name: 'VBtn' }).find((b) => b.text() === label)
const selects = (wrapper) => wrapper.findAllComponents({ name: 'VSelect' })
const fieldSelect = (wrapper) => selects(wrapper)[1]
// 「值」是選單裡直接列出的勾選清單，上面一個搜尋框
const valueBoxes = (wrapper) => wrapper.findAllComponents({ name: 'VCheckbox' })
const valueLabels = (wrapper) => valueBoxes(wrapper).map((b) => b.props('label'))
const checked = (wrapper) => valueBoxes(wrapper).filter((b) => b.find('input').element.checked).map((b) => b.props('label'))
const toggle = (wrapper, value) => valueBoxes(wrapper).find((b) => b.props('label') === value).find('input').trigger('click')
const searchBox = (wrapper) => wrapper.findAllComponents({ name: 'VTextField' }).find((t) => t.props('label') === '找值')
const lastEmitted = (wrapper) => wrapper.emitted('update:modelValue').at(-1)[0]
// 輸入框自己也帶著一條沒啟用（active 是 false）的進度條，只看選單裡那條
const loadingBar = (wrapper) => wrapper.findAllComponents({ name: 'VProgressLinear' }).some((p) => p.props('active'))

async function openAdd(wrapper) {
  await btn(wrapper, '加篩選').trigger('click')
  await flushPromises()
}

describe('DashboardFilters', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.filterOptions.mockResolvedValue(OPTIONS)
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('期間：選項照 spec 2.1，換了就送出新的篩選，分類照留；選回「不限」又沒有分類就是 null', async () => {
    const wrapper = mountBar({ modelValue: { period: null, fields: [region] } })
    const period = selects(wrapper)[0]
    expect(period.props('label')).toBe('期間')
    expect(period.props('items').map((i) => i.title)).toEqual(['不限', '本月', '上月', '本季', '上季', '今年', '去年'])
    expect(period.props('modelValue')).toBeNull()

    await period.vm.$emit('update:modelValue', 'last_quarter')
    expect(lastEmitted(wrapper)).toEqual({ period: 'last_quarter', fields: [region] })

    const alone = mountBar({ modelValue: { period: 'this_month', fields: [] } })
    await selects(alone)[0].vm.$emit('update:modelValue', null)
    expect(lastEmitted(alone)).toBeNull()
  })

  it('加篩選：第一次打開才抓 filter-options，之後沿用；選欄位、選值再按套用，接在最後面', async () => {
    const wrapper = mountBar({ modelValue: { period: 'this_year', fields: [region] } })
    expect(api.filterOptions).not.toHaveBeenCalled()

    await openAdd(wrapper)
    expect(api.filterOptions).toHaveBeenCalledWith('d1')
    // 已經加過的欄位不列
    expect(fieldSelect(wrapper).props('items')).toEqual(['產品'])
    expect(btn(wrapper, '套用').props('disabled')).toBe(true)

    await fieldSelect(wrapper).vm.$emit('update:modelValue', '產品')
    expect(valueLabels(wrapper)).toEqual(['A', 'B'])
    await toggle(wrapper, 'B')
    expect(checked(wrapper)).toEqual(['B'])
    expect(btn(wrapper, '套用').props('disabled')).toBe(false)
    await btn(wrapper, '套用').trigger('click')

    expect(lastEmitted(wrapper)).toEqual({ period: 'this_year', fields: [region, { field: '產品', values: ['B'] }] })

    await btn(wrapper, '取消').trigger('click')
    await openAdd(wrapper)
    expect(api.filterOptions).toHaveBeenCalledTimes(1)
  })

  it('換欄位時清掉已選的值', async () => {
    const wrapper = mountBar()
    await openAdd(wrapper)
    await fieldSelect(wrapper).vm.$emit('update:modelValue', '地區')
    await toggle(wrapper, '北區')
    await fieldSelect(wrapper).vm.$emit('update:modelValue', '產品')
    expect(checked(wrapper)).toEqual([])
    expect(btn(wrapper, '套用').props('disabled')).toBe(true)
  })

  it('chip：寫出欄位和值，超過 3 個值寫「等 N 個」；關閉鈕叫「移除「地區」篩選」，按了就拿掉那一個', async () => {
    const product = { field: '產品', values: ['A', 'B', 'C', 'D', 'E'] }
    const wrapper = mountBar({ modelValue: { period: null, fields: [region, product] } })
    const chips = wrapper.findAllComponents({ name: 'VChip' })
    expect(chips.map((c) => c.text())).toEqual(['地區：北區、南區', '產品：A、B等 5 個'])

    const close = wrapper.get('[aria-label="移除「地區」篩選"]')
    await close.trigger('click')
    expect(lastEmitted(wrapper)).toEqual({ period: null, fields: [product] })
  })

  it('點 chip 打開同一個選單，帶著它的欄位和值；套用後留在原來的位置', async () => {
    const product = { field: '產品', values: ['A'] }
    const wrapper = mountBar({ modelValue: { period: null, fields: [region, product] } })
    await wrapper.findAllComponents({ name: 'VChip' })[0].trigger('click')
    await flushPromises()

    expect(fieldSelect(wrapper).props('modelValue')).toBe('地區')
    expect(fieldSelect(wrapper).props('items')).toEqual(['地區'])
    expect(checked(wrapper)).toEqual(['北區', '南區'])
    await toggle(wrapper, '北區')
    await toggle(wrapper, '南區')
    await toggle(wrapper, '中區')
    await btn(wrapper, '套用').trigger('click')

    expect(lastEmitted(wrapper)).toEqual({ period: null, fields: [{ field: '地區', values: ['中區'] }, product] })
  })

  it('值是選單裡直接列出的勾選清單，不再疊一層下拉；搜尋框只過濾清單，勾過的值照樣留著', async () => {
    const wrapper = mountBar()
    await openAdd(wrapper)
    expect(wrapper.findComponent({ name: 'VAutocomplete' }).exists()).toBe(false)
    await fieldSelect(wrapper).vm.$emit('update:modelValue', '地區')
    expect(valueLabels(wrapper)).toEqual(['中區', '北區', '南區', '（空白）'])
    // 清單自己捲動，「取消」「套用」在清單外面
    const list = document.body.querySelector('.dash-filters__values')
    expect(list).not.toBeNull()
    expect(list.contains(btn(wrapper, '套用').element)).toBe(false)

    await toggle(wrapper, '中區')
    await searchBox(wrapper).vm.$emit('update:modelValue', '北')
    expect(valueLabels(wrapper)).toEqual(['北區'])
    await toggle(wrapper, '北區')
    await searchBox(wrapper).vm.$emit('update:modelValue', '')
    expect(checked(wrapper)).toEqual(['中區', '北區'])
    await btn(wrapper, '套用').trigger('click')
    expect(lastEmitted(wrapper)).toEqual({ period: null, fields: [{ field: '地區', values: ['中區', '北區'] }] })
  })

  it('「欄位」和搜尋框都是跟其他輸入框一樣的 outlined', async () => {
    const wrapper = mountBar()
    await openAdd(wrapper)
    await fieldSelect(wrapper).vm.$emit('update:modelValue', '地區')
    expect(fieldSelect(wrapper).props('variant')).toBe(searchBox(wrapper).props('variant'))
  })

  it('篩選放不進網址（URL 編碼後超過 4000 字）時，「套用」「存成預設」停用，選單裡說「篩選選太多了，請少選一些值」', async () => {
    const notes = { field: '備註', values: Array.from({ length: 50 }, (_, i) => `很長的備註${i}`.padEnd(12, '值')) }
    api.filterOptions.mockResolvedValue({ fields: [notes] })
    const tooLong = { period: null, fields: [notes] }
    expect(encodeURIComponent(JSON.stringify(tooLong)).length).toBeGreaterThan(4000)

    const wrapper = mountBar({ owner: true, modelValue: tooLong })
    expect(btn(wrapper, '存成預設').props('disabled')).toBe(true)
    await wrapper.findAllComponents({ name: 'VChip' })[0].trigger('click')
    await flushPromises()
    expect(btn(wrapper, '套用').props('disabled')).toBe(true)
    expect(document.body.textContent).toContain('篩選選太多了，請少選一些值')

    // 少選幾個就放得進去
    for (const v of notes.values.slice(0, 16)) await toggle(wrapper, v)
    expect(btn(wrapper, '套用').props('disabled')).toBe(false)
    expect(document.body.textContent).not.toContain('篩選選太多了，請少選一些值')
  })

  it('已經有 3 個分類篩選時沒有「加篩選」', () => {
    const three = ['地區', '產品', '通路'].map((field) => ({ field, values: ['x'] }))
    expect(btn(mountBar({ modelValue: { period: null, fields: three } }), '加篩選')).toBeUndefined()
    expect(btn(mountBar({ modelValue: { period: null, fields: three.slice(0, 2) } }), '加篩選')).toBeDefined()
  })

  it('沒有可以選的欄位時寫「這份儀表板沒有可以篩選的文字欄位」', async () => {
    api.filterOptions.mockResolvedValue({ fields: [] })
    const wrapper = mountBar()
    await openAdd(wrapper)
    expect(document.body.textContent).toContain('這份儀表板沒有可以篩選的文字欄位')
    expect(fieldSelect(wrapper)).toBeUndefined()
  })

  it('載入中顯示進度條；失敗顯示錯誤，下次打開再抓一次', async () => {
    let fail
    api.filterOptions.mockImplementationOnce(() => new Promise((_, reject) => { fail = reject }))
    const wrapper = mountBar()
    await openAdd(wrapper)
    expect(loadingBar(wrapper)).toBe(true)

    fail(new Error('資料來源載入失敗'))
    await flushPromises()
    expect(loadingBar(wrapper)).toBe(false)
    expect(document.body.textContent).toContain('資料來源載入失敗')

    await btn(wrapper, '取消').trigger('click')
    await openAdd(wrapper)
    expect(api.filterOptions).toHaveBeenCalledTimes(2)
    expect(fieldSelect(wrapper).props('items')).toEqual(['地區', '產品'])
  })

  it('有任何篩選才有「清除篩選」，按了送出 null', async () => {
    expect(btn(mountBar(), '清除篩選')).toBeUndefined()
    expect(btn(mountBar({ modelValue: { period: null, fields: [] } }), '清除篩選')).toBeUndefined()
    const wrapper = mountBar({ modelValue: { period: 'last_month', fields: [] } })
    await btn(wrapper, '清除篩選').trigger('click')
    expect(lastEmitted(wrapper)).toBeNull()
  })

  it('存成預設：只有擁有者有；跟存著的預設一樣、或正在存時停用；按了送出 save', async () => {
    const current = { period: 'last_month', fields: [region] }
    expect(btn(mountBar({ modelValue: current }), '存成預設')).toBeUndefined()

    // null 和「沒有任何篩選」是同一件事
    expect(btn(mountBar({ owner: true, modelValue: null, saved: { period: null, fields: [] } }), '存成預設').props('disabled')).toBe(true)
    expect(btn(mountBar({ owner: true, modelValue: current, saved: { ...current } }), '存成預設').props('disabled')).toBe(true)
    expect(btn(mountBar({ owner: true, modelValue: current, saved: null, saving: true }), '存成預設').props('disabled')).toBe(true)

    const wrapper = mountBar({ owner: true, modelValue: current, saved: null })
    const save = btn(wrapper, '存成預設')
    expect(save.props('disabled')).toBe(false)
    expect(save.props('variant')).toBe('tonal')
    expect(save.props('prependIcon')).toBe('mdi-content-save-outline')
    await save.trigger('click')
    expect(wrapper.emitted('save')).toHaveLength(1)
  })

  it('KPI spec 6.2：只有擁有者在篩選列最右邊有「加 KPI 卡」，按了送出 add-kpi', async () => {
    expect(btn(mountBar(), '加 KPI 卡')).toBeUndefined()

    const wrapper = mountBar({ owner: true, modelValue: { period: 'last_month', fields: [region] } })
    const add = btn(wrapper, '加 KPI 卡')
    expect(add.props('variant')).toBe('text')
    expect(add.props('prependIcon')).toBe('mdi-numeric')
    expect(add.classes()).toContain('ml-auto')
    expect(wrapper.findAllComponents({ name: 'VBtn' }).at(-1).text()).toBe('加 KPI 卡')
    await add.trigger('click')
    expect(wrapper.emitted('add-kpi')).toHaveLength(1)
  })
})

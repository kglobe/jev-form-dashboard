// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DatasourcePanel from './DatasourcePanel.vue'
import MergeForm from './MergeForm.vue'
import { api } from './api.js'
import { vuetify } from './test-vuetify.js'

vi.mock('./api.js', () => ({
  api: {
    listDatasources: vi.fn(),
    uploadDatasource: vi.fn(),
    registerApi: vi.fn(),
    setSensitive: vi.fn(),
    setLabels: vi.fn(),
    setRatios: vi.fn(),
    previewMerged: vi.fn(),
    createMerged: vi.fn(),
    datasourceUsage: vi.fn(),
    deleteDatasource: vi.fn(),
    reloadDatasource: vi.fn(),
    getDatasourceShare: vi.fn(),
  },
}))

const ds1 = {
  id: 'ds1',
  name: 'sales.csv',
  kind: 'upload',
  rowCount: 12,
  schema: [
    { name: 'region', type: 'string', sensitive: false, samples: ['北區', '南區', '東區', '西區'] },
    { name: 'memo', type: 'string', sensitive: true },
    { name: 'qty', type: 'number', sensitive: false },
  ],
}
const ds2 = { id: 'ds2', name: 'ERP 訂單', kind: 'api', apiUrl: 'https://erp.example.com', schema: [{ name: 'day', type: 'date', sensitive: false }] }
const merged = {
  id: 'mg',
  name: '全區',
  kind: 'merged',
  merge: { op: 'union', inputs: ['ds1', 'gone'], columns: [], sourceColumn: '來源' },
  schema: [
    { name: 'region', type: 'string', sensitive: false },
    { name: 'memo', type: 'string', sensitive: true, sensitiveFrom: 'sales.csv' },
    { name: 'qty', type: 'number', sensitive: false },
  ],
}
const enriched = {
  id: 'lk',
  name: '退貨（補產品主檔）',
  kind: 'merged',
  merge: { op: 'lookup', main: 'ds1', lookup: 'ds2', keys: [{ main: 'product', lookup: 'code' }], bring: [] },
  schema: [{ name: 'product', type: 'string', sensitive: false }],
}

// B3：sales、refund 有顯示名稱，refund 是敏感的，已經有一個比率
const withRatio = {
  id: 'dr',
  name: '銷售明細',
  kind: 'upload',
  schema: [
    { name: 'region', type: 'string', sensitive: false },
    { name: 'sales', type: 'number', sensitive: false, label: '銷售金額' },
    { name: 'refund', type: 'number', sensitive: true, label: '退貨金額' },
    { name: 'qty', type: 'number', sensitive: false },
  ],
  ratios: [{ name: '退貨率', numerator: 'refund', denominator: 'sales', sensitive: true }],
}

function mountPanel(selectedId = 'ds1', extra = {}) {
  return mount(DatasourcePanel, { props: { selectedId, ...extra }, global: { plugins: [vuetify] } })
}

// 對話框的內容會被 teleport 到 document.body，按鈕要從 body 找；元件本身的按鈕從 wrapper 找
function byText(root, selector, text) {
  return [...root.querySelectorAll(selector)].find((el) => el.textContent.trim() === text)
}
const button = (text) => byText(document.body, 'button', text)
const field = (wrapper, label) => wrapper.findAllComponents({ name: 'VTextField' }).find((f) => f.props('label') === label)
const dialogOpen = (wrapper) => wrapper.findComponent({ name: 'VDialog' }).props('modelValue')

// 欄位表每一列的敏感開關、顯示名稱輸入框
const switches = (wrapper) => ['region', 'memo', 'qty'].map((n) => wrapper.find(`input[aria-label="${n} 敏感"]`))
const labelInput = (wrapper, name) => wrapper.find(`input[aria-label="${name} 顯示名稱"]`)

async function openAdd(wrapper) {
  await wrapper.findAll('button').find((b) => b.text() === '新增來源').trigger('click')
  await flushPromises()
}

async function openApiTab(wrapper) {
  await openAdd(wrapper)
  const apiTab = wrapper.findAllComponents({ name: 'VTab' }).find((t) => t.text() === '外部 API')
  await apiTab.trigger('click')
  await flushPromises()
}

async function pickFile(wrapper, file) {
  await wrapper.findComponent({ name: 'VFileInput' }).vm.$emit('update:modelValue', file)
}

async function click(text) {
  button(text).click()
  await flushPromises()
}

// 比率區每一列的名稱、公式、敏感；「新增比率」是第二個對話框（第一個是「新增來源」）
const ratioRows = (wrapper) => wrapper.findAll('.ds-ratios tbody tr').map((r) => r.findAll('td').slice(0, 3).map((td) => td.text()))
const ratioDialog = (wrapper) => wrapper.findAllComponents({ name: 'VDialog' })[1]
const ratioSelect = (wrapper, label) => wrapper.findAllComponents({ name: 'VSelect' }).find((s) => s.props('label') === label)

async function openRatio(wrapper) {
  await wrapper.findAll('button').find((b) => b.text() === '新增比率').trigger('click')
  await flushPromises()
}

async function fillRatio(wrapper, name, numerator, denominator) {
  await field(wrapper, '名稱').find('input').setValue(name)
  await ratioSelect(wrapper, '分子').vm.$emit('update:modelValue', numerator)
  await ratioSelect(wrapper, '分母').vm.$emit('update:modelValue', denominator)
  await flushPromises()
}

describe('DatasourcePanel', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('selects the first datasource on mount when nothing (or a missing id) is selected, and keeps a valid selection', async () => {
    api.listDatasources.mockResolvedValue([ds1, ds2])

    const none = mountPanel(null)
    await flushPromises()
    expect(api.listDatasources).toHaveBeenCalledTimes(1)
    expect(none.emitted('select')).toEqual([['ds1']])

    const missing = mountPanel('gone')
    await flushPromises()
    expect(missing.emitted('select')).toEqual([['ds1']])

    const valid = mountPanel('ds2')
    await flushPromises()
    expect(valid.emitted('select')).toBeUndefined()
  })

  it('lists datasources, filters them by name (trimmed, case-insensitive), survives clearing, and clicking one emits select', async () => {
    api.listDatasources.mockResolvedValue([ds1, ds2])
    const wrapper = mountPanel()
    await flushPromises()

    const titles = () => wrapper.findAllComponents({ name: 'VListItem' }).map((i) => i.props('title'))
    expect(titles()).toEqual(['sales.csv', 'ERP 訂單'])

    const search = wrapper.find('input[aria-label="搜尋來源"]')
    await search.setValue('  SALES ')
    expect(titles()).toEqual(['sales.csv'])

    await search.setValue('zzz')
    expect(titles()).toEqual([])
    expect(wrapper.text()).toContain('沒有符合的來源')

    // clearable 會把值設成 null
    await wrapper.findAllComponents({ name: 'VTextField' }).find((f) => f.props('placeholder') === '搜尋來源').vm.$emit('update:modelValue', null)
    expect(titles()).toEqual(['sales.csv', 'ERP 訂單'])

    await wrapper.findAllComponents({ name: 'VListItem' })[1].trigger('click')
    expect(wrapper.emitted('select')).toEqual([['ds2']])
  })

  it('shows the selected datasource with one table row per field, and the 敏感 switches are always visible (no 隱私設定 toggle)', async () => {
    api.listDatasources.mockResolvedValue([ds1, ds2])
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.find('.ds-name').text()).toBe('sales.csv')
    expect(wrapper.find('.tc-badge').text()).toBe('上傳檔案')
    expect(wrapper.find('.ds-meta').text()).toBe('3 個欄位 · 1 個敏感')

    const rows = wrapper.findAll('.ds-fields tbody tr')
    expect(rows.map((r) => r.findAll('td').slice(0, 3).map((td) => td.text()))).toEqual([
      ['region', '文字', '北區、南區、東區'],
      ['memo', '文字', ''],
      ['qty', '數字', ''],
    ])
    expect(labelInput(wrapper, 'qty').attributes('placeholder')).toBe('qty')
    expect(switches(wrapper).map((s) => s.element.checked)).toEqual([false, true, false])
    expect(wrapper.findAll('button').some((b) => b.text().startsWith('隱私設定'))).toBe(false)

    await wrapper.setProps({ selectedId: 'ds2' })
    expect(wrapper.find('.tc-badge').text()).toBe('外部 API')
    expect(wrapper.find('.ds-meta').text()).toBe('https://erp.example.com · 1 個欄位 · 0 個敏感')
    expect(wrapper.find('.ds-fields tbody td:nth-child(2)').text()).toBe('日期')
  })

  describe('資料來源共享 spec 6.1', () => {
    const mine = { ...ds1, createdBy: 'u1' }
    // 別人分享的外部 API：後端不給網址和 header
    const theirs = {
      id: 'th',
      name: '總部 ERP',
      kind: 'api',
      apiUrl: null,
      apiHeaders: {},
      createdBy: 'boss',
      schema: [
        { name: 'sales', type: 'number', sensitive: false, label: '銷售金額' },
        { name: 'refund', type: 'number', sensitive: true },
      ],
      ratios: [{ name: '退貨率', numerator: 'refund', denominator: 'sales', sensitive: true }],
    }
    const headButtons = (wrapper) => wrapper.findAll('.cc-panel__head button').map((b) => b.text())

    it('清單先列自己的，再列「分享給我的 · N」，副標是「來自 帳號 · N 個欄位」', async () => {
      api.listDatasources.mockResolvedValue([mine, theirs])
      const wrapper = mountPanel('ds1', { username: 'u1' })
      await flushPromises()
      expect(wrapper.text()).toContain('來源 · 1')
      expect(wrapper.text()).toContain('分享給我的 · 1')
      const shared = wrapper.get('[aria-label="分享給我的資料來源"]').findAllComponents({ name: 'VListItem' })
      expect(shared.map((i) => [i.props('title'), i.props('subtitle')])).toEqual([['總部 ERP', '來自 boss · 2 個欄位']])
      await shared[0].trigger('click')
      expect(wrapper.emitted('select')).toEqual([['th']])

      // 沒有別人分享的就不顯示那一段
      api.listDatasources.mockResolvedValue([mine])
      const alone = mountPanel('ds1', { username: 'u1' })
      await flushPromises()
      expect(alone.text()).not.toContain('分享給我的')
    })

    it('別人分享的來源是唯讀的：沒有網址，按鈕只有製圖，欄位表和比率都不能改', async () => {
      api.listDatasources.mockResolvedValue([mine, theirs])
      const wrapper = mountPanel('th', { username: 'u1' })
      await flushPromises()

      expect(wrapper.find('.cc-head .cc-desc').text()).toBe('「boss」分享給你的來源，可以拿來製圖，不能修改。')
      expect(wrapper.find('.ds-meta').text()).toBe('外部 API · 2 個欄位 · 1 個敏感')
      expect(headButtons(wrapper)).toEqual(['用這個來源製圖'])
      expect(wrapper.find('input[aria-label="sales 敏感"]').element.disabled).toBe(true)
      expect(wrapper.find('input[aria-label="sales 顯示名稱"]').element.disabled).toBe(true)
      expect(wrapper.find('input[aria-label="sales 顯示名稱"]').element.value).toBe('銷售金額')
      expect(ratioRows(wrapper)).toEqual([['退貨率', 'refund ÷ 銷售金額', '敏感']])
      expect(wrapper.find('button[aria-label="刪除比率 退貨率"]').exists()).toBe(false)
      expect(wrapper.findAll('button').some((b) => b.text() === '新增比率')).toBe(false)
    })

    it('自己的來源標題列在「更新資料」左邊有「分享」，按了打開資料來源的分享對話框', async () => {
      api.listDatasources.mockResolvedValue([mine, theirs])
      api.getDatasourceShare.mockResolvedValue({ allUsers: false, users: [] })
      const wrapper = mountPanel('ds1', { username: 'u1' })
      await flushPromises()

      expect(wrapper.find('.cc-head .cc-desc').text()).toBe('管理資料來源和欄位：顯示名稱會用在圖表上，標成敏感的欄位不會把內容送給 JEV。')
      expect(headButtons(wrapper)).toEqual(['分享', '更新資料', '刪除來源', '用這個來源製圖'])
      const share = wrapper.findAll('.cc-panel__head button').find((b) => b.text() === '分享')
      expect(share.find('.mdi-share-variant-outline').exists()).toBe(true)

      await share.trigger('click')
      await flushPromises()
      const dialog = wrapper.findComponent({ name: 'ShareDialog' })
      expect(dialog.props('modelValue')).toBe(true)
      expect(dialog.props('datasource')).toEqual(mine)
      expect(api.getDatasourceShare).toHaveBeenCalledWith('ds1')
    })
  })

  it('用這個來源製圖 emits build with the selected datasource', async () => {
    api.listDatasources.mockResolvedValue([ds1, ds2])
    const wrapper = mountPanel('ds2')
    await flushPromises()

    await wrapper.findAll('button').find((b) => b.text() === '用這個來源製圖').trigger('click')
    expect(wrapper.emitted('build')).toEqual([['ds2']])
  })

  it('with no datasources shows the empty state, and its 新增來源 opens the add dialog', async () => {
    api.listDatasources.mockResolvedValue([])
    const wrapper = mountPanel(null)
    await flushPromises()

    expect(wrapper.emitted('select')).toBeUndefined()
    expect(wrapper.find('.cc-empty').text()).toContain('還沒有資料來源')
    expect(wrapper.find('.ds-list').text()).toContain('還沒有資料來源')

    await wrapper.find('.cc-empty button').trigger('click')
    expect(dialogOpen(wrapper)).toBe(true)
  })

  // 唯讀分享 spec 2.6：只有管理員能註冊外部 API 來源
  it('不是管理員：新增來源沒有「外部 API」分頁，空白狀態也不提外部 API', async () => {
    api.listDatasources.mockResolvedValue([])
    const wrapper = mountPanel(null)
    await flushPromises()
    expect(wrapper.find('.cc-empty p').text()).toBe('上傳 CSV / Excel，欄位會自動判斷型別。')

    await openAdd(wrapper)
    expect(wrapper.findAllComponents({ name: 'VTab' }).map((t) => t.text())).toEqual(['上傳檔案', '合併'])
  })

  it('管理員：新增來源有「外部 API」分頁，空白狀態提到外部 API', async () => {
    api.listDatasources.mockResolvedValue([])
    const wrapper = mountPanel(null, { admin: true })
    await flushPromises()
    expect(wrapper.find('.cc-empty p').text()).toBe('上傳 CSV / Excel，或註冊一個外部 API，欄位會自動判斷型別。')

    await openAdd(wrapper)
    expect(wrapper.findAllComponents({ name: 'VTab' }).map((t) => t.text())).toEqual(['上傳檔案', '外部 API', '合併'])
  })

  it('toggling a switch sends the union of sensitive fields, disables every switch and label field while pending, then refreshes and re-enables', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    let resolveSetSensitive
    api.setSensitive.mockImplementation(() => new Promise((resolve) => { resolveSetSensitive = resolve }))

    const wrapper = mountPanel()
    await flushPromises()
    expect(api.listDatasources).toHaveBeenCalledTimes(1)

    await switches(wrapper)[0].setValue(true) // region
    await flushPromises()

    expect(api.setSensitive).toHaveBeenCalledWith('ds1', ['region', 'memo'])
    switches(wrapper).forEach((el) => expect(el.element.disabled).toBe(true))
    expect(labelInput(wrapper, 'region').element.disabled).toBe(true)

    resolveSetSensitive()
    await flushPromises()

    expect(api.listDatasources).toHaveBeenCalledTimes(2)
    switches(wrapper).forEach((el) => expect(el.element.disabled).toBe(false))
    expect(labelInput(wrapper, 'region').element.disabled).toBe(false)
  })

  it('shows the error and re-enables the switches when setSensitive rejects', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.setSensitive.mockRejectedValue(new Error('找不到資料源'))

    const wrapper = mountPanel()
    await flushPromises()

    await switches(wrapper)[0].setValue(true)
    await flushPromises()

    expect(wrapper.find('.v-alert').text()).toContain('找不到資料源')
    switches(wrapper).forEach((el) => expect(el.element.disabled).toBe(false))
    // 開關回到實際的值，再點一次會重送，不會卡在「畫面關著、原生 input 勾著」
    expect(switches(wrapper)[0].element.checked).toBe(false)
    await switches(wrapper)[0].setValue(true)
    await flushPromises()
    expect(api.setSensitive).toHaveBeenCalledTimes(2)
  })

  it('a successful sensitive toggle clears a previous toggle error', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.setSensitive
      .mockRejectedValueOnce(new Error('找不到資料源'))
      .mockResolvedValueOnce(undefined)

    const wrapper = mountPanel()
    await flushPromises()

    await switches(wrapper)[0].setValue(true) // region: fails
    await flushPromises()
    expect(wrapper.text()).toContain('找不到資料源')

    await switches(wrapper)[1].setValue(false) // memo: succeeds
    await flushPromises()

    expect(api.setSensitive).toHaveBeenLastCalledWith('ds1', [])
    expect(wrapper.text()).not.toContain('找不到資料源')
  })

  it('changing a display name sends every label with this one replaced, then refreshes; an unchanged value sends nothing', async () => {
    api.listDatasources.mockResolvedValue([{ ...ds1, schema: [{ ...ds1.schema[0], label: '地區' }, ...ds1.schema.slice(1)] }])
    api.setLabels.mockResolvedValue({})
    const wrapper = mountPanel()
    await flushPromises()

    await labelInput(wrapper, 'region').setValue('地區')
    await flushPromises()
    expect(api.setLabels).not.toHaveBeenCalled()

    await labelInput(wrapper, 'qty').setValue('數量')
    await flushPromises()
    expect(api.setLabels).toHaveBeenCalledWith('ds1', { region: '地區', qty: '數量' })
    expect(api.listDatasources).toHaveBeenCalledTimes(2)
  })

  it('a display name is sent trimmed; a failed save shows the error and puts the saved name back in the box', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.setLabels.mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('儲存失敗'))
    const wrapper = mountPanel()
    await flushPromises()

    await labelInput(wrapper, 'qty').setValue(' 數量 ')
    await flushPromises()
    expect(api.setLabels).toHaveBeenLastCalledWith('ds1', { qty: '數量' })

    await labelInput(wrapper, 'region').setValue('地區')
    await flushPromises()
    expect(wrapper.find('.v-alert').text()).toContain('儲存失敗')
    expect(labelInput(wrapper, 'region').element.value).toBe('') // 沒存成功，不留著看起來像存好的字
  })

  it('新增來源 opens the dialog with a cleared error; a successful upload closes it and resets the form', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.uploadDatasource.mockResolvedValue({ id: 'ds2' })
    const wrapper = mountPanel()
    await flushPromises()

    expect(dialogOpen(wrapper)).toBe(false)
    await openAdd(wrapper)
    expect(dialogOpen(wrapper)).toBe(true)

    // 上次留下的錯誤，重新打開時要清掉
    await click('上傳')
    expect(document.body.textContent).toContain('請先選擇檔案')
    await click('取消')
    expect(dialogOpen(wrapper)).toBe(false)
    await openAdd(wrapper)
    expect(document.body.textContent).not.toContain('請先選擇檔案')

    await pickFile(wrapper, new File(['a,b'], 'sales.csv', { type: 'text/csv' }))
    await field(wrapper, '名稱（預設用檔名）').find('input').setValue('業績')
    await field(wrapper, '工作表（Excel 選填）').find('input').setValue('Sheet2')
    await click('上傳')

    expect(dialogOpen(wrapper)).toBe(false)
    expect(wrapper.findComponent({ name: 'VFileInput' }).props('modelValue')).toBeNull()
    expect(field(wrapper, '名稱（預設用檔名）').props('modelValue')).toBe('')
    expect(field(wrapper, '工作表（Excel 選填）').props('modelValue')).toBe('')
  })

  it('uploads the selected file as FormData with name/sheet, refreshes, and emits select on success', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.uploadDatasource.mockResolvedValue({ id: 'ds2' })

    const wrapper = mountPanel()
    await flushPromises()
    await openAdd(wrapper)

    const file = new File(['a,b'], 'sales.csv', { type: 'text/csv' })
    await pickFile(wrapper, file)
    await click('上傳')

    expect(api.uploadDatasource).toHaveBeenCalledTimes(1)
    const form1 = api.uploadDatasource.mock.calls[0][0]
    expect(form1.get('file')).toStrictEqual(file)
    expect(form1.get('name')).toBe('sales.csv')
    expect(form1.has('sheet')).toBe(false)
    expect(api.listDatasources).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('select')).toEqual([['ds2']])

    // 成功後表單清空，第二次要重新選檔
    await openAdd(wrapper)
    await pickFile(wrapper, file)
    await field(wrapper, '名稱（預設用檔名）').find('input').setValue('業績')
    await field(wrapper, '工作表（Excel 選填）').find('input').setValue('Sheet2')
    await click('上傳')

    expect(api.uploadDatasource).toHaveBeenCalledTimes(2)
    const form2 = api.uploadDatasource.mock.calls[1][0]
    expect(form2.get('name')).toBe('業績')
    expect(form2.get('sheet')).toBe('Sheet2')
  })

  it('upload(): shows an error and does not call the API when no file is selected', async () => {
    api.listDatasources.mockResolvedValue([])
    const wrapper = mountPanel(null)
    await flushPromises()
    await openAdd(wrapper)

    await click('上傳')

    expect(document.body.textContent).toContain('請先選擇檔案')
    expect(api.uploadDatasource).not.toHaveBeenCalled()
    expect(dialogOpen(wrapper)).toBe(true)
  })

  it('upload(): shows an error and does not call the API when the file exceeds 50MB', async () => {
    api.listDatasources.mockResolvedValue([])
    const wrapper = mountPanel(null)
    await flushPromises()
    await openAdd(wrapper)

    const bigFile = new File(['x'], 'big.csv', { type: 'text/csv' })
    Object.defineProperty(bigFile, 'size', { value: 50 * 1024 * 1024 + 1 })
    await pickFile(wrapper, bigFile)
    await click('上傳')

    expect(document.body.textContent).toContain('檔案太大，上限 50MB')
    expect(api.uploadDatasource).not.toHaveBeenCalled()
  })

  it('shows a JSON hint/example and the DS_-prefixed label on the headers field', async () => {
    api.listDatasources.mockResolvedValue([])
    const wrapper = mountPanel(null, { admin: true })
    await flushPromises()
    await openApiTab(wrapper)

    const headersField = wrapper.findComponent({ name: 'VTextarea' })
    expect(headersField.props('label')).toBe('headers（JSON，值只能寫 ${DS_環境變數名}）')
    // 密鑰綁定 spec 4：密鑰沒綁定 host 就送不出去，提示裡要先講
    expect(headersField.props('hint')).toBe('範例：{"Authorization":"${DS_ERP_AUTH}"}。密鑰還要在 backend/.env 綁定這個網址的 host')
    expect(document.body.textContent).toContain('密鑰還要在 backend/.env 綁定這個網址的 host')
  })

  it('register() sends {name, url, headers} parsed from the textarea', async () => {
    api.listDatasources.mockResolvedValue([])
    api.registerApi.mockResolvedValue({ id: 'ds9' })

    const wrapper = mountPanel(null, { admin: true })
    await flushPromises()
    await openApiTab(wrapper)

    await field(wrapper, '名稱').find('input').setValue('erp')
    await field(wrapper, '網址').find('input').setValue('https://erp.example.com')
    await wrapper.findComponent({ name: 'VTextarea' }).find('textarea').setValue('{"Authorization":"${DS_ERP_AUTH}"}')

    await click('註冊')

    expect(api.registerApi).toHaveBeenCalledWith({
      name: 'erp',
      url: 'https://erp.example.com',
      headers: { Authorization: '${DS_ERP_AUTH}' },
    })
    expect(wrapper.emitted('select')).toEqual([['ds9']])
  })

  it('register(): invalid JSON in headers shows the Chinese error and does not call the API', async () => {
    api.listDatasources.mockResolvedValue([])

    const wrapper = mountPanel(null, { admin: true })
    await flushPromises()
    await openApiTab(wrapper)

    await wrapper.findComponent({ name: 'VTextarea' }).find('textarea').setValue('{not json')
    await click('註冊')

    expect(document.body.textContent).toContain('headers 必須是 JSON 物件')
    expect(api.registerApi).not.toHaveBeenCalled()
  })

  it('register(): valid but non-object JSON in headers (array/string) shows the Chinese error and does not call the API', async () => {
    api.listDatasources.mockResolvedValue([])

    const wrapper = mountPanel(null, { admin: true })
    await flushPromises()
    await openApiTab(wrapper)

    const headersField = wrapper.findComponent({ name: 'VTextarea' })

    await headersField.find('textarea').setValue('[]')
    await click('註冊')
    expect(document.body.textContent).toContain('headers 必須是 JSON 物件')

    await headersField.find('textarea').setValue('"x"')
    await click('註冊')
    expect(document.body.textContent).toContain('headers 必須是 JSON 物件')

    expect(api.registerApi).not.toHaveBeenCalled()
  })

  it('上傳檔案 and 外部 API are not pages in a sequence: switching swaps the content in place, without the window slide', async () => {
    api.listDatasources.mockResolvedValue([])
    const wrapper = mountPanel(null)
    await flushPromises()
    await openAdd(wrapper)

    const items = wrapper.findAllComponents({ name: 'VWindowItem' })
    expect(items.map((w) => [w.props('transition'), w.props('reverseTransition')])).toEqual([[false, false], [false, false], [false, false]])
  })

  it('a merged source shows the 合併 badge and icon, what it merges (missing inputs as （已不存在）), and locks inherited sensitive switches', async () => {
    api.listDatasources.mockResolvedValue([ds1, ds2, merged, enriched])
    const wrapper = mountPanel('mg')
    await flushPromises()

    expect(wrapper.find('.tc-badge').text()).toBe('合併')
    expect(wrapper.findAllComponents({ name: 'VListItem' }).find((i) => i.props('title') === '全區').props('prependIcon')).toBe('mdi-set-merge')
    expect(wrapper.find('.ds-meta').text()).toBe('疊加：sales.csv、（已不存在） · 3 個欄位 · 1 個敏感')
    const [region, memo] = switches(wrapper)
    expect(memo.element.disabled).toBe(true)
    expect(region.element.disabled).toBe(false)
    expect(memo.element.closest('td').getAttribute('title')).toBe('在『sales.csv』標為敏感，要改請到那個來源')
    expect(region.element.closest('td').hasAttribute('title')).toBe(false)

    await wrapper.setProps({ selectedId: 'lk' })
    expect(wrapper.find('.ds-meta').text()).toBe('查表：sales.csv ← ERP 訂單（product = code） · 1 個欄位 · 0 個敏感')
  })

  it('a 對照 source says 對照 instead of 疊加 in its summary', async () => {
    const compared = { ...merged, id: 'cp', name: '銷售／退貨 對照', merge: { ...merged.merge, compare: true } }
    api.listDatasources.mockResolvedValue([ds1, ds2, compared])
    const wrapper = mountPanel('cp')
    await flushPromises()

    expect(wrapper.find('.ds-meta').text()).toBe('對照：sales.csv、（已不存在） · 3 個欄位 · 1 個敏感')
  })

  it('合併 tab widens the dialog and shows the merge form; a created source is selected and the dialog closes', async () => {
    api.listDatasources.mockResolvedValue([ds1, ds2])
    const wrapper = mountPanel()
    await flushPromises()
    await openAdd(wrapper)
    await wrapper.findAllComponents({ name: 'VTab' }).find((t) => t.text() === '合併').trigger('click')
    await flushPromises()

    expect(wrapper.findComponent({ name: 'VDialog' }).props('maxWidth')).toBe(760)
    const form = wrapper.findComponent(MergeForm)
    expect(form.props('sources')).toEqual([ds1, ds2])
    expect(button('上傳')).toBeUndefined()
    expect(button('註冊')).toBeUndefined()

    api.listDatasources.mockResolvedValue([ds1, ds2, merged])
    form.vm.$emit('created', { id: 'mg' })
    await flushPromises()
    expect(api.listDatasources).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('select').at(-1)).toEqual(['mg'])
    expect(dialogOpen(wrapper)).toBe(false)
  })

  it('比率區 lists each ratio with its formula in display names and a 敏感 mark; with none it explains what a ratio is', async () => {
    api.listDatasources.mockResolvedValue([withRatio, ds1])
    const wrapper = mountPanel('dr')
    await flushPromises()

    expect(wrapper.find('.ds-ratios .cc-label').text()).toBe('比率 · 1')
    expect(ratioRows(wrapper)).toEqual([['退貨率', '退貨金額 ÷ 銷售金額', '敏感']])
    expect(wrapper.find('.ds-ratios .tc-tag').attributes('title')).toBe('分子或分母是敏感欄位')
    expect(wrapper.find('button[aria-label="刪除比率 退貨率"]').exists()).toBe(true)

    await wrapper.setProps({ selectedId: 'ds1' })
    expect(wrapper.find('.ds-ratios .cc-label').text()).toBe('比率 · 0')
    expect(wrapper.findAll('.ds-ratios tbody tr')).toHaveLength(0)
    expect(wrapper.find('.ds-ratios').text()).toContain('比率＝分子 ÷ 分母，每一組先各自加總再相除，例如退貨率。')
  })

  it('新增比率 offers only number fields by display name, enables 新增 once all three are filled with two different fields, and sends the whole list', async () => {
    api.listDatasources.mockResolvedValue([withRatio])
    api.setRatios.mockResolvedValue({})
    const wrapper = mountPanel('dr')
    await flushPromises()
    await openRatio(wrapper)

    expect(ratioDialog(wrapper).props('modelValue')).toBe(true)
    expect(ratioSelect(wrapper, '分子').props('items')).toEqual([
      { title: '銷售金額', value: 'sales' }, { title: '退貨金額', value: 'refund' }, { title: 'qty', value: 'qty' },
    ])
    expect(button('新增').disabled).toBe(true)
    await fillRatio(wrapper, ' 件均 ', 'sales', 'sales')
    expect(button('新增').disabled).toBe(true) // 分子和分母一樣
    await ratioSelect(wrapper, '分母').vm.$emit('update:modelValue', 'qty')
    await flushPromises()
    expect(button('新增').disabled).toBe(false)

    await click('新增')
    // 整份清單送出，只有名稱、分子、分母；sensitive 是後端算的
    expect(api.setRatios).toHaveBeenCalledWith('dr', [
      { name: '退貨率', numerator: 'refund', denominator: 'sales' },
      { name: '件均', numerator: 'sales', denominator: 'qty' },
    ])
    expect(api.listDatasources).toHaveBeenCalledTimes(2)
    expect(ratioDialog(wrapper).props('modelValue')).toBe(false)
  })

  it('a rejected 新增 keeps the dialog open with the backend message', async () => {
    api.listDatasources.mockResolvedValue([withRatio])
    api.setRatios.mockRejectedValue(new Error('比率名稱「退貨率」跟欄位或其他比率重複'))
    const wrapper = mountPanel('dr')
    await flushPromises()
    await openRatio(wrapper)
    await fillRatio(wrapper, '退貨率', 'sales', 'qty')

    await click('新增')

    expect(ratioDialog(wrapper).props('modelValue')).toBe(true)
    expect(ratioDialog(wrapper).findComponent({ name: 'VAlert' }).text()).toBe('比率名稱「退貨率」跟欄位或其他比率重複')
    expect(api.listDatasources).toHaveBeenCalledTimes(1)
  })

  it('刪除比率 sends the list without it and refreshes; a rejected delete shows the message in the 比率 section', async () => {
    api.listDatasources.mockResolvedValue([withRatio])
    api.setRatios.mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('找不到資料源'))
    const wrapper = mountPanel('dr')
    await flushPromises()

    await wrapper.find('button[aria-label="刪除比率 退貨率"]').trigger('click')
    await flushPromises()
    expect(api.setRatios).toHaveBeenCalledWith('dr', [])
    expect(api.listDatasources).toHaveBeenCalledTimes(2)

    await wrapper.find('button[aria-label="刪除比率 退貨率"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.ds-ratios .v-alert').text()).toBe('找不到資料源')
  })

  // 「刪除來源」的對話框是第三個（新增來源、新增比率之後）
  const deleteDialog = (wrapper) => wrapper.findAllComponents({ name: 'VDialog' })[2]
  const deleteBtn = (wrapper) => wrapper.findAll('button').find((b) => b.text() === '刪除來源')
  async function openDelete(wrapper, usage) {
    api.datasourceUsage.mockResolvedValue(usage)
    await deleteBtn(wrapper).trigger('click')
    await flushPromises()
  }
  const dialogText = () => document.body.textContent

  it('被合併來源用到時只說明、不能刪', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    const wrapper = mountPanel()
    await flushPromises()

    await openDelete(wrapper, { mergedBy: [{ id: 'mg', name: '全區' }], dashboards: [] })

    expect(api.datasourceUsage).toHaveBeenCalledTimes(1)
    expect(api.datasourceUsage).toHaveBeenCalledWith('ds1')
    expect(deleteDialog(wrapper).props('modelValue')).toBe(true)
    expect(dialogText()).toContain('無法刪除')
    expect(dialogText()).toContain('「sales.csv」是下面這些合併來源的輸入，要先刪掉它們：')
    expect(dialogText()).toContain('全區')
    expect(button('刪除')).toBeUndefined()
    await click('知道了')
    expect(deleteDialog(wrapper).props('modelValue')).toBe(false)
    expect(api.deleteDatasource).not.toHaveBeenCalled()
  })

  it('被儀表板用到時列出儀表板，確認後刪除並重讀清單', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.deleteDatasource.mockResolvedValue(null)
    const wrapper = mountPanel()
    await flushPromises()

    await openDelete(wrapper, { mergedBy: [], dashboards: [{ id: 'd1', name: '週報', charts: 3 }] })

    expect(dialogText()).toContain('刪除資料來源')
    expect(dialogText()).toContain('確定刪除「sales.csv」？刪除後無法復原。')
    expect(dialogText()).toContain('下面這些儀表板有用到它的圖，刪除後會顯示「資料來源已刪除」，可以再按 ✕ 移除：')
    expect(dialogText()).toContain('週報（3 張圖）')
    await click('刪除')
    expect(api.deleteDatasource).toHaveBeenCalledWith('ds1')
    expect(api.listDatasources).toHaveBeenCalledTimes(2)
    expect(deleteDialog(wrapper).props('modelValue')).toBe(false)
  })

  it('資料來源共享 spec 6.3：有分享出去時，確認文字多一行提醒', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    const wrapper = mountPanel()
    await flushPromises()
    const warning = '這個來源有分享給別人，刪除後，別人用它做的圖和合併來源都會失效。'

    await openDelete(wrapper, { mergedBy: [], dashboards: [], shared: true })
    expect(dialogText()).toContain(warning)
    await click('取消')
    await openDelete(wrapper, { mergedBy: [], dashboards: [], shared: false })
    expect(dialogText()).not.toContain(warning)
  })

  it('沒有人用到時不列儀表板那一段', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    const wrapper = mountPanel()
    await flushPromises()

    await openDelete(wrapper, { mergedBy: [], dashboards: [] })

    expect(dialogText()).toContain('確定刪除「sales.csv」？刪除後無法復原。')
    expect(dialogText()).not.toContain('下面這些儀表板')
  })

  it('刪除失敗時錯誤留在對話框裡', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.deleteDatasource.mockRejectedValue(new Error('「sales.csv」是這些合併來源的輸入，要先刪掉：全區'))
    const wrapper = mountPanel()
    await flushPromises()
    await openDelete(wrapper, { mergedBy: [], dashboards: [] })

    await click('刪除')

    expect(deleteDialog(wrapper).props('modelValue')).toBe(true)
    expect(deleteDialog(wrapper).findComponent({ name: 'VAlert' }).text()).toBe('「sales.csv」是這些合併來源的輸入，要先刪掉：全區')
  })

  it('刪除請求還沒回來時不能再按一次', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.deleteDatasource.mockReturnValue(new Promise(() => {}))
    const wrapper = mountPanel()
    await flushPromises()
    await openDelete(wrapper, { mergedBy: [], dashboards: [] })

    await click('刪除')
    const btn = deleteDialog(wrapper).findAllComponents({ name: 'VBtn' }).find((b) => b.text() === '刪除')
    expect(btn.props('loading')).toBe(true)
    button('刪除').click()
    await flushPromises()

    expect(api.deleteDatasource).toHaveBeenCalledTimes(1)
  })

  it('usage 查詢中切換選取，刪除的還是按下按鈕時的那個來源', async () => {
    api.listDatasources.mockResolvedValue([ds1, ds2])
    api.deleteDatasource.mockResolvedValue(null)
    const wrapper = mountPanel('ds1')
    await flushPromises()
    let resolveUsage
    api.datasourceUsage.mockReturnValue(new Promise((r) => (resolveUsage = r)))
    await deleteBtn(wrapper).trigger('click')
    await wrapper.setProps({ selectedId: 'ds2' })
    resolveUsage({ mergedBy: [], dashboards: [] })
    await flushPromises()

    expect(dialogText()).toContain('確定刪除「sales.csv」？')
    await click('刪除')
    expect(api.deleteDatasource).toHaveBeenCalledWith('ds1')
  })

  it('usage 查詢失敗時訊息顯示在頁面的錯誤列，對話框不打開', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.datasourceUsage.mockRejectedValue(new Error('找不到資料源'))
    const wrapper = mountPanel()
    await flushPromises()

    await deleteBtn(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.find('.v-alert').text()).toBe('找不到資料源')
    expect(deleteDialog(wrapper).props('modelValue')).toBe(false)
  })

  it('usage 查詢失敗後再查成功，頁面上的舊錯誤會清掉並打開對話框', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.datasourceUsage.mockRejectedValueOnce(new Error('找不到資料源'))
    const wrapper = mountPanel()
    await flushPromises()
    await deleteBtn(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.find('.v-alert').text()).toBe('找不到資料源')

    await openDelete(wrapper, { mergedBy: [], dashboards: [] })

    expect(wrapper.find('.v-alert').exists()).toBe(false)
    expect(deleteDialog(wrapper).props('modelValue')).toBe(true)
  })

  // 更新資料 spec 第 5 節：「更新資料」的對話框是第四個（新增來源、新增比率、刪除來源之後）
  const reloadDialog = (wrapper) => wrapper.findAllComponents({ name: 'VDialog' })[3]
  const reloadBtn = (wrapper) => wrapper.findAll('button').find((b) => b.text() === '更新資料')
  async function openReload(wrapper) {
    await reloadBtn(wrapper).trigger('click')
    await flushPromises()
  }
  async function pickReloadFile(wrapper, file) {
    await reloadDialog(wrapper).findComponent({ name: 'VFileInput' }).vm.$emit('update:modelValue', file)
  }
  async function chooseMode(wrapper, mode) {
    await reloadDialog(wrapper).findComponent({ name: 'VRadioGroup' }).vm.$emit('update:modelValue', mode)
    await flushPromises()
  }
  const cancelReload = (wrapper) => reloadDialog(wrapper).findAllComponents({ name: 'VBtn' }).find((b) => b.text() === '取消')
  const reloadField = (wrapper, label) =>
    reloadDialog(wrapper).findAllComponents({ name: 'VTextField' }).find((f) => f.props('label') === label)

  it('只有上傳的來源有「更新資料」，外部 API 和合併來源沒有', async () => {
    api.listDatasources.mockResolvedValue([ds1, ds2, merged])
    const wrapper = mountPanel('ds1')
    await flushPromises()
    expect(reloadBtn(wrapper)).toBeDefined()

    await wrapper.setProps({ selectedId: 'ds2' })
    expect(reloadBtn(wrapper)).toBeUndefined()
    await wrapper.setProps({ selectedId: 'mg' })
    expect(reloadBtn(wrapper)).toBeUndefined()
  })

  it('對話框寫出目前筆數和會影響哪些東西；沒選更新方式之前「更新」按不下去', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    const wrapper = mountPanel()
    await flushPromises()
    await openReload(wrapper)

    expect(reloadDialog(wrapper).props('modelValue')).toBe(true)
    expect(dialogText()).toContain('「sales.csv」目前有 12 筆資料。新檔案的欄位名稱要跟原本一樣。')
    expect(dialogText()).toContain('更新後，用到它的圖和合併來源都會顯示新的資料。')
    const fileInput = reloadDialog(wrapper).findComponent({ name: 'VFileInput' })
    expect(fileInput.find('input').attributes('accept')).toBe('.csv,.xlsx,.xls')
    expect(reloadField(wrapper, '工作表（Excel 選填）')).toBeDefined()
    const radios = reloadDialog(wrapper).findAllComponents({ name: 'VRadio' })
    expect(radios.map((r) => [r.props('value'), r.props('label')])).toEqual([['replace', '整份覆蓋'], ['append', '接在後面']])
    expect(reloadDialog(wrapper).findComponent({ name: 'VRadioGroup' }).props('modelValue')).toBeNull()

    expect(button('更新').disabled).toBe(true)
    await chooseMode(wrapper, 'append')
    expect(button('更新').disabled).toBe(false)
  })

  it('送出檔案、更新方式和工作表，成功後關掉對話框、重讀清單並顯示新的筆數', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.reloadDatasource.mockResolvedValue({ ...ds1, rowCount: 15 })
    const wrapper = mountPanel()
    await flushPromises()
    await openReload(wrapper)

    const file = new File(['a,b'], 'sales.xlsx')
    await pickReloadFile(wrapper, [file])
    await reloadField(wrapper, '工作表（Excel 選填）').find('input').setValue('九月')
    await chooseMode(wrapper, 'replace')
    await click('更新')

    expect(api.reloadDatasource).toHaveBeenCalledTimes(1)
    const [id, form] = api.reloadDatasource.mock.calls[0]
    expect(id).toBe('ds1')
    expect(form.get('file')).toStrictEqual(file)
    expect(form.get('mode')).toBe('replace')
    expect(form.get('sheet')).toBe('九月')
    expect(api.listDatasources).toHaveBeenCalledTimes(2)
    expect(reloadDialog(wrapper).props('modelValue')).toBe(false)
    expect(wrapper.text()).toContain('已更新「sales.csv」：共 15 筆')

    // 再打開是乾淨的表單：沒選方式、沒選檔案，成功訊息也收起來
    await openReload(wrapper)
    expect(reloadDialog(wrapper).findComponent({ name: 'VRadioGroup' }).props('modelValue')).toBeNull()
    expect(reloadDialog(wrapper).findComponent({ name: 'VFileInput' }).props('modelValue')).toBeNull()
    expect(wrapper.text()).not.toContain('已更新')
    expect(button('更新').disabled).toBe(true)
  })

  it('沒填工作表就不送 sheet', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.reloadDatasource.mockResolvedValue({ ...ds1, rowCount: 13 })
    const wrapper = mountPanel()
    await flushPromises()
    await openReload(wrapper)

    await pickReloadFile(wrapper, new File(['a,b'], 'oct.csv'))
    await chooseMode(wrapper, 'append')
    await click('更新')

    const form = api.reloadDatasource.mock.calls[0][1]
    expect(form.get('mode')).toBe('append')
    expect(form.has('sheet')).toBe(false)
  })

  it('後端擋下來時，錯誤留在對話框裡，不重讀清單', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.reloadDatasource.mockRejectedValue(new Error('欄位跟原本的不一樣：少了 qty'))
    const wrapper = mountPanel()
    await flushPromises()
    await openReload(wrapper)

    await pickReloadFile(wrapper, new File(['a,b'], 'oct.csv'))
    await chooseMode(wrapper, 'append')
    await click('更新')

    expect(reloadDialog(wrapper).props('modelValue')).toBe(true)
    expect(reloadDialog(wrapper).findComponent({ name: 'VAlert' }).text()).toBe('欄位跟原本的不一樣：少了 qty')
    expect(api.listDatasources).toHaveBeenCalledTimes(1)

    // 重新打開時舊的錯誤清掉
    await click('取消')
    await openReload(wrapper)
    expect(reloadDialog(wrapper).findComponent({ name: 'VAlert' }).exists()).toBe(false)
  })

  it('沒選檔案或檔案超過 50MB 時不送出', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    const wrapper = mountPanel()
    await flushPromises()
    await openReload(wrapper)
    await chooseMode(wrapper, 'replace')

    await click('更新')
    expect(reloadDialog(wrapper).findComponent({ name: 'VAlert' }).text()).toBe('請先選擇檔案')

    const big = new File(['x'], 'big.csv')
    Object.defineProperty(big, 'size', { value: 50 * 1024 * 1024 + 1 })
    await pickReloadFile(wrapper, big)
    await click('更新')
    expect(reloadDialog(wrapper).findComponent({ name: 'VAlert' }).text()).toBe('檔案太大，上限 50MB')
    expect(api.reloadDatasource).not.toHaveBeenCalled()
  })

  it('更新請求還沒回來時不能再按一次', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.reloadDatasource.mockReturnValue(new Promise(() => {}))
    const wrapper = mountPanel()
    await flushPromises()
    await openReload(wrapper)
    await pickReloadFile(wrapper, new File(['a,b'], 'oct.csv'))
    await chooseMode(wrapper, 'append')

    await click('更新')
    button('更新').click()
    await flushPromises()

    expect(api.reloadDatasource).toHaveBeenCalledTimes(1)
  })

  it('更新請求還沒回來時關不掉對話框，失敗的訊息才看得到；回來之後又能關', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    let fail
    api.reloadDatasource.mockReturnValue(new Promise((_, reject) => (fail = reject)))
    const wrapper = mountPanel()
    await flushPromises()
    await openReload(wrapper)
    await pickReloadFile(wrapper, new File(['a,b'], 'oct.csv'))
    await chooseMode(wrapper, 'replace')
    await click('更新')

    // 「取消」停用；persistent 讓點對話框外面、按 Esc 也不會關
    expect(cancelReload(wrapper).element.disabled).toBe(true)
    expect(reloadDialog(wrapper).props('persistent')).toBe(true)

    fail(new Error('欄位跟原本的不一樣：少了 qty'))
    await flushPromises()
    expect(reloadDialog(wrapper).props('modelValue')).toBe(true)
    expect(reloadDialog(wrapper).findComponent({ name: 'VAlert' }).text()).toBe('欄位跟原本的不一樣：少了 qty')
    expect(cancelReload(wrapper).element.disabled).toBe(false)
    expect(reloadDialog(wrapper).props('persistent')).toBe(false)
  })
})

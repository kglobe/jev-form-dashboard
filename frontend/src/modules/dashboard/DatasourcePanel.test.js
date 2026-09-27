// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DatasourcePanel from './DatasourcePanel.vue'
import { api } from './api.js'
import { vuetify } from './test-vuetify.js'

vi.mock('./api.js', () => ({
  api: {
    listDatasources: vi.fn(),
    uploadDatasource: vi.fn(),
    registerApi: vi.fn(),
    setSensitive: vi.fn(),
    setLabels: vi.fn(),
  },
}))

const ds1 = {
  id: 'ds1',
  name: 'sales.csv',
  kind: 'upload',
  schema: [
    { name: 'region', type: 'string', sensitive: false, samples: ['北區', '南區', '東區', '西區'] },
    { name: 'memo', type: 'string', sensitive: true },
    { name: 'qty', type: 'number', sensitive: false },
  ],
}
const ds2 = { id: 'ds2', name: 'ERP 訂單', kind: 'api', apiUrl: 'https://erp.example.com', schema: [{ name: 'day', type: 'date', sensitive: false }] }

function mountPanel(selectedId = 'ds1') {
  return mount(DatasourcePanel, { props: { selectedId }, global: { plugins: [vuetify] } })
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
    const wrapper = mountPanel(null)
    await flushPromises()
    await openApiTab(wrapper)

    const headersField = wrapper.findComponent({ name: 'VTextarea' })
    expect(headersField.props('label')).toBe('headers（JSON，值只能寫 ${DS_環境變數名}）')
    expect(document.body.textContent).toContain('{"Authorization":"${DS_ERP_AUTH}"}')
  })

  it('register() sends {name, url, headers} parsed from the textarea', async () => {
    api.listDatasources.mockResolvedValue([])
    api.registerApi.mockResolvedValue({ id: 'ds9' })

    const wrapper = mountPanel(null)
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

    const wrapper = mountPanel(null)
    await flushPromises()
    await openApiTab(wrapper)

    await wrapper.findComponent({ name: 'VTextarea' }).find('textarea').setValue('{not json')
    await click('註冊')

    expect(document.body.textContent).toContain('headers 必須是 JSON 物件')
    expect(api.registerApi).not.toHaveBeenCalled()
  })

  it('register(): valid but non-object JSON in headers (array/string) shows the Chinese error and does not call the API', async () => {
    api.listDatasources.mockResolvedValue([])

    const wrapper = mountPanel(null)
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
    expect(items.map((w) => [w.props('transition'), w.props('reverseTransition')])).toEqual([[false, false], [false, false]])
  })
})

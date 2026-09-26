// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
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
    { name: 'region', type: 'string', sensitive: false },
    { name: 'memo', type: 'string', sensitive: true },
    { name: 'qty', type: 'number', sensitive: false },
  ],
}

function mountPanel() {
  return mount(DatasourcePanel, { props: { selectedId: null }, global: { plugins: [vuetify] } })
}

// 敏感勾選框（隱私設定展開後才有）
function checkboxes(wrapper) {
  return wrapper.findAllComponents({ name: 'VCheckbox' }).filter((c) => c.props('label').endsWith('敏感')).map((c) => c.find('input'))
}

// 想看的欄位勾選框
function pickBoxes(wrapper) {
  return wrapper.findAllComponents({ name: 'VCheckbox' }).filter((c) => !c.props('label').endsWith('敏感')).map((c) => c.find('input'))
}

async function openPrivacy(wrapper) {
  await wrapper.findAll('button').find((b) => b.text().startsWith('隱私設定')).trigger('click')
}

async function openApiTab(wrapper) {
  const apiTab = wrapper.findAllComponents({ name: 'VTab' }).find((t) => t.text() === '外部 API')
  await apiTab.trigger('click')
  await flushPromises()
}

describe('DatasourcePanel', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('ticking a checkbox sends the union of sensitive fields, disables all checkboxes while pending, then refreshes and re-enables', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    let resolveSetSensitive
    api.setSensitive.mockImplementation(() => new Promise((resolve) => { resolveSetSensitive = resolve }))

    const wrapper = mountPanel()
    await flushPromises()
    expect(api.listDatasources).toHaveBeenCalledTimes(1)

    await openPrivacy(wrapper)
    await checkboxes(wrapper)[0].setValue(true) // region
    await flushPromises()

    expect(api.setSensitive).toHaveBeenCalledWith('ds1', ['region', 'memo'])
    checkboxes(wrapper).forEach((el) => expect(el.element.disabled).toBe(true))

    resolveSetSensitive()
    await flushPromises()

    expect(api.listDatasources).toHaveBeenCalledTimes(2)
    checkboxes(wrapper).forEach((el) => expect(el.element.disabled).toBe(false))
  })

  it('shows the error and re-enables checkboxes when setSensitive rejects', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.setSensitive.mockRejectedValue(new Error('找不到資料源'))

    const wrapper = mountPanel()
    await flushPromises()

    await openPrivacy(wrapper)
    await checkboxes(wrapper)[0].setValue(true)
    await flushPromises()

    expect(wrapper.text()).toContain('找不到資料源')
    checkboxes(wrapper).forEach((el) => expect(el.element.disabled).toBe(false))
  })

  it('a successful sensitive toggle clears a previous toggle error', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.setSensitive
      .mockRejectedValueOnce(new Error('找不到資料源'))
      .mockResolvedValueOnce(undefined)

    const wrapper = mountPanel()
    await flushPromises()

    await openPrivacy(wrapper)
    await checkboxes(wrapper)[0].setValue(true) // region: fails
    await flushPromises()
    expect(wrapper.text()).toContain('找不到資料源')

    await checkboxes(wrapper)[1].setValue(false) // memo: succeeds
    await flushPromises()

    expect(wrapper.text()).not.toContain('找不到資料源')
  })

  it('field rows are pick checkboxes; the 敏感 checkboxes stay hidden until 隱私設定 is opened', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    const wrapper = mountPanel()
    await flushPromises()

    const labels = () => wrapper.findAllComponents({ name: 'VCheckbox' }).map((c) => c.props('label'))
    expect(labels()).toEqual(['region（string）', 'memo（string）', 'qty（number）'])
    expect(wrapper.text()).toContain('隱私設定（1 個敏感欄位）')
    expect(wrapper.text()).not.toContain('勾選為敏感的欄位')

    await openPrivacy(wrapper)
    expect(labels().slice(3)).toEqual(['region（string）敏感', 'memo（string）敏感', 'qty（number）敏感'])
    expect(wrapper.text()).toContain('勾選為敏感的欄位：不會把樣本和相異值送給 JEV，用到它的卡片也不做異常檢查。')
  })

  it('picking a field of the selected datasource adds it to picked; on another datasource it selects that one first', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    const wrapper = mount(DatasourcePanel, { props: { selectedId: 'ds1', picked: ['qty'] }, global: { plugins: [vuetify] } })
    await flushPromises()

    expect(pickBoxes(wrapper)[2].element.checked).toBe(true)
    await pickBoxes(wrapper)[0].setValue(true)
    expect(wrapper.emitted('update:picked').at(-1)).toEqual([['qty', 'region']])
    expect(wrapper.emitted('select')).toBeUndefined()

    await wrapper.setProps({ selectedId: 'other', picked: [] })
    await pickBoxes(wrapper)[1].setValue(true)
    expect(wrapper.emitted('select')).toEqual([['ds1']])
    expect(wrapper.emitted('update:picked').at(-1)).toEqual([['memo']])
  })

  it('uploads the selected file as FormData with name/sheet, and emits select on success', async () => {
    api.listDatasources.mockResolvedValue([ds1])
    api.uploadDatasource.mockResolvedValue({ id: 'ds2' })

    const wrapper = mountPanel()
    await flushPromises()

    const file = new File(['a,b'], 'sales.csv', { type: 'text/csv' })
    await wrapper.findComponent({ name: 'VFileInput' }).vm.$emit('update:modelValue', file)

    const uploadBtn = wrapper.findAll('button').find((b) => b.text() === '上傳')
    await uploadBtn.trigger('click')
    await flushPromises()

    expect(api.uploadDatasource).toHaveBeenCalledTimes(1)
    const form1 = api.uploadDatasource.mock.calls[0][0]
    expect(form1.get('file')).toStrictEqual(file)
    expect(form1.get('name')).toBe('sales.csv')
    expect(form1.has('sheet')).toBe(false)
    expect(wrapper.emitted('select')[0]).toEqual(['ds2'])

    const sheetField = wrapper.findAllComponents({ name: 'VTextField' }).find((c) => c.props('label') === '工作表（Excel 選填）')
    await sheetField.find('input').setValue('Sheet2')
    await uploadBtn.trigger('click')
    await flushPromises()

    expect(api.uploadDatasource).toHaveBeenCalledTimes(2)
    const form2 = api.uploadDatasource.mock.calls[1][0]
    expect(form2.get('sheet')).toBe('Sheet2')
  })

  it('upload(): shows an error and does not call the API when no file is selected', async () => {
    api.listDatasources.mockResolvedValue([])
    const wrapper = mountPanel()
    await flushPromises()

    const uploadBtn = wrapper.findAll('button').find((b) => b.text() === '上傳')
    await uploadBtn.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('請先選擇檔案')
    expect(api.uploadDatasource).not.toHaveBeenCalled()
  })

  it('upload(): shows an error and does not call the API when the file exceeds 50MB', async () => {
    api.listDatasources.mockResolvedValue([])
    const wrapper = mountPanel()
    await flushPromises()

    const bigFile = new File(['x'], 'big.csv', { type: 'text/csv' })
    Object.defineProperty(bigFile, 'size', { value: 50 * 1024 * 1024 + 1 })
    await wrapper.findComponent({ name: 'VFileInput' }).vm.$emit('update:modelValue', bigFile)

    const uploadBtn = wrapper.findAll('button').find((b) => b.text() === '上傳')
    await uploadBtn.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('檔案太大，上限 50MB')
    expect(api.uploadDatasource).not.toHaveBeenCalled()
  })

  it('shows a JSON hint/example and the DS_-prefixed label on the headers field', async () => {
    api.listDatasources.mockResolvedValue([])
    const wrapper = mountPanel()
    await flushPromises()
    await openApiTab(wrapper)

    const headersField = wrapper.findComponent({ name: 'VTextarea' })
    expect(headersField.props('label')).toBe('headers（JSON，值只能寫 ${DS_環境變數名}）')
    expect(wrapper.text()).toContain('{"Authorization":"${DS_ERP_AUTH}"}')
  })

  it('register() sends {name, url, headers} parsed from the textarea', async () => {
    api.listDatasources.mockResolvedValue([])
    api.registerApi.mockResolvedValue({ id: 'ds9' })

    const wrapper = mountPanel()
    await flushPromises()
    await openApiTab(wrapper)

    const nameField = wrapper.findAllComponents({ name: 'VTextField' }).find((c) => c.props('label') === '名稱')
    await nameField.find('input').setValue('erp')
    const urlField = wrapper.findAllComponents({ name: 'VTextField' }).find((c) => c.props('label') === '網址')
    await urlField.find('input').setValue('https://erp.example.com')
    const headersField = wrapper.findComponent({ name: 'VTextarea' })
    await headersField.find('textarea').setValue('{"Authorization":"${DS_ERP_AUTH}"}')

    const registerBtn = wrapper.findAll('button').find((b) => b.text() === '註冊')
    await registerBtn.trigger('click')
    await flushPromises()

    expect(api.registerApi).toHaveBeenCalledWith({
      name: 'erp',
      url: 'https://erp.example.com',
      headers: { Authorization: '${DS_ERP_AUTH}' },
    })
  })

  it('register(): invalid JSON in headers shows the Chinese error and does not call the API', async () => {
    api.listDatasources.mockResolvedValue([])

    const wrapper = mountPanel()
    await flushPromises()
    await openApiTab(wrapper)

    const headersField = wrapper.findComponent({ name: 'VTextarea' })
    await headersField.find('textarea').setValue('{not json')

    const registerBtn = wrapper.findAll('button').find((b) => b.text() === '註冊')
    await registerBtn.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('headers 必須是 JSON 物件')
    expect(api.registerApi).not.toHaveBeenCalled()
  })

  it('register(): valid but non-object JSON in headers (array/string) shows the Chinese error and does not call the API', async () => {
    api.listDatasources.mockResolvedValue([])

    const wrapper = mountPanel()
    await flushPromises()
    await openApiTab(wrapper)

    const headersField = wrapper.findComponent({ name: 'VTextarea' })

    await headersField.find('textarea').setValue('[]')
    const registerBtn = wrapper.findAll('button').find((b) => b.text() === '註冊')
    await registerBtn.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('headers 必須是 JSON 物件')

    await headersField.find('textarea').setValue('"x"')
    await registerBtn.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('headers 必須是 JSON 物件')

    expect(api.registerApi).not.toHaveBeenCalled()
  })

  it('上傳檔案 and 外部 API are not pages in a sequence: switching swaps the content in place, without the window slide', async () => {
    api.listDatasources.mockResolvedValue([])
    const wrapper = mountPanel()
    await flushPromises()
    const items = wrapper.findAllComponents({ name: 'VWindowItem' })
    expect(items.map((w) => [w.props('transition'), w.props('reverseTransition')])).toEqual([[false, false], [false, false]])
  })

  it('changing a display name sends every label with this one replaced, then refreshes; an unchanged value sends nothing', async () => {
    api.listDatasources.mockResolvedValue([{ ...ds1, schema: [{ ...ds1.schema[0], label: '地區' }, ...ds1.schema.slice(1)] }])
    api.setLabels.mockResolvedValue({})
    const wrapper = mountPanel()
    await flushPromises()
    const inputs = wrapper.findAll('input[type="text"]').filter((el) => el.attributes('placeholder'))

    await inputs[0].setValue('地區')
    await flushPromises()
    expect(api.setLabels).not.toHaveBeenCalled()

    await inputs[2].setValue('數量')
    await flushPromises()
    expect(api.setLabels).toHaveBeenCalledWith('ds1', { region: '地區', qty: '數量' })
    expect(api.listDatasources).toHaveBeenCalledTimes(2)
  })
})

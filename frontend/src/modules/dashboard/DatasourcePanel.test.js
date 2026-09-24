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

function checkboxes(wrapper) {
  return wrapper.findAll('input[type="checkbox"]')
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

    await checkboxes(wrapper)[0].setValue(true)
    await flushPromises()

    expect(wrapper.text()).toContain('找不到資料源')
    checkboxes(wrapper).forEach((el) => expect(el.element.disabled).toBe(false))
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
})

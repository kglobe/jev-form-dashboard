// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AnalyzeButton from './AnalyzeButton.vue'
import { api } from './api.js'
import { vuetify } from './test-vuetify.js'

vi.mock('./api.js', () => ({
  api: {
    analyze: vi.fn(),
    createDashboard: vi.fn(),
  },
}))

vi.mock('./DashboardView.vue', () => ({
  default: {
    name: 'DashboardView',
    props: { config: Object, preview: Boolean },
    template: '<div class="dashboard-view-stub" />',
  },
}))

function byText(root, selector, text) {
  return [...root.querySelectorAll(selector)].find((el) => el.textContent.trim() === text)
}

function mountButton(datasourceId) {
  return mount(AnalyzeButton, { props: { datasourceId }, global: { plugins: [vuetify] } })
}

describe('AnalyzeButton', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('disables 分析 when there is no datasource, enables it once one is selected', () => {
    const w1 = mountButton(null)
    expect(w1.get('button').element.disabled).toBe(true)

    const w2 = mountButton('ds1')
    expect(w2.get('button').element.disabled).toBe(false)
  })

  it('analyzes, previews in a dialog, and saves the previewed config', async () => {
    const previewConfig = { name: 'preview', layout: [] }
    api.analyze.mockResolvedValue(previewConfig)
    api.createDashboard.mockResolvedValue({ id: 'd9' })

    const wrapper = mountButton('ds1')
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(api.analyze).toHaveBeenCalledWith('ds1')
    expect(wrapper.findComponent({ name: 'VDialog' }).props('modelValue')).toBe(true)
    const dashboardView = wrapper.findComponent({ name: 'DashboardView' })
    expect(dashboardView.props('preview')).toBe(true)
    expect(dashboardView.props('config')).toEqual(previewConfig)

    const saveBtn = byText(document.body, 'button', '存為我的儀表板')
    saveBtn.click()
    await flushPromises()

    expect(api.createDashboard).toHaveBeenCalledWith(previewConfig)
    expect(wrapper.emitted('saved')).toEqual([['d9']])
    expect(wrapper.findComponent({ name: 'VDialog' }).props('modelValue')).toBe(false)
  })

  it('requires a dashboard name: labels the field, shows an error for blank/whitespace, and disables 存為我的儀表板', async () => {
    const previewConfig = { name: 'preview', layout: [] }
    api.analyze.mockResolvedValue(previewConfig)

    const wrapper = mountButton('ds1')
    await wrapper.get('button').trigger('click')
    await flushPromises()

    const nameField = wrapper.findComponent({ name: 'VTextField' })
    expect(nameField.props('label')).toBe('儀表板名稱')

    await nameField.find('input').setValue('   ')
    await flushPromises()

    expect(document.body.textContent).toContain('請輸入儀表板名稱')
    const saveBtn = byText(document.body, 'button', '存為我的儀表板')
    expect(saveBtn.disabled).toBe(true)
  })

  it('存為我的儀表板 guards against double submission: only one createDashboard call goes out', async () => {
    const previewConfig = { name: 'preview', layout: [] }
    api.analyze.mockResolvedValue(previewConfig)
    let resolveCreate
    api.createDashboard.mockImplementation(() => new Promise((resolve) => { resolveCreate = resolve }))

    const wrapper = mountButton('ds1')
    await wrapper.get('button').trigger('click')
    await flushPromises()

    const saveBtn = byText(document.body, 'button', '存為我的儀表板')
    saveBtn.click()
    saveBtn.click()
    await flushPromises()

    expect(api.createDashboard).toHaveBeenCalledTimes(1)

    resolveCreate({ id: 'd9' })
    await flushPromises()

    expect(wrapper.emitted('saved')).toEqual([['d9']])
  })

  it('shows the error message and keeps the dialog closed when analyze rejects', async () => {
    api.analyze.mockRejectedValue(new Error('未設定 TYPESAFE_API_KEY'))

    const wrapper = mountButton('ds1')
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('未設定 TYPESAFE_API_KEY')
    expect(wrapper.findComponent({ name: 'VDialog' }).props('modelValue')).toBe(false)
  })
})

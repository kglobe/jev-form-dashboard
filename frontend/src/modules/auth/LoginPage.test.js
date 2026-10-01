// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import LoginPage from './LoginPage.vue'
import { api } from '../dashboard/api.js'
import { vuetify } from '../dashboard/test-vuetify.js'

vi.mock('../dashboard/api.js', () => ({ api: { login: vi.fn(), authOptions: vi.fn() } }))

enableAutoUnmount(afterEach)

async function mountPage(props = {}) {
  const w = mount(LoginPage, { props: { error: null, isLight: true, ...props }, global: { plugins: [vuetify] } })
  await flushPromises()
  return w
}
const field = (w, label) => w.findAllComponents({ name: 'VTextField' }).find((c) => c.props('label') === label)
const btn = (w, label) => w.findAll('button').find((b) => b.text() === label || b.attributes('aria-label') === label)

describe('LoginPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.authOptions.mockResolvedValue({ oidc: null })
  })
  afterEach(() => history.replaceState(null, '', '/'))

  it('有「JEV 儀表板」、帳號、密碼和「登入」；按登入呼叫 api.login，成功 emit logged-in', async () => {
    api.login.mockResolvedValue(null)
    const w = await mountPage()
    expect(w.text()).toContain('JEV 儀表板')
    const user = field(w, '帳號').find('input')
    const pass = field(w, '密碼').find('input')
    expect(user.attributes('autocomplete')).toBe('username')
    expect(pass.attributes('autocomplete')).toBe('current-password')
    expect(pass.attributes('type')).toBe('password')
    await user.setValue('u1')
    await pass.setValue('pw')
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(api.login).toHaveBeenCalledWith('u1', 'pw')
    expect(w.emitted('logged-in')).toHaveLength(1)
  })

  it('登入失敗直接顯示後端的訊息', async () => {
    api.login.mockRejectedValue(new Error('帳號或密碼錯誤'))
    const w = await mountPage()
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(w.find('.v-alert').text()).toContain('帳號或密碼錯誤')
    expect(w.emitted('logged-in')).toBeUndefined()
  })

  it('有 OIDC 才有單一登入按鈕', async () => {
    api.authOptions.mockResolvedValue({ oidc: { label: '公司帳號' } })
    expect(btn(await mountPage(), '用公司帳號登入')).toBeTruthy()

    api.authOptions.mockResolvedValue({ oidc: null })
    expect(btn(await mountPage(), '用公司帳號登入')).toBeUndefined()

    api.authOptions.mockRejectedValue(new Error('無法連線到伺服器'))
    const w = await mountPage()
    expect(btn(w, '用公司帳號登入')).toBeUndefined()
    expect(btn(w, '登入')).toBeTruthy()
  })

  it('網址有 login_error 時顯示對應的訊息', async () => {
    history.replaceState(null, '', '/?login_error=account_disabled')
    const w = await mountPage()
    expect(w.find('.v-alert').text()).toContain('帳號已停用，請聯絡管理員')
    expect(location.search).toBe('')
  })

  it('error prop 有值時只顯示訊息和「重試」，不顯示表單；按「重試」emit retry', async () => {
    const w = await mountPage({ error: '無法連線到伺服器' })
    expect(w.find('.v-alert').text()).toContain('無法連線到伺服器')
    expect(w.find('form').exists()).toBe(false)
    expect(field(w, '帳號')).toBeUndefined()
    expect(btn(w, '登入')).toBeUndefined()
    await btn(w, '重試').trigger('click')
    expect(w.emitted('retry')).toHaveLength(1)
  })

  it('右上角的主題鈕 emit toggle-theme', async () => {
    const w = await mountPage({ isLight: true })
    await btn(w, '切換成深色主題').trigger('click')
    expect(w.emitted('toggle-theme')).toHaveLength(1)
    await w.setProps({ isLight: false })
    expect(btn(w, '切換成淺色主題')).toBeTruthy()
  })
})

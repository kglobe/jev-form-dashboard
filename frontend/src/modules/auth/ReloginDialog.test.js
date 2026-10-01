// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ReloginDialog from './ReloginDialog.vue'
import { api } from '../dashboard/api.js'
import { resetAndReload } from './session.js'
import { vuetify } from '../dashboard/test-vuetify.js'

vi.mock('../dashboard/api.js', () => ({ api: { login: vi.fn(), me: vi.fn(), authOptions: vi.fn() } }))
vi.mock('./session.js', async (orig) => ({ ...(await orig()), resetAndReload: vi.fn() }))

enableAutoUnmount(afterEach)

const PASSWORD_USER = { username: 'u1', admin: false, password: true }

async function mountDialog(me = PASSWORD_USER) {
  const w = mount(ReloginDialog, { props: { modelValue: true, me }, global: { plugins: [vuetify] } })
  await flushPromises()
  return w
}
// 對話框 teleport 到 document.body：元件用 findAllComponents 找，字和按鈕從 document.body 找
const field = (w, label) => w.findAllComponents({ name: 'VTextField' }).find((c) => c.props('label') === label)
const bodyButton = (label) => [...document.body.querySelectorAll('button')].find((b) => b.textContent.trim() === label)

async function loginAgain(w) {
  await field(w, '密碼').find('input').setValue('pw')
  await w.findComponent({ name: 'LoginForm' }).find('form').trigger('submit')
  await flushPromises()
}

describe('ReloginDialog', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.authOptions.mockResolvedValue({ oidc: null })
    api.login.mockResolvedValue(null)
  })
  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('有密碼的帳號：標題、說明、帳號欄預先填好目前的帳號', async () => {
    const w = await mountDialog()
    expect(document.body.textContent).toContain('請重新登入')
    expect(document.body.textContent).toContain('登入已經逾時，請重新登入。畫面上的東西會留著。')
    expect(field(w, '帳號').find('input').element.value).toBe('u1')
    expect(field(w, '密碼')).toBeTruthy()
    expect(api.authOptions).toHaveBeenCalledOnce()
  })

  it('登入後 me 還是同一個帳號：emit done，不重新載入', async () => {
    api.me.mockResolvedValue({ username: 'u1', admin: false, password: true })
    const w = await mountDialog()
    await loginAgain(w)
    expect(api.login).toHaveBeenCalledWith('u1', 'pw')
    expect(api.me).toHaveBeenCalledOnce()
    expect(w.emitted('done')).toHaveLength(1)
    expect(resetAndReload).not.toHaveBeenCalled()
  })

  it('換了另一個帳號：呼叫 resetAndReload，不 emit done', async () => {
    api.me.mockResolvedValue({ username: 'u2', admin: false, password: true })
    const w = await mountDialog()
    await field(w, '帳號').find('input').setValue('u2')
    await loginAgain(w)
    expect(resetAndReload).toHaveBeenCalledOnce()
    expect(w.emitted('done')).toBeUndefined()
  })

  it('沒有密碼的帳號只看到單一登入按鈕', async () => {
    api.authOptions.mockResolvedValue({ oidc: { label: '公司帳號' } })
    const w = await mountDialog({ username: 'sso-a', admin: false, password: false })
    expect(document.body.textContent).toContain('請重新登入')
    expect(document.body.textContent).toContain('你用單一登入，請按下面的按鈕重新登入。「製圖」頁還沒存的預覽會不見。')
    expect(bodyButton('用公司帳號登入')).toBeTruthy()
    expect(field(w, '帳號')).toBeUndefined()
    expect(field(w, '密碼')).toBeUndefined()
    expect(bodyButton('登入')).toBeUndefined()
  })

  it('左下角「重新整理」呼叫 location.reload', async () => {
    const reload = vi.spyOn(window.location, 'reload').mockImplementation(() => {})
    await mountDialog()
    const button = bodyButton('重新整理')
    expect(button.classList.contains('v-btn--variant-text')).toBe(true)
    button.click()
    expect(reload).toHaveBeenCalledOnce()
  })

  it('對話框是 persistent，點外面不會關', async () => {
    const w = await mountDialog()
    const dialog = w.findComponent({ name: 'VDialog' })
    expect(dialog.props('persistent')).toBe(true)
    // Vuetify 的 click-outside 要先 mousedown 再 click，處理函式在 setTimeout 裡
    const scrim = document.body.querySelector('.v-overlay__scrim')
    scrim.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    scrim.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await new Promise((r) => setTimeout(r, 10))
    await flushPromises()
    expect(dialog.emitted('update:modelValue')).toBeUndefined()
    expect(document.body.textContent).toContain('請重新登入')
  })
})

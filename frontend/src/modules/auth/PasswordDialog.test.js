// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import PasswordDialog from './PasswordDialog.vue'
import { api } from '../dashboard/api.js'
import { vuetify } from '../dashboard/test-vuetify.js'

vi.mock('../dashboard/api.js', () => ({ api: { changePassword: vi.fn() } }))

enableAutoUnmount(afterEach)

async function mountDialog() {
  const w = mount(PasswordDialog, { props: { modelValue: true }, global: { plugins: [vuetify] } })
  await flushPromises()
  return w
}
// 對話框 teleport 到 document.body：元件用 findAllComponents 找，字和按鈕從 document.body 找
const field = (w, label) => w.findAllComponents({ name: 'VTextField' }).find((c) => c.props('label') === label)
const bodyButtons = () => [...document.body.querySelectorAll('button')].map((b) => b.textContent.trim())
const bodyButton = (label) => [...document.body.querySelectorAll('button')].find((b) => b.textContent.trim() === label)

async function fill(w, current, password, again) {
  await field(w, '目前的密碼').find('input').setValue(current)
  await field(w, '新密碼').find('input').setValue(password)
  await field(w, '再輸入一次新密碼').find('input').setValue(again)
}
async function submit(w) {
  await w.findComponent({ name: 'VForm' }).find('form').trigger('submit')
  await flushPromises()
}

describe('PasswordDialog', () => {
  beforeEach(() => vi.resetAllMocks())
  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('三個欄位、提示和 autocomplete', async () => {
    const w = await mountDialog()
    expect(document.body.textContent).toContain('改密碼')
    expect(w.findComponent({ name: 'VDialog' }).props('maxWidth')).toBe('420')
    const input = (label) => field(w, label).find('input')
    expect(input('目前的密碼').attributes('autocomplete')).toBe('current-password')
    expect(input('新密碼').attributes('autocomplete')).toBe('new-password')
    expect(input('再輸入一次新密碼').attributes('autocomplete')).toBe('new-password')
    for (const label of ['目前的密碼', '新密碼', '再輸入一次新密碼']) expect(input(label).attributes('type')).toBe('password')
    expect(field(w, '新密碼').props('hint')).toBe('至少 12 個字元')
    expect(field(w, '目前的密碼').props('autofocus')).toBe(true)
    expect(bodyButton('取消')).toBeTruthy()
    expect(bodyButton('更新密碼')).toBeTruthy()
  })

  it('兩次新密碼不一樣時不送出，顯示「兩次輸入的新密碼不一樣」', async () => {
    const w = await mountDialog()
    await fill(w, 'old-password-1', 'new-password-1', 'new-password-2')
    await submit(w)
    expect(api.changePassword).not.toHaveBeenCalled()
    expect(w.findComponent({ name: 'VAlert' }).text()).toContain('兩次輸入的新密碼不一樣')
  })

  it('失敗時錯誤顯示在標題下面', async () => {
    api.changePassword.mockRejectedValue(new Error('目前的密碼不對'))
    const w = await mountDialog()
    await fill(w, 'wrong', 'new-password-1', 'new-password-1')
    await submit(w)
    expect(api.changePassword).toHaveBeenCalledWith('wrong', 'new-password-1')
    expect(w.findComponent({ name: 'VAlert' }).text()).toContain('目前的密碼不對')
    expect(w.emitted('update:modelValue')).toBeUndefined()
    expect(field(w, '目前的密碼')).toBeTruthy()
  })

  it('成功後只剩「密碼已更新，請用新密碼重新登入」和「重新登入」，按了 location.reload', async () => {
    api.changePassword.mockResolvedValue(null)
    const reload = vi.spyOn(window.location, 'reload').mockImplementation(() => {})
    const w = await mountDialog()
    expect(w.findComponent({ name: 'VDialog' }).props('persistent')).toBe(false)
    await fill(w, 'old-password-1', 'new-password-1', 'new-password-1')
    await submit(w)
    expect(document.body.textContent).toContain('密碼已更新，請用新密碼重新登入')
    expect(bodyButtons()).toEqual(['重新登入'])
    expect(w.findAllComponents({ name: 'VTextField' })).toHaveLength(0)
    expect(w.findComponent({ name: 'VDialog' }).props('persistent')).toBe(true)
    bodyButton('重新登入').click()
    expect(reload).toHaveBeenCalledOnce()
  })

  it('在最後一個欄位按 Enter 也會送出', async () => {
    api.changePassword.mockResolvedValue(null)
    const w = await mountDialog()
    await fill(w, 'old-password-1', 'new-password-1', 'new-password-1')
    // happy-dom 沒有做「按 Enter 送出表單」：瀏覽器的做法是按下表單裡的 submit 按鈕，這裡照做
    const last = field(w, '再輸入一次新密碼').find('input').element
    const submitter = last.form.querySelector('button[type="submit"]')
    expect(submitter.textContent.trim()).toBe('更新密碼')
    last.form.requestSubmit(submitter)
    await flushPromises()
    expect(api.changePassword).toHaveBeenCalledWith('old-password-1', 'new-password-1')
  })
})

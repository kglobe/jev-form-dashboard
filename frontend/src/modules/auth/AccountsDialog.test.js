// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import AccountsDialog from './AccountsDialog.vue'
import { api } from '../dashboard/api.js'
import { vuetify } from '../dashboard/test-vuetify.js'

vi.mock('../dashboard/api.js', () => ({
  api: { listAccounts: vi.fn(), createAccount: vi.fn(), updateAccount: vi.fn(), setAccountPassword: vi.fn() },
}))

enableAutoUnmount(afterEach)

const ME = { username: 'boss', admin: true, password: true }
const acct = (username, o = {}) => ({
  username,
  admin: false,
  enabled: true,
  password: true,
  sso: false,
  createdAt: '2026-10-01T08:00:00Z',
  ...o,
})
const ACCOUNTS = () => [acct('boss', { admin: true }), acct('sso-a', { password: false, sso: true }), acct('u1')]

async function mountDialog() {
  const w = mount(AccountsDialog, { props: { modelValue: true, me: ME }, global: { plugins: [vuetify] } })
  await flushPromises()
  return w
}
// 對話框 teleport 到 document.body：元件用 findAllComponents 找，表格和按鈕從 document.body 找
const field = (w, label) => w.findAllComponents({ name: 'VTextField' }).find((c) => c.props('label') === label)
const rows = () => [...document.body.querySelectorAll('tbody tr')]
const cells = (tr) => [...tr.querySelectorAll('td')].map((td) => td.textContent.trim())
const row = (name) => rows().find((tr) => tr.cells[0].textContent.trim() === name)
const buttons = (el) => [...el.querySelectorAll('button')].map((b) => b.textContent.trim())
const button = (el, label) => [...el.querySelectorAll('button')].find((b) => b.textContent.trim() === label)
const alert = (w) => w.findComponent({ name: 'VAlert' })

// happy-dom 沒有做「按 Enter 送出表單」：瀏覽器的做法是按下表單裡的 submit 按鈕，這裡照做
async function pressEnter(input, submitLabel) {
  const submitter = input.form.querySelector('button[type="submit"]')
  expect(submitter.textContent.trim()).toBe(submitLabel)
  input.form.requestSubmit(submitter)
  await flushPromises()
}

describe('AccountsDialog', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.listAccounts.mockResolvedValue(ACCOUNTS())
  })
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('表格的欄位和每一列的身分、登入方式、狀態', async () => {
    api.listAccounts.mockResolvedValue([...ACCOUNTS(), acct('u2', { sso: true, enabled: false })])
    const w = await mountDialog()
    expect(api.listAccounts).toHaveBeenCalledOnce()
    expect(w.findComponent({ name: 'VDialog' }).props('maxWidth')).toBe('720')
    expect(document.body.querySelector('.v-card-title').textContent.trim()).toBe('帳號管理')
    const headers = [...document.body.querySelectorAll('thead th')].map((th) => th.textContent.trim())
    expect(headers).toEqual(['帳號', '身分', '登入方式', '狀態', '動作'])
    expect(rows().map((tr) => cells(tr).slice(0, 4))).toEqual([
      ['boss（你）', '管理員', '密碼', '啟用'],
      ['sso-a', '一般', '單一登入', '啟用'],
      ['u1', '一般', '密碼', '啟用'],
      ['u2', '一般', '兩者', '停用'],
    ])
  })

  it('自己那一列加「（你）」，沒有停用、取消管理員、重設密碼', async () => {
    await mountDialog()
    expect(buttons(row('boss（你）'))).toEqual([])
    expect(buttons(row('u1'))).toEqual(['重設密碼', '停用', '設成管理員'])
  })

  it('沒有密碼的帳號按鈕文字是「設定密碼」', async () => {
    await mountDialog()
    expect(buttons(row('sso-a'))).toEqual(['設定密碼', '停用', '設成管理員'])
  })

  it('新增帳號：送出 { username, password, admin }，成功清空這一列並重抓清單；按 Enter 等於按「新增帳號」', async () => {
    const w = await mountDialog()
    const username = field(w, '帳號').find('input')
    const password = field(w, '初始密碼').find('input')
    expect(username.attributes('autocomplete')).toBe('off')
    expect(field(w, '帳號').props('autofocus')).toBe(true)
    expect(password.attributes('autocomplete')).toBe('new-password')
    expect(password.attributes('type')).toBe('password')

    api.createAccount.mockResolvedValue(acct('alice', { admin: true }))
    api.listAccounts.mockResolvedValue([...ACCOUNTS(), acct('alice', { admin: true })])
    await username.setValue('alice')
    await password.setValue('initial-pass-1')
    const admin = w.findAllComponents({ name: 'VCheckbox' }).find((c) => c.props('label') === '管理員').find('input')
    admin.element.checked = true
    await admin.trigger('input')
    await pressEnter(password.element, '新增帳號')

    expect(api.createAccount).toHaveBeenCalledWith({ username: 'alice', password: 'initial-pass-1', admin: true })
    expect(api.listAccounts).toHaveBeenCalledTimes(2)
    expect(username.element.value).toBe('')
    expect(password.element.value).toBe('')
    expect(admin.element.checked).toBe(false)
    expect(cells(row('alice')).slice(0, 4)).toEqual(['alice', '管理員', '密碼', '啟用'])
    expect(alert(w).exists()).toBe(false)
  })

  it('新增失敗時錯誤顯示在標題下面', async () => {
    api.createAccount.mockRejectedValue(new Error('帳號「alice」已經存在'))
    const w = await mountDialog()
    await field(w, '帳號').find('input').setValue('alice')
    await field(w, '初始密碼').find('input').setValue('initial-pass-1')
    await button(document.body, '新增帳號').click()
    await flushPromises()

    expect(api.createAccount).toHaveBeenCalledWith({ username: 'alice', password: 'initial-pass-1', admin: false })
    expect(alert(w).props('type')).toBe('error')
    expect(alert(w).text()).toContain('帳號「alice」已經存在')
    // 標題下面、新增列上面
    const title = document.body.querySelector('.v-card-title')
    expect(title.compareDocumentPosition(alert(w).element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(alert(w).element.compareDocumentPosition(field(w, '帳號').element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // 失敗不清空，改一下就能再送
    expect(field(w, '帳號').find('input').element.value).toBe('alice')
    expect(api.listAccounts).toHaveBeenCalledOnce()
  })

  it('重設密碼：展開一列，儲存後收起來，顯示「已重設「u1」的密碼」', async () => {
    api.setAccountPassword.mockResolvedValue(null)
    const w = await mountDialog()

    // 「取消」收起來，不送出
    button(row('u1'), '重設密碼').click()
    await flushPromises()
    expect(rows()).toHaveLength(4)
    button(document.body, '取消').click()
    await flushPromises()
    expect(rows()).toHaveLength(3)

    button(row('u1'), '重設密碼').click()
    await flushPromises()
    const expanded = row('u1').nextElementSibling
    expect(expanded.cells[0].getAttribute('colspan')).toBe('5')
    const input = expanded.querySelector('input')
    expect(input.getAttribute('autocomplete')).toBe('new-password')
    expect(input.getAttribute('type')).toBe('password')
    input.value = 'reset-password-1'
    input.dispatchEvent(new Event('input'))
    await pressEnter(input, '儲存')

    expect(api.setAccountPassword).toHaveBeenCalledWith('u1', 'reset-password-1')
    expect(rows()).toHaveLength(3)
    expect(alert(w).props('type')).toBe('success')
    expect(alert(w).text()).toBe('已重設「u1」的密碼')

    // 沒有密碼的帳號：成功是「已設定」，之後就是兩種方式都能登入
    button(row('sso-a'), '設定密碼').click()
    await flushPromises()
    const ssoInput = row('sso-a').nextElementSibling.querySelector('input')
    ssoInput.value = 'reset-password-2'
    ssoInput.dispatchEvent(new Event('input'))
    button(document.body, '儲存').click()
    await flushPromises()
    expect(api.setAccountPassword).toHaveBeenCalledWith('sso-a', 'reset-password-2')
    expect(alert(w).text()).toBe('已設定「sso-a」的密碼')
    expect(cells(row('sso-a'))[2]).toBe('兩者')
    expect(buttons(row('sso-a'))[0]).toBe('重設密碼')
  })

  it('停用／啟用、設成管理員／取消管理員用 PATCH，回來的帳號換掉那一列，不另外確認', async () => {
    const w = await mountDialog()
    api.updateAccount.mockResolvedValueOnce(acct('u1', { enabled: false }))
    button(row('u1'), '停用').click()
    await flushPromises()
    expect(api.updateAccount).toHaveBeenLastCalledWith('u1', { enabled: false })
    expect(cells(row('u1')).slice(0, 4)).toEqual(['u1', '一般', '密碼', '停用'])
    expect(buttons(row('u1'))).toEqual(['重設密碼', '啟用', '設成管理員'])

    api.updateAccount.mockResolvedValueOnce(acct('u1', { enabled: false, admin: true }))
    button(row('u1'), '設成管理員').click()
    await flushPromises()
    expect(api.updateAccount).toHaveBeenLastCalledWith('u1', { admin: true })
    expect(buttons(row('u1'))).toEqual(['重設密碼', '啟用', '取消管理員'])

    api.updateAccount.mockResolvedValueOnce(acct('u1', { admin: true }))
    button(row('u1'), '啟用').click()
    await flushPromises()
    expect(api.updateAccount).toHaveBeenLastCalledWith('u1', { enabled: true })

    api.updateAccount.mockResolvedValueOnce(acct('u1'))
    button(row('u1'), '取消管理員').click()
    await flushPromises()
    expect(api.updateAccount).toHaveBeenLastCalledWith('u1', { admin: false })
    expect(cells(row('u1')).slice(0, 4)).toEqual(['u1', '一般', '密碼', '啟用'])
    expect(api.updateAccount).toHaveBeenCalledTimes(4)
    expect(api.listAccounts).toHaveBeenCalledOnce()

    // 失敗時錯誤顯示在標題下面，那一列不變
    api.updateAccount.mockRejectedValueOnce(new Error('找不到帳號'))
    button(row('u1'), '停用').click()
    await flushPromises()
    expect(alert(w).props('type')).toBe('error')
    expect(alert(w).text()).toContain('找不到帳號')
    expect(cells(row('u1'))[3]).toBe('啟用')
  })

  it('密碼框的眼睛圖示切換顯示，aria-label 是「顯示密碼」／「隱藏密碼」', async () => {
    const w = await mountDialog()
    const initial = field(w, '初始密碼')
    const eye = initial.find('button')
    expect(eye.attributes('aria-label')).toBe('顯示密碼')
    expect(eye.find('.mdi-eye-outline').exists()).toBe(true)
    await eye.trigger('click')
    expect(initial.find('input').attributes('type')).toBe('text')
    expect(eye.attributes('aria-label')).toBe('隱藏密碼')
    expect(eye.find('.mdi-eye-off-outline').exists()).toBe(true)
    await eye.trigger('click')
    expect(initial.find('input').attributes('type')).toBe('password')

    // 展開的密碼框也有；按眼睛不會送出
    button(row('u1'), '重設密碼').click()
    await flushPromises()
    const expanded = row('u1').nextElementSibling
    const resetEye = expanded.querySelector('button[aria-label="顯示密碼"]')
    expect(resetEye.querySelector('.mdi-eye-outline')).toBeTruthy()
    resetEye.click()
    await flushPromises()
    expect(expanded.querySelector('input').getAttribute('type')).toBe('text')
    expect(resetEye.getAttribute('aria-label')).toBe('隱藏密碼')
    expect(api.setAccountPassword).not.toHaveBeenCalled()
  })
})

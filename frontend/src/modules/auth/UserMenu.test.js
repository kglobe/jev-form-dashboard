// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { h } from 'vue'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import UserMenu from './UserMenu.vue'
import { api } from '../dashboard/api.js'
import { resetAndReload } from './session.js'
import { vuetify } from '../dashboard/test-vuetify.js'

vi.mock('../dashboard/api.js', () => ({ api: { logout: vi.fn(), changePassword: vi.fn() } }))
vi.mock('./session.js', async (orig) => ({ ...(await orig()), resetAndReload: vi.fn() }))

enableAutoUnmount(afterEach)

const BOSS = { username: 'boss', admin: true, password: true }

// activator 用測試自己的按鈕；選單內容 teleport 到 document.body
async function openMenu(me = BOSS) {
  const w = mount(UserMenu, {
    props: { me },
    slots: { activator: ({ props }) => h('button', { ...props, class: 'open', type: 'button' }, '帳號') },
    global: { plugins: [vuetify] },
  })
  await w.find('button.open').trigger('click')
  await flushPromises()
  return w
}
const items = () => [...document.body.querySelectorAll('.v-overlay-container .v-list-item')]
const itemTexts = () => items().map((el) => el.textContent.trim())
const item = (label) => items().find((el) => el.textContent.trim() === label)

describe('UserMenu', () => {
  beforeEach(() => vi.resetAllMocks())
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('第一行是帳號名稱，管理員後面加「· 管理員」，不能點', async () => {
    await openMenu()
    const first = items()[0]
    expect(first.textContent.trim()).toBe('boss · 管理員')
    expect(first.classList.contains('v-list-item--disabled')).toBe(true)
  })

  it('有密碼才有「改密碼」，管理員才有「帳號管理」', async () => {
    const w = await openMenu({ username: 'u1', admin: false, password: false })
    expect(itemTexts()).toEqual(['u1', '登出'])
    w.unmount()
    document.body.innerHTML = ''

    await openMenu({ username: 'u1', admin: false, password: true })
    expect(itemTexts()).toEqual(['u1', '改密碼', '登出'])
  })

  it('「登出」呼叫 api.logout，接著 resetAndReload；logout 失敗也一樣 reload', async () => {
    api.logout.mockResolvedValue(null)
    const w = await openMenu()
    item('登出').click()
    await flushPromises()
    expect(api.logout).toHaveBeenCalledOnce()
    expect(resetAndReload).toHaveBeenCalledOnce()
    w.unmount()
    document.body.innerHTML = ''

    api.logout.mockRejectedValue(new Error('無法連線到伺服器'))
    await openMenu()
    item('登出').click()
    await flushPromises()
    expect(api.logout).toHaveBeenCalledTimes(2)
    expect(resetAndReload).toHaveBeenCalledTimes(2)
  })

  it('「改密碼」打開改密碼對話框', async () => {
    const w = await openMenu()
    const dialog = w.findComponent({ name: 'PasswordDialog' })
    expect(dialog.props('modelValue')).toBe(false)
    item('改密碼').click()
    await flushPromises()
    expect(dialog.props('modelValue')).toBe(true)
    expect(document.body.textContent).toContain('再輸入一次新密碼')
  })
})

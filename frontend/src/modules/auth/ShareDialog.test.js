// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ShareDialog from './ShareDialog.vue'
import { api } from '../dashboard/api.js'
import { vuetify } from '../dashboard/test-vuetify.js'

vi.mock('../dashboard/api.js', () => ({ api: { getShare: vi.fn(), setShare: vi.fn() } }))

enableAutoUnmount(afterEach)

const ID = '11111111-2222-3333-4444-555555555555'
const COPY_HINT = '瀏覽器不讓網頁直接複製，請按 Ctrl+C'

async function mountDialog() {
  const w = mount(ShareDialog, { props: { modelValue: true, dashboard: { id: ID, name: '各區營收' } }, global: { plugins: [vuetify] } })
  await flushPromises()
  return w
}
// 對話框 teleport 到 document.body：元件用 findAllComponents 找，字和按鈕從 document.body 找
const userField = (w) => w.findAllComponents({ name: 'VTextField' }).find((c) => c.props('placeholder') === '輸入對方的帳號')
const linkField = (w) => w.findAllComponents({ name: 'VTextField' }).find((c) => c.props('readonly'))
const chips = () => [...document.body.querySelectorAll('.v-chip')].map((c) => c.textContent.trim())
const bodyButton = (label) => [...document.body.querySelectorAll('button')].find((b) => b.textContent.trim() === label)

// happy-dom 沒有做「按 Enter 送出表單」：瀏覽器的做法是按下表單裡的 submit 按鈕，這裡照做
async function pressEnter(input, submitLabel) {
  const submitter = input.form.querySelector('button[type="submit"]')
  expect(submitter.textContent.trim()).toBe(submitLabel)
  input.form.requestSubmit(submitter)
  await flushPromises()
}

describe('ShareDialog', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.getShare.mockResolvedValue({ publicLink: false, users: ['alice'] })
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    document.body.innerHTML = ''
    history.replaceState(null, '', '/')
  })

  it('標題、公開連結的開關和說明、分享給的輸入框、提示和提醒文字', async () => {
    const w = await mountDialog()
    expect(api.getShare).toHaveBeenCalledWith(ID)
    expect(w.findComponent({ name: 'VDialog' }).props('maxWidth')).toBe('480')
    expect(document.body.querySelector('.v-card-title').textContent.trim()).toBe('分享「各區營收」')
    const text = document.body.textContent
    expect(text).toContain('公開連結')
    expect(text).toContain('分享給')
    const toggle = w.findComponent({ name: 'VSwitch' })
    expect(toggle.props('label')).toBe('有帳號的人拿到連結就能看')
    expect(toggle.find('input').element.checked).toBe(false)
    // spec 7.11：打開時焦點在「分享給」的輸入框
    expect(userField(w).props('autofocus')).toBe(true)
    expect(userField(w).props('hint')).toBe('對方的帳號在他帳號選單的第一行')
    expect(text).toContain('對方的帳號在他帳號選單的第一行')
    expect(document.body.querySelector('.cc-desc').textContent.trim()).toBe('對方看到的資料跟你一樣，包括明細表和敏感欄位。')
    expect(chips()).toEqual(['alice'])
    expect(bodyButton('取消')).toBeTruthy()
    expect(bodyButton('儲存').classList.contains('bg-primary')).toBe(true)
  })

  it('加入：按 Enter 等於「加入」，加入後焦點留在輸入框；重複的不加', async () => {
    const w = await mountDialog()
    const input = userField(w).find('input')
    await input.setValue('  Bob ')
    await pressEnter(input.element, '加入')
    expect(chips()).toEqual(['alice', 'bob'])
    expect(input.element.value).toBe('')
    expect(document.activeElement).toBe(input.element)

    // 按「加入」鈕：焦點回到輸入框；轉小寫後跟已加入的一樣，不再加
    await input.setValue('ALICE')
    input.element.blur()
    bodyButton('加入').click()
    await flushPromises()
    expect(chips()).toEqual(['alice', 'bob'])
    expect(document.activeElement).toBe(input.element)

    // 空白的不加
    await input.setValue('   ')
    bodyButton('加入').click()
    await flushPromises()
    expect(chips()).toEqual(['alice', 'bob'])
  })

  it('chip 的 ✕ 移除，close-label 是「移除「alice」」；沒有人時顯示「還沒有分享給任何人」', async () => {
    await mountDialog()
    expect(document.body.textContent).not.toContain('還沒有分享給任何人')
    document.body.querySelector('button[aria-label="移除「alice」"]').click()
    await flushPromises()
    expect(chips()).toEqual([])
    expect(document.body.textContent).toContain('還沒有分享給任何人')
  })

  it('儲存送出 { publicLink, users }，成功關掉對話框', async () => {
    api.setShare.mockResolvedValue({ publicLink: true, users: ['alice', 'bob'] })
    const w = await mountDialog()
    const toggle = w.findComponent({ name: 'VSwitch' }).find('input')
    toggle.element.checked = true
    await toggle.trigger('input')
    await userField(w).find('input').setValue('bob')
    bodyButton('加入').click()
    await flushPromises()

    bodyButton('儲存').click()
    await flushPromises()
    expect(api.setShare).toHaveBeenCalledWith(ID, { publicLink: true, users: ['alice', 'bob'] })
    expect(w.emitted('update:modelValue')).toEqual([[false]])
  })

  it('儲存失敗時錯誤留在對話框裡，對話框不關', async () => {
    const msg = '找不到帳號「x」。用單一登入的人要先登入過一次才會有帳號，也可以先打開公開連結'
    api.setShare.mockRejectedValue(new Error(msg))
    const w = await mountDialog()
    bodyButton('儲存').click()
    await flushPromises()
    expect(w.findComponent({ name: 'VAlert' }).props('type')).toBe('error')
    expect(w.findComponent({ name: 'VAlert' }).text()).toBe(msg)
    expect(w.emitted('update:modelValue')).toBeUndefined()
    expect(chips()).toEqual(['alice'])
  })

  it('連結是 <網站網址>/#/d/<id>；複製成功後按鈕變成「已複製」2 秒', async () => {
    history.replaceState(null, '', '/app/?x=1#/d/old')
    const writeText = vi.fn().mockResolvedValue()
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const w = await mountDialog()
    const link = `${location.origin}/app/#/d/${ID}`
    expect(linkField(w).find('input').element.value).toBe(link)
    expect(bodyButton('複製連結').querySelector('.mdi-link-variant')).toBeTruthy()

    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    bodyButton('複製連結').click()
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith(link)
    expect(bodyButton('已複製')).toBeTruthy()
    expect(bodyButton('複製連結')).toBeUndefined()

    vi.advanceTimersByTime(1999)
    await flushPromises()
    expect(bodyButton('已複製')).toBeTruthy()
    vi.advanceTimersByTime(1)
    await flushPromises()
    expect(bodyButton('複製連結')).toBeTruthy()
    expect(document.body.textContent).not.toContain(COPY_HINT)
  })

  it('沒有 navigator.clipboard 或 writeText 失敗時，選取輸入框的文字並提示「瀏覽器不讓網頁直接複製，請按 Ctrl+C」', async () => {
    const select = vi.spyOn(HTMLInputElement.prototype, 'select')
    vi.stubGlobal('navigator', {})
    const w = await mountDialog()
    const input = linkField(w).find('input').element
    expect(document.body.textContent).not.toContain(COPY_HINT)
    bodyButton('複製連結').click()
    await flushPromises()
    expect(select.mock.contexts).toEqual([input])
    expect(document.body.textContent).toContain(COPY_HINT)
    expect(bodyButton('已複製')).toBeUndefined()

    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
    bodyButton('複製連結').click()
    await flushPromises()
    expect(select.mock.contexts).toEqual([input, input])
    expect(document.body.textContent).toContain(COPY_HINT)
    expect(bodyButton('已複製')).toBeUndefined()
  })
})

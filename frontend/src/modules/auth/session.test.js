// @vitest-environment happy-dom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { oidcButtonText, resetAndReload, restoreSavedHash, startOidc, takeLoginError } from './session.js'

const ID = '0f8fad5b-d9cb-469f-a165-70867728950e'
const KEY = 'jev-return-hash'

describe('session', () => {
  afterEach(() => {
    history.replaceState(null, '', '/')
    sessionStorage.clear()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('按鈕文字：label 開頭或結尾是英數字時那一側加空格', () => {
    expect(oidcButtonText('公司帳號')).toBe('用公司帳號登入')
    expect(oidcButtonText('Microsoft')).toBe('用 Microsoft 登入')
    expect(oidcButtonText('Entra 帳號')).toBe('用 Entra 帳號登入')
    expect(oidcButtonText('帳號 SSO')).toBe('用帳號 SSO 登入')
  })

  it('login_error 對到訊息，拿掉參數之後 hash 還在', () => {
    history.replaceState(null, '', `/?login_error=username_taken#/d/${ID}`)
    expect(takeLoginError()).toBe('這個帳號名稱已經有人用，不能再用單一登入建立，請聯絡管理員')
    expect(location.search).toBe('')
    expect(location.hash).toBe(`#/d/${ID}`)

    history.replaceState(null, '', '/?login_error=account_disabled')
    expect(takeLoginError()).toBe('帳號已停用，請聯絡管理員')
    history.replaceState(null, '', '/?login_error=no_username')
    expect(takeLoginError()).toBe('單一登入沒有提供帳號名稱，請聯絡管理員')
    history.replaceState(null, '', '/?login_error=whatever')
    expect(takeLoginError()).toBe('單一登入失敗，請再試一次')
    history.replaceState(null, '', '/?login_error=constructor')
    expect(takeLoginError()).toBe('單一登入失敗，請再試一次')
    expect(location.search).toBe('')
    expect(takeLoginError()).toBeNull()
  })

  it('restoreSavedHash：網址沒有 hash 才補回去，存的那筆一律刪掉', () => {
    // OIDC 失敗回來時網址是 /?login_error=…，補 hash 要留著 query，等一下 takeLoginError 才讀得到
    history.replaceState(null, '', '/?login_error=x')
    sessionStorage.setItem(KEY, `#/d/${ID}`)
    restoreSavedHash()
    expect(location.search).toBe('?login_error=x')
    expect(location.hash).toBe(`#/d/${ID}`)
    expect(sessionStorage.getItem(KEY)).toBeNull()

    const other = '1b4e28ba-2fa1-11d2-883f-0016d3cca427'
    history.replaceState(null, '', `/#/d/${other}`)
    sessionStorage.setItem(KEY, `#/d/${ID}`)
    restoreSavedHash()
    expect(location.hash).toBe(`#/d/${other}`)
    expect(sessionStorage.getItem(KEY)).toBeNull()
  })

  it('startOidc：先存合法的 #/d/<id> 再導過去；不合法的不存', () => {
    vi.stubGlobal('location', { hash: `#/d/${ID}`, href: '' })
    startOidc()
    expect(sessionStorage.getItem(KEY)).toBe(`#/d/${ID}`)
    expect(location.href).toBe('/oauth2/authorization/oidc')

    sessionStorage.clear()
    vi.stubGlobal('location', { hash: '#/d/0f8fad5b-d9cb', href: '' })
    startOidc()
    expect(sessionStorage.getItem(KEY)).toBeNull()
    expect(location.href).toBe('/oauth2/authorization/oidc')
  })

  it('resetAndReload：拿掉 hash 和存的那筆，再重新載入', () => {
    const reload = vi.spyOn(window.location, 'reload').mockImplementation(() => {})
    history.replaceState(null, '', `/#/d/${ID}`)
    sessionStorage.setItem(KEY, `#/d/${ID}`)
    resetAndReload()
    expect(location.hash).toBe('')
    expect(sessionStorage.getItem(KEY)).toBeNull()
    expect(reload).toHaveBeenCalledOnce()
  })
})

// 唯讀分享 spec 7.9：只認這種 hash
export const DASHBOARD_HASH = /^#\/d\/([0-9a-f-]{36})$/

// 唯讀分享 spec 7.1：單一登入導出去之前存分享連結，登入回來再補回去
const RETURN_HASH_KEY = 'jev-return-hash'

const LOGIN_ERRORS = new Map([
  ['account_disabled', '帳號已停用，請聯絡管理員'],
  ['username_taken', '這個帳號名稱已經有人用，不能再用單一登入建立，請聯絡管理員'],
  ['no_username', '單一登入沒有提供帳號名稱，請聯絡管理員'],
])

// sessionStorage 在私密視窗或封鎖網站資料時會丟例外，存不了就當作沒有
function storage(fn) {
  try {
    return fn(sessionStorage)
  } catch {
    return null
  }
}

// 唯讀分享 spec 7.1 開機第 1 步：存的那筆只用一次，網址已經有 hash 就不蓋掉
export function restoreSavedHash() {
  const saved = storage((s) => {
    const v = s.getItem(RETURN_HASH_KEY)
    s.removeItem(RETURN_HASH_KEY)
    return v
  })
  if (saved && !location.hash && DASHBOARD_HASH.test(saved)) {
    history.replaceState(null, '', location.pathname + location.search + saved)
  }
}

export function startOidc() {
  if (DASHBOARD_HASH.test(location.hash)) storage((s) => s.setItem(RETURN_HASH_KEY, location.hash))
  location.href = '/oauth2/authorization/oidc'
}

// 唯讀分享 spec 7.2、7.3：換了帳號或登出，不留前一個人的畫面
export function resetAndReload() {
  storage((s) => s.removeItem(RETURN_HASH_KEY))
  history.replaceState(null, '', location.pathname)
  location.reload()
}

// 唯讀分享 spec 7.1：label 開頭或結尾是英數字時，那一側加一個空格
export function oidcButtonText(label) {
  return `用${/^[A-Za-z0-9]/.test(label) ? ' ' : ''}${label}${/[A-Za-z0-9]$/.test(label) ? ' ' : ''}登入`
}

// 唯讀分享 spec 7.1：讀 ?login_error=，拿掉這個參數（hash 留著），回傳要顯示的訊息
export function takeLoginError() {
  const params = new URLSearchParams(location.search)
  const code = params.get('login_error')
  if (code == null) return null
  params.delete('login_error')
  const query = params.toString()
  history.replaceState(null, '', location.pathname + (query ? `?${query}` : '') + location.hash)
  return LOGIN_ERRORS.get(code) ?? '單一登入失敗，請再試一次'
}

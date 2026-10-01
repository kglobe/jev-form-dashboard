// 唯讀分享 spec 7.10：401 時通知 App 跳出重新登入；登入、開機的 me 自己處理 401
const SELF_HANDLED_401 = ['/api/auth/login', '/api/auth/me']
let unauthorized = () => {}
export function onUnauthorized(handler) {
  unauthorized = handler
}

function xsrfToken() {
  if (typeof document === 'undefined') return null // vitest 的 node 環境沒有 document
  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)
  return m ? decodeURIComponent(m[1]) : null
}

async function request(method, url, body) {
  // FormData、URLSearchParams 原樣送出，瀏覽器自己設 Content-Type
  const raw = body instanceof FormData || body instanceof URLSearchParams
  const headers = {}
  if (body != null && !raw) headers['Content-Type'] = 'application/json'
  // 每次都重讀：登入時 token 會換
  const token = method === 'GET' ? null : xsrfToken()
  if (token) headers['X-XSRF-TOKEN'] = token
  let res
  try {
    res = await fetch(url, {
      method,
      headers,
      body: body == null ? undefined : raw ? body : JSON.stringify(body),
    })
  } catch {
    throw new Error('無法連線到伺服器')
  }
  if (!res.ok) {
    if (res.status === 401 && !SELF_HANDLED_401.includes(url)) {
      unauthorized()
      throw Object.assign(new Error('登入逾時，重新登入後請再操作一次'), { status: 401 })
    }
    const err = await res.json().catch(() => ({}))
    // 帶上狀態碼，呼叫端才分得出「找不到」（例如儀表板在別的分頁被刪了）
    throw Object.assign(new Error(err.error || `HTTP ${res.status}`), { status: res.status })
  }
  return res.status === 204 ? null : res.json()
}

export const api = {
  // 唯讀分享 spec 4.1：登入
  me: () => request('GET', '/api/auth/me'),
  authOptions: () => request('GET', '/api/auth/options'),
  login: (username, password) => request('POST', '/api/auth/login', new URLSearchParams({ username, password })),
  logout: () => request('POST', '/api/auth/logout'),
  changePassword: (current, password) => request('POST', '/api/auth/password', { current, password }),
  // 唯讀分享 spec 4.2：帳號管理
  listAccounts: () => request('GET', '/api/admin/accounts'),
  createAccount: ({ username, password, admin }) => request('POST', '/api/admin/accounts', { username, password, admin }),
  updateAccount: (username, patch) => request('PATCH', `/api/admin/accounts/${encodeURIComponent(username)}`, patch),
  setAccountPassword: (username, password) =>
    request('PUT', `/api/admin/accounts/${encodeURIComponent(username)}/password`, { password }),
  // 唯讀分享 spec 4.3：分享
  getShare: (id) => request('GET', `/api/dashboards/${id}/share`),
  setShare: (id, { publicLink, users }) => request('PUT', `/api/dashboards/${id}/share`, { publicLink, users }),
  sharedDashboards: () => request('GET', '/api/dashboards/shared'),
  listDatasources: () => request('GET', '/api/datasources'),
  uploadDatasource: (form) => request('POST', '/api/datasources/upload', form),
  registerApi: (body) => request('POST', '/api/datasources/api', body),
  setSensitive: (id, fields) => request('PATCH', `/api/datasources/${id}/sensitive`, { fields }),
  setLabels: (id, labels) => request('PATCH', `/api/datasources/${id}/labels`, { labels }),
  // B3 spec 第 7 節：比率清單整份取代，新增、刪除都用這支
  setRatios: (id, ratios) => request('PATCH', `/api/datasources/${id}/ratios`, { ratios }),
  datasourceUsage: (id) => request('GET', `/api/datasources/${id}/usage`),
  deleteDatasource: (id) => request('DELETE', `/api/datasources/${id}`),
  previewMerged: (body) => request('POST', '/api/datasources/merged/preview', body),
  createMerged: (body) => request('POST', '/api/datasources/merged', body),
  // intents、current、fields 沒給時 JSON.stringify 會略過，body 跟原本一樣只有 datasourceId
  analyze: (datasourceId, intents, current, fields) => request('POST', '/api/jev/analyze', { datasourceId, intents, current, fields }),
  // C2 spec 第 4 節：inputs 的第一個是基準；compare 是對照
  suggestMapping: (inputs, compare) => request('POST', '/api/jev/mapping', { inputs, compare }),
  listDashboards: () => request('GET', '/api/dashboards'),
  getDashboard: (id) => request('GET', `/api/dashboards/${id}`),
  createDashboard: (config) => request('POST', '/api/dashboards', config),
  updateDashboard: (id, config) => request('PUT', `/api/dashboards/${id}`, config),
  // 多來源 spec 6.3：把預覽裡的一批圖接在既有儀表板最下面，回傳更新後的設定
  appendCharts: (id, batch) => request('POST', `/api/dashboards/${id}/charts`, batch),
  deleteDashboard: (id) => request('DELETE', `/api/dashboards/${id}`),
  dashboardData: (id) => request('GET', `/api/dashboards/${id}/data`),
  previewData: (config) => request('POST', '/api/dashboards/preview/data', config),
  highlights: (id) => request('GET', `/api/jev/highlight/${id}`),
}

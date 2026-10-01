import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api, onUnauthorized } from './api.js'

function jsonResponse(body, { status = 200 } = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  }
}

describe('api', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    onUnauthorized(() => {})
  })

  it('非 GET 請求從 cookie 帶 X-XSRF-TOKEN，GET 不帶，也不再有 X-User-Id', async () => {
    vi.stubGlobal('document', { cookie: 'a=1; XSRF-TOKEN=t%3D1' })
    fetch.mockResolvedValueOnce(jsonResponse([]))
    await api.listDatasources()
    const getHeaders = fetch.mock.calls[0][1].headers
    expect(getHeaders['X-XSRF-TOKEN']).toBeUndefined()
    expect(getHeaders['X-User-Id']).toBeUndefined()

    fetch.mockResolvedValueOnce(jsonResponse({}))
    await api.analyze('ds1')
    const postHeaders = fetch.mock.calls[1][1].headers
    expect(postHeaders['X-XSRF-TOKEN']).toBe('t=1')
    expect(postHeaders['X-User-Id']).toBeUndefined()
  })

  it('沒有 document 的時候（vitest 的 node 環境）照樣送出，不帶 token', async () => {
    expect(typeof document).toBe('undefined')
    fetch.mockResolvedValueOnce(jsonResponse({}))
    await api.analyze('ds1')
    expect(fetch.mock.calls[0][1].headers['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('401 先呼叫 onUnauthorized，再丟「登入逾時，重新登入後請再操作一次」，status 是 401', async () => {
    const handler = vi.fn()
    onUnauthorized(handler)
    fetch.mockResolvedValueOnce(jsonResponse({ error: '請先登入' }, { status: 401 }))
    await expect(api.listDashboards()).rejects.toMatchObject({
      message: '登入逾時，重新登入後請再操作一次',
      status: 401,
    })
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('/api/auth/login、/api/auth/me 的 401 不呼叫 onUnauthorized，訊息照後端', async () => {
    const handler = vi.fn()
    onUnauthorized(handler)
    fetch.mockResolvedValueOnce(jsonResponse({ error: '帳號或密碼錯誤' }, { status: 401 }))
    await expect(api.login('u1', 'pw')).rejects.toMatchObject({ message: '帳號或密碼錯誤', status: 401 })
    fetch.mockResolvedValueOnce(jsonResponse({ error: '請先登入' }, { status: 401 }))
    await expect(api.me()).rejects.toMatchObject({ message: '請先登入', status: 401 })
    expect(handler).not.toHaveBeenCalled()
  })

  it('login 用 URLSearchParams 原樣送出，不設 Content-Type', async () => {
    fetch.mockResolvedValueOnce(jsonResponse(null, { status: 204 }))
    await api.login('u1', 'pw')
    const [url, init] = fetch.mock.calls[0]
    expect(url).toBe('/api/auth/login')
    expect(init.method).toBe('POST')
    expect(init.body).toBeInstanceOf(URLSearchParams)
    expect(init.body.toString()).toBe('username=u1&password=pw')
    expect(init.headers['Content-Type']).toBeUndefined()
  })

  it('帳號和分享的 API 用對 method 和網址', async () => {
    fetch.mockImplementation(async () => jsonResponse({}))
    await api.me()
    await api.authOptions()
    await api.logout()
    await api.changePassword('old', 'new-password-1')
    await api.listAccounts()
    await api.createAccount({ username: 'alice', password: 'init-password', admin: false })
    await api.updateAccount('a@b', { admin: true })
    await api.setAccountPassword('a@b', 'new-password-1')
    await api.getShare('d1')
    await api.setShare('d1', { publicLink: true, users: ['alice'] })
    await api.sharedDashboards()
    expect(fetch.mock.calls.map(([url, init]) => [url, init.method, init.body])).toEqual([
      ['/api/auth/me', 'GET', undefined],
      ['/api/auth/options', 'GET', undefined],
      ['/api/auth/logout', 'POST', undefined],
      ['/api/auth/password', 'POST', JSON.stringify({ current: 'old', password: 'new-password-1' })],
      ['/api/admin/accounts', 'GET', undefined],
      ['/api/admin/accounts', 'POST', JSON.stringify({ username: 'alice', password: 'init-password', admin: false })],
      ['/api/admin/accounts/a%40b', 'PATCH', JSON.stringify({ admin: true })],
      ['/api/admin/accounts/a%40b/password', 'PUT', JSON.stringify({ password: 'new-password-1' })],
      ['/api/dashboards/d1/share', 'GET', undefined],
      ['/api/dashboards/d1/share', 'PUT', JSON.stringify({ publicLink: true, users: ['alice'] })],
      ['/api/dashboards/shared', 'GET', undefined],
    ])
  })

  it('sets Content-Type + JSON.stringify body for a JSON POST; FormData body is sent as-is with no Content-Type', async () => {
    fetch.mockResolvedValueOnce(jsonResponse({}))
    await api.analyze('ds1')
    const [, jsonInit] = fetch.mock.calls[0]
    expect(jsonInit.headers['Content-Type']).toBe('application/json')
    expect(jsonInit.body).toBe(JSON.stringify({ datasourceId: 'ds1' }))

    const form = new FormData()
    fetch.mockResolvedValueOnce(jsonResponse({ id: 'ds1' }))
    await api.uploadDatasource(form)
    const [, formInit] = fetch.mock.calls[1]
    expect(formInit.body).toBe(form)
    expect(formInit.headers['Content-Type']).toBeUndefined()
  })

  it('rejects with the server error message for a non-2xx JSON body', async () => {
    fetch.mockResolvedValueOnce(jsonResponse({ error: '只支援 .csv、.xlsx、.xls' }, { status: 400 }))
    await expect(api.uploadDatasource(new FormData())).rejects.toThrow('只支援 .csv、.xlsx、.xls')
  })

  it('rejects with "HTTP <status>" when the non-2xx body is not JSON', async () => {
    fetch.mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: async () => {
        throw new Error('not json')
      },
    })
    await expect(api.listDatasources()).rejects.toThrow('HTTP 500')
  })

  it('rejects with "無法連線到伺服器" when fetch itself rejects (server down)', async () => {
    fetch.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await expect(api.listDatasources()).rejects.toThrow('無法連線到伺服器')
  })

  it('resolves a 204 response to null', async () => {
    fetch.mockResolvedValueOnce({
      ok: true,
      status: 204,
      json: async () => {
        throw new Error('should not be called for 204')
      },
    })
    await expect(api.deleteDashboard('d1')).resolves.toBeNull()
  })

  it('analyze sends intents and current only when they are given', async () => {
    fetch.mockResolvedValueOnce(jsonResponse({}))
    await api.analyze('ds1', ['各區每月營收'], [{ chartId: 'c1' }])
    expect(fetch.mock.calls[0][1].body).toBe(
      JSON.stringify({ datasourceId: 'ds1', intents: ['各區每月營收'], current: [{ chartId: 'c1' }] }),
    )

    fetch.mockResolvedValueOnce(jsonResponse({}))
    await api.analyze('ds1', ['各區每月營收'])
    expect(fetch.mock.calls[1][1].body).toBe('{"datasourceId":"ds1","intents":["各區每月營收"]}')
  })

  it('previewMerged / createMerged POST {name, merge} to the merged endpoints', async () => {
    const body = { name: '全區', merge: { op: 'union', inputs: ['a', 'b'], columns: [], sourceColumn: '來源' } }
    fetch.mockResolvedValueOnce(jsonResponse({ rowCount: 0 }))
    await api.previewMerged(body)
    fetch.mockResolvedValueOnce(jsonResponse({ id: 'm1' }))
    await api.createMerged(body)
    expect(fetch.mock.calls.map(([url, init]) => [url, init.method, init.body])).toEqual([
      ['/api/datasources/merged/preview', 'POST', JSON.stringify(body)],
      ['/api/datasources/merged', 'POST', JSON.stringify(body)],
    ])
  })

  it('appendCharts POSTs the batch to /api/dashboards/{id}/charts', async () => {
    fetch.mockResolvedValueOnce(jsonResponse({ id: 'd1' }))
    await api.appendCharts('d1', { layout: [{ chartId: 'c1' }], intents: ['各區退貨'] })
    const [url, init] = fetch.mock.calls[0]
    expect(url).toBe('/api/dashboards/d1/charts')
    expect(init.method).toBe('POST')
    expect(init.body).toBe(JSON.stringify({ layout: [{ chartId: 'c1' }], intents: ['各區退貨'] }))
  })

  it('a rejected request also carries the HTTP status, so callers can tell a 404 apart', async () => {
    fetch.mockResolvedValueOnce(jsonResponse({ error: '找不到儀表板' }, { status: 404 }))
    await expect(api.getDashboard('d1')).rejects.toMatchObject({ message: '找不到儀表板', status: 404 })
  })

  it('datasourceUsage GETs /usage; deleteDatasource DELETEs and resolves null on 204', async () => {
    fetch.mockResolvedValueOnce(jsonResponse({ mergedBy: [], dashboards: [] }))
    await expect(api.datasourceUsage('ds1')).resolves.toEqual({ mergedBy: [], dashboards: [] })
    expect(fetch.mock.calls[0][0]).toBe('/api/datasources/ds1/usage')
    expect(fetch.mock.calls[0][1].method).toBe('GET')

    fetch.mockResolvedValueOnce(jsonResponse(null, { status: 204 }))
    await expect(api.deleteDatasource('ds1')).resolves.toBeNull()
    expect(fetch.mock.calls[1][0]).toBe('/api/datasources/ds1')
    expect(fetch.mock.calls[1][1].method).toBe('DELETE')
  })

  it('suggestMapping POSTs the inputs and the compare flag to /api/jev/mapping', async () => {
    fetch.mockResolvedValueOnce(jsonResponse({ matches: [] }))
    await api.suggestMapping(['a', 'b'], true)
    const [url, init] = fetch.mock.calls[0]
    expect(url).toBe('/api/jev/mapping')
    expect(init.method).toBe('POST')
    expect(init.body).toBe(JSON.stringify({ inputs: ['a', 'b'], compare: true }))
  })
})

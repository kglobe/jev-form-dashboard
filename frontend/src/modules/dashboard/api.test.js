import { describe, it, expect, vi, beforeEach } from 'vitest'
import { api } from './api.js'

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

  it('sends X-User-Id: demo-user on a GET and on a JSON POST', async () => {
    fetch.mockResolvedValueOnce(jsonResponse([]))
    await api.listDatasources()
    expect(fetch.mock.calls[0][1].headers['X-User-Id']).toBe('demo-user')

    fetch.mockResolvedValueOnce(jsonResponse({}))
    await api.analyze('ds1')
    expect(fetch.mock.calls[1][1].headers['X-User-Id']).toBe('demo-user')
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
})

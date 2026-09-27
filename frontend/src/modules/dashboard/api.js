const USER_ID = import.meta.env.VITE_USER_ID || 'demo-user'

async function request(method, url, body) {
  const isForm = body instanceof FormData
  const headers = { 'X-User-Id': USER_ID }
  if (body != null && !isForm) headers['Content-Type'] = 'application/json'
  let res
  try {
    res = await fetch(url, {
      method,
      headers,
      body: body == null ? undefined : isForm ? body : JSON.stringify(body),
    })
  } catch {
    throw new Error('無法連線到伺服器')
  }
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || `HTTP ${res.status}`)
  }
  return res.status === 204 ? null : res.json()
}

export const api = {
  listDatasources: () => request('GET', '/api/datasources'),
  uploadDatasource: (form) => request('POST', '/api/datasources/upload', form),
  registerApi: (body) => request('POST', '/api/datasources/api', body),
  setSensitive: (id, fields) => request('PATCH', `/api/datasources/${id}/sensitive`, { fields }),
  setLabels: (id, labels) => request('PATCH', `/api/datasources/${id}/labels`, { labels }),
  previewMerged: (body) => request('POST', '/api/datasources/merged/preview', body),
  createMerged: (body) => request('POST', '/api/datasources/merged', body),
  // intents、current、fields 沒給時 JSON.stringify 會略過，body 跟原本一樣只有 datasourceId
  analyze: (datasourceId, intents, current, fields) => request('POST', '/api/jev/analyze', { datasourceId, intents, current, fields }),
  listDashboards: () => request('GET', '/api/dashboards'),
  getDashboard: (id) => request('GET', `/api/dashboards/${id}`),
  createDashboard: (config) => request('POST', '/api/dashboards', config),
  updateDashboard: (id, config) => request('PUT', `/api/dashboards/${id}`, config),
  deleteDashboard: (id) => request('DELETE', `/api/dashboards/${id}`),
  dashboardData: (id) => request('GET', `/api/dashboards/${id}/data`),
  previewData: (config) => request('POST', '/api/dashboards/preview/data', config),
  highlights: (id) => request('GET', `/api/jev/highlight/${id}`),
}

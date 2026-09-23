const USER_ID = import.meta.env.VITE_USER_ID || 'demo-user'

async function request(method, url, body) {
  const isForm = body instanceof FormData
  const headers = { 'X-User-Id': USER_ID }
  if (body != null && !isForm) headers['Content-Type'] = 'application/json'
  const res = await fetch(url, {
    method,
    headers,
    body: body == null ? undefined : isForm ? body : JSON.stringify(body),
  })
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
  analyze: (datasourceId) => request('POST', '/api/jev/analyze', { datasourceId }),
  listDashboards: () => request('GET', '/api/dashboards'),
  getDashboard: (id) => request('GET', `/api/dashboards/${id}`),
  createDashboard: (config) => request('POST', '/api/dashboards', config),
  updateDashboard: (id, config) => request('PUT', `/api/dashboards/${id}`, config),
  deleteDashboard: (id) => request('DELETE', `/api/dashboards/${id}`),
  dashboardData: (id) => request('GET', `/api/dashboards/${id}/data`),
  previewData: (config) => request('POST', '/api/dashboards/preview/data', config),
  highlights: (id) => request('GET', `/api/jev/highlight/${id}`),
}

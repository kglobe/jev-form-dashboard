import { describe, it, expect, afterEach, vi } from 'vitest'

// 測試劇本的 E2E 環境：API_TARGET 換掉三條 proxy 的後端位址，使用者自己的 8080 不用停
async function proxy() {
  vi.resetModules()
  return (await import('./vite.config.js')).default.server.proxy
}

const targets = (p) => [p['/api'], p['/oauth2'].target, p['/login/oauth2'].target]

describe('vite proxy target', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('沒設 API_TARGET 時接 127.0.0.1:8080', async () => {
    vi.stubEnv('API_TARGET', undefined)
    expect(targets(await proxy())).toEqual(Array(3).fill('http://127.0.0.1:8080'))
  })

  it('三條 proxy 都換成 API_TARGET', async () => {
    vi.stubEnv('API_TARGET', 'http://127.0.0.1:8081')
    expect(targets(await proxy())).toEqual(Array(3).fill('http://127.0.0.1:8081'))
  })

  it('API_TARGET 是空字串時當作沒設', async () => {
    vi.stubEnv('API_TARGET', '')
    expect(targets(await proxy())).toEqual(Array(3).fill('http://127.0.0.1:8080'))
  })

  it('單一登入的兩條照樣不改 Host', async () => {
    vi.stubEnv('API_TARGET', 'http://127.0.0.1:8081')
    const p = await proxy()
    expect([p['/oauth2'].changeOrigin, p['/login/oauth2'].changeOrigin]).toEqual([false, false])
  })
})

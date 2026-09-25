import { describe, it, expect, afterEach, vi } from 'vitest'
import { prefersReducedMotion } from './motion.js'

describe('prefersReducedMotion', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('returns matchMedia(...).matches when matchMedia exists', () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true }))
    expect(prefersReducedMotion()).toBe(true)
  })

  it('returns false instead of throwing when matchMedia is missing', () => {
    vi.stubGlobal('matchMedia', undefined)
    expect(prefersReducedMotion()).toBe(false)
  })
})

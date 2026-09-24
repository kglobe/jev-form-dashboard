// Shared test helper for mounting dashboard components with a real Vuetify
// instance (avoids "Failed to resolve component" / Vuetify inject warnings).
// Not a *.test.js file, so Vitest does not pick it up as a test suite.
import { createVuetify } from 'vuetify'

if (!globalThis.ResizeObserver) {
  globalThis.ResizeObserver = class ResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
}

if (!globalThis.visualViewport) {
  globalThis.visualViewport = {
    addEventListener() {},
    removeEventListener() {},
  }
}

if (!globalThis.matchMedia) {
  globalThis.matchMedia = () => ({
    matches: false,
    media: '',
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
    dispatchEvent() {
      return false
    },
  })
}

export const vuetify = createVuetify()

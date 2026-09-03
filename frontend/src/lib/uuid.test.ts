import { afterEach, describe, expect, it, vi } from 'vitest'

import { randomUuid } from './uuid'

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

afterEach(() => {
  vi.unstubAllGlobals()
})

/**
 * The scenario the guard exists for: a secure context has crypto.randomUUID, an insecure one
 * (the SPA served over plain http on a LAN IP, which frontend/nginx.conf does) has only
 * getRandomValues, since randomUUID alone is [SecureContext].
 */
function insecureContextCrypto() {
  return {
    getRandomValues: <T extends ArrayBufferView>(array: T): T => {
      const bytes = new Uint8Array(array.buffer, array.byteOffset, array.byteLength)
      for (let i = 0; i < bytes.length; i += 1) bytes[i] = i * 7 + 3
      return array
    },
  }
}

describe('randomUuid', () => {
  it('uses crypto.randomUUID when the page is a secure context', () => {
    const randomUUID = vi.fn(() => '11111111-2222-4333-8444-555555555555' as const)
    vi.stubGlobal('crypto', { randomUUID, getRandomValues: insecureContextCrypto().getRandomValues })

    expect(randomUuid()).toBe('11111111-2222-4333-8444-555555555555')
    expect(randomUUID).toHaveBeenCalledOnce()
  })

  // The regression. Without the guard this call is `undefined()` and throws a TypeError, which
  // on the checkout page happens during render.
  it('does not throw when crypto.randomUUID is undefined, and still yields a UUID v4', () => {
    vi.stubGlobal('crypto', insecureContextCrypto())

    expect(() => randomUuid()).not.toThrow()
    expect(randomUuid()).toMatch(UUID_V4)
  })

  it('prefers getRandomValues over Math.random in an insecure context', () => {
    const getRandomValues = vi.fn(insecureContextCrypto().getRandomValues)
    vi.stubGlobal('crypto', { getRandomValues })
    const mathRandom = vi.spyOn(Math, 'random')

    expect(randomUuid()).toMatch(UUID_V4)
    expect(getRandomValues).toHaveBeenCalledOnce()
    expect(mathRandom).not.toHaveBeenCalled()
  })

  it('still yields a UUID v4 with no Web Crypto at all', () => {
    vi.stubGlobal('crypto', undefined)

    expect(() => randomUuid()).not.toThrow()
    expect(randomUuid()).toMatch(UUID_V4)
  })

  it('does not repeat itself', () => {
    const seen = new Set(Array.from({ length: 200 }, () => randomUuid()))
    expect(seen.size).toBe(200)
  })
})

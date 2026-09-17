import { afterEach, describe, expect, it } from 'vitest'

import { DEFAULT_API_BASE_URL, resolveApiBaseUrl, resolveStripePublishableKey } from '@/lib/runtime-config'

describe('resolveApiBaseUrl', () => {
  afterEach(() => {
    delete window.__APP_CONFIG__
  })

  it('prefers the value the container wrote into /config.js over the one baked into the bundle', () => {
    expect(resolveApiBaseUrl('https://api.example.com/api/v1', 'http://localhost:8080/api/v1')).toBe(
      'https://api.example.com/api/v1',
    )
  })

  it('falls back to the build-time value when nothing was written at runtime', () => {
    expect(resolveApiBaseUrl(undefined, 'http://localhost:9999/api/v1')).toBe(
      'http://localhost:9999/api/v1',
    )
  })

  // public/config.js ships with an empty apiBaseUrl so `npm run dev` serves the file instead of
  // 404ing; that placeholder must not win over .env.local.
  it('treats a blank runtime value as absent rather than as an origin', () => {
    expect(resolveApiBaseUrl('   ', 'http://localhost:9999/api/v1')).toBe(
      'http://localhost:9999/api/v1',
    )
  })

  it('falls back to the default when neither source is set', () => {
    expect(resolveApiBaseUrl(undefined, undefined)).toBe(DEFAULT_API_BASE_URL)
  })

  // Callers join with a leading slash, so a pasted trailing one would produce `//auth/refresh`.
  it('strips trailing slashes so joined paths do not double up', () => {
    expect(resolveApiBaseUrl('https://api.example.com/api/v1/', undefined)).toBe(
      'https://api.example.com/api/v1',
    )
    expect(resolveApiBaseUrl('https://api.example.com/api/v1///', undefined)).toBe(
      'https://api.example.com/api/v1',
    )
  })

  // How api.ts actually calls it: no arguments, both sources read from their defaults.
  it('reads window.__APP_CONFIG__ when called with no arguments', () => {
    window.__APP_CONFIG__ = { apiBaseUrl: 'https://api.example.com/api/v1' }
    expect(resolveApiBaseUrl()).toBe('https://api.example.com/api/v1')
  })

  it('ignores a config object that carries no apiBaseUrl at all', () => {
    window.__APP_CONFIG__ = {}
    expect(resolveApiBaseUrl(undefined, 'http://localhost:9999/api/v1')).toBe(
      'http://localhost:9999/api/v1',
    )
  })
})

describe('resolveStripePublishableKey', () => {
  afterEach(() => {
    delete window.__APP_CONFIG__
  })

  it('prefers the key the container wrote over the one baked into the bundle', () => {
    expect(resolveStripePublishableKey('pk_test_runtime', 'pk_test_build')).toBe('pk_test_runtime')
  })

  it('falls back to the build-time key, and treats blank as absent', () => {
    expect(resolveStripePublishableKey('  ', 'pk_test_build')).toBe('pk_test_build')
  })

  // Unlike the API origin there is no sensible default: an unconfigured deployment must be told
  // apart from a configured one, and the card form reads undefined as "not available here".
  it('is undefined when neither source is set', () => {
    expect(resolveStripePublishableKey(undefined, undefined)).toBeUndefined()
    expect(resolveStripePublishableKey('', '')).toBeUndefined()
  })

  it('reads window.__APP_CONFIG__ when called with no arguments', () => {
    window.__APP_CONFIG__ = { stripePublishableKey: 'pk_test_runtime' }
    expect(resolveStripePublishableKey()).toBe('pk_test_runtime')
  })
})

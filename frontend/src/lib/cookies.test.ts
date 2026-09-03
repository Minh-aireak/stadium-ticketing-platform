import { afterEach, describe, expect, it } from 'vitest'

import { getCookie } from './cookies'

function setDocumentCookie(value: string) {
  Object.defineProperty(document, 'cookie', {
    value,
    configurable: true,
    writable: true,
  })
}

afterEach(() => {
  setDocumentCookie('')
})

describe('getCookie', () => {
  it('reads a cookie by exact name', () => {
    setDocumentCookie('a=1; XSRF-TOKEN=abc123; b=2')
    expect(getCookie('XSRF-TOKEN')).toBe('abc123')
  })

  it('returns null when the cookie is absent', () => {
    setDocumentCookie('a=1; b=2')
    expect(getCookie('XSRF-TOKEN')).toBeNull()
  })

  it('does not match a name that merely ends with the requested one', () => {
    setDocumentCookie('NOT-XSRF-TOKEN=wrong')
    expect(getCookie('XSRF-TOKEN')).toBeNull()
  })

  it('url-decodes the value', () => {
    setDocumentCookie('XSRF-TOKEN=a%2Bb%3Dc')
    expect(getCookie('XSRF-TOKEN')).toBe('a+b=c')
  })

  it('keeps a value containing "=" intact', () => {
    setDocumentCookie('XSRF-TOKEN=abc=def')
    expect(getCookie('XSRF-TOKEN')).toBe('abc=def')
  })

  // The reason this function parses instead of building `new RegExp(name)`: a name is data, not
  // a pattern. Under the old implementation the metacharacters below were compiled into the
  // pattern, so this lookup matched the *other* cookie's value.
  it('treats regex metacharacters in the name as literal characters', () => {
    setDocumentCookie('XSRF-TOKEN=real; X.RF.TOKEN=spoofed')
    expect(getCookie('X.RF.TOKEN')).toBe('spoofed')
    expect(getCookie('X.*')).toBeNull()
  })
})

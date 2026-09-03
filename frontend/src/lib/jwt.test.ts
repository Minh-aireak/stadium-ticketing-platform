import { describe, expect, it } from 'vitest'

import { decodeJwt } from './jwt'

/**
 * base64url over UTF-8 bytes, the way Nimbus writes a JWT payload
 * (identity-service JwtTokenGeneratorAdapter -> SignedJWT#serialize).
 *
 * <p>Deliberately not `btoa(JSON.stringify(...))`, which is what AuthContext.test.tsx uses:
 * btoa throws on any code point above U+00FF, so that helper cannot build the one payload
 * shape this module's TextDecoder step exists for.
 */
function encodeSegment(json: string): string {
  const bytes = new TextEncoder().encode(json)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

const tokenFor = (json: string) => `header.${encodeSegment(json)}.signature`

const wellFormed = JSON.stringify({
  sub: '11111111-1111-1111-1111-111111111111',
  email: 'admin@stadium.test',
  status: 'ACTIVE',
  role: 'ADMIN',
  exp: 1893456000,
  iat: 1893452400,
})

describe('decodeJwt', () => {
  it('reads the claims identity-service puts in an access token', () => {
    const payload = decodeJwt(tokenFor(wellFormed))
    expect(payload).not.toBeNull()
    expect(payload?.sub).toBe('11111111-1111-1111-1111-111111111111')
    expect(payload?.email).toBe('admin@stadium.test')
    expect(payload?.role).toBe('ADMIN')
    expect(payload?.exp).toBe(1893456000)
    expect(payload?.iat).toBe(1893452400)
  })

  it('decodes a payload whose bytes are UTF-8, not Latin-1', () => {
    const json = JSON.stringify({ sub: 'u1', email: 'ãữãữ@ví-dụ.vn', role: 'USER' })
    const token = tokenFor(json)
    // Self-check: this specific payload is what makes the segment exercise both base64url
    // substitutions and the multi-byte decode. If either stops being true the case is hollow.
    const segment = token.split('.')[1]
    expect(segment).toContain('-')
    expect(segment).toContain('_')
    expect(decodeJwt(token)?.email).toBe('ãữãữ@ví-dụ.vn')
  })

  it('decodes a segment whose length needs padding restored', () => {
    const json = JSON.stringify({ sub: 'u1', email: 'a@b.test' })
    const segment = tokenFor(json).split('.')[1]
    expect(segment.length % 4).not.toBe(0)
    expect(decodeJwt(tokenFor(json))?.sub).toBe('u1')
  })

  it.each([
    ['a token with no dot at all', 'notajwt'],
    ['an empty string', ''],
    ['an empty payload segment', 'header..signature'],
    ['a payload that is not base64', 'header.!!!!.signature'],
    ['a payload whose length cannot be base64', 'header.b.signature'],
  ])('returns null for %s', (_label, token) => {
    expect(decodeJwt(token)).toBeNull()
  })

  it.each([
    ['a number', '123'],
    ['a string', '"hi"'],
    ['an array', '[{"sub":"u1"}]'],
    ['a boolean', 'true'],
    ['null', 'null'],
  ])('returns null when the payload is %s rather than a claims object', (_label, json) => {
    expect(decodeJwt(tokenFor(json))).toBeNull()
  })

  it.each([
    ['sub is missing', '{"email":"a@b.test","role":"ADMIN"}'],
    ['sub is empty', '{"sub":"","email":"a@b.test"}'],
    ['sub is a number', '{"sub":42,"email":"a@b.test"}'],
    ['sub is null', '{"sub":null,"email":"a@b.test"}'],
  ])('returns null when %s', (_label, json) => {
    expect(decodeJwt(tokenFor(json))).toBeNull()
  })

  it('does not let a __proto__ member in the payload reach Object.prototype', () => {
    const payload = decodeJwt(tokenFor('{"sub":"u1","__proto__":{"role":"ADMIN"}}'))
    expect(payload?.role).toBeUndefined()
    expect(({} as { role?: string }).role).toBeUndefined()
  })
})

export interface JwtPayload {
  sub: string
  email?: string
  role?: string
  exp: number
  iat: number
}

function base64UrlDecode(input: string): string {
  const base64 = input.replace(/-/g, '+').replace(/_/g, '/')
  const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=')
  const binary = atob(padded)
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0))
  return new TextDecoder().decode(bytes)
}

// Decodes the access token client-side to read `sub` (user id) / `email`.
// The gateway is the one that verifies the signature — this is display-only, never trust-boundary.
export function decodeJwt(token: string): JwtPayload | null {
  try {
    const [, payload] = token.split('.')
    const claims: unknown = JSON.parse(base64UrlDecode(payload))
    // JSON.parse accepts a bare number, string, array or boolean, and every one of those is
    // truthy enough to survive AuthContext's `if (!payload) return null`. What comes out the
    // other side is an AuthUser whose `id` is undefined while its type says string — and
    // CheckoutPage sends that straight on as `customerId`. A payload that is not an object
    // with a subject is not a token this app can identify anyone by, so it is no token at all.
    if (typeof claims !== 'object' || claims === null || Array.isArray(claims)) return null
    const { sub } = claims as { sub?: unknown }
    if (typeof sub !== 'string' || sub.length === 0) return null
    return claims as JwtPayload
  } catch {
    return null
  }
}

export interface JwtPayload {
  sub: string
  email?: string
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
    return JSON.parse(base64UrlDecode(payload))
  } catch {
    return null
  }
}

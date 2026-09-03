/**
 * A UUID v4, usable on any page — including one that is not a secure context.
 *
 * `crypto.randomUUID` is annotated `[SecureContext]` in the Web Crypto spec, so it is undefined
 * on a page served over plain http to anything but localhost. frontend/nginx.conf serves the
 * built SPA with `listen 80` and `server_name _`, so every deployment reached by LAN IP or
 * hostname is exactly that page, and an unguarded call there throws a TypeError.
 *
 * `crypto.getRandomValues` carries no such annotation and is available in an insecure context, so
 * it is the fallback rather than `Math.random`: this function backs the checkout Idempotency-Key
 * (see CheckoutPage), which booking-service dedupes on, and two customers deriving the same key
 * would resolve to the same booking row. Math.random remains as a last resort for an environment
 * with no Web Crypto at all.
 *
 * The output must also satisfy the gateway's CorrelationIdWebFilter, which trusts an inbound ID
 * only when it is a well-formed UUID v4 and silently replaces anything else.
 */
export function randomUuid(): string {
  const webCrypto = typeof crypto !== 'undefined' ? crypto : undefined

  if (typeof webCrypto?.randomUUID === 'function') {
    return webCrypto.randomUUID()
  }

  if (typeof webCrypto?.getRandomValues === 'function') {
    const bytes = webCrypto.getRandomValues(new Uint8Array(16))
    bytes[6] = (bytes[6] & 0x0f) | 0x40
    bytes[8] = (bytes[8] & 0x3f) | 0x80
    const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  }

  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16)
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
  })
}

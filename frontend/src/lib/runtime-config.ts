/**
 * Where the storefront finds the API origin.
 *
 * vite inlines `VITE_*` into the bundle at build time, which made the API origin a property of the
 * *image* rather than of the deployment: pointing the app at a real domain meant rebuilding, and a
 * plain restart silently kept serving the origin compiled in weeks earlier — with nothing visibly
 * wrong until requests started going to the wrong host. The container now writes `/config.js` from
 * its environment on every start (see `docker-entrypoint.d/40-app-config.sh`) and `index.html`
 * loads it ahead of the bundle, so the origin travels with the deployment.
 *
 * The build-time value is kept as the second choice: outside Docker nothing writes `/config.js`,
 * so `npm run dev` and `npm run preview` keep reading `.env.local` exactly as before.
 */

declare global {
  interface Window {
    __APP_CONFIG__?: { apiBaseUrl?: string; stripePublishableKey?: string }
  }
}

/** Last resort: the gateway on the port docker-compose publishes locally. */
export const DEFAULT_API_BASE_URL = 'http://localhost:8080/api/v1'

/**
 * Blank counts as absent, so the placeholder `public/config.js` committed for dev does not
 * shadow `VITE_API_BASE_URL`.
 *
 * Trailing slashes go because callers join with a leading one (`${API_BASE_URL}/auth/refresh`),
 * and anyone filling this in from a browser address bar will paste one eventually — `//auth/refresh`
 * is a 404 nobody would trace back to a stray character in an env file.
 */
function normalize(value: string | undefined): string | undefined {
  const trimmed = value?.trim()
  if (!trimmed) return undefined
  return trimmed.replace(/\/+$/, '')
}

export function resolveApiBaseUrl(
  runtimeValue: string | undefined = typeof window === 'undefined'
    ? undefined
    : window.__APP_CONFIG__?.apiBaseUrl,
  buildTimeValue: string | undefined = import.meta.env.VITE_API_BASE_URL as string | undefined,
): string {
  return normalize(runtimeValue) ?? normalize(buildTimeValue) ?? DEFAULT_API_BASE_URL
}

/**
 * The Stripe publishable key (`pk_test_…` / `pk_live_…`) the card form loads Stripe.js with. Same
 * two sources and the same precedence as the API origin, for the same reason: which Stripe account
 * the storefront talks to is a property of the deployment, not of the image. Publishable keys are
 * designed to ship to browsers, so there is nothing to protect here -- but there is also no
 * default to fall back on, and `undefined` is what the card form reads as "card payments are not
 * configured on this deployment".
 */
export function resolveStripePublishableKey(
  runtimeValue: string | undefined = typeof window === 'undefined'
    ? undefined
    : window.__APP_CONFIG__?.stripePublishableKey,
  buildTimeValue: string | undefined = import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY as string | undefined,
): string | undefined {
  return runtimeValue?.trim() || buildTimeValue?.trim() || undefined
}

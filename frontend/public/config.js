// Rewritten from the container's environment on every start by
// docker-entrypoint.d/40-app-config.sh. Committed with an empty value so that `npm run dev` and
// `npm run preview` serve the file rather than 404ing on it, while still deferring to
// VITE_API_BASE_URL / VITE_STRIPE_PUBLISHABLE_KEY from .env.local — src/lib/runtime-config.ts treats blank as "not configured".
window.__APP_CONFIG__ = { apiBaseUrl: '', stripePublishableKey: '' }

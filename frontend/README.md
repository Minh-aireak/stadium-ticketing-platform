# Storefront

The customer-facing SPA for the stadium ticketing platform: browse matches, pick seats, check
out, and follow a payment to its outcome. React 19 + TypeScript on Vite 8, built to a static
bundle and served by nginx.

## Running it

```bash
npm ci
cp .env.example .env.local     # optional; the default already points at a local gateway
npm run dev                    # vite's default port, 5173
```

5173 is not incidental: it is the origin `CORS_ALLOWED_ORIGINS` allows in the repository's root
`.env.example`, and the port docker-compose maps the built image to. Starting the dev server
while the compose stack is up puts vite on 5174 and the gateway will reject its requests.

| command | what it does |
|---|---|
| `npm run dev` | vite dev server with HMR |
| `npm test` | vitest, jsdom, `src/**/*.test.{ts,tsx}` |
| `npm run lint` | oxlint |
| `npm run build` | `tsc -b && vite build` — the only place the app is type-checked |
| `npm run preview` | serve the built bundle locally |

## How it talks to the backend

The API origin is the one variable, and it must be an address the *browser* can reach, not a
Docker service name. It is resolved at start-up rather than compiled in: the container writes
`/config.js` from `API_BASE_URL` (see `docker-entrypoint.d/40-app-config.sh`), `index.html` loads
that ahead of the bundle, and `src/lib/runtime-config.ts` prefers it over the `VITE_API_BASE_URL`
vite inlined at build time. So a deployed storefront moves to a new domain on a restart, while
`npm run dev` still just reads `.env.local`. `src/lib/api.ts` holds the single axios instance: the access token lives in memory only, the refresh token is an
HttpOnly cookie the browser sends on its own, and every request carries a UUID v4
`X-Correlation-Id` so a failure can be quoted back to the backend logs.

The gateway is the only origin the storefront calls. It routes `/api/v1/{auth,matches,inventory,
payments,notifications,bookings}/**` onward.

## Layout

```
src/
  routes/router.tsx     every route; all lazy except the landing page
  pages/                one component per route
  features/             per-domain API clients, types and components
  components/ui/        shadcn-style primitives (see components.json)
  lib/                  axios instance, cookies, formatting, uuid
  hooks/                useToast, useCountdown
  index.css             Tailwind v4 @theme tokens — the palette lives here, not in a config file
```

## Shipping

`Dockerfile` builds the bundle and copies it into nginx with `nginx.conf`, which serves the SPA
fallback, compresses, and caches `/assets/` immutably — those files are content-hashed, so a
deploy only invalidates what changed. CI builds that image and smoke-tests the served result.

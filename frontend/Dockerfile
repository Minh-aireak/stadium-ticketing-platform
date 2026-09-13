# ---- Stage 1: Build ----
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Vite inlines VITE_* vars into the bundle at build time, so this value is only the fallback
# compiled in for builds run outside a container (npm run build, npm run preview). Deployments do
# not set it: /docker-entrypoint.d/40-app-config.sh writes the real origin into /config.js at
# start-up, and src/lib/runtime-config.ts prefers that over anything baked in here.
ARG VITE_API_BASE_URL=http://localhost:8080/api/v1
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# ---- Stage 2: Runtime ----
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Runs before nginx starts, on every container start — see the script for why the API origin is
# resolved here rather than compiled into the bundle above. chmod because the image's entrypoint
# only executes files that carry the bit, and a COPY from a Windows checkout does not.
COPY docker-entrypoint.d/40-app-config.sh /docker-entrypoint.d/40-app-config.sh
RUN chmod +x /docker-entrypoint.d/40-app-config.sh

EXPOSE 80

#!/bin/sh
# nginx:alpine runs every executable /docker-entrypoint.d/*.sh before starting nginx, so this
# needs no custom ENTRYPOINT -- and it runs on every container start, which is the entire point:
# the API origin becomes a property of the deployment instead of the image, so pointing the
# storefront at a domain is a restart rather than a rebuild.
set -eu

: "${API_BASE_URL:=http://localhost:8080/api/v1}"

# Escaped rather than interpolated raw: the value comes from the environment and lands inside a
# JavaScript string literal, so one stray quote or backslash would emit a file the browser fails
# to parse. The app would then fall back to the origin baked into the bundle and keep working
# against the wrong host, with nothing in any log saying why. Backslashes are doubled first, or
# the escaping added for the quotes would itself get re-escaped.
escaped=$(printf '%s' "$API_BASE_URL" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')

cat > /usr/share/nginx/html/config.js <<CONFIG
window.__APP_CONFIG__ = { apiBaseUrl: "$escaped" };
CONFIG

echo "40-app-config.sh: apiBaseUrl=$API_BASE_URL"

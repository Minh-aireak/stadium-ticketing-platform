#!/bin/sh
# Registers all Debezium outbox connectors against kafka-connect's REST API.
# Idempotent: a 409 (connector already exists) counts as success, so
# re-running `docker compose up -d` is always safe.
set -e

for f in /connectors/*.json; do
  name=$(basename "$f" .json)
  echo "Registering connector: $name"
  status=$(curl -s -o /tmp/response.json -w "%{http_code}" -X POST \
    -H "Content-Type: application/json" \
    --data @"$f" \
    http://kafka-connect:8083/connectors)
  if [ "$status" = "201" ] || [ "$status" = "409" ]; then
    echo "  -> OK ($status)"
  else
    echo "  -> FAILED ($status): $(cat /tmp/response.json)"
    exit 1
  fi
done

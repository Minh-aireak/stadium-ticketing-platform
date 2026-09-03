#!/bin/sh
# Registers all Debezium outbox connectors against kafka-connect's REST API.
# Idempotent: a 409 (connector already exists) counts as success, so
# re-running `docker compose up -d` is always safe.
#
# "Safe" means it will not fail, NOT that it will pick up an edit. A 409 leaves
# the existing connector on whatever config it was created with, so this script
# only ever creates -- it never updates. Applying an edited JSON needs
# PUT /connectors/<name>/config; see the recipe on the kafka-connect service in
# docker-compose.yaml. Left as POST deliberately: a compose restart should not
# silently reconfigure a running CDC connector.
#
# The connector JSONs carry no credentials: they reference
# ${file:/kafka/connect-secrets/postgres.properties:...}, which Kafka Connect's FileConfigProvider
# resolves inside the kafka-connect container (see its entrypoint in docker-compose.yaml). This
# script therefore POSTs each file exactly as it is committed.
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

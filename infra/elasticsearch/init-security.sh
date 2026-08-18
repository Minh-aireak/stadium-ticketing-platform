#!/bin/sh
# Creates the Elasticsearch accounts the rest of the stack logs in with, using the elastic
# superuser (bootstrapped from ELASTIC_PASSWORD) to do it.
#
# This has to run as a container against a live cluster rather than as settings on the
# elasticsearch service, because ELASTIC_PASSWORD only ever sets the password of the built-in
# `elastic` user. Every other account — Kibana's included — lives in the cluster's security index
# and can only be created or given a password through the security API.
#
# Idempotent: roles and users are PUT, and setting a password to the value it already has is a
# no-op, so `docker compose up -d` is always safe to re-run.
set -e

ES="http://elasticsearch:9200"
AUTH="elastic:${ELASTIC_PASSWORD}"

call() {
  method="$1"; path="$2"; body="$3"; label="$4"
  status=$(curl -s -o /tmp/response.json -w "%{http_code}" -u "$AUTH" \
    -X "$method" -H "Content-Type: application/json" \
    ${body:+--data "$body"} "$ES$path")
  case "$status" in
    2*) echo "  -> OK ($status) $label" ;;
    *)  echo "  -> FAILED ($status) $label: $(cat /tmp/response.json)"; exit 1 ;;
  esac
}

# The cluster reports healthy before the security subsystem finishes initialising, and calls to
# /_security answer 503 in that window.
echo "Waiting for the security API..."
i=1
while [ "$i" -le 30 ]; do
  if curl -s -f -u "$AUTH" "$ES/_security/_authenticate" >/dev/null 2>&1; then
    echo "  -> ready"
    break
  fi
  [ "$i" -eq 30 ] && { echo "  -> FAILED: security API never came up"; exit 1; }
  i=$((i + 1))
  sleep 2
done

# kibana_system is built-in, so it is only given a password, never created. Kibana refuses to run
# as `elastic`: a superuser cannot write the system indices Kibana needs, and Kibana rejects that
# username during config validation rather than failing later at runtime.
echo "Setting kibana_system password"
call POST "/_security/user/kibana_system/_password" \
  "{\"password\":\"${KIBANA_SYSTEM_PASSWORD}\"}" "kibana_system password"

# create_doc is append-only: a compromised shipper cannot rewrite or delete log history it has
# already sent. auto_configure covers the mapping updates a new ECS field triggers.
echo "Creating logstash_writer role"
call PUT "/_security/role/logstash_writer" '{
  "cluster": ["monitor"],
  "indices": [
    {
      "names": ["logs-stadium-*"],
      "privileges": ["auto_configure", "create_doc", "view_index_metadata"]
    }
  ]
}' "logstash_writer role"

echo "Creating logstash_internal user"
call PUT "/_security/user/logstash_internal" \
  "{\"password\":\"${LOGSTASH_PASSWORD}\",\"roles\":[\"logstash_writer\"],\"full_name\":\"Logstash log shipper\"}" \
  "logstash_internal user"

# Scoped to the one index the search read model owns; no access to logs-stadium-*. create_index is
# needed because ElasticsearchMatchSearchAdapter lets the first index() call create "matches"
# implicitly rather than declaring a mapping up front.
echo "Creating catalog_app role"
call PUT "/_security/role/catalog_app" '{
  "cluster": ["monitor"],
  "indices": [
    {
      "names": ["matches"],
      "privileges": ["create_index", "read", "write", "view_index_metadata", "auto_configure"]
    }
  ]
}' "catalog_app role"

echo "Creating catalog_service user"
call PUT "/_security/user/catalog_service" \
  "{\"password\":\"${CATALOG_ES_PASSWORD}\",\"roles\":[\"catalog_app\"],\"full_name\":\"match-catalog-service read model\"}" \
  "catalog_service user"

echo "Elasticsearch security provisioning complete."

#!/usr/bin/env bash
# D:\Ledger\scripts\get-token.sh
# Usage: ./scripts/get-token.sh [client|alice|bob|admin]
# Prints an access token to stdout for manual curl testing against the gateway.
set -euo pipefail

MODE="${1:-client}"
KEYCLOAK_TOKEN_URL="http://localhost:8180/realms/ledger/protocol/openid-connect/token"

case "$MODE" in
  client)
    curl -sf -X POST "$KEYCLOAK_TOKEN_URL" \
      -d "grant_type=client_credentials" \
      -d "client_id=chaos-suite-client" \
      -d "client_secret=chaos-suite-secret" \
      | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4
    ;;
  alice|bob)
    curl -sf -X POST "$KEYCLOAK_TOKEN_URL" \
      -d "grant_type=password" \
      -d "client_id=demo-users-client" \
      -d "client_secret=demo-users-secret" \
      -d "username=$MODE" \
      -d "password=${MODE}-password" \
      | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4
    ;;
  admin)
    curl -sf -X POST "$KEYCLOAK_TOKEN_URL" \
      -d "grant_type=password" \
      -d "client_id=demo-users-client" \
      -d "client_secret=demo-users-secret" \
      -d "username=admin" \
      -d "password=admin-password" \
      | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4
    ;;
  *)
    echo "Unknown mode: $MODE (expected client|alice|bob|admin)" >&2
    exit 1
    ;;
esac

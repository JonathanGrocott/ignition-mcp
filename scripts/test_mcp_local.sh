#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

HOST_VALUE="${IGNITION_HOST:-${GATEWAY_URL:-${HOST:-http://localhost:8088}}}"
if [[ "$HOST_VALUE" != http://* && "$HOST_VALUE" != https://* ]]; then
  HOST_VALUE="http://$HOST_VALUE"
fi

MOUNT_ALIAS="${IGNITION_MCP_ALIAS:-ignition-mcp}"
BASE_URL="${MCP_BASE_URL:-$HOST_VALUE/data/$MOUNT_ALIAS}"
TOKEN_VALUE="${IGNITION_API_TOKEN:-${X_IGNITION_API_TOKEN:-${API_TOKEN:-${TOKEN:-}}}}"

if [[ -z "$TOKEN_VALUE" ]]; then
  echo "ERROR: Missing API token. Set TOKEN (or IGNITION_API_TOKEN/API_TOKEN) in $ENV_FILE" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq is required for this test script." >&2
  exit 1
fi

PASS_COUNT=0
FAIL_COUNT=0

RESPONSE_BODY=""
RESPONSE_STATUS=""
RESPONSE_HEADERS=""

request() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local session_id="${4:-}"

  local headers_file body_file
  headers_file="$(mktemp)"
  body_file="$(mktemp)"

  local curl_args=(
    -sS
    -o "$body_file"
    -D "$headers_file"
    -w "%{http_code}"
    -X "$method"
    "$BASE_URL$path"
    -H "X-Ignition-API-Token: $TOKEN_VALUE"
  )

  if [[ -n "$session_id" ]]; then
    curl_args+=( -H "Mcp-Session-Id: $session_id" )
  fi

  if [[ -n "$body" ]]; then
    curl_args+=(
      -H "Content-Type: application/json"
      --data "$body"
    )
  fi

  RESPONSE_STATUS="$(curl "${curl_args[@]}")"
  RESPONSE_BODY="$(cat "$body_file")"
  RESPONSE_HEADERS="$(cat "$headers_file")"

  rm -f "$body_file" "$headers_file"
}

header_value() {
  local key="$1"
  printf '%s\n' "$RESPONSE_HEADERS" | awk -F': ' -v k="$key" 'tolower($1)==tolower(k){print $2}' | tail -n 1 | tr -d '\r'
}

pass() {
  local msg="$1"
  PASS_COUNT=$((PASS_COUNT + 1))
  echo "PASS: $msg"
}

fail() {
  local msg="$1"
  FAIL_COUNT=$((FAIL_COUNT + 1))
  echo "FAIL: $msg"
}

assert_status() {
  local expected="$1"
  local label="$2"
  if [[ "$RESPONSE_STATUS" == "$expected" ]]; then
    pass "$label (status $expected)"
  else
    fail "$label (expected status $expected, got $RESPONSE_STATUS, body: $RESPONSE_BODY)"
  fi
}

assert_json_true() {
  local filter="$1"
  local label="$2"
  if ! echo "$RESPONSE_BODY" | jq -e . >/dev/null 2>&1; then
    fail "$label (response is not JSON, body: $RESPONSE_BODY)"
    return
  fi
  if echo "$RESPONSE_BODY" | jq -e "$filter" >/dev/null; then
    pass "$label"
  else
    fail "$label (filter: $filter, body: $RESPONSE_BODY)"
  fi
}

echo "Running MCP smoke tests against: $BASE_URL"

# 1) initialize
request "POST" "/mcp" '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
assert_status "200" "initialize request"
assert_json_true '.result.protocolVersion != null' "initialize returns protocolVersion"

if [[ "$RESPONSE_STATUS" != "200" ]]; then
  echo
  echo "Initialization failed. Check that the module is installed/enabled and route alias is correct."
  echo "Tried base URL: $BASE_URL"
  echo "Summary: $PASS_COUNT passed, $FAIL_COUNT failed"
  exit 1
fi

SESSION_ID="$(header_value "Mcp-Session-Id")"
if [[ -n "$SESSION_ID" ]]; then
  pass "session id returned from initialize"
else
  fail "session id missing from initialize response"
fi

# 2) tools/list
request "POST" "/mcp" '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' "$SESSION_ID"
assert_status "200" "tools/list request"
assert_json_true '.result.tools | map(.name) | index("ignition.tags.definition.read") != null' "tools/list contains ignition.tags.definition.read"
assert_json_true '.result.tools | map(.name) | index("ignition.tags.definition.write") != null' "tools/list contains ignition.tags.definition.write"

# 3) definition read
request "POST" "/mcp" '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ignition.tags.definition.read","arguments":{"paths":["[default]MCP"],"recursive":true}}}' "$SESSION_ID"
assert_status "200" "tags.definition.read request"
assert_json_true '.result.isError == false' "tags.definition.read returns success"

# 4) definition write dry-run
request "POST" "/mcp" '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"ignition.tags.definition.write","arguments":{"operation":"upsert","path":"[default]MCP/NewFromMcp","tagObjectType":"AtomicTag","properties":{"documentation":"created via mcp smoke test"}}}}' "$SESSION_ID"
assert_status "200" "tags.definition.write dry-run request"
assert_json_true '.result.isError == false' "tags.definition.write dry-run returns success"
assert_json_true '.result.structuredContent.dryRun == true' "tags.definition.write dry-run flagged"

# 5) definition write commit=true
request "POST" "/mcp" '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"ignition.tags.definition.write","arguments":{"operation":"upsert","path":"[default]MCP/NewFromMcp","tagObjectType":"AtomicTag","properties":{"documentation":"created via mcp smoke test"},"commit":true}}}' "$SESSION_ID"
assert_status "200" "tags.definition.write commit request"
assert_json_true '.result.isError == false' "tags.definition.write commit returns success"
assert_json_true '.result.structuredContent.updated == true' "tags.definition.write commit updated"

# 6) cleanup session
request "DELETE" "/mcp" '' "$SESSION_ID"
if [[ "$RESPONSE_STATUS" == "200" || "$RESPONSE_STATUS" == "204" ]]; then
  pass "session delete"
else
  fail "session delete (status $RESPONSE_STATUS, body: $RESPONSE_BODY)"
fi

echo
echo "Summary: $PASS_COUNT passed, $FAIL_COUNT failed"
if (( FAIL_COUNT > 0 )); then
  exit 1
fi

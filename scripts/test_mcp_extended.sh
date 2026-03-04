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
  local auth_header="${5:-Authorization: Bearer $TOKEN_VALUE}"

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
    -H "$auth_header"
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

assert_tool_present() {
  local tool_name="$1"
  local label="$2"
  if ! echo "$RESPONSE_BODY" | jq -e . >/dev/null 2>&1; then
    fail "$label (response is not JSON, body: $RESPONSE_BODY)"
    return
  fi
  if echo "$RESPONSE_BODY" | jq -e --arg t "$tool_name" '.result.tools | map(.name) | index($t) != null' >/dev/null; then
    pass "$label"
  else
    fail "$label (tool: $tool_name, body: $RESPONSE_BODY)"
  fi
}

wait_for_module_ready() {
  local max_wait_seconds="${1:-45}"
  local attempt=0
  while (( attempt < max_wait_seconds )); do
    request "POST" "/mcp" '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{}}' "" "X-Ignition-API-Token: "
    if [[ "$RESPONSE_STATUS" == "401" ]]; then
      return 0
    fi
    sleep 1
    attempt=$((attempt + 1))
  done
  return 1
}

cleanup_session() {
  local session_id="$1"
  [[ -z "$session_id" ]] && return
  request "DELETE" "/mcp?sessionId=$session_id" ""
}

echo "Running MCP extended tests against: $BASE_URL"

if wait_for_module_ready 45; then
  pass "gateway MCP route is responsive"
else
  fail "gateway MCP route did not become ready within timeout (last status $RESPONSE_STATUS)"
  echo
  echo "Summary: $PASS_COUNT passed, $FAIL_COUNT failed"
  exit 1
fi

# 1) No auth rejection
request "POST" "/mcp" '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{}}' "" "X-Ignition-API-Token: "
if [[ "$RESPONSE_STATUS" == "401" ]]; then
  pass "initialize without token is rejected"
else
  fail "initialize without token should return 401 (got $RESPONSE_STATUS)"
fi

# 2) initialize
request "POST" "/mcp" '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
assert_status "200" "initialize request"
assert_json_true '.result.protocolVersion != null' "initialize returns protocolVersion"
SESSION_ID="$(header_value "Mcp-Session-Id")"
if [[ -n "$SESSION_ID" ]]; then
  pass "session id returned from initialize"
else
  fail "session id missing from initialize response"
fi

# 3) tools list
request "POST" "/mcp" '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' "$SESSION_ID"
assert_status "200" "tools/list request"
for tool in \
  "ignition.tags.browse" \
  "ignition.tags.read" \
  "ignition.tags.write" \
  "ignition.tags.definition.read" \
  "ignition.tags.definition.write" \
  "ignition.projects.list" \
  "ignition.namedqueries.list" \
  "ignition.namedqueries.read" \
  "ignition.namedqueries.execute" \
  "ignition.historian.query" \
  "ignition.alarms.list" \
  "ignition.alarms.acknowledge"; do
  assert_tool_present "$tool" "tools/list contains $tool"
done

# 4) tags.browse providers
request "POST" "/mcp" '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ignition.tags.browse","arguments":{}}}' "$SESSION_ID"
assert_status "200" "tags.browse request"
assert_json_true '.result.isError == false' "tags.browse returns success"
assert_json_true '.result.structuredContent.providers | length > 0' "tags.browse returns providers"

# 5) tags.read on known sample tags
request "POST" "/mcp" '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"ignition.tags.read","arguments":{"paths":["[Sample_Tags]RampUDT/Ramp0","[Sample_Tags]RampUDT/Ramp1"]}}}' "$SESSION_ID"
assert_status "200" "tags.read request"
assert_json_true '.result.isError == false' "tags.read returns success"
assert_json_true '.result.structuredContent.values | length == 2' "tags.read returns two values"

# 6) definition.read recursive
request "POST" "/mcp" '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"ignition.tags.definition.read","arguments":{"paths":["[Sample_Tags]"],"recursive":true}}}' "$SESSION_ID"
assert_status "200" "tags.definition.read request"
assert_json_true '.result.isError == false' "tags.definition.read returns success"
assert_json_true '.result.structuredContent.count >= 1' "tags.definition.read count populated"

# 7) definition.write dry-run
request "POST" "/mcp" '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"ignition.tags.definition.write","arguments":{"operation":"upsert","path":"[default]MCP/IterTestTag","tagObjectType":"AtomicTag","properties":{"documentation":"extended test dry-run"}}}}' "$SESSION_ID"
assert_status "200" "tags.definition.write dry-run request"
assert_json_true '.result.isError == false' "tags.definition.write dry-run returns success"
assert_json_true '.result.structuredContent.dryRun == true' "tags.definition.write dry-run flagged"

# 8) definition.write commit true
request "POST" "/mcp" '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"ignition.tags.definition.write","arguments":{"operation":"upsert","path":"[default]MCP/IterTestTag","tagObjectType":"AtomicTag","properties":{"documentation":"extended test commit"},"commit":true}}}' "$SESSION_ID"
assert_status "200" "tags.definition.write commit request"
assert_json_true '.result.isError == false' "tags.definition.write commit returns success"
assert_json_true '.result.structuredContent.updated == true' "tags.definition.write commit updated"

# 9) safety: blocked write outside default allowlist
request "POST" "/mcp" '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"ignition.tags.write","arguments":{"commit":true,"writes":[{"path":"[Sample_Tags]RampUDT/Ramp0","value":12.34}]}}}' "$SESSION_ID"
assert_status "200" "tags.write blocked request still returns jsonrpc envelope"
assert_json_true '.result.isError == true' "tags.write outside allowlist blocked"

# 10) alarms list
request "POST" "/mcp" '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"ignition.alarms.list","arguments":{"state":"all","maxResults":25}}}' "$SESSION_ID"
assert_status "200" "alarms.list request"
assert_json_true '.result.isError == false' "alarms.list returns success"
assert_json_true '.result.structuredContent.alarms | type == "array"' "alarms.list alarms array present"

# 11) namedqueries.list, read, and execute
request "POST" "/mcp" '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"ignition.namedqueries.list","arguments":{}}}' "$SESSION_ID"
assert_status "200" "namedqueries.list request"
assert_json_true '.result.isError == false' "namedqueries.list returns success"
assert_json_true '.result.structuredContent.queries | type == "array"' "namedqueries.list queries array present"
NQ_LIST_BODY="$RESPONSE_BODY"

NQ_COUNT="$(echo "$NQ_LIST_BODY" | jq -r '.result.structuredContent.count // 0')"
if [[ "$NQ_COUNT" =~ ^[0-9]+$ ]] && (( NQ_COUNT > 0 )); then
  NQ_PROJECT="$(echo "$NQ_LIST_BODY" | jq -r '.result.structuredContent.queries[0].project')"
  NQ_PATH="$(echo "$NQ_LIST_BODY" | jq -r '.result.structuredContent.queries[0].path')"
  request "POST" "/mcp" "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{\"name\":\"ignition.namedqueries.read\",\"arguments\":{\"project\":\"$NQ_PROJECT\",\"path\":\"$NQ_PATH\"}}}" "$SESSION_ID"
  assert_status "200" "namedqueries.read request"
  assert_json_true '.result.isError == false' "namedqueries.read returns success"
  assert_json_true '.result.structuredContent.project != null and .result.structuredContent.path != null' "namedqueries.read includes identity"

  # namedqueries.execute dry-run should always succeed when allowlisted
  request "POST" "/mcp" "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\",\"params\":{\"name\":\"ignition.namedqueries.execute\",\"arguments\":{\"project\":\"$NQ_PROJECT\",\"path\":\"$NQ_PATH\"}}}" "$SESSION_ID"
  assert_status "200" "namedqueries.execute dry-run request"
  assert_json_true '.result.isError == false' "namedqueries.execute dry-run returns success"
  assert_json_true '.result.structuredContent.dryRun == true' "namedqueries.execute dry-run flagged"

  # commit test: find a named query with zero parameters to avoid required-input failures
  EXEC_PROJECT=""
  EXEC_PATH=""
  while IFS=$'\t' read -r PROJECT PATH_VALUE; do
    [[ -z "$PROJECT" || -z "$PATH_VALUE" ]] && continue
    request "POST" "/mcp" "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"tools/call\",\"params\":{\"name\":\"ignition.namedqueries.read\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"$PATH_VALUE\",\"includeQuery\":false}}}" "$SESSION_ID"
    if echo "$RESPONSE_BODY" | jq -e '.result.isError == false and (.result.structuredContent.parameterCount // 0) == 0' >/dev/null; then
      EXEC_PROJECT="$PROJECT"
      EXEC_PATH="$PATH_VALUE"
      break
    fi
  done < <(echo "$NQ_LIST_BODY" | jq -r '.result.structuredContent.queries[] | [.project,.path] | @tsv')

  if [[ -n "$EXEC_PROJECT" && -n "$EXEC_PATH" ]]; then
    request "POST" "/mcp" "{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"tools/call\",\"params\":{\"name\":\"ignition.namedqueries.execute\",\"arguments\":{\"project\":\"$EXEC_PROJECT\",\"path\":\"$EXEC_PATH\",\"commit\":true,\"includeResultData\":false}}}" "$SESSION_ID"
    assert_status "200" "namedqueries.execute commit request"
    assert_json_true '.result.isError == false' "namedqueries.execute commit returns success"
    assert_json_true '.result.structuredContent.executed == true' "namedqueries.execute commit flagged as executed"
  else
    pass "namedqueries.execute commit skipped (no zero-parameter query found)"
  fi
else
  pass "namedqueries read/execute skipped (no named queries found)"
fi

# 12) historian query (last hour)
NOW_SEC="$(date +%s)"
START_MS="$(( (NOW_SEC - 3600) * 1000 ))"
END_MS="$(( NOW_SEC * 1000 ))"
request "POST" "/mcp" "{\"jsonrpc\":\"2.0\",\"id\":15,\"method\":\"tools/call\",\"params\":{\"name\":\"ignition.historian.query\",\"arguments\":{\"paths\":[\"[Sample_Tags]RampUDT/Ramp0\"],\"start\":$START_MS,\"end\":$END_MS,\"maxRows\":100}}}" "$SESSION_ID"
assert_status "200" "historian.query request"
assert_json_true '.result.isError == false' "historian.query returns success"
assert_json_true '.result.structuredContent.rowCount | type == "number"' "historian.query rowCount present"

# 13) SSE fallback handshake
request "GET" "/sse"
SSE_SESSION_ID="$(header_value "Mcp-Session-Id")"
if [[ "$RESPONSE_STATUS" == "200" ]]; then
  pass "sse handshake request (status 200)"
else
  fail "sse handshake expected 200 (got $RESPONSE_STATUS, body: $RESPONSE_BODY)"
fi
if [[ -n "$SSE_SESSION_ID" ]]; then
  pass "sse session id returned"
else
  fail "sse session id missing"
fi
if printf '%s' "$RESPONSE_BODY" | rg -q 'event: message'; then
  pass "sse handshake returned event payload"
else
  fail "sse payload missing event: message"
fi

# cleanup
cleanup_session "$SESSION_ID"
cleanup_session "$SSE_SESSION_ID"

echo
echo "Summary: $PASS_COUNT passed, $FAIL_COUNT failed"
if (( FAIL_COUNT > 0 )); then
  exit 1
fi

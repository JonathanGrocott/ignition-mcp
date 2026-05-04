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
TEST_TAG_READ_PATH_1="${MCP_TEST_TAG_READ_PATH_1:-[Sample_Tags]RampUDT/Ramp0}"
TEST_TAG_READ_PATH_2="${MCP_TEST_TAG_READ_PATH_2:-[Sample_Tags]RampUDT/Ramp1}"
TEST_DEFINITION_READ_PATH="${MCP_TEST_DEFINITION_READ_PATH:-[Sample_Tags]}"
TEST_DEFINITION_WRITE_PATH="${MCP_TEST_DEFINITION_WRITE_PATH:-[default]MCP/IterTestTag}"
TEST_BLOCKED_WRITE_PATH="${MCP_TEST_BLOCKED_WRITE_PATH:-[Sample_Tags]RampUDT/Ramp0}"
TEST_UDT_TYPE_PATH="${MCP_TEST_UDT_TYPE_PATH:-[default]_types_/MCP/McpSmokeType}"
TEST_DELETE_PATH="${MCP_TEST_DELETE_PATH:-[default]MCP/McpSmokeDeleteCandidate}"
REQUIRE_TAG_WRITES="${MCP_REQUIRE_TAG_WRITES:-true}"
COMMIT_PROJECT_SCRIPT_TESTS="${MCP_COMMIT_PROJECT_SCRIPT_TESTS:-false}"
COMMIT_NAMED_QUERY_TESTS="${MCP_COMMIT_NAMED_QUERY_TESTS:-false}"

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
  "ignition.projects.resource.list" \
  "ignition.projects.resource.read" \
  "ignition.projects.resource.export" \
  "ignition.scripts.project.list" \
  "ignition.scripts.project.read" \
  "ignition.scripts.project.write" \
  "ignition.scripts.project.delete" \
  "ignition.scripts.project.import" \
  "ignition.namedqueries.list" \
  "ignition.namedqueries.read" \
  "ignition.namedqueries.execute" \
  "ignition.namedqueries.write" \
  "ignition.namedqueries.delete" \
  "ignition.namedqueries.import" \
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
TAG_READ_PAYLOAD="$(jq -nc --arg p1 "$TEST_TAG_READ_PATH_1" --arg p2 "$TEST_TAG_READ_PATH_2" '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"ignition.tags.read","arguments":{"paths":[$p1,$p2]}}}')"
request "POST" "/mcp" "$TAG_READ_PAYLOAD" "$SESSION_ID"
assert_status "200" "tags.read request"
assert_json_true '.result.isError == false' "tags.read returns success"
assert_json_true '.result.structuredContent.values | length == 2' "tags.read returns two values"

# 6) definition.read recursive
DEFINITION_READ_PAYLOAD="$(jq -nc --arg path "$TEST_DEFINITION_READ_PATH" '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"ignition.tags.definition.read","arguments":{"paths":[$path],"recursive":true}}}')"
request "POST" "/mcp" "$DEFINITION_READ_PAYLOAD" "$SESSION_ID"
assert_status "200" "tags.definition.read request"
assert_json_true '.result.isError == false' "tags.definition.read returns success"
assert_json_true '.result.structuredContent.count >= 1' "tags.definition.read count populated"

# 7/8) definition.write dry-run and commit true
if [[ "$REQUIRE_TAG_WRITES" == "true" ]]; then
  DEFINITION_WRITE_DRY_RUN_PAYLOAD="$(jq -nc --arg path "$TEST_DEFINITION_WRITE_PATH" '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"ignition.tags.definition.write","arguments":{"operation":"upsert","path":$path,"tagObjectType":"AtomicTag","properties":{"documentation":"extended test dry-run"}}}}')"
  request "POST" "/mcp" "$DEFINITION_WRITE_DRY_RUN_PAYLOAD" "$SESSION_ID"
  assert_status "200" "tags.definition.write dry-run request"
  assert_json_true '.result.isError == false' "tags.definition.write dry-run returns success"
  assert_json_true '.result.structuredContent.dryRun == true' "tags.definition.write dry-run flagged"

  DEFINITION_WRITE_COMMIT_PAYLOAD="$(jq -nc --arg path "$TEST_DEFINITION_WRITE_PATH" '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"ignition.tags.definition.write","arguments":{"operation":"upsert","path":$path,"tagObjectType":"AtomicTag","properties":{"documentation":"extended test commit"},"commit":true}}}')"
  request "POST" "/mcp" "$DEFINITION_WRITE_COMMIT_PAYLOAD" "$SESSION_ID"
  assert_status "200" "tags.definition.write commit request"
  assert_json_true '.result.isError == false' "tags.definition.write commit returns success"
  assert_json_true '.result.structuredContent.updated == true' "tags.definition.write commit updated"
else
  pass "tag definition write tests skipped (MCP_REQUIRE_TAG_WRITES=false)"
fi

# 9) safety: blocked write outside default allowlist
BLOCKED_WRITE_PAYLOAD="$(jq -nc --arg path "$TEST_BLOCKED_WRITE_PATH" '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"ignition.tags.write","arguments":{"commit":true,"writes":[{"path":$path,"value":12.34}]}}}')"
request "POST" "/mcp" "$BLOCKED_WRITE_PAYLOAD" "$SESSION_ID"
assert_status "200" "tags.write blocked request still returns jsonrpc envelope"
assert_json_true '.result.isError == true' "tags.write outside allowlist blocked"
assert_json_true '.result.structuredContent.code != null' "tags.write blocked includes structured error code"

# 10) UDT definition dry-run and delete dry-run
if [[ "$REQUIRE_TAG_WRITES" == "true" ]]; then
  UDT_DRY_RUN_PAYLOAD="$(jq -nc --arg path "$TEST_UDT_TYPE_PATH" '{"jsonrpc":"2.0","id":90,"method":"tools/call","params":{"name":"ignition.tags.definition.write","arguments":{"operation":"create","path":$path,"tagObjectType":"UdtType","children":[{"name":"Value","tagObjectType":"AtomicTag","properties":{"documentation":"MCP smoke UDT member"}}]}}}')"
  request "POST" "/mcp" "$UDT_DRY_RUN_PAYLOAD" "$SESSION_ID"
  assert_status "200" "tags.definition.write UDT dry-run request"
  assert_json_true '.result.isError == false' "tags.definition.write UDT dry-run returns success"
  assert_json_true '.result.structuredContent.dryRun == true and (.result.structuredContent.children | length) == 1' "tags.definition.write UDT dry-run includes child plan"

  DELETE_DRY_RUN_PAYLOAD="$(jq -nc --arg path "$TEST_DELETE_PATH" '{"jsonrpc":"2.0","id":91,"method":"tools/call","params":{"name":"ignition.tags.definition.write","arguments":{"operation":"delete","path":$path}}}')"
  request "POST" "/mcp" "$DELETE_DRY_RUN_PAYLOAD" "$SESSION_ID"
  assert_status "200" "tags.definition.write delete dry-run request"
  assert_json_true '.result.isError == false' "tags.definition.write delete dry-run returns success"
  assert_json_true '.result.structuredContent.dryRun == true and .result.structuredContent.operation == "delete"' "tags.definition.write delete dry-run flagged"
else
  pass "UDT/delete tag definition write tests skipped (MCP_REQUIRE_TAG_WRITES=false)"
fi

# 11) projects, project resources, and project scripts
request "POST" "/mcp" '{"jsonrpc":"2.0","id":92,"method":"tools/call","params":{"name":"ignition.projects.list","arguments":{"includeNamedQueryCounts":true}}}' "$SESSION_ID"
assert_status "200" "projects.list request"
assert_json_true '.result.isError == false' "projects.list returns success"
assert_json_true '.result.structuredContent.projects | type == "array"' "projects.list projects array present"
PROJECTS_BODY="$RESPONSE_BODY"
MUTABLE_PROJECT="$(echo "$PROJECTS_BODY" | jq -r '.result.structuredContent.projects[]? | select(.mutable == true) | .name' | head -n 1)"

request "POST" "/mcp" '{"jsonrpc":"2.0","id":93,"method":"tools/call","params":{"name":"ignition.projects.resource.list","arguments":{"includeData":false}}}' "$SESSION_ID"
assert_status "200" "projects.resource.list request"
assert_json_true '.result.isError == false' "projects.resource.list returns success"
assert_json_true '.result.structuredContent.resources | type == "array"' "projects.resource.list resources array present"
RESOURCE_LIST_BODY="$RESPONSE_BODY"
RESOURCE_PROJECT="$(echo "$RESOURCE_LIST_BODY" | jq -r '.result.structuredContent.resources[0].project // empty')"
RESOURCE_MODULE="$(echo "$RESOURCE_LIST_BODY" | jq -r '.result.structuredContent.resources[0].moduleId // empty')"
RESOURCE_TYPE="$(echo "$RESOURCE_LIST_BODY" | jq -r '.result.structuredContent.resources[0].resourceType // empty')"
RESOURCE_PATH="$(echo "$RESOURCE_LIST_BODY" | jq -r '.result.structuredContent.resources[0].path // empty')"
if [[ -n "$RESOURCE_PROJECT" && -n "$RESOURCE_MODULE" && -n "$RESOURCE_TYPE" ]]; then
  RESOURCE_READ_PAYLOAD="$(jq -nc --arg project "$RESOURCE_PROJECT" --arg moduleId "$RESOURCE_MODULE" --arg resourceType "$RESOURCE_TYPE" --arg path "$RESOURCE_PATH" '{"jsonrpc":"2.0","id":94,"method":"tools/call","params":{"name":"ignition.projects.resource.read","arguments":{"project":$project,"moduleId":$moduleId,"resourceType":$resourceType,"path":$path,"includeData":false}}}')"
  request "POST" "/mcp" "$RESOURCE_READ_PAYLOAD" "$SESSION_ID"
  assert_status "200" "projects.resource.read request"
  assert_json_true '.result.isError == false' "projects.resource.read returns success"
  assert_json_true '.result.structuredContent.resource.moduleId != null' "projects.resource.read includes resource metadata"
else
  pass "projects.resource.read skipped (no project resources found)"
fi

if [[ -n "$RESOURCE_PROJECT" ]]; then
  RESOURCE_EXPORT_PAYLOAD="$(jq -nc --arg project "$RESOURCE_PROJECT" '{"jsonrpc":"2.0","id":100,"method":"tools/call","params":{"name":"ignition.projects.resource.export","arguments":{"project":$project,"maxResources":10}}}')"
  request "POST" "/mcp" "$RESOURCE_EXPORT_PAYLOAD" "$SESSION_ID"
  assert_status "200" "projects.resource.export request"
  assert_json_true '.result.isError == false' "projects.resource.export returns success"
  assert_json_true '.result.structuredContent.bundle.format == "ignition-mcp.project-resource-bundle.v1"' "projects.resource.export bundle format"
else
  pass "projects.resource.export skipped (no project resources found)"
fi

request "POST" "/mcp" '{"jsonrpc":"2.0","id":95,"method":"tools/call","params":{"name":"ignition.scripts.project.list","arguments":{"includeCode":false}}}' "$SESSION_ID"
assert_status "200" "scripts.project.list request"
assert_json_true '.result.isError == false' "scripts.project.list returns success"
assert_json_true '.result.structuredContent.scripts | type == "array"' "scripts.project.list scripts array present"

if [[ -n "$MUTABLE_PROJECT" ]]; then
  SCRIPT_WRITE_PAYLOAD="$(jq -nc --arg project "$MUTABLE_PROJECT" '{"jsonrpc":"2.0","id":96,"method":"tools/call","params":{"name":"ignition.scripts.project.write","arguments":{"project":$project,"scriptType":"startup","code":"print '\''mcp smoke startup dry run'\''"}}}')"
  request "POST" "/mcp" "$SCRIPT_WRITE_PAYLOAD" "$SESSION_ID"
  assert_status "200" "scripts.project.write dry-run request"
  assert_json_true '.result.isError == false' "scripts.project.write dry-run returns success"
  assert_json_true '.result.structuredContent.dryRun == true' "scripts.project.write dry-run flagged"

  SCRIPT_DELETE_PAYLOAD="$(jq -nc --arg project "$MUTABLE_PROJECT" '{"jsonrpc":"2.0","id":97,"method":"tools/call","params":{"name":"ignition.scripts.project.delete","arguments":{"project":$project,"scriptType":"startup"}}}')"
  request "POST" "/mcp" "$SCRIPT_DELETE_PAYLOAD" "$SESSION_ID"
  assert_status "200" "scripts.project.delete dry-run request"
  assert_json_true '.result.isError == false' "scripts.project.delete dry-run returns success"
  assert_json_true '.result.structuredContent.dryRun == true' "scripts.project.delete dry-run flagged"

  SCRIPT_IMPORT_PAYLOAD="$(jq -nc --arg project "$MUTABLE_PROJECT" '{"jsonrpc":"2.0","id":101,"method":"tools/call","params":{"name":"ignition.scripts.project.import","arguments":{"targetProject":$project,"bundle":{"resources":[{"moduleId":"ignition","resourceType":"script-app-library","path":"MCP/SmokeImport","applicationScope":7,"version":1,"documentation":"","data":{"resource.json":{"encoding":"utf-8","value":"{\"scripts\":{\"MCP/SmokeImport\":\"print '\''mcp import dry run'\''\"}}"}}}]}}}}')"
  request "POST" "/mcp" "$SCRIPT_IMPORT_PAYLOAD" "$SESSION_ID"
  assert_status "200" "scripts.project.import dry-run request"
  assert_json_true '.result.isError == false' "scripts.project.import dry-run returns success"
  assert_json_true '.result.structuredContent.dryRun == true and .result.structuredContent.importCount == 1' "scripts.project.import dry-run plan"

  if [[ "$COMMIT_PROJECT_SCRIPT_TESTS" == "true" ]]; then
    SCRIPT_COMMIT_PATH="MCP/SmokeCommitLibrary"
    SCRIPT_COMMIT_WRITE_PAYLOAD="$(jq -nc --arg project "$MUTABLE_PROJECT" --arg path "$SCRIPT_COMMIT_PATH" '{"jsonrpc":"2.0","id":103,"method":"tools/call","params":{"name":"ignition.scripts.project.write","arguments":{"project":$project,"scriptType":"library","path":$path,"operation":"upsert","code":"print '\''mcp committed library smoke'\''","commit":true}}}')"
    request "POST" "/mcp" "$SCRIPT_COMMIT_WRITE_PAYLOAD" "$SESSION_ID"
    assert_status "200" "scripts.project.write commit request"
    assert_json_true '.result.isError == false' "scripts.project.write commit returns success"

    SCRIPT_COMMIT_READ_PAYLOAD="$(jq -nc --arg project "$MUTABLE_PROJECT" --arg path "$SCRIPT_COMMIT_PATH" '{"jsonrpc":"2.0","id":104,"method":"tools/call","params":{"name":"ignition.scripts.project.read","arguments":{"project":$project,"scriptType":"library","path":$path}}}')"
    request "POST" "/mcp" "$SCRIPT_COMMIT_READ_PAYLOAD" "$SESSION_ID"
    assert_status "200" "scripts.project.read committed resource request"
    assert_json_true '.result.isError == false' "scripts.project.read committed resource returns success"

    SCRIPT_COMMIT_DELETE_PAYLOAD="$(jq -nc --arg project "$MUTABLE_PROJECT" --arg path "$SCRIPT_COMMIT_PATH" '{"jsonrpc":"2.0","id":105,"method":"tools/call","params":{"name":"ignition.scripts.project.delete","arguments":{"project":$project,"scriptType":"library","path":$path,"commit":true}}}')"
    request "POST" "/mcp" "$SCRIPT_COMMIT_DELETE_PAYLOAD" "$SESSION_ID"
    assert_status "200" "scripts.project.delete commit request"
    assert_json_true '.result.isError == false' "scripts.project.delete commit returns success"
  else
    pass "scripts.project commit create/read/delete skipped (MCP_COMMIT_PROJECT_SCRIPT_TESTS=false)"
  fi
else
  pass "scripts.project write/delete dry-runs skipped (no mutable project found)"
fi

# 12) alarms list
request "POST" "/mcp" '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"ignition.alarms.list","arguments":{"state":"all","maxResults":25}}}' "$SESSION_ID"
assert_status "200" "alarms.list request"
assert_json_true '.result.isError == false' "alarms.list returns success"
assert_json_true '.result.structuredContent.alarms | type == "array"' "alarms.list alarms array present"

# 13) namedqueries.list, read, execute, write dry-run, and delete dry-run
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

if [[ -n "$MUTABLE_PROJECT" ]]; then
  NQ_WRITE_PAYLOAD="$(jq -nc --arg project "$MUTABLE_PROJECT" '{"jsonrpc":"2.0","id":98,"method":"tools/call","params":{"name":"ignition.namedqueries.write","arguments":{"project":$project,"path":"MCP/Smoke Dry Run Query","type":"ScalarQuery","query":"SELECT 1","parameters":[]}}}')"
  request "POST" "/mcp" "$NQ_WRITE_PAYLOAD" "$SESSION_ID"
  assert_status "200" "namedqueries.write dry-run request"
  assert_json_true '.result.isError == false' "namedqueries.write dry-run returns success"
  assert_json_true '.result.structuredContent.dryRun == true' "namedqueries.write dry-run flagged"

  NQ_DELETE_PAYLOAD="$(jq -nc --arg project "$MUTABLE_PROJECT" '{"jsonrpc":"2.0","id":99,"method":"tools/call","params":{"name":"ignition.namedqueries.delete","arguments":{"project":$project,"path":"MCP/Smoke Dry Run Query"}}}')"
  request "POST" "/mcp" "$NQ_DELETE_PAYLOAD" "$SESSION_ID"
  assert_status "200" "namedqueries.delete dry-run request"
  assert_json_true '.result.isError == false' "namedqueries.delete dry-run returns success"
  assert_json_true '.result.structuredContent.dryRun == true' "namedqueries.delete dry-run flagged"

  NQ_IMPORT_PAYLOAD="$(jq -nc --arg project "$MUTABLE_PROJECT" '{"jsonrpc":"2.0","id":102,"method":"tools/call","params":{"name":"ignition.namedqueries.import","arguments":{"targetProject":$project,"bundle":{"resources":[{"moduleId":"ignition","resourceType":"named-query","path":"MCP/Smoke Imported Query","applicationScope":7,"version":1,"documentation":"","data":{"resource.json":{"encoding":"utf-8","value":"{}"}}}]}}}}')"
  request "POST" "/mcp" "$NQ_IMPORT_PAYLOAD" "$SESSION_ID"
  assert_status "200" "namedqueries.import dry-run request"
  assert_json_true '.result.isError == false' "namedqueries.import dry-run returns success"
  assert_json_true '.result.structuredContent.dryRun == true and .result.structuredContent.importCount == 1' "namedqueries.import dry-run plan"

  if [[ "$COMMIT_NAMED_QUERY_TESTS" == "true" ]]; then
    NQ_COMMIT_PATH="MCP/Smoke Commit Query"
    NQ_COMMIT_WRITE_PAYLOAD="$(jq -nc --arg project "$MUTABLE_PROJECT" --arg path "$NQ_COMMIT_PATH" '{"jsonrpc":"2.0","id":106,"method":"tools/call","params":{"name":"ignition.namedqueries.write","arguments":{"project":$project,"path":$path,"operation":"upsert","type":"ScalarQuery","query":"SELECT 1","parameters":[],"commit":true}}}')"
    request "POST" "/mcp" "$NQ_COMMIT_WRITE_PAYLOAD" "$SESSION_ID"
    assert_status "200" "namedqueries.write commit request"
    assert_json_true '.result.isError == false' "namedqueries.write commit returns success"

    NQ_COMMIT_READ_PAYLOAD="$(jq -nc --arg project "$MUTABLE_PROJECT" --arg path "$NQ_COMMIT_PATH" '{"jsonrpc":"2.0","id":107,"method":"tools/call","params":{"name":"ignition.namedqueries.read","arguments":{"project":$project,"path":$path}}}')"
    request "POST" "/mcp" "$NQ_COMMIT_READ_PAYLOAD" "$SESSION_ID"
    assert_status "200" "namedqueries.read committed resource request"
    assert_json_true '.result.isError == false' "namedqueries.read committed resource returns success"

    NQ_COMMIT_DELETE_PAYLOAD="$(jq -nc --arg project "$MUTABLE_PROJECT" --arg path "$NQ_COMMIT_PATH" '{"jsonrpc":"2.0","id":108,"method":"tools/call","params":{"name":"ignition.namedqueries.delete","arguments":{"project":$project,"path":$path,"commit":true}}}')"
    request "POST" "/mcp" "$NQ_COMMIT_DELETE_PAYLOAD" "$SESSION_ID"
    assert_status "200" "namedqueries.delete commit request"
    assert_json_true '.result.isError == false' "namedqueries.delete commit returns success"
  else
    pass "namedqueries commit create/read/delete skipped (MCP_COMMIT_NAMED_QUERY_TESTS=false)"
  fi
else
  pass "namedqueries write/delete dry-runs skipped (no mutable project found)"
fi

# 14) historian query (last hour)
NOW_SEC="$(date +%s)"
START_MS="$(( (NOW_SEC - 3600) * 1000 ))"
END_MS="$(( NOW_SEC * 1000 ))"
request "POST" "/mcp" "{\"jsonrpc\":\"2.0\",\"id\":15,\"method\":\"tools/call\",\"params\":{\"name\":\"ignition.historian.query\",\"arguments\":{\"paths\":[\"[Sample_Tags]RampUDT/Ramp0\"],\"start\":$START_MS,\"end\":$END_MS,\"maxRows\":100}}}" "$SESSION_ID"
assert_status "200" "historian.query request"
assert_json_true '.result.isError == false' "historian.query returns success"
assert_json_true '.result.structuredContent.rowCount | type == "number"' "historian.query rowCount present"

# 15) SSE fallback handshake
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

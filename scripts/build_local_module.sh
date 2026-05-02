#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR"

echo "Building Ignition MCP module..."
./gradlew clean test zipModule checksumModl

MODULE_PATH="$ROOT_DIR/build/ignition-mcp.unsigned.modl"
if [[ ! -f "$MODULE_PATH" ]]; then
  echo "ERROR: Expected module artifact not found: $MODULE_PATH" >&2
  exit 1
fi

echo
echo "Module built:"
echo "  $MODULE_PATH"
echo
echo "Install it in the Ignition Gateway UI:"
echo "  Config > System > Modules > Install or Upgrade a Module"
echo
echo "Then create/configure an Ignition API token and run:"
echo "  cp .env.example .env"
echo "  ./scripts/test_mcp_local.sh"
echo "  ./scripts/test_mcp_extended.sh"

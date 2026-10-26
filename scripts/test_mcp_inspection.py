#!/usr/bin/env python3
"""Read-only live checks for native inspection tools. Never provisions fixtures or commits changes."""
import json
import os
import sys
import urllib.error
import urllib.request


def main():
    base = os.environ.get("MCP_BASE_URL", "http://localhost:8088/data/ignition-mcp").rstrip("/")
    token = os.environ.get("IGNITION_API_TOKEN", "")
    if not token:
        raise ValueError("Set IGNITION_API_TOKEN and MCP_BASE_URL in the environment")
    session = None
    request_id = 0
    checks = []
    expect_fixtures = os.environ.get("MCP_INSPECTION_EXPECT_FIXTURES", "false").lower() == "true"
    expect_pipeline = os.environ.get("MCP_INSPECTION_EXPECT_PIPELINE", "false").lower() == "true"

    def rpc(method, params):
        nonlocal session, request_id
        request_id += 1
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                   "X-Ignition-API-Token": token}
        if session:
            headers["Mcp-Session-Id"] = session
        payload = {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}
        request = urllib.request.Request(base + "/mcp", json.dumps(payload).encode(), headers)
        with urllib.request.urlopen(request, timeout=45) as response:
            session = response.headers.get("Mcp-Session-Id", session)
            body = json.load(response)
        if "error" in body:
            raise AssertionError("RPC error code: " + str(body["error"].get("code")))
        return body["result"]

    def tool(name, args, expected_key):
        result = rpc("tools/call", {"name": name, "arguments": args})
        data = result.get("structuredContent", {})
        if result.get("isError"):
            raise AssertionError(name + ": " + data.get("code", "TOOL_ERROR"))
        if expected_key not in data:
            raise AssertionError(name + ": missing " + expected_key)
        checks.append({"tool": name, "ok": True, "truncated": data.get("truncated", False),
                       "complete": data.get("complete")})
        print("PASS " + name)
        return data

    try:
        initialized = rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                          "clientInfo": {"name": "inspection-smoke", "version": "1"}})
        catalog = rpc("tools/list", {})
        expected = {
            "ignition.perspective.views.list", "ignition.perspective.views.read",
            "ignition.alarms.pipelines.list", "ignition.alarms.pipelines.read",
            "ignition.projects.search", "ignition.projects.references",
            "ignition.udts.list", "ignition.udts.read", "ignition.tags.opc.search", "ignition.tags.snapshot",
            "ignition.gateway.status", "ignition.gateway.modules", "ignition.gateway.connections",
            "ignition.gateway.logs", "ignition.gateway.diagnose",
        }
        available = {item["name"] for item in catalog["tools"]}
        if expected - available:
            raise AssertionError("Missing inspection tools: " + ", ".join(sorted(expected - available)))
        print("PASS tool catalog (" + str(len(available)) + " tools)")
        project = os.environ.get("MCP_INSPECTION_PROJECT", "")
        filters = {"project": project} if project else {}
        views = tool("ignition.perspective.views.list", {**filters, "maxResults": 10}, "views")
        if views["views"]:
            selected = views["views"][0]
            tool("ignition.perspective.views.read", {"project": selected["project"], "path": selected["path"],
                                                     "maxComponents": 30}, "root")
        else:
            checks.append({"tool": "ignition.perspective.views.read", "skipped": "No authorized views"})
            if expect_fixtures:
                raise AssertionError("Expected at least one authorized Perspective view")
        pipelines = tool("ignition.alarms.pipelines.list", {**filters, "maxResults": 10}, "pipelines")
        if pipelines["pipelines"]:
            selected = pipelines["pipelines"][0]
            result = rpc("tools/call", {"name": "ignition.alarms.pipelines.read", "arguments": {
                "project": selected["project"], "path": selected["path"], "resourceType": selected["resourceType"]}})
            data = result.get("structuredContent", {})
            if result.get("isError"):
                if expect_pipeline:
                    raise AssertionError("Expected a readable pipeline: " + data.get("code", "TOOL_ERROR"))
                if data.get("code") != "UNSUPPORTED_FORMAT":
                    raise AssertionError("pipelines.read: " + data.get("code", "TOOL_ERROR"))
                checks.append({"tool": "ignition.alarms.pipelines.read", "unsupported": True})
                print("UNSUPPORTED pipeline data format (reported explicitly)")
            else:
                if not data.get("configuration"):
                    raise AssertionError("Pipeline read returned no configuration")
                checks.append({"tool": "ignition.alarms.pipelines.read", "ok": True})
                print("PASS ignition.alarms.pipelines.read")
        else:
            checks.append({"tool": "ignition.alarms.pipelines.read", "skipped": "No authorized pipelines"})
            if expect_pipeline:
                raise AssertionError("Expected at least one authorized pipeline fixture")
        query = os.environ.get("MCP_INSPECTION_QUERY", "[default]")
        for name, key in [("ignition.projects.search", "query"), ("ignition.projects.references", "target")]:
            found = tool(name, {**filters, key: query, "maxResults": 10}, "matches")
            if expect_fixtures and not found["matches"]:
                raise AssertionError("Expected source matches for " + name)
        udt_args = {"provider": os.environ.get("MCP_INSPECTION_PROVIDER", "default"), "maxTags": 30}
        if os.environ.get("MCP_INSPECTION_UDT_PATH"):
            udt_args["path"] = os.environ["MCP_INSPECTION_UDT_PATH"]
        definitions = tool("ignition.udts.list", udt_args, "definitions")
        if definitions["definitions"]:
            tool("ignition.udts.read", {"path": definitions["definitions"][0]["path"], "maxTags": 30}, "definitions")
        else:
            checks.append({"tool": "ignition.udts.read", "skipped": "No authorized UDT definitions"})
            if expect_fixtures:
                raise AssertionError("Expected at least one authorized UDT definition")
        path = os.environ.get("MCP_INSPECTION_TAG_PATH")
        if path:
            for name in ("ignition.tags.snapshot", "ignition.tags.opc.search"):
                found = tool(name, {"path": path, "maxTags": 30, "maxBrowses": 10}, "tags")
                if expect_fixtures and not found["tags"]:
                    raise AssertionError("Expected populated tag results for " + name)
        else:
            for name in ("ignition.tags.snapshot", "ignition.tags.opc.search"):
                checks.append({"tool": name, "skipped": "Set MCP_INSPECTION_TAG_PATH to an authorized root"})
            if expect_fixtures:
                raise AssertionError("Set MCP_INSPECTION_TAG_PATH when expecting populated fixtures")
        for name, key in [("status", "state"), ("modules", "modules"), ("connections", "databases"),
                          ("logs", "events"), ("diagnose", "verdict")]:
            tool("ignition.gateway." + name, {"maxResults": 10}, key)
        summary = {"server": initialized.get("serverInfo"), "catalogCount": len(available), "checks": checks}
        output = os.environ.get("MCP_INSPECTION_REPORT")
        if output:
            with open(output, "w", encoding="utf-8") as handle:
                json.dump(summary, handle, indent=2)
        print("Completed: " + str(sum(bool(c.get("ok")) for c in checks)) + " passed; "
              + str(sum("skipped" in c for c in checks)) + " skipped; "
              + str(sum(bool(c.get("unsupported")) for c in checks)) + " unsupported")
    finally:
        if session:
            try:
                request = urllib.request.Request(base + "/mcp", method="DELETE", headers={
                    "X-Ignition-API-Token": token, "Mcp-Session-Id": session})
                with urllib.request.urlopen(request, timeout=10):
                    pass
            except (OSError, urllib.error.HTTPError):
                pass


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        print("FAIL HTTP " + str(error.code), file=sys.stderr)
        sys.exit(1)
    except (ValueError, AssertionError, OSError) as error:
        print("FAIL " + str(error), file=sys.stderr)
        sys.exit(1)

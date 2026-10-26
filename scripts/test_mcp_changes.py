#!/usr/bin/env python3
"""Change-plan checks. Default: preview only. Opt-in commits are restricted to a loopback validation project."""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    base = os.environ.get("MCP_BASE_URL", "http://localhost:8088/data/ignition-mcp").rstrip("/")
    token = os.environ.get("IGNITION_API_TOKEN", "")
    project = os.environ.get("MCP_CHANGE_PROJECT", "")
    commit = os.environ.get("MCP_COMMIT_CHANGE_TESTS", "false").lower() == "true"
    if not token or not project:
        raise ValueError("Set IGNITION_API_TOKEN, MCP_BASE_URL and MCP_CHANGE_PROJECT")
    if commit and (urllib.parse.urlsplit(base).hostname not in {"localhost", "127.0.0.1", "::1"}
                   or not project.startswith("McpChangeValidation")):
        raise ValueError("Commit checks require a loopback Gateway and a dedicated McpChangeValidation project")
    suffix = uuid.uuid4().hex[:12]
    path = "MCP/Workflow_" + suffix
    checks = []
    session = None
    request_id = 0

    def rpc(name, args):
        nonlocal session, request_id
        request_id += 1
        payload = {"jsonrpc": "2.0", "id": request_id, "method": "tools/call", "params": {"name": name, "arguments": args}}
        headers = {"Content-Type": "application/json", "X-Ignition-API-Token": token}
        if session:
            headers["Mcp-Session-Id"] = session
        with urllib.request.urlopen(urllib.request.Request(base + "/mcp", json.dumps(payload).encode(), headers), timeout=45) as response:
            session = response.headers.get("Mcp-Session-Id", session)
            return json.load(response)

    def tool(name, args):
        result = rpc(name, args)
        if "error" in result:
            raise AssertionError(name + ": " + result["error"].get("data", {}).get("code", "RPC_ERROR"))
        body = result["result"]
        if body.get("isError"):
            raise AssertionError(name + ": " + body.get("structuredContent", {}).get("code", "TOOL_ERROR"))
        return body["structuredContent"]

    def check(label, condition):
        if not condition:
            raise AssertionError(label)
        checks.append(label)
        print("PASS " + label)

    def resource(code):
        return {"moduleId": "ignition", "resourceType": "script-python", "path": path, "operation": "create",
                "applicationScope": "A", "version": 1, "restricted": False, "overridable": True,
                "attributes": {"validationMarker": suffix},
                "data": {"code.py": {"encoding": "utf-8", "value": code}}}

    def bundle(rows):
        return {"format": "ignition-mcp.project-resource-bundle.v1", "resources": rows}

    def preview(rows):
        return tool("ignition.projects.changes.preview", {"targetProject": project, "bundle": bundle(rows)})

    def apply(plan):
        return tool("ignition.projects.changes.apply", {"planId": plan["planId"], "planHash": plan["planHash"], "commit": True})

    try:
        row = resource("def value():\n    return 42\n")
        inspected = tool("ignition.projects.bundle.inspect", {"bundle": bundle([row])})
        check("offline bundle integrity inspection", inspected["resourceCount"] == 1 and inspected["complete"])
        plan = preview([row])
        check("preview stages one create without applying", plan["dryRun"] and plan["changes"][0]["action"] == "create")
        missing = tool("ignition.projects.resource.list", {"project": project, "moduleId": "ignition", "resourceType": "script-python", "pathPrefix": path})
        check("preview left the Gateway resource absent", not missing["resources"])
        if not commit:
            print("Commit checks skipped; set MCP_COMMIT_CHANGE_TESTS=true only for an isolated validation Gateway")
        else:
            created = apply(plan)
            check("create verified by readback", created["verified"] and created["committed"])
            replay = apply(plan)
            check("replay returns the stored receipt", replay["alreadyAttempted"] and replay["verified"])
            current = tool("ignition.projects.resource.read", {"project": project, "moduleId": "ignition", "resourceType": "script-python", "path": path})["resource"]
            check("script bytes and custom attributes survived", current["data"]["code.py"]["value"] == row["data"]["code.py"]["value"]
                  and current["attributes"]["validationMarker"] == suffix)
            edit = dict(current, operation="edit")
            edit["data"] = {"code.py": {"encoding": "utf-8", "value": "def value():\n    return 43\n"}}
            edited = apply(preview([edit]))
            check("edit verified by readback", edited["verified"])
            stale = preview([dict(edit, operation="upsert", data={"code.py": {"encoding": "utf-8", "value": "answer = 99\n"}})])
            intervening = dict(edit, operation="edit", data={"code.py": {"encoding": "utf-8", "value": "answer = 44\n"}})
            check("intervening edit verified", apply(preview([intervening]))["verified"])
            result = rpc("ignition.projects.changes.apply", {"planId": stale["planId"], "planHash": stale["planHash"], "commit": True})
            check("stale preview rejected", result.get("result", {}).get("structuredContent", {}).get("code") == "CONFLICT")
            current = tool("ignition.projects.resource.read", {"project": project, "moduleId": "ignition", "resourceType": "script-python", "path": path})["resource"]
            check("conflict preserved intervening content", current["data"]["code.py"]["value"] == "answer = 44\n")
            delete = {"moduleId": "ignition", "resourceType": "script-python", "path": path, "operation": "delete"}
            deleted = apply(preview([delete]))
            check("explicit deletion verified absent", deleted["verified"] and deleted["verification"][0]["actualHash"] == "absent")
            receipt = tool("ignition.projects.changes.status", {"planId": plan["planId"]})
            check("receipt remains an observation of its apply", receipt["state"] == "verified" and receipt["verified"])

            query_path = "MCP/Query_" + suffix
            tool("ignition.namedqueries.write", {"project": project, "path": query_path, "operation": "create", "type": "ScalarQuery",
                                                 "query": "SELECT 1", "database": "SyntheticDatabase", "commit": True})
            query = tool("ignition.projects.resource.read", {"project": project, "moduleId": "ignition", "resourceType": "named-query", "path": query_path})["resource"]
            unchanged = apply(preview([dict(query, operation="edit")]))
            check("native named-query bundle roundtrip has no drift", unchanged["verified"] and unchanged["changeCount"] == 0)
            deletion = {"moduleId": "ignition", "resourceType": "named-query", "path": query_path, "operation": "delete"}
            check("named-query deletion verified", apply(preview([deletion]))["verified"])
        report = {"project": project, "commitChecks": commit, "passed": len(checks), "checks": checks}
        output = os.environ.get("MCP_CHANGE_REPORT")
        if output:
            with open(output, "w", encoding="utf-8") as handle:
                json.dump(report, handle, indent=2)
        print("Completed: " + str(len(checks)) + " checks passed")
    finally:
        if session:
            try:
                with urllib.request.urlopen(urllib.request.Request(base + "/mcp", method="DELETE", headers={
                    "X-Ignition-API-Token": token, "Mcp-Session-Id": session}), timeout=10):
                    pass
            except (OSError, urllib.error.HTTPError):
                pass


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        print("FAIL HTTP " + str(error.code), file=sys.stderr)
        sys.exit(1)
    except (AssertionError, ValueError, OSError) as error:
        print("FAIL " + str(error), file=sys.stderr)
        sys.exit(1)

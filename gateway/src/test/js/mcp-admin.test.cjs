const { test } = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const path = require("node:path");
const source = fs.readFileSync(
  path.join(__dirname, "../../main/resources/mounted/mcp-admin.js"),
  "utf8",
);
const clone = (value) => JSON.parse(JSON.stringify(value));
const initial = {
  enabled: true,
  mountAlias: "ignition-mcp",
  writesEnabled: true,
  defaultDryRun: true,
  authorizationMode: "profiles-only",
  allowedReadToolPatterns: ["*"],
  allowedTagReadPatterns: ["*"],
  allowedWriteToolPatterns: ["*"],
  allowedTagWritePatterns: ["[default]MCP/*"],
  authorizationProfiles: [
    {
      name: "Operators",
      tokenPatterns: ["ops-*"],
      allowedReadToolPatterns: ["ignition.tags.read"],
      allowedWriteToolPatterns: ["ignition.projects.changes.apply"],
      allowedTagReadPatterns: ["[default]MCP/*"],
      allowedTagWritePatterns: [],
      allowedProjectResourceReadPatterns: ["Test/*"],
      allowedProjectScriptWritePatterns: ["Test/library/MCP/*"],
      allowedNamedQueryWritePatterns: [],
      allowedNamedQueryExecutePatterns: [],
      allowedAlarmAckSources: [],
      customMetadata: { retained: true },
    },
  ],
};
function harness(failInitial = false) {
  const h = {
    slots: [],
    effects: [],
    pending: [],
    cursor: 0,
    rerender: true,
    posts: [],
    server: clone(initial),
    local: new Map(),
    held: [],
    hold: false,
  };
  let Component;
  const React = {
    Fragment: "fragment",
    createElement: (type, props, ...children) => ({
      type,
      props: props || {},
      children: children
        .flat(Infinity)
        .filter(
          (item) => item !== null && item !== undefined && item !== false,
        ),
    }),
    useState: (init) => {
      const i = h.cursor++;
      if (!(i in h.slots))
        h.slots[i] = typeof init === "function" ? init() : init;
      return [
        h.slots[i],
        (value) => {
          h.slots[i] = typeof value === "function" ? value(h.slots[i]) : value;
          h.rerender = true;
        },
      ];
    },
    useRef: (init) => {
      const i = h.cursor++;
      if (!(i in h.slots)) h.slots[i] = { current: init };
      return h.slots[i];
    },
    useMemo: (fn) => fn(),
    useEffect: (fn, deps) => {
      const i = h.cursor++;
      const before = h.effects[i];
      if (!before || deps.some((value, n) => value !== before.deps[n])) {
        if (before && before.cleanup) before.cleanup();
        h.effects[i] = { deps };
        h.pending.push(() => {
          h.effects[i].cleanup = fn();
        });
      }
    },
  };
  const payload = () => ({
    running: true,
    activeSessions: 2,
    queuedEvents: 0,
    config: clone(h.server),
    csrfToken: "test-csrf",
    observability: { totalToolCalls: 0, topTools: [], recentEvents: [] },
  });
  const response = (body) => ({ ok: true, json: async () => clone(body) });
  const fakeFetch = async (url, options = {}) => {
    if (options.method === "POST") {
      const body = JSON.parse(options.body);
      h.posts.push({ ...body, headers: options.headers });
      h.server = body.config;
      return response({ config: h.server });
    }
    if (failInitial) return { ok: false, status: 401 };
    const data = payload();
    if (h.hold)
      return new Promise((resolve) =>
        h.held.push(() => resolve(response(data))),
      );
    return response(data);
  };
  vm.runInNewContext(source, {
    System: {
      register: (name, imports, factory) => {
        const module = factory((key, value) => {
          if (key === "IgnitionMcpAdmin") Component = value;
        });
        module.setters[0](React);
        module.execute();
      },
    },
    document: {
      scripts: [{ src: "http://localhost/res/ignition-mcp/mcp-admin.js" }],
      getElementById: () => null,
      createElement: () => ({}),
      head: { appendChild() {} },
    },
    window: {
      location: { origin: "http://localhost:6890" },
      addEventListener() {},
      removeEventListener() {},
    },
    navigator: {
      clipboard: {
        writeText: async (text) => {
          h.clipboard = text;
        },
      },
    },
    localStorage: {
      getItem: (key) => h.local.get(key),
      setItem: (key, value) => h.local.set(key, value),
    },
    fetch: fakeFetch,
    setInterval: (callback) => {
      h.tick = callback;
      return 1;
    },
    clearInterval() {},
    console,
  });
  h.render = () => {
    h.cursor = 0;
    h.rerender = false;
    h.tree = Component();
    while (h.pending.length) h.pending.shift()();
  };
  h.flush = async () => {
    for (let i = 0; i < 8; i++) {
      if (h.rerender) h.render();
      await new Promise((resolve) => setImmediate(resolve));
    }
  };
  h.nodes = (node = h.tree) =>
    node && typeof node === "object"
      ? [node, ...node.children.flatMap((child) => h.nodes(child))]
      : [];
  h.text = (node) =>
    node && typeof node === "object"
      ? node.children.map(h.text).join("")
      : String(node ?? "");
  h.find = (predicate) => {
    const node = h.nodes().find(predicate);
    assert.ok(node, "Expected UI element");
    return node;
  };
  h.button = (label) =>
    h.find((node) => node.type === "button" && h.text(node) === label);
  h.id = (id) => h.find((node) => node.props.id === id);
  h.click = async (label) => {
    h.button(label).props.onClick();
    await h.flush();
  };
  h.change = async (id, value) => {
    h.id(id).props.onChange({ target: { value } });
    await h.flush();
  };
  h.mode = async (enabled) => {
    h.find(
      (node) =>
        node.type === "input" &&
        node.props.type === "radio" &&
        node.props.checked !== undefined &&
        h
          .nodes()
          .some(
            (parent) =>
              parent.children.includes(node) &&
              h
                .text(parent)
                .startsWith(enabled ? "Allow authorized writes" : "Read-only"),
          ),
    ).props.onChange();
    await h.flush();
  };
  h.release = async () => {
    h.hold = false;
    h.held.splice(0).forEach((resolve) => resolve());
    await h.flush();
  };
  h.render();
  return h;
}
test("visual editor preserves all existing grants and custom profile data on save", async () => {
  const h = harness();
  await h.flush();
  await h.click("Access & writes");
  await h.click("Edit");
  await h.change("profile-0-name", "Operators updated");
  await h.click("Save changes");
  assert.equal(h.posts.length, 1);
  const { config, headers } = h.posts[0];
  assert.deepEqual(config.authorizationProfiles, [
    { ...initial.authorizationProfiles[0], name: "Operators updated" },
  ]);
  assert.deepEqual(config.allowedWriteToolPatterns, ["*"]);
  assert.equal(config.authorizationMode, "profiles-only");
  assert.equal(headers["X-CSRF-Token"], "test-csrf");
  assert.equal(config.writesEnabled, true);
  assert.ok(!h.text(h.tree).includes("Unsaved changes"));
});
test("list inputs retain trailing newlines and commas while editing", async () => {
  const h = harness();
  await h.flush();
  await h.click("Access & writes");
  await h.click("Edit");
  await h.change("profile-0-tokens", "ops-one\n");
  assert.equal(h.id("profile-0-tokens").props.value, "ops-one\n");
  await h.change("profile-0-tokens", "ops-one\nops-two,");
  assert.equal(h.id("profile-0-tokens").props.value, "ops-one\nops-two,");
  await h.click("Save changes");
  assert.deepEqual(h.posts[0].config.authorizationProfiles[0].tokenPatterns, [
    "ops-one",
    "ops-two",
  ]);
});
test("a status response that arrives after an edit preserves the draft and saved status", async () => {
  const h = harness();
  await h.flush();
  await h.click("Access & writes");
  h.hold = true;
  await h.click("Refresh status");
  await h.mode(false);
  await h.release();
  assert.match(h.text(h.tree), /Saved: authorized writes/);
  assert.match(h.text(h.tree), /Unsaved changes/);
  assert.equal(h.button("Save changes").props.disabled, false);
  await h.click("Save changes");
  assert.equal(h.posts[0].config.writesEnabled, false);
});
test("external configuration changes block overwriting until the saved configuration is reloaded", async () => {
  const h = harness();
  await h.flush();
  await h.click("Access & writes");
  await h.mode(false);
  h.server.maxBatchWriteSize = 9;
  await h.click("Refresh status");
  assert.equal(h.button("Save changes").props.disabled, true);
  assert.match(h.text(h.tree), /changed while you were editing/);
  await h.click("Reload saved settings");
  assert.ok(!h.text(h.tree).includes("Unsaved changes"));
  await h.click("Limits");
  assert.equal(h.id("mcp-maxBatchWriteSize").props.value, 9);
});
test("an older status request cannot overwrite a completed save", async () => {
  const h = harness();
  await h.flush();
  await h.click("Access & writes");
  h.hold = true;
  await h.click("Refresh status");
  await h.mode(false);
  await h.click("Save changes");
  await h.release();
  assert.match(h.text(h.tree), /Saved: read-only/);
  assert.ok(!h.text(h.tree).includes("Unsaved changes"));
});
test("invalid profile JSON and pasted token secrets cannot be saved", async () => {
  const h = harness();
  await h.flush();
  await h.click("Access & writes");
  await h.click("Edit JSON");
  await h.change("mcp-profiles-json", "[broken");
  assert.equal(h.button("Save changes").props.disabled, true);
  await h.change(
    "mcp-profiles-json",
    JSON.stringify([
      {
        ...initial.authorizationProfiles[0],
        tokenPatterns: ["ops:do-not-save-secret"],
      },
    ]),
  );
  assert.match(h.text(h.tree), /without a colon or token secret/);
  assert.equal(h.button("Save changes").props.disabled, true);
  assert.equal(h.posts.length, 0);
  await h.click("Discard changes");
  assert.ok(!h.text(h.tree).includes("Unsaved changes"));
});
test("reader and writer templates append without replacing profiles or granting target wildcards", async () => {
  const h = harness();
  await h.flush();
  await h.click("Access & writes");
  await h.click("+ Add writer");
  assert.equal(h.button("Save changes").props.disabled, true);
  await h.change("profile-1-tokens", "new-writer");
  await h.click("Save changes");
  const profiles = h.posts[0].config.authorizationProfiles;
  assert.deepEqual(profiles[0], initial.authorizationProfiles[0]);
  assert.equal(profiles.length, 2);
  assert.deepEqual(profiles[1].allowedWriteToolPatterns, [
    "ignition.projects.changes.apply",
  ]);
  assert.deepEqual(profiles[1].allowedProjectScriptWritePatterns, []);
  assert.deepEqual(profiles[1].allowedTagWritePatterns, []);
});
test("appearance stays local and invalid limits cannot reach the Gateway", async () => {
  const h = harness();
  await h.flush();
  await h.change("mcp-theme", "dark");
  assert.equal(h.tree.props["data-theme"], "dark");
  assert.equal(h.local.get("ignition-mcp-theme"), "dark");
  assert.ok(!h.text(h.tree).includes("Unsaved changes"));
  assert.equal(h.posts.length, 0);
  await h.click("Limits");
  await h.change("mcp-maxBatchWriteSize", "");
  assert.equal(h.button("Save changes").props.disabled, true);
  await h.change("mcp-maxBatchWriteSize", "0");
  assert.equal(h.button("Save changes").props.disabled, true);
});
test("a failed first load cannot expose an editable fallback configuration", async () => {
  const h = harness(true);
  await h.flush();
  assert.match(h.text(h.tree), /HTTP 401/);
  assert.ok(!h.nodes().some((node) => node.props.id === "mcp-auth-mode"));
  assert.ok(
    !h
      .nodes()
      .some(
        (node) => node.type === "button" && h.text(node) === "Save changes",
      ),
  );
});

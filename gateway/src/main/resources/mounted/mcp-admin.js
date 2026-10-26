System.register("com.jg.ignition.mcp.gateway", ["react"], function (_export) {
  "use strict";
  let React;
  return {
    setters: [
      (module) => {
        React = module && module.default ? module.default : module;
      },
    ],
    execute: function () {
      const e = React.createElement;
      const { useEffect, useMemo, useRef, useState } = React;
      const STYLE_ID = "ignition-mcp-admin-style";
      const DARK = `--mcp-bg:#111922;--mcp-panel:#19232e;--mcp-soft:#202d3a;--mcp-text:#e9eff5;--mcp-muted:#a6b7c8;--mcp-border:#344454;--mcp-accent:#8ab9ff;--mcp-accent-bg:#203c5b;--mcp-green:#8dd8b3;--mcp-green-bg:#16382f;--mcp-amber:#f2c982;--mcp-amber-bg:#3d301e;--mcp-red:#ffb3b3;--mcp-red-bg:#42292d;--mcp-input:#131e29;--mcp-focus:#9bc5ff;color-scheme:dark;`;
      const STYLES = `
.mcp-admin-root{--mcp-bg:#f4f6f9;--mcp-panel:#fff;--mcp-soft:#f0f3f7;--mcp-text:#202f40;--mcp-muted:#53667b;--mcp-border:#d8e0e9;--mcp-accent:#215fa1;--mcp-accent-bg:#eaf2fd;--mcp-green:#226348;--mcp-green-bg:#e9f5ed;--mcp-amber:#795010;--mcp-amber-bg:#fff3dd;--mcp-red:#a42d37;--mcp-red-bg:#fceef0;--mcp-input:#fff;--mcp-focus:#286fc1;color-scheme:light;font-family:inherit;color:var(--mcp-text);background:var(--mcp-bg);padding:clamp(14px,2.5vw,32px);min-height:100%;line-height:1.5;isolation:isolate}
.mcp-admin-root[data-theme=dark]{${DARK}}
@media(prefers-color-scheme:dark){.mcp-admin-root[data-theme=system]{${DARK}}}
.mcp-admin-root *{box-sizing:border-box}.mcp-admin-root [hidden]{display:none!important}
.mcp-admin-root button,.mcp-admin-root input,.mcp-admin-root textarea,.mcp-admin-root select{font:inherit}.mcp-admin-root button{cursor:pointer}.mcp-admin-root button:disabled{cursor:not-allowed;opacity:.5}.mcp-admin-root :focus-visible{outline:3px solid var(--mcp-focus);outline-offset:3px}
.mcp-admin-shell{max-width:1160px;margin:auto;min-width:0;padding-bottom:60px}.mcp-head{display:flex;justify-content:space-between;align-items:flex-start;gap:20px;margin-bottom:24px}.mcp-brand{display:flex;gap:14px;align-items:center}.mcp-mark{display:grid;place-items:center;width:46px;height:46px;flex:none;background:var(--mcp-accent-bg);color:var(--mcp-accent);border:1px solid var(--mcp-border);border-radius:12px}.mcp-admin-root h1{font-size:26px;line-height:1.2;letter-spacing:-.6px;margin:0;font-weight:650}.mcp-admin-root h2{font-size:19px;line-height:1.35;margin:0;font-weight:650}.mcp-admin-root h3{font-size:15px;line-height:1.4;margin:0;font-weight:650}.mcp-admin-root p{margin:6px 0 0}.mcp-sub,.mcp-hint{color:var(--mcp-muted);font-size:13px}.mcp-eyebrow{font-size:11px;font-weight:700;letter-spacing:1.1px;text-transform:uppercase;color:var(--mcp-muted);margin-bottom:5px}.mcp-head-actions,.mcp-inline,.mcp-buttons{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.mcp-head-actions{justify-content:flex-end}.mcp-head-actions select{max-width:160px;min-width:148px}
.mcp-admin-root .mcp-btn{display:inline-flex;justify-content:center;align-items:center;gap:7px;min-height:36px;padding:7px 13px;border:1px solid var(--mcp-border);border-radius:7px;background:var(--mcp-panel);color:var(--mcp-text);font-size:13px;font-weight:600;text-decoration:none}.mcp-admin-root .mcp-btn:hover:not(:disabled){background:var(--mcp-soft);border-color:var(--mcp-muted)}.mcp-admin-root .mcp-btn.primary{background:var(--mcp-accent);border-color:var(--mcp-accent);color:var(--mcp-panel)}.mcp-admin-root[data-theme=dark] .mcp-btn.primary{color:#112339}@media(prefers-color-scheme:dark){.mcp-admin-root[data-theme=system] .mcp-btn.primary{color:#112339}}.mcp-admin-root .mcp-btn.danger{color:var(--mcp-red)}
.mcp-status-line{display:flex;align-items:center;flex-wrap:wrap;gap:10px 20px;padding:14px 18px;border:1px solid var(--mcp-border);border-radius:10px;background:var(--mcp-panel);font-size:13px;margin-bottom:22px}.mcp-status-line .mcp-hint{margin-left:auto}.mcp-dot{display:inline-block;width:7px;height:7px;border-radius:50%;background:currentColor;margin-right:7px}.mcp-good{color:var(--mcp-green)}.mcp-warn{color:var(--mcp-amber)}.mcp-pill{display:inline-flex;align-items:center;gap:5px;max-width:100%;border-radius:5px;padding:3px 8px;background:var(--mcp-soft);color:var(--mcp-muted);font-size:11px;font-weight:650;overflow-wrap:anywhere}.mcp-pill.good{background:var(--mcp-green-bg);color:var(--mcp-green)}.mcp-pill.warn{background:var(--mcp-amber-bg);color:var(--mcp-amber)}
.mcp-nav{display:flex;flex-wrap:wrap;gap:4px;padding:4px;border:1px solid var(--mcp-border);border-radius:9px;background:var(--mcp-soft);margin-bottom:24px;width:fit-content;max-width:100%}.mcp-admin-root .mcp-nav button{border:0;border-radius:6px;background:transparent;color:var(--mcp-muted);padding:9px 15px;font-size:13px;font-weight:600;min-height:38px}.mcp-admin-root .mcp-nav button[aria-current=page]{background:var(--mcp-panel);color:var(--mcp-accent);box-shadow:0 1px 4px #00000012}.mcp-page-heading{margin-bottom:20px}.mcp-stack{display:grid;gap:18px}.mcp-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.mcp-card{background:var(--mcp-panel);border:1px solid var(--mcp-border);border-radius:10px;padding:22px;min-width:0}.mcp-card-header{display:flex;align-items:flex-start;justify-content:space-between;gap:14px;margin-bottom:18px}.mcp-card-header p{max-width:680px}.mcp-step{display:flex;gap:14px;align-items:flex-start}.mcp-step-number{width:28px;height:28px;display:grid;place-items:center;background:var(--mcp-accent-bg);color:var(--mcp-accent);border-radius:50%;flex:none;font-size:12px;font-weight:700}.mcp-step+.mcp-step{border-top:1px solid var(--mcp-border);padding-top:20px;margin-top:20px}.mcp-step .mcp-btn{margin-top:10px}.mcp-endpoint{display:block;padding:12px 14px;border:1px solid var(--mcp-border);border-radius:7px;background:var(--mcp-soft);font:12px/1.7 ui-monospace,SFMono-Regular,Menlo,monospace;overflow-wrap:anywhere;margin:14px 0}.mcp-admin-root code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.9em;overflow-wrap:anywhere}.mcp-stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-top:18px}.mcp-stat strong{display:block;font-size:25px;letter-spacing:-.7px}.mcp-stat span{font-size:12px;color:var(--mcp-muted)}
.mcp-form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.mcp-field{display:flex;flex-direction:column;gap:6px;min-width:0}.mcp-field>label,.mcp-label{font-size:13px;font-weight:600}.mcp-admin-root input:not([type=checkbox]):not([type=radio]),.mcp-admin-root textarea,.mcp-admin-root select{width:100%;min-width:0;min-height:38px;border:1px solid var(--mcp-border);border-radius:6px;background:var(--mcp-input);color:var(--mcp-text);padding:8px 10px}.mcp-admin-root textarea{resize:vertical;min-height:84px;font:12px/1.65 ui-monospace,SFMono-Regular,Menlo,monospace}.mcp-admin-root input[type=checkbox],.mcp-admin-root input[type=radio]{accent-color:var(--mcp-accent);width:17px;height:17px;flex:none;margin:0}.mcp-admin-root fieldset{margin:0;padding:0;border:0;min-width:0}.mcp-admin-root fieldset:disabled{opacity:.65}.mcp-check{display:flex;gap:11px;align-items:flex-start;padding:12px 0;font-size:13px}.mcp-check input{margin-top:3px!important}.mcp-check strong{display:block;font-weight:600}.mcp-check .mcp-hint{display:block;margin-top:3px}
.mcp-mode-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.mcp-mode{display:flex;align-items:flex-start;gap:12px;padding:17px;border:1px solid var(--mcp-border);border-radius:8px;cursor:pointer}.mcp-mode.selected{border-color:var(--mcp-accent);background:var(--mcp-accent-bg);box-shadow:inset 0 0 0 1px var(--mcp-accent)}.mcp-mode input{margin-top:3px!important}.mcp-mode strong{display:block;font-size:14px;margin-bottom:4px}.mcp-flow{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin-top:16px;font-size:12px;color:var(--mcp-muted)}.mcp-flow span{background:var(--mcp-soft);border-radius:5px;padding:6px 9px}.mcp-callout{border-left:3px solid var(--mcp-accent);padding:12px 15px;background:var(--mcp-accent-bg);border-radius:0 7px 7px 0;font-size:13px;margin:16px 0 0}.mcp-callout.warn{border-color:var(--mcp-amber);background:var(--mcp-amber-bg);color:var(--mcp-amber)}
.mcp-profile{border:1px solid var(--mcp-border);border-radius:8px;overflow:hidden}.mcp-profile+.mcp-profile{margin-top:12px}.mcp-profile-head{display:flex;align-items:center;gap:10px;padding:15px 17px}.mcp-profile-name{min-width:0;flex:1;overflow-wrap:anywhere}.mcp-profile-name .mcp-hint{margin-top:3px}.mcp-profile-body{border-top:1px solid var(--mcp-border);padding:20px}.mcp-profile-section{margin-top:23px;border-top:1px solid var(--mcp-border);padding-top:19px}.mcp-profile-section h3{margin-bottom:14px}.mcp-admin-root details>summary{cursor:pointer;font-size:13px;font-weight:600;padding:12px 0;color:var(--mcp-accent)}.mcp-admin-root details[open]>summary{margin-bottom:8px}.mcp-admin-root summary:focus-visible{border-radius:4px}.mcp-empty{border:1px dashed var(--mcp-border);border-radius:8px;text-align:center;padding:28px;color:var(--mcp-muted)}.mcp-mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}.mcp-table-wrap{overflow-x:auto}.mcp-table{width:100%;border-collapse:collapse;font-size:12px}.mcp-table th,.mcp-table td{text-align:left;padding:11px 9px;border-bottom:1px solid var(--mcp-border)}.mcp-table th{font-weight:600;color:var(--mcp-muted);white-space:nowrap}.mcp-table td:first-child{overflow-wrap:anywhere}.mcp-events{list-style:none;padding:0;margin:0;max-height:520px;overflow:auto}.mcp-events li{padding:14px 0;border-bottom:1px solid var(--mcp-border);font-size:12px;overflow-wrap:anywhere}.mcp-events li:last-child{border-bottom:0}.mcp-event-head{display:flex;justify-content:space-between;gap:12px;align-items:center;margin-bottom:6px}.mcp-alert{padding:12px 16px;border-radius:8px;border:1px solid var(--mcp-border);margin-bottom:16px;font-size:13px;overflow-wrap:anywhere}.mcp-alert.error{background:var(--mcp-red-bg);color:var(--mcp-red)}.mcp-alert.success{background:var(--mcp-green-bg);color:var(--mcp-green)}
.mcp-savebar{position:sticky;bottom:48px;z-index:5;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:14px;margin-top:22px;padding:14px 18px;border:1px solid var(--mcp-border);border-radius:10px;background:var(--mcp-panel);box-shadow:0 4px 18px #00000013}.mcp-savebar strong{font-size:13px}.mcp-change-list{font-size:12px;margin:0;padding-left:18px}.mcp-footer{font-size:11px;color:var(--mcp-muted);margin-top:22px;display:flex;justify-content:space-between;gap:10px;flex-wrap:wrap}.mcp-sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
@media(max-width:760px){.mcp-grid,.mcp-form-grid{grid-template-columns:minmax(0,1fr)}.mcp-head{flex-direction:column;gap:14px}.mcp-head-actions{justify-content:flex-start}.mcp-card{padding:18px}.mcp-status-line .mcp-hint{margin-left:0;width:100%}.mcp-nav{width:100%;gap:3px}.mcp-admin-root .mcp-nav button{flex:1 1 auto;padding:8px 10px;font-size:12px}.mcp-profile-head{flex-wrap:wrap}.mcp-profile-name{flex-basis:65%}.mcp-savebar{padding:13px;bottom:48px}.mcp-savebar .mcp-buttons{width:100%}.mcp-savebar .mcp-buttons .primary{flex:1}}
@media(max-width:440px){.mcp-mode-grid{grid-template-columns:1fr}.mcp-admin-root{padding:14px 12px}.mcp-admin-root h1{font-size:23px}.mcp-sub{font-size:12px}.mcp-head-actions{width:100%}.mcp-stats{gap:8px}.mcp-stat strong{font-size:22px}.mcp-card-header{flex-wrap:wrap}.mcp-profile-body{padding:16px}}
`;
      const GRANTS = [
        [
          "allowedReadToolPatterns",
          "Read tools",
          "Tool names this token may call for inspection.",
          "ignition.tags.read",
        ],
        [
          "allowedTagReadPatterns",
          "Readable tags",
          "Full tag paths, including the provider.",
          "[default]MCP/*",
        ],
        [
          "allowedProjectResourceReadPatterns",
          "Readable project resources",
          "Format: project/module/type/path. A project prefix can include all of its resources.",
          "MyProject/*",
        ],
        [
          "allowedWriteToolPatterns",
          "Write tools",
          "Allow the actions this token needs. Planned project changes use ignition.projects.changes.apply.",
          "ignition.projects.changes.apply",
        ],
        [
          "allowedTagWritePatterns",
          "Tag write targets",
          "Include the folder itself when creating or editing a folder definition.",
          "[default]MCP\n[default]MCP/*",
        ],
        [
          "allowedProjectScriptWritePatterns",
          "Project script write targets",
          "Format: project/script-type/path. Native Python libraries use library.",
          "MyProject/library/MCP/*",
        ],
        [
          "allowedNamedQueryWritePatterns",
          "Named query write targets",
          "Format: project/query-path.",
          "MyProject/MCP/*",
        ],
        [
          "allowedNamedQueryExecutePatterns",
          "Named query execution targets",
          "Separate permission to execute queries. This does not configure a database.",
          "MyProject/Reports/*",
        ],
        [
          "allowedAlarmAckSources",
          "Alarm acknowledgment sources",
          "Separate permission to acknowledge alarms.",
          "prov:default:/tag:MCP/*",
        ],
      ];
      const READ_TOOLS = [
        "ignition.tags.browse",
        "ignition.tags.read",
        "ignition.tags.definition.read",
        "ignition.tags.snapshot",
        "ignition.udts.*",
        "ignition.perspective.views.*",
        "ignition.projects.list",
        "ignition.projects.resource.*",
        "ignition.projects.search",
        "ignition.projects.references",
        "ignition.projects.bundle.inspect",
        "ignition.scripts.project.list",
        "ignition.scripts.project.read",
        "ignition.namedqueries.list",
        "ignition.namedqueries.read",
      ];
      const LIMITS = [
        ["maxConcurrentSessions", "Concurrent client sessions", 200],
        ["maxRequestsPerMinutePerToken", "Requests per minute, per token", 300],
        [
          "maxWriteOpsPerMinutePerToken",
          "Write requests per minute, per token",
          60,
        ],
        ["maxBatchWriteSize", "Resources per write batch", 50],
        ["historianMaxRows", "Historian result rows", 5000],
        ["namedQueryMaxRows", "Named query result rows", 1000],
      ];
      const NAMES = {
        enabled: "MCP service",
        writesEnabled: "Gateway write mode",
        defaultDryRun: "Explicit commit for legacy writes",
        authorizationMode: "Authorization mode",
        authorizationProfiles: "Token profiles",
        mountAlias: "Endpoint alias",
        streamableEnabled: "HTTP transport",
        sseFallbackEnabled: "SSE fallback",
        allowedHosts: "Allowed hosts",
        allowedOrigins: "Allowed browser origins",
        historianDefaultProvider: "Default historian provider",
      };
      GRANTS.forEach((row) => {
        NAMES[row[0]] = "Global: " + row[1].toLowerCase();
      });
      LIMITS.forEach((row) => {
        NAMES[row[0]] = row[1];
      });
      const listText = (value) =>
        Array.isArray(value) ? value.join("\n") : "";
      const parseList = (text) =>
        String(text || "")
          .split(/[\n,]/)
          .map((item) => item.trim())
          .filter(Boolean);
      const canonical = (value) =>
        JSON.stringify(value, function (key, item) {
          return item && typeof item === "object" && !Array.isArray(item)
            ? Object.keys(item)
                .sort()
                .reduce((out, k) => {
                  out[k] = item[k];
                  return out;
                }, {})
            : item;
        });
      const number = (value, fallback) =>
        Number.isFinite(Number(value)) && value !== ""
          ? Number(value)
          : fallback;
      const count = (value) => Number(value || 0).toLocaleString();
      const when = (value) => (value ? new Date(value).toLocaleString() : "—");
      function normalize(raw) {
        const cfg = raw || {};
        const out = {
          ...cfg,
          enabled: cfg.enabled !== false,
          mountAlias: cfg.mountAlias || "ignition-mcp",
          allowedOrigins: cfg.allowedOrigins || [],
          allowedHosts: cfg.allowedHosts || [],
          streamableEnabled: cfg.streamableEnabled !== false,
          sseFallbackEnabled: cfg.sseFallbackEnabled !== false,
          writesEnabled: cfg.writesEnabled === true,
          defaultDryRun: cfg.defaultDryRun !== false,
          authorizationMode:
            cfg.authorizationMode === "profiles-only"
              ? "profiles-only"
              : "additive",
          authorizationProfiles: cfg.authorizationProfiles || [],
          historianDefaultProvider: cfg.historianDefaultProvider || "",
        };
        LIMITS.forEach(([key, label, fallback]) => {
          out[key] = number(cfg[key], fallback);
        });
        GRANTS.forEach(([key]) => {
          out[key] = Array.isArray(cfg[key])
            ? cfg[key]
            : ["allowedTagWritePatterns"].includes(key)
              ? []
              : ["*"];
        });
        return out;
      }
      function parseProfiles(text) {
        const rows = JSON.parse(text || "[]");
        if (!Array.isArray(rows))
          throw new Error("Profiles must be a JSON array.");
        rows.forEach((row, index) => {
          if (!row || typeof row !== "object" || Array.isArray(row))
            throw new Error(`Profile ${index + 1} must be an object.`);
          for (const key of [
            "tokenPatterns",
            ...GRANTS.map((item) => item[0]),
          ]) {
            if (
              row[key] !== undefined &&
              (!Array.isArray(row[key]) ||
                row[key].some((item) => typeof item !== "string"))
            )
              throw new Error(
                `Profile ${index + 1}: ${key} must be a list of text values.`,
              );
          }
          if (row.name !== undefined && typeof row.name !== "string")
            throw new Error(`Profile ${index + 1}: name must be text.`);
        });
        return rows;
      }
      function detectAlias() {
        for (const script of Array.from(document.scripts || [])) {
          const match = (script.src || "").match(
            /\/res\/([^/]+)\/mcp-admin\.js/,
          );
          if (match) return decodeURIComponent(match[1]);
        }
        return "ignition-mcp";
      }
      function icon(name) {
        const paths = {
          plug: "M8 3v5m8-5v5M6 8h12v4a6 6 0 0 1-6 6v4m-6-14v4a6 6 0 0 0 6 6",
          refresh:
            "M20 7v5h-5M4 17v-5h5M5.1 7a8 8 0 0 1 13.2-2L20 7M4 17l1.7 2A8 8 0 0 0 18.9 17",
          copy: "M8 8h12v13H8zM16 8V3H3v13h5",
        };
        return e(
          "svg",
          {
            width: name === "plug" ? 26 : 15,
            height: name === "plug" ? 26 : 15,
            viewBox: "0 0 24 24",
            fill: "none",
            stroke: "currentColor",
            strokeWidth: 1.7,
            strokeLinecap: "round",
            strokeLinejoin: "round",
            "aria-hidden": true,
          },
          e("path", { d: paths[name] || paths.plug }),
        );
      }
      const pill = (text, tone) =>
        e("span", { className: `mcp-pill ${tone || ""}` }, text);
      const sectionHeading = (title, hint) =>
        e(
          "div",
          { className: "mcp-page-heading" },
          e("h2", null, title),
          hint && e("p", { className: "mcp-sub" }, hint),
        );
      const card = (title, hint, body, extra) =>
        e(
          "section",
          { className: "mcp-card" },
          e(
            "div",
            { className: "mcp-card-header" },
            e(
              "div",
              null,
              e("h3", null, title),
              hint && e("p", { className: "mcp-sub" }, hint),
            ),
            extra,
          ),
          body,
        );
      const stat = (value, label) =>
        e(
          "div",
          { className: "mcp-stat" },
          e("strong", null, value),
          e("span", null, label),
        );

      function IgnitionMcpAdmin() {
        const alias = useMemo(detectAlias, []);
        const endpointBase = `/data/${alias}/admin`;
        const [config, setConfig] = useState(normalize(null));
        const [saved, setSaved] = useState(null);
        const [live, setLive] = useState(null);
        const [profileText, setProfileText] = useState("[]");
        const [loading, setLoading] = useState(true);
        const [saving, setSaving] = useState(false);
        const [status, setStatus] = useState({});
        const [tab, setTab] = useState("overview");
        const [loadError, setLoadError] = useState("");
        const [saveError, setSaveError] = useState("");
        const [notice, setNotice] = useState("");
        const [csrf, setCsrf] = useState("");
        const [remoteChanged, setRemoteChanged] = useState(false);
        const [autoRefresh, setAutoRefresh] = useState(true);
        const [loadedAt, setLoadedAt] = useState(0);
        const [rawProfiles, setRawProfiles] = useState(false);
        const [openProfile, setOpenProfile] = useState(-1);
        const [inputs, setInputs] = useState({});
        const [theme, setTheme] = useState(() => {
          try {
            const value = localStorage.getItem("ignition-mcp-theme");
            return ["light", "dark"].includes(value) ? value : "system";
          } catch (error) {
            return "system";
          }
        });
        const dirtyRef = useRef(false);
        const savingRef = useRef(false);
        const baseline = useRef(null);
        const revision = useRef(0);
        const requestId = useRef(0);
        let profiles = [],
          profileError = "";
        try {
          profiles = parseProfiles(profileText);
        } catch (error) {
          profileError = error.message;
        }
        const draft = { ...config, authorizationProfiles: profiles };
        const changed = saved
          ? Object.keys(draft).filter(
              (key) => canonical(draft[key]) !== canonical(saved[key]),
            )
          : [];
        const dirty = !!saved && (!!profileError || changed.length > 0);
        dirtyRef.current = dirty;
        const invalidProfile = profiles.findIndex(
          (item) =>
            !String(item.name || "").trim() ||
            !(item.tokenPatterns || []).length,
        );
        const invalidToken = profiles.some((item) =>
          (item.tokenPatterns || []).some(
            (name) => !name.trim() || name.includes(":"),
          ),
        );
        const validationError =
          profileError ||
          (invalidProfile >= 0
            ? `Profile ${invalidProfile + 1} needs a name and at least one API token name.`
            : invalidToken
              ? "Enter API token names only, without a colon or token secret."
              : LIMITS.some(
                    ([key]) => !Number.isInteger(draft[key]) || draft[key] <= 0,
                  )
                ? "Limits must be whole numbers greater than zero."
                : !/^[A-Za-z0-9_-]+$/.test(draft.mountAlias)
                  ? "The endpoint alias can contain letters, numbers, dashes and underscores."
                  : "");
        const accept = (next) => {
          baseline.current = next;
          dirtyRef.current = false;
          setConfig(next);
          setSaved(next);
          setProfileText(JSON.stringify(next.authorizationProfiles, null, 2));
          setInputs({});
          setRemoteChanged(false);
        };
        const load = async () => {
          const id = ++requestId.current,
            startedRevision = revision.current;
          try {
            const response = await fetch(`${endpointBase}/status`, {
              credentials: "same-origin",
            });
            if (!response.ok)
              throw new Error(
                `Could not load Gateway settings (HTTP ${response.status}). Sign in to the Gateway and try again.`,
              );
            const payload = await response.json();
            if (id !== requestId.current) return;
            if (!payload.config)
              throw new Error("The Gateway did not return its configuration.");
            const next = normalize(payload.config);
            setStatus(payload);
            setLive(next);
            setCsrf(payload.csrfToken || "");
            setLoadedAt(Date.now());
            setLoadError("");
            if (
              !dirtyRef.current &&
              startedRevision === revision.current &&
              !savingRef.current
            )
              accept(next);
            else if (baseline.current)
              setRemoteChanged(canonical(next) !== canonical(baseline.current));
          } catch (error) {
            if (id === requestId.current)
              setLoadError(error.message || String(error));
          } finally {
            if (id === requestId.current) setLoading(false);
          }
        };
        useEffect(() => {
          let style = document.getElementById(STYLE_ID);
          if (!style) {
            style = document.createElement("style");
            style.id = STYLE_ID;
            document.head.appendChild(style);
          }
          style.textContent = STYLES;
          load();
          return () => {
            requestId.current++;
          };
        }, [endpointBase]);
        useEffect(() => {
          if (!autoRefresh) return undefined;
          const timer = setInterval(() => {
            if (!savingRef.current) load();
          }, 10000);
          return () => clearInterval(timer);
        }, [autoRefresh, endpointBase]);
        useEffect(() => {
          const beforeUnload = (event) => {
            if (dirtyRef.current) {
              event.preventDefault();
              event.returnValue = "";
            }
          };
          window.addEventListener("beforeunload", beforeUnload);
          return () => window.removeEventListener("beforeunload", beforeUnload);
        }, []);
        const edit = () => {
          revision.current++;
          dirtyRef.current = true;
          setNotice("");
          setSaveError("");
        };
        const update = (key, value) => {
          edit();
          setConfig((previous) => ({ ...previous, [key]: value }));
        };
        const updateProfiles = (next) => {
          edit();
          setProfileText(JSON.stringify(next, null, 2));
        };
        const updateProfile = (index, key, value) =>
          updateProfiles(
            profiles.map((item, position) =>
              position === index ? { ...item, [key]: value } : item,
            ),
          );
        const rawInput = (id, value) => {
          setInputs((previous) => ({ ...previous, [id]: value }));
        };
        const field = (id, label, value, onChange, hint, options = {}) =>
          e(
            "div",
            { className: "mcp-field", key: id },
            e("label", { htmlFor: id }, label),
            e(options.list || options.multiline ? "textarea" : "input", {
              id,
              type: options.number ? "number" : "text",
              min: options.number ? 1 : undefined,
              step: options.number ? 1 : undefined,
              value: options.list
                ? Object.prototype.hasOwnProperty.call(inputs, id)
                  ? inputs[id]
                  : listText(value)
                : value,
              onChange: (event) => {
                const text = event.target.value;
                if (options.list) rawInput(id, text);
                onChange(
                  options.list
                    ? parseList(text)
                    : options.number
                      ? text === ""
                        ? ""
                        : Number(text)
                      : text,
                );
              },
              "aria-describedby": hint ? `${id}-hint` : undefined,
              placeholder: options.placeholder,
              rows: options.rows || 3,
              spellCheck: options.list || options.multiline ? false : undefined,
            }),
            hint && e("div", { id: `${id}-hint`, className: "mcp-hint" }, hint),
          );
        const grant = (row, value, setter, prefix) =>
          field(
            `${prefix}-${row[0]}`,
            row[1],
            value[row[0]] || [],
            (values) => setter(row[0], values),
            row[2] +
              " One pattern per line; * and ? are supported. Empty means no grant.",
            { list: true, placeholder: row[3] },
          );
        const check = (key, label, hint) =>
          e(
            "label",
            { className: "mcp-check" },
            e("input", {
              type: "checkbox",
              checked: !!config[key],
              onChange: (event) => update(key, event.target.checked),
            }),
            e(
              "span",
              null,
              e("strong", null, label),
              e("span", { className: "mcp-hint" }, hint),
            ),
          );
        const btn = (text, onClick, primary, extra) =>
          e(
            "button",
            {
              type: "button",
              className: `mcp-btn${primary ? " primary" : ""}`,
              onClick,
              ...extra,
            },
            text,
          );
        const copy = async () => {
          try {
            if (!navigator.clipboard)
              throw new Error(
                "Clipboard is unavailable. Select and copy the endpoint below.",
              );
            await navigator.clipboard.writeText(
              `${window.location.origin}/data/${(live || config).mountAlias}/mcp`,
            );
            setNotice("Saved MCP endpoint copied.");
          } catch (error) {
            setSaveError(error.message || String(error));
          }
        };
        const addProfile = (writer) => {
          const next = {
            name: `new-${writer ? "writer" : "reader"}-${profiles.length + 1}`,
            tokenPatterns: [],
          };
          GRANTS.forEach(([key]) => {
            next[key] = [];
          });
          next.allowedReadToolPatterns = writer
            ? [
                ...READ_TOOLS,
                "ignition.projects.changes.preview",
                "ignition.projects.changes.status",
              ]
            : [...READ_TOOLS];
          if (writer)
            next.allowedWriteToolPatterns = ["ignition.projects.changes.apply"];
          updateProfiles([...profiles, next]);
          setOpenProfile(profiles.length);
          setRawProfiles(false);
        };
        const discard = () => {
          revision.current++;
          accept(live || saved);
          setSaveError("");
          setNotice(
            "Draft discarded. Showing the latest saved Gateway settings.",
          );
        };
        const save = async () => {
          if (!dirty || saving || validationError || remoteChanged) return;
          savingRef.current = true;
          requestId.current++;
          setSaving(true);
          setSaveError("");
          setNotice("");
          try {
            const response = await fetch(`${endpointBase}/config`, {
              method: "POST",
              credentials: "same-origin",
              headers: {
                "Content-Type": "application/json",
                ...(csrf ? { "X-CSRF-Token": csrf } : {}),
              },
              body: JSON.stringify({ config: draft }),
            });
            if (!response.ok)
              throw new Error(
                (await response.text()) ||
                  `Could not save settings (HTTP ${response.status}).`,
              );
            const payload = await response.json();
            if (!payload.config)
              throw new Error(
                "Save response did not include settings. Refresh status to check the Gateway before trying again.",
              );
            const next = normalize(payload.config);
            accept(next);
            setLive(next);
            setNotice("Settings saved to the Gateway.");
          } catch (error) {
            setSaveError(error.message || String(error));
          } finally {
            savingRef.current = false;
            setSaving(false);
          }
        };
        const current = live || config;
        const endpoint = `${window.location.origin}/data/${current.mountAlias}/mcp`;
        const obs = status.observability || {};
        const total = Number(obs.totalToolCalls || 0);
        const header = e(
          "header",
          { className: "mcp-head" },
          e(
            "div",
            { className: "mcp-brand" },
            e("div", { className: "mcp-mark" }, icon("plug")),
            e(
              "div",
              null,
              e("div", { className: "mcp-eyebrow" }, "Gateway integration"),
              e("h1", null, "Ignition MCP"),
              e(
                "p",
                { className: "mcp-sub" },
                "Connect AI clients. Control what they can read and change.",
              ),
            ),
          ),
          e(
            "div",
            { className: "mcp-head-actions" },
            e(
              "label",
              { className: "mcp-sr-only", htmlFor: "mcp-theme" },
              "Appearance",
            ),
            e(
              "select",
              {
                id: "mcp-theme",
                value: theme,
                onChange: (event) => {
                  const value = event.target.value;
                  setTheme(value);
                  try {
                    localStorage.setItem("ignition-mcp-theme", value);
                  } catch (error) {
                    /* Appearance remains local to this page. */
                  }
                },
              },
              e("option", { value: "system" }, "System theme"),
              e("option", { value: "light" }, "Light theme"),
              e("option", { value: "dark" }, "Dark theme"),
            ),
            btn(
              e(React.Fragment, null, icon("refresh"), "Refresh status"),
              load,
              false,
              { disabled: saving || loading },
            ),
          ),
        );
        let body;
        if (tab === "overview")
          body = e(
            "div",
            { className: "mcp-stack" },
            sectionHeading(
              "Your MCP workspace",
              "Start with the connection, then choose who has access. You can enable changes when you are ready.",
            ),
            e(
              "div",
              { className: "mcp-grid" },
              card(
                "Connect a client",
                "Use this saved endpoint in Codex or another MCP client.",
                e(
                  React.Fragment,
                  null,
                  e("code", { className: "mcp-endpoint" }, endpoint),
                  btn(
                    e(React.Fragment, null, icon("copy"), "Copy endpoint"),
                    copy,
                  ),
                  e(
                    "p",
                    { className: "mcp-hint" },
                    "Authenticate with an Ignition API token using X-Ignition-API-Token or Authorization: Bearer.",
                  ),
                  btn("Connection settings →", () => setTab("connection")),
                ),
              ),
              card(
                "Saved access policy",
                "These values reflect what the Gateway is enforcing now.",
                e(
                  React.Fragment,
                  null,
                  pill(
                    current.writesEnabled
                      ? "Authorized writes enabled"
                      : "Read-only",
                    current.writesEnabled ? "warn" : "good",
                  ),
                  e(
                    "p",
                    { className: "mcp-sub" },
                    current.authorizationMode === "profiles-only"
                      ? "Only matching token profiles grant access. Global grants are inactive."
                      : "Global grants and all matching token profiles combine their permissions.",
                  ),
                  e(
                    "div",
                    { className: "mcp-stats" },
                    stat(
                      count(current.authorizationProfiles.length),
                      "Token profiles",
                    ),
                    stat(count(status.activeSessions), "Active sessions"),
                    stat(count(total), "Tool calls"),
                  ),
                  e(
                    "div",
                    { className: "mcp-buttons", style: { marginTop: 18 } },
                    btn("Manage access & writes →", () => setTab("access")),
                  ),
                ),
              ),
            ),
            card(
              "Set up access in three steps",
              "Every change to permissions stays in your draft until you save.",
              e(
                "div",
                null,
                ...[
                  [
                    "Connect",
                    "Create a key under Platform → Security → API Keys and give its token value to your MCP client. Keep the secret in the client, not in a permission profile.",
                    "connection",
                  ],
                  [
                    "Scope access",
                    "Add a profile matching the API token’s name. Choose the tools and tag/project paths it can access.",
                    "access",
                  ],
                  [
                    "Allow changes",
                    "Enable authorized writes, grant the write tool and its targets, then have the client explicitly commit the change.",
                    "access",
                  ],
                ].map(([title, text, target], index) =>
                  e(
                    "div",
                    { className: "mcp-step", key: title },
                    e("span", { className: "mcp-step-number" }, index + 1),
                    e(
                      "div",
                      null,
                      e("h3", null, title),
                      e("p", { className: "mcp-sub" }, text),
                      btn(
                        index === 0
                          ? "Set up a client"
                          : index === 1
                            ? "Edit token profiles"
                            : "Review write controls",
                        () => setTab(target),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          );
        if (tab === "access")
          body = e(
            "div",
            { className: "mcp-stack" },
            sectionHeading(
              "Access & writes",
              "Decide whether changes are allowed, then scope each client’s permissions.",
            ),
            card(
              "1. Choose the Gateway write mode",
              "Reads and permitted change previews remain available in either mode. Save to apply your choice.",
              e(
                React.Fragment,
                null,
                e(
                  "fieldset",
                  {
                    className: "mcp-mode-grid",
                    "aria-label": "Gateway write mode",
                  },
                  ...[
                    [
                      false,
                      "Read-only",
                      "Block all MCP commits while keeping permitted inspection and previews available.",
                    ],
                    [
                      true,
                      "Allow authorized writes",
                      "Permit commits only for tokens with the required write-tool and target grants.",
                    ],
                  ].map(([value, title, hint]) =>
                    e(
                      "label",
                      {
                        key: title,
                        className: `mcp-mode ${config.writesEnabled === value ? "selected" : ""}`,
                      },
                      e("input", {
                        type: "radio",
                        name: "mcp-write-mode",
                        checked: config.writesEnabled === value,
                        onChange: () => update("writesEnabled", value),
                      }),
                      e(
                        "span",
                        null,
                        e("strong", null, title),
                        e("span", { className: "mcp-hint" }, hint),
                      ),
                    ),
                  ),
                ),
                e(
                  "div",
                  { className: "mcp-flow" },
                  e("span", null, "Gateway allows writes"),
                  " + ",
                  e("span", null, "Token has tool + target grants"),
                  " + ",
                  e(
                    "span",
                    null,
                    config.defaultDryRun
                      ? "Client sends commit: true"
                      : "Client commits · planned apply needs commit: true",
                  ),
                ),
                check(
                  "defaultDryRun",
                  "Require explicit commit for legacy write tools",
                  "Keep enabled to make legacy mutations dry-run by default. Planned project changes always require commit: true.",
                ),
              ),
            ),
            card(
              "2. Decide where permissions come from",
              "Profiles match API token names. Multiple matching profiles combine their grants.",
              e(
                React.Fragment,
                null,
                e(
                  "div",
                  { className: "mcp-field" },
                  e(
                    "label",
                    { htmlFor: "mcp-auth-mode" },
                    "Authorization mode",
                  ),
                  e(
                    "select",
                    {
                      id: "mcp-auth-mode",
                      value: config.authorizationMode,
                      onChange: (event) =>
                        update("authorizationMode", event.target.value),
                    },
                    e(
                      "option",
                      { value: "profiles-only" },
                      "Profiles only — scoped access",
                    ),
                    e(
                      "option",
                      { value: "additive" },
                      "Global + profile grants — additive access",
                    ),
                  ),
                ),
                e(
                  "div",
                  {
                    className: `mcp-callout${config.authorizationMode === "additive" ? " warn" : ""}`,
                  },
                  config.authorizationMode === "profiles-only"
                    ? "Global grants are ignored. A token without a matching profile has no tool-call grants."
                    : "A global * grants access even when a matching profile is narrower. Profiles add permissions; they do not restrict global grants.",
                ),
              ),
            ),
            card(
              "3. Configure token profiles",
              "Use the name before the colon in an API token. Never enter the token secret here.",
              e(
                React.Fragment,
                null,
                e(
                  "div",
                  { className: "mcp-buttons", style: { marginBottom: 16 } },
                  btn("+ Add reader", () => addProfile(false), false, {
                    disabled: !!profileError,
                  }),
                  btn("+ Add writer", () => addProfile(true), false, {
                    disabled: !!profileError,
                  }),
                  btn(rawProfiles ? "Use visual editor" : "Edit JSON", () =>
                    setRawProfiles(!rawProfiles),
                  ),
                ),
                rawProfiles
                  ? field(
                      "mcp-profiles-json",
                      "Authorization profiles JSON",
                      profileText,
                      (text) => {
                        edit();
                        setProfileText(text);
                        setInputs({});
                      },
                      "Advanced editing preserves custom profile fields. Invalid JSON cannot be saved.",
                      { multiline: true, rows: 15 },
                    )
                  : profileError
                    ? e(
                        "div",
                        { className: "mcp-alert error", role: "alert" },
                        profileError,
                        " Open Edit JSON to fix it.",
                      )
                    : profiles.length === 0
                      ? e(
                          "div",
                          { className: "mcp-empty" },
                          "No token profiles yet. Add a reader or writer to get started.",
                        )
                      : profiles.map((profile, index) =>
                          e(
                            "article",
                            { className: "mcp-profile", key: index },
                            e(
                              "div",
                              { className: "mcp-profile-head" },
                              e(
                                "div",
                                { className: "mcp-profile-name" },
                                e(
                                  "h3",
                                  null,
                                  profile.name || "Unnamed profile",
                                ),
                                e(
                                  "div",
                                  { className: "mcp-hint mcp-mono" },
                                  (profile.tokenPatterns || []).join(", ") ||
                                    "Add an API token name",
                                ),
                              ),
                              pill(
                                (profile.allowedWriteToolPatterns || []).length
                                  ? "Write tools granted"
                                  : "No write tools",
                                (profile.allowedWriteToolPatterns || []).length
                                  ? "warn"
                                  : "",
                              ),
                              btn(
                                openProfile === index ? "Close" : "Edit",
                                () =>
                                  setOpenProfile(
                                    openProfile === index ? -1 : index,
                                  ),
                                false,
                                {
                                  "aria-expanded": openProfile === index,
                                  "aria-controls": `mcp-profile-${index}`,
                                  "aria-label": `${openProfile === index ? "Close" : "Edit"} profile ${profile.name || index + 1}`,
                                },
                              ),
                            ),
                            openProfile === index &&
                              e(
                                "div",
                                {
                                  id: `mcp-profile-${index}`,
                                  className: "mcp-profile-body",
                                },
                                e(
                                  "div",
                                  { className: "mcp-form-grid" },
                                  field(
                                    `profile-${index}-name`,
                                    "Profile name",
                                    profile.name || "",
                                    (value) =>
                                      updateProfile(index, "name", value),
                                    "A name to help operators recognize this profile.",
                                  ),
                                  field(
                                    `profile-${index}-tokens`,
                                    "API token names",
                                    profile.tokenPatterns || [],
                                    (value) =>
                                      updateProfile(
                                        index,
                                        "tokenPatterns",
                                        value,
                                      ),
                                    "Exact names or glob patterns, one per line. Example: mcp-operations. Never paste name:secret.",
                                    { list: true },
                                  ),
                                ),
                                e(
                                  "div",
                                  { className: "mcp-profile-section" },
                                  e("h3", null, "Read permissions"),
                                  e(
                                    "div",
                                    { className: "mcp-form-grid" },
                                    ...GRANTS.slice(0, 3).map((row) =>
                                      grant(
                                        row,
                                        profile,
                                        (key, value) =>
                                          updateProfile(index, key, value),
                                        `profile-${index}`,
                                      ),
                                    ),
                                  ),
                                ),
                                e(
                                  "div",
                                  { className: "mcp-profile-section" },
                                  e("h3", null, "Write permissions"),
                                  e(
                                    "p",
                                    {
                                      className: "mcp-hint",
                                      style: { marginBottom: 14 },
                                    },
                                    "A write needs both its tool grant and target grant. Project change previews also require the target’s write grant and resource read grant.",
                                  ),
                                  e(
                                    "div",
                                    { className: "mcp-form-grid" },
                                    ...GRANTS.slice(3, 7).map((row) =>
                                      grant(
                                        row,
                                        profile,
                                        (key, value) =>
                                          updateProfile(index, key, value),
                                        `profile-${index}`,
                                      ),
                                    ),
                                  ),
                                ),
                                e(
                                  "details",
                                  { className: "mcp-profile-section" },
                                  e(
                                    "summary",
                                    null,
                                    "Query execution & alarm acknowledgments",
                                  ),
                                  e(
                                    "div",
                                    { className: "mcp-form-grid" },
                                    ...GRANTS.slice(7).map((row) =>
                                      grant(
                                        row,
                                        profile,
                                        (key, value) =>
                                          updateProfile(index, key, value),
                                        `profile-${index}`,
                                      ),
                                    ),
                                  ),
                                ),
                                e(
                                  "div",
                                  {
                                    className: "mcp-buttons",
                                    style: { marginTop: 20 },
                                  },
                                  btn(
                                    "Remove this profile",
                                    () => {
                                      updateProfiles(
                                        profiles.filter(
                                          (item, position) =>
                                            position !== index,
                                        ),
                                      );
                                      setInputs({});
                                      setOpenProfile(-1);
                                    },
                                    false,
                                    { className: "mcp-btn danger" },
                                  ),
                                ),
                              ),
                          ),
                        ),
              ),
            ),
            e(
              "details",
              { className: "mcp-card" },
              e(
                "summary",
                null,
                `Global grants · ${config.authorizationMode === "profiles-only" ? "inactive" : "active"}`,
              ),
              e(
                "p",
                { className: "mcp-sub", style: { marginBottom: 18 } },
                config.authorizationMode === "profiles-only"
                  ? "Saved for compatibility, but ignored in Profiles only mode. Switch to additive mode to edit and activate them."
                  : "These grants apply in addition to every matching profile. A broad global grant cannot be narrowed by a profile.",
              ),
              e(
                "fieldset",
                { disabled: config.authorizationMode === "profiles-only" },
                e(
                  "div",
                  { className: "mcp-form-grid" },
                  ...GRANTS.map((row) => grant(row, config, update, "global")),
                ),
              ),
            ),
          );
        if (tab === "connection")
          body = e(
            "div",
            { className: "mcp-stack" },
            sectionHeading(
              "Connection",
              "Manage the endpoint and how MCP clients reach this Gateway.",
            ),
            card(
              "Client connection",
              "The endpoint below uses the saved alias. Unsaved alias changes do not change the live connection.",
              e(
                React.Fragment,
                null,
                e("code", { className: "mcp-endpoint" }, endpoint),
                e(
                  "div",
                  { className: "mcp-buttons" },
                  btn("Copy saved endpoint", copy),
                  e(
                    "a",
                    {
                      className: "mcp-btn",
                      href: "/app/platform/security/api-keys",
                      target: "_blank",
                      rel: "noopener noreferrer",
                    },
                    "Manage API keys ↗",
                  ),
                ),
                e(
                  "p",
                  { className: "mcp-sub" },
                  "Create a key under Platform → Security → API Keys, then configure the client with X-Ignition-API-Token: name:secret or Authorization: Bearer name:secret. Add its name to a token profile under Access & writes.",
                ),
              ),
            ),
            card(
              "Service & transports",
              "The service switch controls MCP requests. HTTP is the primary transport for current clients.",
              e(
                React.Fragment,
                null,
                check(
                  "enabled",
                  "Enable MCP service",
                  "Allow authenticated clients to use this module’s endpoints.",
                ),
                check(
                  "streamableEnabled",
                  "Enable streamable HTTP",
                  "Serve MCP over HTTP at /mcp.",
                ),
                check(
                  "sseFallbackEnabled",
                  "Enable legacy SSE fallback",
                  "Keep /sse and /message available for clients that need them.",
                ),
              ),
            ),
            e(
              "details",
              { className: "mcp-card" },
              e("summary", null, "Advanced endpoint & browser restrictions"),
              e(
                "div",
                { className: "mcp-form-grid" },
                field(
                  "mcp-alias",
                  "Endpoint alias",
                  config.mountAlias,
                  (value) => update("mountAlias", value),
                  "Changing the alias changes the endpoint URL. Update connected clients after saving.",
                ),
                field(
                  "mcp-origins",
                  "Allowed browser origins",
                  config.allowedOrigins,
                  (value) => update("allowedOrigins", value),
                  "Empty allows all origins. Example: https://app.example.com. Does not replace token authentication.",
                  { list: true },
                ),
                field(
                  "mcp-hosts",
                  "Allowed hosts",
                  config.allowedHosts,
                  (value) => update("allowedHosts", value),
                  "Empty allows all hosts. Example: localhost:6890 or *.example.com.",
                  { list: true },
                ),
              ),
            ),
          );
        if (tab === "limits")
          body = e(
            "div",
            { className: "mcp-stack" },
            sectionHeading(
              "Limits",
              "Bound client traffic, write batches and query results. Values apply to the module as a whole.",
            ),
            card(
              "Request & result limits",
              "Defaults are a starting point. Every value must be a whole number greater than zero.",
              e(
                "div",
                { className: "mcp-form-grid" },
                ...LIMITS.map(([key, label, fallback]) =>
                  field(
                    `mcp-${key}`,
                    label,
                    config[key],
                    (value) => update(key, value),
                    `Default: ${count(fallback)}.`,
                    { number: true },
                  ),
                ),
              ),
            ),
            card(
              "Historian defaults",
              "Used when a historian request does not specify a provider.",
              field(
                "mcp-historian-provider",
                "Default historian provider",
                config.historianDefaultProvider,
                (value) => update("historianDefaultProvider", value),
                "Leave empty to use Ignition’s default provider behavior.",
              ),
            ),
          );
        if (tab === "activity")
          body = e(
            "div",
            { className: "mcp-stack" },
            sectionHeading(
              "Activity",
              "Live, in-memory telemetry for this module. Refreshing activity keeps your configuration draft.",
            ),
            e(
              "div",
              { className: "mcp-grid" },
              card(
                "Runtime",
                "Current Gateway observations.",
                e(
                  "div",
                  { className: "mcp-stats" },
                  stat(count(status.activeSessions), "Active sessions"),
                  stat(count(status.queuedEvents), "Queued events"),
                  stat(count(total), "Tool calls"),
                ),
              ),
              card(
                "Call outcomes",
                "Since the module last started.",
                e(
                  "div",
                  { className: "mcp-stats" },
                  stat(
                    total
                      ? `${((Number(obs.successfulToolCalls || 0) / total) * 100).toFixed(1)}%`
                      : "—",
                    "Successful calls",
                  ),
                  stat(
                    `${Number(obs.averageToolDurationMs || 0).toFixed(1)} ms`,
                    "Average latency",
                  ),
                  stat(count(obs.blockedWriteAttempts), "Blocked writes"),
                ),
              ),
            ),
            card(
              "Most used tools",
              "Call counts and latency.",
              !(obs.topTools || []).length
                ? e(
                    "p",
                    { className: "mcp-sub" },
                    "No tool calls yet. Connect a client to begin.",
                  )
                : e(
                    "div",
                    { className: "mcp-table-wrap" },
                    e(
                      "table",
                      { className: "mcp-table" },
                      e(
                        "thead",
                        null,
                        e(
                          "tr",
                          null,
                          ...[
                            "Tool",
                            "Calls",
                            "Succeeded",
                            "Average",
                            "Maximum",
                          ].map((label) =>
                            e("th", { key: label, scope: "col" }, label),
                          ),
                        ),
                      ),
                      e(
                        "tbody",
                        null,
                        ...(obs.topTools || []).map((tool) =>
                          e(
                            "tr",
                            { key: tool.tool },
                            e("td", { className: "mcp-mono" }, tool.tool),
                            e("td", null, count(tool.calls)),
                            e("td", null, count(tool.successfulCalls)),
                            e(
                              "td",
                              null,
                              `${Number(tool.averageDurationMs || 0).toFixed(1)} ms`,
                            ),
                            e("td", null, `${count(tool.maxDurationMs)} ms`),
                          ),
                        ),
                      ),
                    ),
                  ),
            ),
            card(
              "Recent events",
              "Tool calls and write attempts, newest first.",
              !(obs.recentEvents || []).length
                ? e("p", { className: "mcp-sub" }, "No recent events.")
                : e(
                    "ul",
                    { className: "mcp-events" },
                    ...(obs.recentEvents || []).map((item, index) =>
                      e(
                        "li",
                        { key: index },
                        e(
                          "div",
                          { className: "mcp-event-head" },
                          pill(
                            item.eventType === "write-attempt"
                              ? item.allowed
                                ? "Write allowed"
                                : "Write blocked"
                              : item.success
                                ? "Call succeeded"
                                : "Call failed",
                            (
                              item.eventType === "write-attempt"
                                ? item.allowed
                                : item.success
                            )
                              ? "good"
                              : "warn",
                          ),
                          e(
                            "span",
                            { className: "mcp-hint" },
                            when(item.timestampMs),
                          ),
                        ),
                        e(
                          "strong",
                          { className: "mcp-mono" },
                          item.tool || "Event",
                        ),
                        e(
                          "div",
                          { className: "mcp-hint" },
                          [
                            item.actor && `Token: ${item.actor}`,
                            item.target && `Target: ${item.target}`,
                          ]
                            .filter(Boolean)
                            .join(" · "),
                        ),
                        item.detail &&
                          e("div", { className: "mcp-hint" }, item.detail),
                      ),
                    ),
                  ),
            ),
          );
        return e(
          "div",
          { className: "mcp-admin-root", "data-theme": theme },
          e(
            "div",
            { className: "mcp-admin-shell" },
            header,
            loadError &&
              e(
                "div",
                { className: "mcp-alert error", role: "alert" },
                loadError,
              ),
            saveError &&
              e(
                "div",
                { className: "mcp-alert error", role: "alert" },
                saveError,
              ),
            notice &&
              e(
                "div",
                { className: "mcp-alert success", role: "status" },
                notice,
              ),
            loading
              ? e(
                  "div",
                  { className: "mcp-card", role: "status" },
                  "Loading Gateway settings…",
                )
              : !saved
                ? e("div", { className: "mcp-card" }, btn("Try again", load))
                : e(
                    React.Fragment,
                    null,
                    e(
                      "div",
                      { className: "mcp-status-line" },
                      e(
                        "span",
                        {
                          className:
                            status.running && current.enabled
                              ? "mcp-good"
                              : "mcp-warn",
                        },
                        e("i", { className: "mcp-dot", "aria-hidden": true }),
                        status.running && current.enabled
                          ? "MCP service running"
                          : "MCP service unavailable",
                      ),
                      pill(
                        current.writesEnabled
                          ? "Saved: authorized writes"
                          : "Saved: read-only",
                        current.writesEnabled ? "warn" : "good",
                      ),
                      e(
                        "span",
                        { className: "mcp-hint" },
                        `Status refreshed ${loadedAt ? new Date(loadedAt).toLocaleTimeString() : "—"}`,
                      ),
                    ),
                    e(
                      "nav",
                      {
                        className: "mcp-nav",
                        "aria-label": "MCP settings sections",
                      },
                      ...[
                        ["overview", "Overview"],
                        ["access", "Access & writes"],
                        ["connection", "Connection"],
                        ["limits", "Limits"],
                        ["activity", "Activity"],
                      ].map(([id, label]) =>
                        e(
                          "button",
                          {
                            type: "button",
                            key: id,
                            "aria-current": tab === id ? "page" : undefined,
                            onClick: () => setTab(id),
                          },
                          label,
                        ),
                      ),
                    ),
                    remoteChanged &&
                      e(
                        "div",
                        { className: "mcp-alert error", role: "alert" },
                        "Gateway settings changed while you were editing. Reload the saved settings before making further changes. ",
                        btn("Reload saved settings", discard),
                      ),
                    e(
                      "fieldset",
                      { disabled: saving, "aria-label": "MCP configuration" },
                      body,
                    ),
                    dirty &&
                      e(
                        "div",
                        {
                          className: "mcp-savebar",
                          "aria-label": "Unsaved configuration",
                        },
                        e(
                          "div",
                          null,
                          e("strong", null, "Unsaved changes"),
                          e(
                            "p",
                            { className: "mcp-hint" },
                            validationError ||
                              "The Gateway continues using its saved settings until you save.",
                          ),
                          !validationError &&
                            e(
                              "details",
                              null,
                              e(
                                "summary",
                                null,
                                `Review ${changed.length} changed setting${changed.length === 1 ? "" : "s"}`,
                              ),
                              e(
                                "ul",
                                { className: "mcp-change-list" },
                                ...changed.map((key) =>
                                  e("li", { key }, NAMES[key] || key),
                                ),
                              ),
                            ),
                        ),
                        e(
                          "div",
                          { className: "mcp-buttons" },
                          btn("Discard changes", discard, false, {
                            disabled: saving,
                          }),
                          btn(saving ? "Saving…" : "Save changes", save, true, {
                            disabled:
                              saving || !!validationError || remoteChanged,
                          }),
                        ),
                      ),
                    e(
                      "footer",
                      { className: "mcp-footer" },
                      e(
                        "label",
                        { className: "mcp-inline" },
                        e("input", {
                          type: "checkbox",
                          checked: autoRefresh,
                          onChange: (event) =>
                            setAutoRefresh(event.target.checked),
                        }),
                        "Refresh status every 10 seconds",
                      ),
                      e(
                        "span",
                        null,
                        "Appearance is saved in this browser. Permissions are saved on the Gateway.",
                      ),
                    ),
                  ),
          ),
        );
      }
      _export("IgnitionMcpAdmin", IgnitionMcpAdmin);
    },
  };
});

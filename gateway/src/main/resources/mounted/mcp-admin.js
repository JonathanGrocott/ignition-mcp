System.register("com.jg.ignition.mcp.gateway", ["react"], function (_export) {
  "use strict";

  var React;

  return {
    setters: [
      function (_react) {
        React = _react && _react.default ? _react.default : _react;
      }
    ],
    execute: function () {
      const e = React.createElement;
      const { useEffect, useMemo, useState } = React;

      const STYLE_ID = "ignition-mcp-admin-style";
      const STYLE_TEXT = `
.mcp-admin-root {
  font-family: "IBM Plex Sans", "Segoe UI", sans-serif;
  background: linear-gradient(145deg, #f7f9fb, #edf3f7 58%, #f4fafc);
  color: #13212d;
  min-height: 100vh;
  padding: 22px;
}
.mcp-admin-shell { max-width: 1220px; margin: 0 auto; }
.mcp-admin-header {
  margin-bottom: 14px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(18, 33, 45, 0.08);
  border-radius: 14px;
  padding: 14px 16px;
  box-shadow: 0 10px 24px rgba(18, 33, 45, 0.06);
}
.mcp-admin-title { font-size: clamp(24px, 4vw, 34px); font-weight: 700; }
.mcp-admin-sub { font-size: 13px; color: #3b5568; margin-top: 5px; }
.mcp-admin-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
}
.mcp-admin-card {
  background: rgba(255,255,255,0.95);
  border: 1px solid rgba(18, 33, 45, 0.09);
  border-radius: 14px;
  box-shadow: 0 12px 28px rgba(18, 33, 45, 0.07);
  padding: 14px;
}
.mcp-admin-card-title {
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: #496174;
  margin-bottom: 8px;
  font-weight: 600;
}
.mcp-admin-kv { margin: 6px 0; font-size: 13px; color: #2f4b5e; }
.mcp-admin-kv strong { color: #12293b; }
.mcp-admin-form {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
  gap: 10px;
}
.mcp-admin-field label {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: #526f83;
  display: block;
  margin-bottom: 0;
}
.mcp-admin-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
  gap: 8px;
}
.mcp-admin-help {
  border: 1px solid #bdd0dd;
  color: #3f6076;
  width: 17px;
  height: 17px;
  line-height: 15px;
  text-align: center;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  cursor: help;
  flex: 0 0 auto;
}
.mcp-admin-field input, .mcp-admin-field textarea {
  width: 100%;
  border: 1px solid #c9d6df;
  border-radius: 8px;
  padding: 7px 8px;
  background: white;
  font-size: 13px;
}
.mcp-admin-field input:focus, .mcp-admin-field textarea:focus {
  outline: 2px solid #6fa3c9;
  outline-offset: 1px;
  border-color: #7ca9ca;
}
.mcp-admin-field textarea { min-height: 74px; resize: vertical; }
.mcp-admin-field-hint {
  margin-top: 4px;
  color: #4d6679;
  font-size: 11px;
  line-height: 1.4;
}
.mcp-admin-checks { display: grid; gap: 6px; margin-top: 6px; }
.mcp-admin-check { font-size: 13px; color: #2f4b5e; display: flex; gap: 8px; align-items: center; }
.mcp-admin-actions { margin-top: 12px; display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.mcp-admin-btn {
  border: none;
  border-radius: 999px;
  padding: 8px 14px;
  font-size: 12px;
  font-weight: 600;
  background: #14334e;
  color: white;
  cursor: pointer;
}
.mcp-admin-btn[disabled] { opacity: 0.6; cursor: not-allowed; }
.mcp-admin-note { font-size: 12px; color: #405f75; margin-top: 10px; }
.mcp-admin-error { color: #9e2020; font-size: 12px; margin-top: 8px; }
.mcp-admin-ok { color: #1e6d36; font-size: 12px; margin-top: 8px; }
`;

      function ensureStyles() {
        if (document.getElementById(STYLE_ID)) {
          return;
        }
        const style = document.createElement("style");
        style.id = STYLE_ID;
        style.textContent = STYLE_TEXT;
        document.head.appendChild(style);
      }

      function detectAlias() {
        const scripts = Array.from(document.scripts || []);
        for (const script of scripts) {
          const src = script && script.src ? script.src : "";
          const match = src.match(/\/res\/([^/]+)\/mcp-admin\.js/);
          if (match && match[1]) {
            return decodeURIComponent(match[1]);
          }
        }
        return "ignition-mcp";
      }

      function listToText(list) {
        return Array.isArray(list) ? list.join("\n") : "";
      }

      function textToList(text) {
        return String(text || "")
          .split(/[\n,]/)
          .map(item => item.trim())
          .filter(Boolean);
      }

      function asNumber(value, fallback) {
        const n = Number(value);
        return Number.isFinite(n) ? n : fallback;
      }

      function helpLabel(text, helpText) {
        return e("div", { className: "mcp-admin-label-row" },
          e("label", { title: helpText || "" }, text),
          helpText ? e("span", { className: "mcp-admin-help", title: helpText, "aria-label": helpText }, "?") : null
        );
      }

      function fieldHint(text) {
        return text ? e("div", { className: "mcp-admin-field-hint" }, text) : null;
      }

      function normalizeConfig(raw) {
        const cfg = raw || {};
        return {
          enabled: !!cfg.enabled,
          mountAlias: cfg.mountAlias || "ignition-mcp",
          allowedOrigins: Array.isArray(cfg.allowedOrigins) ? cfg.allowedOrigins : [],
          allowedHosts: Array.isArray(cfg.allowedHosts) ? cfg.allowedHosts : [],
          streamableEnabled: cfg.streamableEnabled !== false,
          sseFallbackEnabled: cfg.sseFallbackEnabled !== false,
          maxConcurrentSessions: asNumber(cfg.maxConcurrentSessions, 200),
          maxRequestsPerMinutePerToken: asNumber(cfg.maxRequestsPerMinutePerToken, 300),
          maxWriteOpsPerMinutePerToken: asNumber(cfg.maxWriteOpsPerMinutePerToken, 60),
          defaultDryRun: cfg.defaultDryRun !== false,
          maxBatchWriteSize: asNumber(cfg.maxBatchWriteSize, 50),
          allowedTagReadPatterns: Array.isArray(cfg.allowedTagReadPatterns) ? cfg.allowedTagReadPatterns : ["*"],
          allowedTagWritePatterns: Array.isArray(cfg.allowedTagWritePatterns) ? cfg.allowedTagWritePatterns : [],
          allowedAlarmAckSources: Array.isArray(cfg.allowedAlarmAckSources) ? cfg.allowedAlarmAckSources : ["*"],
          allowedNamedQueryExecutePatterns: Array.isArray(cfg.allowedNamedQueryExecutePatterns) ? cfg.allowedNamedQueryExecutePatterns : ["*"],
          historianDefaultProvider: cfg.historianDefaultProvider || "",
          historianMaxRows: asNumber(cfg.historianMaxRows, 5000),
          namedQueryMaxRows: asNumber(cfg.namedQueryMaxRows, 1000)
        };
      }

      function IgnitionMcpAdmin() {
        ensureStyles();

        const alias = useMemo(() => detectAlias(), []);
        const endpointBase = `/data/${alias}/admin`;

        const [loading, setLoading] = useState(true);
        const [saving, setSaving] = useState(false);
        const [error, setError] = useState("");
        const [message, setMessage] = useState("");
        const [csrfToken, setCsrfToken] = useState("");
        const [status, setStatus] = useState({});
        const [config, setConfig] = useState(normalizeConfig(null));

        const loadStatus = () => {
          setLoading(true);
          setError("");
          fetch(`${endpointBase}/status`, { credentials: "same-origin" })
            .then(r => r.ok ? r.json() : Promise.reject(`HTTP ${r.status}`))
            .then(payload => {
              setStatus(payload || {});
              setConfig(normalizeConfig(payload && payload.config));
              setCsrfToken(payload && payload.csrfToken ? payload.csrfToken : "");
            })
            .catch(err => setError(String(err || "Failed to load status")))
            .finally(() => setLoading(false));
        };

        useEffect(() => {
          loadStatus();
        }, [endpointBase]);

        const setBool = (key, value) => setConfig(prev => ({ ...prev, [key]: !!value }));
        const setText = (key, value) => setConfig(prev => ({ ...prev, [key]: value }));
        const setNum = (key, value) => setConfig(prev => ({ ...prev, [key]: asNumber(value, prev[key]) }));
        const setList = (key, value) => setConfig(prev => ({ ...prev, [key]: textToList(value) }));

        const saveConfig = () => {
          setSaving(true);
          setError("");
          setMessage("");
          fetch(`${endpointBase}/config`, {
            method: "POST",
            credentials: "same-origin",
            headers: {
              "Content-Type": "application/json",
              ...(csrfToken ? { "X-CSRF-Token": csrfToken } : {})
            },
            body: JSON.stringify({ config })
          })
            .then(r => r.ok ? r.json() : r.text().then(t => Promise.reject(t || `HTTP ${r.status}`)))
            .then(payload => {
              if (payload && payload.config) {
                setConfig(normalizeConfig(payload.config));
              }
              setMessage("Configuration saved.");
              loadStatus();
            })
            .catch(err => setError(String(err || "Failed to save configuration")))
            .finally(() => setSaving(false));
        };

        if (loading) {
          return e("div", { className: "mcp-admin-root" }, "Loading MCP status...");
        }

        return e("div", { className: "mcp-admin-root" },
          e("div", { className: "mcp-admin-shell" },
            e("div", { className: "mcp-admin-header" },
              e("div", { className: "mcp-admin-title" }, "Ignition MCP"),
              e("div", { className: "mcp-admin-sub" }, "Gateway configuration and runtime status.")
            ),

            e("div", { className: "mcp-admin-grid" },
              e("div", { className: "mcp-admin-card" },
                e("div", { className: "mcp-admin-card-title" }, "Runtime"),
                e("div", { className: "mcp-admin-kv" }, e("strong", null, "Service"), `: ${status.running ? "Running" : "Not running"}`),
                e("div", { className: "mcp-admin-kv" }, e("strong", null, "Enabled"), `: ${config.enabled ? "Yes" : "No"}`),
                e("div", { className: "mcp-admin-kv" }, e("strong", null, "Active Sessions"), `: ${status.activeSessions || 0}`),
                e("div", { className: "mcp-admin-kv" }, e("strong", null, "Queued Events"), `: ${status.queuedEvents || 0}`),
                e("div", { className: "mcp-admin-kv" }, e("strong", null, "By Transport"), `: ${JSON.stringify(status.sessionsByTransport || {})}`)
              )
            ),

            e("div", { className: "mcp-admin-card", style: { marginTop: "12px" } },
              e("div", { className: "mcp-admin-card-title" }, "Configuration"),
              e("div", { className: "mcp-admin-note" }, "List fields accept either comma-separated values or one value per line."),
              e("div", { className: "mcp-admin-form" },
              e("div", { className: "mcp-admin-field" },
                helpLabel("Mount Alias", "Used in the route path /data/<mountAlias>. Letters, numbers, dashes and underscores are recommended."),
                e("input", {
                  type: "text",
                  value: config.mountAlias,
                  onChange: ev => setText("mountAlias", ev.target.value)
                }),
                fieldHint("Changing this value updates the MCP endpoint path.")
              ),
              e("div", { className: "mcp-admin-field" },
                helpLabel("Historian Default Provider", "Ignition historian provider name used when the tool request omits provider."),
                e("input", {
                  type: "text",
                  value: config.historianDefaultProvider,
                  onChange: ev => setText("historianDefaultProvider", ev.target.value)
                }),
                fieldHint("Leave blank to use Ignition default behavior.")
              ),
              e("div", { className: "mcp-admin-field" },
                helpLabel("Max Concurrent Sessions", "Maximum number of live MCP sessions. Must be greater than 0."),
                e("input", {
                  type: "number",
                  value: config.maxConcurrentSessions,
                  onChange: ev => setNum("maxConcurrentSessions", ev.target.value)
                }),
                fieldHint("Positive integer. Default is 200.")
              ),
              e("div", { className: "mcp-admin-field" },
                helpLabel("Max Requests / Minute / Token", "Per-token request rate limit. Must be greater than 0."),
                e("input", {
                  type: "number",
                  value: config.maxRequestsPerMinutePerToken,
                  onChange: ev => setNum("maxRequestsPerMinutePerToken", ev.target.value)
                }),
                fieldHint("Positive integer. Default is 300.")
              ),
              e("div", { className: "mcp-admin-field" },
                helpLabel("Max Write Ops / Minute / Token", "Per-token mutation/write rate limit. Must be greater than 0."),
                e("input", {
                  type: "number",
                  value: config.maxWriteOpsPerMinutePerToken,
                  onChange: ev => setNum("maxWriteOpsPerMinutePerToken", ev.target.value)
                }),
                fieldHint("Positive integer. Default is 60.")
              ),
              e("div", { className: "mcp-admin-field" },
                helpLabel("Max Batch Write Size", "Maximum number of writes accepted in a single batch request. Must be greater than 0."),
                e("input", {
                  type: "number",
                  value: config.maxBatchWriteSize,
                  onChange: ev => setNum("maxBatchWriteSize", ev.target.value)
                }),
                fieldHint("Positive integer. Default is 50.")
              ),
              e("div", { className: "mcp-admin-field" },
                helpLabel("Historian Max Rows", "Upper limit for historian query row counts."),
                e("input", {
                  type: "number",
                  value: config.historianMaxRows,
                  onChange: ev => setNum("historianMaxRows", ev.target.value)
                }),
                fieldHint("Positive integer. Default is 5000.")
<<<<<<< ours
              ),
              e("div", { className: "mcp-admin-field" },
<<<<<<< ours
                e("label", null, "Named Query Max Rows"),
                e("input", {
                  type: "number",
                  value: config.namedQueryMaxRows,
                  onChange: ev => setNum("namedQueryMaxRows", ev.target.value)
                })
              ),
              e("div", { className: "mcp-admin-field" },
                e("label", null, "Allowed Origins (comma/new line)"),
=======
                helpLabel("Allowed Origins", "Matched against the HTTP Origin header. Supports glob wildcards: * and ?. Leave empty to allow any origin."),
>>>>>>> theirs
=======
              ),
              e("div", { className: "mcp-admin-field" },
                helpLabel("Allowed Origins", "Matched against the HTTP Origin header. Supports glob wildcards: * and ?. Leave empty to allow any origin."),
>>>>>>> theirs
                e("textarea", {
                  value: listToText(config.allowedOrigins),
                  onChange: ev => setList("allowedOrigins", ev.target.value)
                }),
                fieldHint("Examples: https://app.example.com, https://*.example.com")
              ),
              e("div", { className: "mcp-admin-field" },
                helpLabel("Allowed Hosts", "Matched against Host header (or server name). Supports glob wildcards: * and ?. Include :port if needed. Leave empty to allow any host."),
                e("textarea", {
                  value: listToText(config.allowedHosts),
                  onChange: ev => setList("allowedHosts", ev.target.value)
                }),
                fieldHint("Examples: localhost:8088, *.corp.example.com")
              ),
              e("div", { className: "mcp-admin-field" },
                helpLabel("Allowed Tag Read Patterns", "Tag paths allowed for read operations. Supports glob wildcards: * and ?."),
                e("textarea", {
                  value: listToText(config.allowedTagReadPatterns),
                  onChange: ev => setList("allowedTagReadPatterns", ev.target.value)
                }),
                fieldHint("Examples: *, [default]MCP/*")
              ),
              e("div", { className: "mcp-admin-field" },
                helpLabel("Allowed Tag Write Patterns", "Tag paths allowed for write operations. Supports glob wildcards: * and ?."),
                e("textarea", {
                  value: listToText(config.allowedTagWritePatterns),
                  onChange: ev => setList("allowedTagWritePatterns", ev.target.value)
                }),
                fieldHint("Example: [default]MCP/*")
              ),
              e("div", { className: "mcp-admin-field" },
                helpLabel("Allowed Alarm Ack Sources", "Allowed source strings for alarm acknowledge calls. Supports glob wildcards: * and ?."),
                e("textarea", {
                  value: listToText(config.allowedAlarmAckSources),
                  onChange: ev => setList("allowedAlarmAckSources", ev.target.value)
<<<<<<< ours
<<<<<<< ours
                })
              ),
              e("div", { className: "mcp-admin-field" },
                e("label", null, "Allowed Named Query Execute Patterns"),
                e("textarea", {
                  value: listToText(config.allowedNamedQueryExecutePatterns),
                  onChange: ev => setList("allowedNamedQueryExecutePatterns", ev.target.value)
                })
              )
            ),

            e("div", { className: "mcp-admin-checks" },
              e("label", { className: "mcp-admin-check" },
                e("input", {
                  type: "checkbox",
                  checked: !!config.enabled,
                  onChange: ev => setBool("enabled", ev.target.checked)
=======
>>>>>>> theirs
=======
>>>>>>> theirs
                }),
                fieldHint("Use * to allow any source.")
              )
              ),

              e("div", { className: "mcp-admin-checks" },
                e("label", { className: "mcp-admin-check", title: "Enable or disable all MCP endpoints." },
                  e("input", {
                    type: "checkbox",
                    checked: !!config.enabled,
                    onChange: ev => setBool("enabled", ev.target.checked)
                  }),
                  "MCP Enabled"
                ),
                e("label", { className: "mcp-admin-check", title: "Enable streamable HTTP transport." },
                  e("input", {
                    type: "checkbox",
                    checked: !!config.streamableEnabled,
                    onChange: ev => setBool("streamableEnabled", ev.target.checked)
                  }),
                  "Streamable Transport Enabled"
                ),
                e("label", { className: "mcp-admin-check", title: "Allow SSE fallback transport when needed." },
                  e("input", {
                    type: "checkbox",
                    checked: !!config.sseFallbackEnabled,
                    onChange: ev => setBool("sseFallbackEnabled", ev.target.checked)
                  }),
                  "SSE Fallback Enabled"
                ),
                e("label", { className: "mcp-admin-check", title: "Start mutation operations in dry-run mode by default." },
                  e("input", {
                    type: "checkbox",
                    checked: !!config.defaultDryRun,
                    onChange: ev => setBool("defaultDryRun", ev.target.checked)
                  }),
                  "Default Dry-Run for Mutations"
                )
              ),

              e("div", { className: "mcp-admin-actions" },
                e("button", { className: "mcp-admin-btn", onClick: saveConfig, disabled: saving }, saving ? "Saving..." : "Save Configuration"),
                e("button", { className: "mcp-admin-btn", onClick: loadStatus, disabled: loading || saving }, "Refresh")
              ),
              error ? e("div", { className: "mcp-admin-error" }, error) : null,
              message ? e("div", { className: "mcp-admin-ok" }, message) : null
            )
          )
        );
      }

      _export("IgnitionMcpAdmin", IgnitionMcpAdmin);
    }
  };
});

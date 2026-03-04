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
  background: linear-gradient(140deg, #f6f8fa, #edf2f5 60%, #f7fbfd);
  color: #13212d;
  min-height: 100vh;
  padding: 22px;
}
.mcp-admin-header { margin-bottom: 14px; }
.mcp-admin-title { font-size: clamp(24px, 4vw, 34px); font-weight: 700; }
.mcp-admin-sub { font-size: 13px; color: #3b5568; margin-top: 4px; }
.mcp-admin-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
}
.mcp-admin-card {
  background: rgba(255,255,255,0.94);
  border: 1px solid rgba(18, 33, 45, 0.1);
  border-radius: 12px;
  box-shadow: 0 10px 24px rgba(18, 33, 45, 0.08);
  padding: 12px;
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
  margin-bottom: 4px;
}
.mcp-admin-field input, .mcp-admin-field textarea {
  width: 100%;
  border: 1px solid #c9d6df;
  border-radius: 8px;
  padding: 7px 8px;
  background: white;
  font-size: 13px;
}
.mcp-admin-field textarea { min-height: 74px; resize: vertical; }
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
          historianDefaultProvider: cfg.historianDefaultProvider || "",
          historianMaxRows: asNumber(cfg.historianMaxRows, 5000)
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
          e("div", { className: "mcp-admin-header" },
            e("div", { className: "mcp-admin-title" }, "Ignition MCP"),
            e("div", { className: "mcp-admin-sub" }, `Route base: /data/${alias} (served by the Ignition gateway web server port)`)
          ),

          e("div", { className: "mcp-admin-grid" },
            e("div", { className: "mcp-admin-card" },
              e("div", { className: "mcp-admin-kv" }, e("strong", null, "Service"), `: ${status.running ? "Running" : "Not running"}`),
              e("div", { className: "mcp-admin-kv" }, e("strong", null, "Enabled"), `: ${config.enabled ? "Yes" : "No"}`),
              e("div", { className: "mcp-admin-kv" }, e("strong", null, "Active Sessions"), `: ${status.activeSessions || 0}`),
              e("div", { className: "mcp-admin-kv" }, e("strong", null, "Queued Events"), `: ${status.queuedEvents || 0}`),
              e("div", { className: "mcp-admin-kv" }, e("strong", null, "By Transport"), `: ${JSON.stringify(status.sessionsByTransport || {})}`)
            ),
            e("div", { className: "mcp-admin-card" },
              e("div", { className: "mcp-admin-note" },
                "Port note: this module uses Ignition's built-in web server. A separate MCP port is not exposed in this version."
              )
            )
          ),

          e("div", { className: "mcp-admin-card", style: { marginTop: "12px" } },
            e("div", { className: "mcp-admin-form" },
              e("div", { className: "mcp-admin-field" },
                e("label", null, "Mount Alias"),
                e("input", {
                  type: "text",
                  value: config.mountAlias,
                  onChange: ev => setText("mountAlias", ev.target.value)
                })
              ),
              e("div", { className: "mcp-admin-field" },
                e("label", null, "Historian Default Provider"),
                e("input", {
                  type: "text",
                  value: config.historianDefaultProvider,
                  onChange: ev => setText("historianDefaultProvider", ev.target.value)
                })
              ),
              e("div", { className: "mcp-admin-field" },
                e("label", null, "Max Concurrent Sessions"),
                e("input", {
                  type: "number",
                  value: config.maxConcurrentSessions,
                  onChange: ev => setNum("maxConcurrentSessions", ev.target.value)
                })
              ),
              e("div", { className: "mcp-admin-field" },
                e("label", null, "Max Requests / Minute / Token"),
                e("input", {
                  type: "number",
                  value: config.maxRequestsPerMinutePerToken,
                  onChange: ev => setNum("maxRequestsPerMinutePerToken", ev.target.value)
                })
              ),
              e("div", { className: "mcp-admin-field" },
                e("label", null, "Max Write Ops / Minute / Token"),
                e("input", {
                  type: "number",
                  value: config.maxWriteOpsPerMinutePerToken,
                  onChange: ev => setNum("maxWriteOpsPerMinutePerToken", ev.target.value)
                })
              ),
              e("div", { className: "mcp-admin-field" },
                e("label", null, "Max Batch Write Size"),
                e("input", {
                  type: "number",
                  value: config.maxBatchWriteSize,
                  onChange: ev => setNum("maxBatchWriteSize", ev.target.value)
                })
              ),
              e("div", { className: "mcp-admin-field" },
                e("label", null, "Historian Max Rows"),
                e("input", {
                  type: "number",
                  value: config.historianMaxRows,
                  onChange: ev => setNum("historianMaxRows", ev.target.value)
                })
              ),
              e("div", { className: "mcp-admin-field" },
                e("label", null, "Allowed Origins (comma/new line)"),
                e("textarea", {
                  value: listToText(config.allowedOrigins),
                  onChange: ev => setList("allowedOrigins", ev.target.value)
                })
              ),
              e("div", { className: "mcp-admin-field" },
                e("label", null, "Allowed Hosts (comma/new line)"),
                e("textarea", {
                  value: listToText(config.allowedHosts),
                  onChange: ev => setList("allowedHosts", ev.target.value)
                })
              ),
              e("div", { className: "mcp-admin-field" },
                e("label", null, "Allowed Tag Read Patterns"),
                e("textarea", {
                  value: listToText(config.allowedTagReadPatterns),
                  onChange: ev => setList("allowedTagReadPatterns", ev.target.value)
                })
              ),
              e("div", { className: "mcp-admin-field" },
                e("label", null, "Allowed Tag Write Patterns"),
                e("textarea", {
                  value: listToText(config.allowedTagWritePatterns),
                  onChange: ev => setList("allowedTagWritePatterns", ev.target.value)
                })
              ),
              e("div", { className: "mcp-admin-field" },
                e("label", null, "Allowed Alarm Ack Sources"),
                e("textarea", {
                  value: listToText(config.allowedAlarmAckSources),
                  onChange: ev => setList("allowedAlarmAckSources", ev.target.value)
                })
              )
            ),

            e("div", { className: "mcp-admin-checks" },
              e("label", { className: "mcp-admin-check" },
                e("input", {
                  type: "checkbox",
                  checked: !!config.enabled,
                  onChange: ev => setBool("enabled", ev.target.checked)
                }),
                "MCP Enabled"
              ),
              e("label", { className: "mcp-admin-check" },
                e("input", {
                  type: "checkbox",
                  checked: !!config.streamableEnabled,
                  onChange: ev => setBool("streamableEnabled", ev.target.checked)
                }),
                "Streamable Transport Enabled"
              ),
              e("label", { className: "mcp-admin-check" },
                e("input", {
                  type: "checkbox",
                  checked: !!config.sseFallbackEnabled,
                  onChange: ev => setBool("sseFallbackEnabled", ev.target.checked)
                }),
                "SSE Fallback Enabled"
              ),
              e("label", { className: "mcp-admin-check" },
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
        );
      }

      _export("IgnitionMcpAdmin", IgnitionMcpAdmin);
    }
  };
});

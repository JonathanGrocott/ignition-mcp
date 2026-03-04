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
  background:
    radial-gradient(circle at 6% 10%, rgba(136, 176, 210, 0.16), transparent 34%),
    radial-gradient(circle at 94% 5%, rgba(126, 184, 163, 0.15), transparent 30%),
    linear-gradient(145deg, #f6f9fb, #eff5f8 56%, #f6fafc);
  color: #13212d;
  min-height: 100vh;
  padding: 22px;
}
.mcp-admin-shell { max-width: 1280px; margin: 0 auto; }
.mcp-admin-header {
  margin-bottom: 12px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(19, 34, 45, 0.1);
  border-radius: 14px;
  padding: 14px 16px;
  box-shadow: 0 12px 28px rgba(19, 34, 45, 0.06);
}
.mcp-admin-title { font-size: clamp(24px, 4vw, 34px); font-weight: 700; letter-spacing: -0.01em; }
.mcp-admin-sub { font-size: 13px; color: #415b70; margin-top: 4px; }
.mcp-admin-toolbar {
  margin-top: 10px;
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
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
.mcp-admin-toggle {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
  color: #355266;
}
.mcp-admin-last { font-size: 12px; color: #4e687a; }
.mcp-admin-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
}
.mcp-admin-card {
  background: rgba(255,255,255,0.95);
  border: 1px solid rgba(19, 34, 45, 0.09);
  border-radius: 14px;
  box-shadow: 0 12px 28px rgba(19, 34, 45, 0.07);
  padding: 14px;
}
.mcp-admin-card-title {
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: #476276;
  margin-bottom: 8px;
  font-weight: 700;
}
.mcp-admin-kv { margin: 6px 0; font-size: 13px; color: #2f4b5e; }
.mcp-admin-kv strong { color: #13293d; }
.mcp-admin-kpi-grid {
  margin-top: 10px;
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}
.mcp-admin-kpi {
  border: 1px solid #d2dee7;
  border-radius: 12px;
  background: #fbfdff;
  padding: 10px;
}
.mcp-admin-kpi-label {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: #617a8d;
}
.mcp-admin-kpi-value {
  margin-top: 4px;
  font-size: 21px;
  font-weight: 700;
  color: #153249;
}
.mcp-admin-kpi-sub {
  margin-top: 2px;
  font-size: 12px;
  color: #4b667a;
}
.mcp-admin-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.mcp-admin-table th,
.mcp-admin-table td {
  text-align: left;
  padding: 6px 4px;
  border-bottom: 1px solid #e3ebf1;
  color: #2f4c5f;
}
.mcp-admin-table th {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: #5c7589;
}
.mcp-admin-events {
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 270px;
  overflow: auto;
  display: grid;
  gap: 8px;
}
.mcp-admin-event {
  border: 1px solid #d7e3ec;
  border-radius: 10px;
  background: #fcfeff;
  padding: 8px;
}
.mcp-admin-event-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 11px;
  color: #4f6a7d;
}
.mcp-admin-event-body {
  margin-top: 4px;
  font-size: 12px;
  color: #244255;
}
.mcp-admin-form {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
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
.mcp-admin-field input,
.mcp-admin-field textarea {
  width: 100%;
  border: 1px solid #c9d6df;
  border-radius: 8px;
  padding: 7px 8px;
  background: white;
  font-size: 13px;
}
.mcp-admin-field input:focus,
.mcp-admin-field textarea:focus {
  outline: 2px solid #6fa3c9;
  outline-offset: 1px;
  border-color: #7ca9ca;
}
.mcp-admin-field textarea { min-height: 76px; resize: vertical; }
.mcp-admin-field-hint {
  margin-top: 4px;
  color: #4d6679;
  font-size: 11px;
  line-height: 1.4;
}
.mcp-admin-checks { display: grid; gap: 6px; margin-top: 10px; }
.mcp-admin-check {
  font-size: 13px;
  color: #2f4b5e;
  display: flex;
  gap: 8px;
  align-items: center;
}
.mcp-admin-actions { margin-top: 12px; display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.mcp-admin-note { font-size: 12px; color: #405f75; margin-top: 10px; }
.mcp-admin-error { color: #9e2020; font-size: 12px; margin-top: 8px; }
.mcp-admin-ok { color: #1e6d36; font-size: 12px; margin-top: 8px; }
.mcp-badge {
  display: inline-flex;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}
.mcp-badge.ok { background: #dcefe3; color: #1f6c3e; }
.mcp-badge.warn { background: #f9e8d0; color: #7b5318; }
.mcp-badge.off { background: #f5d9d9; color: #8a2323; }
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

      function formatNumber(value) {
        const numeric = Number(value || 0);
        return Number.isFinite(numeric) ? numeric.toLocaleString() : "0";
      }

      function formatPercent(value) {
        const numeric = Number(value || 0);
        return `${numeric.toFixed(1)}%`;
      }

      function formatDuration(value) {
        const numeric = Number(value || 0);
        return `${numeric.toFixed(1)} ms`;
      }

      function formatWhen(timestampMs) {
        if (!timestampMs) {
          return "-";
        }
        try {
          return new Date(timestampMs).toLocaleString();
        } catch (err) {
          return "-";
        }
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

      function kpi(label, value, sub) {
        return e("div", { className: "mcp-admin-kpi" },
          e("div", { className: "mcp-admin-kpi-label" }, label),
          e("div", { className: "mcp-admin-kpi-value" }, value),
          sub ? e("div", { className: "mcp-admin-kpi-sub" }, sub) : null
        );
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
          allowedNamedQueryExecutePatterns: Array.isArray(cfg.allowedNamedQueryExecutePatterns)
            ? cfg.allowedNamedQueryExecutePatterns
            : ["*"],
          historianDefaultProvider: cfg.historianDefaultProvider || "",
          historianMaxRows: asNumber(cfg.historianMaxRows, 5000),
          namedQueryMaxRows: asNumber(cfg.namedQueryMaxRows, 1000)
        };
      }

      function normalizeObservability(raw) {
        const obs = raw || {};
        return {
          startedAtMs: asNumber(obs.startedAtMs, 0),
          sampledAtMs: asNumber(obs.sampledAtMs, 0),
          totalToolCalls: asNumber(obs.totalToolCalls, 0),
          successfulToolCalls: asNumber(obs.successfulToolCalls, 0),
          failedToolCalls: asNumber(obs.failedToolCalls, 0),
          totalWriteAttempts: asNumber(obs.totalWriteAttempts, 0),
          allowedWriteAttempts: asNumber(obs.allowedWriteAttempts, 0),
          blockedWriteAttempts: asNumber(obs.blockedWriteAttempts, 0),
          averageToolDurationMs: Number(obs.averageToolDurationMs || 0),
          topTools: Array.isArray(obs.topTools) ? obs.topTools : [],
          recentEvents: Array.isArray(obs.recentEvents) ? obs.recentEvents : []
        };
      }

      function statusBadge(label, enabled) {
        return e("span", { className: `mcp-badge ${enabled ? "ok" : "off"}` }, `${label}: ${enabled ? "ON" : "OFF"}`);
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
        const [observability, setObservability] = useState(normalizeObservability(null));
        const [config, setConfig] = useState(normalizeConfig(null));
        const [dirty, setDirty] = useState(false);
        const [autoRefresh, setAutoRefresh] = useState(true);
        const [lastLoadedAt, setLastLoadedAt] = useState(0);

        const loadStatus = (silent) => {
          if (!silent) {
            setLoading(true);
          }
          setError("");
          fetch(`${endpointBase}/status`, { credentials: "same-origin" })
            .then(r => r.ok ? r.json() : Promise.reject(`HTTP ${r.status}`))
            .then(payload => {
              setStatus(payload || {});
              setObservability(normalizeObservability(payload && payload.observability));
              if (!silent || !dirty) {
                setConfig(normalizeConfig(payload && payload.config));
                setDirty(false);
              }
              setCsrfToken(payload && payload.csrfToken ? payload.csrfToken : "");
              setLastLoadedAt(Date.now());
            })
            .catch(err => setError(String(err || "Failed to load status")))
            .finally(() => {
              if (!silent) {
                setLoading(false);
              }
            });
        };

        useEffect(() => {
          loadStatus(false);
        }, [endpointBase]);

        useEffect(() => {
          if (!autoRefresh) {
            return undefined;
          }
          const id = setInterval(() => loadStatus(true), 10000);
          return () => clearInterval(id);
        }, [autoRefresh, endpointBase, dirty]);

        const setBool = (key, value) => {
          setDirty(true);
          setConfig(prev => ({ ...prev, [key]: !!value }));
        };
        const setText = (key, value) => {
          setDirty(true);
          setConfig(prev => ({ ...prev, [key]: value }));
        };
        const setNum = (key, value) => {
          setDirty(true);
          setConfig(prev => ({ ...prev, [key]: asNumber(value, prev[key]) }));
        };
        const setList = (key, value) => {
          setDirty(true);
          setConfig(prev => ({ ...prev, [key]: textToList(value) }));
        };

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
              setDirty(false);
              setMessage("Configuration saved.");
              loadStatus(true);
            })
            .catch(err => setError(String(err || "Failed to save configuration")))
            .finally(() => setSaving(false));
        };

        if (loading) {
          return e("div", { className: "mcp-admin-root" }, "Loading MCP status...");
        }

        const totalCalls = observability.totalToolCalls;
        const successRate = totalCalls === 0 ? 0 : (observability.successfulToolCalls / totalCalls) * 100;
        const writeBlockedRate = observability.totalWriteAttempts === 0
          ? 0
          : (observability.blockedWriteAttempts / observability.totalWriteAttempts) * 100;

        return e("div", { className: "mcp-admin-root" },
          e("div", { className: "mcp-admin-shell" },
            e("div", { className: "mcp-admin-header" },
              e("div", { className: "mcp-admin-title" }, "Ignition MCP"),
              e("div", { className: "mcp-admin-sub" }, "Gateway controls, policy settings, and live usage telemetry."),
              e("div", { className: "mcp-admin-toolbar" },
                statusBadge("Service", !!status.running),
                statusBadge("Module", !!config.enabled),
                statusBadge("Streamable", !!config.streamableEnabled),
                statusBadge("SSE Fallback", !!config.sseFallbackEnabled),
                e("button", {
                  className: "mcp-admin-btn",
                  onClick: () => loadStatus(false),
                  disabled: loading || saving
                }, "Refresh"),
                e("label", { className: "mcp-admin-toggle", title: "Refreshes runtime and observability data every 10 seconds." },
                  e("input", {
                    type: "checkbox",
                    checked: !!autoRefresh,
                    onChange: ev => setAutoRefresh(ev.target.checked)
                  }),
                  "Auto refresh (10s)"
                ),
                e("div", { className: "mcp-admin-last" }, `Last refresh: ${formatWhen(lastLoadedAt)}`)
              )
            ),

            e("div", { className: "mcp-admin-grid" },
              e("div", { className: "mcp-admin-card" },
                e("div", { className: "mcp-admin-card-title" }, "Runtime"),
                e("div", { className: "mcp-admin-kv" }, e("strong", null, "Active Sessions"), `: ${formatNumber(status.activeSessions || 0)}`),
                e("div", { className: "mcp-admin-kv" }, e("strong", null, "Queued Events"), `: ${formatNumber(status.queuedEvents || 0)}`),
                e("div", { className: "mcp-admin-kv" }, e("strong", null, "Sessions by Transport"), `: ${JSON.stringify(status.sessionsByTransport || {})}`),
                e("div", { className: "mcp-admin-kv" }, e("strong", null, "Mount Alias"), `: ${config.mountAlias}`)
              ),
              e("div", { className: "mcp-admin-card" },
                e("div", { className: "mcp-admin-card-title" }, "Usage Overview"),
                e("div", { className: "mcp-admin-kpi-grid" },
                  kpi("Tool Calls", formatNumber(totalCalls), `${formatNumber(observability.failedToolCalls)} failed`),
                  kpi("Success Rate", formatPercent(successRate), `${formatNumber(observability.successfulToolCalls)} successful`),
                  kpi("Avg Tool Latency", formatDuration(observability.averageToolDurationMs), "Mean duration"),
                  kpi("Write Block Rate", formatPercent(writeBlockedRate), `${formatNumber(observability.blockedWriteAttempts)} blocked`)
                )
              )
            ),

            e("div", { className: "mcp-admin-grid", style: { marginTop: "12px" } },
              e("div", { className: "mcp-admin-card" },
                e("div", { className: "mcp-admin-card-title" }, "Top Tools"),
                observability.topTools.length === 0
                  ? e("div", { className: "mcp-admin-note" }, "No tool calls recorded yet.")
                  : e("table", { className: "mcp-admin-table" },
                    e("thead", null,
                      e("tr", null,
                        e("th", null, "Tool"),
                        e("th", null, "Calls"),
                        e("th", null, "Success"),
                        e("th", null, "Avg (ms)"),
                        e("th", null, "Max (ms)")
                      )
                    ),
                    e("tbody", null,
                      ...observability.topTools.map(tool => e("tr", { key: `${tool.tool}-${tool.calls}` },
                        e("td", { title: tool.tool || "" }, tool.tool || "(unknown)"),
                        e("td", null, formatNumber(tool.calls || 0)),
                        e("td", null, formatNumber(tool.successfulCalls || 0)),
                        e("td", null, Number(tool.averageDurationMs || 0).toFixed(1)),
                        e("td", null, formatNumber(tool.maxDurationMs || 0))
                      ))
                    )
                  )
              ),
              e("div", { className: "mcp-admin-card" },
                e("div", { className: "mcp-admin-card-title" }, "Recent Activity"),
                observability.recentEvents.length === 0
                  ? e("div", { className: "mcp-admin-note" }, "No recent events.")
                  : e("ul", { className: "mcp-admin-events" },
                    ...observability.recentEvents.map((event, index) => {
                      const summary = event.eventType === "write-attempt"
                        ? `${event.allowed ? "allowed" : "blocked"} write`
                        : `${event.success ? "success" : "error"} tool call`;
                      return e("li", { className: "mcp-admin-event", key: `${event.timestampMs || 0}-${event.eventType || "event"}-${index}` },
                        e("div", { className: "mcp-admin-event-head" },
                          e("span", null, `${event.eventType || "event"} • ${summary}`),
                          e("span", null, formatWhen(event.timestampMs))
                        ),
                        e("div", { className: "mcp-admin-event-body" },
                          `${event.tool || "(no tool)"}${event.actor ? ` • actor: ${event.actor}` : ""}`,
                          event.target ? e("div", { style: { marginTop: "2px", color: "#4f6a7d" } }, `target: ${event.target}`) : null,
                          event.detail ? e("div", { style: { marginTop: "2px", color: "#4f6a7d" } }, event.detail) : null
                        )
                      );
                    })
                  )
              )
            ),

            e("div", { className: "mcp-admin-card", style: { marginTop: "12px" } },
              e("div", { className: "mcp-admin-card-title" }, "Configuration"),
              e("div", { className: "mcp-admin-note" }, "List fields accept comma-separated values or one value per line. Glob wildcards are supported: * and ?."),

              e("div", { className: "mcp-admin-form" },
                e("div", { className: "mcp-admin-field" },
                  helpLabel("Mount Alias", "Used by MCP routes at /data/<mountAlias>. Use letters, numbers, dashes, and underscores."),
                  e("input", {
                    type: "text",
                    value: config.mountAlias,
                    onChange: ev => setText("mountAlias", ev.target.value)
                  }),
                  fieldHint("Changing this value changes your MCP endpoint path.")
                ),

                e("div", { className: "mcp-admin-field" },
                  helpLabel("Allowed Origins", "Checks the HTTP Origin header. Useful for browser-based MCP clients. Leave empty to allow all origins."),
                  e("textarea", {
                    value: listToText(config.allowedOrigins),
                    onChange: ev => setList("allowedOrigins", ev.target.value)
                  }),
                  fieldHint("Examples: https://app.example.com, https://*.example.com")
                ),

                e("div", { className: "mcp-admin-field" },
                  helpLabel("Allowed Hosts", "Checks Host header or server name. Use to restrict which hostnames can access MCP routes."),
                  e("textarea", {
                    value: listToText(config.allowedHosts),
                    onChange: ev => setList("allowedHosts", ev.target.value)
                  }),
                  fieldHint("Examples: localhost:8088, gateway.internal, *.corp.example.com")
                ),

                e("div", { className: "mcp-admin-field" },
                  helpLabel("Max Concurrent Sessions", "Maximum active MCP sessions across all clients. Must be > 0."),
                  e("input", {
                    type: "number",
                    value: config.maxConcurrentSessions,
                    onChange: ev => setNum("maxConcurrentSessions", ev.target.value)
                  }),
                  fieldHint("Default: 200")
                ),

                e("div", { className: "mcp-admin-field" },
                  helpLabel("Max Requests / Minute / Token", "Per-token request limit across all tool and protocol calls."),
                  e("input", {
                    type: "number",
                    value: config.maxRequestsPerMinutePerToken,
                    onChange: ev => setNum("maxRequestsPerMinutePerToken", ev.target.value)
                  }),
                  fieldHint("Default: 300")
                ),

                e("div", { className: "mcp-admin-field" },
                  helpLabel("Max Write Ops / Minute / Token", "Per-token limit for mutating operations."),
                  e("input", {
                    type: "number",
                    value: config.maxWriteOpsPerMinutePerToken,
                    onChange: ev => setNum("maxWriteOpsPerMinutePerToken", ev.target.value)
                  }),
                  fieldHint("Default: 60")
                ),

                e("div", { className: "mcp-admin-field" },
                  helpLabel("Max Batch Write Size", "Largest write batch accepted by write tools."),
                  e("input", {
                    type: "number",
                    value: config.maxBatchWriteSize,
                    onChange: ev => setNum("maxBatchWriteSize", ev.target.value)
                  }),
                  fieldHint("Default: 50")
                ),

                e("div", { className: "mcp-admin-field" },
                  helpLabel("Allowed Tag Read Patterns", "Tag paths that read tools may access."),
                  e("textarea", {
                    value: listToText(config.allowedTagReadPatterns),
                    onChange: ev => setList("allowedTagReadPatterns", ev.target.value)
                  }),
                  fieldHint("Examples: *, [default]MCP/*")
                ),

                e("div", { className: "mcp-admin-field" },
                  helpLabel("Allowed Tag Write Patterns", "Tag paths that mutating tag tools may update."),
                  e("textarea", {
                    value: listToText(config.allowedTagWritePatterns),
                    onChange: ev => setList("allowedTagWritePatterns", ev.target.value)
                  }),
                  fieldHint("Example: [default]MCP/*")
                ),

                e("div", { className: "mcp-admin-field" },
                  helpLabel("Allowed Alarm Ack Sources", "Alarm source patterns allowed for acknowledge operations."),
                  e("textarea", {
                    value: listToText(config.allowedAlarmAckSources),
                    onChange: ev => setList("allowedAlarmAckSources", ev.target.value)
                  }),
                  fieldHint("Use * to allow all alarm sources.")
                ),

                e("div", { className: "mcp-admin-field" },
                  helpLabel("Allowed Named Query Execute Patterns", "Allowed execute targets in the form project/path."),
                  e("textarea", {
                    value: listToText(config.allowedNamedQueryExecutePatterns),
                    onChange: ev => setList("allowedNamedQueryExecutePatterns", ev.target.value)
                  }),
                  fieldHint("Examples: samplequickstart/*, */Reports/*")
                ),

                e("div", { className: "mcp-admin-field" },
                  helpLabel("Historian Default Provider", "Used when historian.query omits provider."),
                  e("input", {
                    type: "text",
                    value: config.historianDefaultProvider,
                    onChange: ev => setText("historianDefaultProvider", ev.target.value)
                  }),
                  fieldHint("Leave empty to use Ignition default provider behavior.")
                ),

                e("div", { className: "mcp-admin-field" },
                  helpLabel("Historian Max Rows", "Hard cap for historian query row return count."),
                  e("input", {
                    type: "number",
                    value: config.historianMaxRows,
                    onChange: ev => setNum("historianMaxRows", ev.target.value)
                  }),
                  fieldHint("Default: 5000")
                ),

                e("div", { className: "mcp-admin-field" },
                  helpLabel("Named Query Max Rows", "Hard cap for returned rows when named query results are datasets."),
                  e("input", {
                    type: "number",
                    value: config.namedQueryMaxRows,
                    onChange: ev => setNum("namedQueryMaxRows", ev.target.value)
                  }),
                  fieldHint("Default: 1000")
                )
              ),

              e("div", { className: "mcp-admin-checks" },
                e("label", { className: "mcp-admin-check", title: "Enable or disable all MCP transport endpoints." },
                  e("input", {
                    type: "checkbox",
                    checked: !!config.enabled,
                    onChange: ev => setBool("enabled", ev.target.checked)
                  }),
                  "MCP Enabled"
                ),
                e("label", { className: "mcp-admin-check", title: "Enable streamable HTTP transport on /mcp." },
                  e("input", {
                    type: "checkbox",
                    checked: !!config.streamableEnabled,
                    onChange: ev => setBool("streamableEnabled", ev.target.checked)
                  }),
                  "Streamable Transport Enabled"
                ),
                e("label", { className: "mcp-admin-check", title: "Enable legacy SSE fallback transport on /sse + /message." },
                  e("input", {
                    type: "checkbox",
                    checked: !!config.sseFallbackEnabled,
                    onChange: ev => setBool("sseFallbackEnabled", ev.target.checked)
                  }),
                  "SSE Fallback Enabled"
                ),
                e("label", { className: "mcp-admin-check", title: "Mutating tools run as dry-run unless commit=true when enabled." },
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
                dirty ? e("div", { className: "mcp-admin-note" }, "Unsaved changes") : null
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

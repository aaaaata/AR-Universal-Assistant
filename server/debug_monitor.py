from __future__ import annotations


DEBUG_MONITOR_HTML = r"""
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>AR Mahjong Cloud Monitor</title>
  <style>
    :root { color-scheme: light; font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    body { margin: 0; background: #f6f7fb; color: #182033; }
    header { padding: 18px 24px; background: #172033; color: white; display: flex; gap: 16px; align-items: center; justify-content: space-between; }
    h1 { font-size: 20px; margin: 0; }
    main { display: grid; grid-template-columns: 360px 1fr; gap: 16px; padding: 16px; }
    .card { background: white; border: 1px solid #e2e6ef; border-radius: 12px; box-shadow: 0 1px 3px rgba(16, 24, 40, 0.06); overflow: hidden; }
    .card h2 { margin: 0; padding: 14px 16px; font-size: 15px; border-bottom: 1px solid #edf0f5; }
    .toolbar { display: flex; gap: 8px; align-items: center; }
    input { border: 1px solid #cad1df; border-radius: 8px; padding: 8px 10px; min-width: 220px; }
    button { border: 0; border-radius: 8px; padding: 8px 12px; background: #2563eb; color: white; cursor: pointer; }
    button.secondary { background: #eef2ff; color: #1d4ed8; }
    button:disabled { opacity: 0.55; cursor: not-allowed; }
    .sessions { max-height: calc(100vh - 128px); overflow: auto; }
    .session-item { padding: 12px 14px; border-bottom: 1px solid #edf0f5; cursor: pointer; }
    .session-item:hover, .session-item.active { background: #eef5ff; }
    .muted { color: #667085; font-size: 12px; }
    .pill { display: inline-block; padding: 2px 8px; border-radius: 999px; font-size: 12px; background: #eef2ff; color: #3443a8; }
    .pill.complete { background: #ecfdf3; color: #027a48; }
    .pill.diagnostic { background: #fff7ed; color: #c2410c; }
    .pill.failed { background: #fef3f2; color: #b42318; }
    .summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; padding: 14px 16px; }
    .metric { border: 1px solid #edf0f5; border-radius: 10px; padding: 10px; }
    .metric .value { font-size: 19px; font-weight: 700; margin-top: 4px; }
    table { border-collapse: collapse; width: 100%; font-size: 13px; }
    th, td { padding: 9px 10px; border-bottom: 1px solid #edf0f5; text-align: left; vertical-align: top; }
    th { background: #fafbff; color: #475467; font-weight: 600; }
    tr.clickable { cursor: pointer; }
    tr.clickable:hover { background: #f8fbff; }
    pre { margin: 0; padding: 14px 16px; background: #101828; color: #e4e7ec; overflow: auto; max-height: 42vh; font-size: 12px; }
    .viewer { padding: 14px 16px; }
    .image-wrap { position: relative; display: inline-block; max-width: 100%; border: 1px solid #d0d5dd; border-radius: 10px; overflow: hidden; background: #111827; }
    .image-wrap img { display: block; width: 100%; max-height: 56vh; object-fit: contain; }
    .image-wrap svg { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }
    .region-box { fill: none; stroke: #22c55e; stroke-width: 3; vector-effect: non-scaling-stroke; }
    .roi-box { fill: none; stroke: #38bdf8; stroke-width: 2; stroke-dasharray: 6 4; vector-effect: non-scaling-stroke; }
    .tile-box { fill: none; stroke: #f97316; stroke-width: 2; vector-effect: non-scaling-stroke; }
    .box-label { fill: #111827; paint-order: stroke; stroke: white; stroke-width: 3; font-size: 18px; font-weight: 700; }
    .legend { display: flex; gap: 12px; padding: 0 0 10px; font-size: 12px; color: #475467; }
    .legend span::before { content: ''; display: inline-block; width: 10px; height: 10px; margin-right: 5px; border-radius: 2px; }
    .legend .region::before { background: #22c55e; }
    .legend .roi::before { background: #38bdf8; }
    .legend .tile::before { background: #f97316; }
    .empty, .error { padding: 16px; color: #667085; }
    .error { color: #b42318; }
    @media (max-width: 900px) { main { grid-template-columns: 1fr; } .summary { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
  </style>
</head>
<body>
  <header>
    <div>
      <h1>AR Mahjong Cloud Monitor</h1>
      <div class="muted">Local debug view for cloud sessions and analyses</div>
    </div>
    <div class="toolbar">
      <input id="apiKey" type="password" placeholder="API key, e.g. local-token" />
      <button id="saveKey" class="secondary">Save</button>
      <button id="refresh">Refresh</button>
    </div>
  </header>
  <main>
    <section class="card">
      <h2>Sessions</h2>
      <div id="sessions" class="sessions"><div class="empty">Loading...</div></div>
    </section>
    <section class="card">
      <h2 id="detailTitle">Session Detail</h2>
      <div id="summary" class="summary"></div>
      <div id="analyses"></div>
      <h2>YOLO Visualization</h2>
      <div id="visualization" class="viewer"><div class="empty">Select an analysis row.</div></div>
      <h2>Evidence</h2>
      <div id="evidence" class="viewer"><div class="empty">Select an analysis row.</div></div>
      <h2>Selected Analysis JSON</h2>
      <pre id="json">Select an analysis row.</pre>
    </section>
  </main>
  <script>
    const qs = new URLSearchParams(location.search);
    const apiKeyInput = document.getElementById("apiKey");
    const sessionsEl = document.getElementById("sessions");
    const detailTitle = document.getElementById("detailTitle");
    const summaryEl = document.getElementById("summary");
    const analysesEl = document.getElementById("analyses");
    const visualizationEl = document.getElementById("visualization");
    const evidenceEl = document.getElementById("evidence");
    const jsonEl = document.getElementById("json");
    let currentSessionId = null;
    let currentSessions = [];
    let currentAnalysis = null;

    apiKeyInput.value = qs.get("api_key") || localStorage.getItem("cloudMonitorApiKey") || "";

    function escapeHtml(value) {
      return String(value ?? "").replace(/[&<>"']/g, ch => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[ch]));
    }

    function statusPill(value) {
      const text = value || "unknown";
      const cls = text === "complete" ? "complete" : (text === "diagnostic" ? "diagnostic" : (text === "failed" ? "failed" : ""));
      return `<span class="pill ${cls}">${escapeHtml(text)}</span>`;
    }

    function svgRect(box, cls, label) {
      if (!box) return "";
      const x = Number(box.x1 || 0);
      const y = Number(box.y1 || 0);
      const w = Math.max(0, Number(box.x2 || 0) - x);
      const h = Math.max(0, Number(box.y2 || 0) - y);
      const labelY = Math.max(18, y - 5);
      return `<rect class="${cls}" x="${x}" y="${y}" width="${w}" height="${h}"></rect><text class="box-label" x="${x + 4}" y="${labelY}">${escapeHtml(label)}</text>`;
    }

    function renderVisualization(analysis) {
      const result = analysis.result || {};
      const image = result.image || {};
      const width = Number(image.width || analysis.image_width || 0);
      const height = Number(image.height || analysis.image_height || 0);
      if (!analysis.debug_image_stored || !analysis.debug_image_url || !width || !height) {
        visualizationEl.innerHTML = '<div class="empty">No stored image for this analysis. Run a new analysis with the current cloud service, then refresh.</div>';
        return;
      }
      const apiKey = encodeURIComponent(apiKeyInput.value.trim());
      const src = `${analysis.debug_image_url}${apiKey ? `?api_key=${apiKey}` : ""}`;
      const rois = (result.regions || []).map(region => svgRect(region.roi_bbox, "roi-box", "")).join("");
      const regions = (result.regions || []).map(region => svgRect(region.bbox, "region-box", `${region.region_type || "region"} ${region.confidence ?? ""}`)).join("");
      const tiles = (result.tile_detections || []).map(tile => svgRect(tile.bbox_in_original_image, "tile-box", `${tile.tile || "tile"} ${tile.tile_confidence ?? ""}`)).join("");
      visualizationEl.innerHTML = `<div class="legend"><span class="region">region</span><span class="roi">roi</span><span class="tile">tile</span></div><div class="image-wrap"><img src="${src}" alt="analysis image" onerror="this.parentElement.parentElement.innerHTML='<div class=&quot;empty&quot;>Debug image unavailable or API key invalid.</div>'"/><svg viewBox="0 0 ${width} ${height}" preserveAspectRatio="none">${rois}${regions}${tiles}</svg></div>`;
    }

    function evidenceFor(analysis) {
      if (analysis && analysis.evidence && typeof analysis.evidence === "object") return analysis.evidence;
      if (!analysis || !analysis.evidence_id) return null;
      return {
        evidence_id: analysis.evidence_id,
        status: analysis.evidence_status,
        source: analysis.evidence_source,
        hold_until: analysis.evidence_hold_until,
        exported_at: analysis.evidence_exported_at,
      };
    }

    function evidenceLabel(analysis) {
      const evidence = evidenceFor(analysis);
      if (!evidence) return "-";
      const source = evidence.source === "manual" ? "manual" : "auto";
      return source + (evidence.exported_at ? " exported" : " frozen");
    }

    function renderEvidence(analysis) {
      if (!analysis || !analysis.analysis_id) {
        evidenceEl.innerHTML = '<div class="empty">Select an analysis row.</div>';
        return;
      }
      const evidence = evidenceFor(analysis);
      if (!evidence) {
        evidenceEl.innerHTML =
          '<div class="metric"><div class="muted">当前分析尚未冻结为证据。</div>' +
          '<div class="toolbar" style="margin-top:8px"><button id="freezeEvidence">冻结证据</button></div></div>';
        document.getElementById("freezeEvidence").addEventListener("click", event => freezeEvidence(analysis.analysis_id, event.currentTarget));
        return;
      }
      const evidenceId = evidence.evidence_id;
      const source = evidence.source === "manual" ? "人工" : "自动";
      const status = evidence.status || "frozen";
      evidenceEl.innerHTML =
        '<div class="metric"><div><strong>证据已冻结</strong></div>' +
        '<div class="muted">来源=' + escapeHtml(source) +
        ' 状态=' + escapeHtml(status) +
        ' 截止=' + escapeHtml(evidence.hold_until || "-") +
        ' 导出=' + escapeHtml(evidence.exported_at || "未导出") +
        '</div><div class="toolbar" style="margin-top:8px"><button id="downloadEvidence">下载证据包</button></div></div>';
      document.getElementById("downloadEvidence").addEventListener("click", event => downloadEvidence(evidenceId, event.currentTarget));
    }

    async function freezeEvidence(analysisId, button) {
      button.disabled = true;
      try {
        const payload = await fetchJson("/debug/analyses/" + encodeURIComponent(analysisId) + "/evidence", { method: "POST" });
        currentAnalysis = Object.assign({}, currentAnalysis || {}, { evidence: payload.evidence });
        renderSelectedAnalysis(currentAnalysis);
      } catch (error) {
        evidenceEl.innerHTML += '<div class="error">' + escapeHtml(error.message) + '</div>';
        button.disabled = false;
      }
    }

    async function downloadEvidence(evidenceId, button) {
      const apiKey = apiKeyInput.value.trim();
      button.disabled = true;
      try {
        const response = await fetch("/debug/evidence/" + encodeURIComponent(evidenceId) + "/download", {
          headers: apiKey ? { "X-API-Key": apiKey } : {},
        });
        if (!response.ok) {
          const text = await response.text();
          let payload;
          try { payload = text ? JSON.parse(text) : {}; } catch { payload = {}; }
          const message = payload.error ? payload.error.code + ": " + payload.error.message : text;
          throw new Error(message || ("HTTP " + response.status));
        }
        const blob = await response.blob();
        const link = document.createElement("a");
        link.href = URL.createObjectURL(blob);
        link.download = "ar-mahjong-evidence-" + evidenceId + ".zip";
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(link.href);
        if (currentAnalysis && currentAnalysis.analysis_id) {
          const detail = await fetchJson("/debug/analyses/" + encodeURIComponent(currentAnalysis.analysis_id));
          renderSelectedAnalysis(detail.analysis);
        }
      } catch (error) {
        evidenceEl.innerHTML += '<div class="error">' + escapeHtml(error.message) + '</div>';
        button.disabled = false;
      }
    }

    async function downloadRegressionPack(sessionId, button) {
      const apiKey = apiKeyInput.value.trim();
      button.disabled = true;
      try {
        const response = await fetch("/debug/sessions/" + encodeURIComponent(sessionId) + "/regression-pack/download", {
          headers: apiKey ? { "X-API-Key": apiKey } : {},
        });
        if (!response.ok) {
          const text = await response.text();
          let payload;
          try { payload = text ? JSON.parse(text) : {}; } catch { payload = {}; }
          const message = payload.error ? payload.error.code + ": " + payload.error.message : text;
          throw new Error(message || ("HTTP " + response.status));
        }
        const blob = await response.blob();
        const link = document.createElement("a");
        link.href = URL.createObjectURL(blob);
        link.download = "ar-mahjong-session-regression-" + sessionId + ".zip";
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(link.href);
        await loadSession(sessionId);
      } catch (error) {
        summaryEl.innerHTML += '<div class="error">' + escapeHtml(error.message) + '</div>';
        button.disabled = false;
      }
    }


    function renderSelectedAnalysis(analysis) {
      currentAnalysis = analysis;
      jsonEl.textContent = JSON.stringify(analysis, null, 2);
      renderVisualization(analysis);
      renderEvidence(analysis);
    }

    async function fetchJson(path, options = {}) {
      const apiKey = apiKeyInput.value.trim();
      const response = await fetch(path, {
        ...options,
        headers: {
          ...(options.headers || {}),
          ...(apiKey ? { "X-API-Key": apiKey } : {}),
        },
      });
      const text = await response.text();
      let payload;
      try { payload = text ? JSON.parse(text) : {}; } catch { payload = { raw_text: text }; }
      if (!response.ok) {
        const error = payload.error ? `${payload.error.code}: ${payload.error.message}` : text;
        throw new Error(error || `HTTP ${response.status}`);
      }
      return payload;
    }

    function renderSessions(payload) {
      currentSessions = payload.sessions || [];
      if (!currentSessions.length) {
        sessionsEl.innerHTML = '<div class="empty">No sessions yet.</div>';
        return;
      }
      sessionsEl.innerHTML = currentSessions.map(session => {
        const latest = session.latest_analysis || {};
        const status = latest.result_status || latest.status || "no-analysis";
        const active = session.session_id === currentSessionId ? "active" : "";
        return `<div class="session-item ${active}" data-session-id="${escapeHtml(session.session_id)}">
          <div><strong>${escapeHtml(session.session_id.slice(0, 8))}</strong> ${statusPill(status)}</div>
          <div class="muted">rule=${escapeHtml(session.rule_profile_id)} seq=${escapeHtml(session.last_sequence_id)} analyses=${escapeHtml(session.analysis_count)}</div>
          <div class="muted">latest=${escapeHtml(session.last_activity_at || session.created_at || "-")}</div>
          <div class="muted">diag=${escapeHtml(latest.diagnostic_code || latest.error_code || "-")} suggestion=${escapeHtml(latest.suggestion_tile || "-")} total=${escapeHtml(latest.total_ms || "-")}ms</div>
        </div>`;
      }).join("");
      document.querySelectorAll(".session-item").forEach(item => {
        item.addEventListener("click", () => loadSession(item.dataset.sessionId));
      });
      if (!currentSessionId && currentSessions[0]) loadSession(currentSessions[0].session_id);
    }

    function renderSession(payload) {
      const session = payload.session;
      const analyses = payload.analyses || [];
      detailTitle.textContent = `Session ${session.session_id}`;
      summaryEl.innerHTML = [
        ["Status", session.status],
        ["Rule", session.rule_profile_id],
        ["Last Seq", session.last_sequence_id],
        ["Analyses", analyses.length],
      ].map(([label, value]) => `<div class="metric"><div class="muted">${label}</div><div class="value">${escapeHtml(value)}</div></div>`).join("");

      const recording = payload.recording;
      if (recording) {
        const canDownload = recording.status === "completed";
        summaryEl.innerHTML += '<div class="metric"><div class="muted">Regression Recording</div><div class="value">' +
          escapeHtml(recording.status) + ' · frames=' + escapeHtml(recording.frame_count) +
          ' · bytes=' + escapeHtml(recording.total_bytes) +
          (recording.incomplete_reason ? ' · reason=' + escapeHtml(recording.incomplete_reason) : '') +
          ' · expires=' + escapeHtml(recording.expires_at || "-") +
          ' · exported=' + escapeHtml(recording.exported_at || "not yet") +
          '</div>' + (canDownload ? '<div class="toolbar" style="margin-top:8px"><button id="downloadRegressionPack">下载回归包</button></div>' : '') + '</div>';
        if (canDownload) {
          document.getElementById("downloadRegressionPack").addEventListener(
            "click",
            event => downloadRegressionPack(session.session_id, event.currentTarget),
          );
        }
      }

      if (!analyses.length) {
        analysesEl.innerHTML = '<div class="empty">No analyses in this session.</div>';
        jsonEl.textContent = "No analysis result.";
        return;
      }
      analysesEl.innerHTML = `<table><thead><tr>
        <th>Seq</th><th>DB</th><th>Result</th><th>Diag/Error</th><th>Tiles</th><th>Suggestion</th><th>Conf</th><th>Latency</th><th>Image</th>
      </tr></thead><tbody>${analyses.map((analysis, idx) => {
        const result = analysis.result || {};
        const tileCount = (result.tile_detections || []).length;
        const diag = analysis.diagnostic_code || analysis.error_code || "-";
        const conf = analysis.suggestion_confidence == null ? "-" : analysis.suggestion_confidence;
        const latency = analysis.total_ms == null ? "-" : `${analysis.total_ms} ms`;
        return `<tr class="clickable" data-idx="${idx}">
          <td>${escapeHtml(analysis.sequence_id)}</td>
          <td>${statusPill(analysis.status)}</td>
          <td>${statusPill(analysis.result_status || "-")}</td>
          <td>${escapeHtml(diag)}</td>
          <td>${escapeHtml(tileCount)}</td>
          <td>${escapeHtml(analysis.suggestion_tile || "-")}</td>
          <td>${escapeHtml(conf)}</td>
          <td>${escapeHtml(latency)}</td>
          <td>${escapeHtml(analysis.image_format || "-")} ${escapeHtml(analysis.image_width || "?")}x${escapeHtml(analysis.image_height || "?")} debug=${analysis.debug_image_stored ? "yes" : "no"}</td>
        </tr>`;
      }).join("")}</tbody></table>`;
      document.querySelectorAll("tr.clickable").forEach(row => {
        row.addEventListener("click", () => renderSelectedAnalysis(analyses[Number(row.dataset.idx)]));
      });
      renderSelectedAnalysis(analyses[analyses.length - 1]);
    }

    async function loadSessions() {
      sessionsEl.innerHTML = '<div class="empty">Loading...</div>';
      try {
        renderSessions(await fetchJson('/debug/sessions'));
      } catch (error) {
        sessionsEl.innerHTML = `<div class="error">${escapeHtml(error.message)}</div>`;
      }
    }

    async function loadSession(sessionId) {
      currentSessionId = sessionId;
      try {
        renderSession(await fetchJson(`/debug/sessions/${encodeURIComponent(sessionId)}`));
        renderSessions({ sessions: currentSessions });
      } catch (error) {
        analysesEl.innerHTML = `<div class="error">${escapeHtml(error.message)}</div>`;
      }
    }

    document.getElementById("saveKey").addEventListener("click", () => {
      localStorage.setItem("cloudMonitorApiKey", apiKeyInput.value.trim());
      loadSessions();
    });
    document.getElementById("refresh").addEventListener("click", loadSessions);
    setInterval(() => { if (!document.hidden) loadSessions(); }, 5000);
    loadSessions();
  </script>
</body>
</html>
"""
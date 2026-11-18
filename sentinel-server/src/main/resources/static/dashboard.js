"use strict";

const node = id => document.getElementById(id);
const date = value => value ? new Date(value).toISOString().replace("T", " ").replace(/\.\d+Z$/, " UTC").replace(/Z$/, " UTC") : "No captures";
const number = value => new Intl.NumberFormat().format(value);
const limit = 10;
let page = 0;
let filters = new URLSearchParams();
let selection;
let chosenTimeline;
let loadVersion = 0;
let detailVersion = 0;
let timelineVersion = 0;

function element(tag, text, css) {
  const result = document.createElement(tag);
  result.textContent = text;
  if (css) result.className = css;
  return result;
}

async function api(path) {
  const response = await fetch("/api/v1" + path, {headers: {"Accept": "application/json"}});
  if (!response.ok) throw new Error("Request failed");
  return response.json();
}

function reportButton(report, text) {
  const button = element("button", text, "cell-link");
  button.type = "button";
  button.addEventListener("click", () => showReport(report.reportId, true));
  return button;
}

async function refresh() {
  const version = ++loadVersion;
  node("status").textContent = "Loading saved history…";
  node("refresh").disabled = true;
  try {
    const query = new URLSearchParams(filters);
    query.set("page", page);
    query.set("limit", limit);
    const [summary, incidents, reports] = await Promise.all([
      api("/summary"), api("/incidents?" + query), api("/reports?limit=10")
    ]);
    if (version !== loadVersion) return;
    if (page > 0 && incidents.items.length === 0) {
      page = Math.max(0, Math.ceil(incidents.total / limit) - 1);
      await refresh();
      return;
    }
    showSummary(summary);
    showIncidents(incidents);
    showReports(reports);
    const reportId = selection ?? incidents.items[0]?.reportId ?? reports.items[0]?.reportId;
    if (reportId) await showReport(reportId, false);
    await loadTimeline();
  } catch {
    if (version === loadVersion) node("status").textContent = "Could not load history. Check the local server and refresh.";
  } finally {
    if (version === loadVersion) node("refresh").disabled = false;
  }
}

function showSummary(summary) {
  node("report-total").textContent = number(summary.totalReports);
  node("incident-total").textContent = number(summary.totalIncidents);
  node("recent-total").textContent = number(summary.recentIncidents);
  node("latest").textContent = date(summary.latestCapture);
  node("severity-total").replaceChildren(
    element("div", "Critical " + number(summary.bySeverity.CRITICAL ?? 0), "critical"),
    element("div", "Warning " + number(summary.bySeverity.WARNING ?? 0), "warning")
  );
  const diagnostics = summary.diagnostics;
  const skipped = diagnostics.corrupt + diagnostics.unsupported + diagnostics.oversized;
  const messages = [summary.totalReports ? "Showing reports saved on this device." : "No saved reports yet."];
  if (skipped) messages.push(number(skipped) + " unreadable or unsupported files skipped.");
  if (diagnostics.scanTruncated) messages.push("History scan reached its limit. Results are partial.");
  node("status").textContent = messages.join(" ");
}

function showIncidents(result) {
  const rows = result.items.map(incident => {
    const row = document.createElement("tr");
    const code = document.createElement("td");
    code.append(reportButton(incident, incident.code));
    row.append(code, element("td", incident.severity, incident.severity.toLowerCase()),
      element("td", date(incident.timestamp)), element("td", incident.scenario),
      element("td", incident.observationSummary));
    return row;
  });
  node("incident-rows").replaceChildren(...rows);
  node("incident-state").textContent = rows.length ? "" : "No incidents match these filters.";
  node("previous").disabled = page === 0;
  node("next").disabled = (page + 1) * limit >= result.total;
  node("page-status").textContent = result.total ? "Page " + (page + 1) + " of " + Math.ceil(result.total / limit) : "";
}

function showReports(result) {
  node("reports").replaceChildren(...result.items.map(report => {
    const item = document.createElement("li");
    item.append(reportButton(report, report.scenario + " · " + report.type + " · " + date(report.capturedAt)));
    return item;
  }));
  node("reports-state").textContent = result.items.length ? "" : "No reports saved.";
}

async function showReport(id, focus) {
  selection = id;
  const version = ++detailVersion;
  const container = node("detail");
  if (focus) node("detail-title").focus();
  container.replaceChildren(element("p", "Loading report…"));
  try {
    const report = await api("/reports/" + encodeURIComponent(id));
    if (version !== detailVersion) return;
    const body = [element("p", report.scenario + " · " + report.type + " · " + date(report.capturedAt)),
      element("h3", "Measured evidence", "facts"), observations(report, false),
      element("h3", "JFR event counts", "facts"), observations(report, true),
      element("h3", "Deterministic findings", "rules")];
    if (report.incidents.length === 0) body.push(element("p", "No rule incident in this report."));
    for (const finding of report.incidents) {
      body.push(element("p", finding.severity + " · " + finding.code), element("p", finding.rule));
    }
    body.push(element("h3", "AI advisory", "advisory"));
    const advisory = report.advisory;
    if (advisory?.status === "VALID") {
      body.push(element("p", "Validated references · qualitative confidence: " + advisory.confidence),
        element("p", "Classification: " + (advisory.incidentCode ?? "Observations only")),
        element("p", "Model narrative is kept out of shared history. References do not establish causality."));
      const references = document.createElement("ul");
      references.append(...advisory.evidenceIds.map(id => element("li", id)));
      body.push(references);
    } else {
      body.push(element("p", advisory ? "Unavailable (" + advisory.status + "). Deterministic evidence retained." : "Not collected."));
    }
    container.replaceChildren(...body);
  } catch {
    if (version === detailVersion) container.replaceChildren(element("p", "Report unavailable. It may have expired from history.", "error"));
  }
}

function observations(report, jfr) {
  const relevant = id => id.startsWith("jfr.") === jfr;
  const entries = document.createElement("dl");
  for (const item of report.evidence.filter(item => relevant(item.id))) {
    entries.append(element("dt", item.id), element("dd", number(item.value) + " " + item.unit));
  }
  for (const id of report.notCollected.filter(relevant)) {
    entries.append(element("dt", id), element("dd", "Not collected"));
  }
  return entries.childElementCount ? entries : element("p", "Not collected.");
}

async function loadTimeline() {
  const version = ++timelineVersion;
  node("timeline-state").textContent = "Loading timeline…";
  try {
    const metric = encodeURIComponent(node("metric").value);
    const series = await api("/metrics/timeline?limit=100&metric=" + metric);
    if (version !== timelineVersion) return;
    chosenTimeline = series;
    drawTimeline();
  } catch {
    if (version !== timelineVersion) return;
    chosenTimeline = undefined;
    node("chart").hidden = true;
    node("timeline-range").replaceChildren();
    node("timeline-state").textContent = "Could not load the timeline.";
  }
}

function drawTimeline() {
  if (!chosenTimeline) return;
  const {points, unit, metric} = chosenTimeline;
  const observed = points.filter(point => point.value !== null);
  const canvas = node("chart");
  const state = node("timeline-state");
  const range = node("timeline-range");
  range.replaceChildren();
  canvas.hidden = observed.length === 0;
  if (!observed.length) {
    state.textContent = "No observations for this metric.";
    return;
  }
  const values = observed.map(point => point.value);
  const high = Math.max(...values);
  const low = Math.min(...values);
  const missing = points.length - observed.length;
  state.textContent = observed.length + " observed · " + missing + " not collected · "
      + number(low) + "–" + number(high) + " " + unit;
  canvas.setAttribute("aria-label", metric + " chart. " + state.textContent);
  const width = canvas.clientWidth, height = canvas.clientHeight;
  if (width === 0 || height === 0) return;
  const scale = window.devicePixelRatio || 1;
  canvas.width = Math.round(width * scale);
  canvas.height = Math.round(height * scale);
  const ctx = canvas.getContext("2d");
  ctx.scale(scale, scale);
  const left = Math.min(96, width / 3), right = width - 16, top = 14, bottom = height - 18;
  const start = Date.parse(points[0].capturedAt);
  const end = Date.parse(points[points.length - 1].capturedAt);
  ctx.font = "12px system-ui";
  ctx.fillStyle = "#adbdd2";
  ctx.strokeStyle = "#334258";
  const ticks = high === low ? [high] : [high, (high + low) / 2, low];
  ticks.forEach((value, index) => {
    const y = ticks.length === 1 ? (top + bottom) / 2 : top + index * (bottom - top) / 2;
    ctx.fillText(number(value), 2, y + 4, left - 6);
    ctx.beginPath();
    ctx.moveTo(left, y);
    ctx.lineTo(right, y);
    ctx.stroke();
  });
  ctx.fillStyle = "#7ce0cb";
  for (const point of observed) {
    const x = end === start ? (left + right) / 2 : left + (Date.parse(point.capturedAt) - start) / (end - start) * (right - left);
    const y = low === high ? (top + bottom) / 2 : bottom - (point.value - low) / (high - low) * (bottom - top);
    ctx.beginPath();
    ctx.arc(x, y, 4.5, 0, 2 * Math.PI);
    ctx.fill();
  }
  range.append(element("span", date(points[0].capturedAt)), element("span", date(points[points.length - 1].capturedAt)));
}

node("refresh").addEventListener("click", refresh);
node("filters").addEventListener("submit", event => {
  event.preventDefault();
  filters = new URLSearchParams();
  for (const name of ["severity", "code"]) {
    if (node(name).value) filters.set(name, node(name).value);
  }
  page = 0;
  refresh();
});
node("previous").addEventListener("click", () => { if (page > 0) { page--; refresh(); } });
node("next").addEventListener("click", () => { page++; refresh(); });
node("metric").addEventListener("change", loadTimeline);
new ResizeObserver(drawTimeline).observe(node("chart").parentElement);
refresh();

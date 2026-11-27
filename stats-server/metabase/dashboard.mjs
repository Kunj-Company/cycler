#!/usr/bin/env node
// Creates (or rebuilds) the "Cycler stats" dashboard in Metabase from queries.sql:
// one saved question per "-- ===== Q<n>." block, with a sensible chart for each.
//
//   MB_URL=http://localhost:3000 MB_EMAIL=... MB_PASSWORD=... node dashboard.mjs
//
// Run setup.sh first (it adds the "Cycler stats" database). Running this again replaces the
// questions and dashboard it created before, so edits to queries.sql are picked up.
// Needs Node 18+ (built-in fetch). Talks only to your own Metabase.
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const MB = (process.env.MB_URL || "http://localhost:3000").replace(/\/$/, "");
const { MB_EMAIL, MB_PASSWORD } = process.env;
const DB_LABEL = "Cycler stats";
const NAME = "Cycler stats";
if (!MB_EMAIL || !MB_PASSWORD) { console.error("set MB_EMAIL and MB_PASSWORD"); process.exit(1); }

let session = "";
async function api(method, path, body) {
  const res = await fetch(MB + "/api" + path, {
    method,
    headers: { "content-type": "application/json", ...(session ? { "X-Metabase-Session": session } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
}
const list = (j) => (Array.isArray(j) ? j : j.data);

// ---- parse queries.sql into blocks ----
const sql = readFileSync(join(dirname(fileURLToPath(import.meta.url)), "queries.sql"), "utf8");
const blocks = [];
for (const part of sql.split(/^-- ===== /m).slice(1)) {
  const m = part.match(/^Q(\d+)\.\s*(.*)$/m);
  const title = m[2].replace(/\s*\(chart:.*$/, "").trim();
  const query = part.split("\n").slice(1).filter((l) => !l.startsWith("--")).join("\n").trim().replace(/;\s*$/, "");
  blocks.push({ n: Number(m[1]), title, query });
}

// Chart per question number, with explicit columns (an empty database can't tell Metabase
// which columns are numbers, so nothing is guessed). Column names must match queries.sql.
const g = (display, dims, metrics, extra = {}) =>
  ({ display, settings: { "graph.dimensions": dims, "graph.metrics": metrics, ...extra } });
const CHART = {
  1: g("line", ["week_start"], ["active_installs"]),
  2: g("bar", ["week_start"], ["new_installs"]),
  3: { display: "table", settings: {} },
  4: { display: "table", settings: {} },
  5: g("combo", ["week_start"], ["km", "cumulative_km"]),
  6: g("bar", ["week_start"], ["rides_started", "rides_finished", "rides_discarded"]),
  7: g("line", ["week_start"], ["median_km", "p90_km"]),
  8: g("line", ["week_start", "feature"], ["pct_of_active"]),
  9: g("row", ["feature"], ["pct_of_active"]),
  10: g("area", ["week_start", "app_version"], ["active_installs"], { "stackable.stack_type": "stacked" }),
  11: { display: "map", settings: { "map.type": "pin", "map.latitude_column": "latitude", "map.longitude_column": "longitude" } },
  12: g("bar", ["week_start"], ["area_reports"]),
};

session = (await api("POST", "/session", { username: MB_EMAIL, password: MB_PASSWORD })).id;
const db = list(await api("GET", "/database")).find((d) => d.name === DB_LABEL);
if (!db) { console.error(`database "${DB_LABEL}" not found; run setup.sh first`); process.exit(1); }

// Collection to keep everything together.
let coll = (await api("GET", "/collection")).find((c) => c.name === NAME && !c.archived);
if (!coll) coll = await api("POST", "/collection", { name: NAME, color: "#509EE3" });

// Remove what an earlier run created, so a rerun is a clean rebuild.
const items = list(await api("GET", `/collection/${coll.id}/items`));
for (const it of items) {
  if (it.model === "dashboard") await api("DELETE", `/dashboard/${it.id}`).catch(() => api("PUT", `/dashboard/${it.id}`, { archived: true }));
  if (it.model === "card") await api("DELETE", `/card/${it.id}`).catch(() => api("PUT", `/card/${it.id}`, { archived: true }));
}

const cards = [];
for (const b of blocks) {
  const dataset_query = { database: db.id, type: "native", native: { query: b.query } };
  const result = await api("POST", "/dataset", dataset_query);
  if (result.error) throw new Error(`Q${b.n} failed: ${result.error}`);
  const chart = CHART[b.n] || { display: "table", settings: {} };
  const have = new Set(result.data.cols.map((c) => c.name));
  const used = [...(chart.settings["graph.dimensions"] || []), ...(chart.settings["graph.metrics"] || [])];
  const missing = used.filter((c) => !have.has(c));
  if (missing.length) throw new Error(`Q${b.n}: chart uses columns not in the query: ${missing.join(", ")}`);
  const display = chart.display;
  const card = await api("POST", "/card", {
    name: `Q${b.n}. ${b.title}`, collection_id: coll.id, display, dataset_query,
    visualization_settings: chart.settings,
  });
  cards.push({ n: b.n, id: card.id, display });
  console.log(`saved Q${b.n} (${display}): ${b.title}`);
}

// Headline numbers across the top (the figures to show supporters).
const TILES = [
  ["Total km ridden", "SELECT ROUND(COALESCE(SUM(km), 0), 0) AS km FROM reports"],
  ["Rides recorded", "SELECT COALESCE(SUM(rides_finished), 0) AS rides FROM reports"],
  ["Riders (all time)", "SELECT COUNT(DISTINCT install) AS riders FROM reports"],
  ["Active riders, latest week",
    "SELECT COUNT(DISTINCT install) AS riders FROM reports WHERE days_active > 0 AND week = (SELECT MAX(week) FROM reports)"],
];
const tiles = [];
for (const [title, query] of TILES) {
  const dataset_query = { database: db.id, type: "native", native: { query } };
  const r = await api("POST", "/dataset", dataset_query);
  if (r.error) throw new Error(`tile "${title}" failed: ${r.error}`);
  const card = await api("POST", "/card", { name: title, collection_id: coll.id, display: "scalar",
    dataset_query, visualization_settings: {} });
  tiles.push(card.id);
  console.log(`saved tile: ${title}`);
}

// Layout: 24-column grid. Tiles on top, then two charts per row; tables and the map get a full row.
const dash = await api("POST", "/dashboard", { name: NAME, collection_id: coll.id,
  description: "Anonymous Cycler usage stats (installs with stats switched on). Data refreshes after each daily pull." });
const tileCards = tiles.map((card_id, i) => ({ id: -(100 + i), card_id, row: 0, col: i * 6, size_x: 6, size_y: 3,
  parameter_mappings: [], visualization_settings: {} }));
let row = 3, col = 0;
const dashcards = cards.map((c, i) => {
  const wide = ["table", "map"].includes(c.display);
  if (wide && col) { row += 6; col = 0; }
  const size_x = wide ? 24 : 12, size_y = c.display === "map" ? 9 : 6;
  const dc = { id: -(i + 1), card_id: c.id, row, col, size_x, size_y, parameter_mappings: [], visualization_settings: {} };
  if (wide) { row += size_y; col = 0; } else if (col) { row += 6; col = 0; } else { col = 12; }
  return dc;
});
await api("PUT", `/dashboard/${dash.id}`, { dashcards: [...tileCards, ...dashcards] });
console.log(`dashboard: ${MB}/dashboard/${dash.id}`);

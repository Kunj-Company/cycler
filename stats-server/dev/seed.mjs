#!/usr/bin/env node
// Generates a LOCAL SQLite file with realistic synthetic Cycler stats, for trying out
// metabase/queries.sql and dashboards. It never talks to Cloudflare / the remote D1.
//
//   node dev/seed.mjs                       # writes dev/seed.db
//   node dev/seed.mjs --out path/to.db      # custom path (refuses to overwrite)
//   node dev/seed.mjs --out path/to.db --force
//   options: --installs 800 --weeks 26 --seed 42
//
// Needs the sqlite3 command-line tool.

import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const args = process.argv.slice(2);
const opt = (name, def) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : def;
};
const OUT = resolve(opt("out", join(ROOT, "dev", "seed.db")));
const FORCE = args.includes("--force");
const INSTALLS = Number(opt("installs", 800));
const WEEKS = Number(opt("weeks", 26));
let seed = Number(opt("seed", 42)) >>> 0;

if (existsSync(OUT) && !FORCE) {
  console.error(`${OUT} exists; pass --force to overwrite`);
  process.exit(1);
}

// ---- deterministic PRNG (mulberry32) ----
function rnd() {
  seed = (seed + 0x6d2b79f5) >>> 0;
  let t = seed;
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}
const pick = (arr) => arr[Math.floor(rnd() * arr.length)];
const chance = (p) => rnd() < p;
const randInt = (a, b) => a + Math.floor(rnd() * (b - a + 1));
const gauss = () => Math.sqrt(-2 * Math.log(1 - rnd())) * Math.cos(2 * Math.PI * rnd());
const uuid = () =>
  "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = Math.floor(rnd() * 16);
    return (c === "x" ? r : (r & 0x3) | 0x8).toString(16);
  });

// ---- ISO weeks ----
const DAY = 86400000;
function isoWeek(ms) {
  const d = new Date(ms);
  const idx = (d.getUTCDay() + 6) % 7;
  const thu = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()) + (3 - idx) * DAY;
  const y = new Date(thu).getUTCFullYear();
  const w = Math.floor((thu - Date.UTC(y, 0, 1)) / (7 * DAY)) + 1;
  return `${y}-W${String(w).padStart(2, "0")}`;
}
const now = Date.now();
// weeks[0] = oldest, weeks[WEEKS-1] = last completed week
const weeks = Array.from({ length: WEEKS }, (_, i) => isoWeek(now - (WEEKS - i) * 7 * DAY));

// ---- world ----
const CITIES = [
  { lat: 12.97, lon: 77.59, w: 30 }, // Bengaluru
  { lat: 19.07, lon: 72.87, w: 12 }, // Mumbai
  { lat: 28.61, lon: 77.2, w: 10 }, // Delhi
  { lat: 18.52, lon: 73.85, w: 10 }, // Pune
  { lat: 13.08, lon: 80.27, w: 6 }, // Chennai
  { lat: 10.0, lon: 76.3, w: 6 }, // Kochi
  { lat: 52.52, lon: 13.4, w: 5 }, // Berlin
  { lat: 51.5, lon: -0.12, w: 4 }, // London
  { lat: 52.37, lon: 4.9, w: 4 }, // Amsterdam
  { lat: -33.87, lon: 151.21, w: 2 }, // Sydney
  { lat: 64.15, lon: -21.94, w: 0.3 }, // Reykjavik (rare: should mostly be hidden)
];
const totalW = CITIES.reduce((s, c) => s + c.w, 0);
function pickCity() {
  let x = rnd() * totalW;
  for (const c of CITIES) if ((x -= c.w) < 0) return c;
  return CITIES[0];
}
const cell = (v) => Math.floor(v * 10) / 10;

const FEATURES = [
  ["export_gpx", 0.15], ["export_tcx", 0.03], ["export_fit", 0.05], ["share_video", 0.08],
  ["share_cards", 0.2], ["share_file", 0.05], ["plan_a_to_b", 0.25], ["plan_loop", 0.12],
  ["route_saved", 0.1], ["route_import_gpx", 0.04], ["search", 0.35], ["lap", 0.06],
  ["pause", 0.45], ["workout", 0.03], ["big_numbers", 0.1], ["widget_ride", 0.05],
  ["widget_stats", 0.04], ["widget_week", 0.03], ["widget_last_ride", 0.02],
  ["offline_map_download", 0.06], ["backup_export", 0.02], ["backup_import", 0.01],
  ["health_connect_connect", 0.02], ["bike_care", 0.07], ["bike_added", 0.02],
  ["segments_opened", 0.09], ["map_load_failed", 0.03], ["routing_failed", 0.02], ["ride_recovered", 0.01],
];
// Version rollout: a new release every ~5 weeks.
const VERSIONS = ["0.1.9", "0.1.10", "0.1.11", "0.1.12", "0.1.13", "0.1.14"];
const versionAt = (wi, lag) => VERSIONS[Math.max(0, Math.min(VERSIONS.length - 1, Math.floor((wi - lag) / 5)))];

// ---- simulate ----
const reports = [];
const features = [];
const areas = [];
for (let n = 0; n < INSTALLS; n++) {
  const install = uuid();
  // more installs join later (growth)
  const start = Math.min(WEEKS - 1, Math.floor(WEEKS * Math.pow(rnd(), 0.6)));
  const keen = Math.exp(gauss() * 0.7); // how much this person rides
  const churn = chance(0.35) ? 0.45 : 0.08; // tourists vs regulars
  const home = pickCity();
  const lag = randInt(0, 3); // how quickly this person updates
  const likes = Object.fromEntries(FEATURES.map(([k, p]) => [k, Math.min(1, p * Math.exp(gauss() * 0.8))]));
  let alive = true;
  for (let wi = start; wi < WEEKS && alive; wi++) {
    if (wi > start && chance(churn)) {
      alive = false;
      break;
    }
    const idle = wi > start && chance(0.25); // a quiet week: app not opened
    const week = weeks[wi];
    const app_version = versionAt(wi, lag) + (chance(0.01) ? "-debug" : "");
    if (idle) {
      // phone still reports the completed week with zeros
      reports.push({ install, week, app_version, days_active: 0, rides_started: 0, rides_finished: 0, rides_discarded: 0, km: 0 });
      continue;
    }
    const days = randInt(1, 7);
    const started = Math.max(0, Math.round(days * 0.6 * keen + gauss() * 0.6));
    const discarded = Math.min(started, chance(0.2) ? randInt(1, 2) : 0);
    const finished = started - discarded;
    const rideKms = Array.from({ length: finished }, () => Math.max(0.5, 14 * keen * Math.exp(gauss() * 0.6)));
    const km = Math.round(rideKms.reduce((a, b) => a + b, 0) * 10) / 10;
    reports.push({ install, week, app_version, days_active: days, rides_started: started, rides_finished: finished, rides_discarded: discarded, km });
    for (const [k] of FEATURES) {
      if (chance(likes[k] * (k === "pause" || k === "lap" ? Math.min(1, started / 2) : 1))) {
        features.push({ install, week, key: k, count: randInt(1, k === "pause" ? 12 : 4) });
      }
    }
    if (finished > 0) {
      // separate anonymous area report
      const report = uuid();
      const cells = new Map();
      rideKms.forEach((rk) => {
        for (const kind of ["start", "end"]) {
          const lat = cell(home.lat + gauss() * 0.08);
          const lon = cell(home.lon + gauss() * 0.08);
          const id = `${lat}|${lon}|${kind}`;
          const c = cells.get(id) ?? { lat, lon, kind, rides: 0, km: 0 };
          c.rides += 1;
          c.km = Math.round((c.km + rk) * 10) / 10;
          cells.set(id, c);
        }
      });
      for (const c of [...cells.values()].slice(0, 50)) areas.push({ report, week, app_version, ...c });
    }
  }
}

// ---- write ----
const q = (v) => (typeof v === "number" ? String(v) : `'${String(v).replace(/'/g, "''")}'`);
const insert = (table, rows) => {
  if (!rows.length) return "";
  const cols = Object.keys(rows[0]);
  let sql = "";
  for (let i = 0; i < rows.length; i += 500) {
    sql += `INSERT INTO ${table} (${cols.join(",")}) VALUES\n` +
      rows.slice(i, i + 500).map((r) => `(${cols.map((c) => q(r[c])).join(",")})`).join(",\n") + ";\n";
  }
  return sql;
};
// Same received_week for everything is fine for synthetic data.
const recv = isoWeek(now);
for (const r of reports) r.received_week = recv;

const work = mkdtempSync(join(tmpdir(), "cycler-seed-"));
try {
  const sqlFile = join(work, "seed.sql");
  writeFileSync(
    sqlFile,
    readFileSync(join(ROOT, "migrations", "0001_init.sql"), "utf8") +
      "\nBEGIN;\n" + insert("reports", reports) + insert("features", features) + insert("areas", areas) + "COMMIT;\n" +
      readFileSync(join(ROOT, "exporter", "views.sql"), "utf8"),
  );
  const tmpDb = join(work, "seed.db");
  execFileSync("sqlite3", ["-bail", tmpDb, `.read ${sqlFile}`], { stdio: "inherit" });
  rmSync(OUT, { force: true });
  execFileSync("sqlite3", [tmpDb, `VACUUM INTO '${OUT.replace(/'/g, "''")}'`], { stdio: "inherit" });
} finally {
  rmSync(work, { recursive: true, force: true });
}
console.log(`wrote ${OUT}: ${reports.length} reports, ${features.length} feature rows, ${areas.length} area rows, weeks ${weeks[0]}..${weeks[WEEKS - 1]}`);

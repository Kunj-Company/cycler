import { env, exports } from "cloudflare:workers";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentWeek, weekOffset } from "../src/week";

const DB = (env as unknown as { DB: D1Database }).DB;
const worker = (exports as unknown as { default: Fetcher }).default;
const BASE = "https://stats.example";

const INSTALL = "3f0c6a1e-8d3b-4c55-9a7e-2a1f0b6c9d10";
const REPORT_ID = "b7e4c2a0-1f3d-4e5a-9b6c-7d8e9f0a1b2c";
const LAST_WEEK = weekOffset(-1);

function validReport(over: Record<string, unknown> = {}) {
  return {
    schema: 1,
    install: INSTALL,
    app_version: "0.1.13",
    week: LAST_WEEK,
    days_active: 3,
    rides_started: 4,
    rides_finished: 3,
    rides_discarded: 1,
    km: 42.7,
    features: { export_gpx: 2, share_video: 1 },
    ...over,
  };
}

function validArea(over: Record<string, unknown> = {}) {
  return {
    schema: 1,
    report: REPORT_ID,
    app_version: "0.1.13",
    week: LAST_WEEK,
    cells: [
      { lat: 12.9, lon: 77.5, kind: "start", rides: 3, km: 40.1 },
      { lat: 12.9, lon: 77.6, kind: "end", rides: 3, km: 40.1 },
    ],
    ...over,
  };
}

function post(path: string, body: unknown, headers: Record<string, string> = {}) {
  return worker.fetch(`${BASE}${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

async function all<T = Record<string, unknown>>(sql: string): Promise<T[]> {
  return (await DB.prepare(sql).all<T>()).results;
}

async function count(table: string): Promise<number> {
  return (await DB.prepare(`SELECT COUNT(*) AS n FROM ${table}`).first<{ n: number }>())!.n;
}

beforeEach(async () => {
  await DB.batch([DB.prepare("DELETE FROM reports"), DB.prepare("DELETE FROM features"), DB.prepare("DELETE FROM areas")]);
});

describe("routing", () => {
  it("GET / returns one line of plain text", async () => {
    const res = await worker.fetch(`${BASE}/`);
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toMatch(/^text\/plain/);
    const body = await res.text();
    expect(body.trim().split("\n")).toHaveLength(1);
  });

  it("unknown path is 404", async () => {
    expect((await worker.fetch(`${BASE}/v1/other`, { method: "POST", body: "{}" })).status).toBe(404);
    expect((await worker.fetch(`${BASE}/v2/report`)).status).toBe(404);
  });

  it("wrong method is 405", async () => {
    expect((await worker.fetch(`${BASE}/v1/report`)).status).toBe(405);
    expect((await worker.fetch(`${BASE}/v1/area`, { method: "PUT", body: "{}" })).status).toBe(405);
    expect((await worker.fetch(`${BASE}/`, { method: "POST", body: "{}" })).status).toBe(405);
  });
});

describe("POST /v1/report", () => {
  it("stores a valid report and its features", async () => {
    const res = await post("/v1/report", validReport());
    expect(res.status).toBe(204);
    const rows = await all("SELECT * FROM reports");
    expect(rows).toEqual([
      {
        install: INSTALL,
        week: LAST_WEEK,
        app_version: "0.1.13",
        days_active: 3,
        rides_started: 4,
        rides_finished: 3,
        rides_discarded: 1,
        km: 42.7,
        received_week: currentWeek(),
      },
    ]);
    expect(await all("SELECT key, count FROM features ORDER BY key")).toEqual([
      { key: "export_gpx", count: 2 },
      { key: "share_video", count: 1 },
    ]);
  });

  it("accepts a report without features, debug versions and rounds km to 1 decimal", async () => {
    const r = validReport({ app_version: "0.1.13-debug", km: 10.04 }) as Record<string, unknown>;
    delete r.features;
    expect((await post("/v1/report", r)).status).toBe(204);
    expect(await all("SELECT app_version, km FROM reports")).toEqual([{ app_version: "0.1.13-debug", km: 10 }]);
    expect(await count("features")).toBe(0);
  });

  it("normalises an upper-case UUID to lower case", async () => {
    expect((await post("/v1/report", validReport({ install: INSTALL.toUpperCase() }))).status).toBe(204);
    expect(await all("SELECT install FROM reports")).toEqual([{ install: INSTALL }]);
  });

  it("is idempotent: a retry replaces the report and its features", async () => {
    expect((await post("/v1/report", validReport())).status).toBe(204);
    expect((await post("/v1/report", validReport())).status).toBe(204);
    expect(await count("reports")).toBe(1);
    expect(await count("features")).toBe(2);

    // A corrected resend replaces values and drops features no longer present.
    expect((await post("/v1/report", validReport({ km: 50, features: { plan_loop: 5 } }))).status).toBe(204);
    expect(await all("SELECT km FROM reports")).toEqual([{ km: 50 }]);
    expect(await all("SELECT key, count FROM features")).toEqual([{ key: "plan_loop", count: 5 }]);
  });

  it("keeps different weeks and installs apart", async () => {
    await post("/v1/report", validReport());
    await post("/v1/report", validReport({ week: weekOffset(-2) }));
    await post("/v1/report", validReport({ install: "11111111-2222-4333-8444-555555555555" }));
    expect(await count("reports")).toBe(3);
    expect(await count("features")).toBe(6);
  });

  it("accepts the boundary weeks (10 weeks old, 1 week ahead)", async () => {
    expect((await post("/v1/report", validReport({ week: weekOffset(-10) }))).status).toBe(204);
    expect((await post("/v1/report", validReport({ week: weekOffset(1) }))).status).toBe(204);
    expect((await post("/v1/report", validReport({ week: currentWeek() }))).status).toBe(204);
  });

  it("accepts 64 feature keys and max values", async () => {
    const features: Record<string, number> = {};
    for (let i = 0; i < 64; i++) features[`key_${i}`] = 10000;
    const r = validReport({ features, days_active: 7, rides_started: 500, rides_finished: 500, rides_discarded: 500, km: 20000 });
    expect((await post("/v1/report", r)).status).toBe(204);
    expect(await count("features")).toBe(64);
  });

  const invalid: [string, unknown][] = [
    ["schema missing", (() => { const r = validReport() as Record<string, unknown>; delete r.schema; return r; })()],
    ["schema 2", validReport({ schema: 2 })],
    ["schema as string", validReport({ schema: "1" })],
    ["unknown top-level field", validReport({ ip: "1.2.3.4" })],
    ["install missing", (() => { const r = validReport() as Record<string, unknown>; delete r.install; return r; })()],
    ["install not a uuid", validReport({ install: "hello" })],
    ["install uuid v1", validReport({ install: "3f0c6a1e-8d3b-1c55-9a7e-2a1f0b6c9d10" })],
    ["install bad variant", validReport({ install: "3f0c6a1e-8d3b-4c55-0a7e-2a1f0b6c9d10" })],
    ["install number", validReport({ install: 42 })],
    ["app_version empty", validReport({ app_version: "" })],
    ["app_version too long", validReport({ app_version: "1".repeat(33) })],
    ["app_version with spaces", validReport({ app_version: "1.0 beta" })],
    ["app_version not string", validReport({ app_version: 1.0 })],
    ["week malformed", validReport({ week: "2026-48" })],
    ["week lowercase w", validReport({ week: LAST_WEEK.replace("W", "w") })],
    ["week 00", validReport({ week: `${LAST_WEEK.slice(0, 4)}-W00` })],
    ["week 54", validReport({ week: `${LAST_WEEK.slice(0, 4)}-W54` })],
    ["week too far ahead", validReport({ week: weekOffset(2) })],
    ["week too old", validReport({ week: weekOffset(-11) })],
    ["week as number", validReport({ week: 202648 })],
    ["days_active 8", validReport({ days_active: 8 })],
    ["days_active negative", validReport({ days_active: -1 })],
    ["days_active fractional", validReport({ days_active: 1.5 })],
    ["days_active string", validReport({ days_active: "3" })],
    ["rides_started 501", validReport({ rides_started: 501 })],
    ["rides_finished negative", validReport({ rides_finished: -1 })],
    ["rides_discarded null", validReport({ rides_discarded: null })],
    ["rides_started missing", (() => { const r = validReport() as Record<string, unknown>; delete r.rides_started; return r; })()],
    ["km negative", validReport({ km: -0.1 })],
    ["km too large", validReport({ km: 20000.1 })],
    ["km string", validReport({ km: "42" })],
    ["features array", validReport({ features: ["export_gpx"] })],
    ["features null", validReport({ features: null })],
    ["features key uppercase", validReport({ features: { Export: 1 } })],
    ["features key too short", validReport({ features: { a: 1 } })],
    ["features key too long", validReport({ features: { ["a".repeat(41)]: 1 } })],
    ["features key starts with digit", validReport({ features: { "1abc": 1 } })],
    ["features __proto__ key", JSON.parse(`{"schema":1,"install":"${INSTALL}","app_version":"1","week":"${LAST_WEEK}","days_active":1,"rides_started":0,"rides_finished":0,"rides_discarded":0,"km":0,"features":{"__proto__":1}}`)],
    ["features zero count", validReport({ features: { export_gpx: 0 } })],
    ["features count too large", validReport({ features: { export_gpx: 10001 } })],
    ["features count fractional", validReport({ features: { export_gpx: 1.5 } })],
    ["features 65 keys", validReport({ features: Object.fromEntries(Array.from({ length: 65 }, (_, i) => [`key_${i}`, 1])) })],
    ["body is an array", [validReport()]],
    ["body is null", null],
    ["body is a string", "\"hello\""],
    ["body is not json", "{not json"],
    ["body empty", ""],
  ];

  for (const [name, body] of invalid) {
    it(`rejects ${name} with 400 and stores nothing`, async () => {
      const res = await post("/v1/report", typeof body === "string" ? body : body);
      expect(res.status).toBe(400);
      expect(await count("reports")).toBe(0);
      expect(await count("features")).toBe(0);
    });
  }

  it("rejects a body over 16 KB", async () => {
    const r = validReport({ app_version: "1" }) as Record<string, unknown>;
    const json = JSON.stringify(r);
    const padded = json.slice(0, -1) + "," + JSON.stringify("pad") + ":" + JSON.stringify("x".repeat(17 * 1024)) + "}";
    const res = await post("/v1/report", padded);
    expect(res.status).toBe(400);
    expect(await count("reports")).toBe(0);
  });

  it("rejects invalid UTF-8", async () => {
    const bytes = new Uint8Array([0x7b, 0xff, 0xfe, 0x7d]);
    const res = await worker.fetch(`${BASE}/v1/report`, { method: "POST", body: bytes });
    expect(res.status).toBe(400);
  });
});

describe("POST /v1/area", () => {
  it("stores a valid area report", async () => {
    expect((await post("/v1/area", validArea())).status).toBe(204);
    expect(await all("SELECT * FROM areas ORDER BY kind DESC")).toEqual([
      { report: REPORT_ID, week: LAST_WEEK, app_version: "0.1.13", lat: 12.9, lon: 77.5, kind: "start", rides: 3, km: 40.1 },
      { report: REPORT_ID, week: LAST_WEEK, app_version: "0.1.13", lat: 12.9, lon: 77.6, kind: "end", rides: 3, km: 40.1 },
    ]);
  });

  it("accepts extreme coordinates and negative cells", async () => {
    const cells = [
      { lat: -90, lon: -180, kind: "start", rides: 1, km: 0 },
      { lat: 90, lon: 180, kind: "end", rides: 500, km: 20000 },
      { lat: -33.9, lon: 151.2, kind: "start", rides: 1, km: 5.5 },
    ];
    expect((await post("/v1/area", validArea({ cells }))).status).toBe(204);
    expect(await count("areas")).toBe(3);
  });

  it("is idempotent: a retry replaces the cells of that report", async () => {
    expect((await post("/v1/area", validArea())).status).toBe(204);
    expect((await post("/v1/area", validArea())).status).toBe(204);
    expect(await count("areas")).toBe(2);
    const cells = [{ lat: 1.1, lon: 2.2, kind: "start", rides: 1, km: 3 }];
    expect((await post("/v1/area", validArea({ cells }))).status).toBe(204);
    expect(await all("SELECT lat, lon FROM areas")).toEqual([{ lat: 1.1, lon: 2.2 }]);
  });

  it("keeps different area reports apart", async () => {
    await post("/v1/area", validArea());
    await post("/v1/area", validArea({ report: "c0ffee00-1234-4abc-8def-0123456789ab" }));
    expect(await count("areas")).toBe(4);
  });

  it("accepts 50 cells", async () => {
    const cells = Array.from({ length: 50 }, (_, i) => ({ lat: i / 10, lon: 0, kind: "start", rides: 1, km: 1 }));
    expect((await post("/v1/area", validArea({ cells }))).status).toBe(204);
    expect(await count("areas")).toBe(50);
  });

  const cell = { lat: 12.9, lon: 77.5, kind: "start", rides: 3, km: 40.1 };
  const invalid: [string, unknown][] = [
    ["schema 2", validArea({ schema: 2 })],
    ["unknown top-level field", validArea({ install: INSTALL })],
    ["report missing", (() => { const a = validArea() as Record<string, unknown>; delete a.report; return a; })()],
    ["report not a uuid", validArea({ report: "abc" })],
    ["app_version bad", validArea({ app_version: "a b" })],
    ["week malformed", validArea({ week: "W48-2026" })],
    ["week too old", validArea({ week: weekOffset(-11) })],
    ["week too far ahead", validArea({ week: weekOffset(2) })],
    ["cells missing", (() => { const a = validArea() as Record<string, unknown>; delete a.cells; return a; })()],
    ["cells empty", validArea({ cells: [] })],
    ["cells not array", validArea({ cells: cell })],
    ["51 cells", validArea({ cells: Array.from({ length: 51 }, (_, i) => ({ ...cell, lat: i / 10 })) })],
    ["cell not object", validArea({ cells: [1] })],
    ["cell unknown field", validArea({ cells: [{ ...cell, name: "home" }] })],
    ["cell missing km", validArea({ cells: [{ lat: 1, lon: 1, kind: "start", rides: 1 }] })],
    ["cell lat > 90", validArea({ cells: [{ ...cell, lat: 90.1 }] })],
    ["cell lat < -90", validArea({ cells: [{ ...cell, lat: -90.1 }] })],
    ["cell lon > 180", validArea({ cells: [{ ...cell, lon: 180.1 }] })],
    ["cell lon < -180", validArea({ cells: [{ ...cell, lon: -180.1 }] })],
    ["cell lat with 2 decimals (exact location)", validArea({ cells: [{ ...cell, lat: 12.97 }] })],
    ["cell lon with many decimals", validArea({ cells: [{ ...cell, lon: 77.5946 }] })],
    ["cell lat string", validArea({ cells: [{ ...cell, lat: "12.9" }] })],
    ["cell kind other", validArea({ cells: [{ ...cell, kind: "middle" }] })],
    ["cell rides 0", validArea({ cells: [{ ...cell, rides: 0 }] })],
    ["cell rides 501", validArea({ cells: [{ ...cell, rides: 501 }] })],
    ["cell rides fractional", validArea({ cells: [{ ...cell, rides: 1.5 }] })],
    ["cell km negative", validArea({ cells: [{ ...cell, km: -1 }] })],
    ["cell km too large", validArea({ cells: [{ ...cell, km: 20001 }] })],
    ["duplicate cell", validArea({ cells: [cell, { ...cell }] })],
    ["body array", [validArea()]],
    ["body not json", "nope"],
  ];

  for (const [name, body] of invalid) {
    it(`rejects ${name} with 400 and stores nothing`, async () => {
      const res = await post("/v1/area", body);
      expect(res.status).toBe(400);
      expect(await count("areas")).toBe(0);
    });
  }

  it("rejects a body over 16 KB", async () => {
    const res = await post("/v1/area", " ".repeat(16 * 1024) + JSON.stringify(validArea()));
    expect(res.status).toBe(400);
    expect(await count("areas")).toBe(0);
  });
});

describe("privacy", () => {
  let spies: ReturnType<typeof vi.spyOn>[] = [];
  beforeEach(() => {
    spies = (["log", "info", "warn", "error", "debug"] as const).map((m) => vi.spyOn(console, m));
  });
  afterEach(() => spies.forEach((s) => s.mockRestore()));

  it("stores nothing from the IP, headers or user agent, and logs nothing", async () => {
    const marker = "203.0.113.77";
    const headers = {
      "cf-connecting-ip": marker,
      "x-forwarded-for": marker,
      "x-real-ip": marker,
      "true-client-ip": marker,
      "user-agent": `okhttp/4.12 ${marker}`,
      "cf-ipcountry": "XX",
    };
    expect((await post("/v1/report", validReport(), headers)).status).toBe(204);
    expect((await post("/v1/area", validArea(), headers)).status).toBe(204);
    expect((await post("/v1/report", validReport({ km: "bad" }), headers)).status).toBe(400);

    const dump = JSON.stringify([
      await all("SELECT * FROM reports"),
      await all("SELECT * FROM features"),
      await all("SELECT * FROM areas"),
    ]);
    expect(dump).not.toContain(marker);
    expect(dump).not.toContain("okhttp");
    expect(dump).not.toContain("XX");

    for (const s of spies) expect(s).not.toHaveBeenCalled();
  });

  it("tables have exactly the contract columns and nothing else", async () => {
    const cols = async (t: string) => (await all<{ name: string }>(`SELECT name FROM pragma_table_info('${t}')`)).map((r) => r.name);
    expect(await cols("reports")).toEqual([
      "install", "week", "app_version", "days_active", "rides_started", "rides_finished", "rides_discarded", "km", "received_week",
    ]);
    expect(await cols("features")).toEqual(["install", "week", "key", "count"]);
    expect(await cols("areas")).toEqual(["report", "week", "app_version", "lat", "lon", "kind", "rides", "km"]);
    const tables = (await all<{ name: string }>(
      "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '_cf_%' AND name <> 'd1_migrations' ORDER BY name",
    )).map((r) => r.name);
    expect(tables).toEqual(["areas", "features", "reports"]);
  });

  it("areas is WITHOUT ROWID, so there is no hidden insertion order", async () => {
    const sql = (await DB.prepare("SELECT sql FROM sqlite_master WHERE name = 'areas'").first<{ sql: string }>())!.sql;
    expect(sql).toMatch(/WITHOUT ROWID/i);
    await expect(DB.prepare("SELECT rowid FROM areas").all()).rejects.toThrow();
  });
});

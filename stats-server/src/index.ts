// Cycler anonymous usage stats endpoint.
//
// Privacy rules (hard requirements):
// - Never read or store the client IP, any request header, user agent, or Cloudflare `cf` data.
//   Only the method, the URL path and the body are used.
// - Never log request data (no console.* calls at all). Observability is off in wrangler.jsonc.

import { currentWeek } from "./week";
import { Invalid, MAX_BODY_BYTES, parseArea, parseReport, type AreaReport, type Report } from "./validate";

export interface Env {
  DB: D1Database;
}

const ROOT_TEXT = "Cycler anonymous stats endpoint. Privacy: https://github.com/Kunj-Company/cycler/blob/main/docs/PRIVACY.md\n";

function text(status: number, body: string | null, extra: Record<string, string> = {}): Response {
  return new Response(body, {
    status,
    headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store", ...extra },
  });
}

/** Reads at most MAX_BODY_BYTES of the body as UTF-8 text. Throws Invalid if larger or not UTF-8. */
async function readBody(request: Request): Promise<string> {
  if (!request.body) throw new Invalid("empty body");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > MAX_BODY_BYTES) {
      await reader.cancel();
      throw new Invalid("body too large");
    }
    chunks.push(value);
  }
  const buf = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) {
    buf.set(c, off);
    off += c.byteLength;
  }
  try {
    return new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(buf);
  } catch {
    throw new Invalid("body not utf-8");
  }
}

async function readJson(request: Request): Promise<unknown> {
  const raw = await readBody(request);
  try {
    return JSON.parse(raw);
  } catch {
    throw new Invalid("body not json");
  }
}

function storeReport(db: D1Database, r: Report, receivedWeek: string): Promise<unknown> {
  const stmts: D1PreparedStatement[] = [
    db
      .prepare(
        `INSERT OR REPLACE INTO reports
           (install, week, app_version, days_active, rides_started, rides_finished, rides_discarded, km, received_week)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .bind(r.install, r.week, r.app_version, r.days_active, r.rides_started, r.rides_finished, r.rides_discarded, r.km, receivedWeek),
    db.prepare(`DELETE FROM features WHERE install = ? AND week = ?`).bind(r.install, r.week),
  ];
  const ins = db.prepare(`INSERT INTO features (install, week, key, count) VALUES (?, ?, ?, ?)`);
  for (const [key, count] of r.features) stmts.push(ins.bind(r.install, r.week, key, count));
  return db.batch(stmts); // one transaction
}

function storeArea(db: D1Database, a: AreaReport): Promise<unknown> {
  // Replace the whole area report on retry (idempotent on report id).
  const stmts: D1PreparedStatement[] = [db.prepare(`DELETE FROM areas WHERE report = ?`).bind(a.report)];
  const ins = db.prepare(
    `INSERT INTO areas (report, week, app_version, lat, lon, kind, rides, km) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
  );
  for (const c of a.cells) stmts.push(ins.bind(a.report, a.week, a.app_version, c.lat, c.lon, c.kind, c.rides, c.km));
  return db.batch(stmts);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(request.url);
    const method = request.method;

    if (pathname === "/") {
      if (method === "GET" || method === "HEAD") return text(200, method === "HEAD" ? null : ROOT_TEXT);
      return text(405, "method not allowed\n", { allow: "GET, HEAD" });
    }

    if (pathname !== "/v1/report" && pathname !== "/v1/area") return text(404, "not found\n");
    if (method !== "POST") return text(405, "method not allowed\n", { allow: "POST" });

    const now = Date.now();
    try {
      const body = await readJson(request);
      if (pathname === "/v1/report") {
        await storeReport(env.DB, parseReport(body, now), currentWeek(now));
      } else {
        await storeArea(env.DB, parseArea(body, now));
      }
      return new Response(null, { status: 204 });
    } catch (e) {
      if (e instanceof Invalid) return text(400, `invalid: ${e.message}\n`);
      // Storage or runtime problem: the phone keeps the report and retries later.
      return text(503, "try again later\n");
    }
  },
} satisfies ExportedHandler<Env>;

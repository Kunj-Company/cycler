#!/usr/bin/env node
// Runs every block of metabase/queries.sql against a LOCAL SQLite file and prints
// row counts and the first rows. Fails if any query errors or returns no rows.
//
//   node dev/check-queries.mjs [path/to.db]     (default dev/seed.db, see dev/seed.mjs)

import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const db = resolve(process.argv[2] ?? join(ROOT, "dev", "seed.db"));
const text = readFileSync(join(ROOT, "metabase", "queries.sql"), "utf8");
const blocks = text.split(/^-- ===== /m).slice(1);

let failed = 0;
for (const block of blocks) {
  const title = block.split("\n")[0];
  const sql = block.slice(block.indexOf("\n") + 1);
  try {
    const out = execFileSync("sqlite3", ["-readonly", "-header", "-separator", " | ", db], { encoding: "utf8", input: sql });
    const lines = out.trim().split("\n").filter(Boolean);
    const rows = Math.max(0, lines.length - 1);
    if (rows === 0) throw new Error("no rows");
    console.log(`OK   ${title}\n     ${rows} rows\n     ${lines.slice(0, 3).join("\n     ")}\n`);
  } catch (e) {
    failed++;
    console.log(`FAIL ${title}\n     ${String(e.stderr || e.message).trim()}\n`);
  }
}
console.log(`${blocks.length - failed}/${blocks.length} queries OK`);
process.exit(failed ? 1 : 0);

# Cycler stats server

The server side of Cycler's anonymous usage stats. A small Cloudflare Worker receives weekly
reports from the app and stores them in a Cloudflare D1 (SQLite) database. Once a day a spare
laptop at home copies the database into a local SQLite file, and Metabase on that laptop draws
the dashboards.

- Live endpoint: **https://cycler-stats.hi-480.workers.dev**
- D1 database: `cycler-stats` (id `9b23cbdc-f98f-48f7-bd70-a229f98ba9ee`)
- Worker: `cycler-stats`

```
phone --HTTPS--> Worker (validates) --> D1  --daily export--> laptop: stats.db --> Metabase :3000
```

## What is stored, and what never is

Stored (see `migrations/0001_init.sql`):

| Table | Columns | Notes |
|---|---|---|
| `reports` | install, week, app_version, days_active, rides_started, rides_finished, rides_discarded, km, received_week | One row per install per ISO week. `install` is a random UUID made on the phone. |
| `features` | install, week, key, count | How often a feature was used that week. |
| `areas` | report, week, app_version, lat, lon, kind, rides, km | ~11 km cells (lat/lon floored to 0.1°) where rides started/ended. No install ID. `report` is a fresh random ID used only to make retries safe. |

Never stored or read: IP address, any request header (including user agent and Cloudflare's
country/location data), Android or advertising IDs, device model, exact coordinates, routes,
place names, names/notes/photos, speed, heart rate, Health Connect data, exact timestamps.
The finest time unit is the ISO week; `received_week` is the server's week, not a timestamp.

How that is enforced:

- The worker only looks at the HTTP method, the URL path and the body. It never touches
  `request.headers` or `request.cf`, and it has no `console.*` calls.
- Workers Logs, traces and Logpush are off in `wrangler.jsonc`. No tail workers.
- `areas` is a `WITHOUT ROWID` table with no time column, so row order cannot be used to link an
  area report to an install's report. The app also sends the two at different random times.
- The tests check that IP/user-agent header values never reach the database and that the
  tables have exactly the columns above.

## API (contract v1)

`POST /v1/report` and `POST /v1/area`, JSON body up to 16 KB.

- `204`: stored. Sending the same report again replaces it (no duplicates).
- `400`: invalid; the app drops it. The response text names the failing field.
- `503`: storage problem; the app retries later. `404`/`405`: wrong path/method.
- `GET /` returns one line of text.

Validation is strict: exact field set (unknown fields are rejected), integer ranges, UUID v4
format, feature keys `^[a-z][a-z0-9_]{1,39}$` (max 64), cell coordinates with at most one
decimal, at most 50 cells, and `week` must be a real ISO week between 10 weeks ago and 1 week
ahead of server time. Full contract: see `src/validate.ts`.

## Develop, test, deploy

Needs Node 22+.

```sh
cd stats-server
npm install
npm test            # vitest inside the Workers runtime, against a throwaway local D1
npm run typecheck
npx wrangler login  # once
npm run deploy      # applies new migrations to the remote D1, then deploys the worker
```

Schema changes: add `migrations/0002_*.sql`, never edit `0001`. `npm run deploy` applies it.

To try the dashboards without real data: `node dev/seed.mjs` writes a synthetic
`dev/seed.db` (local only, it never touches Cloudflare), and `node dev/check-queries.mjs`
runs every query in `metabase/queries.sql` against it.

## Spare laptop setup (home server)

Works on Linux or macOS. Do these once.

1. **Install tools.**
   - macOS: install [Homebrew](https://brew.sh), then `brew install node sqlite git` and
     install [Docker Desktop](https://www.docker.com/products/docker-desktop/) (set it to start at login).
   - Ubuntu/Debian: `sudo apt install nodejs npm sqlite3 git curl` (Node must be 18+; use
     [NodeSource](https://github.com/nodesource/distributions) if apt's is older), then install
     Docker Engine with the compose plugin ([guide](https://docs.docker.com/engine/install/)).
   - Stop the laptop from sleeping when the lid is closed / on power, or the daily job and
     Metabase pause with it.
2. **Get the code:** `git clone https://github.com/Kunj-Company/cycler.git && cd cycler/stats-server && npm install`
   (`npm install` is optional; without it the scripts fetch the pinned wrangler through npx.)
3. **Create a Cloudflare API token** (dashboard → My Profile → API Tokens → Create Token →
   Create Custom Token):
   - Permissions: **Account → D1 → Edit**
   - Account Resources: Include → your account
   - Nothing else. Cloudflare's API reference lists no permission for the D1 export call
     (other D1 calls accept "D1 Read" or "D1 Write"); export is a write-type call that locks the
     database, so use D1 Edit (shown as "D1 Write" in the API). If a pull fails with an
     authentication error, check this first. The same token lets `scripts/delete-install.sh`
     delete rows. It cannot change the worker, DNS or anything else.
   - Alternative: skip the token and run `npx wrangler login` on the laptop (broader access).
4. **Configure:** `cp exporter/.env.example exporter/.env`, then put the token and your account ID
   (`npx wrangler whoami` or the dashboard sidebar) in it. `exporter/.env` is gitignored;
   `chmod 600 exporter/.env`.
5. **First run:** `exporter/pull.sh`. It prints row counts and creates `exporter/data/stats.db`
   and a gzipped dump in `exporter/data/backups/`.
6. **Schedule it daily** (pick one; replace the example path with yours):
   - Linux, systemd (recommended, catches up after the laptop was off):
     `exporter/schedule/cycler-stats-pull.service` + `.timer`, install steps in the file.
   - Linux/macOS, cron: the line in `exporter/schedule/crontab.txt`.
   - macOS, launchd (runs after wake if asleep at 04:15):
     `exporter/schedule/company.kunj.cycler-stats-pull.plist`, install steps in the file.
7. **Start Metabase:**
   ```sh
   cd metabase
   docker compose up -d
   ./setup.sh     # asks for an admin email + password, adds the "Cycler stats" database
   ```
   The first start takes a minute or two. Metabase restarts by itself after reboots as long
   as Docker starts.
8. **Build the dashboard:**
   ```sh
   MB_URL=http://localhost:3000 MB_EMAIL=you@example.com MB_PASSWORD=... node dashboard.mjs
   ```
   This saves every question in `metabase/queries.sql` with a chart, plus headline tiles
   (total km, rides, riders, active riders), on one "Cycler stats" dashboard. Run it again
   after editing `queries.sql`; it replaces what it made before.
9. **Open `http://<laptop-name-or-ip>:3000`** from any device on the home network and log in.
   To allow only the laptop itself, put `METABASE_BIND=127.0.0.1` in `metabase/.env`; if
   port 3000 is taken, set `METABASE_PORT`. Do not forward the port on your router.
   To reach it from anywhere over Tailscale instead, bind to 127.0.0.1 and run
   `sudo tailscale serve --bg --http 3030 http://127.0.0.1:3030` (tailnet only).

### What the exporter does

`exporter/pull.sh` takes a lock (so two runs never overlap), runs
`wrangler d1 export cycler-stats --remote`, loads the dump into a fresh SQLite file, adds the
helper views (`v_reports`, `v_features`, `v_areas` with a `week_start` date), checks
`PRAGMA integrity_check` and that the tables exist, and refuses to continue if the row count
dropped by more than half compared to the current copy (use `FORCE=1 exporter/pull.sh` after a
large deletion). It then swaps the data into `exporter/data/stats.db` in a single SQLite
transaction (Metabase sees old or new, never a mix, without a restart) and keeps the newest
60 gzipped dumps in `exporter/data/backups/`.

Restore a backup: `gunzip -c exporter/data/backups/<file>.sql.gz | sqlite3 restored.db`.

## Deleting someone's stats

Users email the stats ID shown in Settings → Privacy. Then, from `stats-server/` on a machine
with `wrangler login` or `exporter/.env`:

```sh
scripts/delete-install.sh 3f0c6a1e-8d3b-4c55-9a7e-2a1f0b6c9d10
```

It checks the ID format, shows how many `reports`/`features` rows exist, asks for
confirmation (`--yes` skips it), deletes them from the live D1 and shows the counts after.
`areas` rows have no install ID, so they cannot be linked to the person and are not deleted.
The laptop copy drops the rows on its next run; older dumps in `exporter/data/backups/` still
contain them until they rotate out (60 days). Delete those files too if the request asks for it.

## Costs and limits (Cloudflare free plan)

Free-plan numbers at the time of writing (check Cloudflare's pricing pages for current ones):

- Workers: 100,000 requests/day. Each install sends 2 requests per week.
- D1: 5 GB storage, 5 million rows read/day, 100,000 rows written/day. One install-week costs
  roughly 20 to 40 row writes (rows plus index entries), so about 2,500 to 5,000 install-weeks
  per day fit. Most phones report on Monday, so past roughly 2,500 weekly active installs
  consider Workers Paid ($5/month, 50 million rows written/month). If the daily write limit is
  hit, the worker returns 503 and phones simply retry the next day; nothing is lost.
- Storage: about 1 to 2 KB per install-week, so 1,000 installs for a year is around 100 MB.
- The daily export reads every row once and briefly blocks the database while it runs (a few
  seconds at this size). Phones retry if they hit that moment.

Total cost on the free plan: $0. The laptop's electricity is the only running cost.

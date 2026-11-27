#!/usr/bin/env bash
# Pulls the cycler-stats D1 database into a local SQLite file for Metabase.
#
#   exporter/pull.sh            # normal run (daily from cron / systemd / launchd)
#   FORCE=1 exporter/pull.sh    # accept a big drop in row count (e.g. after mass deletion)
#
# Result: exporter/data/stats.db (replaced atomically) and
#         exporter/data/backups/cycler-stats-YYYY-MM-DD_HHMMSS.sql.gz (last 60 kept).
# Auth:   CLOUDFLARE_API_TOKEN + CLOUDFLARE_ACCOUNT_ID from exporter/.env, or an existing `wrangler login`.
# Needs:  bash, sqlite3, gzip, node/npx. Works on Linux and macOS.
set -euo pipefail

DB_NAME="cycler-stats"
WRANGLER_VERSION="4.168.0"
KEEP_BACKUPS=60

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="$HERE/data"
BACKUPS="$DATA/backups"
LOCK="$DATA/.pull.lock"
mkdir -p "$BACKUPS"

log() { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

# Cron/launchd start with a minimal PATH; add the usual places for node and sqlite3.
export PATH="$PATH:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:$HOME/.local/bin"
if [ -d "$HOME/.nvm/versions/node" ] && ! command -v npx >/dev/null 2>&1; then
  NVM_NODE="$(ls -d "$HOME"/.nvm/versions/node/*/bin 2>/dev/null | sort -V | tail -1 || true)"
  [ -n "$NVM_NODE" ] && export PATH="$NVM_NODE:$PATH"
fi
for bin in sqlite3 gzip npx; do
  command -v "$bin" >/dev/null 2>&1 || die "$bin not found in PATH"
done

# ---- lock (mkdir is atomic everywhere; flock is not available on macOS) ----
if ! mkdir "$LOCK" 2>/dev/null; then
  OTHER="$(cat "$LOCK/pid" 2>/dev/null || true)"
  if [ -n "$OTHER" ] && kill -0 "$OTHER" 2>/dev/null; then
    die "another pull is running (pid $OTHER)"
  fi
  log "removing stale lock (pid ${OTHER:-unknown})"
  rm -rf "$LOCK"
  mkdir "$LOCK" || die "could not take lock"
fi
echo $$ > "$LOCK/pid"

WORK="$(mktemp -d "$DATA/.pull.XXXXXX")"
cleanup() { rm -rf "$WORK" "$LOCK"; }
trap cleanup EXIT INT TERM

# ---- credentials ----
if [ -f "$HERE/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$HERE/.env"
  set +a
fi
if [ -n "${CLOUDFLARE_API_TOKEN:-}" ]; then
  [ -n "${CLOUDFLARE_ACCOUNT_ID:-}" ] || die "CLOUDFLARE_ACCOUNT_ID must be set together with CLOUDFLARE_API_TOKEN"
  log "auth: API token from environment"
else
  unset CLOUDFLARE_API_TOKEN
  log "auth: using existing 'wrangler login'"
fi

# Prefer the repo's pinned wrangler if `npm install` was run, else fetch the pinned version.
if [ -x "$HERE/../node_modules/.bin/wrangler" ]; then
  WRANGLER=("$HERE/../node_modules/.bin/wrangler")
else
  WRANGLER=(npx --yes "wrangler@$WRANGLER_VERSION")
fi

# ---- export ----
DUMP="$WORK/dump.sql"
log "exporting $DB_NAME from Cloudflare"
# Run outside the repo so no wrangler.jsonc is picked up; keep wrangler's chatter in a file
# (it prints a temporary signed download URL) and show it only on failure.
if ! (cd "$WORK" && WRANGLER_SEND_METRICS=false "${WRANGLER[@]}" d1 export "$DB_NAME" --remote --output "$DUMP") >"$WORK/wrangler.log" 2>&1; then
  sed -E 's#https://[^ ]*X-Amz[^ ]*#<signed-url>#g' "$WORK/wrangler.log" >&2
  die "wrangler d1 export failed"
fi
[ -s "$DUMP" ] || die "export is empty"
grep -q 'CREATE TABLE reports' "$DUMP" || die "export has no reports table"

# ---- build a fresh SQLite file ----
NEW="$WORK/stats.db"
sqlite3 -bail "$NEW" < "$DUMP" || die "could not load the export into SQLite"
sqlite3 -bail "$NEW" < "$HERE/views.sql" || die "could not create views"
sqlite3 "$NEW" "PRAGMA journal_mode=DELETE;" >/dev/null

# ---- sanity checks ----
INTEGRITY="$(sqlite3 "$NEW" 'PRAGMA integrity_check;')"
[ "$INTEGRITY" = "ok" ] || die "integrity_check failed: $INTEGRITY"
for t in reports features areas; do
  sqlite3 "$NEW" "SELECT 1 FROM $t LIMIT 1;" >/dev/null || die "table $t missing"
done
NEW_ROWS="$(sqlite3 "$NEW" 'SELECT (SELECT COUNT(*) FROM reports) + (SELECT COUNT(*) FROM features) + (SELECT COUNT(*) FROM areas);')"
REPORTS="$(sqlite3 "$NEW" 'SELECT COUNT(*) FROM reports;')"
OLD_ROWS=0
if [ -f "$DATA/stats.db" ]; then
  OLD_ROWS="$(sqlite3 "$DATA/stats.db" 'SELECT (SELECT COUNT(*) FROM reports) + (SELECT COUNT(*) FROM features) + (SELECT COUNT(*) FROM areas);' 2>/dev/null || echo 0)"
fi
# Data only grows apart from manual deletions. A big drop means something went wrong.
if [ "${FORCE:-0}" != "1" ] && [ "$OLD_ROWS" -gt 0 ] && [ $((NEW_ROWS * 2)) -lt "$OLD_ROWS" ]; then
  die "row count fell from $OLD_ROWS to $NEW_ROWS; keeping the old stats.db (run with FORCE=1 to accept)"
fi

# ---- backup the dump, then atomically swap the database ----
STAMP="$(date '+%Y-%m-%d_%H%M%S')"
gzip -9 -c "$DUMP" > "$BACKUPS/.tmp-$STAMP.sql.gz"
mv -f "$BACKUPS/.tmp-$STAMP.sql.gz" "$BACKUPS/cycler-stats-$STAMP.sql.gz"
chmod 644 "$NEW"
if [ -f "$DATA/stats.db" ]; then
  # Replace the contents in one SQLite transaction (backup API) instead of renaming over
  # the file: Metabase keeps its SQLite connection open, and a renamed-over file would
  # leave it reading the old, deleted copy until it restarts. Readers see either the old
  # or the new database, never a mix; an interrupted run rolls back to the old one.
  sqlite3 "$DATA/stats.db" ".timeout 60000" ".restore '$NEW'" || die "could not replace stats.db"
  [ "$(sqlite3 "$DATA/stats.db" 'PRAGMA integrity_check;')" = "ok" ] || die "stats.db failed integrity_check after replace"
else
  mv -f "$NEW" "$DATA/stats.db"   # first run: same filesystem, so rename(2) is atomic
fi

# Keep the newest $KEEP_BACKUPS dumps (names sort by date).
printf '%s\n' "$BACKUPS"/cycler-stats-*.sql.gz | sort -r | tail -n +$((KEEP_BACKUPS + 1)) |
  while IFS= read -r old; do rm -f "$old"; done

log "done: $REPORTS reports, $NEW_ROWS rows total (was $OLD_ROWS) -> $DATA/stats.db"

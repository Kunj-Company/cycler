#!/usr/bin/env bash
# Deletes every stats row for one install ID from the live D1 database.
#
#   scripts/delete-install.sh <stats-id> [--yes]
#
# The stats ID is the UUID the user copies from Settings -> Privacy in the app.
# Area rows (location cells) carry no install ID, so they cannot be linked to a person
# and are not (and cannot be) deleted per install.
# Auth: an existing `wrangler login`, or CLOUDFLARE_API_TOKEN + CLOUDFLARE_ACCOUNT_ID
# (read from exporter/.env if present; the token needs D1 Edit).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB_NAME="cycler-stats"

usage() { echo "usage: $0 <install-uuid> [--yes]" >&2; exit 2; }
[ $# -ge 1 ] || usage
ID="$(printf '%s' "$1" | tr 'A-F' 'a-f')"
YES="${2:-}"
[[ "$ID" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] || {
  echo "not a valid v4 UUID: $1" >&2
  exit 2
}

if [ -f "$HERE/exporter/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$HERE/exporter/.env"
  set +a
fi
[ -n "${CLOUDFLARE_API_TOKEN:-}" ] || unset CLOUDFLARE_API_TOKEN

if [ -x "$HERE/node_modules/.bin/wrangler" ]; then
  WRANGLER=("$HERE/node_modules/.bin/wrangler")
else
  WRANGLER=(npx --yes wrangler@4.168.0)
fi

# $ID is validated above (hex and dashes only), so inlining it in SQL is safe.
d1() {
  (cd "$HERE" && WRANGLER_SEND_METRICS=false "${WRANGLER[@]}" d1 execute "$DB_NAME" --remote --json --command "$1") 2>/dev/null
}

counts() {
  d1 "SELECT (SELECT COUNT(*) FROM reports WHERE install = '$ID') AS reports,
             (SELECT COUNT(*) FROM features WHERE install = '$ID') AS features;" |
    node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const r=JSON.parse(s)[0].results[0];console.log(`reports=${r.reports} features=${r.features}`)})'
}

echo "install $ID"
BEFORE="$(counts)" || { echo "query failed (check auth: wrangler login or exporter/.env)" >&2; exit 1; }
echo "before: $BEFORE"
if [ "$BEFORE" = "reports=0 features=0" ]; then
  echo "nothing to delete"
  exit 0
fi

if [ "$YES" != "--yes" ]; then
  read -r -p "Delete these rows from the live database? [y/N] " answer
  [ "$answer" = "y" ] || [ "$answer" = "Y" ] || { echo "aborted"; exit 1; }
fi

d1 "DELETE FROM features WHERE install = '$ID'; DELETE FROM reports WHERE install = '$ID';" >/dev/null
echo "after:  $(counts)"
echo "Done. The laptop copy updates on the next exporter run (older backups in exporter/data/backups"
echo "still contain the rows until they rotate out after 60 days; delete those files too if asked)."

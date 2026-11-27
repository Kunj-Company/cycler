#!/usr/bin/env bash
# One-time Metabase setup through its API:
#   1. creates the admin user (only if Metabase has never been set up), otherwise logs in;
#   2. adds the "Cycler stats" SQLite database (/cycler/stats.db inside the container)
#      if it is not there yet.
#
#   docker compose up -d && ./setup.sh
#
# Env (optional): MB_URL (default http://localhost:3000), MB_EMAIL, MB_PASSWORD,
#                 MB_FIRST_NAME, MB_LAST_NAME. You are prompted for anything missing.
# Needs: bash, curl, node.
set -euo pipefail

MB_URL="${MB_URL:-http://localhost:3000}"
DB_LABEL="Cycler stats"
DB_PATH="/cycler/stats.db"

json() { node -e "$1"; }   # tiny JSON helper (node is already needed for wrangler)
get_field() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const v=JSON.parse(s)[process.argv[1]];process.stdout.write(v==null?"":String(v))})' "$1"; }

echo "Waiting for Metabase at $MB_URL ..."
for i in $(seq 1 120); do
  if curl -fsS "$MB_URL/api/health" 2>/dev/null | grep -q '"ok"'; then break; fi
  [ "$i" = 120 ] && { echo "Metabase did not become healthy (docker compose logs metabase)" >&2; exit 1; }
  sleep 5
done

PROPS="$(curl -fsS "$MB_URL/api/session/properties")"
HAS_USER="$(printf '%s' "$PROPS" | get_field has-user-setup)"
SETUP_TOKEN="$(printf '%s' "$PROPS" | get_field setup-token)"

if [ -z "${MB_EMAIL:-}" ]; then read -r -p "Admin email: " MB_EMAIL; fi
if [ -z "${MB_PASSWORD:-}" ]; then read -r -s -p "Admin password: " MB_PASSWORD; echo; fi
export MB_EMAIL MB_PASSWORD MB_FIRST_NAME="${MB_FIRST_NAME:-Cycler}" MB_LAST_NAME="${MB_LAST_NAME:-Admin}" SETUP_TOKEN DB_LABEL DB_PATH

if [ "$HAS_USER" != "true" ]; then
  echo "Creating admin user $MB_EMAIL"
  BODY="$(json 'const e=process.env;console.log(JSON.stringify({token:e.SETUP_TOKEN,
    user:{first_name:e.MB_FIRST_NAME,last_name:e.MB_LAST_NAME,email:e.MB_EMAIL,password:e.MB_PASSWORD,site_name:"Cycler stats"},
    prefs:{site_name:"Cycler stats",site_locale:"en",allow_tracking:false}}))')"
  SESSION="$(curl -fsS -X POST -H 'content-type: application/json' --data-binary @- "$MB_URL/api/setup" <<<"$BODY" | get_field id)"
else
  echo "Metabase already set up; logging in as $MB_EMAIL"
  BODY="$(json 'console.log(JSON.stringify({username:process.env.MB_EMAIL,password:process.env.MB_PASSWORD}))')"
  SESSION="$(curl -fsS -X POST -H 'content-type: application/json' --data-binary @- "$MB_URL/api/session" <<<"$BODY" | get_field id)"
fi
[ -n "$SESSION" ] || { echo "could not get a Metabase session" >&2; exit 1; }
AUTH=(-H "X-Metabase-Session: $SESSION")

EXISTING="$(curl -fsS "${AUTH[@]}" "$MB_URL/api/database" |
  node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s);const l=Array.isArray(j)?j:j.data;const d=l.find(x=>x.engine==="sqlite"&&x.details&&x.details.db===process.env.DB_PATH);process.stdout.write(d?String(d.id):"")})')"

if [ -n "$EXISTING" ]; then
  echo "Database already added (id $EXISTING)"
  DB_ID="$EXISTING"
else
  echo "Adding SQLite database $DB_PATH as \"$DB_LABEL\""
  BODY="$(json 'console.log(JSON.stringify({engine:"sqlite",name:process.env.DB_LABEL,details:{db:process.env.DB_PATH},is_full_sync:true}))')"
  DB_ID="$(curl -fsS -X POST "${AUTH[@]}" -H 'content-type: application/json' --data-binary @- "$MB_URL/api/database" <<<"$BODY" | get_field id)"
  [ -n "$DB_ID" ] || { echo "adding the database failed (does exporter/data/stats.db exist? run exporter/pull.sh first)" >&2; exit 1; }
  echo "Added (id $DB_ID)"
fi

# Quick proof that Metabase can read the file.
COUNT="$(curl -fsS -X POST "${AUTH[@]}" -H 'content-type: application/json' \
  --data "{\"database\":$DB_ID,\"type\":\"native\",\"native\":{\"query\":\"SELECT COUNT(*) FROM reports\"}}" \
  "$MB_URL/api/dataset" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s);process.stdout.write(j.data&&j.data.rows?String(j.data.rows[0][0]):("error: "+(j.error||"unknown")))})')"
echo "Metabase reads stats.db: reports rows = $COUNT"
echo "Open $MB_URL and paste questions from metabase/queries.sql (+ New -> SQL query)."

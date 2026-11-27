# Anonymous usage stats

Cycler can send a small weekly summary of how the app is used. It helps us see how many
people use Cycler, which features matter, where things break, and roughly which parts of
the world people ride in. Your rides, routes and photos are never sent.

This page lists everything that is sent. It follows the data contract for the stats
feature (see "For contributors" below).

## In short

- On by default. The first time you open the app (or the first time after the update that
  adds this), a one-time notice tells you about it. Tap **Continue** to go on.
- Turn it off any time: **Settings → Privacy → Share anonymous stats**.
- Once a week at most, for weeks that are over. Never live, never during a ride.
- Counts and kilometre totals only. No routes, no exact positions, no place names, no
  exact times.
- A random stats ID links your weekly reports together. It is made on your phone and is
  not tied to your name, your account (there is none), your phone or your Google account.
- To have your stats deleted, email the stats ID shown in **Settings → Privacy** to
  hellokunjcompany@gmail.com.

## When it is sent

The app keeps counters for the current week on your phone. Once a day, when the phone has
internet, Android runs a small background job that sends the reports for weeks that are
already over (by your phone's local calendar). The current week is never sent.

If sending fails, the app tries again the next day. Reports older than 8 weeks that could
not be sent are dropped.

Each finished week can produce two separate reports: the **weekly report** and the
**area report**. They are sent as two separate requests, the area report first, each after
its own random delay.

## The weekly report

One per install per week. Sending the same week again replaces it; it does not count twice.

```json
{
  "schema": 1,
  "install": "3f0c6a1e-8d3b-4c55-9a7e-2a1f0b6c9d10",
  "app_version": "0.1.13",
  "week": "2026-W48",
  "days_active": 3,
  "rides_started": 4,
  "rides_finished": 3,
  "rides_discarded": 1,
  "km": 42.7,
  "features": { "export_gpx": 2, "share_video": 1 }
}
```

| Field | Meaning |
|---|---|
| `schema` | Format version of the report, now 1 |
| `install` | Your stats ID: a random ID (UUID v4) made on your phone the first time. It stays the same if you turn stats off and on again. It is removed when you uninstall the app or clear its data |
| `app_version` | Cycler version, for example `0.1.13`. Debug builds end in `-debug` |
| `week` | The ISO week, for example `2026-W48`. This is the finest time detail sent |
| `days_active` | On how many days of that week (0 to 7) the app was opened |
| `rides_started` | How many rides were started |
| `rides_finished` | How many rides were saved |
| `rides_discarded` | How many rides were discarded |
| `km` | Total distance of the saved rides, to one decimal |
| `features` | How many times each feature below was used that week. Features you did not use are left out |

### Feature keys

| Key | Counted when you |
|---|---|
| `export_gpx` | export a ride as GPX |
| `export_tcx` | export a ride as TCX |
| `export_fit` | export a ride as FIT |
| `share_video` | share a ride as a flyover video |
| `share_cards` | share a ride as a card |
| `share_file` | share an exported file |
| `plan_a_to_b` | plan an A-to-B route |
| `plan_loop` | plan a loop |
| `route_suggest` | ask for route suggestions near you |
| `route_saved` | save a route |
| `route_import_gpx` | import a route from a GPX file |
| `search` | search for a place (only the fact that you searched, never the text) |
| `lap` | mark a lap |
| `pause` | pause a ride |
| `workout` | ride an interval workout |
| `big_numbers` | use the big-number ride screen |
| `widget_ride` | use the Ride widget |
| `widget_stats` | use the Riding stats widget |
| `widget_week` | use the This week widget |
| `widget_last_ride` | use the Last ride widget |
| `offline_map_download` | save a map for offline use |
| `backup_export` | make a backup |
| `backup_import` | restore a backup |
| `health_connect_connect` | connect Health Connect |
| `bike_care` | use bike care |
| `bike_added` | add a bike |
| `segments_opened` | open segments |
| `map_load_failed` | (app) the map failed to load |
| `routing_failed` | (app) route planning failed |
| `ride_recovered` | (app) a ride was recovered after Android stopped the app |

The last three are counted by the app when something goes wrong, not by something you do.

## The area report

One per week, only if you saved at least one ride that week. It says roughly where rides
started and ended, as squares of about 10 km.

```json
{
  "schema": 1,
  "report": "b7e4c2d0-5a1f-4f3e-9c6b-0d2e8a7f1b34",
  "app_version": "0.1.13",
  "week": "2026-W48",
  "cells": [
    { "lat": 12.9, "lon": 77.5, "kind": "start", "rides": 3, "km": 40.1 },
    { "lat": 12.9, "lon": 77.6, "kind": "end",   "rides": 3, "km": 40.1 }
  ]
}
```

| Field | Meaning |
|---|---|
| `schema` | Format version, now 1 |
| `report` | A new random ID for this one report, used only so a retry is not counted twice. It is not your stats ID |
| `app_version` | Cycler version |
| `week` | The ISO week |
| `cells` | Up to 50 squares |
| `lat`, `lon` | The south-west corner of the square: latitude and longitude rounded down to one decimal (0.1°, about 11 km) |
| `kind` | `start` or `end`: whether rides started or ended in that square |
| `rides` | How many saved rides started or ended there |
| `km` | Total distance of those rides |

Only saved rides count. Discarded rides are not included.

### How the area report is kept apart from you

The area report is built so it cannot be tied back to your weekly report or your stats ID:

- It has no stats ID. Its `report` ID is new each time.
- It is sent as a separate request, before the weekly report, with its own random delay.
- On the server, area data is stored without the time it arrived and in a table that does
  not keep insertion order, so the order of arrival cannot be used to match it to a weekly
  report.

Because nothing ties it to you, we also cannot find your area reports to delete them.

## What is never sent

- Your IP address is not stored (see below).
- Android ID, advertising ID, phone model.
- Exact positions, GPS tracks, routes or waypoints.
- Place names or search text.
- Ride names, notes, tags, photos or videos.
- Speed, heart rate, weight or anything from Health Connect.
- Exact dates or times. A week is the smallest unit.

## Where it goes

Reports go over HTTPS to a small server run by Kunj Company on Cloudflare: a Cloudflare
Worker that checks each report and writes it to a Cloudflare D1 database, which lives in
Cloudflare's network. The server code is in [`stats-server/`](../stats-server) in this repo.
The owner also keeps a daily copy of the database on their own computer to analyse it.

### About your IP address

To deliver any request over the internet, Cloudflare's network has to see the IP address it
comes from. That cannot be avoided. The Cloudflare Worker does not read the IP address and
does not store it, or any request headers or user agent. Worker logging is turned off.

## How long it is kept

Stats are kept until you ask us to delete them.

## Turning it off

Open **Settings → Privacy** and turn off **Share anonymous stats**. The app stops counting
and deletes any reports it has not sent yet. Reports already sent stay on the server until
you ask us to delete them.

If you turn it back on later, the app keeps the same stats ID.

## Deleting your stats

1. Open **Settings → Privacy**. Your stats ID is shown under the switch; tap to copy it.
2. Email it to hellokunjcompany@gmail.com and ask for your stats to be deleted.

We delete every weekly report for that ID. There is no delete button in the app. Write down
or copy the ID before you uninstall: once the app is gone, the ID is gone too, and we have
no other way to find your reports. Area reports have no ID and cannot be found or deleted
per person.

## Builds without stats

The server address is set when the app is built (the Gradle property `statsUrl`). If it is
empty, the feature is compiled off and nothing is ever sent.

## For contributors

Everything the app may send is fixed by the data contract. If you add a feature key, a
field or a new report:

- add it to this file, with a one-line meaning, in the same change. The contract lives in
  this file, in the `Feature` list in `app/src/main/java/com/cycler/app/analytics/UsageStats.kt`
  and in the server's checks in `stats-server/src/validate.ts`. New feature keys need no
  server change; new fields or reports do;
- keep to the rules above: counts and totals only, a week as the smallest time unit, no
  exact positions, no text the user typed, nothing that identifies a person or a phone;
- also update the Privacy section in `README.md`, [`PRIVACY.md`](PRIVACY.md) and
  [`play/DATA_SAFETY.md`](play/DATA_SAFETY.md) if the kind of data changes.

Feature keys must match `^[a-z][a-z0-9_]{1,39}$`; a report can carry at most 64 of them. The
server accepts new keys without a change, so the documentation is the only place a new key
gets reviewed. Don't skip it.

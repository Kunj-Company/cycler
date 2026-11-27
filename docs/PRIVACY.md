# Cycler privacy policy

Last updated: 2026-11-27. Earlier versions are in the file history on GitHub.

Cycler is a cycling app for Android. It records rides with GPS, plans routes and shows
your history. This page says what happens to your data.

## Who is responsible

Kunj Company, contact: hellokunjcompany@gmail.com

## In short

- There is no account and there are no ads.
- Your rides, photos and settings stay on your phone.
- Cycler sends anonymous usage stats once a week: counts, kilometre totals and rough
  areas of about 10 km. It is on by default, you are told on first launch, and you can turn
  it off in Settings → Privacy.
- Nothing else leaves the phone except the online requests listed below.
- A backup is a file you save yourself.

## What stays on your phone

Rides, GPS points, routes, photos and videos you attach, notes, tags, segments, bike care
and settings are stored in the app's own storage. The developer cannot see them.
Android cloud backup is turned off for Cycler.

## What is sent over the internet

All requests use HTTPS. Apart from the stats server, they go to free community services.
None needs an API key or an account.

| What | Sent to | When | What is sent |
|---|---|---|---|
| Map tiles | OpenFreeMap, CyclOSM | Whenever a map is shown or saved for offline use | The tiles you view, so the provider sees the area |
| Weather | Open-Meteo | Whenever the app has a position, at most every 15 minutes | Your position rounded to about 100 m (3 decimals) |
| Route planning | BRouter (brouter.de) | When you plan a route or loop, or ask for suggestions | The waypoints you route between (6 decimals). For a loop, your current position is the start |
| Place search | Photon (photon.komoot.io) | When you search | The text you type, and your position rounded to about 1 km (2 decimals) |
| Traffic signals | Overpass API (overpass-api.de) | When you open or follow a ride or route | A bounding box around the route |
| Usage stats | Cycler's stats server (Cloudflare) | Once a day at most, for weeks that are over, if stats are on | A weekly report with a random stats ID, and a separate area report with no ID. See below |

The community services receive your IP address, as any web server does, and have their own
policies. Cycler sends them nothing else and does not add any identifier.

## Anonymous usage stats

Cycler sends a small weekly summary of how the app is used, so we can see how many people
use it, which features matter, where things break and roughly where people ride. The full
list of what is sent, with examples, is in [ANALYTICS.md](ANALYTICS.md).

- **Weekly report:** a random stats ID made on your phone, the app version, the week (for
  example 2026-W48), on how many days the app was opened, how many rides were started,
  saved and discarded, the total kilometres, and how often each feature was used.
- **Area report:** for weeks with a saved ride, the squares of about 10 km (latitude and
  longitude rounded down to 0.1°) where rides started and ended, with ride counts and
  kilometres. It has no stats ID and is sent separately, so it cannot be tied to the weekly
  report.
- **Never sent:** exact positions, routes, place names, search text, ride names, notes or
  photos, speed, heart rate, anything from Health Connect, Android ID, advertising ID,
  phone model, or exact dates and times.

**When:** only for weeks that are over. A daily background job sends them when the phone
has internet.

**Where:** a Cloudflare Worker run by Kunj Company stores the reports in a Cloudflare D1
database, in Cloudflare's network. The developer keeps a daily copy on their own computer
for analysis. The server code is public, in the `stats-server/` folder of the repository.

**Your IP address:** Cloudflare's network sees it, because it has to deliver the request.
The worker does not read it or store it, and its logging is turned off.

**On by default:** the first launch (or the first launch after the update that adds stats)
shows a one-time notice. You can turn stats off at any time in **Settings → Privacy →
Share anonymous stats**. The app then stops counting and deletes reports it has not sent.

**Deletion:** your stats ID is shown in Settings → Privacy. Email it to
hellokunjcompany@gmail.com and we delete the weekly reports for that ID. Area reports have
no ID, so they cannot be found or deleted per person.

**Legal basis:** we use these stats to understand and improve the app. The stats ID is not
linked to your name or phone, but it does link one install's weekly reports, so we treat it
as personal data. Collection is on by default, you are told about it before anything is
sent, it is easy to turn off, and you can ask us to delete what was sent.

## Location

Cycler uses your precise location to show you on the map and record rides. Recording can
continue with the screen off and shows a notification while it runs. Location is not sent
anywhere except as described above. Usage stats only include rough areas of about 10 km
where saved rides started and ended, never exact positions or routes.

## Health Connect

If you choose to connect Health Connect, Cycler reads heart rate and weight, only to show
heart rate on ride pages and to estimate energy. Access is read only: Cycler never writes
to Health Connect. What it reads stays on your phone and is not shared with anyone.
To revoke access, open Android Settings, then Health Connect, then App permissions,
then Cycler, and turn off Heart rate and Weight (or remove the app's access). You can
also revoke it from Cycler's Settings.

## Backups and photos

Back up everything creates a zip file that you save where you choose. It holds rides,
routes, photos, segments, bike care and settings. Photos keep their original metadata
(EXIF), including where they were taken, because it is not removed. Keep the file as
private as the photos themselves. Restore reads such a file back in.

## Sharing and exports

Exports (FIT, TCX, GPX), share cards and videos are created on your phone and go only where
you send them with Android's share sheet. Many apps import FIT files.

## How long data is kept

Data stays on your phone until you delete a ride or uninstall the app. Uninstalling
removes everything Cycler stored, except files you saved yourself. The developer holds no
copy of your rides, photos or settings.

Usage stats are kept until you ask us to delete them. Uninstalling the app does not delete
stats already sent, and it removes the stats ID from your phone. Copy the ID from Settings →
Privacy first if you want them deleted.

## Children

Cycler is not directed at children under 13 and does not knowingly collect their data.

## Changes

If this policy changes, the new text is published at this address and the date of the
change appears at the top and in the file history. The in-app Privacy page (About, then
Privacy) lists the same requests.

## Contact

Kunj Company, hellokunjcompany@gmail.com

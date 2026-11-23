# Cycler privacy policy

Last updated: see the file history on GitHub.

Cycler is a cycling app for Android. It records rides with GPS, plans routes and shows
your history. This page says what happens to your data.

## Who is responsible

Kunj Company, contact: hellokunjcompany@gmail.com

## In short

- There is no account, no analytics, no ads and no tracking.
- Your rides, photos and settings stay on your phone.
- Nothing leaves the phone except the online requests listed below.
- A backup is a file you save yourself.

## What stays on your phone

Rides, GPS points, routes, photos and videos you attach, notes, tags, segments, bike care
and settings are stored in the app's own storage. The developer cannot see them.
Android cloud backup is turned off for Cycler.

## What is sent over the internet

All requests use HTTPS to free community services. None needs an API key or an account.

| What | Sent to | When | What is sent |
|---|---|---|---|
| Map tiles | OpenFreeMap, CyclOSM | Whenever a map is shown or saved for offline use | The tiles you view, so the provider sees the area |
| Weather | Open-Meteo | Whenever the app has a position, at most every 15 minutes | Your position rounded to about 100 m (3 decimals) |
| Route planning | BRouter (brouter.de) | When you plan a route or loop, or ask for suggestions | The waypoints you route between (6 decimals). For a loop, your current position is the start |
| Place search | Photon (photon.komoot.io) | When you search | The text you type, and your position rounded to about 1 km (2 decimals) |
| Traffic signals | Overpass API (overpass-api.de) | When you open or follow a ride or route | A bounding box around the route |

These services receive your IP address, as any web server does, and have their own
policies. Cycler sends them nothing else and does not add any identifier.

## Location

Cycler uses your precise location to show you on the map and record rides. Recording can
continue with the screen off and shows a notification while it runs. Location is not sent
anywhere except as described above.

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
copy and so has nothing to delete on your behalf.

## Children

Cycler is not directed at children under 13 and does not knowingly collect their data.

## Changes

If this policy changes, the new text is published at this address and the date of the
change appears in the file history. The in-app Privacy page (About, then Privacy) lists the
same requests.

## Contact

Kunj Company, hellokunjcompany@gmail.com

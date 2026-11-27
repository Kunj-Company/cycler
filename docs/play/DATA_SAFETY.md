# Google Play Data safety: draft answers

Status: **draft**, written 2026-11-27 for the version that adds anonymous usage stats.
Check every answer against the Play Console form before submitting; its wording changes.

This covers the whole app, not only stats. Sources for what the app does:
[PRIVACY.md](../PRIVACY.md), [ANALYTICS.md](../ANALYTICS.md), the requests in
`app/src/main/java/com/cycler/app/net/Net.kt`, the map tile URLs in the map code, and the
permissions in `app/src/main/AndroidManifest.xml`.

Google's pages used (read on 2026-11-27):

- Data safety form guide: https://support.google.com/googleplay/android-developer/answer/10787469
- User Data policy (Prominent Disclosure & Consent): https://support.google.com/googleplay/android-developer/answer/10144311
- Prominent disclosure best practices: https://support.google.com/googleplay/android-developer/answer/11150561

Points marked **[check]** are judgement calls or things I could not confirm on those pages.

## Definitions that matter here

From the Data safety guide (answer/10787469):

- **Collect:** "transmitting data from your app off a user's device". This includes data sent
  to third-party servers, not only to us. Processing on the phone is not collection.
- **Share:** "transferring user data collected from your app to a third party". Not sharing:
  transfers to a service provider that processes data on the developer's behalf, transfers
  based on a specific user-initiated action, legal requests, and fully anonymised data.
- **Ephemeral:** data used only in memory and "retained for no longer than necessary to
  service the specific request in real-time". It must still be declared in the form, but is
  not shown on the store page.
- **Approximate location:** an area of 3 km² or more. **Precise location:** less than 3 km².
- **Optional:** "'Optional' includes the ability to opt into or opt out of data collection",
  and only if all users, in every region, can do so and still use the app.
- Pseudonymous data must be disclosed. Only fully anonymised data is exempt.

## What leaves the phone

| Request | Goes to | Data | Notes |
|---|---|---|---|
| Map tiles | OpenFreeMap, CyclOSM | Tile coordinates of the area on screen | Reveals the area being viewed |
| Weather | Open-Meteo | Position to 3 decimals (about 100 m) | Automatic while the app has a position |
| Route planning | BRouter | Waypoints to 6 decimals; current position for loops | When the user plans |
| Place search | Photon (komoot) | Search text; position to 2 decimals (about 1 km) | When the user searches |
| Traffic signals | Overpass API | Bounding box of the route | When a ride or route is opened or followed |
| Usage stats, weekly report | Our Cloudflare Worker + D1 | Random install ID, app version, ISO week, days active, ride counts, km total, feature counts | On by default, can be turned off |
| Usage stats, area report | Our Cloudflare Worker + D1 | ~11 km squares (0.1°) where saved rides started and ended, ride counts, km | No install ID; on by default, can be turned off |

All of these use HTTPS. The `http://` strings in the code are XML namespaces in GPX and TCX
files, not requests.

Not collected (stays on the phone): rides and GPS tracks, photos and videos, notes, tags,
bike care, settings, Health Connect heart rate and weight. Backups and exports go only
where the user sends them with the share sheet; that is the user's action, not collection
by us.

Each third-party service receives the IP address, as any server does. Our stats worker
does not read or store it, and logging is off. We do not infer location from IP.

## Section 1: Data collection and security

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (HTTPS for every request) |
| Do you provide a way for users to request that their data is deleted? | **Yes**: email hellokunjcompany@gmail.com with the stats ID shown in Settings → Privacy. Other data is not stored by us |

Account creation: the app has no accounts, so the account deletion URL requirement does not
apply. **[check]** the form still asks; answer "My app does not allow users to create an
account".

## Section 2: Data types

| Category → type | Collected | Shared | Ephemeral | Required / optional | Purpose |
|---|---|---|---|---|---|
| Location → Approximate location | Yes (stats area report, 0.1° squares, about 11 × 11 km at the equator) | No | No | Optional | Analytics |
| Location → Precise location | Yes (weather ~100 m, routing waypoints, search ~1 km) **[check]** | No **[check]** | Yes **[check]** | Required | App functionality |
| Device or other IDs → Device or other IDs | Yes (random install UUID for stats) | No | No | Optional | Analytics |
| App activity → App interactions | Yes (days active, rides started/saved/discarded, feature counts) | No | No | Optional | Analytics |
| App activity → In-app search history | Yes (search text sent to Photon) **[check]** | No **[check]** | Yes **[check]** | Optional (only when the user searches) | App functionality |
| Health and fitness → Fitness info | Yes (weekly km total; km per area square) | No | No | Optional | Analytics |
| App info and performance → Diagnostics | Maybe: counts of `map_load_failed`, `routing_failed`, `ride_recovered` **[check]** | No | No | Optional | Analytics |

Not collected: Personal info (name, email, etc.), Financial info, Health info (heart rate and
weight are read from Health Connect and stay on the phone), Messages, Photos and videos,
Audio, Files and docs, Calendar, Contacts, Web browsing, Crash logs.

### Notes on the rows

**Approximate location.** A 0.1° square is about 123 km² at the equator and smaller toward
the poles, but always far above 3 km² in places people ride, so "Approximate" fits.

**Precise location (third-party services).** Weather (3 decimals) and routing (6 decimals)
are under 3 km². Search at 2 decimals is about 1.2 km² at the equator, also under 3 km².
The app sends these straight to third-party servers; the guide counts data sent to
third-party servers as collected. Two things to decide:

- *Shared?* We do not pass on data we hold; the phone sends it straight to the service.
  The service-provider and user-initiated exemptions seem to fit (routing and search are
  user-initiated; weather is a service the app uses on our behalf). **[check]**: the
  public services have no contract with us, which may weaken the service-provider reading.
  If in doubt, declaring "Shared" for App functionality is the safer answer.
- *Ephemeral?* We cannot promise what Open-Meteo, BRouter or Photon keep. Marking
  "processed ephemerally" is about our own handling. **[check]** If unsure, answer No.

**Required vs optional for location.** Recording and maps need location, so precise
location is Required for App functionality. Approximate location for stats is Optional.
One data type can carry several purposes; **[check]** how the form combines "required" for
one purpose with "optional" for another (it may ask once per type, in which case answer
Required).

**Device or other IDs.** The examples in the guide include a "Firebase installation ID",
which is the same kind of thing as our random install UUID. It is pseudonymous, not
anonymous, so it must be declared.

**Fitness info.** Described as "information about a user's fitness, such as exercise or
other physical activity". Weekly kilometres ridden fits.

**Diagnostics.** The three failure counters are about app performance. Declaring
Diagnostics → Analytics is the cautious choice. **[check]**

**Optional (stats).** Stats are on by default, but every user can turn them off in
Settings → Privacy and keep using the whole app. The guide says optional "includes the
ability to opt into or opt out of data collection", so on-by-default with an off switch
reads as Optional. **[check]** against the current form text; if the form now asks
whether the user must take action before collection starts, answer accordingly.

## Section 3: Purposes

- **Analytics:** "Used to collect data about how users use the app or how it performs."
  All stats data.
- **App functionality:** weather, routing, search, map tiles, traffic signals.

Not used for: Developer communications, Advertising or marketing, Fraud prevention,
security and compliance, Personalization, Account management.

## Prominent disclosure and consent

The User Data policy (answer/10144311) requires an in-app disclosure and consent when data
use "may not be within the reasonable expectation of the user". Sending rough ride areas
for analytics, on by default, is a case a reviewer could see that way.

The first-launch notice is meant to satisfy this. Policy points to check it against:

- It must be in the app, in normal use, not only in the store listing or privacy policy.
  The notice does this.
- It must describe the data and how it is used, and must not be bundled with unrelated
  disclosures. **[check]** the notice text names the data (counts, km, ~11 km areas, random
  ID), and is its own screen.
- Consent "must require affirmative user action (for example, tap to accept, tick a
  check-box)", must not be read from tapping away or pressing back or home, and must be
  given "before your app can begin to collect or access" the data.
- The best-practices page asks for at least two choices, one to accept and one to decline.

**[check] Main risk:** the owner's design is a single **Continue** button plus a line about
Settings → Privacy. That may be read as an acknowledgement rather than consent, and there
is no decline option on the notice itself. Also check that no counting or sending starts
before Continue is tapped, and that back or home on the notice does not count as Continue.
If Play rejects it, the smallest change is a second button ("Turn off") on the same notice.

Background location: recording uses a foreground service with a notification, not the
background location permission, so that separate disclosure should not apply. **[check]**
against the current manifest and Play's location permissions declaration.

## Other Play items related to this

- Privacy policy URL: https://github.com/Kunj-Company/cycler/blob/main/docs/PRIVACY.md
  (already linked from the app).
- Health Connect: the app reads heart rate and weight. Play has a separate Health Connect
  permissions declaration; that data is not transmitted, so it is not in the Data safety
  form as collected. **[check]** the Health Connect declaration is filled in.
- When the stats feature changes, update this file together with ANALYTICS.md.

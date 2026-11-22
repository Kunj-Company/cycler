# Third-party notices

Cycler's own code is under the MIT licence (see `LICENSE`). It is built with, and
ships, the work below, each under its own licence.

## Libraries

| Component | Licence |
| --- | --- |
| MapLibre Native for Android | BSD 2-Clause |
| AndroidX, Jetpack Compose, Material 3 | Apache 2.0 |
| Health Connect client | Apache 2.0 |
| Kotlin, kotlinx.coroutines | Apache 2.0 |

Test only, not shipped in the app: JUnit 4 (EPL 1.0), Robolectric (MIT),
Roborazzi (Apache 2.0).

## Font

Inter by Rasmus Andersson, bundled as `app/src/main/res/font/inter.ttf`, under the
SIL Open Font License 1.1. https://github.com/rsms/inter

## Data and services

The app shows and uses data from these services at run time. None of it is stored in
this repository.

- Map data © OpenStreetMap contributors, Open Database License. https://www.openstreetmap.org/copyright
- Map tiles and styles: OpenFreeMap (https://openfreemap.org) and CyclOSM (https://www.cyclosm.org)
- Routing and elevation: BRouter (https://brouter.de)
- Place search: Photon by Komoot (https://photon.komoot.io)
- Weather: Open-Meteo (https://open-meteo.com), CC BY 4.0
- Traffic signals: Overpass API (OpenStreetMap data)

These are shared community servers. If you fork Cycler for many users, run your own
or arrange access with the operators.

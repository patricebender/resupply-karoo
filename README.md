<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="extension/app/brand/resupply-wordmark-dark.svg">
  <img src="extension/app/brand/resupply-wordmark-light.svg" alt="Resupply" width="420">
</picture>

**for Karoo. Offline POIs along your route, no signal needed.**

</div>

A [Hammerhead Karoo](https://www.hammerhead.io/) extension that turns a loaded route into an
offline roadbook of resupply stops along the way (food, water, coffee, bike shops, fuel), so
you can plan refuels without cellular signal. No route loaded? It finds resupply around you,
live.

## Features

<div align="center">
<table width="100%">
  <tr>
    <td width="50%" valign="top" align="center">
      <img src="docs/media/01-instant-build.gif" alt="Building the roadbook in seconds" width="280">
    </td>
    <td width="50%" valign="top">
      <h3>⚡ Offline resupply along a route</h3>
      Finds resupply stops along the route you're riding, no signal needed. The whole POI
      database rides along inside the app, so the build runs on-device and returns in a second
      or two.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>⭐ Favorites to plan ahead</h3>
      Star the stops you're aiming for to plan the ride, then refine your roadbook down to
      just those.
    </td>
    <td width="50%" valign="top" align="center">
      <img src="docs/media/03-favorites.gif" alt="Starring favorites and filtering to them" width="280">
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top" align="center">
      <img src="docs/media/map-pois.png" alt="Typed POI pins along the route on the Karoo map" width="320">
    </td>
    <td width="50%" valign="top">
      <h3>🗺️ Pins always in sync</h3>
      Pins on the map always match your resupply selection — toggle categories or favorites to
      focus the map on what's relevant to you.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top" align="center">
      <img src="docs/media/04-live-mode.gif" alt="Live mode showing resupply around your location" width="280">
    </td>
    <td width="50%" valign="top">
      <h3>📍 Live resupply around you</h3>
      No route loaded? Resupply finds places around you and keeps them fresh as you move.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>🗂️ 12 categories</h3>
      Restaurants, Supermarkets, Café&nbsp;&amp;&nbsp;Bakery, Water, Toilets, Bike&nbsp;shops,
      Fuel&nbsp;stations, Ice&nbsp;Cream, Hotels, Pharmacies, ATMs, and Campgrounds. Toggle
      any of them on or off on the fly.
    </td>
    <td width="50%" valign="top" align="center">
      <img src="docs/media/categories.png" alt="Category toggles with per-category counts" width="280">
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top" align="center">
      <img src="docs/media/water.png" alt="Water source detail marked safe to refill" width="280">
    </td>
    <td width="50%" valign="top">
      <h3>💧 Safe water sources</h3>
      Shows drinkable water by default, hiding fountains and springs of unknown quality unless
      you want them.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top" align="center">
      <img src="docs/media/datafield.png" alt="In-ride data field showing upcoming water" width="280">
    </td>
    <td width="50%" valign="top">
      <h3>📊 Data fields for the ride view</h3>
      Put upcoming resupply, your favorites, or any single category straight on your data
      pages: distance ahead, detour, and the next stops.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>🔎 Place detail at a glance</h3>
      Opening hours, distance and detour, address and phone, a scannable Google&nbsp;Maps /
      website QR, and a short description for each stop.
    </td>
    <td width="50%" valign="top" align="center">
      <img src="docs/media/details.png" alt="Place detail with opening hours" width="140">
      <img src="docs/media/qr.png" alt="Scannable QR to open the place on your phone" width="140">
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top" align="center">
      <img src="docs/media/settings.png" alt="Settings: safe water, smart radius, appearance" width="280">
    </td>
    <td width="50%" valign="top">
      <h3>🎯 Smart search radius &amp; themes</h3>
      Searches tight in busy areas and reaches further where places are sparse (or set a fixed
      radius yourself), plus light&nbsp;/&nbsp;dark&nbsp;/&nbsp;auto appearance.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>🌍 Regional coverage</h3>
      Download the regions you ride, right on the device. Europe, the USA, and Canada are
      supported for now.
    </td>
    <td width="50%" valign="top" align="center">
      <img src="docs/media/02-regions-download.gif" alt="Browsing and downloading a region in-app" width="280">
    </td>
  </tr>
</table>
</div>

## How it works

The **`extension/`** directory is the whole app: an on-device Karoo app (Kotlin,
[karoo-ext](https://github.com/hammerheadnav/karoo-ext) SDK). It reads the loaded route, lets
you set a detour radius and category toggles, and queries the **spatial SQLite database
bundled with the app** (an R*Tree corridor search refined against the route line) to find
POIs. No server, fully offline. With no route loaded, the same search runs around your
current location instead. Results render as typed map pins and in the Waybook list, ordered by
distance along the route.

A rider can pick a detour distance from tight 100/250 m up to 5 km, and rebuild either from an
in-app Build button or an in-ride `BonusAction`. Density is capped adaptively so a city doesn't
flood the map while a quiet stretch still shows what's there. The only network calls are the
optional, on-demand Google Places and Wikipedia lookups; they route device to provider through
the Karoo HTTP bridge, so they work over a paired phone, not just WiFi.

POI data comes from [OpenStreetMap](https://www.openstreetmap.org/). The per-region databases
are generated offline by **`extension/tools/poi-db/`** (downloads a regional extract, filters
to our POI tags, loads a spatial SQLite). Nothing ships inside the app: on first run the rider
picks regions from the region picker, which fetches a compact per-region file (a single
Bundesland, or whole countries) and installs it on-device.

See [FOUNDATION.md](FOUNDATION.md) for the full architecture and design.

## Installing

### Karoo 3

Grab the latest APK from [Releases](../../releases) and install it via the Hammerhead Companion
app (paste the release APK URL). For development, use USB: `adb install` or
`./gradlew :app:installDebug`.

### Karoo 2

The app runs on a Karoo 2 (it targets the karoo-ext SDK, which Hammerhead supports on both the
Karoo 2 and Karoo 3), but the Companion-app install path is Karoo 3 only. On a Karoo 2, sideload
the APK over USB with `adb install path/to/resupply-vX.Y.Z.apk` (download it from
[Releases](../../releases)). The Extensions library folder only appears once a third-party app
is installed.

## Building

### Extension

Requires JDK 17 and the Android SDK. karoo-ext is on GitHub Packages (auth required even
though it's public). Put credentials in `~/.gradle/gradle.properties`:

```properties
gpr.user=<your-github-username>
gpr.key=<a-PAT-with-read:packages>
```

```sh
cd extension
./gradlew :app:assembleDebug
```

The Google Places hours fallback needs a `PLACES_API_KEY` at build time; without it, that
feature is simply hidden.

### Refreshing the POI database

The bundled POI database is built offline (needs
[osmium-tool](https://osmcode.org/osmium-tool/)):

```sh
cd extension/tools/poi-db
npm install
# Build the per-region files the in-app picker downloads
npm run build:regions
# Or build a single region for a quick test:
OSM_REGION=europe/germany/baden-wuerttemberg npm run build:poi-db
```

See [`extension/tools/poi-db/README.md`](extension/tools/poi-db/README.md) for options
(coverage, per-region files, forced re-download, bumping the DB version).

## Releases

The extension is versioned with [release-please](https://github.com/googleapis/release-please)
and Conventional Commits. `feat:`/`fix:` commits touching `extension/**` on `main` keep a
Release PR updated; merging it cuts the release: tag `extension-vX.Y.Z`, a GitHub Release,
and a CI-built APK attached as a release asset. Normal pushes don't publish an APK. See
[docs/releasing.md](docs/releasing.md).

## Data & attribution

POI data from OpenStreetMap contributors, © OpenStreetMap contributors, available under the
[Open Database License](https://www.openstreetmap.org/copyright).

Opening hours and contact details for a POI can be filled in on demand from **Google Maps**
(Places API) when OpenStreetMap has none. That content is shown live with a Google Maps
attribution and is not stored on the device. Only the resolved Place ID is cached, which
the Places policy permits.

## License

Code is licensed under [GPL-3.0](LICENSE). You are free to use, study, share, and modify it;
any distributed derivative must remain open under the same license.

The **Resupply** name and logo are not covered by that license. They identify this project,
so please don't use them for a fork or a repackaged build in a way that suggests it is the
official app.

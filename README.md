# Singletrail

A light Android app that follows GPX routes on a mountain bike with arrow guidance over offline
OpenAndroMaps maps. The full specification, decisions log and milestone checklist are in
[`spec-singletrail.md`](spec-singletrail.md).

## Install

APKs are on the [Releases](https://github.com/LRosa2/Singletrail/releases) page (Android 8.0 or newer).

- `singletrail-<version>.apk` is the app. Its signing certificate's SHA-256 is
  `26924e0f96b2d1a9309b8752a96f2c89404f1346cd304847804b9f67127e3b85`.
- `singletrail-<version>-debug.apk` installs next to it, as "Singletrail debug". It adds
  *Simulate ride* to the route menu, so you can try the ride screen at a desk.

Maps: in the app, open *Maps* → *Get maps*, choose a region on the OpenAndroMaps website and tap its
install link for other apps. Maps are free for private use under OpenAndroMaps' terms.

## Build

Requirements: Android Studio 2026.2.1 or newer (AGP 9.4), JDK 17+, Android SDK platform 36.

```bash
./gradlew test assembleDebug
```

The debug build installs as `io.github.lrosa2.singletrail.debug`, next to any release build.

## Modules

| Module | What |
|---|---|
| `:app` | Android app: Compose UI, ride service, maps, storage |
| `:core:gpx` | Pure Kotlin: safe GPX reader/writer |
| `:core:nav` | Pure Kotlin: navigation engine (geometry, turns, matching, ride states) |

## Release signing

One release key is used for every distribution channel (spec D38). The keystore and its passwords
stay **outside** the repository. Create the key once:

```bash
keytool -genkeypair -v -keystore D:/keys/singletrail-release.jks -alias singletrail -keyalg RSA -keysize 4096 -validity 10000
```

Then create `keystore.properties` in the project root (git-ignored):

```properties
storeFile=D:/keys/singletrail-release.jks
storePassword=...
keyAlias=singletrail
keyPassword=...
```

Keep two offline backups of the `.jks` file and the passwords. Losing them means friends must
uninstall (and lose their data) to install a newly signed build.

## Licence

Singletrail is free software under the [GNU General Public License v3.0](LICENSE).

These parts come from others and keep their own terms:
- The map theme in `app/src/main/assets/theme/` is derived from OpenAndroMaps' `andromaps_mtb`
  theme, which apps may integrate for free ([openandromaps.org/en/license](https://www.openandromaps.org/en/license)).
- Map data is © OpenStreetMap contributors (ODbL), shown on every map.
- The map renderer is [VTM](https://github.com/mapsforge/vtm) (LGPL-3.0).

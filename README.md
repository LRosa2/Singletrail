# Singletrail

A light Android app that follows GPX routes on a mountain bike with arrow guidance over offline
OpenAndroMaps maps. The full specification, decisions log and milestone checklist are in
[`spec-singletrail.md`](spec-singletrail.md).

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

# SG Bus Widget

Android home-screen widget. **One tap** shows live Singapore bus arrival times for the
registered bus stop **nearest to you**, for only the services you registered there.
It then keeps refreshing every 20 s for 15 min, and stops by itself.

**Status: v0.2**, the first version. Verified on the emulator; Victor tested it on his Samsung S25+ (2026-11-03) and accepted it.
It's not on Google Play yet (see [What's next](#whats-next)).

| Doc | What's in it |
|---|---|
| [README.md](README.md) | This page: what it is, how to build, test and release |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Branch/PR workflow, conventions, the Android gotchas we've already hit |
| [PLAN.md](PLAN.md) | Research (why we built it), design decisions, phase-by-phase results |
| [PRIVACY.md](PRIVACY.md) | Privacy policy (also published as the Play listing's policy URL) |

## How it works (one paragraph)

Each placed widget is either **Nearest** (follows your location across *My stops*) or
**📌 Pinned** to one stop with its own buses. A tap starts a **foreground service**
(`LiveService`) as `location|specialUse`. It picks the nearest registered stop and fetches
[arrivelah](https://github.com/cheeaun/arrivelah) every 20 s for 15 min, then the widget goes
grey and shows clock times. JobScheduler/WorkManager can't run every 20 s, and OEMs drop queued
jobs, so the foreground service is the only reliable option. PLAN.md §3.1 explains why.

## Getting started

Requirements: **JDK 17** and the **Android SDK** (platform 36, build-tools). Either:
- **Android Studio**: open the folder; it creates `local.properties` with your `sdk.dir`. Or
- **command line**: set `JAVA_HOME` and `ANDROID_HOME`. If you keep the toolchain in
  `~/android-dev/{jdk17,sdk}` (or `$ANDROID_TOOLCHAIN`), `source env.sh` does it for you.

```bash
source env.sh                    # optional, see above
./gradlew :core:test             # unit tests: parser, nearest-stop, ETA, ordering (real arrivelah fixture)
./gradlew :app:assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No API keys are needed. The app has **no third-party Android dependencies**, only the
platform APIs plus `org.json`.

## Code map

| Path | What |
|---|---|
| `core/src/main/kotlin/sg/bus/core/Bus.kt` | Pure Kotlin, unit-tested: arrivelah/stops parsing, haversine, nearest-stop ranking, ETA labels (`Arr` / `3` / `~12`), widget state |
| `core/src/test/` | `BusTest.kt` + a real arrivals fixture for stop 42139 |
| `app/.../LiveService.kt` | The 20 s × 15 min foreground service and its `Session` clock |
| `app/.../Widgets.kt` | `BusWidgetLarge` / `BusWidgetSmall` providers and RemoteViews rendering by size |
| `app/.../Data.kt` | `Store` (saved stops and per-widget config), `Directory` (stops list and its 30-day refresh), `Fetcher` (HTTP), `Locator` (location fix) |
| `app/.../MainActivity.kt` | *My stops*: add, search, order, location disclosure, About/diagnostics |
| `app/.../ConfigureActivity.kt` | Per-widget setup (Nearest vs Pinned), also reachable via long-press › settings |
| `app/.../BootReceiver.kt` | Redraws idle widgets after reboot or app update |
| `app/src/main/assets/` | Bundled copy of the stops and services lists (used until the first refresh) |
| `scripts/` | `publish.sh` (Play release), `send_apk.sh` (debug APK to WhatsApp), `emu_tap.sh` (emulator helper) |

## Testing on the emulator

- The corporate proxy (Zscaler) breaks TLS on the emulator. **Debug builds** read
  `files/fixture.json` (or `fixture-<stop>.json`) from app storage instead of arrivelah when
  it is present, e.g. `adb push fixture.json /data/local/tmp/ && adb shell run-as sg.bus.widget cp /data/local/tmp/fixture.json files/`.
- `scripts/emu_tap.sh "text"` taps the first on-screen element containing that text.
- Set the emulator's location to a stop (e.g. 42139 Avenue Villa, 1.3236, 103.8079) to check nearest-stop picking.
- Always check widget **sizes on a real Samsung** as well. One UI's cell sizes differ from the emulator's (CONTRIBUTING.md).

## Releasing (maintainer only)

Needs files that are **not in the repo** and never should be:
- `~/.android-keys/sg-bus-upload.properties` + `sg-bus-upload.jks`: upload signing key. Without it, release
  builds are unsigned and Play refuses them. Override the path with `-PsigningProps=/path/to.properties`.
- `~/.android-keys/sg-pm25-play.json`: Play service account with release rights for this app.

```bash
scripts/publish.sh "What's new"            # tests, then bundle → Play internal testing
scripts/publish.sh --closed "What's new"   # closed testing (alpha), as a draft you roll out in Play Console
scripts/send_apk.sh ["caption"]            # debug APK to WhatsApp via Victor's Via VM (needs gcloud access)
```
Bump `versionName` in `app/build.gradle.kts` for each release. `versionCode` is resolved
from Play automatically, so it never collides.

## What's next

- Remove `ACCESS_BACKGROUND_LOCATION` once One UI is confirmed to grant the `location` service type. It triggers
  Play's background-location review, and the app doesn't need it.
- Create the app in Play Console, grant `play-publisher` rights, upload the first bundle by hand, then use `scripts/publish.sh`.
- Optional: a Chronometer per-second countdown, and an LTA DataMall fail-over (PLAN.md §2).

## Data and credits

Arrivals from [arrivelah](https://github.com/cheeaun/arrivelah); stops and services from
[busrouter.sg](https://busrouter.sg). Both are based on LTA DataMall. This app is independent of LTA.

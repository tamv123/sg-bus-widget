# SG Bus Widget — Research & Plan

**Goal:** a home-screen widget on Victor's Samsung S25+. **One tap** shows live bus
arrival times for the **registered bus stop nearest to where the phone is**, and
only for the **registered services** at that stop. After that tap the widget
**re-fetches every 20 s for 15 min**, then stops. A bigger widget shows more
services and more arrivals.

Example: Victor registers *42139 Avenue Villa → 970, 156* and *Office stop → 970*.
At home, one tap shows 970 and 156 at Avenue Villa. At the office, the same tap
shows 970 at the office stop.

---

## 1. Research (2026-10-29): is there something we can use as-is?

| App / repo | Platform | Widget | Picks the stop from location? | Refresh | Verdict |
|---|---|---|---|---|---|
| GoBusSG | iOS + Android | Yes, pins a **favourite stop** | No, the stop is fixed | Not stated | ✗ fixed stop, not nearest |
| SG Bus (id1148286322) | **iOS only** | Yes, "3 nearby bus stops", auto-refresh | Yes, but **all** nearby stops, not your registered ones | Auto | ✗ iOS only; no list of your own stops |
| SG Bus!Ahead, BusTrack SG, SGBUS | iOS | Yes (Ahead: Pro only) | Fixed / selected stop | Not stated | ✗ iOS, fixed stop |
| SG Bus / MRT Tracker (Goder Hsu) | Android | Notification bar, not a widget | Lists nearby stops in the app | — | ✗ last updated around 2019; no widget |
| SG BusLeh | Android | Not stated | Nearest stops, in the app | — | ✗ no widget found |
| stops-flutter, transito-flutter (GitHub) | Flutter, open source | **No** | — | — | ✗ app only; useful as LTA reference code |
| ha-lta (GitHub) | Home Assistant | — | — | — | ✗ different platform |

**Conclusion:** no app found does **nearest of *my* registered stops + only *my*
services + a tap-started 20 s × 15 min live session**. The closest are GoBusSG (Android,
but a fixed stop) and SG Bus (nearby stops with auto-refresh, but iOS only). Caveat: the
Android claims come from third-party write-ups. GoBusSG's Play listing wasn't
opened directly. **We build it.** It's about the size of the PM2.5 widget, and most
of the hard parts were already solved there and in Via's `/bus`.

## 2. Data source (verified live 2026-10-29)

**Arrivals:** `GET https://arrivelah2.busrouter.sg/?id=<stopCode>`, no key. This is the
same source Via's `/bus` has used without trouble since 2026-07-18.
```json
{"services":[{"no":"156","operator":"SBST",
  "next":    {"time":"2026-10-29T22:06:03+08:00","duration_ms":946596,"load":"SEA","feature":"WAB","type":"SD","monitored":1},
  "subsequent":{…}, "next2":{…}, "next3":{…}}]}
```
- Anchor on the **absolute `time`**, not on the `next`/`next2` slot. A bus moves from
  `next2` to `next` as the bus ahead of it leaves (learned in Via's PR #49/#50 reminder fix).
- `monitored: 0` means a schedule estimate, not GPS. Show it with `~` (e.g. `~12`), like the
  LTA apps do.
- `load` is SEA / SDA / LSD (seats / standing / limited) and is shown as a dot colour on the larger sizes.

**Stops directory:** `https://data.busrouter.sg/v1/stops.min.json` →
`{code: [lng, lat, name, road]}`. Cache it on the phone and refresh monthly. Keep a
stale cache if the download fails (same as Via's `bus_arrivals.load_stops`).

**Services that call at each stop:** `https://data.busrouter.sg/v1/services.min.json`
(602 services, each with its route stop lists). Used only when registering a stop,
to offer that stop's services as checkboxes.

**Fail-over (optional, phase 3):** LTA DataMall `BusArrival` v3 using **Victor's own**
free AccountKey, typed into the app and never built into the APK. The same lesson as
Ria's LLM chain applies: *an unexercised fail-over is indistinguishable from a dead
one*. If we add it, a "Test both sources" button in the app shows each source's
latency and status.

## 3. Design

Same base as `sg-pm25-widget`: **no third-party Android dependencies**
(`AppWidgetProvider`, `RemoteViews`, `LocationManager`, `HttpURLConnection`,
`org.json`), AGP 8.10.1 / Kotlin 2.1.21, minSdk 34 / targetSdk 36, and the shared
`~/android-dev` toolchain via `env.sh`.

- **`:core`**, pure Kotlin with unit tests: parse arrivelah, pick the nearest
  registered stop (haversine), order services and arrivals, format ETAs
  (`Arr` / `3` / `~12`), and track the session clock (started → ends at +15 min).
- **`:app`**: the widget, the live-session service, the registration screen, the
  setup/permissions screen, and diagnostics.

### 3.1 The one-tap live session: why a foreground service

At 20 s intervals, a JobScheduler or WorkManager loop isn't possible. Both have a
15-min minimum period, and the PM2.5 widget showed that **queued jobs are delayed or
dropped** on Vivo/Xiaomi. Exact alarms need `SCHEDULE_EXACT_ALARM`, which Play
restricts to alarm and calendar apps. A plain thread is killed when the process is cached.

→ **The tap starts a foreground service** (`PendingIntent.getForegroundService`).
- Android 12+ lets a widget tap start a foreground service from the background (a
  documented exemption: the user interacted with the widget).
- The service runs a 20 s loop for 15 min (45 fetches), then calls `stopSelf()`. A
  **Stop** button is in the notification.
- The required ongoing notification is useful in its own right: it shows the headline
  ETA (e.g. `970 · 3 min · 156 · 8 min`) on the lock screen and in the shade.
- Tapping the widget again during a session refreshes immediately and **restarts the
  15-min clock**. It never starts a second loop.
- Foreground-service type: `specialUse` with a Play justification ("live transit
  countdown the user started, ends after 15 min"). The alternative is `dataSync`, which
  Android 15 caps at 6 h/day, more than this app would ever use. **Phase 0 checks which
  one Play accepts.**
- Fetches run **directly in the service**, never queued (the PM2.5 v0.5.6 Vivo fix). A
  short HTTP timeout (5 s) means one slow fetch can't push the next one back.

**Countdown between fetches:** the ETA text changes only on each 20 s fetch. Larger
sizes can show the first bus as a `RemoteViews` `Chronometer` set to count down, so it
ticks every second without an update. Phase 2 tries this; if One UI
renders it badly, we drop it.

### 3.2 Choosing the stop from location

- On tap: get a **current fix with a 3 s timeout**, falling back to the last-known fix
  if it is ≤ 10 min old. Bus stops can be 200 m apart, so a fix that is an hour old
  (which the PM2.5 widget tolerates) is too stale here.
- Pick the **nearest registered stop** by haversine distance. The widget shows the stop
  name plus `· 120 m`. If the nearest registered stop is > 2 km away, it still shows it,
  with the distance highlighted. It never fails silently.
- The stop is **picked again on every fetch** during the session (the location is free:
  `ACCESS_FINE_LOCATION` comes from the foreground service). Walking from home toward
  the office stop changes the stop once you're closer to it. If that flips back and
  forth, phase 2 adds hysteresis (switch only when the new stop is ≥ 50 m closer).
- **Permission:** `ACCESS_FINE_LOCATION` plus a foreground-service type that covers
  location. **Phase 0 checks** whether a service started from a widget tap gets
  *while-in-use* location on Android 16 / One UI. If it doesn't, we fall back to
  `ACCESS_BACKGROUND_LOCATION` ("Allow all the time", which the PM2.5 app already uses,
  with its prominent-disclosure dialog).
- A manual override in the app: "Always use stop X" (for when GPS is poor indoors).

### 3.3 Registration

The app screen lists registered stops. **Add stop** has three ways in:
1. **Near me:** the 10 nearest stops from the cached directory, with distance.
2. **Search:** by 5-digit code, or fuzzy search on stop name or road (same matching as
   Via's `resolve_stop`).
3. **Prefill:** Victor's Via shortcut `s` = 42139 Avenue Villa · Sixth Ave, with 970.

Then **tick the services**: the checkboxes come from `services.min.json` routes, so
only services that actually call at that stop are offered. Each stop keeps its own
ordered list of services. Data lives in a small JSON file in app storage. Nothing
leaves the phone, and there are no accounts.

### 3.4 Widget sizes (responsive `RemoteViews` map, plain layouts, no collections)

| Size | Shows |
|---|---|
| 2×1 | Stop short name · first registered service · next 2 ETAs |
| 4×1 | Stop · up to 3 services × next ETA |
| 4×2 | Stop + distance · up to 4 services × next / next2 / next3, load dots |
| 4×3+ | Up to 6 services, plus a line for the **second-nearest** registered stop |

Every size shows a status line: `Live · 12:41 left` while running, or `Tap for live
times · last 08:02` when idle (greyed out). Errors are shown, never hidden:
`No signal — retrying`, `Location off — using Avenue Villa`, `arrivelah down`.

**Lessons from One UI in the PM2.5 widget, applied from the start:**
- **No `StackView` or other collection widgets.** One UI's launcher left reapplied
  collection cards blank. Fixed rows (hiding unused rows with `View.GONE`) avoid the
  problem entirely. If any blank-on-reapply shows up anyway, use the twin-layout trick
  (`_a`/`_b` layout ids).
- Use `OPTION_APPWIDGET_SIZES` with **minWidth × maxHeight** for portrait.
- Samsung reports a 1×1 cell as 64×93 dp, smaller than the emulator does, so check
  every size on the S25+, not only on the emulator.
- `renderAll` is `@Synchronized`, because the 20 s loop and a tap can render at the same time.
- Re-arm after reboot or app update with a `BootReceiver` that **is declared in the
  manifest** (PR #11's wasn't). For this app, re-arming just means drawing the idle state.
- A force-stop cancels the widget's PendingIntents. The setup screen says so, and
  opening the app re-arms them.

## 4. Phases

**Phase 0: spikes (half a day, emulator + S25+ over wireless adb)**
1. A widget tap starts a foreground service on Android 16 / One UI 8. Also check
   Vivo-like conditions on the emulator
   (`cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore`).
2. Does that service get while-in-use fine location? → decides whether we need
   "Allow all the time".
3. Choose the foreground-service type (`specialUse` vs `dataSync`) against the Play policy text.

**Phase 1: MVP (v0.1)**
`:core` (parser, nearest-stop, session clock, ETA format) with unit tests against
real arrivelah fixtures from stop 42139 · the foreground-service live loop · a 4×2
widget only · registration by code search + service checkboxes · fixture seam for
debug builds (`files/fixture.json`, same as PM2.5, because Zscaler breaks TLS on the
emulator).

**Phase 2: sizes + polish (v0.2)**
All four sizes · "Near me" add · ongoing-notification ETAs · Chronometer countdown
trial · load dots · stop hysteresis · diagnostics line (phone, launcher, cell dp,
last fetch latency).

**Phase 3: release (v0.3)**
Play internal testing using the PM2.5 pipeline: copy `scripts/publish.sh` (Gradle Play
Publisher 3.12.1, `--no-configuration-cache`), a new upload key in `~/.android-keys/`
(**back it up**), and the existing `play-publisher` service account granted rights on
the new app. Also: the location prominent disclosure, a privacy-policy gist, and data
attribution (busrouter / arrivelah, LTA DataMall). Optional: the LTA DataMall
fail-over with its test button.

## 5. Decisions (Victor, 2026-10-29: "go ahead with your recommendation")

1. **Data source:** arrivelah only, as Via does. No LTA key for now.
2. **Bigger widget:** shows more services at the same stop. The second-nearest stop appears only from about 4×3 (portrait height ≥ 200 dp).
3. **Tap during a live session:** refreshes now and restarts that widget's 15 min.
4. **Package:** `sg.bus.widget`.

## 6. Several widgets, each set up differently (Victor's question, 2026-10-29)

Every placed widget has its own `appWidgetId`, and the app keeps a `WidgetConfig` for each one:
- **Nearest** (the default): follows your location across *My stops*.
- **📌 Pinned**: always one stop, with **its own** list of buses. For example, widget 1 = stop A · 123, widget 2 = stop B · 234.

The setup screen (`ConfigureActivity`) opens when you add a widget, and again from long-press › settings
(`widgetFeatures="reconfigurable"`). Each widget runs its own live session. A single service loop serves
all of them and fetches each stop once per tick.

## 7. Phase 0 + MVP results (emulator, Pixel / Android 16, 2026-10-29)

- ✅ **A widget tap starts the foreground service from the background.** Android logged `Background started FGS:
  Allowed … allowWiu:53`.
- ✅ **The service ran as `location|specialUse`** (`types=0x40000008`), so a session started from the widget gets
  *while-in-use* location. "Allow all the time" is **not** needed on stock Android 16. **Still to confirm on the S25+.** The
  app records the type it got and shows a fallback prompt if One UI refuses the location type.
- ✅ The 20 s loop ran on time after one fix: it now schedules from the start of each tick, where waiting
  for the 3 s location attempt had stretched it to about 23 s. The **Stop** button in the notification ends the session. The widget
  goes grey and shows **clock times**, because minutes frozen at the last fetch would be wrong by the time you look.
- ✅ The nearest stop came from location: Avenue Villa at 10 m beat Aft Lantana at 40 m. A widget pinned to Aft Lantana · 970
  showed that stop while the other widget followed the nearest one.
- ✅ **The 15-min session stopped on its own:** 15:22:11 → 15:37:20, **45 fetches**, then the widget went idle (grey, clock times).
- One bug was caught on the emulator: a plain `<View>` spacer made the widget show "Can't load widget", because
  RemoteViews doesn't allow it. A `FrameLayout` replaced it.

## 8. v0.2 (2026-11-02)

- ✅ **Bus order:** an Order button per stop, and per pinned widget. A small widget only fits the first buses, so order decides what it shows.
- ✅ **Stops list refresh:** downloads every 30 days, on opening the app or on a widget tap; a failed try waits a day. Both files must
  parse and be plausibly sized before either replaces the current pair. Checked on the emulator: failed TLS keeps the current list, a good
  download swaps it, an HTML block page is rejected. The real download is not proven on a device yet (Zscaler breaks TLS on the emulator).
- ✅ **Location disclosure** before Android's prompt; a stop dropped from LTA's list is flagged in My stops.
- ✅ Idle widgets no longer say "no live info" once old buses have passed; the first live frame no longer shows a bogus 07:30 fetch time.
- ✅ **Release pipeline:** upload key `~/.android-keys/sg-bus-upload.jks` (**back it up**), `scripts/publish.sh`, `scripts/send_apk.sh`, `PRIVACY.md`, published at https://gist.github.com/tamv123/28d5763300292b49df677c05b6cfc6ed.

**Not done yet (next):**
- Check it on the S25+ (One UI sizes and the location FGS type). If One UI grants the location type, **remove
  `ACCESS_BACKGROUND_LOCATION` from the manifest before release**: it triggers Play's background-location review, and the app wouldn't need it.
- Create the app in Play Console, grant `play-publisher` release rights on it, upload the first bundle, then `scripts/publish.sh`.
- Chronometer countdown trial (only worth it if 20 s steps feel too coarse on the phone).

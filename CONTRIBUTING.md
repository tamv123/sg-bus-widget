# Contributing

## Workflow

1. Branch from `main`: `feat/<thing>` or `fix/<thing>`. Never commit straight to `main`.
2. Keep `:core` logic in `:core`, with a unit test. Keep `:app` to Android glue.
3. Before opening a PR:
   ```bash
   ./gradlew :core:test :app:assembleDebug
   ```
   Then install the debug APK and exercise what you changed on a device or emulator. Say in the PR what
   you checked and where (emulator vs which phone).
4. Open a PR into `main`, describing what changed and how it was tested. Victor merges.
5. User-visible changes: update `app/src/main/play/release-notes/en-US/default.txt`, and add a line to
   the latest results section of `PLAN.md`.

## Ground rules

- **No third-party Android dependencies** without discussion. The app is platform APIs + `org.json`.
- **Nothing leaves the phone** except the arrivelah/busrouter requests (see `PRIVACY.md`). Any
  change to that means updating `PRIVACY.md` *and* the published policy gist.
- **Never commit secrets**: signing keys, `*.properties` with passwords, Play service-account JSON.
  `.gitignore` covers the usual names; check `git status` anyway.
- Errors are shown on the widget (`No signal — retrying`, `arrivelah down`), never swallowed.

## Android gotchas we've already paid for

- **RemoteViews:** a plain `<View>` in a widget layout gives "Can't load widget". Use `FrameLayout`.
  Don't use collection widgets (`StackView`/`ListView`): One UI leaves them blank after a reapply.
  Use fixed rows and hide the unused ones with `View.GONE`.
- **Sizes:** use `OPTION_APPWIDGET_SIZES` with **minWidth × maxHeight** for portrait. Samsung's cells are
  smaller than the emulator's (a 1×1 is about 64×93 dp), so check sizes on a Samsung.
- **The 20 s loop** schedules each tick from its *start*. Otherwise the 3 s location wait stretches it to about 23 s.
- **Idle widgets** show clock times, not minutes, because minutes frozen at the last fetch go stale.
- **Fetch directly** in the service. Don't queue fetches through JobScheduler/WorkManager: Vivo/Xiaomi delay or drop the jobs.
- **Rendering** is `@Synchronized`, because a tap and the loop can render at the same time.
- **Emulator TLS** breaks behind a corporate proxy. Use the debug fixture seam (README › Testing).

## Data sources

- Arrivals: `GET https://arrivelah2.busrouter.sg/?id=<stopCode>`, no key. Anchor each bus on its absolute
  `time`, not on its `next`/`next2` slot. `monitored: 0` means a schedule estimate, shown as `~12`.
- Stops / services: `https://data.busrouter.sg/v1/{stops,services}.min.json`, refreshed every 30 days.
  A copy is bundled in `app/src/main/assets/`.

## License

By contributing, you agree your contributions are licensed under the repo's [MIT License](LICENSE).

## UI tests without an emulator

`./gradlew :app:testDebugUnitTest` runs Robolectric screen tests on the JVM (e.g. `SetupGuideTest` walks the whole
first-run guide). Robolectric is test-only, but it pulls `androidx.test`, which is why `android.useAndroidX=true`;
the app itself still uses no AndroidX.

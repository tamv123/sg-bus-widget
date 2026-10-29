# SG Bus Widget

Android home-screen widget. **One tap** shows live Singapore bus arrival times for the
registered bus stop **nearest to you**, for only the services you registered there.
It then keeps refreshing every 20 s for 15 min.

Status: **v0.1 MVP**, verified on the emulator, not yet on a phone. See [PLAN.md](PLAN.md) for the research, design and phases.

## Build / test

```bash
source env.sh            # shared ~/android-dev toolchain (JDK 17, SDK)
./gradlew :core:test     # parser, nearest-stop and ETA logic against real arrivelah data
./gradlew :app:assembleDebug
```
On the emulator, the corporate proxy breaks TLS. Debug builds read `files/fixture.json` (or `fixture-<stop>.json`) in place of
arrivelah when it is present. `scripts/emu_tap.sh "text"` taps an on-screen element by its text.

Data: [arrivelah](https://github.com/cheeaun/arrivelah) and [busrouter](https://busrouter.sg) (LTA DataMall).

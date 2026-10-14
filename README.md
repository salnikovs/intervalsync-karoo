# Intervalsync for Karoo

A [Hammerhead Karoo extension](https://github.com/hammerheadnav/karoo-ext) that adds the performance metrics Karoo lacks and ties rides into Intervalsync / Intervals.icu.

## Status

Scaffold with the first in-ride data fields:

| Field | Type ID | Shows |
| --- | --- | --- |
| W′ Balance Bar | `wbal-graphic` | W′ balance % with a bar that empties and changes colour (not yet checked on a ride page) |
| W′ Balance | `wbal-kj` | kJ left above critical power (Skiba differential model) |
| W′ Balance % | `wbal-pct` | The same, as a share of W′ |
| Best 5 min | `best-5min` | Best 300 s average power this ride |
| Best 20 min | `best-20min` | Best 1,200 s average power this ride |

The app has two tabs. **Training** shows the rider's training context from their Intervalsync account, training hours per week as bars with critical power and W′ as lines over the last 6 or 12 weeks, past rides and the last ride's summary. **Settings** holds the Intervalsync connection and CP, W′ and FTP: a value typed there wins, then the one synced from Intervals.icu, then the Karoo profile's FTP (for CP and FTP) or 20 kJ (for W′).

Pairing with Intervalsync is built on the Karoo side: the app shows a code, receives a device token, and then takes CP and W′ from Intervals.icu. intervalsync.icu doesn't have the endpoints yet, so it only works against `tools/mock_server.py`; the contract is in [docs/device-api.md](docs/device-api.md).

When a ride with power ends, the app posts a notification that opens a summary: average power, best 5 and 20 min, the lowest W′ balance, and the split of training stress by power zone.

Once connected, a Past rides screen fetches recent rides back from Intervals.icu through the service and summarizes them the same way, since the Karoo can't read rides already recorded on it.

Not built yet: bests vs 90-day PB, Reserve, Form Today, VO2max, send to Intervalsync, FIT developer fields.

## Roadmap

- **Intervalsync as a Karoo connected account.** Listed under Connected Accounts next to Xert, Intervals.icu and SRAM, so riders connect it and get ride upload there like any other account. karoo-ext has no API for this; that list is Hammerhead's own, so it needs a partner integration with Hammerhead. Until then the extension's own pairing and end-of-ride send stand in for it.

## Layout

- `core/` — pure Kotlin metric math, no Android. Unit-tested; this is where every formula lives.
- `app/` — the Karoo extension: samples Karoo's power stream at 1 Hz, feeds `core`, and serves the data fields.
- `validation/` — Python reference implementations of the spec's metrics, run over real rides from Intervals.icu ([validation/README.md](validation/README.md)).
- `tools/mock_server.py` — local stand-in for the Intervalsync device API.

## Build

Needs JDK 17 and the Android SDK (Android Studio brings the SDK). The build always runs on Java 17, whatever the default `java` is (`gradle/gradle-daemon-jvm.properties`).

The tests need nothing else:

```bash
./gradlew :core:test
```

karoo-ext is served from GitHub Packages, which needs a token with `read:packages` even for public packages. With the `gh` CLI, add the scope once (`gh auth refresh -s read:packages`) and pass the token per build:

```bash
GITHUB_ACTOR=<github username> GITHUB_TOKEN="$(gh auth token)" ./gradlew :app:installDebug
```

For builds from Android Studio, put a [personal access token](https://github.com/settings/tokens) in `~/.gradle/gradle.properties` instead:

```properties
gpr.user=<github username>
gpr.key=<token>
```

CI runs the tests and builds a debug APK on every push; the APK is attached to the workflow run.

## Testing without riding

[karoo-ride-replay](https://support.hammerhead.io/hc/en-us/community/posts/53155430254619-karoo-ride-replay-replay-a-recorded-ride-s-data-to-test-your-extension-no-ride-needed) plays a recorded FIT file as a live ride.

The extension only computes while Karoo has it bound, which today means while one of its fields is on a ride page.

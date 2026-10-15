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

The app has three tabs, all filled from the rider's Intervalsync account once the Karoo is connected:

- **Training** — today's brief, this morning's HRV, resting HR and sleep from Intervals.icu, training hours per week as bars with critical power and W′ as lines over the last 6 or 12 weeks, coach comments, past rides and the last ride's summary.
- **Plan** — today's targets, then the workouts planned for the next 7 days, each with a button to Karoo's Workouts list.
- **Settings** — the Intervalsync connection, the rider's training context and the coach and team spaces they belong to (each collapsed until tapped), and read-only CP, W′, FTP, power zones and heart rate zones as set up in Intervals.icu. Nothing is typed in on the Karoo. Without a connection the app uses the Karoo profile's FTP and a 20 kJ W′, and has no zones.

Today's targets are also available in-ride as Power Cap and HR Cap fields, with an alert when the 10-second average goes over a cap.

When a ride with power ends, the app posts a notification that opens a summary: average power, best 5 and 20 min, the lowest W′ balance, and the split of training stress across the rider's own power zones. The ride report goes to Intervalsync for the assistant on its own as soon as the Karoo is online; rating how it felt on the summary sends it again with the rating.

Past rides are fetched back from Intervals.icu through the service and summarized the same way, since the Karoo can't read rides already recorded on it.

The Karoo side of all this is built and tested against `tools/mock_server.py`; the contract is in [docs/device-api.md](docs/device-api.md). intervalsync.icu doesn't serve these endpoints yet.

Not built yet: bests vs 90-day PB, Reserve, Form Today, VO2max, FIT developer fields.

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

## Releasing

The version lives in `app/manifest.json` (`latestVersion`, `latestVersionCode`), the file Karoo reads to install and update the app; the Gradle build and the extension take it from there.

1. Raise both version fields in `app/manifest.json`, write `releaseNotes`, and merge to `main`.
2. Tag that commit `v<latestVersion>` and push the tag.
3. The Release workflow builds the signed APK and publishes it with manifest.json and the icon as a GitHub release. Karoo reads them from `releases/latest/download`.

Release builds are signed with the key in the repository secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`, and fail without it rather than fall back to the debug key. Locally, `:app:assembleRelease` reads `INTERVALSYNC_KEYSTORE_FILE`, `INTERVALSYNC_KEYSTORE_PASSWORD`, `INTERVALSYNC_KEY_ALIAS` and `INTERVALSYNC_KEY_PASSWORD` from the environment.

## Testing without riding

[karoo-ride-replay](https://support.hammerhead.io/hc/en-us/community/posts/53155430254619-karoo-ride-replay-replay-a-recorded-ride-s-data-to-test-your-extension-no-ride-needed) plays a recorded FIT file as a live ride.

The extension only computes while Karoo has it bound, which today means while one of its fields is on a ride page.

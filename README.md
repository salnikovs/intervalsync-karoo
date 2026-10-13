# Intervalsync for Karoo

A [Hammerhead Karoo extension](https://github.com/hammerheadnav/karoo-ext) that adds the performance metrics Karoo lacks and ties rides into Intervalsync / Intervals.icu.

## Status

Scaffold with the first in-ride data fields:

| Field | Type ID | Shows |
| --- | --- | --- |
| W′ Balance | `wbal-kj` | kJ left above critical power (Skiba differential model) |
| W′ Balance % | `wbal-pct` | The same, as a share of W′ |
| Best 5 min | `best-5min` | Best 300 s average power this ride |
| Best 20 min | `best-20min` | Best 1,200 s average power this ride |

CP defaults to the FTP in the Karoo profile and W′ to 20 kJ; both can be set in the app's screen on the Karoo until pairing with Intervalsync replaces them.

Not built yet: the W′ graphic field, bests vs 90-day PB, Reserve, Form Today, ride contribution, the end-of-ride summary and send to Intervalsync, FIT developer fields, pairing.

## Layout

- `core/` — pure Kotlin metric math, no Android. Unit-tested; this is where every formula lives.
- `app/` — the Karoo extension: samples Karoo's power stream at 1 Hz, feeds `core`, and serves the data fields.

## Build

Needs JDK 17 and the Android SDK (Android Studio brings both).

karoo-ext is served from GitHub Packages, which needs a token even for public packages. Put a [personal access token](https://github.com/settings/tokens) with `read:packages` in `~/.gradle/gradle.properties`:

```properties
gpr.user=<github username>
gpr.key=<token>
```

Then:

```bash
./gradlew :core:test
```

```bash
./gradlew :app:installDebug
```

CI runs the tests and builds a debug APK on every push; the APK is attached to the workflow run.

## Testing without riding

[karoo-ride-replay](https://support.hammerhead.io/hc/en-us/community/posts/53155430254619-karoo-ride-replay-replay-a-recorded-ride-s-data-to-test-your-extension-no-ride-needed) plays a recorded FIT file as a live ride.

The extension only computes while Karoo has it bound, which today means while one of its fields is on a ride page.

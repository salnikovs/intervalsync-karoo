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

## Roadmap

- **Intervalsync as a Karoo connected account.** Listed under Connected Accounts next to Xert, Intervals.icu and SRAM, so riders connect it and get ride upload there like any other account. karoo-ext has no API for this; that list is Hammerhead's own, so it needs a partner integration with Hammerhead. Until then the extension's own pairing and end-of-ride send stand in for it.

## Layout

- `core/` — pure Kotlin metric math, no Android. Unit-tested; this is where every formula lives.
- `app/` — the Karoo extension: samples Karoo's power stream at 1 Hz, feeds `core`, and serves the data fields.

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

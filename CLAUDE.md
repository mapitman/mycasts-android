# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew assembleDebug              # build the debug APK
./gradlew testDebugUnitTest          # run JVM unit tests (Robolectric)
./gradlew testDebugUnitTest --tests "com.bugzapperlabs.myfeeds.playback.PlaybackControllerTest"  # single test class
./gradlew lintDebug                  # Android lint
./gradlew installDebug               # install to a connected device/emulator
```

CI (`.github/workflows/build.yml`) runs `./gradlew assembleDebug testDebugUnitTest lintDebug` on every push/PR to `main` — run the same before considering work done. There is no separate ktlint/detekt step; `lintDebug` is the only static check.

Unit tests live under `app/src/test` and run on the JVM via Robolectric (`isIncludeAndroidResources = true`, so Android resources are usable in tests). Instrumented tests (`app/src/androidTest`, run via `connectedAndroidTest` on a device) are limited to Room migration tests — most feature testing happens in the Robolectric unit tests instead.

## Architecture

Single-module Android app (`app/`), Kotlin, Jetpack Compose UI, Hilt DI, Room for persistence, Media3 for podcast playback. Package root: `com.bugzapperlabs.myfeeds`.

**Navigation**: One `NavHost` in `MainActivity`, routes are plain strings (`"articleList/{feedId}"`, `"reader/{feedId}/{itemId}"`, etc.) — see the `composable(...)` blocks in `MainActivity.kt` for the full route table. Each screen package (`feedlist`, `articlelist`, `reader`, `queue`, `settings`, `addfeed`, `feedproperties`) follows Screen (Compose) + ViewModel (Hilt `@HiltViewModel`), with the ViewModel exposing a `StateFlow` of UI state.

**Data layer**: Room database (`data/local/AppDatabase.kt`, entities `Category`, `Feed`, `FeedItem`, `QueueEntry`) accessed through DAOs, wrapped by `data/repository/FeedRepository.kt` and `QueueRepository.kt` — screens/viewmodels go through the repositories, not the DAOs directly. Schema changes require a bump to `AppDatabase`'s `version` plus a matching entry in `data/local/Migrations.kt` (exported schemas live in `app/schemas/`, used by the Room migration instrumented tests). App settings (playback speed defaults, font size, streaming toggle, etc.) live in `data/settings/AppSettings.kt` / `SettingsDataStore.kt`, backed by Jetpack DataStore rather than Room.

**Feed ingestion**: `data/feed/FeedFetcher.kt` + `FeedParser.kt` fetch and parse RSS/Atom, `FeedUpdateEngine.kt` reconciles parsed results into the database, `refresh/FeedRefreshWorker.kt` (WorkManager) runs this periodically — scheduled from `MainActivity.onCreate` via `refresh/FeedRefreshScheduler.kt`, using the interval from `SettingsDataStore`. `data/opml/` handles OPML import/export. `data/directory/FeedDirectory.kt` backs offline feed-directory search.

**Playback**: `playback/PlaybackController.kt` is a `@Singleton` that owns the Media3 `MediaController` connection to `playback/PlaybackService.kt` (a `MediaSessionService`), and exposes a single `PlaybackUiState` `StateFlow` consumed by both the persistent mini-player (`MiniPlayerBar`/`MiniPlayerViewModel`, shown from `MainActivity` whenever something is loaded and the reader isn't already showing that exact episode) and the reader's in-page player. `PlaybackController` also persists the "current" episode and last resume position through `SettingsDataStore` so playback state survives process death, and clears it on explicit stop or on natural completion (`Player.STATE_ENDED`).

**Widget**: `widget/UnreadWidget.kt` is a Glance app widget showing per-feed unread counts; refreshed on app launch and after scheduled feed refreshes. Tapping a feed in the widget launches `MainActivity` with `WIDGET_FEED_ID_EXTRA` set, which is read in `onCreate` to pick the nav start destination.

**DI**: Hilt modules under `di/` (`DatabaseModule`, `NetworkModule`, `SettingsModule`, `WorkModule`) provide the Room database/DAOs, OkHttp client, DataStore, and WorkManager configuration respectively.

## Manual UI verification

Don't drive the UI yourself via `adb shell input tap`/screenshots to verify a change — scripting taps through screenshot coordinates is very token-intensive and brittle. Instead: build, install, and launch the app on the connected device (`./gradlew installDebug`, then `adb shell am start -n com.bugzapperlabs.myfeeds.debug/.MainActivity` -- debug builds carry a `.debug` applicationId suffix so they can coexist with a release-signed install, see "Releases" below), and hand back a numbered list of test steps for the user to perform themselves and report results.

## Releases

Tag pushes matching `vMAJOR.MINOR.PATCH` (e.g. `v1.2.3`) trigger `.github/workflows/release.yml`,
which builds a signed release APK, derives `versionName`/`versionCode` from the tag
(versionCode = major*10000 + minor*100 + patch -- keep minor/patch under 100), and publishes a
GitHub Release with the APK attached and an auto-generated changelog. `scripts/generate-release-keystore.sh`
creates the signing keystore (wraps `keytool`, prompts for passwords). Signing uses that keystore
stored (base64) in the `RELEASE_KEYSTORE_BASE64` secret, decoded to a temp file at build time;
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` are the matching secrets --
`scripts/set-release-secrets.sh /path/to/keystore.jks` pushes all four via the `gh` CLI. Tags are
append-only -- never delete and re-push a release tag. Local `assembleRelease` without these env
vars builds an unsigned APK (succeeds, just not signed). Debug builds use applicationId
`com.bugzapperlabs.myfeeds.debug` (vs. the release build's `com.bugzapperlabs.myfeeds`) so a debug
install and a release-signed install can coexist on the same device instead of colliding at
install time over a signing-certificate mismatch.

## Conventions

- Non-obvious behavior is explained with a comment referencing the GitHub issue that drove it (e.g. `// issue #66`) — follow this pattern for new work rather than writing prose-only comments; it lets a reader trace *why* back to the originating issue.
- `strings.xml` has translations tracked in parallel under `values-de/es/fr/it/` — any new user-facing string needs an entry in all four alongside `values/strings.xml`.
- Feature work in this repo is scoped to one (or a coordinated pair of) GitHub issue(s) per branch/PR, with commit/PR titles following `<Summary> (issue #N)`.

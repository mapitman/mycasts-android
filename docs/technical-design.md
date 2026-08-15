# Technical design

MyCasts is a single-module Android app: Kotlin, Jetpack Compose UI, Hilt for dependency
injection, Room for persistence, Media3 for playback, and WorkManager for background jobs
(feed refresh, downloads). Package root: `com.bugzapperlabs.mycasts`.

## Screens and navigation

`MainActivity` hosts one `NavHost` with routes as plain strings, e.g. `"episodeList/{feedId}"`,
`"episodeDetails/{feedId}/{itemId}"`. Each screen lives in its own package containing a Compose
`Screen` and a Hilt `@HiltViewModel`, with the ViewModel exposing UI state as a `StateFlow`:

| Package | Screen | Purpose |
|---|---|---|
| `feedlist` | Feed list | Subscribed feeds, unread counts, entry point |
| `episodelist` | Episode list | Episodes for one feed |
| `episodedetails` | Episode details | Full episode view with the in-page player |
| `queue` | Next Up queue | Reorderable play-next queue (`ReorderableQueueList`) |
| `downloads` | Downloads | Downloaded episodes |
| `settings` | Settings | Playback defaults, font size, refresh interval, streaming toggle |
| `addfeed` | Add feed | Search the feed directory / PodcastIndex, or paste a URL |
| `podcastdetails` | Podcast details | Preview a feed before subscribing |
| `feedproperties` | Feed properties | Per-feed settings, unsubscribe |

A persistent mini-player (`playback/MiniPlayerBar.kt`, `MiniPlayerViewModel`) is shown from
`MainActivity` whenever an episode is loaded and the episode-details screen for that exact
episode isn't already on screen.

## Data layer

Room database (`data/local/AppDatabase.kt`, currently schema version 14) with entities `Feed`,
`FeedItem`, `QueueEntry`, and `DownloadedEpisode`, accessed through `FeedDao`, `FeedItemDao`, and
`QueueDao`. Schema changes require bumping `AppDatabase.version` and adding a matching
`MIGRATION_n_n+1` in `data/local/Migrations.kt`; exported schemas live in `app/schemas/` and are
exercised by Room migration tests under `app/src/androidTest`.

Screens and ViewModels never touch the DAOs directly — they go through
`data/repository/FeedRepository.kt` (feed/article persistence) and `QueueRepository.kt` (queue
state). Feed parsing/fetching is a separate concern from persistence, kept in `data/feed/`.

App settings (playback speed defaults, font size, streaming toggle, refresh interval, etc.) live
in `data/settings/AppSettings.kt` / `SettingsDataStore.kt`, backed by Jetpack DataStore rather
than Room.

Backup/restore of the whole app's data (`data/backup/AppBackup.kt`, `AppBackupRepository.kt`)
serializes subscriptions and settings for export/import, separate from OPML.

## Feed ingestion

- `data/feed/FeedFetcher.kt` + `FeedParser.kt` fetch and parse RSS/Atom.
- `data/feed/FeedUpdateEngine.kt` reconciles a parsed feed into the database (new/changed items).
- `data/feed/AutoQueueAndDownloadEnforcer.kt` applies auto-queue/auto-download rules after a
  refresh, capping how many items are affected in one pass so an unlimited "keep" setting on a
  feed with a large backlog doesn't enqueue everything at once.
- `refresh/FeedRefreshWorker.kt` (WorkManager) runs ingestion periodically, scheduled from
  `MainActivity.onCreate` via `refresh/FeedRefreshScheduler.kt` using the interval from
  `SettingsDataStore`.
- `data/opml/` handles OPML import/export of subscriptions.
- `data/directory/FeedDirectory.kt` provides offline feed-directory search over a bundled OPML
  snapshot (`app/src/main/assets/feed_directory.opml`) as a fallback; `PodcastSearchService`
  prefers the live podcastindex.org API when configured (`PodcastIndexSearchProvider`).

## Downloads

`download/DownloadManager.kt` schedules per-episode downloads via WorkManager
(`EnclosureDownloadWorker.kt`), naming files with `EnclosureFileNaming.kt` and persisting
completed downloads through `EnclosureDownloadRepository.kt`. Downloads run through a dedicated
OkHttp client (`di/NetworkModule.kt`, `@DownloadHttpClient`), capped to 3 concurrent connections
so a large batch download doesn't open dozens of simultaneous connections across many podcast
hosts at once. `DownloadFeedbackCoordinator.kt` surfaces in-progress/failed download state to the
UI.

## Playback

`playback/PlaybackController.kt` is a `@Singleton` owning the Media3 `MediaController` connection
to `playback/PlaybackService.kt` (a `MediaSessionService`), and exposes a single
`PlaybackUiState` `StateFlow` consumed by both the mini-player and the episode-details screen's
in-page player. It persists the current episode and last resume position through
`SettingsDataStore` so playback survives process death, and clears that state on explicit stop or
on natural completion (`Player.STATE_ENDED`). Chapter data is fetched lazily per episode
(`ChaptersFetcher.kt`, `Chapter.kt`). `PlaybackUrlResolver.kt` resolves the enclosure URL to play
(downloaded file vs. streaming), and `MyCastsMediaNotificationProvider.kt` builds the media
notification.

## Widget

`widget/UnreadWidget.kt` is a Glance app widget showing per-feed unread counts, refreshed on app
launch and after scheduled feed refreshes. Tapping a feed in the widget launches `MainActivity`
with the feed id set as an extra, read in `onCreate` to pick the nav start destination.

## Dependency injection

Hilt modules under `di/`:

- `DatabaseModule` — Room database and DAOs, wired with all migrations.
- `NetworkModule` — the shared OkHttp client, plus the separate `@DownloadHttpClient` used only
  by downloads.
- `PodcastSearchModule` — feed directory / PodcastIndex search providers.
- `SettingsModule` — the DataStore-backed settings.
- `WorkModule` — `WorkManager`, and bindings for `FeedRefreshScheduling` /
  `DownloadScheduling` so callers depend on an interface rather than the concrete
  `FeedRefreshScheduler` / `DownloadManager`.

## Build and tooling

- Gradle Kotlin DSL, single `:app` module, Kotlin 2.0.21, AGP 8.7.3, KSP for Room/Hilt annotation
  processing.
- `compileSdk`/`targetSdk` 36, `minSdk` 31.
- Compose BOM 2024.12.01, Room 2.6.1, Media3 1.5.0, WorkManager 2.10.0, Hilt 2.52, Glance 1.1.1.
- Unit tests (`app/src/test`) run on the JVM via Robolectric; instrumented tests
  (`app/src/androidTest`) are limited to Room migration tests.
- CI (`.github/workflows/build.yml`) runs `assembleDebug testDebugUnitTest lintDebug` on every
  push/PR to `main`. `.github/workflows/release.yml` builds and signs a release APK on
  `vMAJOR.MINOR.PATCH` tags.

# MyCasts

MyCasts is a native Android podcast client. It handles subscribing to RSS/Atom feeds, browsing
and downloading episodes, playback with chapters and adjustable speed, a play-next queue, and a
home-screen widget showing unread counts per feed.

See [`docs/technical-design.md`](docs/technical-design.md) for an architecture overview and
[`docs/user-guide.md`](docs/user-guide.md) for a walkthrough of the app with screenshots.

## Requirements

- Android Studio (or a standalone JDK 21 + Android SDK setup)
- Android SDK: `compileSdk`/`targetSdk` 36, `minSdk` 31
- JDK 21

## Building

```bash
./gradlew assembleDebug              # build the debug APK
./gradlew installDebug                # install to a connected device/emulator
```

The debug build uses applicationId `com.bugzapperlabs.mycasts.debug` (suffix `.debug`), so it can
be installed alongside a release build without a signing-certificate conflict.

## Testing

```bash
./gradlew testDebugUnitTest          # JVM unit tests (Robolectric)
./gradlew lintDebug                  # Android lint
```

CI (`.github/workflows/build.yml`) runs `assembleDebug testDebugUnitTest lintDebug` on every push
and PR to `main`.

## Releases

Pushing a tag matching `vMAJOR.MINOR.PATCH` (e.g. `v1.2.3`) triggers
`.github/workflows/release.yml`, which builds a signed release APK and publishes it as a GitHub
Release. See the "Releases" section of [`CLAUDE.md`](CLAUDE.md) for details on signing and
versioning.

## License

No license file is currently present in this repository.

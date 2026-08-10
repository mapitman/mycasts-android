# Run `just` to list available recipes.
default:
    @just --list

# Build the debug APK.
build:
    ./gradlew assembleDebug

# Run JVM unit tests (Robolectric).
test:
    ./gradlew testDebugUnitTest

# Run a single test class, e.g. `just test-one com.bugzapperlabs.mycasts.playback.PlaybackControllerTest`
test-one class:
    ./gradlew testDebugUnitTest --tests "{{class}}"

# Android lint -- the only static check besides the test suite.
lint:
    ./gradlew lintDebug

# Install the debug APK to a connected device/emulator.
install:
    ./gradlew installDebug

# Launch the app on a connected device/emulator (installs first).
run: install
    adb shell am start -n com.bugzapperlabs.mycasts.debug/com.bugzapperlabs.mycasts.MainActivity

# Everything CI runs: build, test, lint.
ci: build test lint

# Full local loop: build, test, lint, install to device.
all: ci install

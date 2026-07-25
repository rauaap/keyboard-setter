# Android container build template

A minimal Android app skeleton with a fully containerized, CLI-driven Gradle
build. No JDK, Android SDK, or Gradle needed on the host — everything runs
inside a podman container defined by the `Containerfile`. The only host
dependency is `podman` (and `adb`, if you want to install on a device).

## Layout

```
Containerfile          Fedora + JDK 21 + Android SDK + Gradle toolchain
Makefile               build / shell / install targets (podman wrapper)
build.gradle           root project — pins the Android Gradle Plugin version
settings.gradle        project name + module list
app/                   the application module (one-activity skeleton)
```

## Build

Build the toolchain image once:

```sh
make image
```

Build a debug APK (rebuilds the image if needed):

```sh
make debug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Other targets:

```sh
make release            # assembleRelease
make clean              # gradle clean
make gradle ARGS="tasks"   # run any gradle task in the container
make shell              # interactive shell inside the build container
```

The Gradle cache is persisted in a named volume (`android-gradle-cache`) so
incremental builds and the debug keystore survive between runs.

## Install on a device

The build stays containerized; only `adb` runs on the host:

```sh
sudo dnf install android-tools     # Fedora
make install                       # adb install -r the debug APK
```

## First-run setup (Keyboard Setter)

Two one-time steps are needed after installing, both because the app is
sideloaded rather than installed from an app store:

1. **Grant the settings-write permission** (can't be granted through a normal
   in-app prompt):
   ```sh
   adb shell pm grant com.rauaap.keyboardsetter android.permission.WRITE_SECURE_SETTINGS
   ```
   The app detects if this is missing and shows this exact command.

2. **Enable the accessibility service**, via the app's "Open accessibility
   settings" button or Settings → Accessibility → Keyboard Setter. On
   Android 13+, sideloaded apps (`installerPackageName=null`, which is what
   `adb install` produces) are blocked from actually activating accessibility
   services until you lift that restriction once: **App info → ⋮ (top-right
   menu) → Allow restricted settings**. Without this step the toggle may
   *appear* to turn on (and even survive reinstalls) without the service ever
   actually receiving events — do this first if switching doesn't seem to
   work. Equivalent one-liner:
   ```sh
   adb shell cmd appops set com.rauaap.keyboardsetter android:access_restricted_settings allow
   ```

## Starting a new app from this template

Replace each placeholder value below. The package id (`com.example.app`) must be
changed in all four of its locations together — `namespace`, `applicationId`, the
source directory, and the `package` declaration.

| Placeholder | Value | Where |
|-------------|-------|-------|
| Package id | `com.example.app` | `app/build.gradle` (`namespace` + `applicationId`), source dir `app/src/main/java/com/example/app/`, `package` line in `MainActivity.java` |
| Project name | `AndroidApp` | `settings.gradle` (`rootProject.name`) |
| App label | `AndroidApp` | `app/src/main/res/values/strings.xml` (`app_name`) |
| Image / cache names | `android-builder`, `android-gradle-cache` | `Makefile` (`IMAGE`, `GRADLE_CACHE`) — optional |
| Launcher icon | placeholder "A" art | `app/src/main/res/drawable/ic_launcher_foreground.xml` |

Bump SDK / build-tools / Gradle versions in the `Containerfile` `ARG`s if needed.

## Notes

- **Container-only by design.** There is no Gradle wrapper (`gradlew`); the
  pinned Gradle version lives solely in the `Containerfile`. Build through
  `make`, not on the host.
- SDK level, build-tools, and Gradle versions are all `ARG`s at the top of the
  `Containerfile` — change them in one place.

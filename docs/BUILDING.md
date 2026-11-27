# Build and install

Use an OpenJDK 25 distribution such as Eclipse Temurin. Install the Android command-line tools and set `JAVA_HOME` and `ANDROID_HOME` for the current shell. Install the SDK packages and accept the applicable SDK licenses using `sdkmanager --licenses`.

```bash
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
./gradlew --console=plain testDebugUnitTest lintDebug assembleDebug assembleRelease
```

The release APK is intentionally unsigned: `app/build/outputs/apk/release/app-release-unsigned.apk`. Do not add a signing configuration to Gradle. Official APKs are signed locally with `scripts/prepare-release.sh` as described in [RELEASING.md](RELEASING.md).

Use `gradlew.bat` on Windows. The Wrapper uses Gradle 9.6.1 and validates the distribution SHA-256. The Android build uses AGP 9.3.1 with built-in Kotlin and Compose Compiler 2.4.21. Android Studio Rabbit 2 (2026.2.2 Patch 3) is compatible with this AGP series. Gradle resolves dependencies from Google Maven, Maven Central, and the Gradle Plugin Portal, as configured for the build.

```bash
adb devices -l
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL shell am start -W -n io.github.chuck852.linksonda/.MainActivity
```

Replace `SERIAL` with the device or emulator from `adb devices -l`. Updating an existing installation requires a matching signing key. Debug keys can differ between machines and CI runs. Use a disposable emulator when testing an APK signed with a different key.

## GitHub Actions

[Android CI](../.github/workflows/android-ci.yml) runs on pull requests to `main`, pushes to `main`, and manual dispatch. The workflow validates the Gradle Wrapper, runs JVM tests and lint, and builds the debug and unsigned release APKs on a GitHub-hosted Ubuntu runner. Each successful run uploads `LinkSonda-debug` and `LinkSonda-release-unsigned` for 14 days, each with its license notices. Compare a downloaded APK's SHA-256 with the value printed during the build.

The workflow requires read access to repository contents and uses the runner's debug key for the debug APK. The release artifact is unsigned and contains no signing secret. Selected releases follow the [manual release procedure](RELEASING.md).

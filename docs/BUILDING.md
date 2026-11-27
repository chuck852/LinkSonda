# Build and install

Use an OpenJDK 25 distribution such as Eclipse Temurin. Install the Android command-line tools and set `JAVA_HOME` and `ANDROID_HOME` for the current shell. Install the SDK packages and accept the applicable SDK licenses using `sdkmanager --licenses`.

```bash
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
./gradlew --console=plain testDebugUnitTest lintDebug assembleDebug
```

Use `gradlew.bat` on Windows. The Wrapper uses Gradle 9.6.1 and validates the distribution SHA-256. Gradle resolves dependencies from Google Maven, Maven Central, and the Gradle Plugin Portal, as configured for the build.

```bash
adb devices -l
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL shell am start -W -n io.github.chuck852.linksonda/.MainActivity
```

Replace `SERIAL` with the device or emulator from `adb devices -l`. Updating an existing installation requires a matching signing key. Debug keys can differ between machines and CI runs. Use a disposable emulator when testing an APK signed with a different key.

## GitHub Actions

[Android CI](../.github/workflows/android-ci.yml) runs on pull requests to `main`, pushes to `main`, and manual dispatch. The workflow validates the Gradle Wrapper, runs JVM tests and lint, and builds the debug APK on a GitHub-hosted Ubuntu runner. Each successful run uploads `LinkSonda-debug` for 14 days with the APK and its license notices. Compare the downloaded APK's SHA-256 with the value printed during the build.

The workflow requires read access to repository contents and uses the runner's debug key. Release signing and distribution are a separate step on the [roadmap](../ROADMAP.md).

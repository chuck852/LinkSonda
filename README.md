# LinkSonda

<img src="docs/branding/logo.png" alt="LinkSonda logo: a globe with a monitoring pulse" width="120">

[На русском](docs/README.ru.md)

LinkSonda is an Android project for diagnosing network and service availability. The UI Developer Preview candidate (`0.2.0`) has the approved Material 3 design system, a four-tab navigation shell, a domain-backed checks list and details screen, a universal check editor for all P0 protocols, and localized settings with a first-run flow. The production overview stays empty until real monitoring is connected. There is no network permission, no probe engine, no background monitoring and no incident history yet.

The [technical Developer Preview v0.1.0-dev.1](https://github.com/chuck852/LinkSonda/releases/tag/v0.1.0-dev.1) is the currently published preview. The UI Developer Preview v0.2.0 candidate is prepared for separate owner acceptance; see the [release notes](docs/releases/v0.2.0.md) for scope, signature and limitations.

## Build and install

Use JDK 25, Android SDK Platform 37.0, Build-Tools 36.0.0, and ADB. Set `JAVA_HOME` and `ANDROID_HOME` before running the Gradle Wrapper.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n io.github.chuck852.linksonda/.MainActivity
```

The APK supports Android 8.0 and later (min API 26, target API 37). Choose a device with `adb -s SERIAL` when several are connected. See the [build guide](docs/BUILDING.md) for SDK and CI commands.

[GitHub Releases](https://github.com/chuck852/LinkSonda/releases) is the distribution channel for approved builds. GitHub Actions builds a debug APK for pull requests and pushes to `main`. Download the `LinkSonda-debug` artifact from a successful run. CI uses a debug signing key and provides a build for development and testing.

## Project resources

- [Roadmap](ROADMAP.md) and [changelog](CHANGELOG.md)
- [Architecture](docs/ARCHITECTURE.md), [navigation and overview](docs/NAVIGATION.md), [checks and details](docs/CHECKS.md), [check editor](docs/CHECK_EDITOR.md), [settings, localization and first run](docs/SETTINGS.md), [theme and design system](docs/THEME.md), and [contributing guide](CONTRIBUTING.md)
- [Security policy](SECURITY.md) and [code of conduct](CODE_OF_CONDUCT.md)

LinkSonda is licensed under the [Apache License 2.0](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for dependencies and distributed build tooling.

# Third-party notices

The project license applies to LinkSonda's original work. Third-party components retain their own copyright and license terms. Keep these notices and the license files with distributed copies of the APK or source.

| Component | Scope | License and source |
| --- | --- | --- |
| Gradle Wrapper 9.6.1 | Wrapper scripts and JAR in the source tree | Apache-2.0. Script headers and the JAR's `META-INF/LICENSE` are preserved. [Bundled license](third_party/gradle-wrapper-LICENSE.txt) · [Gradle source](https://github.com/gradle/gradle/tree/v9.6.1/platforms/core-runtime/wrapper-main) |
| AndroidX, including Compose, Room, DataStore and the native graphics-path library | APK runtime | Apache-2.0. [Upstream license as included in published artifacts](third_party/androidx-LICENSE.txt) · [AndroidX source](https://android.googlesource.com/platform/frameworks/support/) |
| OkHttp 5.5.1, including okhttp-android, and Okio 3.18.1 | APK runtime | Apache-2.0. [OkHttp license](https://github.com/square/okhttp/blob/parent-5.5.1/LICENSE.txt) · [Okio license](https://github.com/square/okio/blob/3.18.1/LICENSE.txt) |
| Material Icons Core 1.7.8 | APK runtime | Apache-2.0. [AndroidX source](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/material/material-icons-core/) |
| Kotlin standard library 2.4.21 | APK runtime | Apache-2.0. [Kotlin license](https://github.com/JetBrains/kotlin/blob/v2.4.21/license/LICENSE.txt) |
| Kotlin coroutines 1.9.0 and kotlinx.serialization 1.9.0 | APK runtime | Apache-2.0. [Coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/1.9.0/LICENSE.txt) · [Serialization](https://github.com/Kotlin/kotlinx.serialization/blob/v1.9.0/LICENSE.txt) |
| JetBrains Java annotations 23.0.0 | Runtime dependency | Apache-2.0. [JetBrains source](https://github.com/JetBrains/java-annotations) |
| JSpecify 1.0.0 | Runtime dependency | Apache-2.0. [JSpecify source](https://github.com/jspecify/jspecify/tree/v1.0.0) |
| Guava ListenableFuture 1.0 | APK runtime | Apache-2.0. Guava Authors. [Guava source](https://github.com/google/guava) |
| JUnit 4.13.2 | JVM tests | EPL-1.0. [Bundled license](third_party/junit-LICENSE.txt) · [JUnit source](https://github.com/junit-team/junit4/tree/r4.13.2) |
| Hamcrest Core 1.3 | JVM tests | BSD-3-Clause. [Bundled license and copyright](third_party/hamcrest-LICENSE.txt) · [Hamcrest source](https://github.com/hamcrest/JavaHamcrest) |

Android Gradle Plugin 9.3.1, the Kotlin and Compose Compiler 2.4.21 build plugins, the SDK, and GitHub Actions are obtained during builds. The project includes the Gradle Wrapper separately as listed above. The full Apache-2.0 text is in [LICENSE](LICENSE).

Review the resolved runtime and test dependency graphs and update these notices when dependencies change. Keep any supplier-specific copyright, license, and notice files.

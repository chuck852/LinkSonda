# LinkSonda

Каркас Android-приложения для диагностики доступности сети. Текущая версия открывает стартовый экран. Сетевые проверки и мониторинг запланированы.

Android app foundation for network availability diagnostics. The current build displays a starter screen. Network probes and monitoring are planned.

Build and verify the debug APK with JDK 25, Android SDK Platform 37.0 and Build-Tools 36.0.0.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

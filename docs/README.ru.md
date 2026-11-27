# LinkSonda

<img src="branding/logo.png" alt="Логотип LinkSonda: глобус с импульсом мониторинга" width="120">

[English](../README.md)

LinkSonda — Android-проект для диагностики доступности сети и отдельных ресурсов. Текущая версия `0.1.0` содержит стартовый экран и инфраструктуру сборки и тестирования. Сетевые проверки, фоновый мониторинг и история инцидентов запланированы.

## Сборка и установка

Понадобятся JDK 25, Android SDK Platform 37.0, Build-Tools 36.0.0 и ADB. Укажите `JAVA_HOME` и `ANDROID_HOME`.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n io.github.chuck852.linksonda/.MainActivity
```

Для нескольких подключённых устройств укажите `adb -s SERIAL`. Минимальная версия ОС — Android 8.0 (API 26), целевой API — 37. Согласованные APK публикуются в [GitHub Releases](https://github.com/chuck852/LinkSonda/releases). Debug APK для тестирования можно скачать из успешного запуска Android CI в архиве `LinkSonda-debug`. [Руководство по сборке](BUILDING.md)

## Документация

- [Архитектура](ARCHITECTURE.md) и [дорожная карта](../ROADMAP.md)
- [Участие в проекте](../CONTRIBUTING.md) и [сообщения об уязвимостях](../SECURITY.md)
- [Apache-2.0](../LICENSE) и [уведомления о сторонних компонентах](../THIRD_PARTY_NOTICES.md)

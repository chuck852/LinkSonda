# LinkSonda

<img src="branding/logo.png" alt="Логотип LinkSonda: глобус с импульсом мониторинга" width="120">

[English](../README.md)

LinkSonda — Android-проект для диагностики доступности сети и отдельных ресурсов. Опубликованный UI Developer Preview [`0.2.0`](https://github.com/chuck852/LinkSonda/releases/tag/v0.2.0) содержит утверждённую дизайн-систему Material 3, навигацию с четырьмя вкладками, список проверок с экраном деталей на доменных моделях, универсальный конструктор всех P0-протоколов и локализованные настройки с первым запуском. В production обзор остаётся пустым до подключения реального мониторинга. Разрешения на сеть, движка проверок, фонового мониторинга и истории инцидентов пока нет.

Опубликован [UI Developer Preview v0.2.0](https://github.com/chuck852/LinkSonda/releases/tag/v0.2.0); предыдущий [технический Developer Preview v0.1.0-dev.1](https://github.com/chuck852/LinkSonda/releases/tag/v0.1.0-dev.1) остаётся доступен. Область, подпись и ограничения v0.2.0 описаны в [release notes](releases/v0.2.0.md).

## Сборка и установка

Понадобятся JDK 25, Android SDK Platform 37.0, Build-Tools 36.0.0 и ADB. Укажите `JAVA_HOME` и `ANDROID_HOME`.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n io.github.chuck852.linksonda/.MainActivity
```

Для нескольких подключённых устройств укажите `adb -s SERIAL`. Минимальная версия ОС — Android 8.0 (API 26), целевой API — 37. Согласованные APK публикуются в [GitHub Releases](https://github.com/chuck852/LinkSonda/releases). Debug APK для тестирования можно скачать из успешного запуска Android CI в архиве `LinkSonda-debug`. [Руководство по сборке](BUILDING.md)

## Документация

- [Архитектура](ARCHITECTURE.md), [навигация и обзор](NAVIGATION.md), [проверки и детали](CHECKS.md), [конструктор проверок](CHECK_EDITOR.md), [настройки, локализация и первый запуск](SETTINGS.md), [тема и дизайн-система](THEME.md) и [дорожная карта](../ROADMAP.md)
- [Участие в проекте](../CONTRIBUTING.md) и [сообщения об уязвимостях](../SECURITY.md)
- [Apache-2.0](../LICENSE) и [уведомления о сторонних компонентах](../THIRD_PARTY_NOTICES.md)

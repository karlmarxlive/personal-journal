# Мой Журнал

Личный дневник для Android 14+ на Kotlin и Jetpack Compose. Работает без интернета: записи и фотографии хранятся на устройстве.

- Текст, фотографии и рукописные заметки.
- Вопросы-шаблоны, автосохранение черновика и календарь записей.
- Запечатанные записи защищены от редактирования.
- Экспорт в ZIP с PDF, Markdown и фотографиями.
- Русский интерфейс, светлая и тёмная темы.

## Сборка

Нужны JDK 21, Android SDK 36 и Build Tools 36.0.0. Укажите SDK через `ANDROID_HOME` или `local.properties`, JDK — через `JAVA_HOME`.

```powershell
.\scripts\build.ps1 assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Готовые APK: [1.0.1](dist/MyJournal-1.0.1.apk) и [1.0.0](dist/MyJournal-1.0.0.apk).

Подробнее: [руководство и release-сборка](docs/guide.md). Ключи подписи, локальные настройки и временные файлы сборки не хранятся в репозитории.

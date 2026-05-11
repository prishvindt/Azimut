# Азимут 1.1

Android-приложение для прохождения тестов из `.docx`-файлов.

## Основное

- Название приложения: `Азимут`.
- Package ID: `com.prishvindt.azimut`.
- minSdk: 30 / Android 11+.
- Kotlin + обычные Android View/XML.
- Хранение тестов: JSON-файлы в подпапке `test` выбранной пользователем папки.
- Картинки из `.docx` сохраняются в папку assets конкретного теста и отображаются при прохождении попытки.
- Проверка обновлений через `update.json` в GitHub.
- Автоматическое создание `.nomedia` в папках изображений тестов.

## Сборка debug APK

```bash
./gradlew :app:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Release

Для релизной сборки создай keystore в Android Studio:

```text
Build → Generate Signed Bundle / APK
```

Ключ подписи не включен в проект.


## Обновления через GitHub

Приложение проверяет файл:

```text
https://raw.githubusercontent.com/prishvindt/Azimut/main/update.json
```

При выпуске новой версии сначала создай GitHub Release и загрузи туда signed release APK, затем обнови `update.json` в ветке `main`.

Пример `update.json`:

```json
{
  "versionName": "1.1.0",
  "versionCode": 3,
  "apkUrl": "https://github.com/prishvindt/Azimut/releases/download/v1.1.0/Azimut-1.1.0-release.apk",
  "releaseNotes": "Описание изменений",
  "required": false
}
```

`versionCode` всегда должен быть больше, чем у предыдущей версии.

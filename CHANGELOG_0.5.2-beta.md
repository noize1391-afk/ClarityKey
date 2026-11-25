# ClarityKey 0.5.2-beta

## Изменения

- Публичная версия изменена на `0.5.2-beta`.
- `versionCode` повышен до `54004` для установки поверх тестовой 5.4.0-beta.3 при совпадающей подписи.
- Package ID сохранён: `com.claritykey.keyboard`.
- GitHub Actions теперь собирает устанавливаемый debug APK без секретов подписи.
- При наличии постоянного keystore workflow собирает подписанный release APK.
- Push тега `v0.5.2-beta` автоматически создаёт GitHub Release и прикладывает signed APK.
- Удалены небезопасные пароли подписи по умолчанию из Gradle-конфигурации.
- Добавлены инструкции загрузки проекта, настройки подписи и выпуска релиза через Termux.

Функциональный код клавиатуры, словари и OCR-модели не изменялись.

## Проверки

- Контроль `assets.sha256`: 4/4 файлов прошли.
- `testDebugUnitTest`: 25/25 тестов прошли.
- `lintDebug`: завершён без блокирующих ошибок.
- `assembleDebug`: успешно.
- Готовый APK: `com.claritykey.keyboard`, versionCode `54004`, versionName `0.5.2-beta`.
- SHA-256 debug APK: `d4858485f8221d5e8e89eaf553dd3ce45e25ded686629652f78c562f4aa2ef4e`.

GitHub Actions и подписанный release ещё не проверены в удалённом репозитории.

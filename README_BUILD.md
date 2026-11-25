# ClarityKey 0.5.2-beta — сборка

Пакет: `com.claritykey.keyboard`. Отображаемая версия: `0.5.2-beta`.
Внутренний `versionCode` — `54004`, чтобы сборка устанавливалась поверх предыдущей тестовой beta.3.

Нужны JDK 17 и Android SDK Platform 35 / Build Tools 35.0.0. SDK задаётся ANDROID_HOME или локальным sdk.dir в local.properties. Gradle 8.10.2 запускается через включённый Wrapper.

```bash
sha256sum -c assets.sha256
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

build_apk.sh выполняет тесты/Lint/release и копирует готовый результат в корень проекта. Словари и OCR уже находятся в assets; их загрузка во время сборки не нужна. Для первого запуска Gradle и Robolectric нужен доступ к репозиториям зависимостей.

Release подписывается только существующим ключом: `CLARITYKEY_KEYSTORE_PATH` (лучше абсолютный путь), `CLARITYKEY_KEYSTORE_PASSWORD`, `CLARITYKEY_KEY_ALIAS`, `CLARITYKEY_KEY_PASSWORD`. Без ключа GitHub Actions собирает устанавливаемый debug APK. GitHub Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Нельзя генерировать новый ключ для каждого обновления.

Debug использует обычную локальную debug-подпись и тот же applicationId; это не гарантирует установку поверх имеющейся клавиатуры. Удалять приложение для обхода несовпадения подписей не требуется для проверки исходников.

В архив исходников не включаются Android SDK, Gradle caches, local.properties, ключи подписи и промежуточные build-каталоги. Результаты проверок находятся в validation/.

## Новый ID и язык настроек

Application ID: com.claritykey.keyboard. Эта версия устанавливается отдельно от com.breeno.keyboard.ru и не забирает автоматически его данные. Старую версию удалять не нужно. Язык настроек переключается в Languages and layouts / Языки и раскладки; ввод RU/EN остаётся независимым.

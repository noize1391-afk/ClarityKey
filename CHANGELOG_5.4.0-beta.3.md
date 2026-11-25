# ClarityKey 5.4.0-beta.3

## Поведение

Новый applicationId/namespace `com.claritykey.keyboard`. Это отдельная установка Android, а не обновление com.breeno.keyboard.ru. Настройки доступны на RU / EN с сохранением выбора независимо от языка ввода.

## Изменённые файлы и причины

- Все Kotlin-файлы `app/src/main/java/com/breeno/keyboard/ru/` перенесены в `app/src/main/java/com/claritykey/keyboard/`; изменены package/import и обращения к R.
- Все существующие тесты перенесены в соответствующий новый пакет.
- `app/build.gradle.kts`: applicationId, namespace, версия 54003 / 5.4.0-beta.3.
- `app/src/main/AndroidManifest.xml`: локализованная подпись сервиса кодов; относительные имена компонентов теперь разрешаются в новом namespace.
- `app/src/main/res/xml/method.xml`: новый полный путь SettingsActivity.
- `app/proguard-rules.pro`: новые пути компонентов, сохраняемых R8.
- `settings/SettingsActivity.kt`: локализованные ресурсы вместо 649 вхождений строк, селектор языка, сохранение экрана при recreate, локализованные названия папок без изменения их ключей, новый launcher alias.
- `settings/SettingsLocale.kt` (новый): Context локали настроек без изменения глобального Locale или языка ввода.
- `prefs/BreenoPreferences.kt`: отдельный сохранённый параметр settings_ui_language.
- `app/src/main/res/values/strings.xml`, `values-ru/strings.xml`, `values/settings_strings.xml`, `values-ru/settings_strings.xml`: английские и русские ресурсы. Старое сообщение о «резервном режиме» шифрования заменено на корректное сообщение о недоступности новых зашифрованных записей.
- `app/src/test/java/com/claritykey/keyboard/SettingsLanguageTest.kt` (новый): 6 проверок локали, данных, строк, всех экранов, восстановления состояния и ID компонентов.
- `settings.gradle.kts`, `.github/workflows/build-apk.yml`: публичные названия сборки ClarityKey.
- `PROJECT_CONTEXT.md`, `README_BUILD.md`, этот CHANGELOG: актуальный ID, локализация, переход и проверки. Контекст beta.2 сохранён отдельным историческим файлом.

## Проверки

- `testDebugUnitTest`: **25/25 passed** — 19 прежних регрессий и 6 новых проверок SettingsLanguageTest.
- Все 22 экрана настроек открыты в Android sandbox на RU и EN (44 прогона экрана); проверены восстановление экрана, сохранение языка, неизменность языка ввода/шаблонов, наличие и форматирование строк.
- `lintDebug`: **0 errors, 160 warnings**. Предупреждения сохранены в `validation/lint-debug.txt`; общей baseline/отключения ошибок не добавлено.
- `assembleDebug`, `assembleRelease`: успешно, включая R8 и shrinkResources; release без ключа пользователя — unsigned.
- Manifest готового release APK: package `com.claritykey.keyboard`, versionName `5.4.0-beta.3`, новый FileProvider и компоненты; CRC APK корректна, все 4 словаря/OCR-файла совпадают с `assets.sha256`.
- 592 ключа settings_strings.xml совпадают между RU/EN, спецификаторы форматирования согласованы; пути и package всех Kotlin-файлов совпадают.
- Это компиляция, статические проверки и Robolectric/API 28, **не проверка на реальном телефоне**. GitHub Actions ещё не запускался.


## Границы

Текст пользовательских слов, шаблонов, секретов, а также русские примеры ввода не переводятся. Панели самой клавиатуры не входят в обещание полной EN-локализации. Новые зависимости не добавлены. Словари, OCR-модели и стиль клавиатуры сохранены.

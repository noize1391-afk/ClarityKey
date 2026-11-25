# ClarityKey — актуальный технический контекст

**25.11.2026 · 0.5.2-beta · versionCode 54004**

## Текущий результат

Публичная beta переименована в **0.5.2-beta**. ApplicationId и namespace: **`com.claritykey.keyboard`**. `versionCode` намеренно повышен с 54003 до 54004, поэтому новая сборка может обновить предыдущую тестовую 5.4.0-beta.3 с тем же сертификатом. Исходная beta.3 сохранена отдельно; функциональный код клавиатуры при переименовании версии не изменялся.

Android считает эту версию отдельным приложением. Она может существовать рядом со старой, но не обновляет её и не получает автоматически её данные или статус активной IME. После установки новую IME надо отдельно включить и выбрать. Это свойство смены applicationId, а не проблема подписи. Прежнее приложение удалять не требуется.

## Язык настроек

Путь: **Языки и раскладки → Язык интерфейса → Русский / English**; в английском интерфейсе — **Languages and layouts → Interface language**.

- Все 22 экрана SettingsActivity, их заголовки, кнопки, подсказки, диалоги, Toast и форматируемые сообщения переведены через стандартные Android-ресурсы.
- `values/` — английский fallback, `values-ru/` — русский. В каждом `settings_strings.xml` 592 согласованных ключа; базовые strings.xml также локализованы. Литералы букв калибровки, русские примеры, синтаксис токенов и автноним «Русский» намеренно остаются русскими данными.
- `SettingsLocale` применяет локаль только к Context экрана настроек через attachBaseContext. Locale.setDefault и глобальные настройки процесса не меняются. Язык ввода, IME subtype, словари и текст пользователя независимы от языка интерфейса.
- При первом запуске выбирается русский для системной локали ru, иначе английский. Явный выбор RU/EN сохраняется в `settings_ui_language`. Переключение вызывает recreate; текущий экран и выбранные группы восстанавливаются из savedInstanceState.
- Идентификаторы папок шаблонов остаются «Работа», «Доставка», «Личное». В EN показываются Work, Delivery, Personal. Текст и ключи существующих шаблонов не переводятся и не переписываются.
- Это локализация **настроек**. Остальные программные панели IME не объявляются полностью переведёнными на EN.

## Пакет и компоненты

- Main Kotlin: `app/src/main/java/com/claritykey/keyboard/…`; tests: `app/src/test/java/com/claritykey/keyboard/…`.
- Service: `com.claritykey.keyboard.ime.BreenoRuInputMethodService`.
- Activity: `com.claritykey.keyboard.settings.SettingsActivity`.
- Launcher alias: `com.claritykey.keyboard.settings.LauncherAlias`.
- Clipboard FileProvider: `com.claritykey.keyboard.clipboard.fileprovider` (в манифесте производная от applicationId).
- Обновлены package/import, XML method settingsActivity, alias, R8 keep rules, namespace/applicationId. Исторические названия внутренних классов, файлов БД и ключей preferences не переименованы: они не задают публичный ID приложения и не требуют переписывания архитектуры.
- Корневое имя Gradle и имя GitHub Actions artifact — ClarityKey. Переменные локальной подписи: `CLARITYKEY_KEYSTORE_PATH`, `CLARITYKEY_KEYSTORE_PASSWORD`, `CLARITYKEY_KEY_ALIAS`, `CLARITYKEY_KEY_PASSWORD`.

## Сборка и проверки

Один Android-модуль, Kotlin 2.0.21, AGP 8.7.2, Gradle Wrapper 8.10.2, JDK 17. minSdk 28, compile/targetSdk 35. Новых зависимостей в beta.3 нет.

- `testDebugUnitTest`: **25/25 passed** — 19 прежних регрессий и 6 проверок SettingsLanguageTest.
- Все 22 экрана настроек открыты в Android sandbox на RU и EN (44 прогона экрана); проверены восстановление экрана, сохранение языка, неизменность языка ввода/шаблонов, наличие и форматирование строк.
- `lintDebug`: **0 errors, 160 warnings**. Предупреждения сохранены в `validation/lint-debug.txt`; общей baseline/отключения ошибок не добавлено.
- `assembleDebug`: успешно; APK подписан стандартным debug-сертификатом и пригоден для тестовой установки. Для beta.3 отдельно подтверждён `assembleRelease` с R8/shrinkResources; release 0.5.2 должен быть проверен GitHub Actions после добавления постоянного ключа.
- Manifest готового debug APK: package `com.claritykey.keyboard`, versionCode `54004`, versionName `0.5.2-beta`, minSdk 28, targetSdk 35. SHA-256: `d4858485f8221d5e8e89eaf553dd3ce45e25ded686629652f78c562f4aa2ef4e`. Все 4 словаря/OCR-файла совпадают с `assets.sha256`.
- 592 ключа settings_strings.xml совпадают между RU/EN, спецификаторы форматирования согласованы; пути и package всех Kotlin-файлов совпадают.
- Это компиляция, статические проверки и Robolectric/API 28, **не проверка на реальном телефоне**. GitHub Actions ещё не запускался.


Состав словарей и OCR не менялся: RU 250000, EN 200000, rus.traineddata + eng.traineddata. `assets.sha256` проверяет конкретные байты. Лицензии/происхождение — `third_party/NOTICE.md`.

## Данные и переход на новый ID

Использовать существующий ручной `.claritykey` экспорт/импорт для поддерживаемых настроек, пользовательских слов, сокращений и шаблонов. Совместимость формата сохранена. Архив не переносит сейф, профиль автозаполнения или историю буфера; ключ Android Keystore старого приложения недоступен новому пакету. Автоматический перенос этих секретов не реализован и не заявляется.

Дальнейшие обновления новой ветки должны сохранять `com.claritykey.keyboard` и постоянный сертификат. Без предоставленного ключа release собирается unsigned. Смена ID сама по себе не означает готовность к публикации: необходима проверка устройства, подписи и требований конкретной площадки.

## Продолжение работы

- `.github/workflows/build-apk.yml` прогоняет тесты/Lint и всегда выдаёт устанавливаемый APK: debug без секретов либо signed release с четырьмя GitHub Secrets. Тег `v0.5.2-beta` автоматически создаёт GitHub Release только при наличии постоянного ключа. GitHub подключён к `noize1391-afk`, но доступных репозиториев нет; сборка на GitHub пока не запускалась.
- Из beta.2 сохранены: исправления сейфа/Keystore, сеансов ввода, приватности, очереди свайпа, геометрии клавиш, Undo/Redo, Unicode удаления, OCR-фильтра, сборки, словарей и базовые accessibility-узлы.
- Остаточные задачи: реальная проверка IME/Autofill/биометрии/TalkBack/OCR, производительность холодного старта полного словаря, корпус ложных автозамен, оставшиеся Lint warnings. Не выдавать JVM-тесты за тестирование телефона.
- История: `AUDIT_5.4.0-beta.1.md`, `CONTEXT_5.4.0-beta.2.md`, прежние CHANGELOG. Их сведения о старом ID и прошлых проверках исторические; этот файл и `CHANGELOG_0.5.2-beta.md` авторитетны для текущей версии.
- Откат исходников — отдельный ZIP beta.2. Он не перезаписан. Переключение пользователем на старую установленную IME возможно, пока старое приложение сохранено.

## Карта компонентов (сохранившаяся архитектура)

| Узел | Файлы | Откуда получает данные и что с ними делает |
|---|---|---|
| Вход в приложение | `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/method.xml`, `ime/BreenoRuInputMethodService.kt` | Android связывается с `InputMethodService`, передаёт `EditorInfo` и `InputConnection`. Объявлены подтипы RU/EN, Activity настроек, alias значка, сервис уведомлений и закрытый FileProvider |
| Поле и состояние ввода | `ime/BreenoRuInputMethodService.kt` | Выбирает раскладку, роль Enter и защиту поля. Координирует незавершённое слово (`composing`), регистр, подсказки, жесты, курсор и панели. Через `setComposingText` обновляет набираемое слово, через `commitText` передаёт результат редактору |
| Геометрия и касания | `layout/BreenoLayouts.kt`, `layout/AdaptiveBottomLayout.kt`, `view/BreenoKeyboardView.kt`, `view/BreenoKeyPopupView.kt` | Спецификация рядов задаёт визуальные и сенсорные прямоугольники. Canvas рисует клавиши. View различает нажатие, перекрытие двух пальцев, удержание, короткий жест, непрерывный ввод и перемещение курсора по пробелу. Учитываются вырезы, жестовая навигация и нижние углы |
| Словари и подсказки | `engine/BreenoDictionaryEngine.kt`, `engine/SwipeGestureDecoder.kt`, `engine/TouchCalibrationModel.kt` | Словари индексируются по полному слову и первым 1–3 буквам. Учитываются частота, соседство клавиш, е/ё, опечатки, контекст и окончания. Калибровка хранит смещение для языка и ориентации. Свайп сравнивает траектории по 28 точкам и возвращает до трёх слов |
| Персонализация | `engine/PersonalMemoryEngine.kt`, `prefs/BreenoPreferences.kt` | Пользовательский словарь и веса, память слов, биграмм и триграмм, учёт отвергнутых замен, профиль автозаполнения. У словарного движка дополнительно есть сессионные биграммы. Сокращения, шаблоны, словарь и память фраз — разные наборы данных |
| Верхняя панель | `view/BreenoHeaderBarView.kt` и обработчики в IME | Одна область переключается между инструментами, словами, системным автозаполнением, быстрыми вставками, диктовкой и переводом. Системные карточки создаёт выбранная пользователем служба Autofill. Локальный профиль заполняет отдельные поля самостоятельно |
| Буфер и медиа | `engine/BreenoClipboardManager.kt`, `clipboard/ClipboardRepository.kt`, `ClipboardCrypto.kt`, `MediaClipboardStorage.kt`, `RecentScreenshotDetector.kt`, `ClipboardImagePickerActivity.kt` в каталоге `clipboard/` | Системный буфер, выбор файла и MediaStore подают данные в репозиторий SQLite v5. Реализованы дедупликация, закрепление, отдельные сроки и квоты текста и изображений. Файлы и миниатюры хранятся в приватном каталоге. Для вставки изображения IME сверяет MIME-типы редактора и использует FileProvider и `commitContent` |
| Распознавание и панель буфера | `clipboard/SmartPasteAnalyzer.kt`, `clipboard/SmartPasteVisionOcr.kt`, `view/BreenoClipboardPanelView.kt` | Анализатор выделяет ссылки, контакты, даты, суммы, реквизиты, трекинг и другие фрагменты. Tesseract читает изображение на устройстве, возвращает текст, строки и абзацы, повторно использует анализатор. В панели есть вставка фрагмента, просмотр изображения, замазывание, шаблоны и сейф |
| Остальные инструменты | `voice/BreenoVoiceHelper.kt`, `ime/OtpNotificationListenerService.kt`, `engine/RamblerSpeechProcessor.kt`, `engine/LayoutSwitcherAndTranslator.kt`, `engine/InlineCalculator.kt`, `engine/TextPowerTools.kt` | Системный распознаватель речи, локальная обработка продиктованного текста, коды из уведомлений, калькулятор, служебные операции над текстом, транслитерация и исправление неверной раскладки |
| Оформление и управление | `skin/BreenoSkinEngine.kt`, `view/BreenoMenuPanelView.kt`, `view/BreenoEmojiPanelView.kt`, `view/BreenoTextEditPanelView.kt`, `settings/SettingsActivity.kt`, `prefs/BackupExportHelper.kt` | Настройки изменяют предпочтения, IME применяет их при перестроении. Есть настройка палитры и клавиш, отдельные панели эмодзи и редактирования, предпросмотр и ручной импорт/экспорт через системный выбор файла |


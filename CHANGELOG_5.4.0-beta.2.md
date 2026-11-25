# ClarityKey 5.4.0-beta.2

Исправлены блокирующие ошибки компиляции, защита сейфа и правила приватности, изоляция сеансов ввода, ряд ошибок набора и рассогласование assets локальной/CI-сборки. Подробные причины и границы — PROJECT_CONTEXT.md.

## Изменённые файлы

- `.github/workflows/build-apk.yml`
- `PROJECT_CONTEXT.md`
- `README_BUILD.md`
- `app/build.gradle.kts`
- `app/src/main/assets/en_freq_dict.txt`
- `app/src/main/assets/ru_freq_dict.txt`
- `app/src/main/java/com/breeno/keyboard/ru/clipboard/ClipboardCrypto.kt`
- `app/src/main/java/com/breeno/keyboard/ru/clipboard/ClipboardImagePickerActivity.kt`
- `app/src/main/java/com/breeno/keyboard/ru/clipboard/ClipboardRepository.kt`
- `app/src/main/java/com/breeno/keyboard/ru/clipboard/MediaClipboardStorage.kt`
- `app/src/main/java/com/breeno/keyboard/ru/clipboard/SmartPasteAnalyzer.kt`
- `app/src/main/java/com/breeno/keyboard/ru/clipboard/SmartPasteVisionOcr.kt`
- `app/src/main/java/com/breeno/keyboard/ru/engine/BreenoClipboardManager.kt`
- `app/src/main/java/com/breeno/keyboard/ru/engine/BreenoDictionaryEngine.kt`
- `app/src/main/java/com/breeno/keyboard/ru/engine/PersonalMemoryEngine.kt`
- `app/src/main/java/com/breeno/keyboard/ru/engine/TextPowerTools.kt`
- `app/src/main/java/com/breeno/keyboard/ru/ime/BreenoRuInputMethodService.kt`
- `app/src/main/java/com/breeno/keyboard/ru/ime/OtpNotificationListenerService.kt`
- `app/src/main/java/com/breeno/keyboard/ru/layout/BreenoLayouts.kt`
- `app/src/main/java/com/breeno/keyboard/ru/prefs/BackupExportHelper.kt`
- `app/src/main/java/com/breeno/keyboard/ru/prefs/BreenoPreferences.kt`
- `app/src/main/java/com/breeno/keyboard/ru/settings/SettingsActivity.kt`
- `app/src/main/java/com/breeno/keyboard/ru/view/BreenoClipboardPanelView.kt`
- `app/src/main/java/com/breeno/keyboard/ru/view/BreenoHeaderBarView.kt`
- `app/src/main/java/com/breeno/keyboard/ru/view/BreenoKeyboardView.kt`
- `app/src/main/java/com/breeno/keyboard/ru/voice/BreenoVoiceHelper.kt`
- `build_apk.sh`

## Добавленные файлы

- `AUDIT_5.4.0-beta.1.md`
- `app/src/main/assets/tessdata/eng.traineddata`
- `app/src/main/assets/tessdata/rus.traineddata`
- `app/src/test/java/com/breeno/keyboard/ru/PrivacyAndLayoutRegressionTest.kt`
- `assets.sha256`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradlew`
- `gradlew.bat`
- `third_party/NOTICE.md`
- `third_party/frequency-license.txt`
- `third_party/frequency-readme.md`
- `third_party/ocr-license.txt`

## Проверки

- `testDebugUnitTest`: **19/19 passed** — 10 прежних тестов и 9 новых регрессионных проверок (Robolectric, Android API 28).
- `lintDebug`: **0 ошибок, 191 предупреждение**. Полный перечень — `validation/lint-debug.txt`; предупреждения не выданы за исправленные.
- `assembleDebug`: успешно. `assembleRelease`: успешно, включая R8 и shrinkResources; без ключа пользователя release остаётся unsigned.
- APK manifest: `com.breeno.keyboard.ru`, версия `5.4.0-beta.2`; CRC обоих APK корректна. Все четыре словаря/OCR-файла в обоих APK совпали с `assets.sha256`.
- `bash -n build_apk.sh`: успешно; Gradle Wrapper включён, SHA-256 дистрибутива зафиксирован.
- Сборка выполнялась с JDK 17.0.21.1 и Android SDK 35. Подтверждены компиляция и JVM/Android-sandbox tests; эмулятор и реальное устройство не запускались. При запуске Kotlin daemon был один автоматический повтор; итоговая задача Gradle завершилась BUILD SUCCESSFUL.


## Откат

Исходный ClarityKey_v5.4.0-beta.1.zip сохранён отдельно и не перезаписан. Для отката кода использовать его; прежний аудит внутри этого архива — AUDIT_5.4.0-beta.1.md. Пользовательские данные автоматически не сбрасываются при обновлении. Полная очистка запускается только существующей явной командой пользователя.

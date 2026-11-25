# Загрузка ClarityKey на GitHub через Termux

## 1. Подготовка Termux

Установить Termux из F-Droid или GitHub, затем выполнить:

```bash
pkg update
pkg install git gh openssh
termux-setup-storage
gh auth login
```

В `gh auth login` выбрать `GitHub.com` → `HTTPS` → вход через браузер.

## 2. Создание репозитория и загрузка проекта

Распаковать архив исходников в `Download/ClarityKey`, затем:

```bash
cd ~/storage/downloads/ClarityKey
git init
git branch -M main
git add .
git commit -m "ClarityKey 0.5.2-beta"
gh repo create ClarityKey --public --source=. --remote=origin --push
```

После push открыть вкладку **Actions** репозитория. Workflow `Build ClarityKey APK`
сам запустит тесты и сборку. Готовый APK находится внизу страницы запуска в разделе
**Artifacts**. Пока секреты подписи не добавлены, это `ClarityKey-0.5.2-beta-debug.apk`.

## 3. Постоянный ключ release-подписи

Создать ключ один раз. Пароли заменить своими и сохранить их вместе с файлом ключа:

```bash
pkg install openjdk-17
keytool -genkeypair -v -keystore claritykey-release.jks -alias claritykey \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w 0 claritykey-release.jks > claritykey-release.base64
```

В репозитории открыть **Settings → Secrets and variables → Actions** и добавить:

- `KEYSTORE_BASE64` — содержимое `claritykey-release.base64`;
- `KEYSTORE_PASSWORD` — пароль хранилища;
- `KEY_ALIAS` — `claritykey`;
- `KEY_PASSWORD` — пароль ключа.

Файл `claritykey-release.jks` и пароли необходимо сохранить минимум в двух безопасных
местах. Потеря ключа лишит возможности выпускать обновления поверх установленного APK.

## 4. Публичный GitHub Release

После добавления секретов:

```bash
git tag v0.5.2-beta
git push origin v0.5.2-beta
```

Workflow соберёт подписанный APK и автоматически прикрепит его к GitHub Release.

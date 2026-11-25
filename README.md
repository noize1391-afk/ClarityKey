# ClarityKey

[![Build ClarityKey APK](https://github.com/noize1391-afk/ClarityKey/actions/workflows/build-apk.yml/badge.svg)](https://github.com/noize1391-afk/ClarityKey/actions/workflows/build-apk.yml)
[![Release](https://img.shields.io/badge/release-0.5.2--beta-blue)](https://github.com/noize1391-afk/ClarityKey/releases/tag/v0.5.2-beta)

ClarityKey is a privacy-focused Android keyboard with Russian and English input,
local suggestions, autocorrection, swipe typing, encrypted clipboard tools,
on-device OCR and extensive customization.

> **Beta software:** version 0.5.2 is suitable for testing and daily evaluation,
> but it has not yet completed the device and accessibility test matrix expected
> from a stable release.

## Download

- [Latest release](https://github.com/noize1391-afk/ClarityKey/releases/latest)
- [ClarityKey 0.5.2-beta APK](https://github.com/noize1391-afk/ClarityKey/releases/download/v0.5.2-beta/ClarityKey-0.5.2-beta.apk)
- SHA-256: `206bdbe195a25e58b6fbefe0f03004ae350b6b8c1a7c86585e0c9d3b3297ceb3`

## Highlights

- Russian and English layouts with local dictionaries (250,000 RU and 200,000 EN entries).
- Local suggestions, autocorrection, swipe input and personalization.
- Encrypted clipboard history, pinned items, images and smart-paste analysis.
- On-device OCR for Russian and English images using Tesseract.
- Android Autofill integration and optional local OTP extraction from notifications.
- Voice input through the speech-recognition service selected in Android.
- Themes, layout controls, key geometry and haptic customization.
- No `INTERNET` permission requested by the application.

## Compatibility

| Item | Value |
| --- | --- |
| Package | `com.claritykey.keyboard` |
| Current version | `0.5.2-beta` (`54004`) |
| Minimum Android | Android 9 / API 28 |
| Target Android | API 35 |
| Settings interface | Russian and English |

## Installation

1. Download the signed APK from [GitHub Releases](https://github.com/noize1391-afk/ClarityKey/releases).
2. If a debug build with the same package is installed, uninstall it first because
   Android does not allow an update signed with a different key.
3. Install the APK and enable ClarityKey under Android's keyboard settings.
4. Select ClarityKey as the current input method.

Changing from the former package ID does not automatically migrate Android-private
data. The built-in `.claritykey` export/import covers supported preferences,
personal words, shortcuts and templates, but not the encrypted vault, Autofill
profile or clipboard history.

## Privacy

Typing, suggestions, personalization, clipboard history and OCR are processed
locally by ClarityKey. The app does not request Android's `INTERNET` permission.
System speech recognition and Autofill are separate Android services and may have
their own network and privacy behaviour. See [PRIVACY.md](PRIVACY.md) for details.

## Optional permissions and access

| Access | Purpose |
| --- | --- |
| Vibration | Key haptics |
| Microphone | Voice input through Android's selected speech recognizer |
| Biometrics | Unlocking protected local data |
| Images/storage | Selecting screenshots or images for clipboard and OCR features |
| Contacts | Reserved for contact-aware input features; Android permission is required before access |
| Notification access | Optional local detection of one-time codes |

## Building

Every push to `main` runs tests and Android Lint, then builds an APK. A signed tag
such as `v0.5.2-beta` creates or updates the matching GitHub Release when signing
secrets are configured.

- [Build instructions](README_BUILD.md)
- [GitHub and Termux guide](GITHUB_TERMUX.md)
- [Third-party notices](third_party/NOTICE.md)

## Reporting problems

Check [known limitations](KNOWN_ISSUES.md), then use the
[bug report form](https://github.com/noize1391-afk/ClarityKey/issues/new/choose).
Never attach passwords, one-time codes, private typed text, clipboard contents,
personal OCR images or signing keys.

Security-sensitive reports should follow [SECURITY.md](SECURITY.md).

## License

ClarityKey source code is currently published as **All Rights Reserved**. No
permission to redistribute modified builds or reuse the source is granted without
written authorization. Third-party components and data retain their own licenses;
see [LICENSE](LICENSE) and [third_party/NOTICE.md](third_party/NOTICE.md).

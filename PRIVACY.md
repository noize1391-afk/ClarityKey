# Privacy Policy

Effective date: 26 November 2026

ClarityKey is designed to process keyboard data locally. This document describes
the behaviour of version 0.5.2-beta.

## Network access

ClarityKey does not request Android's `INTERNET` permission. The application
therefore cannot directly send typed text, suggestions, personalization data,
clipboard history, OCR results, contacts or vault contents to Internet services.

Features provided by other Android services are outside ClarityKey's process.
In particular, the speech recognizer and Autofill provider selected by the user
may process data according to their own privacy policies and may use a network
connection independently of ClarityKey.

## Data processed locally

Depending on enabled features, ClarityKey may process and store locally:

- typed words and personalization statistics;
- custom dictionary entries, shortcuts, templates and settings;
- clipboard text and images, including pinned items;
- OCR text extracted from user-selected images;
- protected vault content encrypted using Android Keystore-backed keys;
- Autofill profile data entered into ClarityKey's local profile;
- one-time codes detected from notifications when notification access is enabled.

Private files and clipboard media are stored in the application's private storage.
Android backup is disabled for the application.

## Permissions and optional access

- **Microphone:** used only when voice input is started. Audio is handled by the
  speech-recognition service selected in Android.
- **Biometrics:** used to unlock protected local features.
- **Images and storage:** used to select screenshots or images for clipboard and
  OCR functions. Older Android versions use the compatible storage permission.
- **Contacts:** declared for contact-aware input features. Android permission is
  required before contacts can be accessed. ClarityKey has no Internet permission.
- **Notification access:** optional; used to detect one-time codes locally.
- **Vibration:** used for keyboard haptic feedback.

## Data sharing

ClarityKey does not include advertising or analytics SDKs and does not directly
upload keyboard data. Text or images are sent to another application only when
the user inserts or commits that content into the current editor.

## Retention and deletion

Users can clear supported history and personalization data from the application.
Uninstalling ClarityKey removes its Android-private application data. Exported
`.claritykey` files and files copied elsewhere are controlled by the user and must
be deleted separately.

## Beta notice and changes

ClarityKey is beta software. This policy must be reviewed whenever permissions,
network behaviour, third-party services or stored data change. Material changes
will be recorded in the repository and release notes.

## Contact

Privacy questions may be submitted through the repository's
[issue tracker](https://github.com/noize1391-afk/ClarityKey/issues). Do not include
typed private text, passwords, one-time codes, clipboard contents or personal images.

# Known Limitations — 0.5.2-beta

ClarityKey is usable for beta testing, but the following areas still need broader
real-device validation before a stable release:

- The device matrix is limited; behaviour may differ between Android versions,
  manufacturer skins, screen sizes and foldable devices.
- Settings are available in Russian and English, but a small number of keyboard
  panels or system-provided labels may not yet be fully localized.
- Voice recognition quality, offline availability and privacy depend on the speech
  recognizer selected in Android.
- Clipboard source attribution is not available on every Android version or app.
- Image insertion requires the target editor to support the relevant MIME type and
  Android `commitContent`; unsupported apps may accept text only.
- Installing the signed release over an older debug build with the same package
  fails because the signatures differ. Uninstall the debug build first.
- Data from the former package ID is not migrated automatically. Manual export and
  import covers supported settings, words, shortcuts and templates, but not vault,
  Autofill or clipboard secrets.
- Basic accessibility nodes exist, but full TalkBack navigation and announcement
  testing is still pending.
- Autofill, biometric unlock, OCR, cold start with full dictionaries and long-term
  personalization require additional regression testing on physical devices.
- The autocorrection corpus needs further tuning to reduce rare false corrections.

When reporting a problem, include the ClarityKey version, Android version, device,
affected application, exact steps and a privacy-safe example.

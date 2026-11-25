# ClarityKey 0.5.2-beta

Privacy-focused Android keyboard with Russian and English layouts, local suggestions,
autocorrection, swipe input, encrypted clipboard tools, OCR and customization.

- Package: `com.claritykey.keyboard`
- Minimum Android: 9 (API 28)
- Target Android: API 35
- Interface: Russian and English
- Network permission: not requested

## Build on GitHub

Every push to `main` runs tests and Android Lint, then builds an APK. Without signing
secrets the workflow produces an installable debug APK. With the four signing secrets
configured it produces a signed release APK. A signed tag such as `v0.5.2-beta`
automatically creates a GitHub Release.

Detailed instructions are in [GITHUB_TERMUX.md](GITHUB_TERMUX.md).

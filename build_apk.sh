#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
sha256sum -c assets.sha256
./gradlew testDebugUnitTest lintDebug assembleRelease
if [[ -f app/build/outputs/apk/release/app-release.apk ]]; then
  cp app/build/outputs/apk/release/app-release.apk ClarityKey-release.apk
  echo 'Built ClarityKey-release.apk with the configured release key.'
else
  cp app/build/outputs/apk/release/app-release-unsigned.apk ClarityKey-release-unsigned.apk
  echo 'Built ClarityKey-release-unsigned.apk. Use the existing release key to sign an installable update.'
fi

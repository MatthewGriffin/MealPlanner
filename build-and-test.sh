#!/usr/bin/env bash
set -Eeuo pipefail

cd "$(dirname "$0")"

echo "==> Building debug APK"
./gradlew :app:assembleDebug

echo "==> Running sync-contract and app unit tests"
./gradlew :sync-contract:test :app:testDebugUnitTest

echo "==> Build and unit tests passed"

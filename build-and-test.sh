#!/usr/bin/env bash
set -Eeuo pipefail

cd "$(dirname "$0")"

echo "==> Pulling latest changes from origin/master"
git pull --ff-only origin master

echo "==> Building debug APK"
./gradlew --no-daemon :app:assembleDebug

echo "==> Running sync-contract and app unit tests"
./gradlew --no-daemon :sync-contract:test :app:testDebugUnitTest

echo "==> Git pull, build, and unit tests passed"
exit 0

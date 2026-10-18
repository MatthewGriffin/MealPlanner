#!/usr/bin/env bash
set -Eeuo pipefail

cd "$(dirname "$0")"

echo "==> Pulling latest changes from origin/master"
git pull --ff-only origin master

echo "==> Building debug APK"
./gradlew :app:assembleDebug

echo "==> Running sync-contract and app unit tests"
./gradlew :sync-contract:test :app:testDebugUnitTest

echo "==> Git pull, build, and unit tests passed"

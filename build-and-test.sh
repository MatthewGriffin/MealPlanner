#!/usr/bin/env bash
set -Eeuo pipefail

cd "$(dirname "$0")"

branch="$(git rev-parse --abbrev-ref HEAD)"
echo "==> Pulling latest changes for ${branch}"
git pull --ff-only

echo "==> Building debug APK and running all module unit tests"
./gradlew --no-daemon assembleDebug test --continue

echo "==> Git pull, build, and unit tests passed"
exit 0

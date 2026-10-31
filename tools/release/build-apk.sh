#!/usr/bin/env bash
set -euo pipefail

# Check mode exercises the complete App host suites and lint without a runtime binary.
# Packaging requires an explicit, verified release APK as the binary/runtime input.
TASKS=(:shared:desktopTest :androidApp:testDebugUnitTest :androidApp:lintRelease :rootbridge:lintRelease)
if [ "${1:-}" != --check ]; then
    python3 tools/release/runtime_payload.py extract "${1:?usage: build-apk.sh <verified-release.apk>|--check}" app/manager
    TASKS+=(:androidApp:assembleRelease)
fi
bash app/manager/gradlew -p app/manager \
    "${TASKS[@]}" \
    --no-daemon --no-parallel --max-workers=1 \
    -Pkotlin.compiler.execution.strategy=in-process \
    '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2'
if [ "${1:-}" != --check ]; then
    python3 tools/release/runtime_payload.py verify app/manager/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk
fi

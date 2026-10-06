#!/usr/bin/env bash
set -euo pipefail

# No private engine, private dependency token or signing key is needed here.
bash app/manager/gradlew -p app/manager \
    :shared:desktopTest :androidApp:testDebugUnitTest :androidApp:assembleRelease \
    --no-daemon --no-parallel --max-workers=1 \
    -Pkotlin.compiler.execution.strategy=in-process \
    '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2'

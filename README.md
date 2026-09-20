# SoftVoWiFi Client Snapshot

This is an App-only Android client source snapshot. It contains the manager app, observer source, Gradle build files, the Gradle wrapper, source fixtures, schemas, and source notice materials needed for the client build.

It does not contain the VoWiFi engine, KernelSU or Magisk module, privileged runtime, signing material, private source, production history, private receipts, or release artifacts. A debug APK installed by itself is not a usable VoWiFi deployment. Runtime use depends on a separately supplied existing private privileged module and engine.

## Build

Requirements: JDK 21 and an Android SDK with compile SDK 37. Set `JAVA_HOME` and `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to your local installations; do not commit `local.properties`. The app has min SDK 33 and target SDK 36. The included Gradle wrapper uses Gradle 9.6.1; the first build needs access to the public dependency repositories.

From the repository root, run:

```bash
timeout 1500s app/manager/gradlew -p app/manager :androidApp:assembleDebug :androidApp:assembleRelease \
  --no-daemon --max-workers=1 --no-parallel \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=512m -XX:ActiveProcessorCount=2'
```

No production signing configuration is included. The release APK is unsigned. Debug builds use the standard development key. Outputs are under `app/manager/androidApp/build/outputs/apk/` and are not included in this repository.

Both Android build targets passed on 2026-09-20 in a Linux sandbox without access to the private source tree, production signing material, or host private home. The run reused an earlier isolated public dependency/build cache (74 tasks executed, 31 from cache); it is not a fresh-cache or device acceptance claim. See [known issues](KNOWN_ISSUES.md) for the unresolved desktop test failure.

## License and Notices

`LICENSE` covers the approved client-owned code only. Third-party and derived materials retain their own terms. See `NOTICE` and `app/manager/androidApp/src/main/assets/third_party_notices/`.

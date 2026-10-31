# SoftVoWiFi Client

**The native Android management app for SoftVoWiFi.**

[简体中文](README.zh-CN.md) · [Download releases](https://github.com/lilyzhaun/softvowifi-client/releases) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Known issues](KNOWN_ISSUES.md)

This repository contains reviewed **App-only source snapshots**: the native Kotlin/Compose manager, ordinary foreground observer, libsu RootService bridge, shared models, Android integration, and host tests. The app retains its original name **SoftVoWiFi**, package **`com.vowifi.sw`**, icon, and signing identity.

> **Standalone APK with user-authorized Root.** The APK bundles the engine and runtime resources; KernelSU/Magisk may provide `su`, but no SoftVoWiFi module or privileged-app installation is used. The project remains experimental; registration does not establish working SMS or audible calls. Do not rely on it for emergency calls. Historical module releases retain their original packaging and verification scope.

## Preview

[![SoftVoWiFi](https://github.com/user-attachments/assets/fdced940-4009-4762-acdb-3b16f94c820c)](#video)

### Video

https://github.com/user-attachments/assets/973bcf68-27c4-4a6f-ad82-63140677de54

<p align="center">
  <a href="https://github.com/user-attachments/assets/c3ca8359-0e89-4311-aa53-e39da32ea12a"><img src="https://github.com/user-attachments/assets/c3ca8359-0e89-4311-aa53-e39da32ea12a" width="49%" alt="Incoming call and native call controls" /></a>
  <a href="https://github.com/user-attachments/assets/9d2e092e-fbee-45b7-ab4c-cc10e14a0dd0"><img src="https://github.com/user-attachments/assets/9d2e092e-fbee-45b7-ab4c-cc10e14a0dd0" width="49%" alt="Message notification and conversation" /></a>
</p>

## App features

| Area | Purpose |
|---|---|
| **Home** | Active SIM overview, software-engine status, and per-SIM control |
| **Calls** | Dialing, call controls, and app-private call history |
| **Messages** | Conversations, SMS actions, search, and app-private message history |
| **Settings** | Configuration, diagnostics, redacted logs, notification settings, and app preferences |

SMS, call, and management actions use the existing engine protocol through the Root bridge. Full voice, notification, recovery, accessibility, and dual-SIM isolation acceptance still have gaps; see [known issues](KNOWN_ISSUES.md). Public reports and App source checks do not require access to another repository.

The desktop target exists for tests and previews, not as a desktop calling client. eSIM profile management, contacts, the system SMS database, default-dialer integration, RCS, attachments, call hold, and conferencing are outside the current scope.

## Downloads

The current source branch targets a standalone APK; a standalone release has not yet been published. Existing v0.3.49 and earlier assets retain their module packaging and cannot supply the runtime input for this branch. Future standalone [GitHub Releases](https://github.com/lilyzhaun/softvowifi-client/releases) use these assets:

| Asset | Description |
|---|---|
| `SoftVoWiFi-v<version>.apk` | Independently compiled App plus verified bundled runtime, signed with the approved certificate |
| `SHA256SUMS` | APK, metadata, and `.idsig` sidecar checksums |
| `RELEASE.json` | Version/code, APK filename/size/checksum, and reviewed App-source digest |

The APK includes a separately licensed engine binary. This repository's App source license does not make the complete backend open source. Embedded runtime/dependency notices and corresponding-source scope must be considered separately.

Module releases from v0.3.44 include `APP-SOURCE.json`, the client GPL/NOTICE and linked-engine dependency licenses/NOTICE files. Check the release-specific materials for the embedded App's source. Earlier module assets are not offered as public downloads pending source and license review.

### Installation

1. Use a compatible rooted ARM64 Android 13+ device and a supported KernelSU / Magisk setup. The current backend requires Qualcomm QMI / QRTR and suitable XFRM/IPsec support, an active SIM, and carrier ePDG access; Android version alone does not guarantee support.
2. Download the standalone APK and verify its checksum and approved certificate.
3. Install it normally. Existing module-based installations require a deliberate migration and verification that the package is no longer a system/privileged app; a `/data/app` path alone is not sufficient.
4. Grant root access manually in the root manager as needed. KernelSU authorization must be completed by the user in KernelSU Manager; do not assume a changed signature/UID inherits an old grant.
5. Open Home and Settings, inspect diagnostics and SIM configuration, and start the intended line. Test registration, actual SMS delivery, and voice separately.

Stopping a line or runtime `rollback` keeps configuration and logs. Runtime data lives under `/data/adb/softvowifi`, with sockets under `/data/local/tmp/softvowifi`; old module data is not automatically imported, deleted, or re-enabled. Removing the app does not imply that external Root data has been removed. Destructive `purge` is a separate, explicit operation.

Historical module behavior: **Uninstalling the module deletes its configuration and logs**. This does not describe removal of the standalone APK; old module files are not changed automatically.

For checksum verification on Linux, download the assets listed in `SHA256SUMS` into the same directory, then run:

```bash
timeout 30s sha256sum --check SHA256SUMS
```

## Build from source

App tests and lint require no engine source or signing credentials. Packaging a complete APK additionally requires a verified standalone release APK of the matching version as the binary/runtime input. The App is independently compiled; the private engine is not rebuilt from this source tree. These instructions target Linux/Bash.

### Requirements

- JDK **21** and the checked-in Gradle wrapper; do not substitute a system Gradle version.
- Android SDK Platform **37.0**, Build Tools **36.0.0**, and SDK command-line tools. Set `ANDROID_HOME` or `ANDROID_SDK_ROOT`.
- Git, GitHub CLI (for the clone example), Bash, Python 3, `sqlite3`, XML utilities (`libxml2-utils` on Ubuntu), and CJK fonts (`fonts-noto-cjk`) for host tests.
- Sufficient memory for the single-worker Gradle build; the checked-in helper bounds JVM heap and parallelism.

```bash
GIT_MASTER=1 timeout 120s gh repo clone lilyzhaun/softvowifi-client
cd softvowifi-client

# A few retained render tests write temporary images under this directory.
timeout 10s mkdir -p /tmp/opencode
timeout 1800s bash tools/release/build-apk.sh --check
# To package: first verify a matching APK from this repository's release assets.
timeout 1800s bash tools/release/build-apk.sh /path/to/verified-release.apk
```

Both modes run shared desktop tests, Android host tests, and lint. The packaging mode also extracts and validates the runtime inventory, then builds an unsigned release APK:

```text
app/manager/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk
```

This output is **unsigned** and cannot replace the installed official app as-is. Keep private keys, passwords, `local.properties`, and build output out of Git.

The App version is read from `app/manager/version.properties`. It must match the verified runtime input; do not relabel another runtime as a new release.

The working branch can contain reviewed source changes ahead of a release tag. `RELEASE.json` describes its immutable release tag, not every later working-branch snapshot. Check out that tag when inspecting a published APK's source. A preparation-only source refresh does not move tags, change versions, sign an APK or replace release assets.

Historical exception: the current v0.3.47 attachments correspond to client commit [05be12c9](https://github.com/lilyzhaun/softvowifi-client/tree/05be12c98b88d87e75732dc1090092a797db3b38), not the earlier snapshot at the immutable v0.3.47 tag. Use that commit when rebuilding those attachments' client source.

## Source layout

```text
app/manager/          Android app, shared UI/models, schemas, tests, Gradle wrapper
app/manager/rootbridge/  RootService, restricted Binder methods and FD stream bridge
app/observer/src/    Ordinary foreground observer compiled by the Android app
app/observer/res/    Observer resources
export/country-source/  Pinned public AOSP MCC-to-country inputs and attribution
tools/export/        Public country-table regeneration helpers
tools/release/       Build and verification helpers
.github/workflows/   Read-only source checks
```

The public country table supplies MCC-to-ISO country/flag lookup, **not carrier policy or ePDG configuration**. Its inputs, generation helpers, original notices, and the app's third-party license assets are retained.

No private engine source, OEM/operator catalog, upstream Git history, device evidence, SIM configuration, signing material, or module binaries are committed into the exported source tree.

## Contributing and reporting problems

Submit issues and client-source pull requests here; private repository access is not required. Maintainers review and integrate the patch into the development tree before the next export. See [CONTRIBUTING.md](CONTRIBUTING.md); report vulnerabilities through [SECURITY.md](SECURITY.md), not a secret-bearing public issue.

Report the App version, device model, Android version, public carrier PLMN, network conditions, and a **redacted** error summary. Distinguish “registered,” “SMS accepted,” “delivery report,” “message received,” and “call audible.” Never attach SIM identities, full configuration, authentication material, credentials, message bodies, or raw communication captures. Do not send real SMS/calls or consume OTP merely to reproduce a report without authorization.

## License and attribution

Client-owned source in this snapshot is offered under **GPL-3.0-only**; [`LICENSE`](LICENSE) is the unmodified full GPLv3 text. The ten retained KernelSU-derived UI adaptations retain their `GPL-3.0-only` headers; upstream KernelSU manager grants GPL version 3 or later. Keep those statements and original author attribution distinct. Applicable earlier MIT copies and third-party grants below are not revoked.

Other applicable MIT and Apache-2.0 grants and earlier MIT snapshots are not revoked or replaced. See [`NOTICE`](NOTICE), the [country-source notice](https://github.com/lilyzhaun/softvowifi-client/blob/master/export/country-source/NOTICE), and `app/manager/androidApp/src/main/assets/third_party_notices/` for provenance and license texts.

These source materials do not automatically license the private engine or demonstrate complete corresponding-source and binary redistribution compliance. IPC separation, an APK build, or a successful release is not a legal audit.

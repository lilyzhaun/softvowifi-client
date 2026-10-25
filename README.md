# SoftVoWiFi Client

**The native Android management app for SoftVoWiFi.**

[简体中文](README.zh-CN.md) · [Download releases](https://github.com/lilyzhaun/softvowifi-client/releases) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Known issues](KNOWN_ISSUES.md)

This repository receives versioned **app-only source snapshots** from the SoftVoWiFi development repository. It contains the management app, privileged observer, shared models, Android integration, tests, and the source inputs needed to rebuild the client independently. The interface is a native Kotlin / Compose app with miuix components and Navigation3—not a web UI.

> **A compatible SoftVoWiFi root module is required.** A standalone APK does not implement the private VoWiFi engine, install the privileged backend, or provide a working Wi-Fi Calling service by itself. The project is experimental; published binaries do not establish complete device/carrier compatibility. Do not rely on it for emergency calls.

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

SMS, call, and management actions are wired to the module/engine protocol. Successful registration is not proof of SMS delivery or a connected, audible call. Full voice, notification, recovery, accessibility, and dual-SIM isolation acceptance still have gaps; see [known issues](KNOWN_ISSUES.md). The development repository and its device evidence are private; they are not required to build the client or submit a public report.

The desktop target exists for tests and previews, not as a desktop calling client. eSIM profile management, contacts, the system SMS database, default-dialer integration, RCS, attachments, call hold, and conferencing are outside the current scope.

## Downloads

Each [GitHub Release](https://github.com/lilyzhaun/softvowifi-client/releases) contains:

| Asset | Description |
|---|---|
| `SoftVoWiFi-v<version>.apk` | Client APK independently built from this repository and signed with the approved certificate |
| `SoftVoWiFi-ksu-v<version>.zip` | **Exact upstream module ZIP**, copied without rebuilding or repacking |
| `SHA256SUMS` | SHA-256 checksums for the APK, ZIP, and metadata; also covers the APK `.idsig` sidecar when present |
| `RELEASE.json` | Version, version code, module filename/size/checksum, and exported-source content digest |

The module ZIP is a separately distributed binary asset with a **private-source Go engine**. The client source is GPL-3.0-only, with retained third-party and earlier grants described below; this does not make the complete backend open source. Repository visibility and binary redistribution clearance are separate from automation.

Module releases from v0.3.44 include `APP-SOURCE.json`, the client GPL/NOTICE and linked-engine dependency licenses/NOTICE files. Check the release-specific materials for the embedded App's source. Earlier module assets are not offered as public downloads pending source and license review.

### Installation

1. Use a compatible rooted ARM64 Android 13+ device and a supported KernelSU / Magisk setup. The current backend requires Qualcomm QMI / QRTR and suitable XFRM/IPsec support, an active SIM, and carrier ePDG access; Android version alone does not guarantee support.
2. Download the matching module ZIP and verify its checksum. Install it through the root manager and complete the required reboot. The ZIP includes the privileged manager app and backend.
3. If updating the app separately, use the APK from the **same release version** and verify it too. An APK update does not update the module engine or supply a missing module.
4. Grant root access manually in the root manager as needed. KernelSU authorization must be completed by the user in KernelSU Manager; do not assume a changed signature/UID inherits an old grant.
5. Open Home and Settings, inspect diagnostics and SIM configuration, and start the intended line. Test registration, actual SMS delivery, and voice separately.

The independently built APK and the APK embedded in the module use the same version and approved signing certificate, but may differ byte-for-byte. **Uninstalling the module deletes its configuration and logs**; the management App and its private data are not deleted with it. Back up any module data you need before uninstalling. Module `rollback` only cleans up runtime resources and preserves data; deleting module data while installed requires an explicitly authorized `purge`.

For checksum verification on Linux, download the assets listed in `SHA256SUMS` into the same directory, then run:

```bash
timeout 30s sha256sum --check SHA256SUMS
```

## Build from source

Building the client does **not** require upstream engine source, private Go dependencies, or production signing credentials. Access to this repository and ordinary Gradle dependency downloads are still required. The instructions below target Linux / Bash.

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
timeout 1800s bash tools/release/build-apk.sh
```

The helper runs shared desktop tests, Android host tests, and the unsigned Android release build. Output:

```text
app/manager/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk
```

This output is **unsigned** and cannot replace the installed official app as-is. Keep private keys, passwords, `local.properties`, and build output out of Git.

The generated app version is read from `app/manager/version.properties`; it is synchronized from the upstream module version. Do not edit that file independently to create a mismatched client release.

The working branch can contain reviewed source changes ahead of a release tag. `RELEASE.json` describes its immutable release tag, not every later working-branch snapshot. Check out that tag when inspecting a published APK's source. A preparation-only source refresh does not move tags, change versions, sign an APK or replace release assets.

Historical exception: the current v0.3.47 attachments correspond to client commit [05be12c9](https://github.com/lilyzhaun/softvowifi-client/tree/05be12c98b88d87e75732dc1090092a797db3b38), not the earlier snapshot at the immutable v0.3.47 tag. Use that commit when rebuilding those attachments' client source.

## Source layout

```text
app/manager/          Android app, shared UI/models, schemas, tests, Gradle wrapper
app/observer/src/    Privileged observer source compiled by the Android app
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

Report the app/module version, device model, Android version, public carrier PLMN, network conditions, and a **redacted** error summary. Distinguish “registered,” “SMS accepted,” “delivery report,” “message received,” and “call audible.” Never attach SIM identities, full configuration, authentication material, credentials, message bodies, or raw communication captures. Do not send real SMS/calls or consume OTP merely to reproduce a report without authorization.

## License and attribution

Client-owned source in this snapshot is offered under **GPL-3.0-only**; [`LICENSE`](LICENSE) is the unmodified full GPLv3 text. The ten retained KernelSU-derived UI adaptations retain their `GPL-3.0-only` headers; upstream KernelSU manager grants GPL version 3 or later. Keep those statements and original author attribution distinct. Applicable earlier MIT copies and third-party grants below are not revoked.

Other applicable MIT and Apache-2.0 grants and earlier MIT snapshots are not revoked or replaced. See [`NOTICE`](NOTICE), the [country-source notice](https://github.com/lilyzhaun/softvowifi-client/blob/master/export/country-source/NOTICE), and `app/manager/androidApp/src/main/assets/third_party_notices/` for provenance and license texts.

These source materials do not automatically license the private engine or demonstrate complete corresponding-source and binary redistribution compliance. IPC separation, an APK build, or a successful release is not a legal audit.

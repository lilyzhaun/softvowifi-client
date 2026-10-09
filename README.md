# SoftVoWiFi Client

**The native Android management app for SoftVoWiFi.**

[简体中文](README.zh-CN.md) · [Download releases](https://github.com/lilyzhaun/softvowifi-client/releases) · [Upstream project](https://github.com/lilyzhaun/softvowifi) · [Known issues](KNOWN_ISSUES.md)

This repository receives versioned **app-only source snapshots** from the SoftVoWiFi development repository. It contains the management app, privileged observer, shared models, Android integration, tests, and the source inputs needed to rebuild the client independently. The interface is a native Kotlin / Compose app with miuix components and Navigation3—not a web UI.

> **A compatible SoftVoWiFi root module is required.** A standalone APK does not implement the private VoWiFi engine, install the privileged backend, or provide a working Wi-Fi Calling service by itself. The project is experimental; automated releases do not establish complete device/carrier compatibility. Do not rely on it for emergency calls.

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

SMS, call, and management actions are wired to the module/engine protocol. Successful registration is not proof of SMS delivery or a connected, audible call. Full voice, notification, recovery, accessibility, and dual-SIM isolation acceptance still have gaps; see [known issues](KNOWN_ISSUES.md) and the [upstream evidence ledger](https://github.com/lilyzhaun/softvowifi/blob/master/docs/00-文档索引.md).

The desktop target exists for tests and previews, not as a desktop calling client. eSIM profile management, contacts, the system SMS database, default-dialer integration, RCS, attachments, call hold, and conferencing are outside the current scope.

## Downloads

Each [GitHub Release](https://github.com/lilyzhaun/softvowifi-client/releases) contains:

| Asset | Description |
|---|---|
| `SoftVoWiFi-v<version>.apk` | Client APK independently built from this repository and signed with the approved certificate |
| `SoftVoWiFi-ksu-v<version>.zip` | **Exact upstream module ZIP**, copied without rebuilding or repacking |
| `SHA256SUMS` | SHA-256 checksums for the APK, ZIP, and metadata; also covers the APK `.idsig` sidecar when present |
| `RELEASE.json` | Version, version code, module filename/size/checksum, and exported-source content digest |

The module ZIP is a separately distributed binary asset. It is **not** private engine source and does not make this an open implementation of the complete backend. Repository visibility and redistribution rights are not changed by automation; both repositories currently remain private.

### Installation

1. Use a compatible rooted ARM64 Android 13+ device and a supported KernelSU / Magisk setup. The current backend requires Qualcomm QMI / QRTR and suitable XFRM/IPsec support, an active SIM, and carrier ePDG access; Android version alone does not guarantee support.
2. Download the matching module ZIP and verify its checksum. Install it through the root manager and complete the required reboot. The ZIP includes the privileged manager app and backend.
3. If updating the app separately, use the APK from the **same release version** and verify it too. An APK update does not update the module engine or supply a missing module.
4. Grant root access manually in the root manager as needed. KernelSU authorization must be completed by the user in KernelSU Manager; do not assume a changed signature/UID inherits an old grant.
5. Open Home and Settings, inspect diagnostics and SIM configuration, and start the intended line. Test registration, actual SMS delivery, and voice separately.

The independently built APK and the APK embedded in the module use the same version and approved signing certificate, but may differ byte-for-byte. Preserve configuration and history when troubleshooting. Normal module rollback/uninstall preserves configuration and logs; only explicitly authorized `purge` deletes module data.

For checksum verification on Linux, download the assets listed in `SHA256SUMS` into the same directory, then run:

```bash
timeout 30s sha256sum --check SHA256SUMS
```

## Build from source

Building the client does **not** require upstream engine source, private Go dependencies, or production signing credentials. Access to this repository and ordinary Gradle dependency downloads are still required. The instructions below target Linux / Bash.

### Requirements

- JDK **21** and the checked-in Gradle wrapper; do not substitute a system Gradle version.
- Android SDK Platform **37.0**, Build Tools **36.0.0**, and SDK command-line tools. Set `ANDROID_HOME` or `ANDROID_SDK_ROOT`.
- Bash, XML utilities (`libxml2-utils` on Ubuntu), and CJK fonts (`fonts-noto-cjk`) for host rendering tests.
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

This output is **unsigned** and cannot replace the installed official app as-is. Release signing is performed separately in trusted CI. Keep private keys, passwords, `local.properties`, and build output out of Git.

The generated app version is read from `app/manager/version.properties`; it is synchronized from the upstream module version. Do not edit that file independently to create a mismatched client release.

## Source layout

```text
app/manager/          Android app, shared UI/models, schemas, tests, Gradle wrapper
app/observer/src/    Privileged observer source compiled by the Android app
app/observer/res/    Observer resources
export/country-source/  Pinned public AOSP MCC-to-country inputs and attribution
tools/export/        Public country-table regeneration helpers
tools/release/       Separately maintained build, signing, and verification controls
.github/workflows/   Read-only checks and trusted release workflow
```

The public country table supplies MCC-to-ISO country/flag lookup, **not carrier policy or ePDG configuration**. Its inputs, generation helpers, original notices, and the app's third-party license assets are retained.

No private engine source, OEM/operator catalog, upstream Git history, device evidence, SIM configuration, signing material, or module binaries are committed into the exported source tree.

## How releases stay synchronized

The upstream maintainer increments **both** `version` and `versionCode` in `module/module.prop` and merges the change into upstream `master`. The normal successful path is fully automatic:

1. Upstream CI builds and checks the signed module ZIP and validates the app-only export.
2. A filtered app-only snapshot is committed into this repository using its **own parent history**, with matching version metadata and tag.
3. The same ZIP is attached to this repository's draft release and verified by size and SHA-256.
4. Upstream publishes its module release and automatically dispatches this repository's trusted release workflow. The client APK is built **from this repository's tagged source**, not copied out of the module.
5. The independently signed APK, original ZIP, metadata, and checksums are published together.

No manual source copying, tagging, APK upload, or ZIP mirroring is needed in that path. Failed tests, mismatched versions/checksums, or changed protected controls stop publication. Existing release tags and published assets are not overwritten; a matching completed release is not dispatched for another APK build by the upstream publisher.

Ordinary source synchronization includes this README and its Chinese translation, but preserves the separately managed `.github/workflows/` and `tools/release/` files. The client stores no upstream cross-repository credential. Signing is separated from jobs with release-write permission. Manual dispatch exists for controlled recovery, not as a normal release step.

## Contributing and reporting problems

Changes intended to survive the next source export must also be made upstream. Direct edits to managed app files or README material can be replaced by the next version snapshot; CI control files follow a separate reviewed update process.

Report the app/module version, device model, Android version, public carrier PLMN, network conditions, and a **redacted** error summary. Distinguish “registered,” “SMS accepted,” “delivery report,” “message received,” and “call audible.” Never attach SIM identities, full configuration, authentication material, credentials, message bodies, or raw communication captures. Do not send real SMS/calls or consume OTP merely to reproduce a report without authorization.

## License and attribution

[`LICENSE`](LICENSE) contains the unmodified GNU GPL version 3 text for the client-source licensing route. The ten retained KernelSU-derived UI adaptations remain marked `GPL-3.0-only`; the upstream KernelSU manager grants GPL version 3 or later. Keep those statements distinct and preserve the source headers.

Other applicable MIT and Apache-2.0 grants and earlier MIT snapshots are not revoked or replaced. See [`NOTICE`](NOTICE), the [country-source notice](https://github.com/lilyzhaun/softvowifi-client/blob/master/export/country-source/NOTICE), and `app/manager/androidApp/src/main/assets/third_party_notices/` for provenance and license texts.

These source materials do not automatically license the private engine or demonstrate complete corresponding-source and binary redistribution compliance. IPC separation, an APK build, or a successful release is not a legal audit.

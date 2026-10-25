# Known issues for SoftVoWiFi Client

- The APK needs a compatible rooted ARM64 Android device and SoftVoWiFi module. Installing the client alone does not supply the private engine or enable Wi-Fi Calling.
- Device/carrier coverage is incomplete. Registration does not establish SMS delivery, audible calls, long-running reliability, notification recovery or dual-SIM isolation. Do not rely on this project for emergency calls.
- Host tests have exposed timing-dependent failures. Consult the checks for the exact source revision; a passing build is not device or carrier acceptance.
- The public country table provides MCC-to-ISO lookup only, not carrier policy. Client-owned source is GPL-3.0-only with the third-party grants retained in NOTICE; that does not license the private engine.
- Use a release's source tag or its explicit source-commit exception when inspecting published binaries. The working branch may contain later changes. See README for the v0.3.47 exception.

## 简体中文

- APK 须配合兼容的 Root ARM64 Android 设备与模块，单独安装不能提供私有引擎或 Wi-Fi Calling。
- 设备/运营商覆盖仍不完整；注册成功不代表短信送达、双向可听、长期稳定、通知恢复或双卡隔离，不用于紧急呼叫。
- 主机测试曾有时序相关失败，请查看精确源码版本的检查结果；构建通过不等于实机或运营商验收。
- 公开国家表只有 MCC-to-ISO 查询；客户端许可不授权私有引擎。查询发布二进制源码时使用对应 tag 或该版本明确的提交例外，v0.3.47 见 README。

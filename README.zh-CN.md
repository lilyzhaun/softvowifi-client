# SoftVoWiFi Client

**SoftVoWiFi 的原生 Android 管理 App。**

[English](README.md) · [下载 Release](https://github.com/lilyzhaun/softvowifi-client/releases) · [开发主仓](https://github.com/lilyzhaun/softvowifi) · [已知问题](KNOWN_ISSUES.md)

本仓接收主仓按版本导出的 **App-only 源码快照**，包含管理 App、特权 observer、共享模型、Android 接线、测试和独立重建客户端所需的公开输入。界面使用 Kotlin／Compose、miuix 和 Navigation3，是原生 App，不是 Web UI。

> **必须配合兼容的 SoftVoWiFi Root 模块。** 单独 APK 不实现私有引擎、不安装特权后端，也不能自行提供 Wi-Fi Calling。项目仍在开发验收阶段，自动 Release 不证明完整设备／运营商兼容，请勿用于紧急呼叫。

## App 功能

| 页面 | 用途 |
|---|---|
| 首页 | 活动 SIM、软件引擎状态和每卡控制 |
| 通话 | 拨号、通话控制和 App 私有通话历史 |
| 短信 | 会话、短信动作、搜索和 App 私有消息历史 |
| 设置 | 配置、诊断、脱敏日志、通知能力和 App 偏好 |

通信和管理动作已经接入模块／引擎协议，但注册成功不等于短信送达或电话接通／人耳可听。完整语音、通知、恢复、无障碍和双卡隔离仍有验收缺口，见 [已知问题](KNOWN_ISSUES.md) 及 [主仓证据台账](https://github.com/lilyzhaun/softvowifi/blob/master/docs/00-文档索引.md)。

desktop 仅用于测试和预览，不是桌面电话客户端。eSIM profile 管理、联系人、系统短信库、默认拨号器、RCS、附件、保持／会议不属于当前范围。

## 下载与安装

每个 [GitHub Release](https://github.com/lilyzhaun/softvowifi-client/releases) 包含：

| 文件 | 内容 |
|---|---|
| `SoftVoWiFi-v<version>.apk` | 从本仓独立构建，并使用批准证书签名的 APK |
| `SoftVoWiFi-ksu-v<version>.zip` | 主仓的**同字节模块 ZIP**，不重建／不重打包 |
| `SHA256SUMS` | APK、ZIP、元数据及存在时 APK `.idsig` 侧车的 SHA-256 |
| `RELEASE.json` | 版本、版本码、模块名称／大小／摘要和导出源码内容摘要 |

模块 ZIP 是单独分发的二进制资产，不是私有引擎源码，也不使本仓成为完整后端的公开实现。自动化不改变仓库可见性或再分发权利；两仓目前均为 PRIVATE。

1. 准备 Root ARM64 Android 13+、兼容 KernelSU / Magisk、Qualcomm QMI / QRTR 与合适的 XFRM/IPsec、活动 SIM 和运营商 ePDG 访问。满足 Android 版本不代表适配完成。
2. 下载同版本模块 ZIP 并核对摘要，经 Root 管理器安装和必要重启。模块内已有特权管理 App 与后端。
3. 如独立更新 App，使用**同一 Release 版本**的 APK 并核对摘要。APK 更新不会更新引擎或补齐缺失模块。
4. 按需由用户在 Root 管理器手动授权；KernelSU 须在 KernelSU Manager 完成，不假设新签名／UID 继承旧权限。
5. 在首页／设置检查诊断和 SIM 配置，只启动目标线路，分别验证注册、实际短信投递和语音。

独立 APK 与模块内 APK 使用相同版本／批准签名证书，不保证字节一致。排查时保留配置和历史；正常模块 rollback／卸载保留配置／日志，只有明确授权的 `purge` 才删除模块数据。

Linux 下，将 `SHA256SUMS` 中列出的资产放在同一目录，运行：

```bash
timeout 30s sha256sum --check SHA256SUMS
```

## 从源码构建

不需要主仓私有引擎、Go 私有依赖或生产签名材料，但仍需要本仓访问权限和正常 Gradle 依赖下载。以下面向 Linux / Bash。

- JDK **21** 和本仓 Gradle wrapper，不替换为系统 Gradle。
- Android SDK Platform **37.0**、Build Tools **36.0.0**、命令行工具；设置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`。
- Bash、XML 工具（Ubuntu 的 `libxml2-utils`）、CJK 字体（`fonts-noto-cjk`）。
- 单 worker Gradle 所需内存；helper 已限制堆和并行。

```bash
GIT_MASTER=1 timeout 120s gh repo clone lilyzhaun/softvowifi-client
cd softvowifi-client

# 部分保留的渲染测试会在此目录写临时图片。
timeout 10s mkdir -p /tmp/opencode
timeout 1800s bash tools/release/build-apk.sh
```

helper 运行共享 desktop 测试、Android host 测试和 unsigned release 构建，输出：

```text
app/manager/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk
```

该文件**未签名**，不能直接覆盖官方安装版本。正式签名由受信任 CI 单独完成；密钥、密码、`local.properties` 和构建输出不得提交。

App 版本读取 `app/manager/version.properties`，由主仓模块版本同步，不独立修改成不匹配的客户端版本。

## 源码结构与边界

```text
app/manager/          Android App、共享 UI／模型、schema、测试、Gradle wrapper
app/observer/src/    App 编译的特权 observer 源码
app/observer/res/    observer 资源
export/country-source/  固定 AOSP MCC-to-country 输入和归属
tools/export/        公开国家表再生 helper
tools/release/       单独维护的构建／签名／验证控制文件
.github/workflows/   只读检查和受信任发版 workflow
```

公开国家表只提供 MCC-to-ISO 国家／旗帜查询，**不是运营商策略或 ePDG 配置**。保留原输入、再生工具、NOTICE 和 App 第三方许可资产。

导出源码树不包含私有引擎、OEM／运营商目录、主仓 Git 历史、设备证据、SIM 配置、签名材料或模块二进制。

## 全自动同步与发版

主仓维护者同时递增 `module/module.prop` 的 `version`／`versionCode` 并合入主仓 `master`。正常成功路径：

1. 主仓 CI 构建／检查签名模块 ZIP，并验证 App-only 导出。
2. 过滤后的 App-only 快照以**客户端自己的 parent 历史**提交到本仓，版本元数据和 tag 一致。
3. 同 ZIP 进入客户端 draft Release，核对大小／SHA-256。
4. 主仓正式发布模块 Release 并自动 dispatch 本仓 release workflow；APK **从本仓不可变 tag 源码独立构建**，不从模块中提取。
5. 独立签名 APK、原 ZIP、元数据和摘要一起正式发布。

无需手工复制源码、打 tag、上传 APK 或镜像 ZIP。测试失败、版本／摘要不匹配、控制文件漂移时停止；不覆盖既有 tag／资产，相同已完成版本不会由主仓发布器再次 dispatch APK 构建。

普通同步包含本 README 和中文版，但保留单独维护的 `.github/workflows/`／`tools/release/`。本仓不存主仓跨仓库凭据；签名与 release 写权限 job 分离。手动 dispatch 只用于受控失败恢复，不是日常发版步骤。

## 贡献与问题反馈

希望下次导出仍保留的修改应同步到主仓。直接改客户端 App 或 README 可能被下一版快照覆盖；控制文件采用单独审核更新。

反馈提供 App／模块版本、机型、Android 版本、公开运营商 PLMN、网络条件和**脱敏**错误摘要，区分“注册”“短信受理”“报告”“真正收件”“可听电话”。不提交 SIM 身份、完整配置、鉴权材料、凭据、正文或原始通信捕获；未经授权不为复现盲目实发／拨号／消耗 OTP。

## 许可证与归属

[`LICENSE`](LICENSE) 是客户端源码许可路线的原文 GNU GPL version 3。十份 KernelSU 改编 UI 文件保留 `GPL-3.0-only`；上游 KernelSU manager 是 GPL version 3 or later，两者不得混同，原 header 和版权保留。

其他 MIT／Apache-2.0 许可及早前 MIT 快照授权不被撤销或覆盖。见 [`NOTICE`](NOTICE)、[国家源 NOTICE](https://github.com/lilyzhaun/softvowifi-client/blob/master/export/country-source/NOTICE) 和 `app/manager/androidApp/src/main/assets/third_party_notices/`。

这些材料不自动授权私有引擎，也不证明完整对应源码和二进制再分发义务已满足。IPC 分离、构建或成功 Release 都不是法律审计。

# SoftVoWiFi Client

**SoftVoWiFi 的原生 Android 管理 App。**

[English](README.md) · [下载 Release](https://github.com/lilyzhaun/softvowifi-client/releases) · [贡献](CONTRIBUTING.md) · [安全](SECURITY.md) · [已知问题](KNOWN_ISSUES.md)

本仓接收主仓按版本导出的 **App-only 源码快照**，包含管理 App、特权 observer、共享模型、Android 接线、测试和独立重建客户端所需的公开输入。界面使用 Kotlin／Compose、miuix 和 Navigation3，是原生 App，不是 Web UI。

> **必须配合兼容的 SoftVoWiFi Root 模块。** 单独 APK 不实现私有引擎、不安装特权后端，也不能自行提供 Wi-Fi Calling。项目仍在开发验收阶段，已发布二进制不证明完整设备／运营商兼容，请勿用于紧急呼叫。

## 界面预览

[![SoftVoWiFi](https://github.com/user-attachments/assets/fdced940-4009-4762-acdb-3b16f94c820c)](#视频)

### 视频

https://github.com/user-attachments/assets/973bcf68-27c4-4a6f-ad82-63140677de54

<p align="center">
  <a href="https://github.com/user-attachments/assets/c3ca8359-0e89-4311-aa53-e39da32ea12a"><img src="https://github.com/user-attachments/assets/c3ca8359-0e89-4311-aa53-e39da32ea12a" width="49%" alt="来电通知与原生通话界面" /></a>
  <a href="https://github.com/user-attachments/assets/9d2e092e-fbee-45b7-ab4c-cc10e14a0dd0"><img src="https://github.com/user-attachments/assets/9d2e092e-fbee-45b7-ab4c-cc10e14a0dd0" width="49%" alt="短信提醒与会话界面" /></a>
</p>

## App 功能

| 页面 | 用途 |
|---|---|
| 首页 | 活动 SIM、软件引擎状态和每卡控制 |
| 通话 | 拨号、通话控制和 App 私有通话历史 |
| 短信 | 会话、短信动作、搜索和 App 私有消息历史 |
| 设置 | 配置、诊断、脱敏日志、通知能力和 App 偏好 |

通信和管理动作已经接入模块／引擎协议，但注册成功不等于短信送达或电话接通／人耳可听。完整语音、通知、恢复、无障碍和双卡隔离仍有验收缺口，见 [已知问题](KNOWN_ISSUES.md)。开发主仓和实机证据保持私有；构建客户端或提交公开问题不要求访问它们。

desktop 仅用于测试和预览，不是桌面电话客户端。eSIM profile 管理、联系人、系统短信库、默认拨号器、RCS、附件、保持／会议不属于当前范围。

## 下载与安装

每个 [GitHub Release](https://github.com/lilyzhaun/softvowifi-client/releases) 包含：

| 文件 | 内容 |
|---|---|
| `SoftVoWiFi-v<version>.apk` | 从本仓独立构建，并使用批准证书签名的 APK |
| `SoftVoWiFi-ksu-v<version>.zip` | 主仓的**同字节模块 ZIP**，不重建／不重打包 |
| `SHA256SUMS` | APK、ZIP、元数据及存在时 APK `.idsig` 侧车的 SHA-256 |
| `RELEASE.json` | 版本、版本码、模块名称／大小／摘要和导出源码内容摘要 |

模块 ZIP 是单独分发的二进制资产，**Go 引擎源码保持私有**。客户端源码采用 GPL-3.0-only，并保留下文列出的第三方和早前授权；这不使整个后端开源。仓库可见性、二进制再分发资格和自动化分别判断。

v0.3.44 起的模块附有 `APP-SOURCE.json`、客户端 GPL／NOTICE 和链接引擎依赖的许可／NOTICE；请按各版本材料查找内置 App 的对应源码。更早的模块资产尚待源码与许可审查，不作为公开下载提供。

1. 准备 Root ARM64 Android 13+、兼容 KernelSU / Magisk、Qualcomm QMI / QRTR 与合适的 XFRM/IPsec、活动 SIM 和运营商 ePDG 访问。满足 Android 版本不代表适配完成。
2. 下载同版本模块 ZIP 并核对摘要，经 Root 管理器安装和必要重启。模块内已有特权管理 App 与后端。
3. 如独立更新 App，使用**同一 Release 版本**的 APK 并核对摘要。APK 更新不会更新引擎或补齐缺失模块。
4. 按需由用户在 Root 管理器手动授权；KernelSU 须在 KernelSU Manager 完成，不假设新签名／UID 继承旧权限。
5. 在首页／设置检查诊断和 SIM 配置，只启动目标线路，分别验证注册、实际短信投递和语音。

独立 APK 与模块内 APK 使用相同版本／批准签名证书，不保证字节一致。**卸载模块会删除模块配置和日志**；管理 App 及其私有数据不会随模块卸载删除。卸载前请备份需要保留的模块数据。`rollback` 只清理运行态并保留数据；模块安装期间删除模块数据须显式授权执行 `purge`。

Linux 下，将 `SHA256SUMS` 中列出的资产放在同一目录，运行：

```bash
timeout 30s sha256sum --check SHA256SUMS
```

## 从源码构建

不需要主仓私有引擎、Go 私有依赖或生产签名材料，但仍需要本仓访问权限和正常 Gradle 依赖下载。以下面向 Linux / Bash。

- JDK **21** 和本仓 Gradle wrapper，不替换为系统 Gradle。
- Android SDK Platform **37.0**、Build Tools **36.0.0**、命令行工具；设置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`。
- Git、GitHub CLI（用于下方克隆示例）、Bash、Python 3、`sqlite3`、XML 工具（Ubuntu 的 `libxml2-utils`）和主机测试所需 CJK 字体（`fonts-noto-cjk`）。
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

该文件**未签名**，不能直接覆盖官方安装版本。密钥、密码、`local.properties` 和构建输出不得提交。

App 版本读取 `app/manager/version.properties`，由主仓模块版本同步，不独立修改成不匹配的客户端版本。

工作分支可以包含尚未发版的已审源码变化。`RELEASE.json`描述其不可变版本标签，而非其后所有工作分支快照；检查某次已发布APK的源码请切到对应tag。准备阶段的纯源码刷新不移动tag、不改版本、不签名，也不替换发布资产。

历史例外：v0.3.47 现有附件对应客户端提交 [05be12c9](https://github.com/lilyzhaun/softvowifi-client/tree/05be12c98b88d87e75732dc1090092a797db3b38)，不是不可变 v0.3.47 标签上的更早快照。重建这些附件的客户端源码时请使用该提交。

## 源码结构与边界

```text
app/manager/          Android App、共享 UI／模型、schema、测试、Gradle wrapper
app/observer/src/    App 编译的特权 observer 源码
app/observer/res/    observer 资源
export/country-source/  固定 AOSP MCC-to-country 输入和归属
tools/export/        公开国家表再生 helper
tools/release/       构建与验证 helper
.github/workflows/   只读源码检查
```

公开国家表只提供 MCC-to-ISO 国家／旗帜查询，**不是运营商策略或 ePDG 配置**。保留原输入、再生工具、NOTICE 和 App 第三方许可资产。

导出源码树不包含私有引擎、OEM／运营商目录、主仓 Git 历史、设备证据、SIM 配置、签名材料或模块二进制。

## 贡献与问题反馈

可以直接在本仓提交公开问题和客户端源码 PR，无需私有主仓权限。维护者审核、回流开发主仓后再导出，详见 [CONTRIBUTING.md](CONTRIBUTING.md)。漏洞报告请走 [SECURITY.md](SECURITY.md) 的私密渠道，不在公开 issue 上传秘密。

反馈提供 App／模块版本、机型、Android 版本、公开运营商 PLMN、网络条件和**脱敏**错误摘要，区分“注册”“短信受理”“报告”“真正收件”“可听电话”。不提交 SIM 身份、完整配置、鉴权材料、凭据、正文或原始通信捕获；未经授权不为复现盲目实发／拨号／消耗 OTP。

## 许可证与归属

本快照的项目自有客户端源码采用 **GPL-3.0-only**；[`LICENSE`](LICENSE) 是完整原文 GPLv3。十份 KernelSU 改编 UI 文件保留 `GPL-3.0-only`；上游 KernelSU manager 是 GPL version 3 or later，两者与原作者归属分别保留，不抹去适用的早前 MIT 授权。

其他 MIT／Apache-2.0 许可及早前 MIT 快照授权不被撤销或覆盖。见 [`NOTICE`](NOTICE)、[国家源 NOTICE](https://github.com/lilyzhaun/softvowifi-client/blob/master/export/country-source/NOTICE) 和 `app/manager/androidApp/src/main/assets/third_party_notices/`。

这些材料不自动授权私有引擎，也不证明完整对应源码和二进制再分发义务已满足。IPC 分离、构建或成功 Release 都不是法律审计。

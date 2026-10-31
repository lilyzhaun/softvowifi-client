# SoftVoWiFi Client

**SoftVoWiFi 的原生 Android 管理 App。**

[English](README.md) · [下载 Release](https://github.com/lilyzhaun/softvowifi-client/releases) · [贡献](CONTRIBUTING.md) · [安全](SECURITY.md) · [已知问题](KNOWN_ISSUES.md)

本仓提供已审核的 **App-only 源码快照**，包含原生管理App、普通前台observer、libsu RootService桥、共享模型、Android接线和主机测试。保留原应用名 **SoftVoWiFi**、包名 **`com.vowifi.sw`**、图标与签名身份。

> **单 APK，由用户授权 Root。** APK已内置引擎与运行资源；KernelSU/Magisk等可提供su，不需要SoftVoWiFi模块或特权App挂载。项目仍在开发验收阶段，注册不等于短信/语音成功，请勿用于紧急呼叫。旧模块Release保留其历史形态与验证边界。

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

通信与管理动作经Root桥连接既有引擎协议。完整语音、通知、恢复、无障碍和双卡隔离仍有验收缺口，见[已知问题](KNOWN_ISSUES.md)。检查App源码或提交公开问题不要求访问其他仓库。

desktop 仅用于测试和预览，不是桌面电话客户端。eSIM profile 管理、联系人、系统短信库、默认拨号器、RCS、附件、保持／会议不属于当前范围。

## 下载与安装

从v0.3.50起使用单APK形态。历史v0.3.49及更早附件保留模块包装，不能作为本分支的运行资源输入。单APK的[GitHub Release](https://github.com/lilyzhaun/softvowifi-client/releases)使用下列附件：

| 文件 | 内容 |
|---|---|
| `SoftVoWiFi-v<version>.apk` | 独立编译App并内置已核运行资源，采用批准证书签名 |
| `SHA256SUMS` | APK、元数据和`.idsig`侧车的SHA-256 |
| `RELEASE.json` | 版本/版本码、APK名称/大小/摘要及App源码摘要 |

APK内置单独许可的引擎二进制；本仓App源码许可不使整个后端开源。内置依赖声明与对应源码范围分别核对。

v0.3.44 起的模块附有 `APP-SOURCE.json`、客户端 GPL／NOTICE 和链接引擎依赖的许可／NOTICE；请按各版本材料查找内置 App 的对应源码。更早的模块资产尚待源码与许可审查，不作为公开下载提供。

1. 准备 Root ARM64 Android 13+、兼容 KernelSU / Magisk、Qualcomm QMI / QRTR 与合适的 XFRM/IPsec、活动 SIM 和运营商 ePDG 访问。满足 Android 版本不代表适配完成。
2. 下载单APK，核对摘要与批准签名证书。
3. 普通安装APK。既有模块形态须主动迁移并核对实际非system/privileged安装标志，不能仅由`/data/app`路径推断。
4. 按需由用户在 Root 管理器手动授权；KernelSU 须在 KernelSU Manager 完成，不假设新签名／UID 继承旧权限。
5. 在首页／设置检查诊断和 SIM 配置，只启动目标线路，分别验证注册、实际短信投递和语音。

停止线路或运行态`rollback`保留配置和日志。运行数据在`/data/adb/softvowifi`，socket在`/data/local/tmp/softvowifi`，旧模块数据不自动导入、删除或恢复。卸载App不等于外部Root数据已清理；破坏性的`purge`是单独的显式操作。

历史模块语义：**卸载模块会删除模块配置和日志**。这不是单APK卸载语义；旧模块文件不自动处理。

Linux 下，将 `SHA256SUMS` 中列出的资产放在同一目录，运行：

```bash
timeout 30s sha256sum --check SHA256SUMS
```

## 从源码构建

App测试和lint不需要引擎源码或签名材料。封装完整APK还需要同版本、已核验的单APK作为二进制/运行资源输入；App源码独立编译，公开树不重新编译私有引擎。以下面向Linux/Bash。

- JDK **21** 和本仓 Gradle wrapper，不替换为系统 Gradle。
- Android SDK Platform **37.0**、Build Tools **36.0.0**、命令行工具；设置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`。
- Git、GitHub CLI（用于下方克隆示例）、Bash、Python 3、`sqlite3`、XML 工具（Ubuntu 的 `libxml2-utils`）和主机测试所需 CJK 字体（`fonts-noto-cjk`）。
- 单 worker Gradle 所需内存；helper 已限制堆和并行。

```bash
GIT_MASTER=1 timeout 120s gh repo clone lilyzhaun/softvowifi-client
cd softvowifi-client

# 部分保留的渲染测试会在此目录写临时图片。
timeout 10s mkdir -p /tmp/opencode
timeout 1800s bash tools/release/build-apk.sh --check
# 封包前，先核验本仓Release附件中的同版本APK。
timeout 1800s bash tools/release/build-apk.sh /path/to/verified-release.apk
```

两种模式均运行完整desktop/Android主机测试和lint；封包模式另按精确清单提取/核对运行资源，并生成unsigned release APK：

```text
app/manager/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk
```

该文件**未签名**，不能直接覆盖官方安装版本。密钥、密码、`local.properties` 和构建输出不得提交。

App版本读取`app/manager/version.properties`，必须与运行资源输入一致，不把旧后端重新标为新版本。

工作分支可以包含尚未发版的已审源码变化。`RELEASE.json`描述其不可变版本标签，而非其后所有工作分支快照；检查某次已发布APK的源码请切到对应tag。准备阶段的纯源码刷新不移动tag、不改版本、不签名，也不替换发布资产。

历史例外：v0.3.47 现有附件对应客户端提交 [05be12c9](https://github.com/lilyzhaun/softvowifi-client/tree/05be12c98b88d87e75732dc1090092a797db3b38)，不是不可变 v0.3.47 标签上的更早快照。重建这些附件的客户端源码时请使用该提交。

## 源码结构与边界

```text
app/manager/          Android App、共享 UI／模型、schema、测试、Gradle wrapper
app/manager/rootbridge/  RootService、受限Binder与FD流桥
app/observer/src/    App 编译的普通前台observer源码
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

反馈提供App版本、机型、Android版本、公开运营商PLMN、网络条件和**脱敏**错误摘要，区分“注册”“短信受理”“报告”“真正收件”“可听电话”。不提交SIM身份、完整配置、鉴权材料、凭据、正文或原始通信捕获；未经授权不为复现实发/拨号/消耗OTP。

## 许可证与归属

本快照的项目自有客户端源码采用 **GPL-3.0-only**；[`LICENSE`](LICENSE) 是完整原文 GPLv3。十份 KernelSU 改编 UI 文件保留 `GPL-3.0-only`；上游 KernelSU manager 是 GPL version 3 or later，两者与原作者归属分别保留，不抹去适用的早前 MIT 授权。

其他 MIT／Apache-2.0 许可及早前 MIT 快照授权不被撤销或覆盖。见 [`NOTICE`](NOTICE)、[国家源 NOTICE](https://github.com/lilyzhaun/softvowifi-client/blob/master/export/country-source/NOTICE) 和 `app/manager/androidApp/src/main/assets/third_party_notices/`。

这些材料不自动授权私有引擎，也不证明完整对应源码和二进制再分发义务已满足。IPC 分离、构建或成功 Release 都不是法律审计。

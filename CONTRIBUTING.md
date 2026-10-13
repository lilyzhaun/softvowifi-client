# Contributing to SoftVoWiFi Client

This repository accepts **public issues and client-source pull requests**. You do not need access to the private development repository.

The client is a versioned export, not the authoritative development tree. Maintainers review a patch here, integrate it into the development tree, update the exact content-review inventory, and re-export it. A pull request can remain open until that integration is ready. No private Git parents or engine source are brought into this repository.

Direct edits to managed files are not silently overwritten: a later export stops if the edit has not been absorbed by the source tree. CI, signing helpers and issue forms are separately protected controls. Do not edit version properties to manufacture a release or request a moving release tag.

## Before sending a patch

1. Build the unsigned client using the README instructions. Production keys and engine source are not required.
2. Add a focused regression showing the behavior, then make the smallest change. Retain privacy, ownership and lifecycle negative tests.
3. Explain compatibility and what you actually tested. Host tests, screenshots, registration, SMS acceptance, delivery reports, actual received messages and audible calls are different evidence.
4. Preserve upstream copyright, SPDX headers and license texts. Explain the origin and license of copied or adapted material.

Never submit SIM identities, phone/account identifiers, full configuration, authentication material, keys, tokens, OTPs, message bodies, raw captures, `local.properties` or build output. Use clearly synthetic samples. Do not send real messages, place calls, consume OTPs or change carrier settings merely to reproduce a report without the device/account owner's authorization.

For security issues, follow [SECURITY.md](SECURITY.md). For compatibility reports, provide only the public carrier PLMN, app/module versions, device model, Android version and a redacted error summary. Do not claim support for untested devices or carriers.

## 简体中文

可以在公开客户端仓提交问题和补丁，无需私有主仓权限。维护者审核后回流主仓、更新精确内容审查清单，再导出；未回流的人工改动会阻止覆盖。请保留隐私负测、来源和许可证，不上传身份、配置、凭据、短信正文或抓包；安全问题先读 [SECURITY.md](SECURITY.md)。

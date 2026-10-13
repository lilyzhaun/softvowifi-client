# Security reporting

Do not post credentials or subscriber data in a public issue or pull request.

Use **Security → Report a vulnerability** when private reporting is available. If that option is absent, open an issue containing only a generic description asking the maintainer for a private reporting channel. Do not attach an exploit containing real data while waiting for that channel.

We need the client/module version, affected component, expected versus observed behavior, and a minimal synthetic reproduction. Never include ICCID, IMSI, IMEI, MSISDN, account identifiers, authentication vectors, private keys, signing material, tokens, OTPs, message bodies, complete configuration or raw SIP/IPsec captures.

This project requires root and handles sensitive communication. Treat a root module as highly privileged software; check the release checksums and device requirements. The client source license does not license the private engine, certify a carrier, or promise emergency calling. Use the latest supported release, but do not assume that a security or compatibility claim covers older assets or every device.

Maintainers coordinate fixes in the private development repository and export the client fix here. Public disclosure should wait until the affected release and remediation are identified. No response-time or security-certification guarantee is claimed.

## 简体中文

优先使用安全页的私密漏洞报告入口；入口不存在时，只提交不含秘密的概括说明并询问私密渠道。不要上传真实身份、配置、密钥、鉴权向量、OTP、短信正文或抓包。Root 模块权限很高，源码开放不等于全产品安全认证或紧急呼叫保障。

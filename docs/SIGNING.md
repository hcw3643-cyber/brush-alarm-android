# 正式版本签名

Android 使用签名证书判断新 APK 能否覆盖安装旧版本。刷牙闹钟的正式版本必须始终使用
同一把长期密钥签名；Debug 测试包使用独立包名和调试密钥。

## 官方身份

- 应用 ID：`io.github.hcw3643cyber.brushalarm`
- 首个正式签名版本：`1.0.0`（versionCode 7）
- 签名算法：RSA 2048
- 证书 SHA-256：
  `50d81fc8b37ac1a514e7fb099afdae353956e84127e3f6f0bf61aff2d30476df`

证书指纹可以公开，用于核对 APK 身份；私钥、keystore 和密码绝不能公开。

## 密钥管理

- 长期 keystore 必须保存在仓库外，并至少制作两份独立加密备份。
- keystore、密码、`keystore.properties` 和签名后的 APK 都不能进入 Git 历史。
- 密码应保存在密码管理器中，不与 keystore 放在同一个未加密位置。
- 丢失自主管理的长期密钥后，现有安装无法继续接受同包名的普通覆盖更新。

仓库的 `.gitignore` 只是最后一道防误提交措施，不能替代离线备份和发布前审计。

## 发布前验证

使用 Android SDK Build Tools 中的 `apksigner`：

```bash
apksigner verify --verbose --print-certs BrushAlarm-v1.0.0-universal.apk
sha256sum BrushAlarm-v1.0.0-universal.apk
```

发布者必须确认：

1. 输出为 `Verifies`；
2. 证书 SHA-256 与本文完全一致；
3. 包名、versionName 和 versionCode 与发布说明一致；
4. GitHub Release 同时提供 APK 文件 SHA-256；
5. APK 对应的完整源代码位于同一个 Git tag。

面向普通用户只发布长期密钥签名的 universal Release APK。ABI 分包、Debug APK、
未签名 APK、baseline profile 和 `output-metadata.json` 不作为用户下载项。

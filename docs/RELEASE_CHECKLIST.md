# 私有审阅与公开发布清单

## 应进入源码仓库

- `app/src/` 中的 Android 源码和资源，但不包含 ONNX 模型
- `training/` 中的训练、分析和导出脚本
- `scripts/` 中的模型下载与发布检查脚本
- Gradle Wrapper 和构建配置
- README、许可、模型卡、隐私、贡献和兼容性文档
- GitHub Issue 表单

## 不应进入 Git

- `app/src/main/assets/brush_classifier.onnx`
- APK、AAB、签名附属文件
- `local.properties`、本地 SDK、IDE 配置和构建缓存
- `training/data/`、`training/checkpoints/`、`training/export/`
- 用户视频、推理 CSV、闹钟诊断日志
- `.jks`、`.keystore`、密码、Token、API Key 和代理凭据

模型作为 GitHub Release 的独立资产发布。APK 也可以作为 Release 资产公开，但只能发布
使用长期发布密钥签名的 universal APK；不能发布 Debug、未签名或临时密钥 APK。

软件源代码按 `GPL-3.0-only` 发布，模型继续按 `MODEL_LICENSE.md` 单独处理。包含模型的
APK 不是“只有 GPL 一种条款”的文件；Release 说明必须同时链接本版本的完整对应源码、
`LICENSE`、`MODEL_LICENSE.md` 和 `THIRD_PARTY_NOTICES.md`。不要把模型或训练数据写成
GPL 已经覆盖的项目原创代码。

## 私有仓库审阅

- [ ] 仓库可见性为 Private
- [ ] 默认分支为 `main`
- [ ] 分支保护和私密漏洞报告已启用
- [ ] `LICENSE`、`MODEL_LICENSE.md` 和第三方声明完整
- [ ] GitHub 正确识别根目录许可证为 GPL-3.0
- [ ] README 不含私人邮箱、文件路径或本机信息
- [ ] 模型 Release 只有 ONNX、校验值和模型说明
- [ ] 未上传 Debug/未签名 APK、视频、数据、日志、签名库或 checkpoint
- [ ] 正式 APK 证书 SHA-256 与 `docs/SIGNING.md` 一致
- [ ] 全部测试和 Release Lint 通过
- [ ] 在至少一台真机重新验证熄屏、划后台、重启和刷牙流程

## 转为公开前

```bash
./scripts/audit-public-tree.sh
git status --short
git ls-files
git log --stat --oneline
```

还需要人工确认：

1. 维护者名称、仓库 URL 和测试设备信息准确；
2. 志愿者数据征集仍处于关闭状态，或已经具备私密上传与单独同意；
3. 模型卡没有把 UCF101、Kinetics 或第三方权重错误标注成项目自有资产；
4. 如 Release 包含 APK，它是长期密钥签名的 universal APK，并附 SHA-256；
5. APK Release 明确链接同一 Git tag 的完整对应源码与全部许可声明；
6. Git 历史中不存在旧模型、视频、日志或密钥对象。

## 签名

正式发布密钥必须由项目所有者长期离线保存。同一 Android 包后续更新需要保持相同签名。
不要把 keystore、密码或签名配置提交到 Git，也不要把开发调试签名描述为应用商店签名。

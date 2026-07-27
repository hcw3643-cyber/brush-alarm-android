# 开源发布清单

## 建议提交到代码仓库

- `app/src/`：Android 源码、资源、图标和端侧 ONNX 模型
- `training/*.py`、`training/requirements.txt`、`training/README.md`：可复现训练流程
- `gradle/`、`gradlew`、`gradlew.bat`、`*.gradle.kts`、`gradle.properties`
- `README.md`、`OPEN_SOURCE.md`
- `.gitignore`
- 选择并确认后的 `LICENSE`
- 建议后续补充 `CONTRIBUTING.md`、`CODE_OF_CONDUCT.md` 和隐私政策

APK/AAB 应放在 GitHub Releases 等发布页，不要作为普通源码文件反复提交。

## 不应上传

- `local.properties`、`.idea/`、`.gradle/`、本地 Android SDK、虚拟环境和构建缓存
- `app/build/`、根目录 `build/`、临时 APK/AAB
- `training/data/`、`training/checkpoints/`、`training/export/`
- 用户拍摄的视频、推理 CSV、闹钟诊断日志和任何未经明确授权的个人数据
- 发布签名 `.jks/.keystore`、密码、`keystore.properties`、API Key、代理凭据

以上目录已大部分由 `.gitignore` 排除。发布前仍应执行：

```bash
git status --short
git ls-files
git grep -n -I -E '(password|secret|api[_-]?key|token)'
```

并人工检查提交历史；仅在当前工作树删除敏感文件并不能把它从 Git 历史中移除。

## 代码、模型与数据许可

仓库目前尚未选择开源许可证。公开前必须由项目所有者决定许可证；常见选择包括
Apache-2.0、MIT 或 GPL-3.0，但应结合预期的商业使用和衍生项目要求决定。

应用代码的许可证不会自动覆盖模型权重。`brush_classifier.onnx` 基于 TorchVision
S3D/Kinetics-400 预训练权重，并使用 UCF101 和经同意的真机视频微调。公开模型前
必须分别确认：

1. 预训练权重及其训练数据条款允许目标用途和再分发；
2. UCF101 及其原始视频条款允许发布衍生权重；
3. 真机视频提供者明确同意将其训练出的衍生模型公开；
4. 在仓库中增加模型卡，记录数据来源、指标、限制、偏差和许可证。

若许可尚未确认，先开源代码和训练脚本，把 ONNX 从公开仓库中移除，并在 Release
说明中标注“模型暂不分发”。

## 正式版与测试版

- `release`：版本名 `1.0.0`，无手工标签、推理日志或闹钟诊断日志。
- `debug`：独立包名后缀 `.test`，版本名 `1.0.0-test`，保留测试日志和标签。

正式发布必须使用项目所有者长期保存的 release keystore 签名。同一应用后续更新
必须使用同一签名；不要把 keystore 或密码提交到 Git。

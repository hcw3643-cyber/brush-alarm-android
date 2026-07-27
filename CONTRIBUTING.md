# 参与贡献

感谢你帮助改进刷牙闹钟。除非另行书面说明，提交代码或文档贡献即表示你有权提交该
内容，并同意它按仓库根目录 [LICENSE](LICENSE) 中的 GNU GPL v3.0 only
（SPDX：`GPL-3.0-only`）提供。模型、训练数据和志愿者视频不适用这一默认条款，
必须遵循各自的许可与同意流程。

## 代码贡献

1. 先搜索现有 Issue，较大的行为变更请先开 Issue 讨论。
2. 从 `main` 创建短期分支，保持一次提交只解决一个主题。
3. 不要提交生成文件、APK、模型、密钥、日志、训练数据或个人信息。
4. Android 改动至少运行：

   ```bash
   ./gradlew testDebugUnitTest testReleaseUnitTest
   ./gradlew assembleDebug assembleRelease
   ```

5. 模型改动需同时更新 `docs/MODEL_CARD.md`，写明数据划分、指标、阈值、哈希和局限。

## Bug 与兼容性反馈

请优先使用 Issue 表单，并仅填写复现所需的设备型号、Android/系统版本和文字步骤。
公开 Issue 中不要上传：

- 正脸或浴室视频、照片；
- 未脱敏的 CSV、通知或设置页截图；
- 邮箱、手机号、设备标识或精确作息；
- 第三方无明确授权的数据。

如需诊断数值日志，请先删除文件名和内容中的个人信息。正式版默认不生成这些日志。

## 刷牙视频

目前不接受通过 Issue、PR、GitHub 仓库或普通邮件发送刷牙视频。志愿者数据通道启用后，
将使用单独的知情同意和私密上传流程；详见
[`docs/DATA_CONTRIBUTION.md`](docs/DATA_CONTRIBUTION.md)。

## Pull Request 检查

- 功能与文档相符，没有暗中增加网络上传或追踪；
- Debug 测试功能不会进入 Release；
- 新依赖已在 `THIRD_PARTY_NOTICES.md` 记录名称、版本、用途和许可证；
- 没有提交 `local.properties`、签名材料、模型或构建产物；
- 对锁屏、精确闹钟、前台服务和 Direct Boot 的改动有真机验证说明。

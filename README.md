<p align="center">
  <img src="design/brush-alarm-logo-source.png" width="160" alt="刷牙闹钟 Logo">
</p>

# 刷牙闹钟

一个只有完成刷牙动作验证后才会停止的 Android 闹钟。

> [!IMPORTANT]
> 本项目是非商业、社区驱动的实验性软件，目前只在 vivo X300（Android 16 /
> OriginOS 6）上完成端到端测试。它不是经过多品牌兼容性认证的医疗、健康或强制叫醒产品，
> 请保留系统闹钟等备用唤醒方式。

## 项目定位

- 普通用户可以免费安装、自行构建、修改和分享。
- 学校、公益组织和非商业研究可以免费使用。
- 不允许收费销售、广告变现、商业集成、预装销售或提供收费模型/API 服务。
- 源码使用
  [PolyForm Noncommercial 1.0.0](LICENSE)，属于“非商业开放源码
  （source-available）”，不是 OSI 定义下允许商业用途的开源软件。
- App 不上传摄像头画面；正式版不写入或导出推理日志。

## 已实现

- 创建、编辑、启用、停用和删除闹钟
- 周一至周日分别选择重复日期
- 苹果风格的小时/分钟滚轮
- 持续模式：闹铃持续播放，完成刷牙验证后停止
- 舍友模式：允许暂时静音，未完成验证时每分钟复响
- `AlarmManager` 精确闹钟、全屏通知、前台响铃服务和 CPU 唤醒锁
- 熄屏、划掉最近任务、设备重启和 Direct Boot 后重新登记闹钟
- 前置摄像头端侧 ONNX 视频模型；画面只在内存中处理
- 验证页禁用返回键、隐藏最近任务，并请求 Android“屏幕固定”
- 独立 Debug 测试版：保留数值日志和人工标签，不与正式版数据混用

## 已知限制

- Android 厂商可额外限制自启动、后台弹出、锁屏显示和耗电行为。首次启动会集中引导
  所需权限，之后可从首页右上角设置重新打开。
- 普通 App 无法阻止系统“强行停止”，也无法实现 Device Owner/Kiosk 等级的完全锁定。
- 当前模型的训练用户和真实设备覆盖不足，可能在不同面孔、牙刷、角度和光线下漏检。
- 当前兼容性范围和测试方法见
  [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)。

## 从源码运行

### 1. 环境

- Android Studio Ladybug 或更高版本
- JDK 17
- Android SDK 35
- Android 8.0（API 26）或更高版本真机

稳定应用 ID 为 `io.github.hcw3643cyber.brushalarm`。此前使用
`com.example.brushalarm` 的内部测试包不会被识别为同一个 App，闹钟配置也不会自动迁移。

工程优先使用华为云、阿里云和腾讯云 Maven 镜像，并保留官方仓库作为回退。

### 2. 获取模型

模型不放入 Git 历史，而是作为独立 Release 资产发布。将
`brush_classifier.onnx` 下载到：

```text
app/src/main/assets/brush_classifier.onnx
```

仓库转为公开后可使用：

```bash
./scripts/fetch-model.sh
```

Windows PowerShell：

```powershell
.\scripts\fetch-model.ps1
```

私有仓库审阅期间也可以从 `model-v1.0.0` Release 手工下载。模型来源、输入格式、
指标和限制见 [docs/MODEL_CARD.md](docs/MODEL_CARD.md)。

### 3. 构建

```bash
./gradlew testDebugUnitTest testReleaseUnitTest
./gradlew assembleDebug assembleRelease
```

- `debug`：独立测试包，版本名带 `-test`，包含数值日志和人工标签。
- `release`：正式功能包，不创建推理/闹钟诊断 CSV，也不显示导出入口。
- Gradle 生成的 Release APK 默认未使用项目长期密钥签名；签名库绝不能提交到仓库。
  面向用户的 Release 只发布长期密钥签名的 universal APK，模型作为同一版本的独立资产。

## 识别模型概要

输入为最近约两秒的动作窗口：

- 16 帧 RGB
- 8 fps 时间戳采样，窗口跨度约 1.875 秒
- 每帧中心裁剪并缩放到 192×192
- 每 0.5 秒产生一个重叠窗口结果
- 高/低置信度门限为 0.65/0.10
- 需要累计 6 秒高置信度证据

模型以 TorchVision S3D/Kinetics-400 权重为基础，在 UCF101 刷牙和困难负样本上微调，
并使用一段经维护者同意的真机视频做域适配。第三方数据条款并不完全明确，因此模型
作为非商业实验性资产发布，不宣称获得 UCF101 原始视频的再分发权，也不包含任何
原始训练视频。详见 [docs/MODEL_CARD.md](docs/MODEL_CARD.md) 和
[MODEL_LICENSE.md](MODEL_LICENSE.md)。

## 参与项目

- 一般 Bug、设备兼容性和数值模型反馈请使用仓库 Issue 表单。
- 不要把正脸视频、浴室画面、原始日志或其他个人信息上传到公开 Issue、PR 或仓库。
- 志愿者视频征集尚未开放；开放前必须先启用私密上传、单独同意和删除流程。
- 代码贡献规则见 [CONTRIBUTING.md](CONTRIBUTING.md)。
- 数据贡献原则见
  [docs/DATA_CONTRIBUTION.md](docs/DATA_CONTRIBUTION.md)。

## 项目结构

```text
.
├── app/                 Android App、资源和测试
├── training/            训练、标定和 ONNX 导出脚本
├── scripts/             模型下载与发布前检查脚本
├── docs/                架构、兼容性、模型、隐私和数据贡献文档
├── design/              Logo 源文件
├── .github/             Issue 表单
├── LICENSE              非商业软件许可证
├── MODEL_LICENSE.md     模型权重许可边界
└── THIRD_PARTY_NOTICES.md
```

更详细的数据流和组件职责见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 隐私与安全

正式版摄像头帧只在手机内存中实时分析，不保存、不上传。测试版日志只包含模型数值、
时间和设备信息，不包含图像、视频或音频，但分享前仍应人工检查。完整说明见
[docs/PRIVACY.md](docs/PRIVACY.md)；安全问题请遵循 [SECURITY.md](SECURITY.md)。

## 许可

- 维护者：Leo Huang
- 本项目原创软件：PolyForm Noncommercial 1.0.0
- 当前模型中项目方可许可的部分：CC BY-NC 4.0
- 第三方组件和基础权重：保持各自原始条款
- Logo 和“刷牙闹钟”名称不随软件许可证授予商标或冒充官方版本的权利

详情见 [LICENSE](LICENSE)、[MODEL_LICENSE.md](MODEL_LICENSE.md) 和
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

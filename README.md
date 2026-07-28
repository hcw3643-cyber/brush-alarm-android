<p align="center">
  <img src="design/brush-alarm-logo-source.png" width="160" alt="刷牙闹钟 Logo">
</p>

# 刷牙闹钟

[English](README_EN.md) | 简体中文

一个只有完成刷牙动作验证后才会停止的 Android 闹钟。

> [!IMPORTANT]
> 本项目是社区驱动的实验性自由软件，目前只在 vivo X300（Android 16 /
> OriginOS 6）上完成端到端测试。它不是经过多品牌兼容性认证的医疗、健康或强制叫醒产品，
> 请保留系统闹钟等备用唤醒方式。

## 项目定位

- 软件源代码使用 [GNU GPL v3.0 only](LICENSE)；任何人可以运行、研究、修改和分发，
  包括商业使用，但分发修改版或 APK 时必须遵守 GPLv3 的对应源码和同许可证要求。
- `brush_classifier.onnx` 不是 GPL 软件代码，继续适用独立的
  [模型许可和来源限制](MODEL_LICENSE.md)。软件许可证不替模型或训练数据补齐权利。
- App 不上传摄像头画面；正式版不写入或导出推理日志。

## 已实现

- 创建、编辑、启用、停用和删除闹钟
- 周一至周日分别选择重复日期
- 小时/分钟滚轮
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

## 下载和安装

只想使用 App 的用户不需要下载模型或自行构建：

1. 打开 [`v1.0.0` Release](https://github.com/hcw3643-cyber/brush-alarm-android/releases/tag/v1.0.0)；
2. 大多数近年的 Android 手机下载
   `BrushAlarm-v1.0.0-arm64-v8a.apk`；
3. 不确定 CPU 架构或 arm64 包无法安装时，改用
   `BrushAlarm-v1.0.0-universal.apk`；
4. 按系统提示允许从当前来源安装，然后首次启动按 App 引导开启通知、精确闹钟、
   摄像头和必要的厂商后台权限；
5. 先设置一个几分钟后的测试闹钟，确认锁屏和划掉最近任务后仍能响铃，再用于次日
   起床。

Release APK 已内嵌刷牙模型。普通用户不要下载 Debug、unsigned、x86 或 32 位 ARM
内部构建产物，也不需要另行下载 `model-v1.0.0` Release 中的 ONNX 文件。Release
页面同时提供 `BrushAlarm-v1.0.0-SHA256SUMS.txt` 校验文件。由于本项目尚未完成
多机型适配，请始终保留系统闹钟作为备用。

## 从源码运行

### 1. 环境

- Android Studio Ladybug 或更高版本
- JDK 17
- Android SDK 35
- Android 8.0（API 26）或更高版本真机

稳定应用 ID 为 `io.github.hcw3643cyber.brushalarm`。此前使用
`com.example.brushalarm` 的内部测试包不会被识别为同一个 App，闹钟配置也不会自动迁移。


### 2. 获取模型

模型不放入 Git 历史，而是作为独立 Release 资产发布。将
`brush_classifier.onnx` 下载到：

```text
app/src/main/assets/brush_classifier.onnx
```

可使用：

```bash
./scripts/fetch-model.sh
```

Windows PowerShell：

```powershell
.\scripts\fetch-model.ps1
```

可以从 `model-v1.0.0` Release 手工下载。模型来源、输入格式、指标和限制见 [docs/MODEL_CARD.md](docs/MODEL_CARD.md)。

### 3. 构建

```bash
./gradlew testDebugUnitTest testReleaseUnitTest
./gradlew assembleDebug assembleRelease
```

- `debug`：独立测试包，版本名带 `-test`，包含数值日志和人工标签。
- `release`：正式功能包，不创建推理/闹钟诊断 CSV，也不显示导出入口。
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
作为独立实验性资产发布，不宣称获得 UCF101 原始视频的再分发权，也不包含任何
原始训练视频。详见 [docs/MODEL_CARD.md](docs/MODEL_CARD.md) 和
[MODEL_LICENSE.md](MODEL_LICENSE.md)。

MoViNet A0 已完成相同 UCF 划分上的训练、流式 TFLite 导出和数值一致性验证。它的
视频级 F1 为 93.67%，接近当前 S3D 的 93.83%；理论计算量约低 65 倍，但尚未经过
真机域验证，也尚未接入正式 App。详见
[training/MOVINET_A0.md](training/MOVINET_A0.md)。

## 参与项目

- 一般 Bug、设备兼容性和数值模型反馈请使用仓库 Issue 表单。
- 不要把正脸视频、浴室画面、原始日志或其他个人信息上传到公开 Issue、PR 或仓库。
- 志愿者刷牙视频征集已开放，只通过项目邮箱
  [BrushAlarm@163.com](mailto:BrushAlarm@163.com) 接收；发送前必须阅读并在邮件中
  明确接受数据贡献说明。
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
├── README_EN.md         English README
├── LICENSE              GNU GPL v3.0 软件许可证
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
- 本项目原创软件代码、仓库文档与原创美术资源：GNU GPL v3.0 only
  （SPDX：`GPL-3.0-only`）
- 当前模型中项目方可许可的部分：CC BY-NC 4.0
- 第三方组件和基础权重：保持各自原始条款
- Logo 和“刷牙闹钟”名称不随软件许可证授予商标或冒充官方版本的权利

GPL 允许商业使用和收费分发，但分发者必须履行 GPLv3，不能把该软件的衍生版本改成
闭源专有软件。模型权重不包含在根目录 `LICENSE` 的授权范围内；分发包含模型的 APK
前还必须分别确认并遵守 [MODEL_LICENSE.md](MODEL_LICENSE.md) 及第三方来源条款。

详情见 [LICENSE](LICENSE)、[MODEL_LICENSE.md](MODEL_LICENSE.md) 和
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

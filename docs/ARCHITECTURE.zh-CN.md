# 架构说明

[English](ARCHITECTURE.md) | 简体中文

## 运行链路

```text
Room 闹钟记录
  └─ AlarmScheduler / AlarmManager 精确闹钟
       ├─ AlarmReceiver
       ├─ 前台响铃服务 + 音频 + WakeLock
       └─ 全屏通知 / VerificationActivity
            └─ CameraX 前置摄像头
                 └─ BrushMotionAnalyzer
                      ├─ 8 fps 时间戳采样
                      ├─ 16 帧、192×192、RGB 标准化
                      ├─ ONNX Runtime 端侧推理（每 0.5 秒）
                      └─ BrushDecisionFilter
                           └─ 通过后停止服务并登记下一次闹钟
```

## 闹钟可靠性

闹钟本身由 Android `AlarmManager` 调度，不依赖一个常驻 App 进程。闹铃启动后使用前台
服务播放音频，并持有有限期 CPU 唤醒锁。启动、解锁、升级和 Direct Boot 接收器会从
本地数据库重新登记启用中的闹钟。

部分厂商仍要求用户允许精确闹钟、自启动、后台弹出界面、锁屏显示和后台耗电。首次
启动集中显示引导，首页设置入口可再次打开。系统“强行停止”会阻止所有接收器和闹钟，
直到用户再次启动 App；普通第三方 App 无法绕过这个 Android 安全边界。

## 识别数据流

CameraX 回调只在内存中保留模型需要的帧。每帧先中心方形裁剪、缩放到 192×192、
转换为 RGB 浮点数并按 Kinetics 统计量标准化。ONNX 输入布局为
`[1, 16, 3, 192, 192]`（NTCHW），模型内部再转成 S3D 使用的 NCTHW。模型输出一个
logit，App 使用 sigmoid 转为 0 到 1 的分数。

相机采集和模型执行分别串行化处理，避免阻塞界面，也避免同一个 ONNX Session 被并发
调用。

分数达到 0.65 时按真实经过时间累积证据，低于 0.10 时回退，中间区域保持。累计 6 秒高置信度证据后通过。

## 数据与隐私

闹钟配置使用 Room 保存在设备本地，并明确排除 Android 云备份和设备间迁移。正式版
不保存摄像头帧、不生成模型或闹钟 CSV，也没有日志分享 `FileProvider`；测试版可生成
数值诊断日志和人工标签。真实日志实现和分享组件只位于 `app/src/debug/`，Release
源码集只提供无写盘实现。Debug 和 Release 使用不同包名后缀，数据互不共享。

## 关键目录

- `app/src/main/`：两个构建类型共享的运行代码与资源；
- `app/src/debug/`：测试日志、人工标签、日志分享入口和测试版专用 `FileProvider`；
- `app/src/release/`：与 Debug 接口一致但不写日志、不暴露导出入口的正式版实现；
- `app/src/test/`：本地单元测试；
- `training/`：数据准备、训练、日志分析和 ONNX 导出；
- `docs/`：对外架构、兼容性、模型、隐私和数据政策；
- `scripts/`：模型下载和公开前审计。

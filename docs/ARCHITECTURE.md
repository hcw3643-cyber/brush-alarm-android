# 架构说明

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
`[1, 3, 16, 192, 192]`，输出一个 logit，App 使用 sigmoid 转为 0 到 1 的分数。

相机采集和模型执行分别串行化处理，避免阻塞界面，也避免同一个 ONNX Session 被并发
调用。这里的“双线程”是摄像头分析与推理解耦，不代表同时启动两个模型或固定占用两个
CPU 核；ONNX Runtime 可在设备允许时自行使用底层线程。

模型窗口天然高度重叠，因此 App 不再叠加中值/EMA 滤波。分数达到 0.65 时按真实经过
时间累积证据，低于 0.10 时回退，中间区域保持。累计 6 秒高置信度证据后通过。

## 数据与隐私

闹钟配置使用 Room 保存在设备本地。正式版不保存摄像头帧、不生成模型或闹钟 CSV，
也没有上传服务；测试版可生成数值诊断日志和人工标签。Debug 和 Release 使用不同
包名后缀，数据互不共享。

## 关键目录

- `app/src/main/`：正式运行代码与公共资源；
- `app/src/debug/`、`app/src/release/`：构建类型专用配置；
- `app/src/test/`：本地单元测试；
- `training/`：数据准备、训练、日志分析和 ONNX 导出；
- `docs/`：对外架构、兼容性、模型、隐私和数据政策；
- `scripts/`：模型下载和公开前审计。

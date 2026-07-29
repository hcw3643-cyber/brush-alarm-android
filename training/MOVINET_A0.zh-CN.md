# MoViNet A0 流式训练与验证

[English](MOVINET_A0.md) | 简体中文

## 结论

MoViNet A0 已完成与 S3D 相同 UCF group 划分上的二分类头训练和全视频滑窗验证，视频级
F1 为 **93.67%**，接近正式 S3D 的 93.83%。它只有 253.9 万参数，原生流式 TFLite
约 10.2 MB，值得作为下一代模型继续做真机域适配。

它目前**没有替换正式 S3D，也没有接入 App**，原因是：

- 当前只训练二分类头，backbone 保持 Kinetics-600 预训练权重冻结；
- UCF 动作视频不能代表手机前置摄像头、浴室光线和真实用户；
- 0.65 门限下 precision 为 100%，但 recall 只有 85.37%；
- 还没有多用户隔离测试、vivo X300 延迟、温升和耗电结果；
- App 当前使用 ONNX Runtime，A0 需要 LiteRT 和 43 个状态张量的生命周期管理。

## 为什么使用 Stream 2+1D

官方迁移学习教程默认的 `base + Conv3D` 图在实际测试中需要 `Select TF Ops`，普通
TFLite 解释器无法分配 `Conv3D` 和 `AvgPool3D`。最终使用官方移动端测试配置：

- `causal=true`
- `conv_type=2plus1d`
- `se_type=2plus3d`
- `activation=hard_swish`
- `gating_activation=hard_sigmoid`
- 显式输入/输出 43 个 stream-buffer 状态张量

该图只使用 TFLite built-in 算子，不需要 Flex delegate。

## 输入与状态契约

```text
训练输入：NTHWC [batch, 16, 172, 172, 3]
数值范围：RGB float32 [0, 1]
采样：8 fps，首尾跨度 1.875 秒
部署输入：NTHWC [1, 1, 172, 172, 3]，每次输入一个新帧
状态：43 个由模型返回并传给下一帧的张量
输出：一个二分类 logit
```

这与正式 S3D 的 192×192、Kinetics mean/std 标准化和 16 帧整窗 ONNX 输入不同。
接入 App 时必须实现独立预处理，并在新验证会话、摄像头重绑定、时间戳中断和 Session
重建时重置全部状态。

## 完整训练

- 环境：Python 3.12.3、TensorFlow 2.20.0、TF Models Official 2.20.0；
- 训练设备：RTX 4060 Laptop GPU（WSL2）；
- 官方 A0 Stream checkpoint：
  `d116a0d75abf3876976614cd3440cb08c4a03a907db2ba0c3dff5751ea0953c0`；
- 数据：486 个训练视频、198 个固定验证视频；
- 划分：UCF group 1–18 训练，19–25 验证；
- 训练采样：每轮 486 个类别平衡的随机 16 帧窗口，并执行整段一致的光照、颜色、
  翻转、运动模糊和传感器噪声增强；
- 优化：冻结 backbone，只训练 987,137 个二分类头参数；
- 最佳 checkpoint：第 4 轮，中心窗口验证 F1 0.88；
- 第 6 轮后早停，并恢复第 4 轮 checkpoint 做完整验证。

## 固定验证集结果

最终每 0.5 秒生成一个窗口，共 2554 个窗口；主指标对每段原视频取窗口分数中位数，
198 个视频各计一次。

| 模型 | Accuracy | Precision | Recall | F1 | 最佳门限 | 窗口 F1 |
|---|---:|---:|---:|---:|---:|---:|
| 正式 S3D + 真机域适配 | 97.47% | 95.00% | 92.68% | **93.83%** | 0.27 | 91.32% |
| MoViNet A0 Stream（冻结 backbone） | 97.47% | **97.37%** | 90.24% | 93.67% | 0.59 | 89.60% |

A0 在 App 当前 0.65 门限下：

| Accuracy | Precision | Recall | F1 |
|---:|---:|---:|---:|
| 96.97% | 100.00% | 85.37% | 92.11% |

门限和 checkpoint 都使用同一固定验证集选择，因此这仍是验证/标定结果，不是独立
held-out test。窗口高度重叠，也不能把 2554 个窗口当成 2554 个独立样本。

## 导出与数值一致性

| 项目 | 结果 |
|---|---:|
| 参数量 | 2,538,632 |
| TFLite 大小 | 10,202,540 bytes |
| TFLite SHA-256 | `369a3308d4c87c453db948bdd915cc843da52de269eedf1c4efc7e29d4729c95` |
| 流式 TensorFlow/TFLite logit 最大绝对误差 | `0.001543` |
| 流式 TensorFlow/TFLite sigmoid 分数最大绝对误差 | `2.09e-11` |
| WSL x86 两线程单帧延迟中位数 | 4.07 ms |
| 单帧 P90 | 4.24 ms |
| 16 帧序列延迟中位数 | 65.14 ms |

训练图一次处理 16 帧，部署图逐帧传递显式状态，两者不是完全相同的执行图，因此发布
一致性检查比较的是“流式 TensorFlow 图 vs TFLite”。训练图与流式图也单独记录误差，
但不能用它替代转换器一致性判断。

TFLite 转换器估算每帧约 50.67 MMAC；以 8 fps 持续执行约 0.405 GMAC/s。正式 S3D
重叠窗口方案约 26.4 GMAC/s，理论乘加量相差约 65 倍。该数字不包含相机预处理、运行
时调度或内存访问，不能替代 Android 实机耗电测试。

## 下一步

1. 使用按贡献者隔离的真机刷牙、停止动作和相似负样本微调；
2. 单独划分训练、标定和测试用户；
3. 重新确定高/低门限和累计证据时间；
4. 在 App 实验分支接入 LiteRT，并正确维护/重置 43 个状态；
5. 在 vivo X300 测量端到端延迟、温升、耗电和停止动作响应；
6. 评估 float16 或 int8 量化，再决定是否替换 S3D。

## 上游来源

- [MoViNets 论文](https://arxiv.org/abs/2103.11511)
- [TensorFlow 官方 MoViNet 流式教程](https://www.tensorflow.org/hub/tutorials/movinet)
- [TensorFlow 官方 MoViNet 迁移学习教程](https://www.tensorflow.org/tutorials/video/transfer_learning_with_movinet)
- [TensorFlow Model Garden MoViNet 源码](https://github.com/tensorflow/models/tree/master/official/projects/movinet)

TensorFlow Model Garden 源码使用 Apache-2.0；官方权重仍继承 Kinetics-600 数据来源的
许可与使用边界。本实验不提交或发布 checkpoint、TFLite 或原始视频。

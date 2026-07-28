# MoViNet A0 流式可行性验证

## 结论

MoViNet A0 **值得继续训练和实机评估，但当前没有替换正式 S3D，也没有接入 App**。
本仓库已经验证官方 A0 Stream 权重恢复、真实视频前向、二分类头反向、显式状态流式
推理、纯 TFLite built-in 导出和源模型/TFLite 数值一致性。

不能直接使用官方迁移学习教程中的默认 `base + Conv3D` 图作为移动端方案。实际测试
中它需要 `Select TF Ops` 才能转换，普通 TFLite 解释器因 `Conv3D` 和 `AvgPool3D`
无法分配张量。最终采用官方移动端测试使用的配置：

- `causal=true`
- `conv_type=2plus1d`
- `se_type=2plus3d`
- `activation=hard_swish`
- `gating_activation=hard_sigmoid`
- 显式输入/输出 43 个 stream-buffer 状态张量

该图可以只使用 TFLite built-in 算子，不需要 Flex delegate。

## 输入和状态语义

训练验证仍从每段视频取 16 帧、8 fps，覆盖 1.875 秒，但 MoViNet 使用：

```text
训练输入：NTHWC [2, 16, 172, 172, 3]
数值范围：RGB float32 [0, 1]
部署输入：NTHWC [1, 1, 172, 172, 3]，每次输入一个新帧
状态：43 个由模型返回并传给下一帧的张量
输出：一个二分类 logit
```

这和正式 S3D 的 `192×192`、Kinetics mean/std 标准化、16 帧整窗 ONNX 输入不相同。
接入 App 时必须新增独立预处理与状态生命周期，不能把现有 S3D 张量直接送入 A0。

## 本地验证结果

环境：Python 3.12.3、TensorFlow 2.20.0、TF Models Official 2.20.0；CPU-only WSL，
TFLite XNNPACK 两线程。

| 项目 | 结果 |
|---|---:|
| 二分类模型参数量 | 2,538,632 |
| 冻结 backbone 后可训练参数 | 987,137 |
| A0 Stream 官方 checkpoint SHA-256 | `d116a0d75abf3876976614cd3440cb08c4a03a907db2ba0c3dff5751ea0953c0` |
| TFLite 大小 | 10,202,540 bytes |
| TFLite SHA-256 | `1540505354ff2329ec956688532c426ded91be4415527d72b4983b7d55fd871f` |
| 源模型/TFLite 最大绝对误差 | `2.38e-7` |
| WSL x86 单帧延迟中位数 | 4.40 ms |
| WSL x86 单帧延迟 P90 | 5.02 ms |
| 16 帧序列延迟中位数 | 70.36 ms |

延迟只证明执行路径正常，不能外推为 vivo X300 或最低兼容芯片性能。生成物和完整 JSON
报告位于已忽略的 `training/export/movinet-a0/`。

## 验证边界

当前只用一段正样本和一段负样本验证数据管线，并对随机初始化的二分类头执行一次
反向更新。loss 为 0.62070，4 个头部梯度张量有效且更新后输出发生变化。这只能说明
以下链路成立：

```text
官方预训练权重 → 本项目真实视频预处理 → 冻结 backbone 的二分类头训练
→ checkpoint 对象恢复 → 显式流状态模型 → 原生 TFLite → 逐帧推理
```

它没有产生刷牙分类准确率，不能与 S3D 的 93.83% F1 比较。下一阶段需要：

1. 使用与 S3D 相同的 UCF group 划分完整训练 A0；
2. 在视频级指标上比较 F1、precision、recall 和门限；
3. 加入按贡献者隔离的真机数据；
4. 在 Android 端接入 LiteRT/TFLite，并正确持有/重置 43 个状态；
5. 测量 vivo X300 上逐帧延迟、温升、耗电和停止动作后的响应；
6. 评估 float16 或 int8 量化对准确率和速度的影响。

## 上游来源

- [MoViNets 论文](https://arxiv.org/abs/2103.11511)
- [TensorFlow 官方 MoViNet 流式教程](https://www.tensorflow.org/hub/tutorials/movinet)
- [TensorFlow 官方 MoViNet 迁移学习教程](https://www.tensorflow.org/tutorials/video/transfer_learning_with_movinet)
- [TensorFlow Model Garden MoViNet 源码](https://github.com/tensorflow/models/tree/master/official/projects/movinet)

TensorFlow Model Garden 源码使用 Apache-2.0；官方权重仍继承 Kinetics-600 数据来源的
许可与使用边界。本实验不提交或重新分发 checkpoint、TFLite 或视频。

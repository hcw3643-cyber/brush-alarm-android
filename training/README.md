# 刷牙视频分类模型

这里训练的不是画面抖动阈值，而是二分类时序视觉模型：

- 正样本：`BrushingTeeth`
- 困难负样本：`ApplyLipstick`、`BlowDryHair`、`HeadMassage`、`ShavingBeard`
- 普通负样本：从其它 UCF101 动作中补充抽样

每个训练窗口按固定时间轴采样 16 帧、8 fps，覆盖 1.875 秒；空间输入为中心方形
裁剪后的 192×192 RGB。训练时随机选择连续窗口，验证时每 0.5 秒取一个重叠窗口，
与 Android 端完全一致，而不是从整段视频均匀抽 16 帧。

训练增强对整段 clip 保持一致，包括曝光/伽马、对比度、白平衡、水平翻转和轻微
运动模糊，并加入少量逐帧传感器噪声，以覆盖浴室明暗和手机摄像头差异。
预训练数据本身包含 `brushing teeth` 类别，二分类头也用该类别权重初始化，再在
UCF101 上微调最后两个 S3D block。训练后导出为单文件 ONNX，供 Android 端连续
滑窗推理。

公开数据基础模型在固定验证集（UCF group 19 及以后，以每个原视频的窗口中位数
计一次）结果为 accuracy 97.47%、precision 95.00%、recall 92.68%、F1 93.83%。
窗口每 0.5 秒重叠，因此不把所有重叠窗口冒充独立测试样本。

加入一段经同意的真机正样本做一轮混合微调后，隔离的 UCF 指标保持不变；固定
0.70 高门限在原视频级 precision 为 100%、recall 为 87.80%，窗口级 precision
为 97.22%、recall 为 89.86%。同一真机正样本仅用于检查域适配是否生效，不作为
独立泛化成绩：按 App 证据桶回放的通过时间由约 19.4 秒降到约 7.4 秒。

UCF101 只用于基础模型。实际发布前必须加入手机前置摄像头拍摄的刷牙、假刷牙和
嘴边相似动作数据做微调。

## 目录

- `prepare_ucf101.py`：从 UCF101 Hugging Face tar 分片中提取需要的类别
- `train.py`：训练与验证
- `finetune_feedback.py`：混合公开困难负样本和经同意的本地真机正样本做第二阶段微调
- `export_onnx.py`：导出 Android 端模型
- `analyze_inference_logs.py`：汇总 App 导出的带人工标签 CSV

## 运行

```bash
/home/dev/.venvs/ai-infra/bin/python training/prepare_ucf101.py
/home/dev/.venvs/ai-infra/bin/python training/train.py
/home/dev/.venvs/ai-infra/bin/python training/finetune_feedback.py \
  --positive-video /path/to/consented-brushing-video.mp4
/home/dev/.venvs/ai-infra/bin/python training/export_onnx.py
/home/dev/.venvs/ai-infra/bin/python training/analyze_inference_logs.py /path/to/logs/
```

生成的 `training/export/brush_classifier.onnx` 需复制到
`app/src/main/assets/brush_classifier.onnx`。下载环境如果需要 WSL 宿主机代理，可设置
`TRAINING_PROXY=http://<WSL默认网关>:7890`；准备脚本默认也会尝试这个地址。

# 刷牙视频分类模型

这里训练的不是画面抖动阈值，而是二分类时序视觉模型：

- 正样本：`BrushingTeeth`
- 困难负样本：`ApplyLipstick`、`BlowDryHair`、`HeadMassage`、`ShavingBeard`
- 普通负样本：从其它 UCF101 动作中补充抽样

模型对每段视频均匀取 16 帧，使用 Kinetics-400 预训练的 S3D 直接提取时空特征。
预训练数据本身包含 `brushing teeth` 类别，二分类头也用该类别权重初始化，再在
UCF101 上微调最后两个 S3D block。训练后导出为单文件 ONNX，供 Android 端连续
滑窗推理。

本次固定验证集（UCF group 19 及以后）最佳结果：accuracy 97.47%、precision 100%、
recall 87.80%、F1 93.51%，阈值 0.78。最终 ONNX 与 PyTorch 的单样本 logit
最大实测误差约 2.29e-5。

UCF101 只用于基础模型。实际发布前必须加入手机前置摄像头拍摄的刷牙、假刷牙和
嘴边相似动作数据做微调。

## 目录

- `prepare_ucf101.py`：从 UCF101 Hugging Face tar 分片中提取需要的类别
- `train.py`：训练与验证
- `export_onnx.py`：导出 Android 端模型

## 运行

```bash
/home/dev/.venvs/ai-infra/bin/python training/prepare_ucf101.py
/home/dev/.venvs/ai-infra/bin/python training/train.py
/home/dev/.venvs/ai-infra/bin/python training/export_onnx.py
```

生成的 `training/export/brush_classifier.onnx` 需复制到
`app/src/main/assets/brush_classifier.onnx`。下载环境如果需要 WSL 宿主机代理，可设置
`TRAINING_PROXY=http://<WSL默认网关>:7890`；准备脚本默认也会尝试这个地址。

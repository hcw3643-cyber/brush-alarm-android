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
标定时的 0.70 高门限在原视频级 precision 为 100%、recall 为 87.80%，窗口级 precision
为 97.22%、recall 为 89.86%。同一真机正样本仅用于检查域适配是否生效，不作为
独立泛化成绩：其窗口置信度中位数由约 0.46 提升到约 0.97，高于 0.70 高门限的
窗口比例由 35% 提升到 76.25%。

真机连续刷牙日志显示 0.70 会漏掉较多有效窗口，App 1.0 最终使用 0.65 高门限；
低门限仍为 0.10，累计通过时间为 6 秒。

UCF101 只用于基础模型。实际发布前必须加入手机前置摄像头拍摄的刷牙、假刷牙和
嘴边相似动作数据做微调。

## 目录

- `configs/default.toml`：路径、数据划分和加载器的公开默认值
- `configs/s3d.toml`：正式 S3D 实验参数
- `configs/lightweight.toml`：MobileNetV3 流式实验参数
- `configs/movinet_a0.toml`：官方 MoViNet A0 流式验证参数
- `config.example.toml`：本机路径覆盖模板
- `config.py`：TOML 合并、环境变量覆盖和仓库相对路径解析
- `prepare_ucf101.py`：从 UCF101 Hugging Face tar 分片中提取需要的类别
- `train.py`：训练与验证
- `finetune_feedback.py`：混合公开困难负样本和经同意的本地真机正样本做第二阶段微调
- `export_onnx.py`：导出 Android 端模型
- `lightweight_model.py`：流式 MobileNetV3 + 时序差分头
- `train_lightweight.py`：用当前 S3D 模型蒸馏轻量模型，并支持逐轮断点续训
- `export_lightweight_onnx.py`：分别导出逐帧编码器和时序头
- `validate_movinet_a0.py`：恢复官方 A0 Stream 权重，验证二分类头训练和原生 TFLite 导出
- `analyze_inference_logs.py`：汇总 App 导出的带人工标签 CSV

## 配置

可复现实验参数提交在 `configs/*.toml`。默认路径以仓库根目录为基准，不依赖运行命令
时的当前目录，也不包含维护者用户名。

只有在数据或输出位于仓库之外时，才复制本机模板：

```bash
cp training/config.example.toml training/config.local.toml
```

`config.local.toml` 已被 `.gitignore` 排除。密钥和 Token 不应写进 TOML，应使用环境
变量或 CI Secret。路径也可使用：

```bash
export BRUSH_TRAINING_DATA_DIR=/data/brush-alarm
export BRUSH_TRAINING_CHECKPOINT_DIR=/data/brush-alarm-checkpoints
export BRUSH_TRAINING_EXPORT_DIR=/data/brush-alarm-exports
export BRUSH_TRAINING_CACHE_DIR=/data/brush-alarm-cache
```

覆盖优先级为：命令行参数、环境变量、`--config` 指定文件、`config.local.toml`、实验
配置、默认配置。不要把整个 `configs/` 加入 `.gitignore`，否则其他人无法复现实验。

## PyTorch 运行环境

```bash
python -m venv .venv-training
source .venv-training/bin/activate
python -m pip install -r training/requirements.txt
python -m training.prepare_ucf101
python -m training.train
python -m training.finetune_feedback \
  --positive-video /path/to/consented-brushing-video.mp4
python -m training.export_onnx
python -m training.train_lightweight
python -m training.export_lightweight_onnx
python -m training.analyze_inference_logs /path/to/logs/
```

PyTorch 和 TorchVision 必须选择与本机 CPU/CUDA 匹配的同一发布组合。仓库不固定任何
开发者的虚拟环境，也不把 CUDA 13.0 wheel 当成所有人的默认值。需要特定 CUDA
版本时应按 PyTorch 官方安装选择器执行，再安装其余依赖。

生成的 `training/export/brush_classifier.onnx` 需复制到
`app/src/main/assets/brush_classifier.onnx`。导出器会删除可能包含本机绝对路径的
ONNX 调试元数据，执行 PyTorch/ONNX Runtime 数值一致性检查，并在发现本机路径时
拒绝生成发布产物。

下载默认遵循标准 `HTTP_PROXY`、`HTTPS_PROXY` 和 `NO_PROXY`。若只想覆盖数据准备
脚本，可额外设置 `TRAINING_PROXY`；项目不再假设 WSL 网关或固定的 7890 端口。

## MoViNet A0 隔离验证

TensorFlow Model Garden 的依赖树与 PyTorch 相互独立，因此使用另一个虚拟环境：

```bash
python -m venv .venv-movinet
source .venv-movinet/bin/activate
python -m pip install -r training/requirements-movinet.txt
python -m training.validate_movinet_a0
```

验证结论、实际 TFLite 大小和限制见 [`MOVINET_A0.md`](MOVINET_A0.md)。该脚本只验证
官方权重恢复、真实 clip 前向、二分类头反向、显式流状态、原生 TFLite 转换与数值
一致性；一次训练步不是准确率实验。

训练数据、视频、日志、检查点和导出模型都被 `.gitignore` 排除，不能提交到仓库。
正式发布模型时应使用独立 Release 资产并记录 SHA-256。模型来源、指标和限制以
[`docs/MODEL_CARD.md`](../docs/MODEL_CARD.md) 为准；志愿者视频不得通过公开 Issue
或 PR 提交，具体原则见
[`docs/DATA_CONTRIBUTION.md`](../docs/DATA_CONTRIBUTION.md)。

轻量模型的结构、同一验证集对照结果、计算量与当前限制见
[`LIGHTWEIGHT_MODEL.md`](LIGHTWEIGHT_MODEL.md)。它目前是实验结果，没有替换 App
正式使用的 S3D 模型。

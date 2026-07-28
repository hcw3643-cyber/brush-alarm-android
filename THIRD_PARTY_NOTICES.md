# 第三方组件与来源声明

[English](THIRD_PARTY_NOTICES_EN.md) | 简体中文

本文件是项目的依赖与来源索引。各项目的原始许可证文本具有最终效力；本项目的
GNU GPL v3.0 only 条款不会替换第三方组件、模型权重或训练数据各自的许可条款。

## Android App

| 组件 | 用途 | 许可证 | 来源 |
|---|---|---|---|
| AndroidX / Compose / CameraX / Room | UI、相机、生命周期、数据库 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin | Android 开发语言与插件 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Gradle | 构建工具与 Wrapper | Apache-2.0 | https://github.com/gradle/gradle |
| ONNX Runtime Android | 手机端 ONNX 推理 | MIT | https://github.com/microsoft/onnxruntime |
| JUnit 4 | 单元测试 | EPL-1.0 | https://github.com/junit-team/junit4 |

## 训练工具

| 组件 | 用途 | 许可证 | 来源 |
|---|---|---|---|
| PyTorch | 模型训练与导出 | BSD-3-Clause | https://github.com/pytorch/pytorch |
| TorchVision | S3D、MobileNetV3 网络与预训练权重接口 | BSD-3-Clause（源码） | https://github.com/pytorch/vision |
| OpenCV | 视频解码与增强 | Apache-2.0 | https://github.com/opencv/opencv |
| NumPy | 数值处理 | BSD-3-Clause | https://github.com/numpy/numpy |
| ONNX | 模型格式与检查 | Apache-2.0 | https://github.com/onnx/onnx |
| ONNX Script | ONNX 导出支持 | Apache-2.0 | https://github.com/microsoft/onnxscript |
| ONNX Runtime Python | 导出后验证 | MIT | https://github.com/microsoft/onnxruntime |
| TensorFlow / LiteRT | MoViNet A0 隔离训练与移动端格式验证 | Apache-2.0 | https://github.com/tensorflow/tensorflow |
| TensorFlow Model Garden | 官方 MoViNet A0 架构、工具和预训练权重接口 | Apache-2.0（源码） | https://github.com/tensorflow/models |

## 模型和数据来源

- TorchVision S3D Kinetics-400 V1：
  https://docs.pytorch.org/vision/main/models/generated/torchvision.models.video.s3d.html
- 实验性流式模型使用的 TorchVision MobileNetV3 Large ImageNet-1K V2：
  https://docs.pytorch.org/vision/main/models/generated/torchvision.models.mobilenet_v3_large.html
- 实验性 MoViNet A0 Stream 架构与权重：
  https://github.com/tensorflow/models/tree/master/official/projects/movinet
- TorchVision 预训练模型许可提示：
  https://github.com/pytorch/vision#pre-trained-model-license
- Kinetics 数据集下载与说明：
  https://github.com/cvdfoundation/kinetics-dataset
- UCF101 官方项目：
  https://www.crcv.ucf.edu/research/data-sets/ucf101/
- 当前训练脚本使用的 UCF101 镜像：
  https://huggingface.co/datasets/guyuchao/UCF101

Hugging Face 镜像未提供 Dataset Card 或明确许可证。项目只用它复现实验数据，不把
镜像上传者视为 UCF101 权利人，也不再分发镜像中的原始视频。实验性轻量模型同时
使用 ImageNet-1K 预训练权重，并从当前 S3D 模型蒸馏，因此不会消除 S3D、Kinetics
或 UCF101 的来源与许可边界。MoViNet A0 验证使用 Kinetics-600 预训练权重，因此也
不能消除 Kinetics 数据来源的许可边界；仓库记录完整训练和验证方法，但不提交或发布
A0 checkpoint、TFLite 权重或原始视频。

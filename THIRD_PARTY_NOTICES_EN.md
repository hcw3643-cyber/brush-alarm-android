# Third-Party Components and Provenance

English | [简体中文](THIRD_PARTY_NOTICES.md)

This file indexes project dependencies and provenance. The original license text
of each upstream project controls. GNU GPL v3.0 only for this project does not
replace the separate terms of third-party components, model weights, or training
data.

## Android application

| Component | Purpose | License | Source |
|---|---|---|---|
| AndroidX / Compose / CameraX / Room | UI, camera, lifecycle, database | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin | Android language and plugin | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Gradle | Build tool and Wrapper | Apache-2.0 | https://github.com/gradle/gradle |
| ONNX Runtime Android | On-device ONNX inference | MIT | https://github.com/microsoft/onnxruntime |
| JUnit 4 | Unit tests | EPL-1.0 | https://github.com/junit-team/junit4 |

## Training tools

| Component | Purpose | License | Source |
|---|---|---|---|
| PyTorch | Model training and export | BSD-3-Clause | https://github.com/pytorch/pytorch |
| TorchVision | S3D/MobileNetV3 code and pretrained-weight APIs | BSD-3-Clause (source) | https://github.com/pytorch/vision |
| OpenCV | Video decoding and augmentation | Apache-2.0 | https://github.com/opencv/opencv |
| NumPy | Numerical processing | BSD-3-Clause | https://github.com/numpy/numpy |
| ONNX | Model format and validation | Apache-2.0 | https://github.com/onnx/onnx |
| ONNX Script | ONNX export support | Apache-2.0 | https://github.com/microsoft/onnxscript |
| ONNX Runtime Python | Post-export validation | MIT | https://github.com/microsoft/onnxruntime |
| TensorFlow / LiteRT | Isolated MoViNet A0 training and mobile-format validation | Apache-2.0 | https://github.com/tensorflow/tensorflow |
| TensorFlow Model Garden | Official MoViNet A0 implementation and pretrained-weight API | Apache-2.0 (source) | https://github.com/tensorflow/models |

## Model and data sources

- TorchVision S3D Kinetics-400 V1:
  https://docs.pytorch.org/vision/main/models/generated/torchvision.models.video.s3d.html
- TorchVision MobileNetV3 Large ImageNet-1K V2 used by the experimental
  streaming model:
  https://docs.pytorch.org/vision/main/models/generated/torchvision.models.mobilenet_v3_large.html
- Experimental MoViNet A0 Stream architecture and weights:
  https://github.com/tensorflow/models/tree/master/official/projects/movinet
- TorchVision pretrained-model license warning:
  https://github.com/pytorch/vision#pre-trained-model-license
- Kinetics dataset:
  https://github.com/cvdfoundation/kinetics-dataset
- Official UCF101 project:
  https://www.crcv.ucf.edu/research/data-sets/ucf101/
- UCF101 mirror used by the current training script:
  https://huggingface.co/datasets/guyuchao/UCF101

The Hugging Face mirror provides neither a Dataset Card nor a clear license. It
is used only to reproduce experiments; the uploader is not treated as the
UCF101 rights holder, and the project does not redistribute source video. The
experimental lightweight model also uses ImageNet-1K weights and distillation
from S3D, so it does not remove S3D, Kinetics, or UCF101 provenance boundaries.
MoViNet A0 uses Kinetics-600 pretrained weights and therefore retains the
Kinetics data boundary. The repository records the complete training and
validation method but commits or releases no A0 checkpoint, TFLite weights, or
source video.

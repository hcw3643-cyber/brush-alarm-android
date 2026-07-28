# Experimental Lightweight Streaming Model

English | [简体中文](LIGHTWEIGHT_MODEL.md)

## Status

This experiment tests whether continuous brushing recognition can use less
mobile compute. Training, checkpoints, and ONNX export are implemented, but the
model **is not integrated into the production app and must not replace S3D**.
Its fixed-split F1 remains substantially lower and its output is not calibrated
for the app's 0.65 threshold.

## Architecture

- 16 frames at 8 fps and 192×192 RGB, spanning 1.875 seconds
- MobileNetV3 Large encodes each new frame into a 192-dimensional embedding
- A 16-item ring buffer would avoid re-encoding overlapping frames in the app
- A depthwise-separable temporal head summarizes embeddings plus the mean and
  standard deviation of adjacent embedding differences
- Loss combines 70% hard-label BCE and 30% S3D teacher distillation
- The encoder is frozen for four epochs, then its final three blocks are
  fine-tuned with frozen BatchNorm statistics
- Deployment uses separate frame-encoder and temporal-head ONNX files

Explicit differences expose motion to the classifier and reduce reliance on
static cues such as a face, hand, or toothbrush merely appearing near the mouth.

## Fixed validation split

All models use UCF101 groups 19+ for validation, overlapping windows every
0.5 seconds, and one median score per source video:

| Model | Parameters | Accuracy | Precision | Recall | F1 | Best threshold | Window F1 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Current S3D + phone adaptation | ~7.9M | 97.47% | 95.00% | 92.68% | 93.83% | 0.27 | 91.32% |
| MobileNetV3 Small + pooled head | ~1.13M | 81.82% | 54.24% | 78.05% | 64.00% | 0.89 | 58.07% |
| MobileNetV3 Large + pooled head | ~3.25M | 85.35% | 60.34% | 85.37% | 70.71% | 0.07 | 72.28% |
| MobileNetV3 Large + motion head | 3,344,050 | 88.89% | 69.39% | 82.93% | **75.56%** | 0.09 | 72.89% |

These results compare repository experiments, not real bathrooms, users, or
phones. At 0.70, the best lightweight model has only 60.87% precision and
34.15% recall, so the production threshold cannot be reused directly.

## Export and compute

- `brush_frame_encoder.onnx`: 12,612,510 bytes, SHA-256
  `769cbb084f21248c0ea3c735cca0329b57e12dd61be6172b6a032125f6f04206`
- `brush_temporal_head.onnx`: 756,061 bytes, SHA-256
  `7d93297f43a10e55a6eadd72d7af581b5cc37246b3c03a751145589eee46ea2f`
- PyTorch/ONNX Runtime maximum absolute error below `1e-6`
- About `1.27 GMAC/s` at 8 fps plus two temporal-head runs per second, versus
  about `26.4 GMAC/s` for overlapping S3D; roughly 20× fewer multiply-adds

WSL x86 ONNX Runtime measured about 5.25 ms/frame with one thread and
3.17 ms/frame with four. These are not Android latency or power guarantees.

## Reproduction

After preparing the environment and S3D teacher as described in
[README_EN.md](README_EN.md):

```bash
python -m training.train_lightweight
python -m training.export_lightweight_onnx
python -m training.train_lightweight --resume
```

Checkpoints and exports are ignored. Any future weight release must update the
model card, hashes, device results, and provenance notices.

## Before production consideration

1. Add contributor-isolated phone positives, stopped actions, and confusing
   mouth-area negatives.
2. Separate train, calibration, and test by contributor.
3. Calibrate output and app thresholds on an independent calibration set.
4. Measure end-to-end latency, thermal behavior, and battery use on Android.
5. Only after acceptable accuracy, implement the feature ring buffer and two
   ONNX sessions.

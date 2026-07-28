# Model Card: s3d-brush-2s-192-feedback-v1

English | [简体中文](../MODEL_CARD.md)

## Summary

This is an experimental binary video classifier for Brush Alarm. It decides
whether a short window contains a brushing action. It does not perform face or
identity recognition, dental diagnosis, or medical assessment.

- File: `brush_classifier.onnx`
- Release tag: `model-v1.0.1`
- SHA-256: `505ab603651c0cd04aa06fafaef773b6a97f57a210308fdfa4752407af0e8eb5`
- Size: 31,650,265 bytes
- Runtime: ONNX Runtime Mobile/Android, executed on device
- Network: TorchVision S3D with one binary logit

The model file is excluded from Git history and distributed as a separate GitHub
Release asset.

The 2026-07-28 re-export removes machine-local debug-path metadata written by the
PyTorch exporter. Weights and input/output semantics are unchanged; the maximum
PyTorch/ONNX Runtime absolute error is `1.43e-6`.

## Input and output

The app samples the CameraX front-camera stream by timestamp:

- 16 RGB frames
- 8 fps, spanning approximately 1.875 seconds
- Center square crop resized to 192×192
- Pixels scaled to `[0, 1]`, then normalized per channel as `(x - mean) / std`
- Mean: `[0.43216, 0.394666, 0.37645]`
- Standard deviation: `[0.22803, 0.22145, 0.216989]`
- float32 ONNX input in NTCHW layout: `[1, 16, 3, 192, 192]`; the graph
  transposes it to S3D's NCTHW layout

The model emits one logit and the app applies sigmoid to obtain a score in
`[0, 1]`. Inference runs every 0.5 seconds, so adjacent windows share 12 of 16
frames. The 1.0 decision filter uses a 0.65 high threshold, 0.10 low threshold,
and six seconds of accumulated evidence. The score is not a probability
calibrated across a large real-user population.

## Training provenance

1. TorchVision `S3D_Weights.KINETICS400_V1` pretrained weights
2. UCF101 `BrushingTeeth`, hard-negative, and ordinary negative classes
3. One maintainer-consented phone brushing recording for limited domain
   adaptation

UCF101 video is obtained through the Hugging Face user mirror
`guyuchao/UCF101`. It is not an official UCF101 distribution channel and
provides no separate license for the source video. The project redistributes no
UCF101 video and does not call the mirror official. See
[THIRD_PARTY_NOTICES_EN.md](../../THIRD_PARTY_NOTICES_EN.md) and
[MODEL_LICENSE_EN.md](../../MODEL_LICENSE_EN.md).

## Training and validation

Training chooses a random continuous 1.875-second clip. Validation emits an
overlapping window every 0.5 seconds. UCF101 groups 19 and later form the fixed
validation split, preventing windows from one source video from entering both
train and validation. Exposure, gamma, contrast, white balance, horizontal
flip, mild motion blur, and sensor noise are applied consistently across a clip.

On the fixed public-data validation split, counting the median score of each
source video once:

| Metric | Result |
|---|---:|
| Accuracy | 97.47% |
| Precision | 95.00% |
| Recall | 92.68% |
| F1 | 93.83% |

At threshold 0.70, video-level precision/recall is 100%/87.80%, and window-level
precision/recall is 97.22%/89.86%. Mixed fine-tuning with the local positive
sample preserves the isolated UCF metrics. The local sample's median window
score rises from about 0.46 to 0.97, and the fraction above 0.70 rises from 35%
to 76.25%.

Those last numbers come from the same recording used for training. They only
confirm that domain adaptation changed that sample and are not independent
generalization results. Overlapping windows are correlated and must not be
counted as independent test samples.

## Limitations and risks

- Very limited coverage of real users, devices, skin tones, bathroom lighting,
  toothbrushes, and camera angles
- Similar mouth-area movement, shaving, or phone shake may be mistaken for
  brushing, while real brushing may be missed
- Scores and thresholds may change between models and are not directly
  comparable across versions
- A covered camera, revoked permission, or extreme darkness prevents reliable
  operation
- Not a sole safeguard for health, medical, safety, or guaranteed wake-up use

Always keep a system alarm or another backup.

## Future data evaluation

Volunteer data must be split by contributor, never allowing adjacent video from
one person to cross train, validation, and test. Recall, false-positive rate, and
sample count should be reported separately across devices, lighting, and
demographic groups. No raw video may enter public Git history or model Releases.

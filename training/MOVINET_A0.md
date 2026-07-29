# MoViNet A0 Streaming Training and Validation

English | [简体中文](MOVINET_A0.zh-CN.md)

## Conclusion

MoViNet A0 has completed binary-head training and full sliding-window validation
on the same UCF group split as S3D. Its video-level F1 is **93.67%**, close to
production S3D's 93.83%. With 2.539 million parameters and a native streaming
TFLite artifact of about 10.2 MB, it is worth continued phone-domain
adaptation as a next-generation model.

It **has not replaced S3D and is not integrated into the app** because:

- Only the binary head is trained; the Kinetics-600 backbone remains frozen.
- UCF action video does not represent phone front cameras, bathroom lighting,
  or real users.
- At the app's 0.65 threshold, precision is 100% but recall is only 85.37%.
- No contributor-isolated multi-user test or vivo X300 latency, thermal, and
  power benchmark exists.
- The app currently uses ONNX Runtime; A0 requires LiteRT and lifecycle
  management for 43 state tensors.

## Why Stream 2+1D

The official tutorial's default `base + Conv3D` graph required Select TF Ops in
testing, and a regular TFLite interpreter could not allocate its
`Conv3D`/`AvgPool3D` operations. The mobile graph uses:

- `causal=true`
- `conv_type=2plus1d`
- `se_type=2plus3d`
- `activation=hard_swish`
- `gating_activation=hard_sigmoid`
- 43 explicit stream-buffer state inputs and outputs

It converts using only TFLite built-in operators and needs no Flex delegate.

## Input and state contract

```text
Training input: NTHWC [batch, 16, 172, 172, 3]
Value range: RGB float32 [0, 1]
Sampling: 8 fps, spanning 1.875 seconds
Deployment input: NTHWC [1, 1, 172, 172, 3], one new frame per call
State: 43 tensors returned by one call and supplied to the next
Output: one binary logit
```

This differs from S3D's 192×192 input, Kinetics mean/std normalization, and
full-window ONNX execution. Integration must implement separate preprocessing
and reset all state on a new session, camera rebind, timestamp discontinuity, or
runtime recreation.

## Complete training

- Python 3.12.3, TensorFlow 2.20.0, TF Models Official 2.20.0
- RTX 4060 Laptop GPU under WSL2
- Official Stream checkpoint SHA-256:
  `d116a0d75abf3876976614cd3440cb08c4a03a907db2ba0c3dff5751ea0953c0`
- 486 training videos and 198 fixed validation videos
- UCF groups 1–18 for training and 19–25 for validation
- 486 class-balanced random clips per epoch with clip-consistent lighting,
  color, flip, motion-blur, and sensor-noise augmentation
- Frozen backbone; 987,137 binary-head parameters trained
- Best checkpoint at epoch 4 with center-clip F1 0.88
- Early stop after epoch 6, then restore epoch 4 for complete validation

## Fixed validation result

Validation emits one window every 0.5 seconds: 2,554 windows total. Main metrics
count the median score of each of 198 source videos once.

| Model | Accuracy | Precision | Recall | F1 | Best threshold | Window F1 |
|---|---:|---:|---:|---:|---:|---:|
| Production S3D + phone adaptation | 97.47% | 95.00% | 92.68% | **93.83%** | 0.27 | 91.32% |
| MoViNet A0 Stream, frozen backbone | 97.47% | **97.37%** | 90.24% | 93.67% | 0.59 | 89.60% |

At the app's current 0.65 threshold:

| Accuracy | Precision | Recall | F1 |
|---:|---:|---:|---:|
| 96.97% | 100.00% | 85.37% | 92.11% |

Checkpoint and threshold selection use the same fixed validation split, so this
is validation/calibration, not an independent held-out test. Overlapping windows
are correlated and must not be presented as 2,554 independent examples.

## Export and numerical parity

| Item | Result |
|---|---:|
| Parameters | 2,538,632 |
| TFLite size | 10,202,540 bytes |
| TFLite SHA-256 | `369a3308d4c87c453db948bdd915cc843da52de269eedf1c4efc7e29d4729c95` |
| Streaming TensorFlow/TFLite maximum logit error | `0.001543` |
| Streaming TensorFlow/TFLite maximum sigmoid-score error | `2.09e-11` |
| WSL x86 two-thread median per frame | 4.07 ms |
| Per-frame P90 | 4.24 ms |
| Median 16-frame sequence | 65.14 ms |

The training graph consumes a whole clip while deployment carries explicit
state frame by frame. They are not the same execution graph, so conversion
parity correctly compares streaming TensorFlow with TFLite. Training-vs-stream
drift is recorded separately, but cannot replace converter-parity validation.

The converter estimates about 50.67 MMAC per frame, or 0.405 GMAC/s at 8 fps.
The overlapping S3D path is about 26.4 GMAC/s, roughly 65× more multiply-adds.
That theoretical comparison excludes preprocessing, scheduling, and memory
traffic and cannot replace an Android power benchmark.

## Next steps

1. Fine-tune on contributor-isolated phone positives, stopped actions, and
   confusing negatives.
2. Split train, calibration, and test by contributor.
3. Recalibrate thresholds and accumulated-evidence duration.
4. Integrate LiteRT on an experimental app branch with correct state resets.
5. Measure vivo X300 latency, thermal behavior, power, and stop-action response.
6. Evaluate float16/int8 quantization before considering S3D replacement.

## Upstream

- [MoViNets paper](https://arxiv.org/abs/2103.11511)
- [Official TensorFlow streaming tutorial](https://www.tensorflow.org/hub/tutorials/movinet)
- [Official TensorFlow transfer-learning tutorial](https://www.tensorflow.org/tutorials/video/transfer_learning_with_movinet)
- [TensorFlow Model Garden implementation](https://github.com/tensorflow/models/tree/master/official/projects/movinet)

TensorFlow Model Garden source uses Apache-2.0. Official weights remain subject
to the licensing and use boundaries of their Kinetics-600 data source. This
experiment does not commit or release a checkpoint, TFLite artifact, or source
video.

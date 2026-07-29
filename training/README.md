# Brushing Video Classifier

English | [简体中文](README.zh-CN.md)

This directory trains temporal binary video classifiers, not a screen-shake
threshold:

- Positive: `BrushingTeeth`
- Hard negatives: `ApplyLipstick`, `BlowDryHair`, `HeadMassage`, `ShavingBeard`
- Ordinary negatives: optional samples from other UCF101 actions

Each window follows a fixed time axis: 16 frames at 8 fps, spanning 1.875
seconds. Frames are center-square-cropped to 192×192 RGB for S3D. Training
selects a random continuous window; validation emits an overlapping window every
0.5 seconds, matching Android instead of uniformly sampling 16 frames across an
entire source video.

Augmentation is clip-consistent and includes exposure/gamma, contrast, white
balance, horizontal flip, mild motion blur, and small per-frame sensor noise.
The pretrained data already contains a `brushing teeth` class, so the binary
head is initialized from that class's weights before the final two S3D blocks
are fine-tuned on UCF101. Training then exports a single ONNX file for
continuous sliding-window inference on Android.

On the fixed public-data validation split—UCF group 19 and later, counting each
source video's median window score once—the base model reaches 97.47% accuracy,
95.00% precision, 92.68% recall, and 93.83% F1. Windows overlap every 0.5
seconds, so they are not misrepresented as independent test samples.

After one mixed fine-tuning pass with a consented positive phone recording, the
isolated UCF metrics remain unchanged. At the fixed calibration threshold of
0.70, video-level precision/recall is 100%/87.80%, and window-level
precision/recall is 97.22%/89.86%. The same phone recording is used only to
check that domain adaptation took effect, not as an independent generalization
result: its median window score rises from about 0.46 to about 0.97, and the
fraction of windows above 0.70 rises from 35% to 76.25%.

Continuous phone brushing logs show that 0.70 rejects too many valid windows.
App 1.0 therefore uses a 0.65 high threshold, while the low threshold remains
0.10 and the accumulated pass duration is six seconds.

UCF101 is only a base-model dataset. Before production use, the model still
needs phone front-camera brushing, simulated brushing, and confusing
mouth-area actions for fine-tuning.

## Layout

- `configs/default.toml`: public paths, split, and loader defaults
- `configs/s3d.toml`: production S3D experiment
- `configs/lightweight.toml`: MobileNetV3 streaming experiment
- `configs/movinet_a0.toml`: official MoViNet A0 Stream validation parameters
- `config.example.toml`: machine-local path override template
- `config.py`: TOML merging, environment-variable overrides, and
  repository-relative path resolution
- `prepare_ucf101.py`: extract selected classes from UCF101 mirror shards
- `train.py`: training and validation
- `finetune_feedback.py`: second-stage fine-tuning with public hard negatives
  and a consented local positive recording
- `export_onnx.py`: export the Android model
- `lightweight_model.py`: streaming MobileNetV3 plus temporal-difference head
- `train_lightweight.py`: distill the current S3D into the lightweight model
  with epoch-level resume support
- `export_lightweight_onnx.py`: export separate frame-encoder and temporal-head
  models
- `validate_movinet_a0.py`: restore official A0 Stream weights and validate
  binary-head training plus native TFLite export
- `train_movinet_a0.py`: train the A0 binary head on the full UCF split, perform
  video-level validation, and export TFLite
- `analyze_inference_logs.py`: summarize manually labeled app CSV logs

## Configuration

Reproducible experiment values are committed in `configs/*.toml`. Relative paths
are always resolved from the repository root and contain no maintainer username.
Only create a local override when data or outputs live elsewhere:

```bash
cp training/config.example.toml training/config.local.toml
```

`config.local.toml` is ignored. Never place tokens or passwords in TOML; use
environment variables or CI secrets. Paths may also be supplied through:

```bash
export BRUSH_TRAINING_DATA_DIR=/data/brush-alarm
export BRUSH_TRAINING_CHECKPOINT_DIR=/data/brush-alarm-checkpoints
export BRUSH_TRAINING_EXPORT_DIR=/data/brush-alarm-exports
export BRUSH_TRAINING_CACHE_DIR=/data/brush-alarm-cache
```

Precedence is command line, environment, explicit `--config`, local config,
experiment profile, then tracked defaults. Do not ignore the entire `configs/`
directory, because doing so would prevent other contributors from reproducing
the experiments.

## PyTorch environment

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

Install a matching PyTorch/TorchVision CPU or CUDA release pair for the local
machine. The repository does not bind contributors to one developer's virtual
environment or treat a CUDA 13.0 wheel as the default for everyone. For a
specific CUDA version, use PyTorch's official installation selector first, then
install the remaining requirements.

The S3D exporter creates `training/export/brush_classifier.onnx`. It removes
debug metadata that may contain absolute paths, checks PyTorch/ONNX Runtime
numerical parity, and rejects an artifact that still contains a machine-local
path. Copy the validated file to
`app/src/main/assets/brush_classifier.onnx`.

Downloads follow standard `HTTP_PROXY`, `HTTPS_PROXY`, and `NO_PROXY`. The
optional `TRAINING_PROXY` overrides only the data preparation script; no WSL
gateway or port is hard-coded.

## Isolated MoViNet A0 environment

TensorFlow Model Garden is intentionally isolated from the PyTorch environment:

```bash
python -m venv .venv-movinet
source .venv-movinet/bin/activate
python -m pip install -r training/requirements-movinet.txt
python -m training.validate_movinet_a0
```

For NVIDIA GPU execution on WSL2/Linux:

```bash
python -m pip install -r training/requirements-movinet-gpu.txt
```

`validate_movinet_a0.py` proves weight restore, one real-video training step,
explicit stream state, native TFLite conversion, and numerical parity.
`train_movinet_a0.py` uses the complete fixed split, selects thresholds by
source video, and evaluates every overlapping window:

```bash
python -m training.train_movinet_a0
```

See [MOVINET_A0.md](MOVINET_A0.md) for measured results and limitations.

Training data, video, logs, checkpoints, and exported models are ignored and
must not be committed. Publish an approved model only as a separate Release
asset with a recorded SHA-256. Model provenance, metrics, and limitations are
defined in [the English model card](../docs/MODEL_CARD.md). Volunteer video
must not be submitted through a public Issue or pull request; see the
[data contribution notice](../docs/DATA_CONTRIBUTION.md).

The experimental MobileNetV3 comparison is documented in
[LIGHTWEIGHT_MODEL.md](LIGHTWEIGHT_MODEL.md). It has not replaced the
production S3D.

# Brushing Video Classifier

English | [简体中文](README.md)

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
The released S3D starts from Kinetics-400 pretrained weights and fine-tunes on
UCF101 plus limited phone-domain adaptation.

On the fixed group 19+ validation split, counting each source video's median
window score once, the current S3D reaches 97.47% accuracy, 95.00% precision,
92.68% recall, and 93.83% F1. Overlapping windows are correlated and are not
misrepresented as independent test examples.

The app uses a 0.65 high threshold, 0.10 low threshold, and six seconds of
accumulated evidence. UCF101 only provides a base experiment; real release
quality requires contributor-isolated phone-camera positives, stopped actions,
and confusing mouth-area negatives.

## Layout

- `configs/default.toml`: public paths, split, and loader defaults
- `configs/s3d.toml`: production S3D experiment
- `configs/lightweight.toml`: MobileNetV3 streaming experiment
- `configs/movinet_a0.toml`: official MoViNet A0 Stream experiment
- `config.example.toml`: machine-local path override template
- `config.py`: TOML merging, environment override, and path resolution
- `prepare_ucf101.py`: extract selected classes from UCF101 mirror shards
- `train.py`, `finetune_feedback.py`, `export_onnx.py`: S3D pipeline
- `train_lightweight.py`, `export_lightweight_onnx.py`: distilled MobileNetV3
- `validate_movinet_a0.py`: one-step A0 pipeline/export validation
- `train_movinet_a0.py`: complete A0 head training, video evaluation, and export
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
experiment profile, then tracked defaults.

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

Install a matching PyTorch/TorchVision CPU or CUDA pair for the local machine.
The repository does not bind contributors to one developer's CUDA wheel.

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
`train_movinet_a0.py` performs the complete fixed-split experiment:

```bash
python -m training.train_movinet_a0
```

See [MOVINET_A0_EN.md](MOVINET_A0_EN.md) for measured results and limitations.

Data, video, logs, checkpoints, and exported models are ignored and must not be
committed. Publish an approved model only as a separate Release asset with a
SHA-256. See [the English model card](../docs/en/MODEL_CARD.md) and
[data contribution notice](../docs/en/DATA_CONTRIBUTION.md).

The experimental MobileNetV3 comparison is documented in
[LIGHTWEIGHT_MODEL_EN.md](LIGHTWEIGHT_MODEL_EN.md). It has not replaced the
production S3D.

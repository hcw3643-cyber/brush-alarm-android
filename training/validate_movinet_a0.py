# SPDX-FileCopyrightText: 2026 Leo Huang
# SPDX-License-Identifier: GPL-3.0-only

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import tarfile
import time
import urllib.request
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "1")

import cv2
import numpy as np
import tensorflow as tf
import tf_keras
from ai_edge_litert.interpreter import Interpreter
from official.projects.movinet.modeling import movinet, movinet_model
from official.projects.movinet.tools import export_saved_model

from training.config import load_config, repository_relative


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def resolve_video_path(data_dir: Path, value: str) -> Path:
    path = Path(value)
    if path.is_absolute():
        return path
    current = data_dir / path
    legacy = data_dir.parent / path
    return current if current.exists() else legacy


def load_clip(
    path: Path,
    frames: int,
    sample_fps: float,
    size: int,
    fallback_video_fps: float,
) -> np.ndarray:
    """Decode one deterministic center clip in MoViNet's [0,1] NTHWC format."""

    capture = cv2.VideoCapture(str(path))
    frame_count = max(int(capture.get(cv2.CAP_PROP_FRAME_COUNT)), frames)
    source_fps = float(capture.get(cv2.CAP_PROP_FPS))
    if source_fps <= 0:
        source_fps = fallback_video_fps
    last_offset = (frames - 1) * source_fps / sample_fps
    start = max(0.0, (frame_count - 1 - last_offset) / 2)
    positions = np.rint(
        start + np.arange(frames, dtype=np.float32) * source_fps / sample_fps
    ).astype(int)
    positions = np.clip(positions, 0, frame_count - 1)

    output: list[np.ndarray] = []
    for position in positions:
        capture.set(cv2.CAP_PROP_POS_FRAMES, int(position))
        ok, frame = capture.read()
        if not ok:
            break
        frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        height, width = frame.shape[:2]
        side = min(height, width)
        top = (height - side) // 2
        left = (width - side) // 2
        frame = frame[top : top + side, left : left + side]
        output.append(cv2.resize(frame, (size, size), interpolation=cv2.INTER_LINEAR))
    capture.release()
    if not output:
        raise RuntimeError(f"Cannot decode validation video: {path.name}")
    while len(output) < frames:
        output.append(output[-1].copy())
    return np.asarray(output, dtype=np.float32) / 255.0


def prepare_checkpoint(
    cache_dir: Path,
    url: str,
    directory_name: str,
) -> tuple[Path, Path]:
    cache_dir.mkdir(parents=True, exist_ok=True)
    archive = cache_dir / "movinet_a0_stream.tar"
    checkpoint_dir = cache_dir / directory_name
    if not archive.exists():
        urllib.request.urlretrieve(url, archive)
    if not checkpoint_dir.exists():
        with tarfile.open(archive, mode="r:*") as bundle:
            bundle.extractall(cache_dir, filter="data")
    checkpoint = tf.train.latest_checkpoint(str(checkpoint_dir))
    if not checkpoint:
        raise RuntimeError(f"No TensorFlow checkpoint found in {checkpoint_dir.name}")
    return archive, Path(checkpoint)


def model_options(experiment: dict) -> dict:
    return {
        "model_id": str(experiment["model_id"]),
        "causal": True,
        "conv_type": str(experiment["conv_type"]),
        "se_type": str(experiment["se_type"]),
        "activation": str(experiment["activation"]),
        "gating_activation": str(experiment["gating_activation"]),
        "use_sync_bn": False,
    }


def build_training_model(
    experiment: dict,
    frames: int,
    size: int,
    checkpoint_path: Path,
):
    """Restore streaming pretrained weights into a full-clip causal training graph."""

    backbone = movinet.Movinet(
        **model_options(experiment),
        use_external_states=False,
        output_states=True,
    )
    source_model = movinet_model.MovinetClassifier(
        backbone=backbone,
        num_classes=int(experiment["pretrained_classes"]),
        output_states=False,
        activation=str(experiment["activation"]),
    )
    source_model.build([1, frames, size, size, 3])
    status = tf.train.Checkpoint(model=source_model).restore(str(checkpoint_path))
    status.assert_existing_objects_matched()
    status.expect_partial()
    return backbone, source_model


def build_streaming_deployment_model(
    experiment: dict,
    size: int,
    checkpoint_path: Path,
    batch_size: int | None = 1,
):
    """Create the native-TFLite 2+1D graph with explicit stream-buffer states."""

    input_specs = tf_keras.layers.InputSpec(shape=[batch_size, 1, size, size, 3])
    backbone = movinet.Movinet(
        **model_options(experiment),
        input_specs=input_specs,
        use_external_states=True,
        output_states=True,
    )
    model = movinet_model.MovinetClassifier(
        backbone=backbone,
        num_classes=1,
        output_states=True,
        input_specs={"image": input_specs},
        activation=str(experiment["activation"]),
    )
    model.build([batch_size, 1, size, size, 3])
    status = tf.train.Checkpoint(model=model).restore(str(checkpoint_path))
    status.assert_existing_objects_matched()
    status.expect_partial()
    return model


def convert_tflite(model, size: int, output_dir: Path) -> Path:
    saved_model_dir = output_dir / "saved_model_stream"
    export_saved_model.export_saved_model(
        model=model,
        input_shape=(1, 1, size, size, 3),
        export_path=str(saved_model_dir),
        causal=True,
        bundle_input_init_states_fn=False,
    )
    converter = tf.lite.TFLiteConverter.from_saved_model(str(saved_model_dir))
    payload = converter.convert()
    output = output_dir / "brush_classifier_movinet_a0_stream.tflite"
    output.write_bytes(payload)
    return output


def initial_tflite_states(interpreter) -> dict[str, np.ndarray]:
    def state_name(tensor_name: str) -> str:
        prefix = "serving_default_"
        suffix = ":0"
        if not tensor_name.startswith(prefix) or not tensor_name.endswith(suffix):
            raise RuntimeError(f"Unexpected TFLite state tensor: {tensor_name}")
        return tensor_name[len(prefix) : -len(suffix)]

    states = {
        state_name(detail["name"]): np.zeros(detail["shape"], dtype=detail["dtype"])
        for detail in interpreter.get_input_details()
    }
    states.pop("image")
    return states


def run_stream(runner, initial_states: dict, clip: np.ndarray) -> np.ndarray:
    states = {name: value.copy() for name, value in initial_states.items()}
    logits = None
    for frame in np.split(clip, clip.shape[1], axis=1):
        outputs = runner(**states, image=frame)
        logits = outputs.pop("logits")
        states = outputs
    if logits is None:
        raise RuntimeError("Streaming model produced no logits")
    return logits


def benchmark_tflite(
    path: Path,
    sample: np.ndarray,
    iterations: int,
) -> tuple[dict, np.ndarray]:
    interpreter = Interpreter(model_path=str(path), num_threads=2)
    interpreter.allocate_tensors()
    runner = interpreter.get_signature_runner("serving_default")
    states = initial_tflite_states(interpreter)
    for _ in range(2):
        run_stream(runner, states, sample)

    sequence_elapsed: list[float] = []
    runtime_output = None
    for _ in range(iterations):
        start = time.perf_counter()
        runtime_output = run_stream(runner, states, sample)
        sequence_elapsed.append((time.perf_counter() - start) * 1000)
    assert runtime_output is not None
    per_frame = np.asarray(sequence_elapsed) / sample.shape[1]
    return (
        {
            "image_input_shape": [1, 1, sample.shape[2], sample.shape[3], 3],
            "state_tensor_count": len(states),
            "sequence_frames": int(sample.shape[1]),
            "sequence_latency_ms_median": float(np.median(sequence_elapsed)),
            "per_frame_latency_ms_median": float(np.median(per_frame)),
            "per_frame_latency_ms_p90": float(np.percentile(per_frame, 90)),
        },
        runtime_output,
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Validate official streaming MoViNet A0 training and TFLite export."
    )
    parser.add_argument("--config", type=Path)
    parser.add_argument("--benchmark-iterations", type=int, default=10)
    args = parser.parse_args()

    try:
        tf.config.set_visible_devices([], "GPU")
    except RuntimeError:
        pass

    config = load_config("movinet_a0", args.config)
    experiment = config.section("experiment")
    dataset = config.section("dataset")
    frames = int(experiment["frames"])
    size = int(experiment["size"])
    sample_fps = float(experiment["sample_fps"])
    seed = int(experiment["seed"])
    np.random.seed(seed)
    tf.random.set_seed(seed)
    manifest = json.loads(
        (config.paths.data_dir / str(dataset["manifest"])).read_text(encoding="utf-8")
    )
    positive = next(row for row in manifest if int(row["label"]) == 1)
    negative = next(row for row in manifest if int(row["label"]) == 0)
    clips = np.stack(
        [
            load_clip(
                resolve_video_path(config.paths.data_dir, row["path"]),
                frames,
                sample_fps,
                size,
                float(dataset["fallback_video_fps"]),
            )
            for row in (negative, positive)
        ]
    )
    labels = np.asarray([0.0, 1.0], dtype=np.float32)

    cache_dir = config.paths.cache_dir / "movinet-a0-stream"
    archive, checkpoint_path = prepare_checkpoint(
        cache_dir,
        str(experiment["checkpoint_url"]),
        str(experiment["checkpoint_directory"]),
    )
    backbone, source_model = build_training_model(
        experiment, frames, size, checkpoint_path
    )
    pretrained_output = source_model(clips[:1], training=False).numpy()
    if pretrained_output.shape != (1, int(experiment["pretrained_classes"])):
        raise RuntimeError(f"Unexpected pretrained output: {pretrained_output.shape}")

    backbone.trainable = False
    classifier = movinet_model.MovinetClassifier(
        backbone=backbone,
        num_classes=1,
        output_states=False,
        activation=str(experiment["activation"]),
    )
    classifier.build([None, frames, size, size, 3])
    optimizer = tf_keras.optimizers.Adam(learning_rate=1e-3)
    loss_function = tf_keras.losses.BinaryCrossentropy(from_logits=True)
    before = classifier(clips, training=False).numpy()
    with tf.GradientTape() as tape:
        logits = tf.reshape(classifier(clips, training=True), [-1])
        loss = loss_function(labels, logits)
    gradients = tape.gradient(loss, classifier.trainable_variables)
    gradient_pairs = [
        (gradient, variable)
        for gradient, variable in zip(gradients, classifier.trainable_variables)
        if gradient is not None
    ]
    optimizer.apply_gradients(gradient_pairs)
    after = classifier(clips, training=False).numpy()
    if not np.isfinite(after).all() or not np.isfinite(float(loss)):
        raise RuntimeError("MoViNet A0 produced a non-finite training result")

    transfer_checkpoint = Path(
        tf.train.Checkpoint(model=classifier).save(
            str(cache_dir / "movinet_a0_binary_transfer")
        )
    )
    deployment_model = build_streaming_deployment_model(
        experiment, size, transfer_checkpoint
    )
    output_dir = config.paths.export_dir / "movinet-a0"
    output_dir.mkdir(parents=True, exist_ok=True)
    tflite_path = convert_tflite(deployment_model, size, output_dir)
    benchmark, runtime_output = benchmark_tflite(
        tflite_path, clips[:1], args.benchmark_iterations
    )
    source_output = classifier(clips[:1], training=False).numpy()
    max_abs_error = float(np.max(np.abs(source_output - runtime_output)))
    if max_abs_error > 1e-3:
        raise RuntimeError(f"TFLite parity failed: max_abs_error={max_abs_error}")

    result = {
        "status": "pipeline_validated_not_accuracy_validated",
        "framework": {
            "tensorflow": tf.__version__,
            "tf_models_official": "2.20.0",
            "python": platform.python_version(),
            "device": "CPU",
        },
        "model": {
            "id": str(experiment["model_id"]),
            "mode": "causal_stream",
            "conv_type": str(experiment["conv_type"]),
            "parameters": int(classifier.count_params()),
            "trainable_parameters": int(
                sum(
                    np.prod(variable.shape)
                    for variable in classifier.trainable_variables
                )
            ),
            "training_input": {
                "layout": "NTHWC",
                "shape": [2, frames, size, size, 3],
                "sample_fps": sample_fps,
                "range": "[0,1]",
            },
            "deployment_image_input": [1, 1, size, size, 3],
        },
        "checkpoint": {
            "archive": archive.name,
            "sha256": sha256(archive),
            "restored": checkpoint_path.name,
        },
        "validation": {
            "real_clips": 2,
            "pretrained_output_finite": bool(np.isfinite(pretrained_output).all()),
            "head_train_step_loss": float(loss),
            "head_changed": bool(not np.allclose(before, after)),
            "gradient_tensors": len(gradient_pairs),
        },
        "tflite": {
            "path": repository_relative(tflite_path),
            "bytes": tflite_path.stat().st_size,
            "sha256": sha256(tflite_path),
            "conversion_mode": "native_builtins",
            "max_abs_error": max_abs_error,
            **benchmark,
        },
        "limitations": [
            "One head-only training step validates the pipeline, not classification accuracy.",
            "CPU timing is measured in WSL x86 and is not an Android device benchmark.",
            "The App currently uses ONNX Runtime; this deployment path requires LiteRT/TFLite state integration.",
        ],
    }
    report_path = output_dir / str(experiment["validation_output"])
    report_path.write_text(
        json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(json.dumps(result, indent=2, ensure_ascii=False))
    print(f"report={repository_relative(report_path)}")


if __name__ == "__main__":
    main()

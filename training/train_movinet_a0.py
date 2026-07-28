# SPDX-FileCopyrightText: 2026 Leo Huang
# SPDX-License-Identifier: GPL-3.0-only

from __future__ import annotations

import argparse
import gc
import json
import math
import random
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import cv2
import numpy as np
import tensorflow as tf
import tf_keras
from official.projects.movinet.modeling import movinet_model

from training.config import load_config, repository_relative
from training.validate_movinet_a0 import (
    benchmark_tflite,
    build_streaming_deployment_model,
    build_training_model,
    convert_tflite,
    prepare_checkpoint,
    sha256,
)


def resolve_video_path(data_dir: Path, value: str) -> Path:
    path = Path(value)
    if path.is_absolute():
        return path
    current = data_dir / path
    legacy = data_dir.parent / path
    return current if current.exists() else legacy


def crop_resize_rgb(frame: np.ndarray, size: int) -> np.ndarray:
    frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    height, width = frame.shape[:2]
    side = min(height, width)
    top = (height - side) // 2
    left = (width - side) // 2
    frame = frame[top : top + side, left : left + side]
    return cv2.resize(frame, (size, size), interpolation=cv2.INTER_LINEAR)


def read_video_metadata(path: Path, fallback_video_fps: float) -> tuple[int, float]:
    capture = cv2.VideoCapture(str(path))
    count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
    fps = float(capture.get(cv2.CAP_PROP_FPS))
    capture.release()
    if count <= 0:
        raise RuntimeError(f"Cannot read video metadata: {path.name}")
    return count, fps if fps > 0 else fallback_video_fps


def sample_positions(
    count: int,
    source_fps: float,
    frames: int,
    sample_fps: float,
    start_seconds: float,
) -> np.ndarray:
    positions = np.rint(
        start_seconds * source_fps
        + np.arange(frames, dtype=np.float32) * source_fps / sample_fps
    ).astype(int)
    return np.clip(positions, 0, max(0, count - 1))


def read_selected_clip(
    path: Path,
    positions: np.ndarray,
    size: int,
) -> np.ndarray:
    capture = cv2.VideoCapture(str(path))
    source_index = int(positions[0])
    capture.set(cv2.CAP_PROP_POS_FRAMES, source_index)
    target_index = 0
    output: list[np.ndarray] = []
    while target_index < len(positions):
        ok, frame = capture.read()
        if not ok:
            break
        if source_index >= positions[target_index]:
            processed = crop_resize_rgb(frame, size)
            while (
                target_index < len(positions)
                and source_index >= positions[target_index]
            ):
                output.append(processed.copy())
                target_index += 1
        source_index += 1
    capture.release()
    if not output:
        raise RuntimeError(f"Cannot decode video: {path.name}")
    while len(output) < len(positions):
        output.append(output[-1].copy())
    return np.asarray(output, dtype=np.uint8)


def augment_clip(clip: np.ndarray, rng: random.Random) -> np.ndarray:
    if rng.random() < 0.5:
        clip = clip[:, :, ::-1].copy()

    if rng.random() < 0.25:
        kernel = np.zeros((3, 3), np.float32)
        if rng.random() < 0.5:
            kernel[1, :] = 1 / 3
        else:
            kernel[:, 1] = 1 / 3
        clip = np.stack([cv2.filter2D(frame, -1, kernel) for frame in clip])

    values = clip.astype(np.float32) / 255.0
    gamma = rng.uniform(0.65, 1.55)
    contrast = rng.uniform(0.70, 1.30)
    brightness = rng.uniform(-0.18, 0.18)
    channel_gain = np.asarray(
        [rng.uniform(0.88, 1.12) for _ in range(3)], np.float32
    ).reshape(1, 1, 1, 3)
    values = np.power(np.clip(values, 0, 1), gamma)
    values = (values - 0.5) * contrast + 0.5 + brightness
    values *= channel_gain
    if rng.random() < 0.5:
        noise_rng = np.random.default_rng(rng.randrange(2**32))
        sigma = rng.uniform(0.0, 0.025)
        values += noise_rng.normal(0.0, sigma, values.shape).astype(np.float32)
    return np.clip(values, 0, 1).astype(np.float32)


def load_training_clip(
    row: dict,
    data_dir: Path,
    frames: int,
    sample_fps: float,
    size: int,
    fallback_video_fps: float,
    seed: int,
) -> tuple[np.ndarray, float]:
    rng = random.Random(seed)
    path = resolve_video_path(data_dir, str(row["path"]))
    count, source_fps = read_video_metadata(path, fallback_video_fps)
    span = (frames - 1) / sample_fps
    duration = max(0.0, (count - 1) / source_fps)
    start_seconds = rng.uniform(0.0, max(0.0, duration - span))
    positions = sample_positions(count, source_fps, frames, sample_fps, start_seconds)
    return augment_clip(read_selected_clip(path, positions, size), rng), float(
        row["label"]
    )


def load_center_clip(
    row: dict,
    data_dir: Path,
    frames: int,
    sample_fps: float,
    size: int,
    fallback_video_fps: float,
) -> tuple[np.ndarray, float, str]:
    path = resolve_video_path(data_dir, str(row["path"]))
    count, source_fps = read_video_metadata(path, fallback_video_fps)
    span = (frames - 1) / sample_fps
    duration = max(0.0, (count - 1) / source_fps)
    start_seconds = max(0.0, (duration - span) / 2)
    positions = sample_positions(count, source_fps, frames, sample_fps, start_seconds)
    clip = read_selected_clip(path, positions, size).astype(np.float32) / 255.0
    return clip, float(row["label"]), str(row["path"])


def read_resized_video(
    path: Path,
    size: int,
    fallback_video_fps: float,
) -> tuple[np.ndarray, float]:
    capture = cv2.VideoCapture(str(path))
    fps = float(capture.get(cv2.CAP_PROP_FPS))
    fps = fps if fps > 0 else fallback_video_fps
    frames: list[np.ndarray] = []
    while True:
        ok, frame = capture.read()
        if not ok:
            break
        frames.append(crop_resize_rgb(frame, size))
    capture.release()
    if not frames:
        raise RuntimeError(f"Cannot decode video: {path.name}")
    return np.asarray(frames, dtype=np.uint8), fps


def validation_starts(
    count: int,
    source_fps: float,
    frames: int,
    sample_fps: float,
    step_seconds: float,
) -> list[float]:
    duration = max(0.0, (count - 1) / source_fps)
    span = (frames - 1) / sample_fps
    max_start = max(0.0, duration - span)
    starts = list(np.arange(0.0, max_start + 1e-6, step_seconds))
    if not starts or max_start - starts[-1] > 0.2:
        starts.append(max_start)
    return [float(start) for start in starts]


def binary_metrics(
    outputs: list[float],
    targets: list[float],
    threshold: float,
) -> dict[str, float]:
    predicted = [value >= threshold for value in outputs]
    tp = sum(
        prediction and target == 1 for prediction, target in zip(predicted, targets)
    )
    fp = sum(
        prediction and target == 0 for prediction, target in zip(predicted, targets)
    )
    fn = sum(
        (not prediction) and target == 1
        for prediction, target in zip(predicted, targets)
    )
    correct = sum(
        prediction == bool(target) for prediction, target in zip(predicted, targets)
    )
    return {
        "accuracy": correct / max(1, len(targets)),
        "precision": tp / max(1, tp + fp),
        "recall": tp / max(1, tp + fn),
        "f1": 2 * tp / max(1, 2 * tp + fp + fn),
    }


def select_threshold(outputs: list[float], targets: list[float]) -> tuple[float, dict]:
    best: tuple[float, float, float, float] | None = None
    best_metrics: dict[str, float] | None = None
    for threshold in np.arange(0.05, 0.951, 0.01):
        metrics = binary_metrics(outputs, targets, float(threshold))
        candidate = (
            metrics["f1"],
            metrics["precision"],
            metrics["recall"],
            float(threshold),
        )
        if best is None or candidate > best:
            best = candidate
            best_metrics = metrics
    assert best is not None and best_metrics is not None
    return best[3], best_metrics


def streaming_logits(classifier, clips: np.ndarray) -> np.ndarray:
    states = classifier.init_states(tf.shape(clips[:, 0:1]))
    logits = None
    for frame in np.split(clips, clips.shape[1], axis=1):
        logits, states = classifier(
            {**states, "image": frame},
            training=False,
        )
    if logits is None:
        raise RuntimeError("Streaming model produced no logits")
    return np.asarray(logits).reshape(-1)


def make_streaming_predictor(classifier, frames: int, size: int):
    @tf.function(
        input_signature=[
            tf.TensorSpec(
                shape=[None, frames, size, size, 3],
                dtype=tf.float32,
            )
        ]
    )
    def predict(clips):
        states = classifier.init_states(tf.shape(clips[:, 0:1]))
        logits = None
        for frame_index in range(frames):
            logits, states = classifier(
                {**states, "image": clips[:, frame_index : frame_index + 1]},
                training=False,
            )
        return tf.reshape(logits, [-1])

    return predict


def evaluate_center_clips(
    classifier,
    rows: list[dict],
    data_dir: Path,
    frames: int,
    sample_fps: float,
    size: int,
    fallback_video_fps: float,
    batch_size: int,
    workers: int,
) -> dict:
    outputs: list[float] = []
    targets: list[float] = []
    with ThreadPoolExecutor(max_workers=workers) as executor:
        for offset in range(0, len(rows), batch_size):
            batch_rows = rows[offset : offset + batch_size]
            loaded = list(
                executor.map(
                    lambda row: load_center_clip(
                        row,
                        data_dir,
                        frames,
                        sample_fps,
                        size,
                        fallback_video_fps,
                    ),
                    batch_rows,
                )
            )
            clips = np.stack([item[0] for item in loaded])
            logits = np.asarray(classifier(clips, training=False)).reshape(-1)
            outputs.extend(tf.math.sigmoid(logits).numpy().tolist())
            targets.extend(item[1] for item in loaded)
    threshold, metrics = select_threshold(outputs, targets)
    return {
        **metrics,
        "threshold": threshold,
        "videos": len(rows),
        "at_0_65": binary_metrics(outputs, targets, 0.65),
        "at_0_70": binary_metrics(outputs, targets, 0.70),
    }


def evaluate_sliding_windows(
    classifier,
    streaming_predictor,
    rows: list[dict],
    data_dir: Path,
    frames: int,
    sample_fps: float,
    size: int,
    fallback_video_fps: float,
    step_seconds: float,
    batch_size: int,
) -> tuple[dict, dict, np.ndarray]:
    video_outputs: list[float] = []
    video_targets: list[float] = []
    window_outputs: list[float] = []
    window_targets: list[float] = []
    streaming_video_outputs: list[float] = []
    streaming_window_outputs: list[float] = []
    parity_clip: np.ndarray | None = None

    for index, row in enumerate(rows):
        path = resolve_video_path(data_dir, str(row["path"]))
        decoded, source_fps = read_resized_video(path, size, fallback_video_fps)
        starts = validation_starts(
            len(decoded), source_fps, frames, sample_fps, step_seconds
        )
        clips = (
            np.stack(
                [
                    decoded[
                        sample_positions(
                            len(decoded),
                            source_fps,
                            frames,
                            sample_fps,
                            start,
                        )
                    ]
                    for start in starts
                ]
            ).astype(np.float32)
            / 255.0
        )
        if parity_clip is None:
            parity_clip = clips[:1]
        probabilities: list[float] = []
        streaming_probabilities: list[float] = []
        for offset in range(0, len(clips), batch_size):
            batch = clips[offset : offset + batch_size]
            logits = np.asarray(classifier(batch, training=False)).reshape(-1)
            probabilities.extend(tf.math.sigmoid(logits).numpy().tolist())
            stream_batch_logits = np.asarray(streaming_predictor(batch)).reshape(-1)
            streaming_probabilities.extend(
                tf.math.sigmoid(stream_batch_logits).numpy().tolist()
            )
        target = float(row["label"])
        video_outputs.append(float(np.median(probabilities)))
        streaming_video_outputs.append(float(np.median(streaming_probabilities)))
        video_targets.append(target)
        window_outputs.extend(probabilities)
        streaming_window_outputs.extend(streaming_probabilities)
        window_targets.extend([target] * len(probabilities))
        if (index + 1) % 20 == 0 or index + 1 == len(rows):
            print(f"sliding_validation={index + 1}/{len(rows)}", flush=True)

    threshold, metrics = select_threshold(video_outputs, video_targets)
    streaming_threshold, streaming_metrics = select_threshold(
        streaming_video_outputs, video_targets
    )
    assert parity_clip is not None
    return (
        {
            **metrics,
            "threshold": threshold,
            "videos": len(rows),
            "windows": len(window_outputs),
            "window_metrics": binary_metrics(window_outputs, window_targets, threshold),
            "at_0_65": binary_metrics(video_outputs, video_targets, 0.65),
            "at_0_70": binary_metrics(video_outputs, video_targets, 0.70),
        },
        {
            **streaming_metrics,
            "threshold": streaming_threshold,
            "videos": len(rows),
            "windows": len(streaming_window_outputs),
            "window_metrics": binary_metrics(
                streaming_window_outputs,
                window_targets,
                streaming_threshold,
            ),
            "at_0_65": binary_metrics(
                streaming_video_outputs,
                video_targets,
                0.65,
            ),
            "at_0_70": binary_metrics(
                streaming_video_outputs,
                video_targets,
                0.70,
            ),
        },
        parity_clip,
    )


def balanced_epoch_rows(rows: list[dict], rng: random.Random) -> list[dict]:
    positives = [row for row in rows if int(row["label"]) == 1]
    negatives = [row for row in rows if int(row["label"]) == 0]
    half = math.ceil(len(rows) / 2)
    sampled = [rng.choice(positives) for _ in range(half)]
    sampled.extend(rng.choice(negatives) for _ in range(len(rows) - half))
    rng.shuffle(sampled)
    return sampled


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Train and evaluate the causal MoViNet A0 brush classifier."
    )
    parser.add_argument("--config", type=Path)
    parser.add_argument("--epochs", type=int)
    parser.add_argument("--batch-size", type=int)
    parser.add_argument("--workers", type=int)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--max-train-videos", type=int)
    parser.add_argument("--max-validation-videos", type=int)
    parser.add_argument("--skip-export", action="store_true")
    args = parser.parse_args()

    config = load_config("movinet_a0", args.config)
    experiment = config.section("experiment")
    dataset = config.section("dataset")
    frames = int(experiment["frames"])
    size = int(experiment["size"])
    sample_fps = float(experiment["sample_fps"])
    seed = int(experiment["seed"])
    epochs = args.epochs if args.epochs is not None else int(experiment["epochs"])
    batch_size = (
        args.batch_size
        if args.batch_size is not None
        else int(experiment["batch_size"])
    )
    workers = args.workers if args.workers is not None else int(experiment["workers"])
    fallback_video_fps = float(dataset["fallback_video_fps"])
    validation_step = float(dataset["validation_step_seconds"])
    rng = random.Random(seed)
    np.random.seed(seed)
    tf.random.set_seed(seed)

    gpus = tf.config.list_physical_devices("GPU")
    for gpu in gpus:
        tf.config.experimental.set_memory_growth(gpu, True)
    device = "GPU" if gpus else "CPU"

    rows = json.loads(
        (config.paths.data_dir / str(dataset["manifest"])).read_text(encoding="utf-8")
    )
    train_rows = [
        row for row in rows if int(row["group"]) <= int(dataset["train_group_max"])
    ]
    validation_rows = [
        row for row in rows if int(row["group"]) > int(dataset["train_group_max"])
    ]
    if args.max_train_videos:
        train_rows = train_rows[: args.max_train_videos]
    if args.max_validation_videos:
        validation_rows = validation_rows[: args.max_validation_videos]
    if not train_rows or not validation_rows:
        raise RuntimeError("Training or validation split is empty")
    if len({int(row["label"]) for row in train_rows}) != 2:
        raise RuntimeError("Training split must contain both labels")

    cache_dir = config.paths.cache_dir / "movinet-a0-stream"
    archive, checkpoint_path = prepare_checkpoint(
        cache_dir,
        str(experiment["checkpoint_url"]),
        str(experiment["checkpoint_directory"]),
    )
    backbone, source_model = build_training_model(
        experiment, frames, size, checkpoint_path
    )
    backbone.trainable = False
    classifier = movinet_model.MovinetClassifier(
        backbone=backbone,
        num_classes=1,
        output_states=False,
        activation=str(experiment["activation"]),
    )
    classifier.build([None, frames, size, size, 3])
    del source_model
    gc.collect()

    optimizer = tf_keras.optimizers.Adam(
        learning_rate=float(experiment["learning_rate"])
    )
    loss_function = tf_keras.losses.BinaryCrossentropy(from_logits=True)
    epoch_variable = tf.Variable(0, trainable=False, dtype=tf.int64)
    best_f1_variable = tf.Variable(-1.0, trainable=False, dtype=tf.float32)
    state = tf.train.Checkpoint(
        model=classifier,
        optimizer=optimizer,
        epoch=epoch_variable,
        best_f1=best_f1_variable,
    )
    checkpoint_root = config.paths.checkpoint_dir / str(
        experiment["checkpoint_directory_name"]
    )
    latest_manager = tf.train.CheckpointManager(
        state, str(checkpoint_root / "latest"), max_to_keep=2
    )
    best_state = tf.train.Checkpoint(model=classifier)
    best_manager = tf.train.CheckpointManager(
        best_state, str(checkpoint_root / "best"), max_to_keep=1
    )
    if args.resume and latest_manager.latest_checkpoint:
        state.restore(latest_manager.latest_checkpoint).expect_partial()

    @tf.function(reduce_retracing=True)
    def train_step(images, labels):
        with tf.GradientTape() as tape:
            logits = tf.reshape(classifier(images, training=True), [-1])
            loss = loss_function(labels, logits)
        gradients = tape.gradient(loss, classifier.trainable_variables)
        pairs = [
            (gradient, variable)
            for gradient, variable in zip(gradients, classifier.trainable_variables)
            if gradient is not None
        ]
        optimizer.apply_gradients(pairs)
        return loss, len(pairs)

    start_epoch = int(epoch_variable.numpy())
    stale_epochs = 0
    history: list[dict] = []
    print(
        f"device={device} train_videos={len(train_rows)} "
        f"validation_videos={len(validation_rows)} batch_size={batch_size} "
        f"start_epoch={start_epoch} target_epochs={epochs}",
        flush=True,
    )
    with ThreadPoolExecutor(max_workers=workers) as executor:
        for epoch in range(start_epoch, epochs):
            epoch_rows = balanced_epoch_rows(train_rows, rng)
            losses: list[float] = []
            gradient_tensors = 0
            started = time.perf_counter()
            for offset in range(0, len(epoch_rows), batch_size):
                batch_rows = epoch_rows[offset : offset + batch_size]
                seeds = [rng.randrange(2**32) for _ in batch_rows]
                loaded = list(
                    executor.map(
                        lambda item: load_training_clip(
                            item[0],
                            config.paths.data_dir,
                            frames,
                            sample_fps,
                            size,
                            fallback_video_fps,
                            item[1],
                        ),
                        zip(batch_rows, seeds),
                    )
                )
                images = np.stack([item[0] for item in loaded])
                labels = np.asarray([item[1] for item in loaded], dtype=np.float32)
                loss, gradient_tensors = train_step(images, labels)
                losses.append(float(loss))
                if (offset // batch_size + 1) % 40 == 0:
                    print(
                        f"epoch={epoch + 1} batch={offset // batch_size + 1}/"
                        f"{math.ceil(len(epoch_rows) / batch_size)} "
                        f"loss={np.mean(losses[-40:]):.4f}",
                        flush=True,
                    )

            center_metrics = evaluate_center_clips(
                classifier,
                validation_rows,
                config.paths.data_dir,
                frames,
                sample_fps,
                size,
                fallback_video_fps,
                max(batch_size, 4),
                workers,
            )
            epoch_result = {
                "epoch": epoch + 1,
                "loss": float(np.mean(losses)),
                "gradient_tensors": int(gradient_tensors),
                "elapsed_seconds": time.perf_counter() - started,
                "center_validation": center_metrics,
            }
            history.append(epoch_result)
            epoch_variable.assign(epoch + 1)
            improved = center_metrics["f1"] > float(best_f1_variable.numpy())
            if improved:
                best_f1_variable.assign(center_metrics["f1"])
                best_manager.save(checkpoint_number=epoch + 1)
                stale_epochs = 0
            else:
                stale_epochs += 1
            latest_manager.save(checkpoint_number=epoch + 1)
            print(json.dumps(epoch_result, ensure_ascii=False), flush=True)
            if stale_epochs >= int(experiment["early_stop_after"]):
                print("early_stop", flush=True)
                break

    if not best_manager.latest_checkpoint:
        raise RuntimeError("Training did not produce a best checkpoint")
    best_state.restore(best_manager.latest_checkpoint).assert_existing_objects_matched()

    transfer_checkpoint = Path(
        tf.train.Checkpoint(model=classifier).save(
            str(checkpoint_root / "movinet_a0_binary")
        )
    )
    streaming_evaluation_model = build_streaming_deployment_model(
        experiment,
        size,
        transfer_checkpoint,
        batch_size=None,
    )
    streaming_predictor = make_streaming_predictor(
        streaming_evaluation_model,
        frames,
        size,
    )
    evaluation_cache = checkpoint_root / "sliding-evaluation.json"
    parity_cache = checkpoint_root / "sliding-parity-clip.npy"
    cached_evaluation = None
    if args.resume and evaluation_cache.exists() and parity_cache.exists():
        candidate = json.loads(evaluation_cache.read_text(encoding="utf-8"))
        if candidate.get("best_checkpoint") == Path(
            best_manager.latest_checkpoint
        ).name and candidate.get("validation_videos") == len(validation_rows):
            cached_evaluation = candidate
    if cached_evaluation:
        source_sliding_metrics = cached_evaluation["source"]
        streaming_sliding_metrics = cached_evaluation["streaming"]
        parity_clip = np.load(parity_cache)
        print("loaded_sliding_evaluation_cache", flush=True)
    else:
        source_sliding_metrics, streaming_sliding_metrics, parity_clip = (
            evaluate_sliding_windows(
                classifier,
                streaming_predictor,
                validation_rows,
                config.paths.data_dir,
                frames,
                sample_fps,
                size,
                fallback_video_fps,
                validation_step,
                max(batch_size, 8),
            )
        )
        evaluation_cache.parent.mkdir(parents=True, exist_ok=True)
        evaluation_cache.write_text(
            json.dumps(
                {
                    "best_checkpoint": Path(best_manager.latest_checkpoint).name,
                    "validation_videos": len(validation_rows),
                    "source": source_sliding_metrics,
                    "streaming": streaming_sliding_metrics,
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        np.save(parity_cache, parity_clip)

    tflite_result = None
    if not args.skip_export:
        deployment_model = build_streaming_deployment_model(
            experiment, size, transfer_checkpoint
        )
        output_dir = config.paths.export_dir / str(experiment["export_directory"])
        output_dir.mkdir(parents=True, exist_ok=True)
        tflite_path = convert_tflite(deployment_model, size, output_dir)
        benchmark, runtime_output = benchmark_tflite(
            tflite_path, parity_clip, iterations=10
        )
        source_output = np.asarray(classifier(parity_clip, training=False)).reshape(-1)
        deployment_output = streaming_logits(deployment_model, parity_clip)
        max_abs_error = float(np.max(np.abs(deployment_output - runtime_output)))
        deployment_probability = tf.math.sigmoid(deployment_output).numpy()
        runtime_probability = tf.math.sigmoid(runtime_output).numpy()
        probability_abs_error = float(
            np.max(np.abs(deployment_probability - runtime_probability))
        )
        if max_abs_error > 2e-3 or probability_abs_error > 1e-5:
            raise RuntimeError(
                "TFLite parity failed: "
                f"logit_max_abs_error={max_abs_error} "
                f"probability_max_abs_error={probability_abs_error}"
            )
        source_stream_error = float(np.max(np.abs(source_output - deployment_output)))
        source_probability = tf.math.sigmoid(source_output).numpy()
        tflite_result = {
            "path": repository_relative(tflite_path),
            "bytes": tflite_path.stat().st_size,
            "sha256": sha256(tflite_path),
            "max_abs_error": max_abs_error,
            "probability_max_abs_error": probability_abs_error,
            "source_graph_vs_stream_logit_max_abs_error": source_stream_error,
            "source_graph_vs_stream_probability_max_abs_error": float(
                np.max(np.abs(source_probability - deployment_probability))
            ),
            "conversion_mode": "native_builtins",
            **benchmark,
        }

    limited = bool(args.max_train_videos or args.max_validation_videos)
    report = {
        "status": (
            "limited_pipeline_test"
            if limited
            else "ucf_validation_complete_no_phone_domain_validation"
        ),
        "device": device,
        "framework": {
            "tensorflow": tf.__version__,
            "tf_models_official": "2.20.0",
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
            "training_scope": "binary_head_only_frozen_pretrained_backbone",
        },
        "input": {
            "layout": "NTHWC",
            "shape": [None, frames, size, size, 3],
            "sample_fps": sample_fps,
            "window_span_seconds": (frames - 1) / sample_fps,
            "range": "[0,1]",
        },
        "dataset": {
            "train_videos": len(train_rows),
            "validation_videos": len(validation_rows),
            "train_group_max": int(dataset["train_group_max"]),
            "validation_step_seconds": validation_step,
        },
        "checkpoint": {
            "pretrained_archive_sha256": sha256(archive),
            "best_epoch_checkpoint": Path(best_manager.latest_checkpoint).name,
        },
        "history": history,
        "sliding_validation": streaming_sliding_metrics,
        "training_graph_sliding_validation": source_sliding_metrics,
        "tflite": tflite_result,
        "limitations": [
            "Threshold and checkpoint selection use the same fixed UCF validation split; this is not a held-out test set.",
            "UCF action videos do not represent phone front-camera brushing conditions.",
            "No contributor-isolated multi-user validation or Android device benchmark has been completed.",
            "The production app still uses S3D/ONNX Runtime until a separate integration is implemented and tested.",
        ],
    }
    report_dir = config.paths.export_dir / str(experiment["export_directory"])
    report_dir.mkdir(parents=True, exist_ok=True)
    report_path = report_dir / str(experiment["training_report"])
    report_path.write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(json.dumps(report, indent=2, ensure_ascii=False))
    print(f"report={repository_relative(report_path)}")


if __name__ == "__main__":
    main()

from __future__ import annotations

import json
import random
import re
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler

from model import FRAMES, SAMPLE_FPS, SIZE, BrushVideoClassifier

ROOT = Path(__file__).resolve().parent
MEAN = np.asarray([0.43216, 0.394666, 0.37645], np.float32).reshape(1, 1, 1, 3)
STD = np.asarray([0.22803, 0.22145, 0.216989], np.float32).reshape(1, 1, 1, 3)
WINDOW_SPAN_SECONDS = (FRAMES - 1) / SAMPLE_FPS
VALIDATION_STEP_SECONDS = 0.5
random.seed(42)
np.random.seed(42)
torch.manual_seed(42)


class Clips(Dataset):
    """Fixed-time clips matching Android: 16 RGB frames sampled at 8 fps."""

    def __init__(self, rows: list[dict], training: bool) -> None:
        self.rows = rows
        self.training = training

    def __len__(self) -> int:
        return len(self.rows)

    def __getitem__(self, index: int):
        row = self.rows[index]
        cap = cv2.VideoCapture(str(ROOT / row["path"]))
        count = max(int(cap.get(cv2.CAP_PROP_FRAME_COUNT)), FRAMES)
        fps = cap.get(cv2.CAP_PROP_FPS)
        if fps <= 0:
            fps = 25.0
        last_offset = (FRAMES - 1) * fps / SAMPLE_FPS
        max_start = max(0.0, count - 1 - last_offset)
        if self.training and "start_seconds" not in row:
            start = random.uniform(0.0, max_start)
        else:
            start = min(max_start, float(row["start_seconds"]) * fps)
        positions = np.rint(
            start + np.arange(FRAMES, dtype=np.float32) * fps / SAMPLE_FPS
        ).astype(int)
        positions = np.clip(positions, 0, count - 1)

        frames: list[np.ndarray] = []
        target_index = 0
        # Seek to the first requested frame instead of decoding the entire
        # prefix for every random/overlapping window.
        source_index = int(positions[0])
        cap.set(cv2.CAP_PROP_POS_FRAMES, source_index)
        while target_index < len(positions):
            ok, frame = cap.read()
            if not ok:
                break
            if source_index >= positions[target_index]:
                frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                height, width = frame.shape[:2]
                side = min(height, width)
                top = (height - side) // 2
                left = (width - side) // 2
                frame = frame[top:top + side, left:left + side]
                frame = cv2.resize(frame, (SIZE, SIZE), interpolation=cv2.INTER_LINEAR)
                while target_index < len(positions) and source_index >= positions[target_index]:
                    frames.append(frame.copy())
                    target_index += 1
            source_index += 1
        cap.release()

        if not frames:
            frames.append(np.zeros((SIZE, SIZE, 3), np.uint8))
        while len(frames) < FRAMES:
            frames.append(frames[-1].copy())
        clip = np.asarray(frames, np.uint8)
        if self.training:
            clip = augment_clip(clip)
        clip = clip.astype(np.float32) / 255.0
        clip = ((clip - MEAN) / STD).transpose(0, 3, 1, 2)
        return (
            torch.from_numpy(clip),
            torch.tensor(float(row["label"])),
            row["path"],
        )


def augment_clip(clip: np.ndarray) -> np.ndarray:
    """Clip-consistent lighting/geometry plus mild per-frame sensor noise."""
    if random.random() < 0.5:
        clip = clip[:, :, ::-1].copy()

    if random.random() < 0.25:
        kernel = np.zeros((3, 3), np.float32)
        if random.random() < 0.5:
            kernel[1, :] = 1 / 3
        else:
            kernel[:, 1] = 1 / 3
        clip = np.stack([cv2.filter2D(frame, -1, kernel) for frame in clip])

    values = clip.astype(np.float32) / 255.0
    gamma = random.uniform(0.65, 1.55)
    contrast = random.uniform(0.70, 1.30)
    brightness = random.uniform(-0.18, 0.18)
    channel_gain = np.asarray(
        [random.uniform(0.88, 1.12) for _ in range(3)], np.float32
    ).reshape(1, 1, 1, 3)
    values = np.power(np.clip(values, 0, 1), gamma)
    values = (values - 0.5) * contrast + 0.5 + brightness
    values *= channel_gain
    if random.random() < 0.5:
        sigma = random.uniform(0.0, 0.025)
        values += np.random.normal(0.0, sigma, values.shape).astype(np.float32)
    return np.clip(values * 255.0, 0, 255).astype(np.uint8)


def group_id(path: str) -> int:
    match = re.search(r"_g(\d+)_", path)
    return int(match.group(1)) if match else 0


def expand_validation_windows(rows: list[dict]) -> list[dict]:
    windows: list[dict] = []
    for row in rows:
        cap = cv2.VideoCapture(str(ROOT / row["path"]))
        count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        fps = cap.get(cv2.CAP_PROP_FPS)
        cap.release()
        fps = fps if fps > 0 else 25.0
        duration = max(0.0, (count - 1) / fps)
        max_start = max(0.0, duration - WINDOW_SPAN_SECONDS)
        starts = list(np.arange(0.0, max_start + 1e-6, VALIDATION_STEP_SECONDS))
        if not starts or max_start - starts[-1] > 0.2:
            starts.append(max_start)
        for start in starts:
            windows.append({**row, "start_seconds": float(start)})
    return windows


def binary_metrics(outputs: list[float], targets: list[float], threshold: float) -> dict:
    predicted = [value >= threshold for value in outputs]
    tp = sum(p and y == 1 for p, y in zip(predicted, targets))
    fp = sum(p and y == 0 for p, y in zip(predicted, targets))
    fn = sum((not p) and y == 1 for p, y in zip(predicted, targets))
    correct = sum(p == bool(y) for p, y in zip(predicted, targets))
    return {
        "accuracy": correct / max(1, len(targets)),
        "precision": tp / max(1, tp + fp),
        "recall": tp / max(1, tp + fn),
        "f1": 2 * tp / max(1, 2 * tp + fp + fn),
    }


@torch.no_grad()
def evaluate(model, loader, device) -> dict[str, float]:
    model.eval()
    by_video: dict[str, list[float]] = defaultdict(list)
    labels: dict[str, float] = {}
    window_outputs: list[float] = []
    window_targets: list[float] = []
    for clips, targets, paths in loader:
        probabilities = torch.sigmoid(model(clips.to(device))).cpu().tolist()
        for probability, target, path in zip(probabilities, targets.tolist(), paths):
            by_video[path].append(probability)
            labels[path] = target
            window_outputs.append(probability)
            window_targets.append(target)

    # Overlapping windows are correlated. Select checkpoints using one median
    # score per source video instead of pretending every window is independent.
    video_paths = sorted(by_video)
    video_outputs = [float(np.median(by_video[path])) for path in video_paths]
    video_targets = [labels[path] for path in video_paths]
    best_threshold = 0.5
    best_f1 = -1.0
    for threshold in np.arange(0.05, 0.96, 0.01):
        metrics = binary_metrics(video_outputs, video_targets, float(threshold))
        if metrics["f1"] > best_f1:
            best_f1 = metrics["f1"]
            best_threshold = float(threshold)

    video_metrics = binary_metrics(video_outputs, video_targets, best_threshold)
    window_metrics = binary_metrics(window_outputs, window_targets, best_threshold)
    high_video_metrics = binary_metrics(video_outputs, video_targets, 0.70)
    high_window_metrics = binary_metrics(window_outputs, window_targets, 0.70)
    return {
        **video_metrics,
        "threshold": best_threshold,
        "window_f1": window_metrics["f1"],
        "window_precision": window_metrics["precision"],
        "window_recall": window_metrics["recall"],
        "high_0_70_video_precision": high_video_metrics["precision"],
        "high_0_70_video_recall": high_video_metrics["recall"],
        "high_0_70_window_precision": high_window_metrics["precision"],
        "high_0_70_window_recall": high_window_metrics["recall"],
        "validation_windows": float(len(window_outputs)),
    }


def main() -> None:
    rows = json.loads((ROOT / "data/manifest.json").read_text())
    train_rows = [row for row in rows if group_id(row["path"]) <= 18]
    val_rows = [row for row in rows if group_id(row["path"]) > 18]
    val_windows = expand_validation_windows(val_rows)
    if not train_rows or not val_windows:
        raise RuntimeError("Dataset split is empty")

    counts = {label: sum(row["label"] == label for row in train_rows) for label in (0, 1)}
    weights = [1.0 / counts[row["label"]] for row in train_rows]
    sampler = WeightedRandomSampler(weights, len(train_rows), replacement=True)
    train_loader = DataLoader(
        Clips(train_rows, True),
        batch_size=2,
        sampler=sampler,
        num_workers=4,
        pin_memory=True,
        persistent_workers=True,
    )
    val_loader = DataLoader(
        Clips(val_windows, False),
        batch_size=4,
        shuffle=False,
        num_workers=4,
        pin_memory=True,
        persistent_workers=True,
    )

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = BrushVideoClassifier(pretrained=True).to(device)
    model.set_trainable_stage(0)
    loss_fn = nn.BCEWithLogitsLoss()
    checkpoint = ROOT / "checkpoints/best-2s-192.pt"
    checkpoint.parent.mkdir(exist_ok=True)
    best_f1 = -1.0
    epochs_without_improvement = 0
    optimizer = torch.optim.AdamW(
        [p for p in model.parameters() if p.requires_grad],
        lr=5e-4,
        weight_decay=1e-4,
    )

    print(
        f"train_videos={len(train_rows)} val_videos={len(val_rows)} "
        f"val_windows={len(val_windows)} span={WINDOW_SPAN_SECONDS:.3f}s",
        flush=True,
    )
    for epoch in range(14):
        if epoch == 4:
            model.set_trainable_stage(2)
            optimizer = torch.optim.AdamW(
                [p for p in model.parameters() if p.requires_grad],
                lr=1e-5,
                weight_decay=1e-4,
            )
        model.train()
        running = 0.0
        for clips, targets, _ in train_loader:
            optimizer.zero_grad(set_to_none=True)
            with torch.autocast(device_type=device.type, enabled=device.type == "cuda"):
                logits = model(clips.to(device, non_blocking=True))
                loss = loss_fn(logits, targets.to(device, non_blocking=True))
            loss.backward()
            optimizer.step()
            running += loss.item()

        metrics = evaluate(model, val_loader, device)
        print(
            f"epoch={epoch + 1:02d} loss={running / len(train_loader):.4f} {metrics}",
            flush=True,
        )
        if metrics["f1"] > best_f1:
            best_f1 = metrics["f1"]
            torch.save(
                {
                    "model": model.state_dict(),
                    "metrics": metrics,
                    "input": {
                        "frames": FRAMES,
                        "size": SIZE,
                        "sample_fps": SAMPLE_FPS,
                        "window_span_seconds": WINDOW_SPAN_SECONDS,
                    },
                },
                checkpoint,
            )
            epochs_without_improvement = 0
        else:
            epochs_without_improvement += 1
        if epoch >= 7 and epochs_without_improvement >= 4:
            print("early_stop", flush=True)
            break
    print(f"best_f1={best_f1:.4f} checkpoint={checkpoint}")


if __name__ == "__main__":
    main()

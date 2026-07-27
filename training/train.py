from __future__ import annotations

import json
import random
import re
from pathlib import Path

import cv2
import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler

from model import FRAMES, SIZE, BrushVideoClassifier

ROOT = Path(__file__).resolve().parent
MEAN = np.asarray([0.43216, 0.394666, 0.37645], np.float32).reshape(1, 1, 1, 3)
STD = np.asarray([0.22803, 0.22145, 0.216989], np.float32).reshape(1, 1, 1, 3)
random.seed(42)
np.random.seed(42)
torch.manual_seed(42)


class Clips(Dataset):
    def __init__(self, rows: list[dict], training: bool) -> None:
        self.rows = rows
        self.training = training

    def __len__(self) -> int:
        return len(self.rows)

    def __getitem__(self, index: int):
        row = self.rows[index]
        cap = cv2.VideoCapture(str(ROOT / row["path"]))
        count = max(int(cap.get(cv2.CAP_PROP_FRAME_COUNT)), FRAMES)
        margin = max(0, count // 12)
        if self.training:
            max_shift = max(0, count // 8)
            shift = random.randint(-max_shift, max_shift)
        else:
            shift = 0
        positions = np.linspace(margin, max(margin + 1, count - margin - 1), FRAMES)
        positions = np.clip(positions + shift, 0, count - 1).astype(int)
        frames: list[np.ndarray] = []
        target_index = 0
        source_index = 0
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
                frame = cv2.resize(frame, (SIZE, SIZE), interpolation=cv2.INTER_AREA)
                while target_index < len(positions) and source_index >= positions[target_index]:
                    frames.append(frame.copy())
                    target_index += 1
            source_index += 1
        cap.release()
        if not frames:
            frames.append(np.zeros((SIZE, SIZE, 3), np.uint8))
        while len(frames) < FRAMES:
            frames.append(frames[-1].copy())
        clip = np.asarray(frames, np.float32) / 255.0
        if self.training and random.random() < 0.5:
            clip = clip[:, :, ::-1].copy()
        clip = ((clip - MEAN) / STD).transpose(0, 3, 1, 2)
        return torch.from_numpy(clip), torch.tensor(float(row["label"]))


def group_id(path: str) -> int:
    match = re.search(r"_g(\d+)_", path)
    return int(match.group(1)) if match else 0


@torch.no_grad()
def evaluate(model, loader, device) -> dict[str, float]:
    model.eval()
    outputs, targets = [], []
    for clips, labels in loader:
        logits = model(clips.to(device)).cpu()
        outputs.extend(torch.sigmoid(logits).tolist())
        targets.extend(labels.tolist())
    best_threshold = 0.5
    best_f1 = -1.0
    for threshold in np.arange(0.05, 0.96, 0.01):
        predicted = [value >= threshold for value in outputs]
        tp = sum(p and y == 1 for p, y in zip(predicted, targets))
        fp = sum(p and y == 0 for p, y in zip(predicted, targets))
        fn = sum((not p) and y == 1 for p, y in zip(predicted, targets))
        f1 = 2 * tp / max(1, 2 * tp + fp + fn)
        if f1 > best_f1:
            best_f1 = f1
            best_threshold = float(threshold)
    predicted = [value >= best_threshold for value in outputs]
    tp = sum(p and y == 1 for p, y in zip(predicted, targets))
    fp = sum(p and y == 0 for p, y in zip(predicted, targets))
    fn = sum((not p) and y == 1 for p, y in zip(predicted, targets))
    correct = sum(p == bool(y) for p, y in zip(predicted, targets))
    return {
        "accuracy": correct / max(1, len(targets)),
        "precision": tp / max(1, tp + fp),
        "recall": tp / max(1, tp + fn),
        "f1": 2 * tp / max(1, 2 * tp + fp + fn),
        "threshold": best_threshold,
    }


def main() -> None:
    rows = json.loads((ROOT / "data/manifest.json").read_text())
    train_rows = [row for row in rows if group_id(row["path"]) <= 18]
    val_rows = [row for row in rows if group_id(row["path"]) > 18]
    if not train_rows or not val_rows:
        raise RuntimeError("Dataset split is empty")
    counts = {label: sum(row["label"] == label for row in train_rows) for label in (0, 1)}
    weights = [1.0 / counts[row["label"]] for row in train_rows]
    sampler = WeightedRandomSampler(weights, len(train_rows), replacement=True)
    train_loader = DataLoader(Clips(train_rows, True), batch_size=2, sampler=sampler,
                              num_workers=4, pin_memory=True, persistent_workers=True)
    val_loader = DataLoader(Clips(val_rows, False), batch_size=2, shuffle=False,
                            num_workers=4, pin_memory=True, persistent_workers=True)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = BrushVideoClassifier(pretrained=True).to(device)
    model.set_trainable_stage(0)
    loss_fn = nn.BCEWithLogitsLoss()
    checkpoint = ROOT / "checkpoints/best.pt"
    checkpoint.parent.mkdir(exist_ok=True)
    best_f1 = -1.0
    epochs_without_improvement = 0
    optimizer = torch.optim.AdamW(
        [p for p in model.parameters() if p.requires_grad],
        lr=5e-4,
        weight_decay=1e-4,
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
        for clips, labels in train_loader:
            optimizer.zero_grad(set_to_none=True)
            with torch.autocast(device_type=device.type, enabled=device.type == "cuda"):
                logits = model(clips.to(device, non_blocking=True))
                loss = loss_fn(logits, labels.to(device, non_blocking=True))
            loss.backward()
            optimizer.step()
            running += loss.item()
        metrics = evaluate(model, val_loader, device)
        print(f"epoch={epoch + 1:02d} loss={running / len(train_loader):.4f} {metrics}", flush=True)
        if metrics["f1"] > best_f1:
            best_f1 = metrics["f1"]
            torch.save({"model": model.state_dict(), "metrics": metrics}, checkpoint)
            epochs_without_improvement = 0
        else:
            epochs_without_improvement += 1
        if epoch >= 7 and epochs_without_improvement >= 4:
            print("early_stop", flush=True)
            break
    print(f"best_f1={best_f1:.4f} checkpoint={checkpoint}")


if __name__ == "__main__":
    main()

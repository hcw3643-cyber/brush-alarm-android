from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader, WeightedRandomSampler

from model import SAMPLE_FPS, BrushVideoClassifier
from train import (
    Clips,
    ROOT,
    WINDOW_SPAN_SECONDS,
    evaluate,
    expand_validation_windows,
    group_id,
)


def feedback_windows(video: Path) -> list[dict]:
    cap = cv2.VideoCapture(str(video))
    frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    fps = cap.get(cv2.CAP_PROP_FPS)
    cap.release()
    if frame_count <= 0 or fps <= 0:
        raise RuntimeError(f"Cannot read feedback video: {video}")
    duration = (frame_count - 1) / fps
    max_start = max(0.0, duration - WINDOW_SPAN_SECONDS)
    return [
        {
            "path": str(video.resolve()),
            "label": 1,
            "start_seconds": float(start),
            "source": "local_feedback",
        }
        for start in np.arange(0.0, max_start + 1e-6, 0.5)
    ]


@torch.no_grad()
def feedback_metrics(model, loader, device) -> dict[str, float]:
    model.eval()
    probabilities: list[float] = []
    for clips, _, _ in loader:
        probabilities.extend(
            torch.sigmoid(model(clips.to(device, non_blocking=True))).cpu().tolist()
        )
    values = np.asarray(probabilities)
    return {
        "median": float(np.median(values)),
        "p10": float(np.percentile(values, 10)),
        "above_high": float(np.mean(values >= 0.70)),
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Fine-tune the public-data model with a consented local brushing video."
    )
    parser.add_argument("--positive-video", required=True, type=Path)
    parser.add_argument("--epochs", type=int, default=5)
    args = parser.parse_args()

    rows = json.loads((ROOT / "data/manifest.json").read_text())
    public_train = [row for row in rows if group_id(row["path"]) <= 18]
    public_val = [row for row in rows if group_id(row["path"]) > 18]
    feedback = feedback_windows(args.positive_video)
    combined = public_train + feedback

    counts = {label: sum(row["label"] == label for row in combined) for label in (0, 1)}
    weights = [1.0 / counts[row["label"]] for row in combined]
    train_loader = DataLoader(
        Clips(combined, True),
        batch_size=2,
        sampler=WeightedRandomSampler(weights, len(combined), replacement=True),
        num_workers=2,
        pin_memory=True,
        persistent_workers=True,
    )
    public_val_loader = DataLoader(
        Clips(expand_validation_windows(public_val), False),
        batch_size=4,
        shuffle=False,
        num_workers=2,
        pin_memory=True,
        persistent_workers=True,
    )
    feedback_loader = DataLoader(
        Clips(feedback, False),
        batch_size=4,
        shuffle=False,
        num_workers=1,
        pin_memory=True,
        persistent_workers=True,
    )

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    source = torch.load(
        ROOT / "checkpoints/best-2s-192.pt",
        map_location=device,
        weights_only=False,
    )
    model = BrushVideoClassifier(pretrained=False).to(device)
    model.load_state_dict(source["model"])
    model.set_trainable_stage(2)
    optimizer = torch.optim.AdamW(
        [parameter for parameter in model.parameters() if parameter.requires_grad],
        lr=5e-6,
        weight_decay=1e-4,
    )
    loss_fn = nn.BCEWithLogitsLoss()
    output = ROOT / "checkpoints/best-feedback-2s-192.pt"
    best_feedback = -1.0

    for epoch in range(args.epochs + 1):
        public_metrics = evaluate(model, public_val_loader, device)
        local_metrics = feedback_metrics(model, feedback_loader, device)
        print(
            f"epoch={epoch} public_f1={public_metrics['f1']:.4f} "
            f"feedback_median={local_metrics['median']:.4f} "
            f"feedback_p10={local_metrics['p10']:.4f} "
            f"feedback_above_0.70={local_metrics['above_high']:.4f}",
            flush=True,
        )
        # Permit a small public-benchmark tradeoff, but never select a model that
        # fixes one local clip by broadly forgetting the hard negatives.
        if (
            public_metrics["f1"] >= 0.91
            and public_metrics["high_0_70_video_precision"] >= 0.95
            and local_metrics["above_high"] > best_feedback
        ):
            best_feedback = local_metrics["above_high"]
            torch.save(
                {
                    "model": model.state_dict(),
                    "metrics": public_metrics,
                    "feedback_metrics": local_metrics,
                    "input": source["input"],
                },
                output,
            )
        if epoch == args.epochs:
            break

        model.train()
        for clips, targets, _ in train_loader:
            optimizer.zero_grad(set_to_none=True)
            with torch.autocast(device_type=device.type, enabled=device.type == "cuda"):
                logits = model(clips.to(device, non_blocking=True))
                loss = loss_fn(logits, targets.to(device, non_blocking=True))
            loss.backward()
            optimizer.step()

    print(f"saved={output} best_feedback_above_0.70={best_feedback:.4f}")


if __name__ == "__main__":
    main()

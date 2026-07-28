# SPDX-FileCopyrightText: 2026 Leo Huang
# SPDX-License-Identifier: GPL-3.0-only

from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader, WeightedRandomSampler

from training.config import load_config, repository_relative
from training.model import BrushVideoClassifier
from training.train import (
    Clips,
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
            "id": "local_feedback_positive",
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
    parser.add_argument("--config", type=Path)
    parser.add_argument("--positive-video", required=True, type=Path)
    parser.add_argument("--epochs", type=int)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    config = load_config("s3d", args.config)
    experiment = config.section("experiment")
    feedback_config = config.section("feedback")
    dataset = config.section("dataset")
    loader = config.section("loader")
    data_dir = config.paths.data_dir
    manifest = data_dir / str(dataset["manifest"])
    source_path = args.source or (
        config.paths.checkpoint_dir / str(experiment["checkpoint"])
    )
    output = args.output or (
        config.paths.checkpoint_dir / str(experiment["feedback_checkpoint"])
    )
    epochs = args.epochs if args.epochs is not None else int(feedback_config["epochs"])
    workers = int(feedback_config["workers"])
    fallback_video_fps = float(dataset["fallback_video_fps"])
    train_group_max = int(dataset["train_group_max"])
    validation_step = float(dataset["validation_step_seconds"])
    pin_memory = bool(loader["pin_memory"])

    rows = json.loads(manifest.read_text(encoding="utf-8"))
    public_train = [row for row in rows if group_id(row["path"]) <= train_group_max]
    public_val = [row for row in rows if group_id(row["path"]) > train_group_max]
    feedback = feedback_windows(args.positive_video)
    combined = public_train + feedback

    counts = {label: sum(row["label"] == label for row in combined) for label in (0, 1)}
    weights = [1.0 / counts[row["label"]] for row in combined]
    train_loader = DataLoader(
        Clips(combined, True, data_dir, fallback_video_fps),
        batch_size=int(feedback_config["train_batch_size"]),
        sampler=WeightedRandomSampler(weights, len(combined), replacement=True),
        num_workers=workers,
        pin_memory=pin_memory,
        persistent_workers=workers > 0,
    )
    public_val_loader = DataLoader(
        Clips(
            expand_validation_windows(
                public_val, data_dir, validation_step, fallback_video_fps
            ),
            False,
            data_dir,
            fallback_video_fps,
        ),
        batch_size=int(feedback_config["validation_batch_size"]),
        shuffle=False,
        num_workers=workers,
        pin_memory=pin_memory,
        persistent_workers=workers > 0,
    )
    feedback_loader = DataLoader(
        Clips(feedback, False, data_dir, fallback_video_fps),
        batch_size=int(feedback_config["validation_batch_size"]),
        shuffle=False,
        num_workers=1,
        pin_memory=pin_memory,
        persistent_workers=True,
    )

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    source = torch.load(
        source_path,
        map_location=device,
        weights_only=False,
    )
    model = BrushVideoClassifier(pretrained=False).to(device)
    model.load_state_dict(source["model"])
    model.set_trainable_stage(2)
    optimizer = torch.optim.AdamW(
        [parameter for parameter in model.parameters() if parameter.requires_grad],
        lr=float(feedback_config["learning_rate"]),
        weight_decay=float(experiment["weight_decay"]),
    )
    loss_fn = nn.BCEWithLogitsLoss()
    output.parent.mkdir(parents=True, exist_ok=True)
    best_feedback = -1.0

    for epoch in range(epochs + 1):
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
            public_metrics["f1"] >= float(feedback_config["minimum_public_f1"])
            and public_metrics["high_0_70_video_precision"]
            >= float(feedback_config["minimum_high_threshold_precision"])
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
        if epoch == epochs:
            break

        model.train()
        for clips, targets, _ in train_loader:
            optimizer.zero_grad(set_to_none=True)
            with torch.autocast(device_type=device.type, enabled=device.type == "cuda"):
                logits = model(clips.to(device, non_blocking=True))
                loss = loss_fn(logits, targets.to(device, non_blocking=True))
            loss.backward()
            optimizer.step()

    print(
        f"saved={repository_relative(output)} "
        f"best_feedback_above_0.70={best_feedback:.4f}"
    )


if __name__ == "__main__":
    main()

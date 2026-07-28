# SPDX-FileCopyrightText: 2026 Leo Huang
# SPDX-License-Identifier: GPL-3.0-only

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader, WeightedRandomSampler

from lightweight_model import LightweightBrushClassifier
from model import BrushVideoClassifier
from train import (
    Clips,
    ROOT,
    WINDOW_SPAN_SECONDS,
    evaluate,
    expand_validation_windows,
    group_id,
)


def seed_everything(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)


def make_loaders(batch_size: int, workers: int):
    rows = json.loads((ROOT / "data/manifest.json").read_text())
    train_rows = [row for row in rows if group_id(row["path"]) <= 18]
    val_rows = [row for row in rows if group_id(row["path"]) > 18]
    val_windows = expand_validation_windows(val_rows)
    counts = {label: sum(row["label"] == label for row in train_rows) for label in (0, 1)}
    weights = [1.0 / counts[row["label"]] for row in train_rows]
    train_loader = DataLoader(
        Clips(train_rows, True),
        batch_size=batch_size,
        sampler=WeightedRandomSampler(weights, len(train_rows), replacement=True),
        num_workers=workers,
        pin_memory=True,
        persistent_workers=workers > 0,
    )
    val_loader = DataLoader(
        Clips(val_windows, False),
        batch_size=max(8, batch_size * 4),
        shuffle=False,
        num_workers=workers,
        pin_memory=True,
        persistent_workers=workers > 0,
    )
    return train_rows, val_rows, val_windows, train_loader, val_loader


def load_teacher(device: torch.device, checkpoint: Path) -> BrushVideoClassifier:
    state = torch.load(checkpoint, map_location=device, weights_only=False)
    teacher = BrushVideoClassifier(pretrained=False).to(device)
    teacher.load_state_dict(state["model"])
    teacher.eval()
    for parameter in teacher.parameters():
        parameter.requires_grad = False
    return teacher


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Train a streaming MobileNetV3 + temporal head with S3D distillation."
    )
    parser.add_argument("--epochs", type=int, default=14)
    parser.add_argument("--batch-size", type=int, default=2)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--seed", type=int, default=43)
    parser.add_argument("--unfreeze-epoch", type=int, default=4)
    parser.add_argument("--unfreeze-blocks", type=int, default=3)
    parser.add_argument("--finetune-lr", type=float, default=1e-5)
    parser.add_argument("--backbone", choices=("small", "large"), default="large")
    parser.add_argument("--head-version", choices=("pooled", "motion"), default="motion")
    parser.add_argument("--hard-weight", type=float, default=0.70)
    parser.add_argument("--temperature", type=float, default=2.0)
    parser.add_argument(
        "--teacher",
        type=Path,
        default=ROOT / "checkpoints/best-feedback-2s-192.pt",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "checkpoints/best-lightweight-large-motion-2s-192.pt",
    )
    parser.add_argument(
        "--latest",
        type=Path,
        default=ROOT / "checkpoints/latest-lightweight-large-motion-2s-192.pt",
    )
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Continue from --latest, or from --output if the first run was interrupted.",
    )
    parser.add_argument(
        "--restart-best",
        action="store_true",
        help="Restart fine-tuning at --unfreeze-epoch from the best checkpoint.",
    )
    args = parser.parse_args()
    if args.restart_best and not args.output.exists():
        parser.error(f"--restart-best requires an existing checkpoint: {args.output}")
    seed_everything(args.seed)

    train_rows, val_rows, val_windows, train_loader, val_loader = make_loaders(
        args.batch_size, args.workers
    )
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    teacher = load_teacher(device, args.teacher)
    resume_state = None
    restarting_best = args.restart_best and args.output.exists()
    if restarting_best:
        resume_state = torch.load(args.output, map_location=device, weights_only=False)
    elif args.resume and args.latest.exists():
        resume_state = torch.load(args.latest, map_location=device, weights_only=False)
    elif args.resume and args.output.exists():
        # A run may be stopped after writing the best model but before the
        # resumable optimizer state. Continuing with a fresh optimizer is safe.
        resume_state = torch.load(args.output, map_location=device, weights_only=False)

    checkpoint_backbone = (
        resume_state.get("architecture", {}).get("backbone", args.backbone)
        if resume_state
        else args.backbone
    )
    if checkpoint_backbone != args.backbone:
        raise ValueError(
            f"Checkpoint backbone is {checkpoint_backbone}, not {args.backbone}"
        )
    checkpoint_head = (
        resume_state.get("architecture", {}).get("head_version", "pooled")
        if resume_state
        else args.head_version
    )
    if checkpoint_head != args.head_version:
        raise ValueError(
            f"Checkpoint temporal head is {checkpoint_head}, not {args.head_version}"
        )
    student = LightweightBrushClassifier(
        pretrained=resume_state is None,
        backbone=args.backbone,
        head_version=args.head_version,
    ).to(device)
    if resume_state is not None:
        student.load_state_dict(resume_state["model"])
    if restarting_best:
        start_epoch = args.unfreeze_epoch
    else:
        start_epoch = int(resume_state.get("next_epoch", 1)) if resume_state else 0
    desired_blocks = args.unfreeze_blocks if start_epoch >= args.unfreeze_epoch else 0
    student.set_trainable_stage(desired_blocks)
    hard_loss = nn.BCEWithLogitsLoss()
    soft_loss = nn.BCEWithLogitsLoss()
    optimizer = torch.optim.AdamW(
        [parameter for parameter in student.parameters() if parameter.requires_grad],
        lr=args.finetune_lr if desired_blocks else 8e-4,
        weight_decay=2e-4,
    )
    if (
        resume_state is not None
        and not restarting_best
        and "optimizer" in resume_state
        and int(resume_state.get("trainable_blocks", desired_blocks)) == desired_blocks
    ):
        optimizer.load_state_dict(resume_state["optimizer"])
    scaler = torch.amp.GradScaler(device.type, enabled=device.type == "cuda")
    best_f1 = float(resume_state.get("best_f1", -1.0)) if resume_state else -1.0
    if best_f1 < 0 and resume_state is not None:
        best_f1 = float(resume_state.get("metrics", {}).get("f1", -1.0))
    stale_epochs = int(resume_state.get("stale_epochs", 0)) if resume_state else 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.latest.parent.mkdir(parents=True, exist_ok=True)

    print(
        f"device={device} train_videos={len(train_rows)} val_videos={len(val_rows)} "
        f"val_windows={len(val_windows)} span={WINDOW_SPAN_SECONDS:.3f}s "
        f"teacher={args.teacher} start_epoch={start_epoch} target_epochs={args.epochs}",
        flush=True,
    )
    for epoch in range(start_epoch, args.epochs):
        if epoch == args.unfreeze_epoch:
            student.set_trainable_stage(args.unfreeze_blocks)
            desired_blocks = args.unfreeze_blocks
            optimizer = torch.optim.AdamW(
                [parameter for parameter in student.parameters() if parameter.requires_grad],
                lr=args.finetune_lr,
                weight_decay=2e-4,
            )

        student.train()
        running = 0.0
        running_hard = 0.0
        running_soft = 0.0
        for clips, targets, _ in train_loader:
            clips = clips.to(device, non_blocking=True)
            targets = targets.to(device, non_blocking=True)
            optimizer.zero_grad(set_to_none=True)
            with torch.no_grad(), torch.autocast(
                device_type=device.type, enabled=device.type == "cuda"
            ):
                teacher_logits = teacher(clips)
            with torch.autocast(device_type=device.type, enabled=device.type == "cuda"):
                student_logits = student(clips)
                hard = hard_loss(student_logits, targets)
                temperature = args.temperature
                soft_targets = torch.sigmoid(teacher_logits / temperature)
                soft = soft_loss(student_logits / temperature, soft_targets) * (
                    temperature * temperature
                )
                loss = args.hard_weight * hard + (1.0 - args.hard_weight) * soft
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
            running += float(loss.detach())
            running_hard += float(hard.detach())
            running_soft += float(soft.detach())

        metrics = evaluate(student, val_loader, device)
        batches = max(1, len(train_loader))
        print(
            f"epoch={epoch + 1:02d} loss={running / batches:.4f} "
            f"hard={running_hard / batches:.4f} soft={running_soft / batches:.4f} "
            f"metrics={metrics}",
            flush=True,
        )
        if metrics["f1"] > best_f1:
            best_f1 = metrics["f1"]
            torch.save(
                {
                    "model": student.state_dict(),
                    "metrics": metrics,
                    "input": {
                        "frames": 16,
                        "size": 192,
                        "sample_fps": 8.0,
                        "window_span_seconds": WINDOW_SPAN_SECONDS,
                        "layout": "NTCHW",
                    },
                    "architecture": {
                        "name": f"mobilenet-v3-{args.backbone}-stream-tcn-v1",
                        "backbone": args.backbone,
                        "head_version": args.head_version,
                        "embedding_size": 192,
                        "teacher": args.teacher.name,
                        "hard_weight": args.hard_weight,
                        "temperature": args.temperature,
                    },
                    "epoch": epoch + 1,
                },
                args.output,
            )
            stale_epochs = 0
        else:
            stale_epochs += 1
        torch.save(
            {
                "model": student.state_dict(),
                "optimizer": optimizer.state_dict(),
                "metrics": metrics,
                "best_f1": best_f1,
                "stale_epochs": stale_epochs,
                "next_epoch": epoch + 1,
                "trainable_blocks": desired_blocks,
                "input": {
                    "frames": 16,
                    "size": 192,
                    "sample_fps": 8.0,
                    "window_span_seconds": WINDOW_SPAN_SECONDS,
                    "layout": "NTCHW",
                },
                "architecture": {
                    "name": f"mobilenet-v3-{args.backbone}-stream-tcn-v1",
                    "backbone": args.backbone,
                    "head_version": args.head_version,
                    "embedding_size": 192,
                    "teacher": args.teacher.name,
                    "hard_weight": args.hard_weight,
                    "temperature": args.temperature,
                },
            },
            args.latest,
        )
        if epoch >= max(8, args.unfreeze_epoch + 4) and stale_epochs >= 4:
            print("early_stop", flush=True)
            break

    print(f"best_f1={best_f1:.4f} checkpoint={args.output}", flush=True)


if __name__ == "__main__":
    main()

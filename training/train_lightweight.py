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

from training.config import load_config, repository_relative
from training.lightweight_model import LightweightBrushClassifier
from training.model import BrushVideoClassifier
from training.train import (
    Clips,
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


def make_loaders(
    data_dir: Path,
    manifest_name: str,
    train_group_max: int,
    validation_step_seconds: float,
    fallback_video_fps: float,
    batch_size: int,
    workers: int,
    pin_memory: bool,
):
    rows = json.loads((data_dir / manifest_name).read_text(encoding="utf-8"))
    train_rows = [row for row in rows if group_id(row["path"]) <= train_group_max]
    val_rows = [row for row in rows if group_id(row["path"]) > train_group_max]
    val_windows = expand_validation_windows(
        val_rows, data_dir, validation_step_seconds, fallback_video_fps
    )
    counts = {
        label: sum(row["label"] == label for row in train_rows) for label in (0, 1)
    }
    weights = [1.0 / counts[row["label"]] for row in train_rows]
    train_loader = DataLoader(
        Clips(train_rows, True, data_dir, fallback_video_fps),
        batch_size=batch_size,
        sampler=WeightedRandomSampler(weights, len(train_rows), replacement=True),
        num_workers=workers,
        pin_memory=pin_memory,
        persistent_workers=workers > 0,
    )
    val_loader = DataLoader(
        Clips(val_windows, False, data_dir, fallback_video_fps),
        batch_size=max(8, batch_size * 4),
        shuffle=False,
        num_workers=workers,
        pin_memory=pin_memory,
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
    parser.add_argument("--config", type=Path)
    parser.add_argument("--epochs", type=int)
    parser.add_argument("--batch-size", type=int)
    parser.add_argument("--workers", type=int)
    parser.add_argument("--seed", type=int)
    parser.add_argument("--unfreeze-epoch", type=int)
    parser.add_argument("--unfreeze-blocks", type=int)
    parser.add_argument("--finetune-lr", type=float)
    parser.add_argument("--backbone", choices=("small", "large"))
    parser.add_argument("--head-version", choices=("pooled", "motion"))
    parser.add_argument("--hard-weight", type=float)
    parser.add_argument("--temperature", type=float)
    parser.add_argument(
        "--teacher",
        type=Path,
    )
    parser.add_argument(
        "--output",
        type=Path,
    )
    parser.add_argument(
        "--latest",
        type=Path,
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
    config = load_config("lightweight", args.config)
    experiment = config.section("experiment")
    dataset = config.section("dataset")
    loader = config.section("loader")
    args.epochs = args.epochs or int(experiment["epochs"])
    args.batch_size = args.batch_size or int(experiment["batch_size"])
    args.workers = (
        args.workers if args.workers is not None else int(experiment["workers"])
    )
    args.seed = args.seed if args.seed is not None else int(experiment["seed"])
    args.unfreeze_epoch = (
        args.unfreeze_epoch
        if args.unfreeze_epoch is not None
        else int(experiment["unfreeze_epoch"])
    )
    args.unfreeze_blocks = (
        args.unfreeze_blocks
        if args.unfreeze_blocks is not None
        else int(experiment["unfreeze_blocks"])
    )
    args.finetune_lr = (
        args.finetune_lr
        if args.finetune_lr is not None
        else float(experiment["finetune_learning_rate"])
    )
    args.backbone = args.backbone or str(experiment["backbone"])
    args.head_version = args.head_version or str(experiment["head_version"])
    args.hard_weight = (
        args.hard_weight
        if args.hard_weight is not None
        else float(experiment["hard_weight"])
    )
    args.temperature = (
        args.temperature
        if args.temperature is not None
        else float(experiment["temperature"])
    )
    args.teacher = args.teacher or (
        config.paths.checkpoint_dir / str(experiment["teacher_checkpoint"])
    )
    args.output = args.output or (
        config.paths.checkpoint_dir / str(experiment["checkpoint"])
    )
    args.latest = args.latest or (
        config.paths.checkpoint_dir / str(experiment["latest_checkpoint"])
    )
    if args.restart_best and not args.output.exists():
        parser.error(f"--restart-best requires an existing checkpoint: {args.output}")
    seed_everything(args.seed)

    train_rows, val_rows, val_windows, train_loader, val_loader = make_loaders(
        config.paths.data_dir,
        str(dataset["manifest"]),
        int(dataset["train_group_max"]),
        float(dataset["validation_step_seconds"]),
        float(dataset["fallback_video_fps"]),
        args.batch_size,
        args.workers,
        bool(loader["pin_memory"]),
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
        lr=(
            args.finetune_lr
            if desired_blocks
            else float(experiment["head_learning_rate"])
        ),
        weight_decay=float(experiment["weight_decay"]),
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
        f"teacher={repository_relative(args.teacher)} "
        f"start_epoch={start_epoch} target_epochs={args.epochs}",
        flush=True,
    )
    for epoch in range(start_epoch, args.epochs):
        if epoch == args.unfreeze_epoch:
            student.set_trainable_stage(args.unfreeze_blocks)
            desired_blocks = args.unfreeze_blocks
            optimizer = torch.optim.AdamW(
                [
                    parameter
                    for parameter in student.parameters()
                    if parameter.requires_grad
                ],
                lr=args.finetune_lr,
                weight_decay=float(experiment["weight_decay"]),
            )

        student.train()
        running = 0.0
        running_hard = 0.0
        running_soft = 0.0
        for clips, targets, _ in train_loader:
            clips = clips.to(device, non_blocking=True)
            targets = targets.to(device, non_blocking=True)
            optimizer.zero_grad(set_to_none=True)
            with (
                torch.no_grad(),
                torch.autocast(device_type=device.type, enabled=device.type == "cuda"),
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

    print(
        f"best_f1={best_f1:.4f} checkpoint={repository_relative(args.output)}",
        flush=True,
    )


if __name__ == "__main__":
    main()

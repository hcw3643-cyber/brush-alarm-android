# SPDX-FileCopyrightText: 2026 Leo Huang
# SPDX-License-Identifier: GPL-3.0-only

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

from training.config import load_config, repository_relative
from training.model import FRAMES, SIZE, BrushVideoClassifier
from training.onnx_utils import make_self_contained, sha256


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Export and verify the S3D ONNX model."
    )
    parser.add_argument("--config", type=Path)
    parser.add_argument("--checkpoint", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    config = load_config("s3d", args.config)
    experiment = config.section("experiment")
    feedback_checkpoint = config.paths.checkpoint_dir / str(
        experiment["feedback_checkpoint"]
    )
    base_checkpoint = config.paths.checkpoint_dir / str(experiment["checkpoint"])
    checkpoint_path = args.checkpoint or (
        feedback_checkpoint if feedback_checkpoint.exists() else base_checkpoint
    )
    output = args.output or (config.paths.export_dir / str(experiment["export_model"]))

    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=True)
    model = BrushVideoClassifier(pretrained=False)
    model.load_state_dict(checkpoint["model"])
    model.eval()
    generator = torch.Generator().manual_seed(42)
    sample = torch.randn(
        1, FRAMES, 3, SIZE, SIZE, generator=generator, dtype=torch.float32
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        model,
        (sample,),
        output,
        input_names=["frames"],
        output_names=["logit"],
        opset_version=18,
        dynamo=True,
    )
    make_self_contained(output)

    with torch.no_grad():
        expected = model(sample).numpy()
    session = ort.InferenceSession(str(output), providers=["CPUExecutionProvider"])
    actual = session.run(None, {"frames": sample.numpy()})[0]
    max_abs_error = float(np.max(np.abs(expected - actual)))
    if max_abs_error > 1e-4:
        raise RuntimeError(f"ONNX parity failed: max_abs_error={max_abs_error}")

    print(
        f"model={repository_relative(output)} bytes={output.stat().st_size} "
        f"sha256={sha256(output)} checkpoint={checkpoint_path.name} "
        f"max_abs_error={max_abs_error:.8g} metrics={checkpoint.get('metrics')}"
    )


if __name__ == "__main__":
    main()

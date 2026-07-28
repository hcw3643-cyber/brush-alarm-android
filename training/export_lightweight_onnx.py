# SPDX-FileCopyrightText: 2026 Leo Huang
# SPDX-License-Identifier: GPL-3.0-only

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

from training.config import load_config, repository_relative
from training.lightweight_model import (
    EMBEDDING_SIZE,
    LightweightBrushClassifier,
)
from training.model import FRAMES, SIZE
from training.onnx_utils import make_self_contained, sha256


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path)
    parser.add_argument("--checkpoint", type=Path)
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    config = load_config("lightweight", args.config)
    experiment = config.section("experiment")
    args.checkpoint = args.checkpoint or (
        config.paths.checkpoint_dir / str(experiment["checkpoint"])
    )
    args.output_dir = args.output_dir or (
        config.paths.export_dir / str(experiment["export_directory"])
    )

    checkpoint = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    backbone = checkpoint.get("architecture", {}).get("backbone", "small")
    head_version = checkpoint.get("architecture", {}).get("head_version", "pooled")
    model = LightweightBrushClassifier(
        pretrained=False, backbone=backbone, head_version=head_version
    )
    model.load_state_dict(checkpoint["model"])
    model.eval()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    encoder_path = args.output_dir / "brush_frame_encoder.onnx"
    head_path = args.output_dir / "brush_temporal_head.onnx"
    frame = torch.randn(1, 3, SIZE, SIZE)
    embeddings = torch.randn(1, FRAMES, EMBEDDING_SIZE)

    torch.onnx.export(
        model.encoder,
        (frame,),
        encoder_path,
        input_names=["frame"],
        output_names=["embedding"],
        opset_version=18,
        dynamo=True,
    )
    torch.onnx.export(
        model.temporal_head,
        (embeddings,),
        head_path,
        input_names=["embeddings"],
        output_names=["logit"],
        opset_version=18,
        dynamo=True,
    )
    make_self_contained(encoder_path)
    make_self_contained(head_path)

    with torch.no_grad():
        expected_embedding = model.encoder(frame).numpy()
        expected_logit = model.temporal_head(embeddings).numpy()
    encoder_session = ort.InferenceSession(
        str(encoder_path), providers=["CPUExecutionProvider"]
    )
    head_session = ort.InferenceSession(
        str(head_path), providers=["CPUExecutionProvider"]
    )
    actual_embedding = encoder_session.run(None, {"frame": frame.numpy()})[0]
    actual_logit = head_session.run(None, {"embeddings": embeddings.numpy()})[0]
    encoder_error = float(np.max(np.abs(expected_embedding - actual_embedding)))
    head_error = float(np.max(np.abs(expected_logit - actual_logit)))
    if encoder_error > 1e-4 or head_error > 1e-4:
        raise RuntimeError(
            f"ONNX parity failed: encoder={encoder_error} head={head_error}"
        )

    for path in (encoder_path, head_path):
        print(
            f"{repository_relative(path)} bytes={path.stat().st_size} "
            f"sha256={sha256(path)}"
        )
    print(
        f"checkpoint_metrics={checkpoint.get('metrics')} "
        f"max_abs_error_encoder={encoder_error:.8g} "
        f"max_abs_error_head={head_error:.8g}"
    )


if __name__ == "__main__":
    main()

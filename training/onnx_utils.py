# SPDX-FileCopyrightText: 2026 Leo Huang
# SPDX-License-Identifier: GPL-3.0-only

from __future__ import annotations

import hashlib
import re
from pathlib import Path

import onnx
from onnx.external_data_helper import convert_model_from_external_data

LOCAL_PATH_PATTERN = re.compile(
    rb"(?i)(?:[a-z]:[\\/]+users[\\/]+|/home/[^/\x00]+/|"
    rb"/mnt/[a-z]/(?:users/)?[^/\x00]+/|/users/[^/\x00]+/)"
)


def strip_debug_metadata(message) -> None:
    """Recursively remove exporter stack traces and metadata from protobuf messages."""

    for field, value in list(message.ListFields()):
        if field.name in ("doc_string", "metadata_props"):
            message.ClearField(field.name)
        elif field.type == field.TYPE_MESSAGE:
            if field.is_repeated:
                for item in value:
                    strip_debug_metadata(item)
            else:
                strip_debug_metadata(value)


def make_self_contained(path: Path) -> None:
    model_proto = onnx.load(path, load_external_data=True)
    convert_model_from_external_data(model_proto)
    strip_debug_metadata(model_proto)
    onnx.save_model(model_proto, path)
    sidecar = path.with_suffix(path.suffix + ".data")
    if sidecar.exists():
        sidecar.unlink()
    onnx.checker.check_model(onnx.load(path))
    assert_no_local_paths(path)


def assert_no_local_paths(path: Path) -> None:
    if match := LOCAL_PATH_PATTERN.search(path.read_bytes()):
        snippet = match.group(0).decode("utf-8", errors="replace")
        raise RuntimeError(f"Generated artifact contains a local path: {snippet}")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

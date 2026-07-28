# SPDX-FileCopyrightText: 2026 Leo Huang
# SPDX-License-Identifier: GPL-3.0-only

from __future__ import annotations

import os
import tomllib
from copy import deepcopy
from dataclasses import dataclass
from pathlib import Path
from typing import Any

PROJECT_ROOT = Path(__file__).resolve().parent.parent
TRAINING_ROOT = PROJECT_ROOT / "training"
CONFIG_ROOT = TRAINING_ROOT / "configs"
LOCAL_CONFIG = TRAINING_ROOT / "config.local.toml"

PATH_ENVIRONMENT_VARIABLES = {
    "data_dir": "BRUSH_TRAINING_DATA_DIR",
    "checkpoint_dir": "BRUSH_TRAINING_CHECKPOINT_DIR",
    "export_dir": "BRUSH_TRAINING_EXPORT_DIR",
    "cache_dir": "BRUSH_TRAINING_CACHE_DIR",
}


class ConfigurationError(ValueError):
    """Raised when a training configuration is missing or malformed."""


@dataclass(frozen=True)
class ProjectPaths:
    project_root: Path
    training_root: Path
    data_dir: Path
    checkpoint_dir: Path
    export_dir: Path
    cache_dir: Path


@dataclass(frozen=True)
class TrainingConfig:
    values: dict[str, Any]
    paths: ProjectPaths
    sources: tuple[Path, ...]

    def section(self, name: str) -> dict[str, Any]:
        value = self.values.get(name, {})
        if not isinstance(value, dict):
            raise ConfigurationError(f"Configuration section [{name}] must be a table")
        return value

    def get(self, dotted_key: str, default: Any = None) -> Any:
        value: Any = self.values
        for part in dotted_key.split("."):
            if not isinstance(value, dict) or part not in value:
                return default
            value = value[part]
        return value


def _read_toml(path: Path, *, required: bool) -> dict[str, Any]:
    if not path.exists():
        if required:
            raise ConfigurationError(f"Configuration file does not exist: {path}")
        return {}
    with path.open("rb") as stream:
        return tomllib.load(stream)


def _deep_merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    merged = deepcopy(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = _deep_merge(merged[key], value)
        else:
            merged[key] = deepcopy(value)
    return merged


def _resolve_repository_path(value: str | Path) -> Path:
    expanded = Path(os.path.expandvars(str(value))).expanduser()
    return (
        expanded.resolve()
        if expanded.is_absolute()
        else (PROJECT_ROOT / expanded).resolve()
    )


def load_config(
    profile: str | None = None,
    explicit_config: Path | None = None,
) -> TrainingConfig:
    """Load tracked defaults, experiment profile, local overrides, and explicit overrides.

    Path environment variables are applied last. Script-specific command-line
    arguments are intentionally handled by each entry point after this function.
    """

    sources: list[Path] = []
    values: dict[str, Any] = {}
    candidates: list[tuple[Path, bool]] = [(CONFIG_ROOT / "default.toml", True)]
    if profile:
        candidates.append((CONFIG_ROOT / f"{profile}.toml", True))
    candidates.append((LOCAL_CONFIG, False))
    if explicit_config:
        candidates.append((explicit_config.resolve(), True))

    for path, required in candidates:
        loaded = _read_toml(path, required=required)
        if loaded:
            values = _deep_merge(values, loaded)
            sources.append(path)

    path_values = values.get("paths")
    if not isinstance(path_values, dict):
        raise ConfigurationError("Configuration must define a [paths] table")

    resolved: dict[str, Path] = {}
    for key, environment_name in PATH_ENVIRONMENT_VARIABLES.items():
        raw_value = os.environ.get(environment_name, path_values.get(key))
        if not raw_value:
            raise ConfigurationError(
                f"Missing paths.{key}; set it in TOML or {environment_name}"
            )
        resolved[key] = _resolve_repository_path(raw_value)

    paths = ProjectPaths(
        project_root=PROJECT_ROOT,
        training_root=TRAINING_ROOT,
        data_dir=resolved["data_dir"],
        checkpoint_dir=resolved["checkpoint_dir"],
        export_dir=resolved["export_dir"],
        cache_dir=resolved["cache_dir"],
    )
    return TrainingConfig(values=values, paths=paths, sources=tuple(sources))


def repository_relative(path: Path) -> str:
    """Return a portable display value without exposing an external parent path."""

    resolved = path.resolve()
    try:
        return resolved.relative_to(PROJECT_ROOT).as_posix()
    except ValueError:
        return f"<external>/{resolved.name}"

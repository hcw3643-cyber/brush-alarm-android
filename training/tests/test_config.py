# SPDX-FileCopyrightText: 2026 Leo Huang
# SPDX-License-Identifier: GPL-3.0-only

from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from training.config import PROJECT_ROOT, load_config, repository_relative


class ConfigTest(unittest.TestCase):
    def test_defaults_are_repository_relative(self) -> None:
        config = load_config("s3d")
        self.assertEqual(config.paths.data_dir, PROJECT_ROOT / "training/data")
        self.assertEqual(config.get("experiment.name"), "s3d-brush-binary")

    def test_explicit_config_and_environment_precedence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            explicit = root / "experiment.toml"
            explicit.write_text(
                '[paths]\ndata_dir = "from-explicit"\n[experiment]\nepochs = 3\n',
                encoding="utf-8",
            )
            with patch.dict(
                os.environ,
                {"BRUSH_TRAINING_DATA_DIR": str(root / "from-environment")},
            ):
                config = load_config("s3d", explicit)
        self.assertEqual(config.paths.data_dir, (root / "from-environment").resolve())
        self.assertEqual(config.get("experiment.epochs"), 3)

    def test_external_display_does_not_expose_parent_directories(self) -> None:
        self.assertEqual(
            repository_relative(Path("/private/example/video.mp4")),
            "<external>/video.mp4",
        )


if __name__ == "__main__":
    unittest.main()

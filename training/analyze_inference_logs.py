# SPDX-FileCopyrightText: 2026 Leo Huang
# SPDX-License-Identifier: GPL-3.0-only

from __future__ import annotations

import argparse
import csv
from pathlib import Path

import numpy as np


def percentile(values: list[float], points: list[int]) -> dict[int, float]:
    return {
        point: float(value)
        for point, value in zip(points, np.percentile(values, points))
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Summarize exported Brush Alarm inference CSV files."
    )
    parser.add_argument("logs", nargs="+", type=Path)
    args = parser.parse_args()

    rows: list[dict[str, str]] = []
    for path in args.logs:
        paths = sorted(path.glob("*.csv")) if path.is_dir() else [path]
        for item in paths:
            with item.open(newline="", encoding="utf-8") as stream:
                rows.extend(
                    row for row in csv.DictReader(stream) if row["event"] == "inference"
                )

    if not rows:
        raise SystemExit("No inference rows found")
    inference_ms = [float(row["inference_ms"]) for row in rows]
    sample_fps = [float(row["sample_fps"]) for row in rows]
    print("inference_count", len(rows))
    print("inference_ms", percentile(inference_ms, [50, 90, 95, 99]))
    print("sample_fps", percentile(sample_fps, [5, 50, 95]))

    labeled = [
        row for row in rows if row["ground_truth"] in {"brushing", "not_brushing"}
    ]
    if not labeled:
        print("No manually labeled rows; performance and threshold search skipped.")
        return

    for label in ("not_brushing", "brushing"):
        values = [
            float(row["confidence"]) for row in labeled if row["ground_truth"] == label
        ]
        if values:
            print(
                label,
                "count",
                len(values),
                "confidence",
                percentile(values, [1, 5, 10, 25, 50, 75, 90, 95, 99]),
            )

    best: tuple[float, float, float, float, float] | None = None
    for threshold in np.arange(0.05, 0.951, 0.01):
        predicted = [float(row["confidence"]) >= threshold for row in labeled]
        targets = [row["ground_truth"] == "brushing" for row in labeled]
        tp = sum(
            prediction and target for prediction, target in zip(predicted, targets)
        )
        fp = sum(
            prediction and not target for prediction, target in zip(predicted, targets)
        )
        fn = sum(
            not prediction and target for prediction, target in zip(predicted, targets)
        )
        precision = tp / max(1, tp + fp)
        recall = tp / max(1, tp + fn)
        f1 = 2 * precision * recall / max(1e-12, precision + recall)
        candidate = (f1, precision, recall, float(threshold), -fp)
        if best is None or candidate > best:
            best = candidate
    assert best is not None
    print(
        {
            "best_window_f1": best[0],
            "precision": best[1],
            "recall": best[2],
            "threshold": best[3],
            "false_positive_windows": int(-best[4]),
            "note": "Overlapping windows are correlated; use this as a calibration lead, not a release metric.",
        }
    )


if __name__ == "__main__":
    main()

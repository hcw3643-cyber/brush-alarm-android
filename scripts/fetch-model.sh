#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Leo Huang
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

repository="${BRUSH_ALARM_REPOSITORY:-hcw3643-cyber/brush-alarm-android}"
tag="${BRUSH_ALARM_MODEL_TAG:-model-v1.0.1}"
expected_sha256="505ab603651c0cd04aa06fafaef773b6a97f57a210308fdfa4752407af0e8eb5"
target="app/src/main/assets/brush_classifier.onnx"
url="https://github.com/${repository}/releases/download/${tag}/brush_classifier.onnx"

mkdir -p "$(dirname "$target")"
temporary="${target}.download"
trap 'rm -f "$temporary"' EXIT

curl --fail --location --retry 3 --output "$temporary" "$url"
actual_sha256="$(sha256sum "$temporary" | awk '{print $1}')"
if [[ "$actual_sha256" != "$expected_sha256" ]]; then
    echo "Model checksum mismatch: expected ${expected_sha256}, got ${actual_sha256}" >&2
    exit 1
fi

mv "$temporary" "$target"
trap - EXIT
echo "Installed verified model at ${target}"

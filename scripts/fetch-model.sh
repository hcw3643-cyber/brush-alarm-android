#!/usr/bin/env bash
set -euo pipefail

repository="${BRUSH_ALARM_REPOSITORY:-hcw3643-cyber/brush-alarm-android}"
tag="${BRUSH_ALARM_MODEL_TAG:-model-v1.0.0}"
expected_sha256="60142360e01f211a81d80a70c6aa92ea132044c054496472eb5bf7201283b9eb"
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

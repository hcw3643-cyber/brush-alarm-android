#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Leo Huang
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

forbidden_pattern='(\.apk$|\.aab$|\.apks$|\.idsig$|\.jks$|\.keystore$|\.p12$|\.pem$|\.key$|(^|/)local\.properties$|(^|/)\.env($|\.)|\.mp4$|\.mov$|\.avi$|\.mkv$|\.webm$|\.csv$|\.log$|\.pt$|\.pth$|\.ckpt$|brush_classifier\.onnx$|__pycache__|\.pyc$)'
tracked_forbidden="$(git ls-files | grep -E -i "$forbidden_pattern" || true)"
if [[ -n "$tracked_forbidden" ]]; then
    echo "Forbidden generated, private, or large files are tracked:" >&2
    echo "$tracked_forbidden" >&2
    exit 1
fi

secret_pattern='(BEGIN (RSA|OPENSSH|EC|DSA) PRIVATE KEY|AIza[0-9A-Za-z_-]{30,}|gh[pousr]_[0-9A-Za-z]{30,}|github_pat_[0-9A-Za-z_]{40,})'
secret_hits="$(git grep -n -I -E "$secret_pattern" -- . \
    ':(exclude)scripts/audit-public-tree.sh' || true)"
if [[ -n "$secret_hits" ]]; then
    echo "Possible secrets found in tracked text:" >&2
    echo "$secret_hits" >&2
    exit 1
fi

local_path_pattern='([A-Za-z]:[\\/]+Users[\\/]+[^\\/[:space:]]+[\\/]|/mnt/[a-z]/(Users/)?[^/[:space:]]+/|/home/[^/[:space:]]+/(\.venvs|Desktop|Documents|projects?)/|/Users/[^/[:space:]]+/)'
local_path_hits="$(git grep -n -I -E "$local_path_pattern" -- . \
    ':(exclude)scripts/audit-public-tree.sh' || true)"
if [[ -n "$local_path_hits" ]]; then
    echo "Machine-local absolute paths found in tracked text:" >&2
    echo "$local_path_hits" >&2
    exit 1
fi

model_sha256="505ab603651c0cd04aa06fafaef773b6a97f57a210308fdfa4752407af0e8eb5"
model_tag="model-v1.0.1"
for metadata_file in \
    scripts/fetch-model.sh \
    scripts/fetch-model.ps1 \
    docs/en/MODEL_CARD.md \
    docs/zh-CN/MODEL_CARD.zh-CN.md; do
    if ! grep -Fq "$model_sha256" "$metadata_file"; then
        echo "Current model SHA-256 is missing from ${metadata_file}" >&2
        exit 1
    fi
done
for metadata_file in \
    scripts/fetch-model.sh \
    scripts/fetch-model.ps1 \
    docs/en/MODEL_CARD.md \
    docs/zh-CN/MODEL_CARD.zh-CN.md; do
    if ! grep -Fq "$model_tag" "$metadata_file"; then
        echo "Current model tag is missing from ${metadata_file}" >&2
        exit 1
    fi
done

model_path="app/src/main/assets/brush_classifier.onnx"
if [[ -f "$model_path" ]]; then
    actual_model_sha256="$(sha256sum "$model_path" | awk '{print $1}')"
    if [[ "$actual_model_sha256" != "$model_sha256" ]]; then
        echo "Installed model checksum does not match public metadata." >&2
        exit 1
    fi
fi

declare -a mirrored_license_pairs=(
    "LICENSE|app/src/main/assets/licenses/BrushAlarm-GPL-3.0-only.txt"
    "docs/zh-CN/MODEL_LICENSE.zh-CN.md|app/src/main/assets/licenses/BrushAlarm-Model-License.md"
    "MODEL_LICENSE.md|app/src/main/assets/licenses/BrushAlarm-Model-License-EN.md"
    "docs/zh-CN/THIRD_PARTY_NOTICES.zh-CN.md|app/src/main/assets/licenses/BrushAlarm-Third-Party-Notices.md"
    "THIRD_PARTY_NOTICES.md|app/src/main/assets/licenses/BrushAlarm-Third-Party-Notices-EN.md"
)
for pair in "${mirrored_license_pairs[@]}"; do
    source_file="${pair%%|*}"
    packaged_file="${pair#*|}"
    if ! cmp -s "$source_file" "$packaged_file"; then
        echo "Packaged license copy is stale: ${packaged_file}" >&2
        exit 1
    fi
done

if git grep -n -F "BuildConfig.TEST_FEATURES" -- app/src >/dev/null; then
    echo "Legacy runtime-gated test feature code remains in app/src." >&2
    exit 1
fi
if git grep -n -E 'FileProvider|file_paths' -- app/src/main app/src/release >/dev/null; then
    echo "Release/shared sources still expose the debug log FileProvider." >&2
    exit 1
fi

for artifact in "$@"; do
    if [[ ! -f "$artifact" ]]; then
        echo "Artifact does not exist: $artifact" >&2
        exit 1
    fi
    artifact_hits="$(strings "$artifact" | grep -E -i "$local_path_pattern" || true)"
    if [[ -n "$artifact_hits" ]]; then
        echo "Machine-local absolute paths found in artifact: $artifact" >&2
        echo "$artifact_hits" >&2
        exit 1
    fi
done

echo "Public-tree audit passed."

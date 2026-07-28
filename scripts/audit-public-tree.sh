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

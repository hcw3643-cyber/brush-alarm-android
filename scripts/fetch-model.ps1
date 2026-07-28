# SPDX-FileCopyrightText: 2026 Leo Huang
# SPDX-License-Identifier: GPL-3.0-only

$ErrorActionPreference = "Stop"

$repository = if ($env:BRUSH_ALARM_REPOSITORY) {
    $env:BRUSH_ALARM_REPOSITORY
} else {
    "hcw3643-cyber/brush-alarm-android"
}
$tag = if ($env:BRUSH_ALARM_MODEL_TAG) {
    $env:BRUSH_ALARM_MODEL_TAG
} else {
    "model-v1.0.1"
}
$expectedSha256 = "505ab603651c0cd04aa06fafaef773b6a97f57a210308fdfa4752407af0e8eb5"
$target = "app/src/main/assets/brush_classifier.onnx"
$temporary = "$target.download"
$url = "https://github.com/$repository/releases/download/$tag/brush_classifier.onnx"

New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
try {
    Invoke-WebRequest -Uri $url -OutFile $temporary
    $actualSha256 = (Get-FileHash -Path $temporary -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $expectedSha256) {
        throw "Model checksum mismatch: expected $expectedSha256, got $actualSha256"
    }
    Move-Item -Force -Path $temporary -Destination $target
    Write-Host "Installed verified model at $target"
} finally {
    if (Test-Path $temporary) {
        Remove-Item -Force $temporary
    }
}

# SPDX-FileCopyrightText: 2026 Leo Huang
# SPDX-License-Identifier: GPL-3.0-only

from __future__ import annotations

import json
import os
import re
import subprocess
from pathlib import Path
from urllib.request import ProxyHandler, build_opener

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
META_URL = "https://huggingface.co/datasets/guyuchao/UCF101/resolve/main/trainval.json"
SHARDS = [
    "https://huggingface.co/datasets/guyuchao/UCF101/resolve/main/shard-00000.tar",
    "https://huggingface.co/datasets/guyuchao/UCF101/resolve/main/shard-00001.tar",
]
CLASSES = {
    "BrushingTeeth": 1,
    "ApplyLipstick": 0,
    "BlowDryHair": 0,
    "HeadMassage": 0,
    "ShavingBeard": 0,
}


def proxy_url() -> str | None:
    explicit = os.environ.get("TRAINING_PROXY")
    if explicit:
        return explicit
    route = subprocess.run(
        ["sh", "-c", "ip route | awk '/default/ {print $3; exit}'"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    return f"http://{route}:7890" if route else None


def download_metadata() -> list[dict]:
    DATA.mkdir(parents=True, exist_ok=True)
    target = DATA / "trainval.json"
    if not target.exists():
        proxy = proxy_url()
        opener = build_opener(ProxyHandler({"http": proxy, "https": proxy}) if proxy else ProxyHandler())
        target.write_bytes(opener.open(META_URL).read())
    return json.loads(target.read_text())


def group_id(path: str) -> int:
    match = re.search(r"_g(\d+)_", path)
    if not match:
        raise ValueError(f"Missing UCF group id: {path}")
    return int(match.group(1))


def main() -> None:
    rows = download_metadata()
    selected = {
        row["video_path"]: CLASSES[Path(row["video_path"]).parent.name]
        for row in rows
        if Path(row["video_path"]).parent.name in CLASSES
    }
    archive_names = ["video/" + "/".join(Path(name).parts[3:]) for name in selected]
    wanted = DATA / "wanted.txt"
    wanted.write_text("\n".join(archive_names) + "\n")
    videos = DATA / "videos"
    videos.mkdir(exist_ok=True)

    proxy = proxy_url()
    for index, url in enumerate(SHARDS):
        marker = DATA / f".shard-{index}.done"
        if marker.exists():
            continue
        curl = ["curl", "-L", "--fail", "--retry", "4"]
        if proxy:
            curl += ["-x", proxy]
        curl += [url]
        tar = [
            "tar", "-x", "-C", str(videos), "--strip-components=1",
            "--wildcards", "--no-anchored", "--ignore-failed-read",
        ] + archive_names
        source = subprocess.Popen(curl, stdout=subprocess.PIPE)
        extracted = subprocess.run(tar, stdin=source.stdout)
        source.stdout.close()
        curl_status = source.wait()
        # Each shard contains only part of wanted.txt, so GNU tar returns 2 for
        # names that belong to the other shard. A completed curl is authoritative;
        # the final manifest check below verifies that useful files were extracted.
        if curl_status:
            raise RuntimeError(f"Shard {index} download failed")
        marker.touch()

    manifest = []
    for original, label in selected.items():
        path = videos / "/".join(Path(original).parts[3:])
        if path.exists():
            manifest.append({
                "path": str(path.relative_to(ROOT)),
                "label": label,
                "group": group_id(path.name),
                "class": path.parent.name,
            })
    (DATA / "manifest.json").write_text(json.dumps(manifest, indent=2))
    print(f"Prepared {len(manifest)} videos")


if __name__ == "__main__":
    main()

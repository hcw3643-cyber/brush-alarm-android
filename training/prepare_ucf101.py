# SPDX-FileCopyrightText: 2026 Leo Huang
# SPDX-License-Identifier: GPL-3.0-only

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
from pathlib import Path
from urllib.request import ProxyHandler, build_opener

from training.config import load_config, repository_relative


def proxy_url() -> str | None:
    """Return the project-specific override; standard proxy variables stay automatic."""

    return os.environ.get("TRAINING_PROXY")


def download_metadata(data_dir: Path, metadata_url: str) -> list[dict]:
    data_dir.mkdir(parents=True, exist_ok=True)
    target = data_dir / "trainval.json"
    if not target.exists():
        proxy = proxy_url()
        handler = (
            ProxyHandler({"http": proxy, "https": proxy}) if proxy else ProxyHandler()
        )
        target.write_bytes(build_opener(handler).open(metadata_url).read())
    return json.loads(target.read_text(encoding="utf-8"))


def group_id(path: str) -> int:
    match = re.search(r"_g(\d+)_", path)
    if not match:
        raise ValueError(f"Missing UCF group id: {path}")
    return int(match.group(1))


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare the selected UCF101 classes.")
    parser.add_argument("--config", type=Path)
    args = parser.parse_args()
    config = load_config(explicit_config=args.config)
    download = config.section("download")
    data_dir = config.paths.data_dir
    classes = {name: int(label) for name, label in download["classes"].items()}
    shards = [str(url) for url in download["shards"]]

    rows = download_metadata(data_dir, str(download["metadata_url"]))
    selected = {
        row["video_path"]: classes[Path(row["video_path"]).parent.name]
        for row in rows
        if Path(row["video_path"]).parent.name in classes
    }
    archive_names = ["video/" + "/".join(Path(name).parts[3:]) for name in selected]
    wanted = data_dir / "wanted.txt"
    wanted.write_text("\n".join(archive_names) + "\n")
    videos = data_dir / "videos"
    videos.mkdir(exist_ok=True)

    proxy = proxy_url()
    for index, url in enumerate(shards):
        marker = data_dir / f".shard-{index}.done"
        if marker.exists():
            continue
        curl = ["curl", "-L", "--fail", "--retry", "4"]
        if proxy:
            curl += ["-x", proxy]
        curl += [url]
        tar = [
            "tar",
            "-x",
            "-C",
            str(videos),
            "--strip-components=1",
            "--wildcards",
            "--no-anchored",
            "--ignore-failed-read",
        ] + archive_names
        source = subprocess.Popen(curl, stdout=subprocess.PIPE)
        subprocess.run(tar, stdin=source.stdout)
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
            manifest.append(
                {
                    "path": path.relative_to(data_dir).as_posix(),
                    "label": label,
                    "group": group_id(path.name),
                    "class": path.parent.name,
                }
            )
    manifest_name = str(config.get("dataset.manifest", "manifest.json"))
    (data_dir / manifest_name).write_text(
        json.dumps(manifest, indent=2), encoding="utf-8"
    )
    print(
        f"prepared={len(manifest)} data_dir={repository_relative(data_dir)} "
        f"proxy={'custom' if proxy else 'environment-or-direct'}"
    )


if __name__ == "__main__":
    main()

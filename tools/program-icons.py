#!/usr/bin/env python3
"""Turns Simple Icons SVGs into the ic_program_<slug> vector drawables.

Usage: program-icons.py <simple-icons>/icons <slug>...
Get the icons with `npm pack simple-icons` and unpack the tarball.
"""
import pathlib
import re
import sys

OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res/drawable"

TEMPLATE = """<!-- {title}, from Simple Icons (CC0 1.0) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:pathData="{path}" />
</vector>
"""


def main(icons: pathlib.Path, slugs: list[str]) -> None:
    for slug in slugs:
        svg = (icons / f"{slug}.svg").read_text()
        if 'viewBox="0 0 24 24"' not in svg or svg.count("<path") != 1 or "fill-rule" in svg:
            sys.exit(f"{slug}: not a single-path 24x24 icon")
        title = re.search(r"<title>(.*?)</title>", svg).group(1)
        path = re.search(r'<path d="([^"]+)"', svg).group(1)
        name = "ic_program_" + slug.replace("-", "_")
        (OUT / f"{name}.xml").write_text(TEMPLATE.format(title=title.replace("--", "-"), path=path))
        print(name)


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    main(pathlib.Path(sys.argv[1]), sys.argv[2:])

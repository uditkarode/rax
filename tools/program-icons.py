#!/usr/bin/env python3
"""Turns 24x24 SVG logos into the ic_program_<name> vector drawables.

Usage: program-icons.py simple-icons|lobe-icons <icons-dir> <name>...

Sources:
  simple-icons  `npm pack simple-icons`, icons/<slug>.svg (CC0 1.0)
  lobe-icons    `npm pack @lobehub/icons-static-svg`, icons/<name>.svg, the
                monochrome files without -color or -text (MIT)
"""
import pathlib
import sys
import xml.etree.ElementTree as ET

OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res/drawable"
SVG = "{http://www.w3.org/2000/svg}"
CREDITS = {"simple-icons": "Simple Icons (CC0 1.0)", "lobe-icons": "Lobe Icons (MIT)"}


def convert(svg_file: pathlib.Path, credit: str) -> str:
    root = ET.parse(svg_file).getroot()
    if root.get("viewBox") != "0 0 24 24":
        raise ValueError("viewBox is not 0 0 24 24")
    title = root.findtext(f"{SVG}title") or svg_file.stem
    paths = []
    for el in root.iter():
        tag = el.tag.removeprefix(SVG)
        if tag in ("svg", "title"):
            continue
        if tag != "path" or "transform" in el.attrib or el.get("fill") not in (None, "currentColor"):
            raise ValueError(f"unsupported <{tag}>")
        rule = el.get("fill-rule", root.get("fill-rule", "nonzero"))
        fill_type = '\n        android:fillType="evenOdd"' if rule == "evenodd" else ""
        paths.append(f'    <path\n        android:fillColor="#FF000000"{fill_type}\n        android:pathData="{el.get("d")}" />')
    if not paths:
        raise ValueError("no paths")
    return (
        f"<!-- {title.replace('--', '-')}, from {credit} -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n'
        '    android:height="24dp"\n'
        '    android:viewportWidth="24"\n'
        '    android:viewportHeight="24">\n'
        + "\n".join(paths)
        + "\n</vector>\n"
    )


def main(source: str, icons: pathlib.Path, names: list[str]) -> None:
    credit = CREDITS[source]
    for name in names:
        try:
            xml = convert(icons / f"{name}.svg", credit)
        except ValueError as e:
            sys.exit(f"{name}: {e}")
        out = "ic_program_" + name.replace("-", "_")
        (OUT / f"{out}.xml").write_text(xml)
        print(out)


if __name__ == "__main__":
    if len(sys.argv) < 4 or sys.argv[1] not in CREDITS:
        sys.exit(__doc__)
    main(sys.argv[1], pathlib.Path(sys.argv[2]), sys.argv[3:])

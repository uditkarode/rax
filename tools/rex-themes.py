#!/usr/bin/env python3
"""Converts the themes that ship with Rex for macOS into the app's theme asset.

Rex keeps its themes in SuperDesignTokens' themes.json: the terminal colours, the 256-colour
palette it generates for each theme, and the interface colours its own windows are drawn in. The
app takes the same three, in sRGB, so a theme looks the same on the phone as on the desk.

    tools/rex-themes.py [path/to/themes.json]

Without a path it reads the copy inside Rex.app or Rex Beta.app in /Applications.
"""

import json
import math
import sys
from pathlib import Path

APPS = ["/Applications/Rex.app", "/Applications/Rex Beta.app"]
BUNDLE = "Contents/Resources/SuperUI_SuperDesignTokens.bundle/Contents/Resources/themes.json"
OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/themes/rex-themes.json"

# Interface colours, by the name the app uses, from Rex's sRGB CSS variables.
UI = {
    "canvas": "--super-bg",
    "surface": "--super-pane",
    "surfaceSubtle": "--super-sidebar",
    "surfaceRaised": "--super-raised",
    "stroke": "--super-border",
    "strokeStrong": "--super-border-strong",
    "control": "--super-control",
    "controlHover": "--super-hover",
    "controlPressed": "--super-selected",
    "controlOnOverlay": "--super-control-on-overlay",
    "content": "--super-text",
    "contentSecondary": "--super-text-secondary",
    "contentTertiary": "--super-text-tertiary",
    "accent": "--super-accent-solid",
    "accentOnSolid": "--super-accent-on-solid",
    "accentContainer": "--super-accent-container",
    "accentOnContainer": "--super-accent-on-container",
    "danger": "--super-danger-solid",
    "dangerOnSolid": "--super-danger-on-solid",
    "dangerContainer": "--super-danger-container",
    "success": "--super-success-solid",
    "successContainer": "--super-success-container",
    "warning": "--super-warning-solid",
    "warningContainer": "--super-warning-container",
    "info": "--super-info-solid",
    "infoContainer": "--super-info-container",
}

P3_TO_XYZ = [
    [0.4865709, 0.2656677, 0.1982173],
    [0.2289746, 0.6917385, 0.0792869],
    [0.0, 0.0451134, 1.0439444],
]
SRGB_TO_XYZ = [
    [0.4124564, 0.3575761, 0.1804375],
    [0.2126729, 0.7151522, 0.0721750],
    [0.0193339, 0.1191920, 0.9503041],
]
OKLAB_M1 = [
    [0.8189330101, 0.3618667424, -0.1288597137],
    [0.0329845436, 0.9293118715, 0.0361456387],
    [0.0482003018, 0.2643662691, 0.6338517070],
]
OKLAB_M2 = [
    [0.2104542553, 0.7936177850, -0.0040720468],
    [1.9779984951, -2.4285922050, 0.4505937099],
    [0.0259040371, 0.7827717662, -0.8086757660],
]

# Rex's P3 colours keep the lightness and hue of the sRGB ones and stretch the chroma towards the
# wider gamut's edge. Fitted against the sixteen colours each theme states in both spaces, this
# exponent on the ratio of the two gamuts' chroma limits undoes it to within a few steps.
CHROMA_EXPONENT = 0.85


def mul(m, v):
    return [sum(m[r][k] * v[k] for k in range(3)) for r in range(3)]


def inverse(m):
    a, b, c = m[0]
    d, e, f = m[1]
    g, h, i = m[2]
    det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    return [
        [(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det],
        [(f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det],
        [(d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det],
    ]


XYZ_TO_SRGB = inverse(SRGB_TO_XYZ)
XYZ_TO_P3 = inverse(P3_TO_XYZ)


def to_linear(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def to_gamma(c):
    c = min(1.0, max(0.0, c))
    return c * 12.92 if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055


def xyz_to_oklch(xyz):
    lms = [math.copysign(abs(v) ** (1 / 3), v) for v in mul(OKLAB_M1, xyz)]
    lightness, a, b = mul(OKLAB_M2, lms)
    return lightness, math.hypot(a, b), math.atan2(b, a)


def oklch_to_xyz(lightness, chroma, hue):
    lab = [lightness, chroma * math.cos(hue), chroma * math.sin(hue)]
    lms = [v ** 3 for v in mul(inverse(OKLAB_M2), lab)]
    return mul(inverse(OKLAB_M1), lms)


def max_chroma(lightness, hue, xyz_to_rgb):
    low, high = 0.0, 0.5
    for _ in range(40):
        mid = (low + high) / 2
        rgb = mul(xyz_to_rgb, oklch_to_xyz(lightness, mid, hue))
        if all(-1e-6 <= c <= 1 + 1e-6 for c in rgb):
            low = mid
        else:
            high = mid
    return low


def hex_rgb(r, g, b, a=1.0):
    channels = [round(r * 255), round(g * 255), round(b * 255)]
    text = "#" + "".join(f"{c:02x}" for c in channels)
    return text if a >= 1 else text + f"{round(a * 255):02x}"


def p3_to_hex(color):
    """One of Rex's P3 colours as the sRGB colour it would show on an sRGB screen."""
    if color.get("colorSpace") not in ("display-p3", None):
        raise ValueError(f"unexpected colour space {color.get('colorSpace')}")
    linear = [to_linear(color[k]) for k in ("red", "green", "blue")]
    lightness, chroma, hue = xyz_to_oklch(mul(P3_TO_XYZ, linear))
    if chroma > 1e-4:
        srgb_limit = max_chroma(lightness, hue, XYZ_TO_SRGB)
        p3_limit = max_chroma(lightness, hue, XYZ_TO_P3)
        chroma *= (srgb_limit / p3_limit) ** CHROMA_EXPONENT if p3_limit > 0 else 0
    srgb = [to_gamma(c) for c in mul(XYZ_TO_SRGB, oklch_to_xyz(lightness, chroma, hue))]
    return hex_rgb(*srgb, color.get("alpha", 1))


def css_to_hex(value):
    """Rex writes `r g b`, `rgb(r g b)` or `rgb(r g b / a)`."""
    v = value.strip()
    if v.startswith("#"):
        return v.lower()
    if v.startswith("rgb(") and v.endswith(")"):
        v = v[4:-1]
    rgb, _, alpha = v.partition("/")
    r, g, b = (int(x) for x in rgb.split())
    a = float(alpha) if alpha.strip() else 1.0
    return hex_rgb(r / 255, g / 255, b / 255, a)


def convert(theme):
    source = theme["source"]
    css = theme["cssVariables"]
    palette = [p3_to_hex(c) for c in theme["palette"]]
    if len(palette) != 256:
        raise ValueError(f"{theme['id']}: palette has {len(palette)} entries")
    # The first sixteen are the theme's own ANSI colours; Rex states those in sRGB directly.
    palette[:16] = [c.lower() for c in source["ansi"]]
    return {
        "id": theme["id"],
        "name": theme["name"],
        "dark": theme["appearance"] == "dark",
        "terminal": {
            "background": source["background"].lower(),
            "foreground": source["foreground"].lower(),
            "cursor": source["cursor"].lower(),
            "cursorText": source["cursorText"].lower(),
            "selectionBackground": source["selectionBackground"].lower(),
            "selectionForeground": source["selectionForeground"].lower(),
            "palette": palette,
        },
        "ui": {name: css_to_hex(css[var]) for name, var in UI.items()},
    }


def main():
    if len(sys.argv) > 1:
        path = Path(sys.argv[1])
    else:
        path = next((Path(a) / BUNDLE for a in APPS if (Path(a) / BUNDLE).exists()), None)
        if path is None:
            sys.exit("Rex.app not found in /Applications; pass the path to themes.json")
    themes = [convert(t) for t in json.loads(path.read_text())]
    themes.sort(key=lambda t: t["name"])
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(themes, separators=(",", ":")) + "\n")
    print(f"{len(themes)} themes -> {OUT.relative_to(Path.cwd()) if OUT.is_relative_to(Path.cwd()) else OUT}")


if __name__ == "__main__":
    main()

#!/bin/sh
# Builds app/src/main/res/font/google_sans_flex.ttf from the upstream Google Sans Flex variable font.
# The app uses only the weight and optical-size axes, so the others are pinned to their defaults,
# which takes the file from about 3.9 MB to about 0.4 MB.
#
#   tools/ui-font.sh path/to/upstream-google-sans-flex.ttf
#
# Needs fonttools (pip install fonttools).
set -eu
out="$(dirname "$0")/../app/src/main/res/font/google_sans_flex.ttf"
fonttools varLib.instancer "$1" wdth=100 ROND=0 slnt=0 wght=300:800 opsz=9:48 -o "$out"

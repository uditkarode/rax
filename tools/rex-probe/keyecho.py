"""Raw-mode echo program for inputs.py, mouse.py and paste.py: prints each read from the tty as hex.

Usage (as the program a probe starts in a terminal):
  python keyecho.py [--no-bracketed-paste]

Turns on bracketed paste (unless told not to), SGR mouse reporting with button
tracking, and focus events, then prints "IN <hex> <repr>" for every read.
Ctrl-D (a lone 0x04) ends it.
"""

import os
import sys
import tty

modes = b"\x1b[?1000h\x1b[?1002h\x1b[?1006h\x1b[?1004h"
if "--no-bracketed-paste" not in sys.argv:
    modes = b"\x1b[?2004h" + modes
tty.setraw(0)
os.write(1, modes + b"READY\r\n")
while True:
    b = os.read(0, 4096)
    os.write(1, ("IN " + b.hex() + " " + repr(b) + "\r\n").encode())
    if b == b"\x04":
        break

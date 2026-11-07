"""Show the bytes that mouse, wheel and focus events deliver, and which payloads the server rejects.

Usage:
  python mouse.py URL [ECHO_COMMAND...]

ECHO_COMMAND defaults to keyecho.py from this directory. Runs the echo program in a new session called rax-mouse, sends each payload
with block.event, prints the result and the bytes the program read, and
destroys the session. Point it only at a server you started for testing.
"""

import asyncio
import json
import os
import re
import sys

from rexwire import Control, DataStream, RexError

MOUSE = [
    {"action": "bogus"},
    {"action": "press", "button": "left", "x": 5, "y": 3, "mods": ""},
    {"action": "release", "button": "left", "x": 5, "y": 3, "mods": ""},
    {"action": "motion", "button": "left", "x": 6, "y": 3},
    {"action": "press", "button": "left", "pixel": {"x": 40, "y": 32}},
    {"action": "release", "button": "left", "pixel": {"x": 40, "y": 32}},
    {"action": "press", "button": "right", "x": 1, "y": 1},
    {"action": "release", "button": "right", "x": 1, "y": 1},
    {"action": "press", "button": "middle", "x": 1, "y": 1},
    {"action": "release", "button": "middle", "x": 1, "y": 1},
    {"action": "press", "button": "four", "x": 3, "y": 3},
    {"action": "press", "button": "back", "x": 3, "y": 3},
    {"action": "press", "button": "left", "x": 3, "y": 3, "mods": "ctrl"},
    {"action": "release", "button": "left", "x": 3, "y": 3, "mods": "ctrl"},
    {"action": "press", "button": "left", "x": 3, "y": 3, "buttons": {"left": True}},
    {"action": "release", "button": "left", "x": 3, "y": 3},
    {"action": "press", "button": "left", "x": 2.6, "y": 1.2},
    # Shift bypasses mouse reporting, so it goes last: later left events may be taken by the selection.
    {"action": "press", "button": "left", "x": 2, "y": 2, "mods": "shift"},
    {"action": "release", "button": "left", "x": 2, "y": 2, "mods": "shift"},
]
WHEEL = [
    {"delta_mode": "bogus"},
    {"delta_y": 1, "x": 3, "y": 3},
    {"delta_y": -3, "x": 3, "y": 3, "delta_mode": "line"},
    {"delta_y": 48, "x": 3, "y": 3, "delta_mode": "pixel"},
    {"delta_x": 1, "x": 3, "y": 3, "delta_mode": "line"},
]
FOCUS = [{"action": "in"}, {"action": "out"}, {"action": "bogus"}]


def keyecho(*flags):
    """Command line that runs keyecho.py from this directory with the current Python."""
    return [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "keyecho.py"), *flags]


async def main(url, cmd):
    c = Control(url)
    await c.connect()
    cr = await c.request("session.create", {"label": "rax-mouse", "initial_windows": [
        {"layout": {"block": {"flavor": "com.superlogical.terminal.shell", "options": {"shell": "none", "command": cmd}}}}]})
    sid, bid = cr["session_id"], cr["initial_windows"][0]["block_ids"][0]
    await c.request("session.attach", {"session_id": sid})
    tk = await c.request("data.connect", {"session_id": sid, "block_id": bid, "data_ref_id": "terminal",
                                          "subprotocol": "rex.data.terminal.v1"})
    d = DataStream(url)
    await d.hello(tk["ticket"])
    await d.read_for(1.5)

    async def ev(kind, payload):
        start = len(d.buf)
        try:
            res = await c.request("block.event", {"session_id": sid, "block_id": bid,
                                                  "event": {"type": kind, "payload": payload}})
        except RexError as e:
            res = e.error
        await d.read_for(0.3)
        got = re.findall(rb"IN [0-9a-f]+ (.*?)\r\n", bytes(d.buf[start:]))
        print(kind, json.dumps(payload), "->", json.dumps(res)[:240], [g.decode(errors="replace") for g in got])

    for p in MOUSE:
        await ev("mouse", p)
    for p in WHEEL:
        await ev("wheel", p)
    for p in FOCUS:
        await ev("focus", p)
    await c.request("session.destroy", {"session_id": sid})
    await c.close()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], sys.argv[2:] or keyecho()))

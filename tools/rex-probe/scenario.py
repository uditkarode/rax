"""Walk through what Rax does against an isolated Rex server and capture the traffic.

Usage:
  python scenario.py URL [CAPTURE.jsonl]

Connects, bootstraps, creates a session called rax-probe, opens its terminal
stream, takes the size, sets colors, types, and ends the shell so the stream
closes. Prints a trimmed transcript of every frame. Point it only at a server
you started for testing, see isolated-server.sh.
"""

import asyncio
import json
import sys

import ghostsnp
from rexwire import Capture, Control, DataStream

TERM = "com.superlogical.terminal"
SHELL = "com.superlogical.terminal.shell"


def key(code, text="", cp=0, mods=""):
    return {"key": code, "mods": mods, "consumed_mods": "", "text": text,
            "unshifted_codepoint": cp, "composing": False, "action": "press"}


async def main(url, cap_path=None):
    cap = Capture(cap_path)
    c = Control(url, cap, name="rax-probe")
    await c.connect()
    await c.request("session.bootstrap", {
        "event_scope": "all",
        "data_tickets": [{"creator_name": TERM, "data_ref_id": "terminal", "subprotocol": "rex.data.terminal.v1"}],
    })
    cr = await c.request("session.create", {
        "label": "rax-probe",
        "initial_windows": [{"window_label": "main", "layout": {"block": {"flavor": SHELL, "label": "sh"}}}],
    })
    sid, bid = cr["session_id"], cr["initial_windows"][0]["block_ids"][0]
    await c.request("session.attach", {"session_id": sid})
    tk = await c.request("data.connect", {"session_id": sid, "block_id": bid, "data_ref_id": "terminal",
                                          "subprotocol": "rex.data.terminal.v1"})
    d = DataStream(url, cap)
    await d.hello(tk["ticket"])
    await d.read_for(1.5)

    await c.block_call(sid, bid, "resize", {"columns": 60, "rows": 30, "cell_width_px": 21, "cell_height_px": 42})
    await c.block_call(sid, bid, "set_theme", {"foreground": "#d6deeb", "background": "#011627", "palette": ["1=#ef5350"]})
    await d.read_for(0.5)
    for ch in "echo $TERM":
        await c.request("client.key", {"session_id": sid, "block_id": bid,
                                       "key": key("unidentified", text=ch, cp=ord(ch))})
    await c.request("client.key", {"session_id": sid, "block_id": bid, "key": key("enter", cp=13)})
    await d.read_for(0.8)
    screen = await c.block_call(sid, bid, "format", {"format": "text", "trim": True})
    print("TERM seen by the shell:", [line for line in screen["content"].splitlines() if line.startswith(("xterm", "rex"))])
    vt = await c.block_call(sid, bid, "format", {"format": "vt", "extra_cursor": True, "extra_modes": True})
    print("format vt starts:", repr(vt["content"][:80]))
    await c.block_call(sid, bid, "write", {"data": "exit\n"})
    await d.read_for(1.5)
    await c.drain_events(0.3)

    version, records, end = ghostsnp.split(bytes(d.buf))
    print(f"stream: {len(d.buf)} bytes in {d.frames} frames; GHOSTSNP v{version} records "
          f"{[r[1] for r in records]} end at {end}; data close {getattr(d, 'closed', None)}")
    for m in d.rex_messages():
        print("in-band:", m["offset"], m["json"])
    tail = bytes(d.buf[end:])
    print("live bytes after snapshot (trimmed):", repr(tail[:120]), "...", repr(tail[-200:]))
    await c.close()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None))

"""Save a terminal block's data stream to a file.

Usage:
  python stream.py URL SESSION_LABEL OUT_FILE [SECONDS] [BLOCK_ID]

Attaches to the session, opens the block's rex.data.terminal.v1 stream (the
focused block of the active window unless BLOCK_ID is given), and writes every
stream byte to OUT_FILE. Prints the WELCOME fields and Rex's in-band messages.
"""

import asyncio
import sys

from rexwire import Control, DataStream


async def main(url, label, out, seconds=3.0, block_id=None):
    c = Control(url)
    await c.connect()
    sessions = (await c.request("session.list"))["sessions"]
    sid = next(s["session_id"] for s in sessions if s["label"] == label)
    view = (await c.request("session.attach", {"session_id": sid}))["view"]
    if not block_id:
        win = next(w for w in view["windows"] if w["window_id"] == view["active_window_id"])
        block_id = win["focused_block_id"]
    tk = await c.request("data.connect", {
        "session_id": sid, "block_id": block_id,
        "data_ref_id": "terminal", "subprotocol": "rex.data.terminal.v1",
    })
    d = DataStream(url)
    w = await d.hello(tk["ticket"])
    print("WELCOME", {k: (v.hex() if isinstance(v, bytes) else v) for k, v in w.items()})
    await d.read_for(seconds)
    open(out, "wb").write(bytes(d.buf))
    print(f"{len(d.buf)} bytes in {d.frames} frames written to {out}")
    for m in d.rex_messages():
        print("rex message at", m["offset"], m["json"])
    await d.close()
    await c.close()


if __name__ == "__main__":
    a = sys.argv[1:]
    asyncio.run(main(a[0], a[1], a[2], float(a[3]) if len(a) > 3 else 3.0, a[4] if len(a) > 4 else None))

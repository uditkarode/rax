"""Show how a paste event reaches a program with bracketed paste on and off.

Usage:
  python paste.py URL [--no-bracketed-paste]

Runs keyecho.py from this directory as the program that receives the paste.

Creates a session called rax-paste and destroys it at the end. Point it only
at a server you started for testing.
"""

import asyncio
import os
import re
import sys

from rexwire import Control, DataStream

TEXTS = ["one\ntwo", "a\r\nb", "x\x1b[201~y"]


def keyecho(*flags):
    """Command line that runs keyecho.py from this directory with the current Python."""
    return [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "keyecho.py"), *flags]


async def main(url, cmd):
    c = Control(url)
    await c.connect()
    cr = await c.request("session.create", {"label": "rax-paste", "initial_windows": [
        {"layout": {"block": {"flavor": "com.superlogical.terminal.shell", "options": {"shell": "none", "command": cmd}}}}]})
    sid, bid = cr["session_id"], cr["initial_windows"][0]["block_ids"][0]
    await c.request("session.attach", {"session_id": sid})
    tk = await c.request("data.connect", {"session_id": sid, "block_id": bid, "data_ref_id": "terminal",
                                          "subprotocol": "rex.data.terminal.v1"})
    d = DataStream(url)
    await d.hello(tk["ticket"])
    await d.read_for(1.5)
    for text in TEXTS:
        start = len(d.buf)
        await c.request("block.event", {"session_id": sid, "block_id": bid,
                                        "event": {"type": "paste", "payload": {"text": text}}})
        await d.read_for(0.3)
        print(repr(text), "->", [h.decode() for h in re.findall(rb"IN ([0-9a-f]+) ", bytes(d.buf[start:]))])
    await c.request("session.destroy", {"session_id": sid})
    await c.close()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], keyecho(*sys.argv[2:])))

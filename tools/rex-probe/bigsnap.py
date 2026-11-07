"""Show the GHOSTSNP record sequence for a terminal with long scrollback and the alternate screen on.

Usage:
  python bigsnap.py URL [OUT_FILE]

Creates a session called rax-big, prints 30 000 lines, switches to the
alternate screen, then opens a stream and walks its snapshot records. Saves the
stream to OUT_FILE (default /tmp/rexstream-big.bin) and destroys the session.
Point it only at a server you started for testing.
"""

import asyncio
import sys

import ghostsnp
from rexwire import Control, DataStream


async def main(url, out):
    c = Control(url)
    await c.connect()
    cr = await c.request("session.create", {"label": "rax-big", "initial_windows": [
        {"layout": {"block": {"flavor": "com.superlogical.terminal.shell"}}}]})
    sid, bid = cr["session_id"], cr["initial_windows"][0]["block_ids"][0]
    await c.request("session.attach", {"session_id": sid})
    await asyncio.sleep(1.0)
    await c.block_call(sid, bid, "write", {"data": "seq 1 30000; printf '\\033[?1049h\\033[2J\\033[HALT-SCREEN'\n"})
    await asyncio.sleep(2.0)
    print("screen:", (await c.block_call(sid, bid, "modes"))["screen"])
    tk = await c.request("data.connect", {"session_id": sid, "block_id": bid, "data_ref_id": "terminal",
                                          "subprotocol": "rex.data.terminal.v1"})
    d = DataStream(url)
    await d.hello(tk["ticket"])
    await d.read_for(3.0)
    open(out, "wb").write(bytes(d.buf))
    _, records, end = ghostsnp.split(bytes(d.buf))
    runs = []
    for _, tag, _, _ in records:
        if runs and runs[-1][0] == tag:
            runs[-1][1] += 1
        else:
            runs.append([tag, 1])
    print(f"{d.frames} messages, {len(d.buf)} bytes, snapshot ends at {end}")
    print("tags:", " ".join(f"{t}" if n == 1 else f"{t}x{n}" for t, n in runs))
    print("all CRC-32C ok:", all(r[3] for r in records))
    fmt = await c.block_call(sid, bid, "format", {"format": "text", "trim": True, "max_bytes": 2000})
    print("format text on the alternate screen:", {k: fmt.get(k) for k in ("id", "total", "eof")})
    await c.request("session.destroy", {"session_id": sid})
    await c.close()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "/tmp/rexstream-big.bin"))

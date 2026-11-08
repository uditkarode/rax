"""Show how a file upload reaches a terminal: upload_begin, then a rex.data.bytes.v1 data connection.

Usage:
  python upload.py URL

Runs keyecho.py from this directory as the program that receives the typed paths. Creates a
session called rax-upload and destroys it at the end. Point it only at a server you started for
testing.
"""

import asyncio
import os
import re
import sys
import uuid

import websockets

from rexwire import Control, DataStream, decode_ticket, parse_welcome
from paste import keyecho

BYTES_SUBPROTOCOL = "rex.data.bytes.v1"


async def upload(url, c, sid, bid, name, data, batch=None, index=None, count=None, send=None, chunk=65536):
    args = {"ref": f"upload:{uuid.uuid4()}", "name": name, "size": len(data)}
    if batch:
        args.update(batch=batch, index=index, count=count)
    r = await c.block_call(sid, bid, "upload_begin", args)
    print("  upload_begin ->", {k: v for k, v in r.items() if k != "ticket"})
    ws = await websockets.connect(url.replace("http", "ws", 1) + "/api/data", subprotocols=[BYTES_SUBPROTOCOL],
                                  compression=None, max_size=None)
    await ws.send(b"\x01" + decode_ticket(r["ticket"]))
    first = await ws.recv()
    print("  handshake ->", first[0:1].hex(), len(first), parse_welcome(first) if first[0] == 0x81 else first.hex())
    body = data if send is None else data[:send]
    for i in range(0, len(body), chunk):
        await ws.send(body[i:i + chunk])
    # An empty message ends the file. A close frame instead would stop the upload.
    await ws.send(b"")
    try:
        while True:
            m = await asyncio.wait_for(ws.recv(), 3)
            print("  server sent", len(m) if isinstance(m, bytes) else m)
    except asyncio.TimeoutError:
        print("  server sent nothing more within 3 s, connection open:", ws.state.name)
    except websockets.ConnectionClosed as e:
        print("  closed", e.rcvd.code if e.rcvd else None, e.rcvd.reason if e.rcvd else None)
    await ws.close()


async def main(url):
    c = Control(url)
    await c.connect()
    cr = await c.request("session.create", {"label": "rax-upload", "initial_windows": [
        {"layout": {"block": {"flavor": "com.superlogical.terminal.shell", "options": {"shell": "none", "command": keyecho()}}}}]})
    sid, bid = cr["session_id"], cr["initial_windows"][0]["block_ids"][0]
    await c.request("session.attach", {"session_id": sid})
    tk = await c.request("data.connect", {"session_id": sid, "block_id": bid, "data_ref_id": "terminal",
                                          "subprotocol": "rex.data.terminal.v1"})
    d = DataStream(url)
    await d.hello(tk["ticket"])
    await d.read_for(1.0)

    async def typed():
        start = len(d.buf)
        await d.read_for(1.0)
        got = b"".join(bytes.fromhex(h.decode()) for h in re.findall(rb"IN ([0-9a-f]+) ", bytes(d.buf[start:])))
        print("  typed:", got)
        events = await c.drain_events(0.2)
        for e in events:
            print("  event:", e.get("event", e))

    print("one file:")
    await upload(url, c, sid, bid, "photo one.png", os.urandom(200_000))
    await typed()
    print("a batch of two:")
    batch = str(uuid.uuid4())
    await upload(url, c, sid, bid, "a.txt", b"hello\n", batch, 0, 2)
    await typed()
    await upload(url, c, sid, bid, "b.txt", b"world\n", batch, 1, 2)
    await typed()
    print("too few bytes, then the end:")
    await upload(url, c, sid, bid, "short.bin", os.urandom(1000), send=500)
    await typed()
    print("one message of 200 KB:")
    await upload(url, c, sid, bid, "big.bin", os.urandom(200_000), chunk=200_000)
    await typed()

    await c.request("session.destroy", {"session_id": sid})
    await c.close()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1]))

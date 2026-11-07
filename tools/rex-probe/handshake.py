"""Check how the control connection handshake behaves at its edges.

Usage:
  python handshake.py URL

Covers revision negotiation, protocol errors and their close codes, the
Origin and Host headers, compression, client info in the hello, and the plain
HTTP endpoints. Creates nothing.
"""

import asyncio
import base64
import json
import os
import socket
import sys
import urllib.parse
import urllib.request

import websockets


async def ws(url, path="/api/control", subprotocols=("rex.control.v1",), **kw):
    return await websockets.connect(url.replace("http", "ws", 1) + path,
                                    subprotocols=list(subprotocols) if subprotocols else None,
                                    max_size=None, **kw)


async def closed_with(conn, send=None, wait=7.0):
    try:
        if send is not None:
            await conn.send(send)
        while True:
            msg = await asyncio.wait_for(conn.recv(), wait)
            print("     got:", msg[:200] if isinstance(msg, str) else msg)
    except websockets.ConnectionClosed as e:
        return (e.rcvd.code, e.rcvd.reason) if e.rcvd else ("no close frame", None)
    except asyncio.TimeoutError:
        return "still open"


def hello(**kw):
    h = {"type": "hello", "api_revision": 11, "client": {"kind": "other", "name": "handshake-probe"}}
    h.update(kw)
    return json.dumps(h)


async def main(url):
    print("1. older client: api_revision 5")
    c = await ws(url, compression=None)
    await c.send(hello(api_revision=5))
    print("   ", await c.recv())
    await c.close()

    print("2. client newer than server: api_min_revision 12, api_revision 13")
    c = await ws(url, compression=None)
    print("   ", await closed_with(c, hello(api_revision=13, api_min_revision=12)))

    print("3. first message is a request, not a hello")
    c = await ws(url, compression=None)
    print("   ", await closed_with(c, json.dumps({"type": "request", "id": "1", "method": "server.status"})))

    print("4. no subprotocol")
    try:
        c = await ws(url, subprotocols=None, compression=None)
        print("   ", await closed_with(c, hello()))
    except Exception as e:
        print("    refused:", e)

    print("5. binary message after hello")
    c = await ws(url, compression=None)
    await c.send(hello())
    await c.recv()
    print("   ", await closed_with(c, b"\x00\x01"))

    print("6. two pending requests with the same id")
    c = await ws(url, compression=None)
    await c.send(hello())
    await c.recv()
    await c.send(json.dumps({"type": "request", "id": "dup", "method": "session.list"}))
    await c.send(json.dumps({"type": "request", "id": "dup", "method": "session.list"}))
    print("   ", await closed_with(c, None, wait=2))

    print("7. unknown message type from the client is skipped")
    c = await ws(url, compression=None)
    await c.send(hello())
    await c.recv()
    await c.send(json.dumps({"type": "something_new"}))
    await c.send(json.dumps({"type": "request", "id": "1", "method": "server.version"}))
    print("    ", (await c.recv())[:200])
    await c.close()

    print("8. no hello for 5 seconds")
    c = await ws(url, compression=None)
    print("   ", await closed_with(c, None, wait=8))

    print("9. Origin headers")
    for origin in ["http://evil.example", url]:
        try:
            c = await ws(url, compression=None, origin=origin)
            await c.send(hello())
            print(f"    Origin {origin}: accepted ->", (await c.recv())[:60])
            await c.close()
        except Exception as e:
            print(f"    Origin {origin}: refused ->", e)

    print("9b. raw upgrades with the Android emulator's Host header")
    u = urllib.parse.urlparse(url)
    for host_hdr, origin in [("10.0.2.2:7297", None), ("10.0.2.2:7297", "http://10.0.2.2:7297"),
                             ("10.0.2.2:7297", "http://evil.example"), (u.netloc, "http://10.0.2.2:7297")]:
        s = socket.create_connection((u.hostname, u.port or 80))
        req = (f"GET /api/control HTTP/1.1\r\nHost: {host_hdr}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               f"Sec-WebSocket-Key: {base64.b64encode(os.urandom(16)).decode()}\r\nSec-WebSocket-Version: 13\r\n"
               f"Sec-WebSocket-Protocol: rex.control.v1\r\n" + (f"Origin: {origin}\r\n" if origin else "") + "\r\n")
        s.sendall(req.encode())
        print(f"    Host {host_hdr}, Origin {origin}:", s.recv(4096).split(b"\r\n")[0].decode())
        s.close()

    print("9c. client info: platform android, and a hello over 512 bytes")
    c = await ws(url, compression=None)
    await c.send(hello(client={"kind": "app", "platform": "android", "name": "Rax", "version": "0.1.0", "label": "pixel"}))
    me = json.loads(await c.recv())["client"]["id"]
    await c.send(json.dumps({"type": "request", "id": "1", "method": "client.inspect", "payload": {"client_id": me}}))
    print("    stored info:", json.dumps(json.loads(await c.recv())["payload"]["client"]["info"]))
    await c.close()
    c = await ws(url, compression=None)
    print("    600-byte name:", (await closed_with(c, hello(client={"name": "x" * 600}), wait=1)))

    print("10. permessage-deflate offered by the client")
    c = await ws(url)  # websockets offers permessage-deflate by default
    print("    Sec-WebSocket-Extensions:", c.response.headers.get("Sec-WebSocket-Extensions"))
    await c.close()

    print("11. HTTP endpoints over this listener")
    for path in ["/api/help", "/llms.txt", "/api/control", "/nope"]:
        try:
            with urllib.request.urlopen(url + path, timeout=5) as r:
                print(f"    GET {path}: {r.status} {r.headers.get('Content-Type')} {len(r.read())} bytes")
        except urllib.error.HTTPError as e:
            print(f"    GET {path}: {e.code} {e.read()[:120]!r}")


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1]))

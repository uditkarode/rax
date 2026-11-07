"""Minimal Rex wire client used by the probe scripts.

Control connection: WebSocket /api/control, subprotocol rex.control.v1, JSON text frames.
Data connection: WebSocket /api/data, subprotocol rex.data.terminal.v1, binary frames.

Every frame is appended to a JSONL capture file when one is given, so real
exchanges can be quoted in docs/rex-protocol.md.
"""

import asyncio
import base64
import json
import re
import struct
import time

import websockets

API_REVISION = 11
CONTROL_SUBPROTOCOL = "rex.control.v1"
TERMINAL_SUBPROTOCOL = "rex.data.terminal.v1"


class Capture:
    def __init__(self, path=None):
        self.f = open(path, "a") if path else None
        self.t0 = time.monotonic()

    def log(self, chan, direction, payload):
        if not self.f:
            return
        rec = {"t": round(time.monotonic() - self.t0, 4), "chan": chan, "dir": direction}
        if isinstance(payload, (bytes, bytearray)):
            rec["bin_len"] = len(payload)
            rec["bin_hex"] = bytes(payload[:96]).hex()
        else:
            rec["text"] = payload
        self.f.write(json.dumps(rec) + "\n")
        self.f.flush()


class RexError(Exception):
    def __init__(self, error):
        super().__init__(f"{error.get('code')} ({error.get('status')}): {error.get('message')}")
        self.error = error


class Control:
    """One control connection. Responses are matched to requests by id."""

    def __init__(self, base_url, capture=None, name="rax-probe", reconnect_token=None):
        self.base_url = base_url.rstrip("/")
        self.cap = capture or Capture()
        self.name = name
        self.reconnect_token = reconnect_token
        self.ws = None
        self.welcome = None
        self.events = asyncio.Queue()
        self._pending = {}
        self._next = 0
        self._reader = None

    async def connect(self, extra_hello=None):
        url = self.base_url.replace("http", "ws", 1) + "/api/control"
        self.ws = await websockets.connect(
            url, subprotocols=[CONTROL_SUBPROTOCOL], compression=None, max_size=None
        )
        self.response_headers = dict(self.ws.response.headers)
        hello = {
            "type": "hello",
            "api_revision": API_REVISION,
            "client": {"kind": "other", "platform": "other", "name": self.name, "version": "0.0.1"},
        }
        if self.reconnect_token:
            hello["reconnect_token"] = self.reconnect_token
        if extra_hello:
            hello.update(extra_hello)
        await self._send(hello)
        raw = await self.ws.recv()
        self.cap.log("control", "recv", raw)
        self.welcome = json.loads(raw)
        client = self.welcome.get("client") or {}
        self.reconnect_token = client.get("reconnect_token")
        self._reader = asyncio.create_task(self._read())
        return self.welcome

    async def _send(self, obj):
        text = json.dumps(obj)
        self.cap.log("control", "send", text)
        await self.ws.send(text)

    async def _read(self):
        try:
            async for raw in self.ws:
                self.cap.log("control", "recv", raw)
                msg = json.loads(raw)
                if msg.get("type") == "response":
                    fut = self._pending.pop(msg.get("id"), None)
                    if fut and not fut.done():
                        fut.set_result(msg)
                elif msg.get("type") == "event":
                    await self.events.put(msg)
        except websockets.ConnectionClosed as e:
            self.closed = (e.rcvd.code if e.rcvd else None, e.rcvd.reason if e.rcvd else None)
        finally:
            for fut in self._pending.values():
                if not fut.done():
                    fut.set_exception(ConnectionError("control connection closed"))

    async def request(self, method, payload=None, raw=False):
        self._next += 1
        rid = f"r{self._next}"
        fut = asyncio.get_running_loop().create_future()
        self._pending[rid] = fut
        msg = {"type": "request", "id": rid, "method": method}
        if payload is not None:
            msg["payload"] = payload
        await self._send(msg)
        resp = await fut
        if raw:
            return resp
        if "error" in resp and resp["error"]:
            raise RexError(resp["error"])
        return resp.get("payload")

    async def block_call(self, session_id, block_id, method, args=None, creator="com.superlogical.terminal"):
        payload = {"session_id": session_id, "block_id": block_id}
        if args is not None:
            payload["args"] = args
        return await self.request(f"{creator}.{method}", payload)

    async def drain_events(self, seconds):
        out = []
        end = time.monotonic() + seconds
        while True:
            left = end - time.monotonic()
            if left <= 0:
                return out
            try:
                out.append(await asyncio.wait_for(self.events.get(), left))
            except asyncio.TimeoutError:
                return out

    async def close(self, code=1000):
        if self.ws:
            await self.ws.close(code=code)
        if self._reader:
            await asyncio.gather(self._reader, return_exceptions=True)

    async def drop(self):
        """Drop the TCP connection without a close frame, like a phone losing its network."""
        self.ws.transport.abort()
        if self._reader:
            await asyncio.gather(self._reader, return_exceptions=True)


# Data connection handshake (all integers little-endian).
HELLO = 0x01
RESUME = 0x02
WELCOME = 0x81
REJECT = 0x82


def decode_ticket(ticket):
    return base64.urlsafe_b64decode(ticket + "=" * (-len(ticket) % 4))


def parse_welcome(b):
    if len(b) != 32 or b[0] != WELCOME:
        raise ValueError(f"not a WELCOME: {bytes(b).hex()}")
    token = bytes(b[1:17])
    pad = bytes(b[17:20])
    keep_ms = struct.unpack("<I", b[20:24])[0]
    nonce = struct.unpack("<Q", b[24:32])[0]
    return {"token": token, "pad": pad.hex(), "resume_window_ms": keep_ms, "nonce": nonce}


class DataStream:
    """One data connection for one block. Collects the stream bytes in self.buf."""

    def __init__(self, base_url, capture=None, subprotocol=TERMINAL_SUBPROTOCOL):
        self.base_url = base_url.rstrip("/")
        self.cap = capture or Capture()
        self.subprotocol = subprotocol
        self.buf = bytearray()
        self.welcome = None
        self.ws = None
        self.frames = 0

    async def _open(self, first):
        url = self.base_url.replace("http", "ws", 1) + "/api/data"
        self.ws = await websockets.connect(
            url, subprotocols=[self.subprotocol], compression=None, max_size=None
        )
        self.response_headers = dict(self.ws.response.headers)
        self.cap.log("data", "send", first)
        await self.ws.send(first)
        reply = await self.ws.recv()
        self.cap.log("data", "recv", reply)
        if reply[0] == REJECT:
            reason = reply[1] if len(reply) > 1 else None
            return {"reject": reason}
        self.welcome = parse_welcome(reply)
        return self.welcome

    async def hello(self, ticket):
        return await self._open(bytes([HELLO]) + decode_ticket(ticket))

    async def resume(self, token, received):
        return await self._open(bytes([RESUME]) + token + struct.pack("<Q", received))

    async def read_for(self, seconds):
        end = time.monotonic() + seconds
        while True:
            left = end - time.monotonic()
            if left <= 0:
                return
            try:
                chunk = await asyncio.wait_for(self.ws.recv(), left)
            except asyncio.TimeoutError:
                return
            except websockets.ConnectionClosed as e:
                self.closed = (e.rcvd.code if e.rcvd else None, e.rcvd.reason if e.rcvd else None)
                return
            self.frames += 1
            self.cap.log("data", "recv", chunk)
            self.buf.extend(chunk)

    def rex_messages(self):
        """Rex's own in-band messages: ESC _ rex1;<nonce>;<json> ESC \\ with the WELCOME nonce."""
        out = []
        for m in re.finditer(rb"\x1b_rex1;(\d+);(.*?)\x1b\\", bytes(self.buf), re.S):
            nonce = int(m.group(1))
            ok = self.welcome and nonce == self.welcome["nonce"]
            out.append({"offset": m.start(), "nonce_ok": ok, "json": json.loads(m.group(2))})
        return out

    async def close(self, code=1000):
        await self.ws.close(code=code)

    def drop(self):
        self.ws.transport.abort()

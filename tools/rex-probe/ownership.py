"""Two clients share one terminal: who sets its size, and what a dropped phone gets back.

Usage:
  python ownership.py URL [CAPTURE.jsonl]

Client A plays the desktop app, client B plays the phone. Creates a session
called rax-owner and destroys it at the end. Point it only at a server you
started for testing.
"""

import asyncio
import json
import sys

from rexwire import Capture, Control, DataStream

SHELL = "com.superlogical.terminal.shell"


def names(events):
    out = []
    for e in events:
        p = e["payload"]
        if p["type"] == "session_event":
            inner = p["payload"]["event"]
            if inner["type"] == "block_event":
                out.append(f'{e["seq"]}:block_event:{inner["payload"]["name"]} {json.dumps(inner["payload"].get("payload"))[:160]}')
            else:
                out.append(f'{e["seq"]}:{inner["type"]} {json.dumps(inner.get("payload"))[:160]}')
        else:
            out.append(f'{e["seq"]}:{p["type"]}')
    return out


async def size(c, sid, bid):
    s = await c.block_call(sid, bid, "size")
    return {"cols": s["columns"], "rows": s["rows"], "owner": s["owner"],
            "desired": [(x["client_id"][-4:], x["columns"], x["rows"]) for x in s["desired"]]}


async def open_stream(c, url, cap, sid, bid):
    tk = await c.request("data.connect", {"session_id": sid, "block_id": bid, "data_ref_id": "terminal",
                                          "subprotocol": "rex.data.terminal.v1"})
    d = DataStream(url, cap)
    await d.hello(tk["ticket"])
    await d.read_for(0.8)
    return d


async def main(url, cap_path=None):
    cap = Capture(cap_path)
    a = Control(url, cap, name="desktop-A")
    await a.connect()
    cr = await a.request("session.create", {"label": "rax-owner",
                                            "initial_windows": [{"layout": {"block": {"flavor": SHELL}}}]})
    sid, bid = cr["session_id"], cr["initial_windows"][0]["block_ids"][0]
    await a.request("session.attach", {"session_id": sid})
    da = await open_stream(a, url, cap, sid, bid)
    await a.block_call(sid, bid, "resize", {"columns": 120, "rows": 40, "cell_width_px": 9, "cell_height_px": 18})
    print("A ids", a.welcome["client"]["id"][-4:])
    print("after A resize 120x40:", await size(a, sid, bid))

    b = Control(url, cap, name="phone-B")
    await b.connect()
    boot = await b.request("session.bootstrap", {"event_scope": "session", "attach_session_ids": [sid]})
    print("B ids", b.welcome["client"]["id"][-4:], "attached:", [s["session"]["label"] for s in boot["attached_sessions"]])
    db = await open_stream(b, url, cap, sid, bid)
    print("B stream first resize message:", [m["json"] for m in db.rex_messages()][:1])

    await b.block_call(sid, bid, "resize", {"columns": 50, "rows": 20})
    await asyncio.sleep(0.3)
    print("after B resize 50x20 (B not owner):", await size(b, sid, bid))

    r = await b.request("session.own_block", {"session_id": sid, "block_ids": [bid], "mode": "take"})
    await asyncio.sleep(0.4)
    print("B own_block take ->", r, "| size:", await size(b, sid, bid))

    await b.request("session.disown_block", {"session_id": sid, "block_ids": [bid]})
    await asyncio.sleep(0.4)
    print("B disown ->", await size(b, sid, bid))

    r = await b.request("session.own_block", {"session_id": sid, "block_ids": [bid], "mode": "request"})
    print("B own_block request while A owns ->", r)

    await b.request("session.own_block", {"session_id": sid, "block_ids": [bid], "mode": "take"})
    await asyncio.sleep(0.3)
    print("B takes again ->", await size(a, sid, bid))

    # Phone loses its network: no close frame on either connection.
    token = b.reconnect_token
    received = len(db.buf)
    data_token = db.welcome["token"]
    db.drop()
    await b.drop()
    await asyncio.sleep(1.0)
    print("after B drops:", await size(a, sid, bid))
    await a.block_call(sid, bid, "write", {"data": "echo while-phone-away\n"})
    await asyncio.sleep(0.6)

    b2 = Control(url, cap, name="phone-B", reconnect_token=token)
    w = await b2.connect()
    print("B reconnect welcome client:", {k: v for k, v in w["client"].items() if k != "reconnect_token"})
    await asyncio.sleep(0.5)
    print("after B resumes:", await size(a, sid, bid))
    d2 = DataStream(url, cap)
    w2 = await d2.resume(data_token, received)
    print("data RESUME ->", {k: (v.hex() if isinstance(v, bytes) else v) for k, v in w2.items()},
          "same nonce:", w2.get("nonce") == db.welcome["nonce"])
    await d2.read_for(0.8)
    print("resumed bytes:", repr(bytes(d2.buf[:300])))

    print("\nA events:")
    for n in names(await a.drain_events(0.3)):
        print("  ", n)
    print("B2 events (new connection, seq restarts):")
    for n in names(await b2.drain_events(0.3)):
        print("  ", n)

    print("A stream rex messages:", [m["json"] for m in da.rex_messages()])
    await a.request("session.destroy", {"session_id": sid})
    await asyncio.sleep(0.3)
    await d2.read_for(0.5)
    print("B2 data stream close after destroy:", getattr(d2, "closed", None))
    await a.close()
    await b2.close()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None))

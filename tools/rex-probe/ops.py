"""Exercise the session, tab and pane operations Rax uses, the attention events, and error shapes.

Usage:
  python ops.py URL [CAPTURE.jsonl]

Creates a session called rax-ops and destroys it at the end. Prints each call,
its result or error, and the events that followed it. Point it only at a
server you started for testing.
"""

import asyncio
import json
import sys

from rexwire import Capture, Control, RexError

SHELL = "com.superlogical.terminal.shell"
TERM = "com.superlogical.terminal"


def fmt_events(events):
    out = []
    for e in events:
        p = e["payload"]
        if p["type"] == "session_event":
            inner = p["payload"]["event"]
            if inner["type"] == "block_event":
                bp = inner["payload"]
                out.append(f'      #{e["seq"]} block_event {bp["name"]} {json.dumps(bp.get("payload"))[:220]}')
            else:
                out.append(f'      #{e["seq"]} {inner["type"]} {json.dumps(inner.get("payload"))[:220]}')
        else:
            out.append(f'      #{e["seq"]} {p["type"]} {json.dumps(p.get("payload"))[:220]}')
    return "\n".join(out)


async def main(url, cap_path=None):
    c = Control(url, Capture(cap_path), name="ops-probe")
    await c.connect()

    async def call(method, payload=None, wait=0.4, show_events=True):
        try:
            res = await c.request(method, payload)
            line = json.dumps(res)
        except RexError as e:
            res, line = None, "ERROR " + json.dumps(e.error)
        print(f"\n> {method} {json.dumps(payload)[:200] if payload is not None else ''}\n  = {line[:600]}")
        evs = await c.drain_events(wait)
        if show_events and evs:
            print(fmt_events(evs))
        return res

    print("--- errors before attaching")
    await call("no.such_method")
    await call("session.view", {"session_id": "session:00000000-0000-0000-0000-000000000000"})
    await call("session.create", {"label": 5})

    cr = await call("session.create", {"label": "rax-ops", "initial_windows": [
        {"window_label": "one", "layout": {"block": {"flavor": SHELL}}}]})
    sid = cr["session_id"]
    w1, b1 = cr["initial_windows"][0]["window_id"], cr["initial_windows"][0]["block_ids"][0]
    await call("session.view", {"session_id": sid})
    print("--- attach (bootstrap with event_scope all and data tickets)")
    await call("session.bootstrap", {"event_scope": "all", "attach_session_ids": [sid],
                                     "data_tickets": [{"creator_name": TERM, "data_ref_id": "terminal",
                                                       "subprotocol": "rex.data.terminal.v1"}]}, wait=1.2)

    print("--- tabs")
    nw = await call("session.new_window", {"session_id": sid, "window_label": "two",
                                           "layout": {"block": {"flavor": SHELL, "options": {"cwd": "/tmp"}}},
                                           "focus": False}, wait=1.0)
    w2, b2 = nw["window_id"], nw["block_ids"][0]
    await call("session.set_window_label", {"session_id": sid, "window_id": w2, "label": "renamed-two"})
    await call("session.focus_window", {"session_id": sid, "window_id": w2})

    print("--- panes")
    sp = await call("session.new_split", {"session_id": sid, "anchor_id": b2, "direction": "horizontal",
                                          "side": "after", "ratio": 0.6,
                                          "layout": {"block": {"flavor": SHELL, "label": "right"}}, "focus": True},
                    wait=1.0)
    b3 = sp["block_ids"][0]
    await call("session.set_block_label", {"session_id": sid, "block_id": b3, "label": "logs"})
    await call("session.focus_block", {"session_id": sid, "block_id": b2})
    await call("session.resize_pane", {"session_id": sid, "direction": "horizontal",
                                       "before_block_id": b2, "after_block_id": b3, "ratio": 0.3})
    await call("session.zoom_block", {"session_id": sid, "block_id": b3, "zoom": True})
    v = await call("session.view", {"session_id": sid}, show_events=False)

    print("--- attention: bell, OSC 9 / 777 notifications, OSC 7501 status, OSC 9;4 progress, OSC 52")
    status = "printf '\\033]7501;state=blocked:kind=question:app=claude-code:title=VGFzaw==:msg=TmVlZHMgaW5wdXQ=\\033\\\\'"
    await call(f"{TERM}.write", {"session_id": sid, "block_id": b3, "args": {"data":
               "printf '\\a'; printf '\\033]9;hello from osc9\\a'; printf '\\033]777;notify;Title;Body\\a'; "
               "printf '\\033]9;4;1;40\\a'; printf '\\033]52;c;Y29weQ==\\a'; " + status + "\n"}}, wait=1.5)
    await call(f"{TERM}.program_status", {"session_id": sid, "block_id": b3})
    await call(f"{TERM}.write", {"session_id": sid, "block_id": b3, "args": {"data":
               "printf '\\033]7501;state=working:app=claude-code:progress=30\\033\\\\'\n"}}, wait=1.0)
    await call(f"{TERM}.write", {"session_id": sid, "block_id": b3, "args": {"data": "sleep 0.2\n"}}, wait=1.5)
    await call(f"{TERM}.write", {"session_id": sid, "block_id": b3, "args": {"data": "vim -u NONE -c 'set notitle'\n"}}, wait=1.5)
    await call(f"{TERM}.modes", {"session_id": sid, "block_id": b3}, show_events=False)
    await call(f"{TERM}.write", {"session_id": sid, "block_id": b3, "args": {"data": "\x1b:q!\r"}}, wait=1.0)

    print("--- closing")
    await call("block.close", {"session_id": sid, "block_id": b3})
    await call("session.close_window", {"session_id": sid, "window_id": w2})
    await call("session.set_label", {"session_id": sid, "label": "rax-ops-renamed"})
    await call(f"{TERM}.write", {"session_id": sid, "block_id": b1, "args": {"data": "exit 3\n"}}, wait=1.5)
    await call("session.list")
    await call("session.destroy", {"session_id": sid})
    await c.close()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None))

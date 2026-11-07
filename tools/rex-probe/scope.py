"""Show what a connection that did not session.attach can do and hear, per event_scope.

Usage:
  python scope.py URL

A "maker" client creates a session called rax-scope. For event_scope "session"
and "all", a second client bootstraps without attaching, then reads the view
and program status, tries data.connect, and lists the events it hears when the
terminal rings the bell and reports a blocked status. Destroys the session at
the end. Point it only at a server you started for testing.
"""

import asyncio
import json
import sys

from rexwire import Control, RexError


def kind(e):
    p = e["payload"]
    if p["type"] != "session_event":
        return p["type"]
    inner = p["payload"]["event"]
    return inner["type"] + (":" + inner["payload"]["name"] if inner["type"] == "block_event" else "")


async def main(url):
    a = Control(url, name="maker")
    await a.connect()
    cr = await a.request("session.create", {"label": "rax-scope", "initial_windows": [
        {"layout": {"block": {"flavor": "com.superlogical.terminal.shell"}}}]})
    sid, bid = cr["session_id"], cr["initial_windows"][0]["block_ids"][0]
    await a.request("session.attach", {"session_id": sid})
    await asyncio.sleep(1.0)
    for scope in ("session", "all"):
        p = Control(url, name="phone-" + scope)
        await p.connect()
        await p.request("session.bootstrap", {"event_scope": scope, "attach_session_ids": []})
        await p.drain_events(0.3)
        view = await p.request("session.view", {"session_id": sid})
        status = await p.block_call(sid, bid, "program_status")
        try:
            await p.request("data.connect", {"session_id": sid, "block_id": bid, "data_ref_id": "terminal"})
            dc = "ok"
        except RexError as e:
            dc = e.error["code"]
        await a.block_call(sid, bid, "write", {"data": "printf '\\a\\033]7501;state=blocked:kind=permission:app=demo\\033\\\\'\n"})
        events = await p.drain_events(1.2)
        print(f"event_scope={scope}: session.view revision {view['revision']}, program_status {len(status['records'])} records,"
              f" data.connect {dc}, events {sorted({kind(e) for e in events})}")
        await p.close()
    print("hosts.list:", json.dumps(await a.request("hosts.list")))
    await a.request("session.destroy", {"session_id": sid})
    await a.close()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1]))

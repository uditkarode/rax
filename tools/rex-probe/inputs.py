"""Show which bytes each input method delivers to a program.

Usage:
  python inputs.py URL [ECHO_COMMAND...]

ECHO_COMMAND is a program that puts its tty in raw mode, turns on bracketed
paste, SGR mouse and focus reporting, and prints each read as hex. It
defaults to keyecho.py from this directory. It runs in a new session called rax-inputs, which is
destroyed at the end. Point it only at a server you started for testing.
"""

import asyncio
import json
import os
import re
import sys

from rexwire import Control, DataStream, RexError

SHELL = "com.superlogical.terminal.shell"


def keyecho(*flags):
    """Command line that runs keyecho.py from this directory with the current Python."""
    return [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "keyecho.py"), *flags]


def key(code, mods="", text="", cp=0, action="press", consumed=""):
    return {"key": code, "mods": mods, "consumed_mods": consumed, "text": text,
            "unshifted_codepoint": cp, "composing": False, "action": action}


async def main(url, cmd):
    c = Control(url)
    await c.connect()
    created = await c.request("session.create", {
        "label": "rax-inputs",
        "initial_windows": [{"layout": {"block": {"flavor": SHELL, "options": {"shell": "none", "command": cmd}}}}],
    })
    sid = created["session_id"]
    bid = created["initial_windows"][0]["block_ids"][0]
    await c.request("session.attach", {"session_id": sid})
    tk = await c.request("data.connect", {"session_id": sid, "block_id": bid, "data_ref_id": "terminal",
                                          "subprotocol": "rex.data.terminal.v1"})
    d = DataStream(url)
    await d.hello(tk["ticket"])
    await d.read_for(1.5)

    async def probe(label, method, payload):
        start = len(d.buf)
        try:
            res = await c.request(method, payload)
        except RexError as e:
            res = {"ERROR": e.error}
        await d.read_for(0.4)
        got = re.findall(rb"IN ([0-9a-f]+) ", bytes(d.buf[start:]))
        print(f"{label:38s} result={json.dumps(res)[:200]}  bytes={[g.decode() for g in got]}")

    ev = lambda t, p=None: {"session_id": sid, "block_id": bid, "event": {"type": t, **({"payload": p} if p is not None else {})}}
    ck = lambda k, **kw: {"session_id": sid, "block_id": bid, "key": k, **kw}
    W = "com.superlogical.terminal.write"

    await probe("write 'abc'", W, {"session_id": sid, "block_id": bid, "args": {"data": "abc"}})
    await probe("paste (bracketed on)", "block.event", ev("paste", {"text": "one\ntwo"}))
    await probe("focus in", "block.event", ev("focus", {"action": "in"}))
    await probe("focus out", "block.event", ev("focus", {"action": "out"}))
    await probe("client.key a", "client.key", ck(key("key_a", text="a", cp=97)))
    await probe("client.key shift+a", "client.key", ck(key("key_a", mods="shift", consumed="shift", text="A", cp=97)))
    await probe("client.key ctrl+c", "client.key", ck(key("key_c", mods="ctrl", cp=99)))
    await probe("client.key enter", "client.key", ck(key("enter", cp=13)))
    await probe("client.key backspace", "client.key", ck(key("backspace", cp=127)))
    await probe("client.key tab", "client.key", ck(key("tab", cp=9)))
    await probe("client.key escape", "client.key", ck(key("escape", cp=27)))
    await probe("client.key arrow_up", "client.key", ck(key("arrow_up")))
    await probe("client.key alt+arrow_left", "client.key", ck(key("arrow_left", mods="alt")))
    await probe("client.key alt+b (text b)", "client.key", ck(key("key_b", mods="alt", text="b", cp=98)))
    await probe("client.key f5", "client.key", ck(key("f5")))
    await probe("client.key release a", "client.key", ck(key("key_a", text="a", cp=97, action="release")))
    await probe("client.key é (text only)", "client.key", ck(key("unidentified", text="é", cp=233)))
    await probe("block.event key ctrl+d? (no: ctrl+l)", "block.event", ev("key", key("key_l", mods="ctrl", cp=108)))
    await probe("mouse {}", "block.event", ev("mouse", {}))
    await probe("wheel {}", "block.event", ev("wheel", {}))

    await c.request("session.destroy", {"session_id": sid})
    await c.close()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], sys.argv[2:] or keyecho()))

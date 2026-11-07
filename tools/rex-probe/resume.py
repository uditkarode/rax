"""Check data-stream RESUME after a dropped connection.

Usage:
  python resume.py URL

Case 1 drops only the data connection. Case 2 drops the data connection and
the control connection together, as when a phone loses its network, and
continues the client with its reconnect token first. Case 3 sends a RESUME
that counts fewer bytes than were received. Each case creates and destroys its
own session. Point it only at a server you started for testing.
"""

import asyncio
import sys

from rexwire import Control, DataStream

SHELL = "com.superlogical.terminal.shell"


async def case(url, drop_control):
    c = Control(url, name="resume-probe")
    await c.connect()
    cr = await c.request("session.create", {"label": "rax-resume",
                                            "initial_windows": [{"layout": {"block": {"flavor": SHELL}}}]})
    sid, bid = cr["session_id"], cr["initial_windows"][0]["block_ids"][0]
    await c.request("session.attach", {"session_id": sid})
    tk = await c.request("data.connect", {"session_id": sid, "block_id": bid, "data_ref_id": "terminal",
                                          "subprotocol": "rex.data.terminal.v1"})
    d = DataStream(url)
    w = await d.hello(tk["ticket"])
    await d.read_for(1.5)  # read until the shell is quiet, so every sent byte is counted
    count, token, nonce = len(d.buf), w["token"], w["nonce"]
    d.drop()
    if drop_control:
        rt = c.reconnect_token
        await c.drop()
        c = Control(url, name="resume-probe", reconnect_token=rt)
        cw = await c.connect()
        print("  control resumed:", cw["client"].get("resumed"))
    await c.block_call(sid, bid, "write", {"data": "echo away-$((6*7))\n"})
    await asyncio.sleep(0.8)

    d2 = DataStream(url)
    w2 = await d2.resume(token, count)
    if "reject" in w2:
        print(f"  RESUME with {count} bytes rejected, reason 0x{w2['reject']:02x}")
    else:
        await d2.read_for(0.8)
        print(f"  RESUME with {count} bytes accepted, same nonce: {w2['nonce'] == nonce},"
              f" new token: {w2['token'] != token}, starts with snapshot: {bytes(d2.buf[:8]) == b'GHOSTSNP'},"
              f" output seen: {b'away-42' in d2.buf}")
        # Drop again, then RESUME with a count larger than what was sent.
        total = count + len(d2.buf)
        d2.drop()
        d3 = DataStream(url)
        print("  second drop, RESUME counting 1000 bytes too many ->", await d3.resume(w2["token"], total + 1000))
        d4 = DataStream(url)
        print("  then RESUME with the right count ->", await d4.resume(w2["token"], total))

    await c.request("session.destroy", {"session_id": sid})
    await c.close()


async def fewer(url):
    c = Control(url, name="resume-probe")
    await c.connect()
    cr = await c.request("session.create", {"label": "rax-fewer",
                                            "initial_windows": [{"layout": {"block": {"flavor": SHELL}}}]})
    sid, bid = cr["session_id"], cr["initial_windows"][0]["block_ids"][0]
    await c.request("session.attach", {"session_id": sid})
    for back in (1, 100):
        tk = await c.request("data.connect", {"session_id": sid, "block_id": bid, "data_ref_id": "terminal",
                                              "subprotocol": "rex.data.terminal.v1"})
        d = DataStream(url)
        w = await d.hello(tk["ticket"])
        await d.read_for(1.5)
        d.drop()
        await asyncio.sleep(0.2)
        r = await DataStream(url).resume(w["token"], len(d.buf) - back)
        print(f"  RESUME counting {back} byte(s) fewer than received ->", r if "reject" in r else "accepted")
    await c.request("session.destroy", {"session_id": sid})
    await c.close()


async def main(url):
    print("case 1: drop data connection only")
    await case(url, False)
    print("case 2: drop data and control connections")
    await case(url, True)
    print("case 3: count too low")
    await fewer(url)


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1]))

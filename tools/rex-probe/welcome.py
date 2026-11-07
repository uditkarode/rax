"""Print what a server says to a new client: the welcome, server.whoami and server.version.

Usage:
  python welcome.py URL        such as http://127.0.0.1:7297 or https://rex.tail1234.ts.net

Read-only: sends a hello and two read-only requests, then closes with a close
frame, which ends the client at once.
"""

import asyncio
import json
import sys

from rexwire import Control


async def main(url):
    c = Control(url, name="welcome-probe")
    w = await c.connect()
    w = dict(w)
    if w.get("client"):
        w["client"] = {**w["client"], "reconnect_token": "<redacted>"}
    print("response headers:", json.dumps(c.response_headers))
    print("welcome:", json.dumps(w, indent=1))
    print("server.whoami:", json.dumps(await c.request("server.whoami"), indent=1))
    print("server.version:", json.dumps(await c.request("server.version")))
    await c.close()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1]))

"""Split a Rex terminal stream into its GHOSTSNP snapshot records and the live bytes after it.

Usage:
  python ghostsnp.py STREAM_FILE

Only the record framing is read. The record payloads belong to libghostty-vt
and change with its version, so they are reported by tag and size only.
"""

import struct
import sys

MAGIC = b"GHOSTSNP"


def _crc32c_table():
    poly = 0x82F63B78
    table = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ poly if c & 1 else c >> 1
        table.append(c)
    return table


_T = _crc32c_table()


def crc32c(data):
    c = 0xFFFFFFFF
    for b in data:
        c = _T[(c ^ b) & 0xFF] ^ (c >> 8)
    return c ^ 0xFFFFFFFF


def split(buf):
    """Return (version, records, end_offset). Each record is (offset, tag, length, crc_ok)."""
    if buf[:8] != MAGIC:
        raise ValueError("stream does not start with GHOSTSNP")
    version = struct.unpack_from("<H", buf, 8)[0]
    off = 10
    records = []
    while off + 10 <= len(buf):
        tag, length, crc = struct.unpack_from("<HII", buf, off)
        payload = buf[off + 10 : off + 10 + length]
        if len(payload) < length:
            raise ValueError(f"record at {off} is cut short")
        # The CRC-32C covers the tag and length fields as well as the payload.
        records.append((off, tag, length, crc32c(buf[off : off + 6] + payload) == crc))
        off += 10 + length
        if tag == 6:  # FINISH, the last record
            break
    return version, records, off


if __name__ == "__main__":
    data = open(sys.argv[1], "rb").read()
    version, records, end = split(data)
    print(f"GHOSTSNP version {version}, {len(records)} records, snapshot ends at byte {end}")
    for off, tag, length, ok in records:
        print(f"  offset {off:6d}  tag {tag}  length {length:7d}  crc32c {'ok' if ok else 'MISMATCH'}")
    print("bytes after snapshot:", repr(data[end : end + 200]))

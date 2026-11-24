# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (c) 2026 The DIB0700 Open Driver Authors.
#
# Our own code, no vendor code.

"""DIB0700 / DiBcom STK8096GP TS framing.

The bulk-IN endpoint ``0x82`` is a **plain byte pipe**, not a message pipe:
transfers come in 3,584-byte slabs, and 3,584 is not a multiple of 188
(3,584 = 19 packets + 12 bytes).  Packet boundaries therefore do not line up
with transfer boundaries, and the packet phase drifts by 12 bytes on every
transfer:

    transfer 0 starts 100 bytes into a packet
    transfer 1 starts  88 bytes into a packet
    transfer 2 starts  76 bytes into a packet

Any reader that looks for ``0x47`` at a fixed offset inside each transfer sees
nonsense.  Concatenate the transfers and cut every 188 bytes instead; the
capture then yields a single unbroken run of 175,539 packets with 0 sync
errors.

Short transfers (56 bytes and the like) are normal: a bulk read returns whatever
the device has ready, so a slab can end anywhere.  They are part of the same
pipe and must be appended, not dropped -- dropping them is what made an earlier
analysis conclude the framing was unresolved.
"""

TS_PACKET = 188
PACKETS_PER_SLAB = 19  # 3,584 bytes holds 19 whole packets and 12 bytes of slack


class TsStream:
    """Turns bulk transfers into 188-byte packets, resynchronising on loss."""

    def __init__(self):
        self.buffer = bytearray()
        self.sync_errors = 0
        self.packets = 0

    def feed(self, data):
        """Append one bulk transfer; return the aligned packets it completed.

        One slice and one memmove per transfer, not per packet: at 10 Mbit/s the
        old per-packet ``del buffer[:188]`` moved the whole tail ~11,000 times a
        second.
        """
        buf = self.buffer
        buf += data
        n = len(buf)

        start = 0
        while n - start >= TS_PACKET and buf[start] != 0x47:
            offset = self._resync(buf, start)
            if offset is None:
                # keep a tail shorter than a packet, drop what cannot be aligned
                del buf[:n - TS_PACKET + 1]
                self.sync_errors += 1
                return b""
            start = offset
            self.sync_errors += 1

        end = start
        while n - end >= TS_PACKET and buf[end] == 0x47:
            end += TS_PACKET

        if end == start:
            if start:
                del buf[:start]
            return b""

        out = bytes(buf[start:end])
        self.packets += (end - start) // TS_PACKET
        del buf[:end]
        return out

    def _resync(self, buf, start):
        """Offset of the next plausible packet start, or None."""
        n = len(buf)
        for offset in range(start + 1, n - TS_PACKET):
            if buf[offset] == 0x47:
                if offset + TS_PACKET >= n or buf[offset + TS_PACKET] == 0x47:
                    return offset
        return None

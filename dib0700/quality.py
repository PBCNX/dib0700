# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (c) 2026 The DIB0700 Open Driver Authors.
#
# Our own code, no vendor code.

"""Stream quality: the share of clean TS packets over a sliding window.

Our own definition, the same in every driver line: a packet is **not clean** when its
transport_error_indicator is set, or when its continuity counter breaks on its PID. A repeated
counter is not a break. Null packets (PID 0x1FFF) and adaptation-only packets are not counted at
all. Quality is ``100 * clean / total`` over the last ``window`` seconds, and is **unknown**
(``None``) until the first full window has been observed.

This is deliberately not the demodulator's C/N (which this part does not decode; our own C/N
plan is not implemented). It answers the player's question - is the stream I am receiving
clean - from the stream itself.
"""
import time
from collections import deque

TS_PACKET = 188
NULL_PID = 0x1FFF

# Packets are counted in 100 ms chunks: a near-exact sliding window without one object per packet
# (10 Mbit/s is ~6,600 packets a second, ~33,000 in a 5 s window).
CHUNK_SECONDS = 0.1


class QualityMeter:
    def __init__(self, window=5.0):
        self.window = float(window)
        self.chunks = deque()      # [start, total, clean]
        self.last_cc = {}          # PID -> continuity counter
        self.started = None

    def reset(self):
        self.chunks.clear()
        self.last_cc.clear()
        self.started = None

    def feed(self, data, now=None):
        """Count every whole packet in ``data``; ``now`` in seconds (default: monotonic clock)."""
        if now is None:
            now = time.monotonic()
        if self.started is None:
            self.started = now
        for offset in range(0, len(data) - TS_PACKET + 1, TS_PACKET):
            packet = data[offset:offset + TS_PACKET]
            if packet[0] != 0x47:
                continue
            pid = ((packet[1] & 0x1F) << 8) | packet[2]
            if pid == NULL_PID:
                continue
            adaptation = (packet[3] >> 4) & 0x03
            if adaptation == 0b10 or adaptation == 0b00:
                continue            # adaptation only, or the reserved value: no payload
            total, clean = 1, not (packet[1] & 0x80)
            counter = packet[3] & 0x0F
            previous = self.last_cc.get(pid)
            if previous is not None and counter != previous and counter != ((previous + 1) & 0x0F):
                clean = False
            self.last_cc[pid] = counter
            self._count(now, total, clean)

    def percent(self, now=None):
        """Quality 0-100 over the window, or None until one full window has been seen."""
        if now is None:
            now = time.monotonic()
        if self.started is None or now - self.started < self.window:
            return None
        edge = now - self.window
        while self.chunks and self.chunks[0][0] <= edge:
            self.chunks.popleft()
        total = sum(chunk[1] for chunk in self.chunks)
        clean = sum(chunk[2] for chunk in self.chunks)
        if total <= 0:
            return None
        return int(round(100.0 * clean / total))

    def _count(self, now, total, clean):
        if not self.chunks or now - self.chunks[-1][0] >= CHUNK_SECONDS:
            self.chunks.append([now, 0, 0])
        chunk = self.chunks[-1]
        chunk[1] += total
        chunk[2] += clean

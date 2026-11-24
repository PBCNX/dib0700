/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Stream quality: the share of clean TS packets over a sliding window.
 *
 * <p>Our own definition, the same in every driver line: a packet is <b>not clean</b> when its
 * transport_error_indicator is set, or when its continuity counter breaks on its PID. A repeated
 * counter is not a break. Null packets (PID 0x1FFF) and adaptation-only packets are not counted
 * at all. Quality is {@code 100 * clean / total} over the last {@code windowSeconds}, and is
 * {@link #UNKNOWN} until the first full window has been observed.</p>
 *
 * <p>This is deliberately not the demodulator's C/N (which this part does not decode; the
 * our own C/N plan is the C/N note). It answers the player's question - is the
 * stream I am receiving clean - from the stream itself.</p>
 */
public final class QualityMeter {

    public static final int UNKNOWN = -1;

    private static final int PACKET = 188;
    private static final int NULL_PID = 0x1FFF;
    private static final double CHUNK_SECONDS = 0.1;

    private static final class Chunk {
        final double start;
        long total;
        long clean;

        Chunk(double start) {
            this.start = start;
        }
    }

    private final double windowSeconds;
    private final ArrayDeque<Chunk> chunks = new ArrayDeque<>();
    private final Map<Integer, Integer> lastCc = new HashMap<>();
    private double started = Double.NaN;

    public QualityMeter(double windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    /** Counts every whole packet in {@code data[0..length)} at {@code now} seconds. */
    public synchronized void feed(byte[] data, int length, double now) {
        if (Double.isNaN(started)) started = now;
        int limit = length - PACKET;
        for (int offset = 0; offset <= limit; offset += PACKET) {
            if ((data[offset] & 0xFF) != 0x47) continue;
            int pid = ((data[offset + 1] & 0x1F) << 8) | (data[offset + 2] & 0xFF);
            if (pid == NULL_PID) continue;
            int adaptation = (data[offset + 3] >> 4) & 0x03;
            if (adaptation == 0x02 || adaptation == 0x00) continue;   // no payload
            boolean clean = (data[offset + 1] & 0x80) == 0;
            int counter = data[offset + 3] & 0x0F;
            Integer previous = lastCc.get(pid);
            if (previous != null && counter != previous
                    && counter != ((previous + 1) & 0x0F)) {
                clean = false;
            }
            lastCc.put(pid, counter);
            count(now, clean);
        }
    }

    /** Quality 0-100 over the window, or {@link #UNKNOWN} until one full window has been seen. */
    public synchronized int percent(double now) {
        if (Double.isNaN(started) || now - started < windowSeconds) return UNKNOWN;
        double edge = now - windowSeconds;
        while (!chunks.isEmpty() && chunks.peekFirst().start <= edge) {
            chunks.pollFirst();
        }
        long total = 0;
        long clean = 0;
        for (Chunk chunk : chunks) {
            total += chunk.total;
            clean += chunk.clean;
        }
        if (total <= 0) return UNKNOWN;
        return (int) Math.round(100.0 * clean / total);
    }

    public synchronized void reset() {
        chunks.clear();
        lastCc.clear();
        started = Double.NaN;
    }

    private void count(double now, boolean clean) {
        Chunk chunk = chunks.peekLast();
        if (chunk == null || now - chunk.start >= CHUNK_SECONDS) {
            chunk = new Chunk(now);
            chunks.addLast(chunk);
        }
        chunk.total++;
        if (clean) chunk.clean++;
    }
}

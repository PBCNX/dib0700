/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700;

import java.util.Arrays;

/**
 * Turns the bulk-IN {@code 0x82} byte pipe into whole 188-byte TS packets.
 *
 * <p>The pipe is not framed: transfers come in 3,584-byte slabs, and 3,584 is not a multiple of
 * 188 (19 packets + 12 bytes), so the packet phase drifts 12 bytes on every slab. Concatenate the
 * transfers and cut every 188 bytes; short transfers are part of the same stream and must be
 * appended, not dropped. On corruption, resynchronise on a plausible packet start and count a sync
 * error. One slice and one memmove per transfer, not per packet.</p>
 */
public final class TsFramer {

    public interface Sink {
        void onTs(byte[] data, int length);
    }

    public static final int PACKET = 188;
    public static final int SLAB = 3584;

    private byte[] buffer = new byte[SLAB * 2];
    private int count;
    public long packets;
    public long syncErrors;

    public int feed(byte[] data, int length, Sink sink) {
        ensure(count + length);
        System.arraycopy(data, 0, buffer, count, length);
        count += length;

        int start = 0;
        while (count - start >= PACKET && (buffer[start] & 0xFF) != 0x47) {
            int offset = resync(start);
            if (offset < 0) {
                // Keep a tail shorter than one packet; the rest cannot be aligned.
                int keep = PACKET - 1;
                int drop = count - keep;
                if (drop > 0) {
                    System.arraycopy(buffer, drop, buffer, 0, keep);
                    count = keep;
                }
                syncErrors++;
                return 0;
            }
            start = offset;
            syncErrors++;
        }

        int end = start;
        while (count - end >= PACKET && (buffer[end] & 0xFF) == 0x47) end += PACKET;

        if (end == start) {
            if (start > 0) {
                System.arraycopy(buffer, start, buffer, 0, count - start);
                count -= start;
            }
            return 0;
        }

        byte[] out = Arrays.copyOfRange(buffer, start, end);
        packets += (end - start) / PACKET;
        System.arraycopy(buffer, end, buffer, 0, count - end);
        count -= end;
        sink.onTs(out, out.length);
        return out.length;
    }

    public void reset() {
        count = 0;
        packets = 0;
        syncErrors = 0;
    }

    private int resync(int start) {
        for (int offset = start + 1; offset + PACKET < count; offset++) {
            if ((buffer[offset] & 0xFF) != 0x47) continue;
            if (offset + PACKET >= count || (buffer[offset + PACKET] & 0xFF) == 0x47) {
                return offset;
            }
        }
        return -1;
    }

    private void ensure(int needed) {
        if (needed <= buffer.length) return;
        int size = buffer.length * 2;
        while (size < needed) size *= 2;
        buffer = Arrays.copyOf(buffer, size);
    }
}

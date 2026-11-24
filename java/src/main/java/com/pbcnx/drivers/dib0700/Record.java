/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700;

import java.util.Map;

/**
 * One control transfer of a captured session, in the order the kernel issued it.
 *
 * <p>{@code dir} is the wire direction: {@code out} is a vendor OUT write, {@code in} is a
 * vendor IN read whose captured answer (when present) lives in {@code reply}. The timestamps are
 * the kernel's, in seconds; {@code replay} reproduces the gaps between them.</p>
 */
public final class Record {

    public final boolean out;
    public final int request;
    public final int value;
    public final int index;
    public final int length;
    public final byte[] data;
    public final double t;

    Record(boolean out, int request, int value, int index, int length, byte[] data, double t) {
        this.out = out;
        this.request = request;
        this.value = value;
        this.index = index;
        this.length = length;
        this.data = data;
        this.t = t;
    }

    static Record from(Object raw) {
        if (!(raw instanceof Map)) throw new IllegalArgumentException("session record is not an object");
        Map<?, ?> map = (Map<?, ?>) raw;
        Object dir = map.get("dir");
        if (!"out".equals(dir) && !"in".equals(dir)) {
            throw new IllegalArgumentException("session record has dir=" + dir);
        }
        Object data = map.get("data");
        return new Record("out".equals(dir),
                number(map, "request", 0),
                number(map, "value", 0),
                number(map, "index", 0),
                number(map, "length", 0),
                data instanceof String ? hex((String) data) : new byte[0],
                numberDouble(map, "t", 0));
    }

    private static int number(Map<?, ?> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        return fallback;
    }

    private static double numberDouble(Map<?, ?> map, String key, double fallback) {
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        return fallback;
    }

    static byte[] hex(String text) {
        int length = text.length() / 2;
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}

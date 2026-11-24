/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The captured sessions: one whole cold plug per frequency, replayed verbatim.
 *
 * <p>They are protocol data (observed USB traffic), not firmware, and they are the reason a
 * user-space driver can tune this part at all: the dib8000 tune is a state machine driven by
 * register reads, so the driver replays one run instead of re-implementing the state machine.
 * A frequency without a capture is refused, not guessed.</p>
 *
 * <p>The files are the same {@code resources/session-<khz>.json} the Python driver uses; the
 * build copies them onto the classpath under {@code dib0700/} so there is one copy in the repo.</p>
 */
public final class Sessions {

    private final SessionSource source;
    private final java.util.Map<Integer, Record[]> captures;

    public static final String OUTPUT_ENABLE = "038005060540";
    public static final String OUTPUT_OFF = "038005060000";

    public Sessions(SessionSource source) {
        this(source, null);
    }

    private Sessions(SessionSource source, java.util.Map<Integer, Record[]> captures) {
        this.source = source;
        this.captures = captures;
    }

    /**
     * A session set from records that are already parsed: for a host that keeps the captures in
     * its own store, and for a JVM test that replays the captured records with their timing
     * compressed. The core otherwise reads the same files from the classpath.
     */
    public static Sessions fromRecords(java.util.Map<Integer, Record[]> captures) {
        return new Sessions(null, new java.util.HashMap<>(captures));
    }

    /** The sessions that travel with the library, under {@code dib0700/session-<khz>.json}. */
    public static Sessions fromClasspath() {
        return new Sessions(khz -> Dib0700.class.getResourceAsStream("/dib0700/session-" + khz + ".json"));
    }

    public boolean has(int khz) {
        if (captures != null) return captures.containsKey(khz);
        try (InputStream in = source.open(khz)) {
            return in != null;
        } catch (IOException error) {
            return false;
        }
    }

    public Record[] load(int khz) throws IOException {
        if (captures != null) {
            Record[] records = captures.get(khz);
            if (records == null) throw new IOException("no captured session for " + khz + " kHz");
            return records.clone();
        }
        try (InputStream in = source.open(khz)) {
            if (in == null) throw new IOException("no captured session for " + khz + " kHz");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
            return parse(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    public static Record[] parse(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof java.util.List)) {
            throw new IllegalArgumentException("session is not a JSON array");
        }
        java.util.List<?> items = (java.util.List<?>) root;
        Record[] records = new Record[items.size()];
        for (int i = 0; i < records.length; i++) records[i] = Record.from(items.get(i));
        return records;
    }

    /**
     * A {@code dvbv5-zap -x} capture keeps the app's close: 0x0506 = 0x0540 switches the MPEG
     * output on, and 1.2 s later 0x0506 = 0x0000 switches it off. Replaying the close leaves the
     * stream silent even with the demod locked, so the replay stops right after the enable. The
     * captures are not rewritten; a capture without a close after its enable is unchanged.
     */
    public static Record[] trimAtOutputEnable(Record[] records) {
        int enabled = -1;
        for (int index = 0; index < records.length; index++) {
            Record record = records[index];
            if (!record.out || record.request != 0x03) continue;
            String data = hex(record.data);
            if (enabled < 0) {
                if (data.startsWith(OUTPUT_ENABLE)) enabled = index;
            } else if (data.startsWith(OUTPUT_OFF)) {
                return java.util.Arrays.copyOf(records, enabled + 1);
            }
        }
        return records;
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (byte value : data) out.append(String.format("%02x", value & 0xFF));
        return out.toString();
    }
}

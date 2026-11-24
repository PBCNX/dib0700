/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700;

/**
 * Demodulator and stream measurements; the host maps this to its own meter struct.
 *
 * <p>Field names and meanings are the shared driver interface (2026-11-18, aligned with the
 * SMSRIO line): {@code qualityPercent} is the shared packet Q, {@code snrQualityPercent} and
 * {@code snrDb} are the SNR/CNR-derived values under their own name, {@code rawStrength} and
 * {@code dbm} are the strength values. On this line there is no chip C/N (the kernel-derived decode
 * was removed; the C/N note is a plan only), so {@code snrQualityPercent} stays -1,
 * {@code snrDb} stays NaN and {@code dbm} stays {@link Integer#MIN_VALUE} (unknown).</p>
 */
public final class Status {

    public boolean locked;

    /**
     * Driver-specific raw strength. On this line it is the AGC word {@code 65535 - reg 390},
     * unitless; the SMSRIO line puts its RSSI in dBm here, which is why the host must not assume
     * one unit - {@link #strengthPercent} is the comparable value.
     */
    public int rawStrength = -1;

    /** 0-100, from the AGC strength. */
    public int strengthPercent = -1;

    /** Shared quality metric: 0-100 over the 5 s window, or -1 before the first full window. */
    public int qualityPercent = -1;

    /** The older SNR-derived quality (0-100); -1 on this line, which has no chip C/N. */
    public int snrQualityPercent = -1;

    /** dB; NaN on this line (no chip C/N). */
    public double snrDb = Double.NaN;

    /** In-band power, dBm; Integer.MIN_VALUE (unknown) on this line. */
    public int dbm = Integer.MIN_VALUE;

    public void copyFrom(Status other) {
        locked = other.locked;
        rawStrength = other.rawStrength;
        strengthPercent = other.strengthPercent;
        qualityPercent = other.qualityPercent;
        snrQualityPercent = other.snrQualityPercent;
        snrDb = other.snrDb;
        dbm = other.dbm;
    }
}

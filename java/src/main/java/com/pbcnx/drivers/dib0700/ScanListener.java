/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700;

/** Per-channel results of {@link Dib0700#scan}: what a scan found, one channel at a time. */
public interface ScanListener {

    /**
     * One scanned channel.
     *
     * @param khz            the channel in kHz
     * @param locked         true when the tune locked <b>and</b> packets arrived: the Python CLI's
     *                       LOCK line, which is discovery rather than a lock-word reading
     * @param packets        whole TS packets received during the channel's measuring window
     * @param strengthPercent AGC strength 0-100, or -1 when the demod did not answer
     */
    void onChannel(int khz, boolean locked, long packets, int strengthPercent);
}

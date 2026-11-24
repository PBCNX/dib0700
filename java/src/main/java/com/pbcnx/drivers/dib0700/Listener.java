/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700;

/** What the driver reports back to its host. */
public interface Listener {

    /** Whole 188-byte packets, already aligned; valid only for the duration of the call. */
    void onData(byte[] ts, int length);

    /** Bring-up or tune failed; the driver keeps running where it can. */
    void onError(String reason);

    /** The stream ended by itself, not because the host called stop(). */
    void onEnd();
}

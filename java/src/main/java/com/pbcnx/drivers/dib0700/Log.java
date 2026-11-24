/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700;

/** Where the driver's notes go: logcat on Android, stdout in a test. Never telemetry. */
public interface Log {

    void info(String message);

    /** Discards everything. */
    Log NONE = message -> {
    };
}

/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700;

import java.io.IOException;
import java.io.InputStream;

/** Where the captured sessions come from: app assets, classpath, or a test fixture. */
public interface SessionSource {

    /** A stream for the session of this frequency in kHz, or null when there is none. */
    InputStream open(int khz) throws IOException;
}

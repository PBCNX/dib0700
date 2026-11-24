/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700.android;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.pbcnx.drivers.dib0700.Log;
import com.pbcnx.drivers.dib0700.Dib0700;
import com.pbcnx.drivers.dib0700.Sessions;
import com.pbcnx.drivers.dib0700.Tmcc;
import com.pbcnx.drivers.dib0700.TransportException;

/** Android entry point: opens the device and returns a ready-to-start {@link Dib0700}. */
public final class Dib0700Android {

    private Dib0700Android() {
    }

    /**
     * Opens the stick over Android's USB host API. The caller must already hold the USB
     * permission for {@code device}; pass the returned driver to the host's source wrapper.
     */
    public static Dib0700 open(UsbManager manager, UsbDevice device) throws TransportException {
        UsbTransport transport = UsbTransport.open(manager, device);
        Log log = message -> android.util.Log.i("Dib0700", message);
        return new Dib0700(transport, Sessions.fromClasspath(), log);
    }

    /**
     * Same, with the caller's session source: a host may keep the captured sessions in its own
     * assets (for example {@code assets/dib0700/session-<khz>.json}) rather than on the
     * classpath. The core stays free of any host asset knowledge.
     */
    public static Dib0700 open(UsbManager manager, UsbDevice device, Sessions sessions)
            throws TransportException {
        UsbTransport transport = UsbTransport.open(manager, device);
        Log log = message -> android.util.Log.i("Dib0700", message);
        return new Dib0700(transport, sessions, log);
    }

    /**
     * The one-shot TMCC read for the driver {@link #open} returned: the host already holds that
     * {@link Dib0700}, so {@code driver.tmcc()} is the same call. This entry point exists so the
     * Android-facing API names the record beside {@code open}; mapping it to the host's own
     * {@code Tmcc} (canonical mode, boolean flags) is the host wrapper's job.
     */
    public static Tmcc tmcc(Dib0700 driver) {
        return driver.tmcc();
    }
}

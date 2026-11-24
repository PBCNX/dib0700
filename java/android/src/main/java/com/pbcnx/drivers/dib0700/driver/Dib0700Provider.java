/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700.driver;

import android.content.Context;
import android.hardware.usb.UsbDevice;

import com.pbcnx.drivers.dib0700.Log;
import com.pbcnx.drivers.dib0700.Dib0700;
import com.pbcnx.drivers.dib0700.Sessions;
import com.pbcnx.drivers.dib0700.Transport;
import com.pbcnx.tuner.TsReader;
import com.pbcnx.tuner.Tuner;
import com.pbcnx.tuner.TunerProvider;

/**
 * The door's entry point for this driver: {@code com.pbcnx.drivers:dib0700} now implements
 * {@code com.pbcnx.tuner.Tuner} through {@link Dib0700Tuner}.
 *
 * <p>Four construction arguments, no singleton (contract §1): the firmware image is the host's
 * (this package does not ship vendor firmware), the listener is the host's sink, and the device is
 * the instance the host just got a grant for. {@link #open} only <b>builds</b> the tuner; the
 * single checked failure - permission missing, transport gone - is raised by {@code Tuner.open()},
 * which is the door's only throwing method (§2 row 1).
 *
 * <p>The context-less overload is the same construction over a {@link Transport} the caller
 * supplied: a desktop host on libusb, or the JVM self-test's fake. It is what makes the provider
 * testable without an Android runtime.
 */
public final class Dib0700Provider implements TunerProvider {

    /** The USB ids this package answers for: DiBcom STK8096GP ("DIB0700"). */
    public static final int VENDOR_ID = Dib0700.VID;
    public static final int PRODUCT_ID = Dib0700.PID;

    /** True when {@code device} is this driver's chip - for a host's device list. */
    public static boolean isSupported(UsbDevice device) {
        return device != null
                && device.getVendorId() == VENDOR_ID
                && device.getProductId() == PRODUCT_ID;
    }

    @Override
    public Tuner open(Context context, UsbDevice device, byte[] firmware,
            TsReader.Listener listener) {
        return new Dib0700Tuner(context, device, firmware, listener);
    }

    /**
     * The desktop/test seam: open over a transport the caller owns (a JVM host's libusb handle, or
     * a fake). {@code sessions} null means the sessions that travel with the library.
     */
    public Tuner open(Transport transport, Sessions sessions, byte[] firmware,
            TsReader.Listener listener) {
        return new Dib0700Tuner(transport, sessions == null ? Sessions.fromClasspath() : sessions,
                firmware, listener, Log.NONE);
    }
}

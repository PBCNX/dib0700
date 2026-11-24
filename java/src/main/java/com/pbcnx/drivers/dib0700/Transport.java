/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700;

/**
 * The four USB transfers the driver needs, and nothing else.
 *
 * <p>The host supplies the implementation: {@code UsbTransport} in {@code java/android}
 * wraps Android's {@link android.hardware.usb.UsbDeviceConnection}; a JVM test supplies a fake.
 * The core never imports an Android or host class, so it compiles and runs on a plain JDK.</p>
 */
public interface Transport {

    /** Vendor OUT control transfer ({@code 0x40}); returns only when every byte was sent. */
    void controlOut(int request, int value, int index, byte[] data) throws TransportException;

    /** Vendor IN control transfer ({@code 0xC0}); null when the device answered with nothing. */
    byte[] controlIn(int request, int value, int index, int length) throws TransportException;

    /** Bulk OUT {@code 0x01}: firmware records and the JUMPRAM. */
    void bulkOut(byte[] data) throws TransportException;

    /**
     * Bulk IN {@code 0x82}: up to {@code buffer.length} bytes, waiting at most
     * {@code timeoutMs}. Returns 0 on a timeout - that is normal on an idle pipe.
     */
    int bulkIn(byte[] buffer, int timeoutMs) throws TransportException;

    /** Clears a halt on an endpoint: control {@code 0x02/0x01}, {@code wIndex = endpoint}. */
    void clearHalt(int endpoint) throws TransportException;

    /**
     * The USB bus/address pair for the recovery log ("bus 1 addr 5"), the host-log shape's
     * rule 5. The core cannot ask a host for anything it does not expose; a transport that cannot
     * say returns the neutral "bus ? addr ?" (the Python driver's {@code getattr} default), never
     * a guess. Android's public USB API has no bus/address, so its adapter keeps the default; a
     * JVM host on libusb can name the pair.
     */
    default String deviceText() {
        return "bus ? addr ?";
    }

    /** Releases the interface and closes the handle. Safe to call twice. */
    void close();
}

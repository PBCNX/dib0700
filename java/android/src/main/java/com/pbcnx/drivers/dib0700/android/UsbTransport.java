/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700.android;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import com.pbcnx.drivers.dib0700.Transport;
import com.pbcnx.drivers.dib0700.TransportException;

/**
 * Android's USB host API as a {@link Transport}: {@code UsbDeviceConnection} for every transfer.
 *
 * <p>No root and no {@code /dev/bus/usb}: the app framework grants the device through its own
 * permission flow, and this class assumes that permission was already granted. The endpoints are
 * the dib0700 ones: bulk OUT {@code 0x01} for the firmware and bulk IN {@code 0x82} for the TS
 * pipe; every command is a vendor control transfer.</p>
 */
public final class UsbTransport implements Transport {

    private static final int VENDOR_OUT = 0x40;
    private static final int VENDOR_IN = 0xC0;
    private static final int TIMEOUT_MS = 1000;
    private static final int EP_OUT = 0x01;
    private static final int EP_TS = 0x82;

    private final UsbDeviceConnection connection;
    private final UsbInterface claimed;
    private final UsbEndpoint out;
    private final UsbEndpoint ts;
    private volatile boolean closed;

    private UsbTransport(UsbDeviceConnection connection, UsbInterface claimed,
                         UsbEndpoint out, UsbEndpoint ts) {
        this.connection = connection;
        this.claimed = claimed;
        this.out = out;
        this.ts = ts;
    }

    /** Opens and claims the device. The caller must already hold the USB permission. */
    public static UsbTransport open(UsbManager manager, UsbDevice device) throws TransportException {
        if (device == null) throw new TransportException("no USB device");
        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            throw new TransportException("openDevice() failed (no USB permission?)");
        }
        UsbEndpoint out = null;
        UsbEndpoint ts = null;
        UsbInterface claimed = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint endpoint = intf.getEndpoint(e);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT
                        && endpoint.getAddress() == EP_OUT) {
                    out = endpoint;
                }
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN
                        && endpoint.getAddress() == EP_TS) {
                    ts = endpoint;
                }
            }
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC) {
                claimed = intf;
                break;
            }
        }
        if (out == null || ts == null) {
            connection.close();
            throw new TransportException("bulk endpoints 0x01 OUT / 0x82 IN not found");
        }
        if (claimed == null) claimed = device.getInterface(0);
        if (!connection.claimInterface(claimed, true)) {
            int id = claimed.getId();
            connection.close();
            throw new TransportException("claimInterface(" + id + ") failed");
        }
        UsbTransport transport = new UsbTransport(connection, claimed, out, ts);
        // The working Android driver clears both halts on open; a cold ROM accepts the download
        // after it (measured on the LEAP-S1). The libusb path deliberately does not - see
        // dib0700/usb.py - so this stays adapter-specific, not core policy.
        transport.clearHalt(EP_OUT);
        transport.clearHalt(EP_TS);
        return transport;
    }

    @Override
    public void controlOut(int request, int value, int index, byte[] data) throws TransportException {
        if (closed) throw new TransportException("transport closed");
        int sent = data == null ? 0 : data.length;
        int n = connection.controlTransfer(VENDOR_OUT, request, value, index,
                data, sent, TIMEOUT_MS);
        if (n != sent) throw new TransportException("control OUT " + request + ": " + n + " of " + sent);
    }

    @Override
    public byte[] controlIn(int request, int value, int index, int length) throws TransportException {
        if (closed) throw new TransportException("transport closed");
        byte[] buffer = new byte[length];
        int n = connection.controlTransfer(VENDOR_IN, request, value, index, buffer, length, TIMEOUT_MS);
        if (n < 0) throw new TransportException("control IN " + request + " failed: " + n);
        if (n == 0) return null;
        if (n == length) return buffer;
        byte[] cut = new byte[n];
        System.arraycopy(buffer, 0, cut, 0, n);
        return cut;
    }

    @Override
    public void bulkOut(byte[] data) throws TransportException {
        if (closed) throw new TransportException("transport closed");
        int n = connection.bulkTransfer(out, data, data.length, TIMEOUT_MS);
        if (n != data.length) throw new TransportException("bulk OUT: " + n + " of " + data.length);
    }

    @Override
    public int bulkIn(byte[] buffer, int timeoutMs) throws TransportException {
        if (closed) return 0;
        int n = connection.bulkTransfer(ts, buffer, buffer.length, timeoutMs);
        return Math.max(n, 0);
    }

    @Override
    public void clearHalt(int endpoint) throws TransportException {
        int n = connection.controlTransfer(0x02, 0x01, 0, endpoint, null, 0, TIMEOUT_MS);
        if (n < 0) throw new TransportException("clearHalt(0x" + Integer.toHexString(endpoint) + ") failed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            connection.releaseInterface(claimed);
        } catch (RuntimeException ignored) {
            // the device may already be gone
        }
        try {
            connection.close();
        } catch (RuntimeException ignored) {
            // as above
        }
    }
}

/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The dib0700 firmware image: binary Intel-HEX records, and the wire form the boot ROM takes.
 *
 * <p>The file stores each record's 16-bit address little-endian
 * ({@code [len][addr lo][addr hi][type]}); the kernel reads it that way
 * ({@code dvb_usb_get_hexline}: {@code b[1] | b[2] << 8}) and writes it back big-endian, so the
 * two address bytes swap on the wire. A byte swap keeps the byte sum unchanged, so the ROM still
 * accepts the record - which is why a mis-addressed image is ACKed instead of rejected. For an
 * extended-linear-address record (type {@code 0x04}) the upper 16 bits live in the data field and
 * are folded into the address before it is written back, exactly as the kernel does.</p>
 */
public final class Firmware {

    /** Where the boot ROM expects the image ({@code dib0700_jumpram}) - big-endian on the wire. */
    public static final int FIRMWARE_ADDRESS = 0x70000000;

    private Firmware() {
    }

    /** Split the image into records, checking every checksum. */
    public static List<byte[]> records(byte[] image) {
        List<byte[]> out = new ArrayList<>();
        int pos = 0;
        while (pos < image.length) {
            int length = image[pos] & 0xFF;
            int total = length + 5;   // len, address (2), type, data, checksum
            if (pos + total > image.length) {
                throw new IllegalArgumentException("truncated firmware record at " + pos);
            }
            int sum = 0;
            for (int i = 0; i < total; i++) sum += image[pos + i] & 0xFF;
            if ((sum & 0xFF) != 0) {
                throw new IllegalArgumentException("bad checksum in record at " + pos);
            }
            byte[] record = new byte[total];
            System.arraycopy(image, pos, record, 0, total);
            out.add(record);
            pos += total;
        }
        return Collections.unmodifiableList(out);
    }

    /** The record as the boot ROM receives it: address big-endian, checksum untouched. */
    public static byte[] wire(byte[] record) {
        int length = record[0] & 0xFF;
        int address = (record[1] & 0xFF) | ((record[2] & 0xFF) << 8);
        int type = record[3] & 0xFF;
        if (type == 0x04 && length >= 2) {
            address |= ((record[4] & 0xFF) << 24) | ((record[5] & 0xFF) << 16);
        }
        byte[] out = record.clone();
        out[1] = (byte) ((address >> 8) & 0xFF);
        out[2] = (byte) (address & 0xFF);
        return out;
    }

    /** The eight-byte JUMPRAM frame that starts the downloaded image. */
    public static byte[] jump() {
        return new byte[]{0x08, 0, 0, 0,
                (byte) ((FIRMWARE_ADDRESS >> 24) & 0xFF),
                (byte) ((FIRMWARE_ADDRESS >> 16) & 0xFF),
                (byte) ((FIRMWARE_ADDRESS >> 8) & 0xFF),
                (byte) (FIRMWARE_ADDRESS & 0xFF)};
    }
}

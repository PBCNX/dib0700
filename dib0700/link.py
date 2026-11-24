# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (c) 2026 The DIB0700 Open Driver Authors.
#
# Our own code, no vendor code.

"""USB access for the DIB0700, and the dib0700 request set.

dib0700 has three distinct paths and they are not interchangeable:

* **control transfers** for every command (I2C, GPIO, clock, ``ENABLE_VIDEO``,
  version).  There is no bulk-OUT command path.
* **bulk OUT `0x01`** only for the firmware download and the ``JUMPRAM`` that
  starts it.
* **bulk IN `0x82`** for the transport stream, as a plain byte pipe
  (see ``stream.py``).

The host implements this small interface over pyusb or ``UsbDeviceConnection``.
"""

# Endpoints
EP_FIRMWARE_OUT = 0x01
EP_STATUS_IN = 0x81      # cold ROM: 12-byte jump report
EP_TS_IN = 0x82

# bmRequestType
VENDOR_OUT = 0x40
VENDOR_IN = 0xC0

# Requests (dib0700.h)
REQUEST_SET_USB_XFER_LEN = 0x00
REQUEST_I2C_READ = 0x02
REQUEST_I2C_WRITE = 0x03
REQUEST_JUMPRAM = 0x08
REQUEST_SET_CLOCK = 0x0B
REQUEST_SET_GPIO = 0x0C
REQUEST_ENABLE_VIDEO = 0x0F
REQUEST_GET_VERSION = 0x15

# Where the boot ROM expects the image (dib0700_jumpram in the Linux driver).
FIRMWARE_ADDRESS = 0x70000000

TIMEOUT_MS = 1000


class Link:
    """What a host must provide.  One method per USB transfer type."""

    def control_out(self, request, value, index, data):
        """Vendor OUT control transfer; returns the number of bytes sent."""
        raise NotImplementedError

    def control_in(self, request, value, index, length):
        """Vendor IN control transfer; returns the bytes read."""
        raise NotImplementedError

    def bulk_out(self, data, endpoint=EP_FIRMWARE_OUT):
        raise NotImplementedError

    def bulk_in(self, size, timeout=TIMEOUT_MS, endpoint=EP_TS_IN):
        raise NotImplementedError

    # Recovery (state 3) uses these two when the host has them.  They are deliberately optional:
    # a host that cannot reset the bus or clear a halt (Android's UsbDeviceConnection has no
    # reset_device, for one) raises NotImplementedError and :meth:`Dib0700.recover` skips the step
    # and goes on with the forced upload and the replay.
    def reset(self):
        """Bus reset, the same operation as ``libusb_reset_device``."""
        raise NotImplementedError

    def clear_halt(self, endpoint):
        """Clear a stalled endpoint's halt (``CLEAR_FEATURE(HALT)``)."""
        raise NotImplementedError


class LinkError(RuntimeError):
    pass


def enable_video(link, on=True, master=True, channel=0):
    """Start or stop the hardware TS engine (``REQUEST_ENABLE_VIDEO``).

    Byte 1 carries ``onoff << 4``, byte 2 is master mode (``0x10``) OR'd with
    the channel bitmap, so adapter 0 starting is ``0f 10 11 00`` and stopping
    is ``0f 00 10 00``.  The capture agrees byte for byte; getting byte 2
    wrong leaves the engine silent even though the transfer succeeds.
    """
    state = (1 << channel) if on else 0
    data = bytes([REQUEST_ENABLE_VIDEO,
                  0x10 if on else 0x00,
                  (0x10 if master else 0x00) | state,
                  0x00])
    return link.control_out(REQUEST_ENABLE_VIDEO, 0, 0, data)


def get_version(link):
    """Firmware version as a 32-bit integer (0x10200 for 1.20.0), 0 if unknown.

    A cold device has no firmware to answer with, so a stalled or failed
    request means "no firmware yet", not an error.
    """
    try:
        reply = link.control_in(REQUEST_GET_VERSION, 0, 0, 16)
    except Exception:
        return 0
    if len(reply) < 12:
        return 0
    return (reply[8] << 24) | (reply[9] << 16) | (reply[10] << 8) | reply[11]

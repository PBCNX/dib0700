# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (c) 2026 The DIB0700 Open Driver Authors.
#
# Our own code, no vendor code.

"""pyusb implementation of the DIB0700 link.

The device needs a WinUSB/libusb binding (Zadig on Windows, the usual udev rule
on Linux).  Besides the firmware endpoint it uses vendor control transfers for
every command, so a plain bulk-only binding is not enough -- libusb handles
both, which is why this driver uses libusb rather than a bulk-only shim.

pyusb is not vendored here: it is imported from the sibling ``smsrio`` checkout
when it is not installed, because both drivers talk to the same kind of device
on the same machine.
"""
import os
import sys

VID = 0x10B8
PID_DIB0700 = 0x1FA0
INTERFACE = 0

from .link import (EP_FIRMWARE_OUT, EP_TS_IN, VENDOR_IN, VENDOR_OUT,
                   Link, LinkError, TIMEOUT_MS)


def _util():
    _ensure_usb()
    import usb.util

    return usb.util


def _vendor():
    """A vendor folder that carries pyusb (a usb/ package), if any.

    This repo does not vendor pyusb (it is a pip dependency); the sibling SMSRIO line keeps a
    copy, so fall back to it rather than failing on a fresh machine.
    """
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    for path in (os.path.join(root, "vendor"),
                 os.path.join(os.path.dirname(root), "SMSRIO", "research", "vendor")):
        if os.path.isdir(os.path.join(path, "usb")):
            return path
    return None


def _ensure_usb():
    try:
        import usb.core  # noqa: F401
    except Exception:
        vendor = _vendor()
        if vendor and os.path.isdir(os.path.join(vendor, "usb")) and vendor not in sys.path:
            sys.path.insert(0, vendor)
        import usb.core  # noqa: F401


def _libusb_dll():
    """The bundled 64-bit libusb, if this checkout carries it (Windows)."""
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    for vendor in (os.path.join(root, "vendor"),
                   os.path.join(os.path.dirname(root), "SMSRIO", "research", "vendor")):
        candidate = os.path.join(vendor, "windows", "x64", "libusb-1.0.dll")
        if os.path.exists(candidate):
            return candidate
    return None


def backend():
    _ensure_usb()
    import usb.backend.libusb1 as libusb1

    chosen = os.environ.get("DIB0700_LIBUSB")
    if chosen:
        return libusb1.get_backend(find_library=lambda name: chosen)
    dll = _libusb_dll()
    if os.name == "nt" and dll:
        return libusb1.get_backend(find_library=lambda name: dll)
    return libusb1.get_backend()


def devices():
    _ensure_usb()
    import usb.core

    handle = backend()
    if handle is None:
        raise LinkError(
            "libusb backend unavailable: install libusb, or set DIB0700_LIBUSB to its file "
            "(Windows: libusb-1.0.dll from https://libusb.info)")
    return list(usb.core.find(find_all=True, idVendor=VID, backend=handle))


class UsbLink(Link):
    def __init__(self, device, timeout=TIMEOUT_MS, log=None):
        self.device = device
        self.timeout = timeout
        self.log = log or (lambda message: None)

    def open(self):
        # Deliberately does *not* re-set the configuration and does not touch
        # endpoint halt state before the firmware download.  The Linux driver
        # only ever issues SET_CONFIGURATION during enumeration, and a
        # cold-plug capture of it shows nothing between enumeration and the
        # first firmware record; doing either of those things from user space
        # here resets the ROM's endpoint state and it refuses the jump.
        # Captured on the Pi: with them we send
        #   SET_CONFIGURATION(1), CLEAR_FEATURE(HALT, 0x01), CLEAR_FEATURE(HALT, 0x82)
        # right before the load; without them the wire matches the kernel.
        util = _util()
        try:
            util.claim_interface(self.device, INTERFACE)
        except Exception as error:
            raise LinkError("cannot claim interface %d: %s" % (INTERFACE, error))
        return self

    def close(self):
        # dispose_resources() releases the interface *and* closes the libusb
        # handle.  Releasing only the interface leaves the handle open, and
        # Windows then refuses to open the device again in the same process
        # (LIBUSB_ERROR_ACCESS).
        try:
            _util().dispose_resources(self.device)
        except Exception:
            try:
                _util().release_interface(self.device, INTERFACE)
            except Exception:
                pass

    def control_out(self, request, value, index, data):
        return self.device.ctrl_transfer(VENDOR_OUT, request, value, index,
                                         data, self.timeout)

    def control_in(self, request, value, index, length):
        return bytes(self.device.ctrl_transfer(VENDOR_IN, request, value, index,
                                               length, self.timeout))

    def bulk_out(self, data, endpoint=EP_FIRMWARE_OUT):
        written = self.device.write(endpoint, data, self.timeout)
        if written != len(data):
            raise LinkError("short bulk write: %d of %d" % (written, len(data)))
        return written

    def bulk_in(self, size, timeout=None, endpoint=EP_TS_IN):
        try:
            return bytes(self.device.read(endpoint, size,
                                          timeout or self.timeout))
        except Exception:
            return b""

    def reset(self):
        """``libusb_reset_device`` - recovery's bus-reset step (state 3).

        This is *not* a power cycle: the stick's firmware RAM and the endpoint state survive it,
        which is why recovery follows it with the forced upload.  pyusb's ``Device.reset``
        releases every claimed interface and closes the handle, so the interface is claimed again
        here (the next transfer would reopen the handle anyway); a reset that re-enumerated the
        device under a new address leaves the claim failing, and recovery reports that instead of
        pretending.
        """
        self.device.reset()
        _util().claim_interface(self.device, INTERFACE)

    def clear_halt(self, endpoint):
        """``CLEAR_FEATURE(HALT)`` on one endpoint; a halted 0x82 looks like "locked, no TS"."""
        self.device.clear_halt(endpoint)

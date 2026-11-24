# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (c) 2026 The DIB0700 Open Driver Authors.
#
# Our own code, no vendor code.

"""diB0700 firmware download.

The image (``dvb-usb-dib0700-1.20.fw``, 33,768 bytes) is a stream of **binary
Intel-HEX records**, not text:
    [len u8][addr lo][addr hi][type u8][data: len bytes][checksum u8]

Note the address order: in the file the two address bytes are **little-endian**,
but the boot ROM reads them **big-endian**.  ``dvb_usb_get_hexline`` decodes
``addr = b[1] | (b[2] << 8)`` and ``dib0700_download_firmware`` writes
``buf[1] = addr >> 8``, ``buf[2] = addr``, so every record changes two bytes on
its way to the wire.  Sending the file verbatim instead scrambles the image:
0x0010 becomes 0x1000, the ROM still ACKs every record (a two-byte swap leaves
the checksum sum unchanged), and the jump lands in wrong memory.

The host sends each record in that wire form on bulk OUT ``0x01`` (``len + 5``
bytes), then issues ``JUMPRAM`` to ``0x70000000`` and waits half a second for
the firmware to start.  That mirrors ``dib0700_download_firmware`` and
``dib0700_jumpram`` byte for byte - measured against a kernel capture.

The ROM answers each ``JUMPRAM`` on bulk IN ``0x81`` with 12 bytes::

    [status u32][entry u32][word u32]      all big-endian

Big-endian because the image is SPARC code and the ROM encodes the entry the
same way ``dib0700_jumpram`` does (``address >> 24`` first).  ``status`` is 0
when the image it holds is complete and the jump was taken; a jump without an
image answers ``0x80000051``.  ``upload`` logs this, because it is the only
diagnostics a cold stick offers.

The image is vendor material: pass the path in, never bundle it. Private
builds may ship it, public ones must not. ``find_image`` looks at an explicit
path, then ``DIB0700_FIRMWARE``, then the private checkout's ``vendor/firmware/``
and the working directory; an installed package never carries it.
"""
import os
import struct
import time
from collections import namedtuple

from .link import (EP_STATUS_IN, FIRMWARE_ADDRESS, REQUEST_JUMPRAM,
                   LinkError, get_version)

# Where the vendor image can be found. It is never bundled: vendor material, supplied by the
# user; the private checkout keeps it under vendor/firmware so the local checkout works out of the box.
ENV = "DIB0700_FIRMWARE"
IMAGE_NAME = "dvb-usb-dib0700-1.20.fw"
PACKAGE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(PACKAGE)


def candidates(path=None):
    """The paths to try, in order: explicit, env, then the private checkout and the cwd."""
    if path:
        return [path]
    found = []
    if os.environ.get(ENV):
        found.append(os.environ[ENV])
    found.append(os.path.join(REPO, "vendor", "firmware", IMAGE_NAME))
    found.append(os.path.join(os.getcwd(), "vendor", "firmware", IMAGE_NAME))
    found.append(os.path.join(os.getcwd(), IMAGE_NAME))
    return found


def find_image(path=None):
    """The vendor firmware bytes, or None when nothing was found and nothing was asked for.

    An explicit path or ``DIB0700_FIRMWARE`` that does not exist raises ``FileNotFoundError``:
    a caller who named an image should not silently fall through to a different one.
    """
    explicit = bool(path) or bool(os.environ.get(ENV))
    for candidate in candidates(path):
        if os.path.isfile(candidate):
            with open(candidate, "rb") as handle:
                return handle.read()
    if explicit:
        raise FileNotFoundError(
            "firmware image not found: %s" % (path or os.environ.get(ENV)))
    return None


HEADER = 5  # len, address (2), type, checksum

# The cold ROM reports the result of a JUMPRAM here.
STATUS_ENDPOINT = EP_STATUS_IN
STATUS_LENGTH = 12

RomStatus = namedtuple("RomStatus", "status entry word")


def parse_status(reply):
    """Decode the ROM's 12-byte jump report, or None when there is none."""
    if reply is None or len(reply) < STATUS_LENGTH:
        return None
    status, entry, word = struct.unpack(">III", bytes(reply[:STATUS_LENGTH]))
    return RomStatus(status, entry, word)


def rom_status(link, timeout=300):
    """Ask the ROM what it thought of the last jump; None if it said nothing."""
    try:
        return parse_status(link.bulk_in(STATUS_LENGTH, timeout,
                                         endpoint=STATUS_ENDPOINT))
    except Exception:
        return None


def records(image):
    """Split the image into its records, checking each checksum."""
    out = []
    pos = 0
    size = len(image)
    while pos < size:
        length = image[pos]
        end = pos + HEADER + length
        if end > size:
            raise ValueError("truncated firmware record at %d" % pos)
        addr_hi, addr_lo, kind = image[pos + 1], image[pos + 2], image[pos + 3]
        checksum = image[end - 1]
        total = (length + addr_hi + addr_lo + kind
                 + sum(image[pos + 4:end - 1]) + checksum) & 0xFF
        if total:
            raise ValueError("bad checksum in record at %d" % pos)
        out.append(image[pos:end])
        pos = end
    return out


def jump_ram(link, address=FIRMWARE_ADDRESS):
    """Start the downloaded image (8 bytes on the firmware endpoint)."""
    data = bytes([REQUEST_JUMPRAM, 0, 0, 0,
                  (address >> 24) & 0xFF, (address >> 16) & 0xFF,
                  (address >> 8) & 0xFF, address & 0xFF])
    return link.bulk_out(data)


def wire_record(record):
    """The record as the boot ROM receives it: address big-endian.

    ``dvb_usb_get_hexline`` reads the file's address field little-endian and
    ``dib0700_download_firmware`` writes it big-endian, so the two address bytes
    swap.  The checksum byte is left alone, exactly as the kernel leaves it: a
    swap keeps the byte sum unchanged, so the record still checks out.
    """
    length = record[0]
    address = record[1] | (record[2] << 8)
    if record[3] == 0x04 and length >= 2:
        # extended linear address: the upper 16 bits live in the data field
        address |= (record[4] << 24) | (record[5] << 16)
    out = bytearray(record)
    out[1] = (address >> 8) & 0xFF
    out[2] = address & 0xFF
    return bytes(out)


def upload(link, image, log=None, settle=0.5, listen=True):
    """Send every record, start the image, and return the firmware version."""
    log = log or (lambda message: None)
    parts = records(image)
    log("firmware: %d records, %d bytes" % (len(parts), len(image)))
    for index, record in enumerate(parts):
        try:
            link.bulk_out(wire_record(record))
        except Exception as error:
            raise LinkError("record %d of %d failed: %s" % (index, len(parts), error))
    jump_ram(link)
    if listen:
        status = rom_status(link)
        if status is not None:
            log("boot rom: status 0x%08x entry 0x%08x word 0x%08x"
                % (status.status, status.entry, status.word))
            if status.status:
                log("boot rom did not take the jump (entry 0x%08x)" % status.entry)
    time.sleep(settle)
    version = get_version(link)
    log("firmware version 0x%05x" % version)
    return version


def describe(image):
    """Human-readable summary, for the CLI and for tests."""
    parts = records(image)
    kinds = {}
    for record in parts:
        kinds[record[3]] = kinds.get(record[3], 0) + 1
    return {"records": len(parts), "bytes": len(image), "types": kinds}

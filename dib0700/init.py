# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (c) 2026 The DIB0700 Open Driver Authors.
#
# Our own code, no vendor code.

"""Replay the captured dib0700 init sequence.

``dib0700/resources/init.json`` is the ordered list of control transfers a working Linux
session issued after firmware load: 442 six-byte I2C writes, 46 five-byte ones,
434 two-byte I2C reads and two version queries.  Addressing the demod and the
tuner directly over I2C is what makes a replay practical without porting the
whole dib8000 driver.

The read results matter: the Linux driver branches on them (chip IDs), so a
replay issues them too.  ``verify`` compares each reply with the captured one
and reports the first difference, which is a useful way to spot a device that
is in a different state.
"""
import json
import os
import time

RESOURCES = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                         "resources", "init.json")

# Requests the dib0700 firmware answers.  A capture also contains enumeration
# traffic - GET_DESCRIPTOR and friends - which is sent with bmRequestType 0x80
# on the wire but would go out as a *vendor* request here, and the firmware
# stalls it.  One such record is enough to abort the whole replay.
VENDOR_REQUESTS = frozenset((
    0x00,  # SET_USB_XFER_LEN
    0x02,  # I2C_READ
    0x03,  # I2C_WRITE
    0x0B,  # SET_CLOCK
    0x0C,  # SET_GPIO
    0x0F,  # ENABLE_VIDEO
    0x15,  # GET_VERSION
))


def load(path=None):
    path = path or RESOURCES
    with open(path, encoding="utf-8") as handle:
        sequence = json.load(handle)
    return [record for record in sequence
            if record.get("request") in VENDOR_REQUESTS]


def replay(link, sequence, log=None, verify=False, timed=True, cap=1.0):
    """Send every recorded transfer in order; returns a small summary.

    ``timed`` reproduces the gaps of the captured run.  They are part of the
    protocol, not noise: the Linux driver waits for the demod to come out of
    reset before it starts reading registers, and replaying flat-out leaves the
    chip unresponsive (every read back comes back as 0x0000).
    """
    log = log or (lambda message: None)
    writes = reads = mismatches = stalls = 0
    first_mismatch = None
    previous = None
    for index, record in enumerate(sequence):
        if timed and previous is not None and "t" in record:
            delay = record["t"] - previous
            if delay > 0.002:
                time.sleep(min(delay, cap))
        previous = record.get("t", previous)
        try:
            if record["dir"] == "out":
                link.control_out(record["request"], record["value"], record["index"],
                                 bytes.fromhex(record["data"]))
                writes += 1
                continue
            reply = link.control_in(record["request"], record["value"],
                                    record["index"], record["length"])
        except Exception as error:
            # The captured kernel run stalls several early I2C transfers while
            # the demod comes out of reset (status -32 in cold.pcap).  A control
            # endpoint clears its stall on the next SETUP, so continue: aborting
            # here is what made a correct replay look broken.
            stalls += 1
            if stalls <= 5:
                log("init: record %d (%02x) stalled: %s"
                    % (index, record["request"], error))
            continue
        reads += 1
        expected = record.get("reply")
        if verify and expected:
            if bytes(reply or b"") != bytes.fromhex(expected):
                mismatches += 1
                if first_mismatch is None:
                    first_mismatch = (record["index"], expected, bytes(reply or b"").hex())
    summary = {"writes": writes, "reads": reads, "mismatches": mismatches,
               "stalls": stalls}
    log("init replay: %d writes, %d reads, %d stalls, %d mismatches"
        % (writes, reads, stalls, mismatches))
    if first_mismatch:
        index, expected, actual = first_mismatch
        log("first mismatch at index 0x%04x: captured %s, device %s"
            % (index, expected, actual))
    return summary

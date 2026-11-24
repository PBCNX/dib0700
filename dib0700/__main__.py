# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (c) 2026 The DIB0700 Open Driver Authors.
#
# Our own code, no vendor code.

"""Command line for the DIB0700.

    python -m dib0700 list
    python -m dib0700 probe --firmware path/to/dvb-usb-dib0700-1.20.fw --freq 599143
    python -m dib0700 play  --freq 599143 --out - --status-interval 2
    python -m dib0700 play  --freq 479143 --drop-null --udp 127.0.0.1:5000
    python -m dib0700 scan  --start 473143 --stop 509143 --step 6000
    python -m dib0700 tmcc  --freq 479143
    python -m dib0700 iip   --freq 599143 --seconds 10

Streams go to stdout (or a file, or UDP); every message goes to stderr.
"""
import argparse
import json
import socket
import sys
import time

from . import PROVENANCE, __version__
from . import firmware
from . import iip
from . import tune as tune_module
from .device import RECOVER_FAILED, Dib0700
from .link import LinkError
from .usb import PID_DIB0700, UsbLink, devices

TS_PACKET = 188
UDP_PACKETS = 7
UDP_SIZE = TS_PACKET * UDP_PACKETS
# A locked demod with no TS from the start is the state-3 trigger in ``play``; after a failed
# recovery the replug hint is the last word, and the recovery that succeeds after the window has
# closed gets this one bounded drain (never a retry loop).
WEDGE_GRACE = 2.0
RECOVER_DRAIN = 5.0


def log(message):
    """All messages go to stderr; stdout carries the stream only."""
    print(message, file=sys.stderr)


def open_device(index=0, logger=None):
    found = [d for d in devices() if d.idProduct == PID_DIB0700]
    if not found:
        raise SystemExit("no DIB0700 (10b8:1fa0) found; bind it to WinUSB/libusb first")
    if index >= len(found):
        raise SystemExit("only %d device(s) found" % len(found))
    return UsbLink(found[index], log=logger).open()


def read_image(path):
    """The firmware bytes for this run: explicit path, DIB0700_FIRMWARE, or the checkout's copy."""
    return firmware.find_image(path)


def tune_frequency(args):
    """The kHz the bring-up should tune, or None when tuning is off."""
    if args.no_tune:
        return None
    return args.freq or tune_module.DEFAULT_KHZ


def status_text(device, packets):
    """The player's status line, exactly: ts packets N lock True|False strength S quality Q."""
    strength = device.strength_percent()
    quality = device.quality_percent()
    return "ts packets %d lock %s strength %s quality %s" % (
        packets, device.locked(),
        "n/a" if strength is None else strength,
        "n/a" if quality is None else quality)


def drop_null(data):
    """Strip PID 0x1FFF packets, whole packets only."""
    out = bytearray()
    for offset in range(0, len(data) - TS_PACKET + 1, TS_PACKET):
        packet = data[offset:offset + TS_PACKET]
        if packet[0] == 0x47:
            pid = ((packet[1] & 0x1F) << 8) | packet[2]
            if pid == 0x1FFF:
                continue
        out += packet
    return bytes(out)


class UdpSink:
    """Datagrams of seven 188-byte packets; a partial tail waits for the next write."""

    def __init__(self, host, port):
        self.address = (host, int(port))
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.buffer = bytearray()

    def write(self, data):
        self.buffer += data
        while len(self.buffer) >= UDP_SIZE:
            self.socket.sendto(bytes(self.buffer[:UDP_SIZE]), self.address)
            del self.buffer[:UDP_SIZE]

    def close(self):
        if self.buffer:
            self.socket.sendto(bytes(self.buffer), self.address)
        self.socket.close()


def parse_udp(text):
    host, _, port = text.rpartition(":")
    return (host or "127.0.0.1"), int(port)


def cmd_list(args):
    found = False
    for device in devices():
        found = True
        supported = "supported" if device.idProduct == PID_DIB0700 else "unsupported"
        print("10b8:%04x  bus %s addr %s  %s"
              % (device.idProduct, device.bus, device.address, supported))
    if not found:
        print("no 10b8 devices found")
    return 0


def cmd_probe(args):
    link = open_device(args.index, log)
    try:
        device = Dib0700(link, log, cache=not args.no_cache)
        version = device.bring_up(read_image(args.firmware), tune=tune_frequency(args),
                                  sweep=not args.no_sweep)
        log("firmware: 0x%05x" % version)
        started = time.time()
        total = 0
        while time.time() - started < args.seconds:
            total += len(device.read_ts(timeout=500))
        print("ts: %d bytes, %d packets, %d sync errors"
              % (total, total // TS_PACKET, device.stream.sync_errors))
        return 0
    finally:
        link.close()


MODULATION = {0: "DQPSK", 1: "QPSK", 2: "16QAM", 3: "64QAM"}
GUARD = {0: "1/32", 1: "1/16", 2: "1/8", 3: "1/4"}
# The demod's TMCC code-rate field is 1-based, per dib8000_get_frontend: 1=1/2, 2=2/3, 3=3/4,
# 5=5/6, and anything else (0, 4, 6, 7) is 7/8. The layer word keeps the raw value; only the
# label goes through this table. Interleave index 3 is the label I4 (STD-B31 Table 3-30).
FEC = {1: "1/2", 2: "2/3", 3: "3/4", 5: "5/6"}
INTERLEAVE = {0: "I0", 1: "I1", 2: "I2", 3: "I4"}


def fec_text(value):
    return FEC.get(value, "7/8")


def tmcc_text(khz, decoded):
    """The human form of the TMCC record — the same fields a host would show."""
    layers = " ".join(
        "L%d:%s %dseg code %s %s" % (index, MODULATION.get(layer["modulation"], "?"),
                                     layer["segments"], fec_text(layer["fec"]),
                                     INTERLEAVE.get(layer["interleaving"], "I?"))
        for index, layer in enumerate(decoded["layers"]))
    return ("%d kHz tmcc mode %s guard %s partial %d inversion %d sb %d ewbs %d %s"
            % (khz, {1: "2K", 2: "4K", 3: "8K"}.get(decoded["mode"], "?"),
               GUARD.get(decoded["guard"], "?"), decoded["partial"], decoded["inversion"],
               decoded["sb"], decoded.get("ewbs", 0), layers))


def cmd_tmcc(args):
    link = open_device(args.index, log)
    device = Dib0700(link, log, cache=not args.no_cache)
    device.bring_up(read_image(args.firmware), tune=args.freq, sweep=not args.no_sweep)
    report = device.tmcc()
    if args.json:
        print(json.dumps(report, default=str))
    elif report["plausible"]:
        print(tmcc_text(args.freq, report["decoded"]))
    else:
        # An unlocked block decodes to nonsense (all-zero gives 15-segment layers, i.e. "2K/1/32").
        # Refuse it, like LME's decoder does, instead of printing fields the demod never reported.
        print("%d kHz: no usable TMCC (implausible block - unlocked, or the block reads all-zero)"
              % args.freq)
    return 0 if report["plausible"] else 1


def cmd_iip(args):
    """Read the stream and report the IIP (PID 0x1FF0): mode, guard, partial, EWBS and the layers.

    One IIP TSP per multiplex frame describes the **next** frame, so the packet is rare (≈4.6/s on
    hardware); each distinct payload is decoded once and each distinct configuration is printed
    once.  Exit 1 when no 0x1FF0 packet was seen at all.
    """
    link = open_device(args.index, log)
    try:
        device = Dib0700(link, log, cache=not args.no_cache)
        device.bring_up(read_image(args.firmware), tune=args.freq, sweep=not args.no_sweep)
        seen = {}                   # payload bytes -> decoded record
        configurations = {}         # decoded configuration -> first record that showed it
        packets = 0
        started = time.time()
        while time.time() - started < args.seconds:
            for data in iip.payloads(device.read_ts(timeout=500)):
                packets += 1
                if data in seen:
                    continue
                try:
                    record = iip.decode(data)
                except ValueError as error:
                    log("iip: %s" % error)
                    continue
                seen[data] = record
                configurations.setdefault(iip.configuration(record), record)
        if not seen:
            log("iip: no 0x1ff0 packets in %g s" % args.seconds)
            return 1
        if args.json:
            print(json.dumps({"packets": packets,
                              "distinct_payloads": len(seen),
                              "configurations": list(configurations.values())}))
        else:
            for record in configurations.values():
                print(iip.text(record))
        log("iip: %d packets, %d distinct payloads, %d distinct configuration(s)"
            % (packets, len(seen), len(configurations)))
        return 0
    finally:
        link.close()


def cmd_play(args):
    link = open_device(args.index, log)
    sink = open(args.out, "wb") if args.out and args.out != "-" else sys.stdout.buffer
    udp = None
    if args.udp:
        udp = UdpSink(*parse_udp(args.udp))
    packets = 0
    received = 0

    def emit(data):
        nonlocal packets, received
        received += len(data)
        if args.drop_null:
            data = drop_null(data)
        if not data:
            return
        sink.write(data)
        if udp:
            udp.write(data)
        packets += len(data) // TS_PACKET

    try:
        image = read_image(args.firmware)
        device = Dib0700(link, log, cache=not args.no_cache)
        device.bring_up(image, tune=tune_frequency(args), sweep=not args.no_sweep)
        started = time.time()
        next_status = started + args.status_interval if args.status_interval else None
        # State 3: a locked demod that has delivered nothing is the warm wedge, and a wedged
        # stick must not be escalated to the user.  One recovery attempt in the run - never a
        # retry loop - then the replug hint only if it failed.
        recovered = False
        switched = False
        while not args.seconds or time.time() - started < args.seconds:
            if (args.switch_to and not switched
                    and time.time() - started >= args.switch_after):
                switched = True
                log("switch to %d kHz (same session, same sink)" % args.switch_to)
                if not device.tune_any(args.switch_to, sweep=not args.no_sweep):
                    log("switch to %d kHz failed; continuing the stream" % args.switch_to)
            data = device.read_ts(timeout=500)
            if data:
                emit(data)
            elif (not recovered and received == 0
                  and time.time() - started >= WEDGE_GRACE and device.locked()):
                recovered = True
                log("no TS while locked: trying the one-call recovery (state 3)")
                outcome = device.recover(image, sweep=not args.no_sweep)
                if outcome == RECOVER_FAILED:
                    log("no TS while locked: unplug and replug the stick "
                        "(a USB reset is not a power cycle)")
                    return 1
            if next_status is not None and time.time() >= next_status:
                log(status_text(device, packets))
                next_status += args.status_interval
        if received == 0 and not recovered and device.locked():
            # The run was shorter than the in-loop grace: the same single attempt, then the hint.
            recovered = True
            log("no TS while locked: trying the one-call recovery (state 3)")
            if device.recover(image, sweep=not args.no_sweep) == RECOVER_FAILED:
                log("no TS while locked: unplug and replug the stick "
                    "(a USB reset is not a power cycle)")
                return 1
            # The stream came back after the window closed: deliver one bounded window of it, so
            # the caller gets the TS the recovery restored rather than only the log line.
            deadline = time.time() + RECOVER_DRAIN
            while time.time() < deadline:
                emit(device.read_ts(timeout=250))
        if recovered:
            log("recovered: %d packets after the one-call recovery" % packets)
        log("wrote %d packets, %d sync errors" % (packets, device.stream.sync_errors))
        return 0
    finally:
        if udp:
            udp.close()
        if sink is not sys.stdout.buffer:
            sink.close()
        link.close()


def cmd_recover(args):
    """Run the one-call state-3 recovery and report the outcome, the pair and the lock."""
    link = open_device(args.index, log)
    device = Dib0700(link, log)
    if args.freq is not None:
        # The recover re-tunes the last frequency; on a fresh process that is the flag.
        device.khz = args.freq
    image = read_image(args.firmware) if args.firmware else None
    outcome = device.recover(image, sweep=not args.no_sweep)
    log("recover: outcome %s, lock %s" % (outcome, device.locked()))
    return 0 if outcome != RECOVER_FAILED else 1


def cmd_scan(args):
    """Lock sweep over the PH raster; captured frequencies replay, the rest use the procedure."""
    link = open_device(args.index, log)
    try:
        device = Dib0700(link, log, cache=not args.no_cache)
        device.bring_up(read_image(args.firmware), sequence=[], tune=None)
        # The fast path's template: a *captured* channel that locked.  ``retune`` replays the
        # captured tune procedure (PLL + calibration + search + TMCC patch), so it works from any
        # current state - but it needs the capture's records, which an uncaptured channel cannot
        # supply.  A scan therefore anchors on the last captured channel that locked.
        anchor = None
        khz = args.start
        while khz <= args.stop:
            if anchor is not None and not args.no_chain:
                # A scan is discovery, not rescue: a missed chained retune is "no lock" here.
                # ``--no-chain`` gives the thorough per-channel path (base replay + sweep).
                locked = device.retune(khz, base=anchor, sweep=not args.no_sweep)
            else:
                locked = device.tune(khz, sweep=not args.no_sweep)
            packets = 0
            deadline = time.time() + args.seconds
            while time.time() < deadline:
                packets += len(device.read_ts(timeout=200)) // TS_PACKET
            strength = device.strength_percent()
            if locked and packets > 0:
                print("%d kHz LOCK strength %s"
                      % (khz, "n/a" if strength is None else strength))
                if tune_module.has_session(khz):
                    anchor = khz
                elif anchor is None:
                    anchor = tune_module.nearest(khz)
            else:
                print("%d kHz no lock strength %s"
                      % (khz, "n/a" if strength is None else strength))
            khz += args.step
        return 0
    finally:
        link.close()


def cmd_upload(args):
    """Download the image and report what the boot ROM said about the jump."""
    image = read_image(args.firmware)
    if image is None:
        raise SystemExit("no firmware image: pass --firmware or set DIB0700_FIRMWARE")
    link = open_device(args.index, log)
    try:
        return 0 if firmware.upload(link, image, log, settle=args.settle) else 1
    finally:
        link.close()


def add_device_args(parser):
    parser.add_argument("--index", type=int, default=0)
    parser.add_argument("--firmware", metavar="PATH",
                        help="vendor dvb-usb-dib0700-1.20.fw; default: $DIB0700_FIRMWARE or "
                             "vendor/firmware/ in the checkout")
    parser.add_argument("--no-cache", action="store_true",
                        help="do not keep the per-frequency zap cache (the cached PLL + channel "
                             "block a revisit replays instead of the full tune)")


def build_parser():
    parser = argparse.ArgumentParser(prog="dib0700", description=__doc__)
    parser.add_argument("--version", action="version",
                        version="%(prog)s " + __version__ + " (" + PROVENANCE + ")")
    # The image is a property of the program, not of one command: `--firmware PATH` before the
    # command name must work for every command that opens the device (the guide teaches that
    # order). A command's own --firmware still wins.
    parser.add_argument("--firmware", metavar="PATH", dest="firmware_app",
                        help="vendor dvb-usb-dib0700-1.20.fw; default: $DIB0700_FIRMWARE or "
                             "vendor/firmware/ in the checkout")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("list", help="list 10b8 devices").set_defaults(func=cmd_list)

    upload = sub.add_parser("upload", help="download the image and report the ROM's verdict")
    upload.add_argument("--index", type=int, default=0)
    upload.add_argument("--firmware", metavar="PATH",
                        help="vendor dvb-usb-dib0700-1.20.fw; default: $DIB0700_FIRMWARE or "
                             "vendor/firmware/ in the checkout")
    upload.add_argument("--settle", type=float, default=0.5)
    upload.set_defaults(func=cmd_upload)

    for name, func, text in (("probe", cmd_probe, "bring up and count packets"),
                             ("play", cmd_play, "stream MPEG-TS")):
        p = sub.add_parser(name, help=text)
        add_device_args(p)
        p.add_argument("--freq", type=int, metavar="KHZ",
                       help="frequency in kHz (default %d); uncaptured ones use the tune "
                            "procedure" % tune_module.DEFAULT_KHZ)
        p.add_argument("--seconds", type=float,
                       default=None if name == "play" else 5.0,
                       help="stop after this many seconds; play without it runs until killed")
        p.add_argument("--no-tune", action="store_true",
                       help="bring up without replaying a tune")
        p.add_argument("--no-sweep", action="store_true",
                       help="do not try the twelve mode/guard combinations when a captured "
                            "replay (or a retune) does not lock")
        p.add_argument("--status-interval", type=float, default=0.0, metavar="N",
                       help="one status line on stderr every N seconds; 0 = off")
        p.set_defaults(func=func)
        if name == "play":
            p.add_argument("--out", default="-", help="file, - for stdout")
            p.add_argument("--drop-null", action="store_true",
                           help="drop PID 0x1FFF packets before writing")
            p.add_argument("--udp", metavar="HOST:PORT",
                           help="also send the TS as UDP datagrams of 7 x 188 bytes")
            p.add_argument("--switch-to", type=int, metavar="KHZ",
                           help="zap to this frequency after --switch-after seconds, in the same "
                                "session and to the same sink (the host-app zap)")
            p.add_argument("--switch-after", type=float, default=5.0, metavar="SEC",
                           help="when to zap, in seconds (default 5.0)")

    recover = sub.add_parser("recover",
                              help="state-3 recovery: reset, force firmware, replay, re-tune")
    recover.add_argument("--freq", type=int, metavar="KHZ",
                         help="frequency the recovery should re-tune to (the last channel)")
    recover.add_argument("--firmware", metavar="FILE", help="the firmware image")
    recover.add_argument("--index", type=int, default=0, metavar="N",
                         help="which 10b8 device (0-based)")
    recover.add_argument("--no-sweep", action="store_true",
                         help="do not try the mode/guard combinations if the direct search fails")
    recover.set_defaults(func=cmd_recover)

    scan = sub.add_parser("scan", help="lock sweep over the PH raster")
    add_device_args(scan)
    scan.add_argument("--start", type=int, default=473143, help="first kHz (default 473143)")
    scan.add_argument("--stop", type=int, default=509143, help="last kHz (default 509143)")
    scan.add_argument("--step", type=int, default=6000, help="step in kHz (default 6000)")
    scan.add_argument("--seconds", type=float, default=2.0,
                      help="seconds of stream per channel (default 2)")
    scan.add_argument("--no-sweep", action="store_true",
                      help="do not try the twelve mode/guard combinations on a miss")
    scan.add_argument("--no-chain", action="store_true",
                      help="do not retune from the last locked channel (the fast path); replay a "
                           "base for every channel instead")
    scan.set_defaults(func=cmd_scan)

    tmcc = sub.add_parser("tmcc", help="print the demod's ISDB-T TMCC report")
    add_device_args(tmcc)
    tmcc.add_argument("--freq", type=int, metavar="KHZ", required=True, help="frequency in kHz")
    tmcc.add_argument("--json", action="store_true", help="print the record as JSON")
    tmcc.add_argument("--no-sweep", action="store_true",
                      help="do not try the twelve mode/guard combinations on a miss")
    tmcc.set_defaults(func=cmd_tmcc)

    iip_cmd = sub.add_parser("iip", help="read the ISDB-T Information Packet (PID 0x1ff0)")
    add_device_args(iip_cmd)
    iip_cmd.add_argument("--freq", type=int, metavar="KHZ", required=True,
                         help="frequency in kHz")
    iip_cmd.add_argument("--seconds", type=float, default=10.0,
                         help="seconds of stream to read (default 10)")
    iip_cmd.add_argument("--json", action="store_true", help="print the records as JSON")
    iip_cmd.add_argument("--no-sweep", action="store_true",
                         help="do not try the twelve mode/guard combinations on a miss")
    iip_cmd.set_defaults(func=cmd_iip)
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    if getattr(args, "firmware_app", None) and not getattr(args, "firmware", None):
        args.firmware = args.firmware_app
    try:
        return args.func(args)
    except LinkError as error:
        print("dib0700: %s" % error, file=sys.stderr)
        return 1
    except (RuntimeError, ValueError, OSError) as error:
        # A wrong image fails its record checksum (ValueError) and a missing one is an OSError:
        # both are one line with a non-zero exit, never a traceback.
        print("dib0700: %s" % error, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main())

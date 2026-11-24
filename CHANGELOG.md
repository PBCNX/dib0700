# Changelog

First public release. Nothing was published under an earlier name.

## 0.3.0 — 2026-11-24

- One Python package, `dib0700`: `python -m dib0700` runs the command line, and the package
  implements the Java tuner contract through the Android adapter in `java/`.
- Java: a dependency-free core and the `UsbDeviceConnection` adapter, both in `java/`; the
  `com.pbcnx.tuner` contract is compiled against and not bundled.
- Cold start from user space: the firmware image is uploaded on every attach (the device has no
  flash), then the captured init and tune session for the frequency is replayed.
- Tuning: a captured session per frequency; any other frequency replays the nearest capture and
  re-runs the tune procedure, with a mode/guard acquisition sweep on a miss.
- Recovery: one in-place call rebuilds a device that stopped streaming, and `play` calls it once.
- Reader: the driver counts its own MPEG-TS packets and resyncs, and reports them on stderr.
- TMCC: mode, guard interval, partial reception, the EWBS flag and the three layers are decoded,
  with an explicit unknown instead of a guessed value.
- IIP: the ISDB-T Information Packet (PID `0x1FF0`) is decoded and its CRC-32 checked.
- Command line: a machine with no libusb, no device or no firmware image gets one line and a
  non-zero exit, not a traceback.
- Firmware is vendor material: never distributed in this repository, and its record checksums are
  verified before use.

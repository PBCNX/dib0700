# dib0700 — DiBcom DIB0700 ISDB-T dongle (`10b8:1fa0`)

User-space driver for the ISDB-T dongle sold as the **Geniatech MyGica S870** (DiBcom DIB0700,
`10b8:1fa0`). It speaks the device's own USB protocol, uploads its firmware, tunes, and streams
MPEG-TS to a file, stdout or UDP; it also reads the multiplex's transmission parameters (TMCC) and
the ISDB-T Information Packet (IIP). Python over libusb (pyusb is the only dependency); a
dependency-free Java core and Android USB adapter live in `java/`.

**Our own code, no vendor code.**

## Device

| | |
|---|---|
| USB ID | `10b8:1fa0` |
| USB product string | `STK8096GP` (manufacturer string `DiBcom`) |
| Chip | DiBcom DIB0700 USB bridge, dib8000 demodulator, dib0090 tuner |
| Endpoints | `0x01` OUT firmware, `0x81` IN cold-ROM status, `0x82` IN MPEG-TS |
| Status | lock, strength from the AGC word, and quality as the share of clean packets (this part has no chip C/N) |
| TMCC | mode (2K/4K/8K), guard interval, partial reception, EWBS, and layers A/B/C (modulation, segments, code rate, interleave) |
| IIP | PID `0x1FF0`, one packet per multiplex frame, CRC-32 checked |
| Tune | a captured session is replayed per frequency; any other frequency runs the tune procedure, with a mode/guard acquisition sweep on a miss |

## Platforms

Windows 11, Linux (Raspberry Pi 3B, aarch64) and macOS 15.7.9 (Intel) are measured on hardware;
Android is measured on a LEAP-S1 (Android 14). Apple Silicon is not yet tested. No kernel driver is
involved: libusb over WinUSB on Windows, udev on Linux, libusb on macOS.

## Install

From a checkout. `pyusb` is the only Python dependency:

```bash
python -m pip install "pyusb>=1.2"
python -m dib0700 --version      # dib0700 0.3.0 (dib0700-pbcnx)
python -m dib0700 list           # lists the 10b8 devices present
```

- **Windows**: bind the device to WinUSB with [Zadig](https://zadig.akeo.ie/) and replug it, then get
  `libusb-1.0.dll` from <https://libusb.info> and set `DIB0700_LIBUSB` to its full path (or copy it
  next to `python.exe`). Zadig is the device binding; libusb is the library the driver loads. No DLL
  is bundled with this driver.
- **Linux**: `sudo cp udev/99-dib0700.rules /etc/udev/rules.d/ && sudo udevadm control --reload && sudo udevadm trigger`,
  then replug. The rule grants `uaccess` plus group `plugdev` (needed over SSH, where there is no
  logind seat).
- **macOS**: install libusb (`brew install libusb`).

**Firmware.** The image is not in this repository — it is vendor material. Supply your own with
`--firmware PATH` or `DIB0700_FIRMWARE`; every record's checksum is verified before it is sent.
`--version` and `list` work without it; anything that opens the S870 needs it.

## Use

```bash
python -m dib0700 list
python -m dib0700 play  --freq 479143 --seconds 30 --out capture.ts
python -m dib0700 tmcc  --freq 479143
python -m dib0700 iip   --freq 479143 --seconds 10
python -m dib0700 scan  --start 473143 --stop 695143 --step 6000
```

Android / JVM, through the `com.pbcnx.tuner` contract:

```java
Tuner tuner = new Dib0700Provider().open(context, device, firmware, listener);
tuner.open();
tuner.tune(479143);
tuner.start();
```

## License

GPL-2.0-or-later — see `LICENSE`. `NOTICE` lists what is not ours.

Copyright (c) 2026 The DIB0700 Open Driver Authors.

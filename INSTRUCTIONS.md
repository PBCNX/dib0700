# How to use the DIB0700 driver

This guide is for anyone who has the tuner and wants to watch or record TV with it. You do not need
to be a programmer. Follow the numbered steps in order.

---

## 1. What you need

1. The stick itself: the ISDB-T dongle sold as the **Geniatech MyGica S870** (DiBcom DIB0700). Its
   USB ID is `10b8:1fa0`.
2. A computer running Windows, Linux or macOS with **Python 3.9 or newer**. Typing `python --version`
   should show that number, or higher.
3. The **firmware file** for this device. The device has no flash memory, so it is blank every time it
   is plugged in, and this file is what brings it to life. Section 2 tells you where to put it.
4. The driver itself: this repository, downloaded as a ZIP or cloned with git.

The driver runs from its folder; you do not have to install it. It needs the `pyusb` package, which
one command installs:

```bash
python -m pip install "pyusb>=1.2"
```

---

## 2. Where to put the firmware file

This file is the manufacturer's, and this repository never ships it. You get it from vendor software
you already have (the application that came with the device): find its firmware file
`dvb-usb-dib0700-1.20.fw` (33,768 bytes), copy it somewhere you will remember, and tell the driver
where it is. The driver looks in this order and stops at the first one it finds:

1. **On the command line, for one run.** Put `--firmware PATH` **before** the command name (it belongs
   to the program, not to the command), and it wins over the other two.

   ```bash
   python -m dib0700 --firmware "C:\Users\you\Downloads\dvb-usb-dib0700-1.20.fw" play --freq 479143 --out capture.ts
   ```

2. **In an environment variable, for every run.** Set `DIB0700_FIRMWARE` to the full path of the file.

   ```powershell
   setx DIB0700_FIRMWARE "C:\Users\you\Downloads\dvb-usb-dib0700-1.20.fw"
   ```

   ```bash
   export DIB0700_FIRMWARE="$HOME/Downloads/dvb-usb-dib0700-1.20.fw"
   ```

   On Windows you can also use *System properties → Environment Variables*. `setx` only affects
   terminals you open afterwards; close the window and open a new one.

3. **In the driver's own folder, as the default.** Put the file at `dib0700/firmware.bin` — inside the
   `dib0700` folder of this repository, named exactly `firmware.bin`. Nothing else needs to be set,
   and every command will find it.

The driver checks every record in the file before it sends it, so a damaged copy is refused instead of
being written to the device. If the file you named does not exist, you see one line:

```text
dib0700: firmware image not found: C:\Users\you\Downloads\dvb-usb-dib0700-1.20.fw
```

**You can do a lot without this file.** `--version`, `list` and `--help` work without it. Tuning and
playing need it, because the device cannot receive until the image has been loaded.

---

## 3. Install

Open a terminal in the folder you downloaded, and stay in that folder for every command in this guide.

### Windows

Windows needs two different things, and one is not the other: **Zadig** tells Windows which driver to
use for the device, and **libusb** is the library the driver itself loads. Do both.

1. Plug in the device.
2. Download **Zadig** from <https://zadig.akeo.ie/> and run it.
3. In Zadig, open the drop-down list, choose the device whose USB ID is `10b8:1fa0`.
4. Set the driver on the right to **WinUSB** and click **Install Driver**. Wait for "The driver was
   installed successfully".
5. Unplug the device and plug it in again. You do this only once.
6. Get **libusb** from <https://libusb.info> (Downloads → Windows), and unzip it. You need the file
   `libusb-1.0.dll` from the 64-bit folder.
7. Put that file where the driver looks for it. The simplest way is to point the driver at it:

   ```powershell
   setx DIB0700_LIBUSB "C:\path\to\libusb-1.0.dll"
   ```

   (Copying `libusb-1.0.dll` next to `python.exe` works too, and needs no variable.) Close the terminal
   and open a new one, because `setx` only affects terminals opened afterwards.
8. Run `python -m dib0700 list` (section 4). You should see the device, or `no 10b8 devices found` if
   it is not plugged in. Either answer means libusb was found; the error in section 8 means it was not.

### Linux

1. Copy the rule that lets your user reach the device without being root:

   ```bash
   sudo cp udev/99-dib0700.rules /etc/udev/rules.d/
   sudo udevadm control --reload
   sudo udevadm trigger
   ```

2. Unplug the device and plug it in again.
3. If you are connected over SSH (no desktop session logged in), add your user to the `plugdev` group
   with `sudo usermod -aG plugdev $USER`, then log out and back in.

### macOS

1. Install libusb with Homebrew: `brew install libusb`.
2. Apple Silicon Macs are not tested yet; the measured macOS run is an Intel Mac (section 4).

---

## 4. First run

Three commands tell you whether everything is in place. Run them in this order.

1. **Check the version.**

   ```bash
   python -m dib0700 --version
   ```

   A good answer is one line:

   ```text
   dib0700 0.3.0 (dib0700-pbcnx)
   ```

2. **Find the device.**

   ```bash
   python -m dib0700 list
   ```

   A good answer names the device and its address, for example:

   ```text
   10b8:1fa0  bus 1 addr 41  supported
   ```

   If it says `no 10b8 devices found`, the device is not plugged in, or Windows has not been told to
   use WinUSB for it (section 3). If it says libusb is not available, do step 6 of section 3.

3. **Ask the driver for its commands.** This needs no device and no firmware:

   ```bash
   python -m dib0700 --help
   ```

   It lists the commands: `list`, `upload`, `probe`, `play`, `recover`, `scan`, `tmcc`, `iip`.

---

## 5. Watch or record

The S870 tunes to one channel at a time. A frequency is given in kHz, so `479143` means 479.143 MHz.

1. **Check whether a channel has a signal.**

   ```bash
   python -m dib0700 scan --start 479143 --stop 479143 --seconds 3
   ```

   A good answer is one line:

   ```text
   479143 kHz LOCK strength 72
   ```

   `no lock` means the channel is empty or the signal is too weak where you are. Try another
   frequency, or move the antenna.

2. **Record 30 seconds to a file.**

   ```bash
   python -m dib0700 play --freq 479143 --seconds 30 --out capture.ts
   ```

   You get `capture.ts` in the current folder. Any player that reads MPEG-TS can open it. Press
   `Ctrl-C` to stop early.

3. **Watch live in VLC, over UDP.** In one terminal:

   ```bash
   python -m dib0700 play --freq 479143 --udp 127.0.0.1:1234
   ```

   And in a second terminal, or in VLC's *Open Network Stream* box:

   ```bash
   vlc udp://@:1234
   ```

   The picture appears in VLC within a few seconds. Stop the first command with `Ctrl-C` and VLC stops
   on its own. Without `--seconds`, `play` runs until you stop it.

4. **Watch its health while it runs.** Add `--status-interval 1` to print one line a second on stderr:

   ```bash
   python -m dib0700 play --freq 479143 --udp 127.0.0.1:1234 --status-interval 1
   ```

   The line is `ts packets N lock True strength S quality Q`. `quality` is the share of clean packets
   over the last 5 seconds, not a signal meter.

---

## 6. TV signal information

Broadcasters describe how their signal is built. The device reads that description from the air, and
`tmcc` prints it:

```bash
python -m dib0700 tmcc --freq 479143
```

You get one line, measured on a real multiplex:

```text
479143 kHz tmcc mode 8K guard 1/4 partial 1 inversion 0 sb 0 ewbs 0 L0:QPSK 1seg code 7/8 I2 L1:16QAM 12seg code 3/4 I2
```

- **mode** — how many carriers the channel uses (2K, 4K or 8K). It is a property of the broadcast, not
  something you choose.
- **guard** — the spacing between carriers, shown as a fraction such as `1/4`. Larger guards survive
  echoes better, which matters in cities and in moving vehicles.
- **partial** — `1` means the channel also carries a small low-resolution service for phones, next to
  the main program.
- **L0 / L1 / L2** — the layers of the channel. Each layer has a modulation (how densely the signal is
  packed), a number of segments (how much of the channel it occupies), a code rate (how much error
  correction it carries) and an interleave depth. In practice: the layer your player is using carries
  your channel, and a layer with more error correction is steadier but needs a stronger signal.

If it says `no usable TMCC`, the device has a signal but has not read the description yet. Tune again,
and give it a second or two.

`iip` reads the ISDB-T Information Packet, which describes the **next** frame. It needs a locked
stream and prints one line per distinct configuration:

```bash
python -m dib0700 iip --freq 479143 --seconds 10
```

---

## 7. Android

The same driver exists for Android as a small Java library, so an app can list, tune and play without
writing the USB protocol itself. `java/` holds a dependency-free core and the `UsbDeviceConnection`
adapter; the app opens the device, hands it the firmware image (section 2), tunes a frequency, and
receives the video stream as it arrives. The package implements the `com.pbcnx.tuner` contract, so an
app written against that contract can use it without code of its own:

```java
Tuner tuner = new Dib0700Provider().open(context, device, firmware, listener);
tuner.open();
tuner.tune(479143);
tuner.start();
```

Nothing in `java/` has to be built to use the command line on a computer.

---

## 8. When something goes wrong

Each of these is the exact first line the driver prints, what it means, and what to do about it.

| What you see | What it means | What to do |
|---|---|---|
| `dib0700: libusb backend unavailable: install libusb, or set DIB0700_LIBUSB to its file ...` | Your computer has no USB library the driver can use. | Windows: get `libusb-1.0.dll` from <https://libusb.info> and set `DIB0700_LIBUSB` to its path (section 3, steps 6-7). macOS: `brew install libusb`. Linux: install `libusb-1.0-0`. |
| `dib0700: no DIB0700 (10b8:1fa0) found; bind it to WinUSB/libusb first` | The device is not visible to the driver. | Plug it in. On Windows, bind it to WinUSB with Zadig first. On Linux, install the udev rule and replug. |
| `dib0700: firmware image not found: <path>` | The file you named does not exist, or the path is wrong. | Check the path. If you used `--firmware`, it belongs before the command name: `dib0700 --firmware PATH play ...`. |
| `dib0700: cold device and no firmware image found: ...` | The device is blank and the driver found no image. | Section 2: the three places it looks, in order. |
| `dib0700: bad checksum in record at N` | The file is not the image this device needs, or the copy is damaged. | Copy the file again from the vendor application for this device. |
| `dib0700: cannot claim interface 0: ...` | Another program is using the device, or your user is not allowed to. | Close other TV programs. On Linux, install the udev rule (section 3) and replug; over SSH add your user to `plugdev`. |
| `<frequency> kHz no lock` | The channel has no signal where you are. | Try another frequency, move the antenna, or run `python -m dib0700 scan` across the band. |
| `<frequency> kHz LOCK strength 12` | You have the channel, but the signal is weak, so the picture may break up. | Move or raise the antenna. A short USB extension cable helps, because the device's own size blocks reception. |

---

## 9. Getting help

Open an issue at <https://github.com/PBCNX/dib0700/issues>. Please say which command you ran, what it
printed, and whether `python -m dib0700 list` finds the device. That last answer tells us whether the
problem is the device or the computer.

---

Copyright (c) 2026 The DIB0700 Open Driver Authors.

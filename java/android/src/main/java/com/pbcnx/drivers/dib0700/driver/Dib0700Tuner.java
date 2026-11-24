/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700.driver;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.pbcnx.drivers.dib0700.Log;
import com.pbcnx.drivers.dib0700.Dib0700;
import com.pbcnx.drivers.dib0700.Sessions;
import com.pbcnx.drivers.dib0700.Status;
import com.pbcnx.drivers.dib0700.Transport;
import com.pbcnx.drivers.dib0700.TransportException;
import com.pbcnx.drivers.dib0700.android.UsbTransport;
import com.pbcnx.tuner.ScanListener;
import com.pbcnx.tuner.Tmcc;
import com.pbcnx.tuner.TsReader;
import com.pbcnx.tuner.Tuner;
import com.pbcnx.tuner.TunerException;
import com.pbcnx.tuner.TunerStatus;

/**
 * The tuner contract ({@code com.pbcnx.tuner.Tuner}, TUNER-CONTRACT 1.4) over the Dib0700 core.
 *
 * <p>Mapping only: every method is a call into {@link Dib0700} plus the two translations the door
 * requires of any package - the TMCC record's canonical shape and the error rules. It owns no
 * protocol of its own, and it declares no {@code start()}: the four construction arguments include
 * the firmware image, so {@link #open()} uploads it when the chip is cold and arms the reader, and
 * the optional {@code start()} stays the interface's no-op (contract §2 row 1b).
 *
 * <p>Threading (§3): every door method is {@code synchronized} except the {@code scan} <b>worker</b>,
 * which must stay off this monitor so {@link #close()} can cancel a sweep. The core owns the reader
 * thread and the bulk-in endpoint; the transport is never called from two threads by this class.
 *
 * <p>The desktop/test seam: constructed directly from a {@link Transport} and a {@link Sessions}
 * (the provider's context-less overload), which is how the JVM door self-test opens a fake.
 */
public final class Dib0700Tuner implements Tuner {

    private final UsbManager manager;      // Android seam (null on the transport seam)
    private final UsbDevice device;        // Android seam (null on the transport seam)
    private final Transport transport;     // transport seam (null on the Android one)
    private final Sessions sessions;
    private final byte[] firmware;
    private final TsReader.Listener listener;
    private final Log log;

    private Dib0700 core;
    private boolean opened;
    private int lastKhz;
    private volatile boolean cancelled;

    /** Android: the package's door entry point; the transport is opened by {@link #open()}. */
    Dib0700Tuner(Context context, UsbDevice device, byte[] firmware, TsReader.Listener listener) {
        this.manager = context == null
                ? null : (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.device = device;
        this.transport = null;
        this.sessions = Sessions.fromClasspath();
        this.firmware = firmware;
        this.listener = listener;
        this.log = message -> android.util.Log.i("Dib0700", message);
    }

    /** Desktop/tests: a transport the caller already opened (a JVM host's libusb, or a fake). */
    Dib0700Tuner(Transport transport, Sessions sessions, byte[] firmware, TsReader.Listener listener,
            Log log) {
        this.manager = null;
        this.device = null;
        this.transport = transport;
        this.sessions = sessions == null ? Sessions.fromClasspath() : sessions;
        this.firmware = firmware;
        this.listener = listener;
        this.log = log == null ? Log.NONE : log;
    }

    // -----------------------------------------------------------------------------------------
    // Lifecycle: open() is the only method that throws (contract §2 row 1, §6)
    // -----------------------------------------------------------------------------------------

    @Override
    public synchronized void open() throws TunerException {
        if (opened) return;
        Transport handle = transport;
        if (handle == null) {
            if (manager == null || device == null) {
                throw new TunerException("open failed: no USB device (the transport seam needs one)");
            }
            try {
                handle = UsbTransport.open(manager, device);
            } catch (TransportException error) {
                throw new TunerException("open failed: " + error.getMessage(), error);
            }
        }
        Dib0700 driver = new Dib0700(handle, sessions, log);
        if (!driver.start(firmware, bridge())) {
            String reason = driver.lastError();
            driver.close();
            throw new TunerException("open failed: "
                    + (reason == null ? "the bring-up was refused" : reason));
        }
        core = driver;
        opened = true;
        lastKhz = 0;
        cancelled = false;
        log.info("door open: " + driver.name() + ", reader armed");
    }

    /**
     * The firmware version the door's {@link #open()} brought up (the vendor image's version word),
     * or 0 when there is no session. A reporting accessor for a host that must name the version in
     * the door's evidence line (IT9175's "one line per line", 2026-11-22); it is <b>not</b> part of
     * {@code com.pbcnx.tuner.Tuner} and a host that only uses the door never calls it.
     */
    public synchronized int firmwareVersion() {
        return core == null ? 0 : core.version();
    }

    /** The host's stream sink as the core's listener. Bring-up/tune failures are in the log. */
    private com.pbcnx.drivers.dib0700.Listener bridge() {
        return new com.pbcnx.drivers.dib0700.Listener() {
            @Override
            public void onData(byte[] ts, int length) {
                TsReader.Listener target = listener;
                if (target != null) target.onData(ts, length);
            }

            @Override
            public void onError(String reason) {
                // The core logged the reason; the door has no error channel by design (contract §6).
            }

            @Override
            public void onEnd() {
                // The host learns of the end through its own detach handling (contract §7, §8).
            }
        };
    }

    // -----------------------------------------------------------------------------------------
    // Tune / zap: boolean verdicts, never an exception (contract §2 rows 2-3, §6)
    // -----------------------------------------------------------------------------------------

    @Override
    public synchronized boolean tune(int frequencyKhz) {
        if (core == null) return false;
        lastKhz = frequencyKhz;
        // tuneAny covers both cases the core knows: a captured frequency replays its own session,
        // an uncaptured one retunes from the nearest capture that locks (Dib0700.java:554-676).
        boolean locked = core.tuneAny(frequencyKhz);
        log.info("door tune: " + frequencyKhz + " kHz -> " + (locked ? "locked" : "no lock"));
        return locked;
    }

    @Override
    public synchronized boolean zap(int frequencyKhz) {
        if (core == null) return false;
        lastKhz = frequencyKhz;
        // The core's tune path already stops the reader for the transfer, re-arms the stream and
        // verifies the lock (contract §3e); a zap is that same call on a live handle.
        boolean locked = core.tuneAny(frequencyKhz);
        log.info("door zap: " + frequencyKhz + " kHz -> " + (locked ? "locked" : "no lock"));
        return locked;
    }

    // -----------------------------------------------------------------------------------------
    // Status and TMCC
    // -----------------------------------------------------------------------------------------

    @Override
    public synchronized TunerStatus status() {
        if (core == null) return new TunerStatus(false, -1, -1, -1);
        Status status = core.status();
        if (status == null) status = core.readStatus();   // the first second after open()
        // This part has no chip C/N : snrDb is NaN, which is -1 at the boundary.
        int snr = Double.isNaN(status.snrDb) ? -1 : (int) Math.round(status.snrDb);
        return new TunerStatus(status.locked, status.strengthPercent, status.qualityPercent, snr);
    }

    @Override
    public synchronized Tmcc tmcc() {
        return core == null ? null : map(core.tmcc());
    }

    @Override
    public synchronized Tmcc tmccSettled(int attempts, long delayMs) {
        if (core == null) return null;
        int tries = Math.max(1, attempts);
        int wait = (int) Math.max(0, Math.min(Integer.MAX_VALUE, delayMs));
        Dib0700.TmccSettle settled = core.tmccSettled(tries, wait);
        return settled == null ? null : map(settled.tmcc);
    }

    /**
     * The TMCC translation: canonical shape, never a guess.
     *
     * <ul>
     *   <li>{@code mode} and {@code guard} are already canonical in the core - the demod's 1-based
     *       mode (1/2/3 = 2K/4K/8K) is translated at the register boundary (Tmcc.java:186-194), so
     *       this package does not swap 1 and 2 the way the 9175 must.</li>
     *   <li>{@code ewbs} is already the three-state int from register 472 (0/1, -1 unread); it is
     *       passed through, never turned into a boolean.</li>
     *   <li>{@code partial} is the door's boolean; the core's -1 (unread) reads false and the
     *       register (505) is in every read, so a real reading is always 0/1.</li>
     *   <li>layer {@code codeRate} is the door's 0..4 index (0 = 1/2 ... 4 = 7/8), from the core's
     *       1-based label; {@code interleaving} is already an index (3 = I4), so a depth does not
     *       exist here to translate;</li>
     *   <li>an unread layer field stays -1, and an absent layer keeps 0 segments (contract §5).</li>
     * </ul>
     *
     * <p>{@code valid()} then agrees with the core's own gate by construction: mode and guard known,
     * 1..13 segments claimed by present layers (contract §5, §6 - a lock is not a data verdict).
     */
    static Tmcc map(com.pbcnx.drivers.dib0700.Tmcc reading) {
        if (reading == null) return null;
        Tmcc.Layer[] layers = new Tmcc.Layer[3];
        for (int index = 0; index < layers.length; index++) {
            com.pbcnx.drivers.dib0700.Tmcc.Layer layer =
                    reading.layers != null && index < reading.layers.length
                            ? reading.layers[index] : null;
            layers[index] = layer == null
                    ? new Tmcc.Layer(0, 0, -1, 0)                     // absent
                    : new Tmcc.Layer(layer.segments, layer.modulation,
                            layer.codeRateIndex(), layer.interleave);
        }
        return new Tmcc(reading.mode, reading.guard, reading.partial == 1, reading.ewbs, layers);
    }

    // -----------------------------------------------------------------------------------------
    // Recovery and the detach/attach pair (contract §2 rows 7-8, §8)
    // -----------------------------------------------------------------------------------------

    @Override
    public synchronized boolean recover() {
        if (core == null) return false;
        boolean healed = core.recover();
        log.info("door recover: " + (healed ? "stream back" : "not healed"));
        return healed;
    }

    @Override
    public synchronized void suspend() {
        cancelled = true;
        opened = false;
        Dib0700 driver = core;
        core = null;
        if (driver != null) {
            // State 2 begins with a state-1 rebuild on this part (the bridge has no flash), so the
            // transport is released here and resume() re-opens the new handle.
            driver.close();
            log.info("door suspend: handle released, last "
                    + (lastKhz > 0 ? lastKhz + " kHz" : "none"));
        }
    }

    @Override
    public synchronized boolean resume(UsbDevice device) {
        if (core != null) return true;
        if (transport != null) {
            // The transport seam was handed one handle; it cannot rebuild on a new one.
            log.info("door resume: the transport seam has no handle factory");
            return false;
        }
        if (manager == null || device == null) return false;
        Transport handle;
        try {
            handle = UsbTransport.open(manager, device);
        } catch (TransportException error) {
            log.info("door resume: " + error.getMessage());
            return false;
        }
        Dib0700 driver = new Dib0700(handle, sessions, log);
        if (!driver.start(firmware, bridge())) {
            log.info("door resume: the bring-up was refused (" + driver.lastError() + ")");
            driver.close();
            return false;
        }
        core = driver;
        opened = true;
        cancelled = false;
        // The state to keep is the frequency: a warm tune, and the host's next tune is free to move.
        return lastKhz <= 0 || driver.tuneAny(lastKhz);
    }

    // -----------------------------------------------------------------------------------------
    // Scan: a worker; the verdict is the stream (contract §2 row 10)
    // -----------------------------------------------------------------------------------------

    @Override
    public synchronized void scan(int startKhz, int stopKhz, int stepKhz, int seconds,
            ScanListener listener) {
        if (core == null) {
            if (listener != null) listener.onDone(0, 0);
            return;
        }
        cancelled = false;
        final int from = Math.min(startKhz, stopKhz);
        final int to = Math.max(startKhz, stopKhz);
        final int step = stepKhz > 0 ? stepKhz : 1;
        final double window = Math.max(0, seconds);
        Thread worker = new Thread(() -> runScan(from, to, step, window, listener), "Dib0700-DoorScan");
        worker.setDaemon(true);
        worker.start();
    }

    private void runScan(int from, int to, int step, double seconds, ScanListener listener) {
        Dib0700 driver = core;                       // the worker reads the field once
        if (driver == null) {
            if (listener != null) listener.onDone(0, 0);
            return;
        }
        int total = (to - from) / step + 1;
        int found;
        try {
            // The core's scan already reports the stream (tuned AND packets) per channel, which is
            // the door's verdict. This adapter only adds the door's snapshot and its two calls.
            found = driver.scan(from, to, step, seconds, (khz, locked, packets, strength) -> {
                if (cancelled) throw new ScanCancelled();
                if (listener != null) listener.onFrequency(khz, locked, status());
            });
        } catch (ScanCancelled stopByClose) {
            log.info("door scan: cancelled");
            return;
        } catch (RuntimeException hostError) {
            // A listener throw cancels the sweep (contract §2 row 10); never surface it here.
            log.info("door scan: the listener stopped the sweep (" + hostError + ")");
            return;
        }
        if (cancelled) return;
        if (listener != null) listener.onDone(found, total);
    }

    /** The sweep's own stop signal: thrown through the core's per-channel callback on close(). */
    private static final class ScanCancelled extends RuntimeException {
        ScanCancelled() {
            super("scan cancelled", null, false, false);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Close: safe twice (contract §2 row 9)
    // -----------------------------------------------------------------------------------------

    @Override
    public synchronized void close() {
        cancelled = true;
        opened = false;
        Dib0700 driver = core;
        core = null;
        if (driver != null) driver.close();
    }
}

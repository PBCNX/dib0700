/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * DiBcom STK8096GP ({@code 10b8:1fa0}, "DIB0700") user-space driver.
 *
 * <p>Cold start: the stick has no flash, so every attach it comes back to its boot ROM and needs
 * the vendor image downloaded before it answers. On a warm device (firmware already running) the
 * upload is skipped. Tuning replays one whole captured cold session for the requested frequency:
 * the dib8000 tune is a state machine driven by register reads, so a transcript of one run is the
 * portable approach, and it is frequency-specific by construction.</p>
 *
 * <p>{@code Transport} is the only host dependency: the Android adapter supplies
 * {@code UsbDeviceConnection}, a test supplies a fake. Nothing here imports an Android or host
 * class. The provenance marker is {@link #PROVENANCE} - visible attribution, never telemetry.</p>
 */
public final class Dib0700 {

    /** Visible provenance/attribution string; no network, no device identifier, no protocol bytes. */
    public static final String PROVENANCE = "dib0700-pbcnx";

    public static final int VID = 0x10b8;
    public static final int PID = 0x1fa0;

    public static final int EP_FIRMWARE_OUT = 0x01;
    public static final int EP_TS_IN = 0x82;

    private static final int VENDOR_OUT = 0x40;
    private static final int VENDOR_IN = 0xC0;
    private static final int REQUEST_I2C_READ = 0x02;
    private static final int REQUEST_JUMPRAM = 0x08;
    private static final int REQUEST_ENABLE_VIDEO = 0x0F;
    private static final int REQUEST_GET_VERSION = 0x15;
    private static final int TIMEOUT_MS = 1000;
    private static final int TS_PACKET = 188;
    private static final int SLAB = 3584;
    private static final int QUEUE_SLABS = 192;

    /**
     * The reader's slab contract (the Android host's LEAP crash, 2026-11-20): the pool holds {@link #SLAB}-byte
     * buffers only, and a pooled buffer is never used for a copy that does not fit it. A short USB
     * read must not be able to poison the pool for the next full one.
     */
    static byte[] takeCopy(byte[] buffer, int n, java.util.Deque<byte[]> pool) {
        byte[] copy;
        if (n == SLAB) {
            copy = pool.poll();
            if (copy == null || copy.length != SLAB) copy = new byte[SLAB];
        } else {
            copy = new byte[n];
        }
        if (copy.length < n) copy = new byte[n];
        System.arraycopy(buffer, 0, copy, 0, n);
        return copy;
    }

    /** Return a buffer to the pool only when it is a full slab (the other half of the contract). */
    static void returnSlab(java.util.Deque<byte[]> pool, byte[] slab) {
        if (slab.length == SLAB && pool.size() < 256) pool.add(slab);
    }

    /** {@code 0x02} I2C read, {@code wValue 0x0281}: two bytes come back big-endian. */
    private static final int I2C_READ_VALUE = 0x0281;
    private static final int LOCK_REGISTER = 568;
    private static final int STRENGTH_REGISTER = 390;
    // dib8000_read_lock's word: bits 11/10 (coff/cpil and lmod4) and bit 12 (FEC), then the MPEG
    // lock of *any* layer that carries segments - bit 7 layer A, bit 6 layer B, bit 5 layer C
    // (dib8000_get_status: FE_HAS_LOCK needs bit 12 and (lock >> 5) & 7). The old mask asked for
    // layer A's bit 7 unconditionally, so a 1-seg-A + 12-seg-B mux whose layer A does not reach
    // MPEG lock read "lock False" while its stream was clean (measured 2026-11-20).
    private static final int LOCK_BITS = 0x1C00;
    private static final int LAYER_LOCK_BITS = 0x00E0;
    // dib8000_autosearch_irq: bit 0 is "failed", bit 1 is "succeeded".
    private static final int SEARCH_IRQ_REGISTER = 0x0504;
    // P_search_param_select's low four bits select the guard intervals the search tries
    // (dib8000_autosearch_start: 0xf = "look for the 4 different guard intervals").
    private static final int SEARCH_SELECT_REGISTER = 357;

    /** What the kernel writes to close the demod output before a tune (OUTMODE_HIGH_Z). */
    private static final byte[] OUTPUT_OFF = {0x03, (byte) 0x80, 0x05, 0x06, 0x00, 0x00};
    private static final byte[] SEARCH_RESTART = {0x03, (byte) 0x80, 0x00, 0x00, (byte) 0xE0, 0x00};

    /** One candidate gets this long from the search trigger to the success bit (0.3-0.65 s captured). */
    private static final long ACQUISITION_TIMEOUT_MS = 1000;
    private static final long ACQUISITION_POLL_MS = 25;
    /** How long a tune waits for the lock word or a clean stream run. */
    private static final long LOCK_TIMEOUT_MS = 2000;
    /** A run of this many whole packets with no resync is the stream's own lock evidence. */
    private static final int STREAM_LOCK_PACKETS = 64;

    /** The demod's TMCC registers, in the Python driver's read order; 472 is the EWBS flag. */
    private static final int[] TMCC_REGISTERS = {
            508, 570, 572, 505, 472,
            481, 482, 483, 487, 488, 489, 493, 494, 495, 499, 500, 501};

    private final Transport transport;
    private final Sessions sessions;
    private final Log log;
    private final TsFramer framer = new TsFramer();
    private final QualityMeter quality = new QualityMeter(5.0);
    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(QUEUE_SLABS);
    // Reader and feeder both touch the pool; the ArrayDeque it used to be was not thread-safe and
    // threw IndexOutOfBounds once the self-test's timing shifted (found with the zap cache).
    private final java.util.concurrent.ConcurrentLinkedDeque<byte[]> pool =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final Status live = new Status();
    private volatile Listener listener;
    private final TsFramer.Sink sink = (data, length) -> {
        quality.feed(data, length, nowSeconds());
        Listener target = listener;
        if (target != null) target.onData(data, length);
    };

    private volatile Status snapshot;

    private volatile boolean running;
    private volatile boolean replaying;
    private volatile boolean ready;
    private volatile boolean stoppedByCaller;
    private volatile boolean haveStatus;
    private volatile int currentKhz;
    private volatile String lastError;
    private volatile long packets;
    private volatile long dropped;
    private volatile long callbacks;

    private Thread reader;
    private Thread feeder;
    private Thread poller;
    private long lastLockLog;
    private long lastStatsLog;

    /**
     * A frequency's cached zap: the tuner PLL words, the demod's final channel block and the TMCC
     * it was built from. The cache is per device object, in memory, and a burst is only answered
     * while it locks and the demod still reports the same mux.
     */
    static final class ZapEntry {

        final Map<Integer, Integer> pll;
        final Record[] block;
        final Tmcc decoded;

        ZapEntry(Map<Integer, Integer> pll, Record[] block, Tmcc decoded) {
            this.pll = pll;
            this.block = block;
            this.decoded = decoded;
        }
    }

    /** A cached burst has no calibration or search to wait for: its proof is a short window.
     *  Measured 2026-11-24: the QPSK mux (485) needs ~1 s after the final block before the lock
     *  reads; at 500 ms the burst looked failed on every 479 -> 485 attempt while the full tune
     *  locked it every time.  1,500 ms is the same order as the search wait beside it. */
    private static final long CACHED_LOCK_TIMEOUT_MS = 1500;

    /** The TMCC registers update once per frame; a mismatch is re-read for this long. */
    private static final long CACHED_TMCC_TIMEOUT_MS = 400;
    private static final long CACHED_POLL_MS = 100;

    /**
     * The captured autosearch takes 0.31-0.65 s; the cached burst waits for its success bit.
     * Measured 2026-11-20: the final block alone does not acquire a channel, so the search is part
     * of the burst.
     */
    private static final long CACHED_SEARCH_TIMEOUT_MS = 1500;

    private final Map<Integer, ZapEntry> zaps = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean cacheEnabled = true;

    public Dib0700(Transport transport, Sessions sessions, Log log) {
        this.transport = transport;
        this.sessions = sessions;
        this.log = log == null ? Log.NONE : log;
    }

    public String name() {
        return "DiBcom STK8096GP";
    }

    // -----------------------------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------------------------

    /**
     * Brings the stick up (uploads {@code firmware} when it is cold) and starts the three worker
     * threads. {@code start()} never tunes: the host's tune path supplies the frequency.
     */
    public synchronized boolean start(byte[] firmware, Listener target) {
        if (running) return true;
        // A bring-up is a fresh demod state: no cached channel block describes it.
        zaps.clear();
        listener = target;
        lastError = null;
        int version = version();
        log.info("open ok, version=0x" + Integer.toHexString(version));
        if (version == 0 && !uploadFirmware(firmware)) {
            if (target != null) target.onError(lastError);
            return false;
        }
        startWorkers();
        return true;
    }

    /**
     * Creates and starts the three worker threads. {@code start()} arms a fresh session; a
     * recovery re-arms the one it stopped, so it must not re-check the version or upload anything.
     */
    private synchronized void startWorkers() {
        ready = true;
        running = true;
        stoppedByCaller = false;
        reader = new Thread(this::usbReaderLoop, "Dib0700-USB");
        feeder = new Thread(this::feedLoop, "Dib0700-Feed");
        poller = new Thread(this::statusLoop, "Dib0700-Status");
        reader.start();
        feeder.start();
        poller.start();
    }

    public synchronized void stop() {
        if (!running && reader == null && feeder == null && poller == null) {
            stoppedByCaller = true;
            return;
        }
        stoppedByCaller = true;
        running = false;
        enableVideo(false);
        Thread[] threads = {reader, feeder, poller};
        reader = null;
        feeder = null;
        poller = null;
        for (Thread thread : threads) {
            if (thread == null) continue;
            try {
                thread.join(1500);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        queue.clear();
        haveStatus = false;
        snapshot = null;
        ready = false;
    }

    /** Stop and release the USB handle. Safe to call twice. */
    public void close() {
        stop();
        transport.close();
    }

    public boolean isReady() {
        return ready;
    }

    public int currentKhz() {
        return currentKhz;
    }

    public String lastError() {
        return lastError;
    }

    /**
     * Enables or disables the per-frequency zap cache (the host's {@code --no-cache}).
     *
     * <p>With it on, a tune back to a frequency this process already tuned is a short burst: the
     * cached tuner PLL block and final channel block, then a lock and TMCC check - not the live
     * calibration, autosearch and TMCC patch. Disabling it drops what is cached and makes every
     * tune the full procedure.</p>
     */
    public void setCacheEnabled(boolean enabled) {
        cacheEnabled = enabled;
        if (!enabled) zaps.clear();
    }

    /** True when the zap cache is on (the default). */
    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    /** Drops every cached channel block: a new bring-up or a recovery must not trust them. */
    public void clearCache() {
        zaps.clear();
    }

    /** True when {@code khz} has a cached block (the self-test's window into the cache). */
    boolean isCached(int khz) {
        return zaps.containsKey(khz);
    }

    ZapEntry cached(int khz) {
        return zaps.get(khz);
    }

    /** The last polled meter snapshot, or null before the first poll. */
    public Status status() {
        return snapshot;
    }

    public long packets() {
        return packets;
    }

    public long dropped() {
        return dropped;
    }

    public long syncErrors() {
        return framer.syncErrors;
    }

    public String counters() {
        return "packets " + packets + ", dropped " + dropped;
    }

    public int version() {
        try {
            return versionOrThrow();
        } catch (TransportException error) {
            return 0;
        }
    }

    /**
     * The firmware version, with a transport failure raised rather than read as "cold". {@link
     * #version()} keeps the boot-ROM rule (0 = the image is not running); recovery uses this one to
     * tell a wedged chip that answers from a handle that is gone.
     */
    private int versionOrThrow() throws TransportException {
        byte[] reply = transport.controlIn(REQUEST_GET_VERSION, 0, 0, 16);
        if (reply == null || reply.length < 12) return 0;
        return ((reply[8] & 0xFF) << 24) | ((reply[9] & 0xFF) << 16)
                | ((reply[10] & 0xFF) << 8) | (reply[11] & 0xFF);
    }

    // -----------------------------------------------------------------------------------------
    // Firmware
    // -----------------------------------------------------------------------------------------

    private boolean uploadFirmware(byte[] image) {
        if (image == null || image.length < 16) {
            lastError = "no firmware image: a cold stick needs the vendor dvb-usb-dib0700 image";
            return false;
        }
        int records;
        try {
            List<byte[]> parts = Firmware.records(image);
            records = parts.size();
            for (byte[] record : parts) transport.bulkOut(Firmware.wire(record));
            transport.bulkOut(Firmware.jump());
        } catch (TransportException | IllegalArgumentException error) {
            lastError = "firmware upload failed: " + error.getMessage();
            return false;
        }
        sleep(500);
        int version = version();
        log.info(String.format(Locale.US, "firmware %d records, version 0x%05x", records, version));
        if (version == 0) {
            lastError = "the boot ROM accepted the image but never started it (GET_VERSION still stalls)";
            return false;
        }
        return true;
    }

    // -----------------------------------------------------------------------------------------
    // Recovery
    // -----------------------------------------------------------------------------------------

    /** How long recover waits for the stream to be back: a short clean run, not a tune's window. */
    private static final long RECOVER_STREAM_TIMEOUT_MS = 2000;

    /**
     * State-3 recovery (warm wedge), in place, one call: stop the reader, warm re-init the last
     * frequency's captured session through the existing replay path ({@code cold=false} - no
     * firmware upload), re-tune the last frequency when it has no capture of its own, and verify
     * that the stream is back with a short clean run.
     *
     * <p>The door's row 7 is a verdict, never a promise: {@code true} only when the stream
     * returned, {@code false} with the outcome in the log. An upload belongs to a cold state-1/2
     * rebuild (the host's {@code start()} / {@code resume()}), never to a wedge: a wedged chip
     * answers and its transport runs, so overwriting its RAM is the one thing this call refuses.</p>
     *
     * <p>The lock word alone is not the answer: "locked but no TS" is exactly the wedge being
     * healed, so the verdict is the stream, not the register. A transfer that throws is a handle
     * gone - reported as such, never retried; the host owns the re-open (state 2).</p>
     */
    public synchronized boolean recover() {
        return recover(RECOVER_STREAM_TIMEOUT_MS);
    }

    /** {@link #recover()} with the stream window chosen by the caller (a test). */
    synchronized boolean recover(long streamTimeoutMs) {
        log.info("recover: state 3 (warm wedge) - stop the reader, warm re-init, re-tune; "
                + "never an upload");
        // The recovery rebuilds the demod from the capture: every cached block described the state
        // that wedged, so none of them may be trusted afterwards (the rule).
        clearCache();
        int target = currentKhz;
        boolean wasRunning = running || reader != null;
        if (wasRunning) stop();
        boolean handleGone = false;
        try {
            // The pair before any risky step, the Python shape (host-log rule 5): the host log
            // must be able to say whether the bus/address moved, not reconstruct it afterwards.
            int version;
            try {
                version = versionOrThrow();
            } catch (TransportException error) {
                log.info("recover: pair before - firmware ?, " + transport.deviceText());
                throw error;
            }
            log.info("recover: pair before - firmware " + firmwareText(version) + ", "
                    + transport.deviceText());
            if (target <= 0) {
                lastError = "recover: no tuned frequency to re-tune";
                log.info(lastError);
                return false;
            }
            int base = sessions.has(target) ? target : Tune.nearest(target);
            Record[] records;
            try {
                records = Sessions.trimAtOutputEnable(sessions.load(base));
            } catch (IOException error) {
                lastError = "recover: no captured session to replay: " + error.getMessage();
                log.info(lastError);
                log.info("recover: replay only");
                return false;
            }
            replaying = true;
            try {
                log.info("recover: warm re-init - replaying the captured " + base
                        + " kHz session (" + records.length + " records, cold=false, no upload)");
                replay(records);
                startStream();
                currentKhz = base;
                if (base != target) {
                    log.info("recover: re-tuning " + target + " kHz from the " + base + " kHz base");
                    if (!retune(target, base, true)) {
                        // A failed retune can be a lost lock or a dead handle; only the handle
                        // answers (or does not) at the version register, and a dead one must be
                        // reported as gone, not as "the replay ran".
                        versionOrThrow();
                        lastError = "recover: the re-tune to " + target + " kHz did not lock";
                        log.info("recover: replay only");
                        return false;
                    }
                }
                if (!streamRun(streamTimeoutMs)) {
                    // Same distinction after the wait: a handle that went away during it is state
                    // 2 for the host, not a wedge that failed to clear.
                    versionOrThrow();
                    lastError = "recover: the replay ran, the stream did not come back on "
                            + target + " kHz";
                    log.info("recover: " + target + " kHz: no clean stream in "
                            + streamTimeoutMs + " ms");
                    log.info("recover: replay only");
                    return false;
                }
            } finally {
                replaying = false;
            }
            log.info("recover: pair after - firmware " + firmwareText() + ", "
                    + transport.deviceText());
            lastError = null;
            log.info("recover: replay + lock");
            return true;
        } catch (TransportException error) {
            handleGone = true;
            lastError = "recover: handle gone (" + error.getMessage() + ")";
            log.info("recover: failed (handle gone)");
            log.info("recover: the transfer threw - " + error.getMessage());
            return false;
        } finally {
            // A failed recovery on a live handle leaves a usable object; a gone handle is the
            // host's to re-open (states 2/4), and re-arming it would only spin on a dead pipe.
            if (wasRunning && !handleGone) startWorkers();
        }
    }

    /** The recovery pair's firmware half; a handle that answers nothing is "?", never 0x00000. */
    private String firmwareText() {
        try {
            return firmwareText(versionOrThrow());
        } catch (TransportException error) {
            return "?";
        }
    }

    private static String firmwareText(int version) {
        return String.format(Locale.US, "0x%05x", version);
    }

    /**
     * Waits for the recovery's verdict: the stream, not the lock word.
     *
     * <p>The reader is stopped during a recovery, so this reads the endpoint itself. A run of
     * {@link #STREAM_LOCK_PACKETS} whole packets with at most the one resync the first
     * mid-packet read needs means "the stream is back" (alignment is not promised, contract 7); a
     * transfer that throws is a dead handle and is raised, not read as a timeout.</p>
     */
    private boolean streamRun(long timeoutMs) throws TransportException {
        TsFramer probe = new TsFramer();
        byte[] buffer = new byte[SLAB];
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int n = transport.bulkIn(buffer, 200);
            if (n <= 0) continue;
            probe.feed(buffer, n, (data, length) -> {
            });
            if (probe.syncErrors > 1) {
                log.info("recover: the stream is back but corrupt (" + probe.syncErrors
                        + " resyncs)");
                return false;
            }
            if (probe.packets >= STREAM_LOCK_PACKETS) {
                log.info("recover: stream back, " + probe.packets + " packets, "
                        + probe.syncErrors + " resync(s)");
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------------------------
    // Tuning
    // -----------------------------------------------------------------------------------------

    public boolean hasSession(int khz) {
        return sessions.has(khz);
    }

    /**
     * Tunes a captured frequency: replays its session, and with {@code sweep} acquires mode/guard
     * when the capture's own mux does not lock.
     */
    public boolean tune(int khz) {
        return tune(khz, true);
    }

    /**
     * Tunes a captured frequency; on a miss the twelve mode/guard candidates are tried
     * ({@link #acquire}) unless {@code sweep} is false.
     *
     * <p>A capture is mux-specific: its final channel block fixes mode, guard and partial
     * reception to the channel it was recorded on, so a broadcaster using other parameters cannot
     * lock from the replay alone. A frequency without a capture is refused here; the host's
     * "tune any frequency" path is {@link #tuneAny}.</p>
     */
    public boolean tune(int khz, boolean sweep) {
        if (!sessions.has(khz)) {
            lastError = "no captured session for " + khz + " kHz";
            log.info("tune " + khz + " kHz refused: no captured session");
            return false;
        }
        // A frequency this process already tuned: the cached burst replaces the replay.
        if (tryCachedZap(khz)) return true;
        Record[] records;
        try {
            records = Sessions.trimAtOutputEnable(sessions.load(khz));
        } catch (IOException error) {
            lastError = "session load failed: " + error.getMessage();
            return false;
        }
        log.info("tune " + khz + " kHz: replaying " + records.length
                + " captured records (reader paused)");
        replaying = true;
        try {
            replay(records);
            // The replay leaves the bridge's TS engine unarmed even when the demod locks.
            startStream();
        } catch (TransportException error) {
            lastError = "session replay failed: " + error.getMessage();
            return false;
        } finally {
            replaying = false;
        }
        currentKhz = khz;
        if (awaitLock(LOCK_TIMEOUT_MS)) {
            lastError = null;
            cacheCaptured(khz, records);
            return true;
        }
        Tune.Split split = Tune.splitTune(records);
        if (!split.found()) {
            lastError = "no lock on " + khz + " kHz (the capture has no channel block)";
            log.info("tune " + khz + " kHz: no lock");
            return false;
        }
        if (!sweep) {
            lastError = "no lock on " + khz + " kHz";
            return false;
        }
        log.info("tune " + khz + " kHz: the captured channel did not lock; acquiring mode/guard");
        if (acquire(khz, Arrays.copyOfRange(records, split.finalIndex, records.length))) {
            lastError = null;
            return true;
        }
        lastError = "no lock on " + khz + " kHz";
        log.info("tune " + khz + " kHz: no lock");
        return false;
    }

    /**
     * Tunes any frequency in the tuner band, captured or not.
     *
     * <p>Without a capture a captured frequency is brought up first (its session is a known-good
     * demod state), then {@link #retune} runs the tune procedure for {@code khz}. The nearest
     * capture is tried first, but it is not always usable as a base: 599.143 is the cable-test
     * session and never locks off air, so above ~550 MHz the nearest capture is dead and the tune
     * falls back to the next capture until one locks. Retuning from a base the caller already
     * knows is locked skips the replay. The base replay does not itself sweep: the target is
     * another frequency, so the acquisition belongs to the target, in {@link #retune}.</p>
     */
    public boolean tuneAny(int khz) {
        return tuneAny(khz, true);
    }

    /** {@link #tuneAny(int)} with the acquisition sweep switch. */
    public boolean tuneAny(int khz, boolean sweep) {
        if (sessions.has(khz)) return tune(khz, sweep);
        try {
            Tune.tunerPll(khz);
        } catch (IllegalArgumentException error) {
            lastError = "tune " + khz + " kHz: " + error.getMessage();
            log.info(lastError);
            return false;
        }
        // A frequency this process already tuned (captured or not): the cached burst replaces the
        // base replay and the whole retune procedure.
        if (tryCachedZap(khz)) return true;
        // Fast zap: if the current channel is itself a captured lock, retune straight from it — no
        // base replay, whatever the nearest capture is (the scan chain's anchor rule for one zap).
        if (currentKhz != 0 && sessions.has(currentKhz) && locked()) {
            log.info("tune " + khz + " kHz: retuning from the locked " + currentKhz + " kHz channel");
            if (retune(khz, currentKhz, sweep)) return true;
            log.info("tune " + khz + " kHz: the locked channel did not retune; using a capture");
        }
        int[] candidates = Tune.baseCandidates(khz);
        for (int index = 0; index < candidates.length; index++) {
            int base = candidates[index];
            if (currentKhz == base && locked()) {
                log.info("tune " + khz + " kHz: retuning from the locked " + base + " kHz base");
                return retune(khz, base, sweep);
            }
            log.info("tune " + khz + " kHz: using the " + base + " kHz capture as the base state");
            if (tune(base, false)) return retune(khz, base, sweep);
            log.info("tune " + khz + " kHz: the " + base + " kHz base did not lock"
                    + (index + 1 < candidates.length ? ", trying the next capture" : ""));
        }
        lastError = "no captured base state locks on " + khz + " kHz";
        log.info("tune " + khz + " kHz: no captured base state locks");
        return false;
    }

    /** Re-runs the tune procedure for {@code khz} from the current (or nearest) base state. */
    public boolean retune(int khz) {
        int base = currentKhz > 0 ? currentKhz : Tune.nearest(khz);
        return retune(khz, base, true);
    }

    /** {@link #retune(int, int, boolean)} with the acquisition sweep on. */
    public boolean retune(int khz, int base) {
        return retune(khz, base, true);
    }

    /**
     * Re-runs the tune procedure for {@code khz} from a locked {@code base} state.
     *
     * <p>This is the ported state machine: the base capture's tune preamble is replayed, the
     * dib0090 calibration runs live (it branches on the ADC, so it cannot be replayed), the
     * demod's autosearch is allowed to run, its TMCC answer is read back, and the base capture's
     * final channel block is patched with what the demod reported - the layer words, mode/guard/
     * partial reception, the channel estimate and the stream configuration. The demod finds the
     * channel; we do not guess it.</p>
     *
     * <p>When the search reports no usable channel - a broadcaster whose mode the captured search
     * phase never tried - {@code sweep} re-runs the search over the twelve mode/guard
     * combinations ({@link #acquire}) and finishes from the first one that locks.</p>
     */
    public boolean retune(int khz, int base, boolean sweep) {
        if (base <= 0) base = currentKhz > 0 ? currentKhz : Tune.nearest(khz);
        // A retune to another frequency leaves the base frequency's demod/tuner state: its cached
        // block described a state the chip no longer holds, so it is dropped rather than trusted
        // on a later visit.  The target is re-derived by finishAndLock on a success and stays gone
        // on a failure.
        if (base != khz) zaps.remove(base);
        zaps.remove(khz);
        Tune.Plan plan;
        try {
            plan = Tune.retunePlan(Sessions.trimAtOutputEnable(sessions.load(base)), khz);
        } catch (IOException error) {
            lastError = "no captured base " + base + " kHz: " + error.getMessage();
            log.info("tune " + khz + " kHz: no captured base " + base + " kHz");
            return false;
        } catch (IllegalArgumentException error) {
            lastError = "tune " + khz + " kHz: " + error.getMessage();
            log.info(lastError);
            return false;
        }
        log.info(String.format(Locale.US,
                "tune %d kHz: retuning from the %d kHz base (FBDiv 0x%04x, %d + %d + %d records)",
                khz, base, plan.pll.get(0x15), plan.head.length, plan.body.length,
                plan.finalBlock.length));
        replaying = true;
        try {
            // The kernel closes the demod output before set_frontend, and the captured tune phase
            // expects that. Close the gate and stop the TS engine first, or the tune runs on a
            // streaming demod and the tuner reads/writes collide with the bulk-in pipe.
            enableVideo(false);
            controlOut(OUTPUT_OFF);
            replay(plan.head);
            Tuner tuner = new Tuner(transport, log);
            // A re-tune keeps the DC offset trims the base tune left behind and calibrates WBD and
            // temperature only, exactly like the kernel's calibration mask after the first tune.
            tuner.calibrate(khz, plan.pll, false);
            log.info("tune " + khz + " kHz: " + tuner.writes() + " tuner writes, "
                    + tuner.reads() + " tuner reads");
            replay(plan.body);

            Tmcc decoded = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                if (attempt > 1) {
                    log.info("tune " + khz + " kHz: re-asserting the search");
                    controlOut(SEARCH_RESTART);
                }
                boolean found = waitSearch(2000);
                decoded = tmcc();
                if (found && Tune.plausible(decoded)) break;
                log.info("tune " + khz + " kHz: autosearch "
                        + (found ? "reported success" : "timed out") + ", TMCC "
                        + (Tune.plausible(decoded) ? "plausible" : "not plausible"));
            }
            if (decoded != null && Tune.plausible(decoded)) {
                if (finishAndLock(khz, plan.finalBlock, decoded)) {
                    log.info("tune " + khz + " kHz: lock from the stream");
                    lastError = null;
                    return true;
                }
                log.info("tune " + khz + " kHz: the decoded channel did not lock");
            } else {
                log.info("tune " + khz + " kHz: the demod did not report a usable channel");
            }
        } catch (TransportException error) {
            lastError = "retune failed: " + error.getMessage();
            log.info(lastError);
            return false;
        } finally {
            replaying = false;
        }
        if (!sweep) {
            lastError = "no lock on " + khz + " kHz";
            return false;
        }
        if (acquire(khz, plan.finalBlock)) {
            lastError = null;
            return true;
        }
        lastError = "no lock on " + khz + " kHz";
        log.info("tune " + khz + " kHz: no lock");
        return false;
    }

    /**
     * Locks a sweep over the PH raster: captured frequencies replay (or retune), the rest use the
     * tune procedure.
     *
     * <p>With {@code chain} the scan anchors on the last <b>captured</b> channel that locked and
     * {@link #retune}s channel to channel, with no base replay per channel. {@code retune} needs
     * the capture's tune records, which an uncaptured channel cannot supply, so a chain can only
     * start from a captured channel; before the first anchor - and with {@code chain = false} (the
     * thorough path) - every channel brings up a base and sweeps on a miss.</p>
     *
     * <p>The driver must already be started ({@link #start}): the reader thread counts the
     * per-channel packets, and a channel that streams no packet is reported as "no lock" - the
     * Python CLI's discovery rule, not a rescue.</p>
     *
     * @return the number of channels that locked and produced packets
     */
    public int scan(int startKhz, int stopKhz, int stepKhz, double seconds,
            ScanListener listener) {
        return scan(startKhz, stopKhz, stepKhz, seconds, true, true, listener);
    }

    /** {@link #scan(int, int, int, double, ScanListener)} with the chain and sweep switches. */
    public int scan(int startKhz, int stopKhz, int stepKhz, double seconds, boolean chain,
            boolean sweep, ScanListener listener) {
        if (stepKhz <= 0) throw new IllegalArgumentException("scan step must be positive");
        int anchor = 0;
        int lockedCount = 0;
        for (long khz = startKhz; khz <= stopKhz; khz += stepKhz) {
            int frequency = (int) khz;
            boolean tuned;
            if (chain && anchor != 0) {
                // A scan is discovery, not rescue: a missed chained retune is "no lock" here.
                tuned = retune(frequency, anchor, sweep);
            } else if (sessions.has(frequency)) {
                tuned = tune(frequency, sweep);
            } else {
                tuned = tuneAny(frequency, sweep);
            }
            long before = packets;
            long deadline = System.currentTimeMillis() + Math.round(seconds * 1000.0);
            while (System.currentTimeMillis() < deadline) sleep(50);
            long received = packets - before;
            int strength = strengthPercent();
            boolean locked = tuned && received > 0;
            log.info(String.format(Locale.US, "scan %d kHz: %s, %d packets, strength %s",
                    frequency, locked ? "LOCK" : "no lock", received,
                    strength < 0 ? "n/a" : Integer.toString(strength)));
            if (listener != null) listener.onChannel(frequency, locked, received, strength);
            if (locked) {
                lockedCount++;
                if (sessions.has(frequency)) {
                    anchor = frequency;
                } else if (anchor == 0) {
                    anchor = Tune.nearest(frequency);
                }
            }
        }
        return lockedCount;
    }

    /**
     * The mode/guard acquisition sweep: find the channel on a tuned, searching demod.
     *
     * <p>{@code block} is a channel block (a capture's final block). The current search state is
     * tested first - after a captured replay the demod may already have found the channel, only
     * the replay's final block was for the capture's own mux. Then each of the twelve
     * {@link Tune#acquisitionCombos()} patches the block's mode/guard/partial-reception words
     * ({@link Tune#channelConfigRecords}), narrows the search to that guard interval
     * ({@code P_search_param_select}), re-triggers the search and polls the autosearch IRQ
     * (bit 1 = found, bit 0 = failed) and the lock word. The first candidate that reports a channel
     * gets its TMCC read and the whole block finished from it, which is the packet-level lock
     * test.</p>
     */
    public boolean acquire(int khz, Record[] block) {
        return acquire(khz, block, Tune.acquisitionCombos(), ACQUISITION_TIMEOUT_MS);
    }

    /** {@link #acquire(int, Record[])} with explicit candidates and a per-candidate timeout. */
    public boolean acquire(int khz, Record[] block, List<Tune.Combo> combos, long timeoutMs) {
        if (block == null || block.length == 0) {
            lastError = "no channel block to acquire from";
            log.info("tune " + khz + " kHz: no channel block to acquire from");
            return false;
        }
        List<Tune.Combo> candidates =
                combos == null || combos.isEmpty() ? Tune.acquisitionCombos() : combos;
        log.info("tune " + khz + " kHz: acquiring mode/guard (" + candidates.size()
                + " candidates)");
        boolean previous = replaying;
        replaying = true;
        try {
            if (tryCurrent(khz, block, "current search")) {
                lastError = null;
                return true;
            }
            for (int index = 0; index < candidates.size(); index++) {
                Tune.Combo combo = candidates.get(index);
                String label = combo.text() + " (" + (index + 1) + "/" + candidates.size() + ")";
                enableVideo(false);
                controlOut(OUTPUT_OFF);
                Record[] config = Tune.channelConfigRecords(block, combo);
                if (config.length == 0) {
                    lastError = "the channel block has no mode/guard words";
                    log.info("tune " + khz + " kHz: the channel block has no mode/guard words");
                    return false;
                }
                replay(config);
                // Try only this candidate's guard interval: left at the capture's 0xf, every
                // candidate would search all four and the first mode would lock any mux.
                writeWord(SEARCH_SELECT_REGISTER, 1 << (combo.guard & 0x03));
                restartSearch();
                if (!waitFound(timeoutMs)) {
                    log.info("tune " + khz + " kHz: " + label + " no lock");
                    continue;
                }
                Tmcc decoded = tmcc();
                if (!Tune.plausible(decoded)) {
                    log.info("tune " + khz + " kHz: " + label + " the search found no usable TMCC");
                    continue;
                }
                log.info("tune " + khz + " kHz: " + label + " found "
                        + Tmcc.modeLabel(decoded.mode) + " " + Tmcc.guardLabel(decoded.guard));
                if (finishAndLock(khz, block, decoded)) {
                    log.info("tune " + khz + " kHz: lock from the acquisition sweep at " + label);
                    lastError = null;
                    return true;
                }
                log.info("tune " + khz + " kHz: " + label + " did not stream");
            }
            lastError = "no combination locked on " + khz + " kHz";
            log.info("tune " + khz + " kHz: no combination locked (" + candidates.size()
                    + " tried)");
            return false;
        } catch (TransportException error) {
            lastError = "acquisition failed: " + error.getMessage();
            log.info(lastError);
            return false;
        } finally {
            replaying = previous;
        }
    }

    /** Finish from the search the demod has already run, when its TMCC is usable. */
    private boolean tryCurrent(int khz, Record[] block, String label) throws TransportException {
        Tmcc decoded = tmcc();
        if (!Tune.plausible(decoded)) return false;
        log.info("tune " + khz + " kHz: " + label + " reported " + Tmcc.modeLabel(decoded.mode)
                + " " + Tmcc.guardLabel(decoded.guard));
        if (finishAndLock(khz, block, decoded)) {
            log.info("tune " + khz + " kHz: lock from the " + label);
            return true;
        }
        log.info("tune " + khz + " kHz: the " + label + " did not lock");
        return false;
    }

    /**
     * Patches a channel block from the decoded TMCC, replays it, starts the stream and takes the
     * lock.
     *
     * <p>{@link Tune#patchLayerWords} and {@link Tune#patchChannelConfig} write what the demod
     * reported: the three layer words, the mode, the guard interval and partial reception. The
     * channel-estimate words follow the highest constellation, and the stream-configuration words
     * (the differential map, segment masks, estimate window and TMCC threshold) follow the layer
     * table. Success is the stream (a run of whole packets) as well as the lock word: the DTA's
     * layer-B mux streams with layer A's MPEG lock bit clear.</p>
     */
    private boolean finishAndLock(int khz, Record[] block, Tmcc decoded)
            throws TransportException {
        Record[] patched = patchChannelBlock(khz, block, decoded);
        boolean previous = replaying;
        replaying = true;
        try {
            replay(patched);
            startStream();
            currentKhz = khz;
        } finally {
            replaying = false;
        }
        boolean locked = awaitLock(LOCK_TIMEOUT_MS);
        replaying = previous;
        if (locked) {
            // Keep exactly what locked, and the TMCC it was built from, so a later visit to this
            // frequency can replay this block as a cached burst and verify it against the mux.
            cachePut(khz, patched, decoded);
        }
        return locked;
    }

    private Record[] patchChannelBlock(int khz, Record[] block, Tmcc decoded) {
        int[] values = Tune.layerWords(decoded);
        log.info(String.format(Locale.US,
                "tune %d kHz: TMCC mode %s guard %s partial %d layers %s -> 0x%04x 0x%04x 0x%04x",
                khz, Tmcc.modeLabel(decoded.mode), Tmcc.guardLabel(decoded.guard),
                decoded.partial, Arrays.toString(segmentCounts(decoded)),
                values[0], values[1], values[2]));
        Record[] patched = Tune.patchLayerWords(block, values);
        patched = Tune.patchChannelConfig(patched, decoded);
        Map<Integer, Integer> estimate = Tune.channelEstimateWords(decoded);
        patched = Tune.patchChannelEstimate(patched, estimate);
        Map<Integer, Integer> stream = Tune.streamConfigWords(decoded);
        if (!stream.isEmpty()) {
            patched = Tune.patchWords(patched, stream);
            log.info(String.format(Locale.US,
                    "tune %d kHz: stream config seg_diff 0x%04x cfr 0x%04x/0x%04x tmcc_pow 0x%04x",
                    khz, stream.get(0) & 0x1FFF, stream.get(222), stream.get(223),
                    stream.get(290)));
        }
        return patched;
    }

    private static int[] segmentCounts(Tmcc decoded) {
        int[] counts = new int[decoded.layers.length];
        for (int index = 0; index < counts.length; index++) {
            counts[index] = decoded.layers[index].segments;
        }
        return counts;
    }

    // -----------------------------------------------------------------------------------------
    // The zap cache
    // -----------------------------------------------------------------------------------------

    /**
     * The captured tune's body: the demod AGC/search configuration through the search trigger.
     *
     * <p>Measured on hardware (2026-11-20): the final channel block alone does not acquire a
     * channel after a tuner move - 485 -> 479 left the lock word at 0x4000 with no packets, and a
     * live tuner calibration did not change that. The captured tune's body ends in the
     * {@code 0x0000 = 0x6000} search trigger, which starts the acquisition; the cached burst
     * replays it and waits for the search success bit. The body is demod-generic (no tuner
     * state), so a captured frequency uses its own capture and an uncaptured one the nearest -
     * the same base {@link #retune} would use.</p>
     */
    private Record[] cachedBody(int khz) {
        int base = sessions.has(khz) ? khz : Tune.nearest(khz);
        try {
            return Tune.retunePlan(Sessions.trimAtOutputEnable(sessions.load(base)), khz).body;
        } catch (IOException | IllegalArgumentException error) {
            log.info("zap " + khz + ": no tune body (" + error.getMessage() + ")");
            return null;
        }
    }

    /**
     * Replays a cached channel block as a short burst; false means "run the full procedure".
     *
     * <p>The tuner is still on the previous channel, so the cached PLL block is written first (it
     * moves the LO), then the captured tune's body (the demod AGC/search configuration ending in
     * the search trigger - the final block alone does not acquire a channel, measured
     * 2026-11-20), then the cached final channel block and the output. Success needs a lock
     * (register or a short clean stream run) <b>and</b> the same mux from the TMCC. An entry that
     * fails is dropped, and the caller runs the full procedure, which refreshes the cache: a
     * stale entry is never answered from.</p>
     */
    private boolean tryCachedZap(int khz) {
        if (!cacheEnabled) return false;
        ZapEntry entry = zaps.get(khz);
        if (entry == null) return false;
        Map<Integer, Integer> pll;
        try {
            pll = Tune.tunerPll(khz);
        } catch (IllegalArgumentException error) {
            zaps.remove(khz);
            return false;
        }
        // The stored PLL words are the frequency check: an entry cached for another frequency is
        // not replayed.
        if (!pll.equals(entry.pll) || entry.decoded == null) {
            zaps.remove(khz);
            return false;
        }
        Record[] body = cachedBody(khz);
        if (body == null || body.length == 0) {
            zaps.remove(khz);
            log.info("zap " + khz + ": no tune body to acquire from; running the full tune");
            return false;
        }
        log.info("zap " + khz + ": cached burst (" + pll.size() + " tuner words, "
                + body.length + " body records, " + entry.block.length + " final records)");
        boolean previous = replaying;
        replaying = true;
        try {
            closeOutput();
            new Tuner(transport, log).writePll(pll);
            // The body and the final block are fixed register scripts: replay them flat out. The
            // capture's gaps are its own lock/status polls; waitSearch/verifyCached wait instead.
            replay(body, false);
            boolean found = waitSearch(CACHED_SEARCH_TIMEOUT_MS);
            log.info("zap " + khz + ": cached search " + (found ? "found" : "timed out"));
            if (!found) {
                zaps.remove(khz);
                closeOutput();
                return false;
            }
            replay(entry.block, false);
            startStream();
        } catch (TransportException error) {
            lastError = "cached zap failed: " + error.getMessage();
            zaps.remove(khz);
            log.info("zap " + khz + ": cached burst failed (" + error.getMessage() + ")");
            closeOutput();
            return false;
        } finally {
            replaying = previous;
        }
        if (verifyCached(khz, entry)) {
            currentKhz = khz;
            lastError = null;
            log.info("zap " + khz + ": cached burst");
            return true;
        }
        zaps.remove(khz);
        log.info("zap " + khz + ": cached burst did not verify; running the full tune");
        // The full path replays a captured tune, which expects a closed output.
        closeOutput();
        return false;
    }

    /** Closes the demod output and stops the TS engine (the retune's precondition). */
    private void closeOutput() {
        enableVideo(false);
        try {
            controlOut(OUTPUT_OFF);
        } catch (TransportException error) {
            log.info("close output failed: " + error.getMessage());
        }
    }

    /** A cached burst worked when it locks and the TMCC still reports the cached mux. */
    private boolean verifyCached(int khz, ZapEntry entry) {
        if (!locked() && !awaitLock(CACHED_LOCK_TIMEOUT_MS)) {
            log.info("zap " + khz + ": no lock");
            return false;
        }
        // The TMCC updates once per frame, so the first read after the output opens can still
        // carry the previous channel's mux. Re-read for a short window; a genuinely different mux
        // fails every read and the caller runs the full procedure.
        long deadline = System.currentTimeMillis() + CACHED_TMCC_TIMEOUT_MS;
        Tmcc last = null;
        while (true) {
            Tmcc decoded = quietTmcc();
            if (decoded != null) {
                last = decoded;
                if (Tune.sameChannel(decoded, entry.decoded)) return true;
            }
            if (System.currentTimeMillis() >= deadline) break;
            sleep(CACHED_POLL_MS);
        }
        log.info("zap " + khz + (last == null
                ? ": no usable TMCC" : ": the TMCC reports another mux"));
        return false;
    }

    /** Caches a captured replay's final block, against the TMCC the locked demod now reports. */
    private void cacheCaptured(int khz, Record[] records) {
        Tune.Split split = Tune.splitTune(records);
        if (!split.found()) return;
        Tmcc decoded = tmcc();
        if (decoded == null) return;
        cachePut(khz, Arrays.copyOfRange(records, split.finalIndex, records.length), decoded);
    }

    /** Keeps a tuned frequency's PLL words, final channel block and the TMCC it came from. */
    private void cachePut(int khz, Record[] block, Tmcc decoded) {
        if (!cacheEnabled || block == null || block.length == 0 || decoded == null) return;
        if (!Tune.plausible(decoded)) return;
        Map<Integer, Integer> pll;
        try {
            pll = Tune.tunerPll(khz);
        } catch (IllegalArgumentException error) {
            return;
        }
        zaps.put(khz, new ZapEntry(pll, block.clone(), decoded));
        log.info("zap " + khz + ": cached the channel block (" + block.length + " records)");
    }

    /**
     * Waits for the lock word, or for a clean run of {@link #STREAM_LOCK_PACKETS} packets.
     *
     * <p>The lock word alone is not enough: a layer-B mux streams cleanly with layer A's MPEG lock
     * bit clear, and a register read right after a search is not evidence of a stream. The packets
     * are counted by the reader/feeder threads, so this must run with the reader live.</p>
     */
    private boolean awaitLock(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long baselinePackets = packets;
        long baselineErrors = framer.syncErrors;
        while (System.currentTimeMillis() < deadline) {
            if (locked()) return true;
            if (packets - baselinePackets >= STREAM_LOCK_PACKETS
                    && framer.syncErrors == baselineErrors) {
                log.info("tune: " + (packets - baselinePackets) + " packets in the first run, no resync");
                return true;
            }
            sleep(50);
        }
        return false;
    }

    /** One 16-bit demod register write: {@code [0x03][0x80][reg:2][value:2]}. */
    private void writeWord(int register, int value) throws TransportException {
        byte[] data = {0x03, (byte) 0x80, (byte) ((register >> 8) & 0xFF), (byte) (register & 0xFF),
                (byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
        controlOut(data);
    }

    private void controlOut(byte[] data) throws TransportException {
        transport.controlOut(0x03, 0, 0, data);
    }

    /**
     * Starts a search at the mode the channel block just wrote (register 0 bit 15).
     *
     * <p>{@code dib8000_autosearch_start} writes {@code (1 << 15) | P_mode} to register 0, reads
     * the IRQ register to clear the pending count, then writes the value back with bit 15 clear.
     * The capture's own trigger is that pair ({@code 0x0000 = 0xe000} then {@code 0x6000}).</p>
     */
    private void restartSearch() throws TransportException {
        int value = demodWord(0);
        if (value < 0) value = 0x6000;                   // 8K, the PH band's mode
        writeWord(0, value | 0x8000);
        demodWord(SEARCH_IRQ_REGISTER);                  // clear the pending count
        writeWord(0, value & ~0x8000);
    }

    /**
     * Waits for one acquisition attempt: a lock word or the autosearch success bit.
     *
     * <p>The full lock word does not appear until the final channel block is replayed, so bit 1 of
     * {@code 0x0504} - the demod's own "search succeeded" - is the signal to read the TMCC. Bit 0
     * ends the attempt early: the search failed for this candidate.</p>
     */
    private boolean waitFound(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int value = demodWord(LOCK_REGISTER);
            if (value >= 0 && lockWord(value)) return true;
            int irq = demodWord(SEARCH_IRQ_REGISTER);
            if (irq >= 0) {
                if ((irq & 0x0002) != 0) return true;
                if ((irq & 0x0001) != 0) return false;
            }
            sleep(ACQUISITION_POLL_MS);
        }
        return false;
    }

    /** Waits for dib8000_autosearch_irq: bit 1 of register 0x0504 means the search succeeded. */
    private boolean waitSearch(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int value = demodWord(SEARCH_IRQ_REGISTER);
            if (value >= 0 && (value & 0x0002) != 0) return true;
            if (value >= 0 && (value & 0x0001) != 0) return false;
            sleep(100);
        }
        return false;
    }

    /**
     * Sends one captured session in order, reproducing the captured gaps (capped at one second).
     * Returns the number of transfers that stalled; a stall is normal while the demod comes out of
     * reset, so it is counted rather than fatal.
     */
    public int replay(Record[] records) throws TransportException {
        return replay(records, true);
    }

    /**
     * Sends a captured session in order. With {@code timed} it reproduces the captured gaps (capped
     * at one second); a cached channel block is a fixed register script and is sent flat out - its
     * gaps are the capture's own lock polls, and the caller verifies the lock instead.
     *
     * @return the number of transfers that stalled (normal while the demod comes out of reset)
     */
    private int replay(Record[] records, boolean timed) throws TransportException {
        double previous = 0;
        int stalls = 0;
        long started = System.currentTimeMillis();
        for (Record record : records) {
            if (timed && record.t > 0 && previous > 0) {
                double delayMs = (record.t - previous) * 1000.0;
                if (delayMs > 2) sleep((long) Math.min(delayMs, 1000));
            }
            if (record.t > 0) previous = record.t;
            try {
                if (record.out) {
                    transport.controlOut(record.request, record.value, record.index, record.data);
                } else {
                    transport.controlIn(record.request, record.value, record.index, record.length);
                }
            } catch (TransportException error) {
                stalls++;
                if (stalls <= 5) log.info("record " + record.request + " stalled: "
                        + error.getMessage() + " (tolerated: the capture's own probe, continuing)");
            }
        }
        log.info("session " + records.length + " records, " + stalls + " stalls, "
                + (System.currentTimeMillis() - started) + " ms ("
                + (timed ? "captured timing paced, cap 1 s" : "cached block, no pacing") + ")");
        if (timed) sleep(100);
        return stalls;
    }

    /** The kernel's own streaming start: arm bulk IN 0x82, then ENABLE_VIDEO with "0f 10 11 00". */
    private void startStream() {
        try {
            transport.clearHalt(EP_TS_IN);
        } catch (TransportException error) {
            log.info("clearHalt(0x82) failed: " + error.getMessage());
        }
        byte[] on = {REQUEST_ENABLE_VIDEO, 0x10, 0x11, 0x00};
        try {
            transport.controlOut(REQUEST_ENABLE_VIDEO, 0, 0, on);
            log.info("stream start (ENABLE_VIDEO 0x0F)");
        } catch (TransportException error) {
            log.info("ENABLE_VIDEO failed: " + error.getMessage());
        }
    }

    private void enableVideo(boolean on) {
        byte[] data = {REQUEST_ENABLE_VIDEO, (byte) (on ? 0x10 : 0x00),
                (byte) (0x10 | (on ? 0x01 : 0x00)), 0x00};
        try {
            transport.controlOut(REQUEST_ENABLE_VIDEO, 0, 0, data);
            log.info("enableVideo(" + on + ")");
        } catch (TransportException error) {
            log.info("enableVideo(" + on + ") failed: " + error.getMessage());
        }
    }

    // -----------------------------------------------------------------------------------------
    // TMCC
    // -----------------------------------------------------------------------------------------

    /**
     * One-shot TMCC read: the demod's own ISDB-T channel report - mode, guard interval, partial
     * reception, inversion, sb, the EWBS flag and the three layers' modulation / segments / code
     * rate / interleaving.
     *
     * <p>Every register in {@link #TMCC_REGISTERS} is read exactly once per call through
     * {@link #demodWord}; there is no polling and no cache, because the demod reports what the
     * autosearch found and a stale decode would describe another channel. A register that does not
     * answer stays {@link Tmcc#UNKNOWN} rather than being decoded as zero. Usable before lock:
     * the demod needs the TMCC to demodulate at all.</p>
     *
     * <p>USB work: call it off the host's UI thread.</p>
     */
    public Tmcc tmcc() {
        Map<Integer, Integer> words = readTmccWords();
        Tmcc decoded = Tmcc.decode(words);
        if (!decoded.valid()) {
            log.info("tmcc not decoded yet (mode/guard/layers unreadable - the demod has not decoded "
                    + "TMCC on this channel): " + decoded.text());
            return null;
        }
        log.info("tmcc " + decoded.text() + " (" + words.size() + "/"
                + TMCC_REGISTERS.length + " registers)");
        return decoded;
    }

    /** How long the two-read TMCC settle waits between reads: the TMCC updates once per frame. */
    private static final int TMCC_SETTLE_MS = 400;
    /** The door's default attempt bound: the package retries the pair, the app does not. */
    private static final int TMCC_SETTLE_ATTEMPTS = 3;

    /**
     * The two-read settle's result: the agreed report, or why there is none.
     *
     * <p>The host-log shape's rule 3 (IT9175, 2026-11-21): a codeword is only shown when two
     * reads agree. One read can catch the previous mux just after a switch, which is how that
     * line displayed a channel it was not on for an afternoon.</p>
     */
    public static final class TmccSettle {

        /** The settled report, or null when the two reads did not settle. */
        public final Tmcc tmcc;

        /** Why there is no settled report (null when {@link #tmcc} is set): the app displays it. */
        public final String refusal;

        private TmccSettle(Tmcc tmcc, String refusal) {
            this.tmcc = tmcc;
            this.refusal = refusal;
        }

        public boolean ok() {
            return tmcc != null;
        }
    }

    /**
     * The door's settled TMCC read: two agreeing valid reads, bounded attempts, no latency
     * promise. The no-arg call is {@code (3, 400)} - the package's choice, not the app's loop.
     *
     * <p>"Did not settle (two valid reads disagree)" is a different answer from "not decoded yet",
     * and the app must say which: the first means the demod is decoding another mux, the second
     * that it has not decoded one at all.</p>
     *
     * <p>USB work: call it off the host's UI thread. Each read is the same one-shot register map
     * as {@link #tmcc()}; nothing is cached.</p>
     */
    public TmccSettle tmccSettled() {
        return tmccSettled(TMCC_SETTLE_ATTEMPTS, TMCC_SETTLE_MS);
    }

    /**
     * The door's signature: up to {@code attempts} read pairs, {@code delayMs} between the two
     * reads of each pair. An attempt that disagrees is retried; the refusal keeps the first and the
     * last reading so the log says what changed.
     */
    public TmccSettle tmccSettled(int attempts, int delayMs) {
        int tries = Math.max(1, attempts);
        long delay = Math.max(0, delayMs);
        Tmcc first = quietTmcc();
        if (first == null) {
            String refusal = "not decoded yet (mode/guard/layers unreadable: the demod has not "
                    + "decoded TMCC on this channel)";
            log.info("tmcc refused: " + refusal);
            return new TmccSettle(null, refusal);
        }
        Tmcc latest = first;
        for (int attempt = 1; attempt <= tries; attempt++) {
            sleep(delay);
            Tmcc next = quietTmcc();
            if (next == null) {
                String refusal = "did not settle (a read could not be decoded)";
                log.info("tmcc refused: " + refusal);
                return new TmccSettle(null, refusal);
            }
            if (Tune.sameChannel(latest, next)) {
                log.info("tmcc settled " + next.text());
                return new TmccSettle(next, null);
            }
            latest = next;
        }
        String refusal = "did not settle (two valid reads disagree: "
                + Tmcc.modeLabel(first.mode) + " " + Tmcc.guardLabel(first.guard)
                + " -> " + Tmcc.modeLabel(latest.mode) + " " + Tmcc.guardLabel(latest.guard)
                + ")";
        log.info("tmcc refused: " + refusal);
        return new TmccSettle(null, refusal);
    }

    /** {@link #tmccSettled(int, int)} with one pair: the settle interval a test chooses. */
    public TmccSettle tmccSettled(long settleMs) {
        return tmccSettled(1, (int) Math.min(Integer.MAX_VALUE, Math.max(0, settleMs)));
    }

    /** The demod's channel report without the logging: the cached verification's read. */
    private Tmcc quietTmcc() {
        Tmcc decoded = Tmcc.decode(readTmccWords());
        return decoded.valid() ? decoded : null;
    }

    private Map<Integer, Integer> readTmccWords() {
        Map<Integer, Integer> words = new LinkedHashMap<>();
        for (int register : TMCC_REGISTERS) {
            int value = demodWord(register);
            if (value >= 0) words.put(register, value);
        }
        return words;
    }

    // -----------------------------------------------------------------------------------------
    // Streaming
    // -----------------------------------------------------------------------------------------

    /**
     * USB reader: never waits for parsing, only for the queue to be full. The bridge FIFO holds
     * about 1.4 ms of stream, so a reader that parses inline loses packets.
     */
    private void usbReaderLoop() {
        byte[] buffer = new byte[SLAB];
        boolean announced = false;
        boolean warned = false;
        long lastData = System.currentTimeMillis();
        long windowStart = System.currentTimeMillis();
        long windowBytes = 0;
        int windowReads = 0;
        int windowTimeouts = 0;
        while (running) {
            if (replaying) {
                sleep(10);
                continue;
            }
            int n;
            try {
                n = transport.bulkIn(buffer, 500);
            } catch (TransportException error) {
                if (!running) break;
                sleep(5);
                continue;
            }
            if (n > 0) {
                lastData = System.currentTimeMillis();
                warned = false;
                windowBytes += n;
                windowReads++;
                byte[] copy = takeCopy(buffer, n, pool);
                if (!queue.offer(copy)) {
                    queue.poll();
                    queue.offer(copy);
                    dropped++;
                }
                if (!announced) {
                    log.info("streaming, first slab " + n + " bytes");
                    announced = true;
                }
            } else {
                windowTimeouts++;
                if (!warned && System.currentTimeMillis() - lastData > 5000) {
                    boolean lock = locked();
                    log.info("no TS for 5 s (lock " + (lock ? "yes" : "no") + "), re-arming video");
                    if (lock) {
                        log.info("if it persists while locked: unplug and replug the stick "
                                + "(a USB reset is not a power cycle)");
                        startStream();
                    }
                    warned = true;
                }
            }
            long now = System.currentTimeMillis();
            if (now - windowStart >= 5000) {
                log.info(String.format(Locale.US,
                        "stream %.2f Mbps, %d reads, %d timeouts, %d pkts, %d cbs, %d q, %d drop",
                        windowBytes * 8.0 / 5e6, windowReads, windowTimeouts, packets,
                        callbacks, queue.size(), dropped));
                windowStart = now;
                windowBytes = 0;
                windowReads = 0;
                windowTimeouts = 0;
            }
        }
        log.info("reader stopped: " + counters());
    }

    private void feedLoop() {
        while (running) {
            byte[] slab;
            try {
                slab = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
            if (slab == null) continue;
            int whole = framer.feed(slab, slab.length, sink);
            if (whole > 0) {
                packets += whole / TS_PACKET;
                callbacks++;
            }
            if (slab.length == SLAB && pool.size() < 256) pool.add(slab);
        }
        Listener target = listener;
        if (target != null && !stoppedByCaller) target.onEnd();
    }

    // -----------------------------------------------------------------------------------------
    // Status
    // -----------------------------------------------------------------------------------------

    private void statusLoop() {
        while (running) {
            sleep(1000);
            if (!running) return;
            readStatus();
            Status copy = new Status();
            copy.copyFrom(live);
            snapshot = copy;
            haveStatus = true;
        }
    }

    /** Reads the demod registers and refreshes the live snapshot; USB work, never on the UI thread. */
    public Status readStatus() {
        live.locked = locked();
        int raw = 65535 - demodWord(STRENGTH_REGISTER);
        if (raw >= 0 && raw <= 65535) {
            live.rawStrength = raw;
            live.strengthPercent = (int) Math.round(raw * 100.0 / 65535.0);
        }
        live.qualityPercent = quality.percent(nowSeconds());
        long now = System.currentTimeMillis();
        if (now - lastStatsLog > 5000) {
            lastStatsLog = now;
            log.info(String.format(Locale.US,
                    "stats: raw=%d (%d%%), quality=%s, lock=%s",
                    live.rawStrength, live.strengthPercent,
                    live.qualityPercent < 0 ? "n/a" : live.qualityPercent + "%",
                    live.locked));
        }
        return live;
    }

    /**
     * AGC strength as a percentage, from register 390; -1 when the read fails (the Python
     * driver's {@code strength_percent}). Not dB and not C/N.
     */
    public int strengthPercent() {
        int value = demodWord(STRENGTH_REGISTER);
        if (value < 0) return -1;
        int raw = 65535 - value;
        if (raw < 0 || raw > 65535) return -1;
        return (int) Math.round(raw * 100.0 / 65535.0);
    }

    private int demodWord(int register) {
        try {
            byte[] reply = transport.controlIn(REQUEST_I2C_READ, I2C_READ_VALUE, register, 2);
            if (reply == null || reply.length < 2) return -1;
            return ((reply[0] & 0xFF) << 8) | (reply[1] & 0xFF);
        } catch (TransportException error) {
            return -1;
        }
    }

    /**
     * True when the demod reports a lock: coff/cpil and lmod4 (0x1C00), FEC (bit 12) and the MPEG
     * lock of <b>any</b> layer that carries segments (0x00E0).
     *
     * <p>{@code dib8000_get_status} sets FE_HAS_LOCK for bit 12 with any of bits 7/6/5;
     * requiring layer A's bit 7 specifically rejected a layer-B mux (measured 2026-11-20).</p>
     */
    public boolean locked() {
        int value = demodWord(LOCK_REGISTER);
        if (value < 0) {
            log.info("lock read failed");
            return false;
        }
        boolean ok = lockWord(value);
        long now = System.currentTimeMillis();
        if (now - lastLockLog > 2000) {
            lastLockLog = now;
            log.info(String.format(Locale.US, "lock reg 0x%04x -> %s", value, ok ? "LOCKED" : "no lock"));
        }
        return ok;
    }

    private static boolean lockWord(int value) {
        return (value & LOCK_BITS) == LOCK_BITS && (value & LAYER_LOCK_BITS) != 0;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static double nowSeconds() {
        return System.nanoTime() / 1e9;
    }
}

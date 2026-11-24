/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700;

import java.util.Locale;
import java.util.Map;

/**
 * The {@code 0xC0} tuner behind the demod's I2C bridge, and the live dib0090 calibration.
 *
 * <p>The bridge reaches the tuner only while demod register {@code 0x0401} is {@code 0xC000}, and
 * the capture wraps every tuner read and write that way ({@code _gate}). A read that does not
 * come back raises instead of returning a guess: a calibration that invents an ADC value tunes
 * blind.</p>
 *
 * <p>The calibration is a read-driven state machine, so it is generated, not replayed: the device
 * branches on the ADC it returns (register {@code 0x1d}), and one captured branch is not the
 * branch another frequency takes. The order is the kernel's {@code dib0090_tune}: DC offset ->
 * WBD -> temperature -> PLL -> captrim -> post-captrim gain/baseband. A first tune after a reset
 * runs the DC offset calibration; a re-tune keeps the DC trims the base tune left in
 * {@code 0x06/0x07} and does only WBD and temperature ({@code dib0090_reset} sets
 * {@code DC_CAL | WBD_CAL | TEMP_CAL}; a re-tune does not). Re-running the DC search on an already
 * tuned chip searches against a different baseband state and lands on a wrong offset (measured:
 * the same-frequency control stopped locking), while the kernel never does it.</p>
 *
 * <p>Register numbers and constants are the GPL-2.0-only kernel map ({@code dib0090.c}) as
 * numbers only; the code is this tree's port of {@code dib0700/tune.py}.</p>
 */
public final class Tuner {

    /** The tuner's I2C address. */
    public static final int ADDRESS = 0xC0;

    /** The I2C read window: the tuner register is {@code wIndex >> 8}. */
    public static final int READ_VALUE = 0x01C1;

    private static final int BUS_CONTROL = 0x0401;
    private static final int BUS_TUNER = 0xC000;
    private static final int BUS_DEMOD = 0x0100;

    private static final int DC_OFFSET_START = 0x040D;   // force vcm2 = 0.8 V before the trims
    private static final int DC_OFFSET_GAIN = 0x0FFB;    // LNA and LO off in register 0x24
    // addr, trim-register offset, pga, CTRL_BB1 value, i (0 = I path, 1 = Q path)
    // dib0090_dc_table: BB gain1 = 26 with boost 1, gain 2 = 0, then gain 2 = 29.
    private static final int[][] DC_TABLE = {
            {0x06, 5, 1, (1 << 13) | (0 << 8) | (26 << 3), 1},
            {0x07, 11, 1, (1 << 13) | (0 << 8) | (26 << 3), 0},
            {0x06, 0, 0, (1 << 13) | (29 << 8) | (26 << 3), 1},
            {0x06, 10, 0, (1 << 13) | (29 << 8) | (26 << 3), 0},
    };
    private static final int CAPTRIM_LO4 = 0xE900;       // 0x18 = lo4 | captrim
    private static final int UHF_ENABLE = 0x22E9;        // EN_UHF: LNA2, MIX2, IQADC, PLL, BB, BIAS
    private static final int CRYSTAL = 0x0002;           // EN_CRYSTAL, the analog-output enable
    private static final int WBD_GAIN_UHF = 6;           // dib0090_wbd_table_default: 470-860 MHz
    private static final int WBD_MUX =
            (WBD_GAIN_UHF << 13) | (3 << 11) | 0x781 | (1 << 1);
    private static final int UHF_LNA_BIAS = 15;
    private static final int UHF_V2I = 0x0300;
    private static final int UHF_MIX = 0x1D12;
    private static final int UHF_LOAD = 0xB9CE;
    private static final int BANDWIDTH_6MHZ = 2 << 14;   // 6000 kHz -> 2; bb_1_def is 0 here

    /** The kernel's 10 ms floor between calibration ADC steps. */
    public static final int ADC_SETTLE_MS = 10;

    /**
     * The PLL block's register order, exactly as the captures write it. The zap cache replays just
     * these words to move the LO back to a frequency this session already calibrated.
     */
    private static final int[] PLL_ORDER = {0x0B, 0x15, 0x16, 0x17, 0x19, 0x1C, 0x24};

    /** The transport, which must be the real device or a test fake. */
    private final Transport transport;
    private final Log log;
    private int writes;
    private int reads;

    public Tuner(Transport transport, Log log) {
        this.transport = transport;
        this.log = log == null ? Log.NONE : log;
    }

    /** Tuner register writes issued so far (the gate writes are not counted). */
    public int writes() {
        return writes;
    }

    /** Tuner register reads issued so far. */
    public int reads() {
        return reads;
    }

    // --- bus access --------------------------------------------------------------------------

    private void gate(int value) throws TransportException {
        byte[] data = {0x03, (byte) 0x80, (byte) (BUS_CONTROL >> 8), (byte) BUS_CONTROL,
                (byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
        transport.controlOut(0x03, 0, 0, data);
    }

    /** One tuner register write, wrapped in the bridge gate. */
    public void write(int register, int value) throws TransportException {
        gate(BUS_TUNER);
        byte[] data = {0x03, (byte) ADDRESS, (byte) register,
                (byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
        transport.controlOut(0x03, 0, 0, data);
        gate(BUS_DEMOD);
        writes++;
    }

    /**
     * One tuner register read ({@code 0x02}, {@code wValue 0x01C1}, {@code wIndex = register << 8}).
     *
     * @throws TransportException when the tuner does not answer: a guess would tune blind
     */
    public int read(int register) throws TransportException {
        gate(BUS_TUNER);
        byte[] reply;
        try {
            reply = transport.controlIn(0x02, READ_VALUE, register << 8, 2);
        } finally {
            gate(BUS_DEMOD);
        }
        reads++;
        if (reply == null || reply.length < 2) {
            throw new TransportException("tuner register 0x" + Integer.toHexString(register)
                    + " did not answer");
        }
        return ((reply[0] & 0xFF) << 8) | (reply[1] & 0xFF);
    }

    /** The kernel's pacing between calibration steps. */
    public void wait(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    // --- the dib0090 tune --------------------------------------------------------------------

    /**
     * Writes a PLL block ({@link Tune#tunerPll}'s seven words) to the {@code 0xC0} tuner, in the
     * captured order. The cached zap uses this alone: no calibration, no search.
     */
    public void writePll(Map<Integer, Integer> pll) throws TransportException {
        for (int register : PLL_ORDER) {
            Integer value = pll.get(register);
            if (value == null) {
                throw new IllegalArgumentException("PLL block has no 0x"
                        + Integer.toHexString(register) + " word");
            }
            write(register, value);
        }
    }

    /**
     * The whole dib0090 tune, live, from the bus and the frequency.
     *
     * <p>The caller closes the demod output gate first. The order is the kernel's: the
     * calibrations, the PLL block for {@code khz}, the captrim search on the locked LO, then the
     * band's gain/mixer/filter configuration. Pass {@code dc = false} for a re-tune (see the class
     * note); {@link Dib0700#retune} does.</p>
     */
    public void calibrate(int khz, Map<Integer, Integer> pll, boolean dc)
            throws TransportException {
        if (dc) {
            // dib0090_reset leaves 0x24 = EN_UHF | EN_CRYSTAL, and the DC calibration is written
            // to run from that state: its trims are searched against the IQ ADC.
            write(0x24, UHF_ENABLE | CRYSTAL);
            write(0x23, read(0x23) & ~(1 << 14));       // DataTX off before the calibration
            dcOffsetCalibration();
        }
        write(0x23, read(0x23) & ~(1 << 14));
        int wbdOffset = wbdCalibration();
        write(0x23, read(0x23) & ~(1 << 14));
        int temperature = temperature();
        writePll(pll);
        wait(11);
        int rest = pll.get(0x17);
        int[] captrim = captrimSearch(rest == 0 ? 200 : 400, 64);
        // After the captrim search: the final WBD mux, the UHF LNA/mixer/load row and the
        // baseband filter (dib0090_tune's step 0 and step 1). Then the tuner is tuned.
        write(0x1E, 0x07FF);
        write(0x10, WBD_MUX);
        write(0x09, (Tune.lnaTune(khz) << 5) | UHF_LNA_BIAS);
        write(0x0C, UHF_V2I);
        write(0x0D, UHF_MIX);
        write(0x0E, UHF_LOAD);
        write(0x01, BANDWIDTH_6MHZ);                    // be sure of the baseband filter
        write(0x03, 0x6008);
        write(0x04, 0x0001);
        write(0x02, (5 << 11) | (8 << 6) | 22);
        write(0x05, 0xABCD);
        log.info(String.format(Locale.US,
                "tuner %d kHz: captrim %d (ADC distance %d), WBD offset %d, temperature %d C",
                khz, captrim[0], captrim[1], wbdOffset, temperature - 30));
    }

    /**
     * {@code dib0090_dc_offset_calibration}: four trims, each a sign search on the 0x1d ADC.
     *
     * <p>A trim starts at step 0 and steps up while the ADC difference keeps its sign. When the
     * sign changes - or the step limit is reached - the search stops, stepping back one if the
     * last value was farther from zero than the previous one. The pga rows start on the -16 side
     * (step 0x10), which is the bit-15 direction of the same field.</p>
     */
    public void dcOffsetCalibration() throws TransportException {
        int bb6 = 0x0000;
        int bb7 = DC_OFFSET_START;
        write(0x24, read(0x24) & DC_OFFSET_GAIN);            // LNA and LO off
        write(0x10, (read(0x10) & ~(0xFF << 3)) | (0x7 << 3) | 0x3);
        write(0x23, read(0x23) & ~(1 << 14));                // DataTX off while calibrating
        int stepsTaken = 0;
        for (int[] row : DC_TABLE) {
            int addr = row[0];
            int offset = row[1];
            boolean pga = row[2] != 0;
            write(0x01, row[3]);
            write(0x07, bb7 | (row[4] << 7));
            int step = 0;
            int minAdcDiff = 1023;
            while (true) {
                int[] trim = setTrim(addr, offset, step, bb6, bb7);
                bb6 = trim[0];
                bb7 = trim[1];
                write(0x1F, 0x7);                            // turn the offset positive
                wait(ADC_SETTLE_MS);
                int adcDiff = read(0x1D);
                write(0x1F, 0x4);                            // turn it negative
                wait(ADC_SETTLE_MS);
                adcDiff = signed16(adcDiff - read(0x1D));
                stepsTaken++;
                if (step == 0 && adcDiff < 0) minAdcDiff = -1023;
                if (step == 0) {
                    if (pga && adcDiff < 0) step = 0x10;
                    if (!pga && adcDiff > 0) step = 0x10;
                }
                if ((adcDiff & 0x8000) == (minAdcDiff & 0x8000) && steps(step) < 15) {
                    step = (step + 1) & 0xFF;
                    minAdcDiff = adcDiff;
                    continue;
                }
                if (Math.abs(adcDiff) > Math.abs(minAdcDiff)) {
                    step = (step - 1) & 0xFF;                // the minimum was the step before
                }
                trim = setTrim(addr, offset, step, bb6, bb7);
                bb6 = trim[0];
                bb7 = trim[1];
                break;
            }
        }
        write(0x07, bb7 & ~0x0008);
        write(0x1F, 0x7);
        log.info(String.format(Locale.US, "tuner: DC offset %d steps, 0x06=0x%04x 0x07=0x%04x",
                stepsTaken, bb6, bb7));
    }

    private int[] setTrim(int addr, int offset, int step, int bb6, int bb7)
            throws TransportException {
        int mask = ~(0x1F << offset) & 0xFFFF;
        int field = (step & 0x1F) << offset;
        if (addr == 0x07) {
            bb7 = (bb7 & mask) | field;
            write(addr, bb7);
        } else {
            bb6 = (bb6 & mask) | field;
            write(addr, bb6);
        }
        return new int[]{bb6, bb7};
    }

    /** {@code dib0090_wbd_calibration}: sample the WBD multiplexer offset for the band. */
    public int wbdCalibration() throws TransportException {
        write(0x10, 0x1B81 | (1 << 10) | (WBD_GAIN_UHF << 13) | (1 << 3));
        write(0x24, (UHF_ENABLE & 0x0FFF) | CRYSTAL);
        wait(18);                                        // the WBDMUX switch and ADC sample
        return read(0x1D);
    }

    /**
     * {@code dib0090_get_temperature}: two bias settings, one ADC difference, a Celsius estimate.
     */
    public int temperature() throws TransportException {
        int wbdmux = read(0x10);
        write(0x10, (wbdmux & ~(0xFF << 3)) | (0x8 << 3));
        int bias = read(0x13);
        write(0x13, bias | (0x3 << 8));
        wait(ADC_SETTLE_MS);
        int hot = read(0x1D);
        write(0x13, (bias & ~(0x3 << 8)) | (0x2 << 8));
        wait(ADC_SETTLE_MS);
        int cold = read(0x1D);
        int temperature = (signed16((cold - hot) * 180) >> 8) + 55;
        write(0x13, bias);
        write(0x10, wbdmux);                             // write the original WBDMUX back
        return temperature;
    }

    /**
     * {@code dib0090_captrim_search}: the binary search on the 0x18 capacitor trim.
     *
     * <p>{@code start} is 64 for a full search; the kernel's "already tuned" variant starts at 4.
     * Each pass halves the step, writes {@code 0xE900 | captrim}, reads the ADC and moves the trim
     * toward the target, keeping the closest trim seen. Returns {@code (final captrim, best
     * distance)}.</p>
     */
    public int[] captrimSearch(int target, int start) throws TransportException {
        write(0x10, 0x02B1);
        write(0x1E, 0x0032);
        int step = start;
        int captrim = start;
        int fcaptrim = start;
        int best = 3000;
        while (true) {
            step /= 2;
            write(0x18, CAPTRIM_LO4 | captrim);
            wait(ADC_SETTLE_MS);
            int adc = read(0x1D);
            int sign;
            if (adc >= target) {
                adc -= target;
                sign = -1;
            } else {
                adc = target - adc;
                sign = 1;
            }
            if (adc < best) {
                best = adc;
                fcaptrim = captrim;
            }
            captrim += sign * step;
            if (step < 1) {
                write(0x18, CAPTRIM_LO4 | fcaptrim);     // the final trim configuration
                return new int[]{fcaptrim, best};
            }
        }
    }

    /** Truncate to a signed 16-bit word, the width the kernel's state uses. */
    private static int signed16(int value) {
        int word = value & 0xFFFF;
        return word >= 0x8000 ? word - 0x10000 : word;
    }

    /** {@code dib0090.c}'s {@code steps(u)}: a trim step above 15 carries its sign in bit 4. */
    private static int steps(int step) {
        return step > 15 ? step - 16 : step;
    }
}

/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parameterised tune: the tuner PLL arithmetic, the retune plan and the channel-block patches.
 *
 * <p>This is {@code dib0700/tune.py} ported to the JVM. A capture is mux-specific, so replaying one
 * whole session only tunes the channel it was recorded on. Everything else follows from two
 * things the capture gives us and the device tells us:</p>
 *
 * <ul>
 *   <li>the <b>tune preamble and the demod's AGC/autosearch configuration</b> are replayed from
 *       the nearest capture (the tune itself is a state machine, so a transcript of one run is
 *       the portable part);</li>
 *   <li>the <b>tuner PLL and the dib0090 calibration</b> are regenerated: {@link #tunerPll} from
 *       the kernel's own formula, the calibration live from the ADC reads (the device branches on
 *       them, so one captured branch is not another frequency's branch);</li>
 *   <li>the <b>final channel block</b> is patched from the TMCC the demod reports after its own
 *       search - the layer words, mode/guard/partial reception, the channel estimate and the
 *       stream-side configuration. The demod finds the channel; we do not guess it.</li>
 * </ul>
 *
 * <p>Register sequences and constants are protocol data observed on the hardware and the
 * GPL-2.0-only kernel map ({@code dib0090_set_params}, {@code dib8000_set_isdbt_common_channel},
 * {@code dib8000_adp_fine_tune}); the code is this tree's. Frequencies outside the tuner's UHF
 * table are refused rather than guessed.</p>
 */
public final class Tune {

    private Tune() {
    }

    /** The default tune frequency of the Python driver. */
    public static final int DEFAULT_KHZ = 599143;

    /** Frequencies with a captured session: the tune procedures a retune can replay. */
    public static final int[] CAPTURED = {479143, 485143, 497143, 599143};

    // --- tuner PLL: dib0090_set_params -------------------------------------------------------

    /** The STK8096GP UHF offset in kHz (12 MHz clock / 6 with -63 kHz). */
    public static final int UHF_OFFSET_KHZ = -63;

    /** {@code io.clock_khz 12000 / fref_clock_ratio 6}. */
    public static final int FREF_KHZ = 2000;

    /** The loop-filter discrimination step in {@code dib0090_set_params}. */
    public static final int LPF_KHZ = 100;

    /** What the measured captures write for a {@code *.143} MHz channel: 0x1980. */
    public static final int REST_FULL = 6528;

    /** CH14 is the PH band plan's first channel (473 + 1/7 MHz). */
    public static final int MIN_KHZ = 473143;

    /** CH51 is the PH band plan's last channel (695 + 1/7 MHz). */
    public static final int MAX_PH_KHZ = 695143;

    // {max rf kHz, lo5 with a fractional rest, lo5 with an exact PLL, hfdiv code}
    // dib0090_pll_table's UHF rows. The captures pin the first two: 479/485/497 read 0x1c9e,
    // 599 switches to 0x141d. The vendor driver's own copy of the table has the identical rows.
    private static final int[][] VCO_BANDS = {
            {580000, 0x049E, 0x049F, 3},
            {700000, 0x041D, 0x041F, 2},
            {860000, 0x049E, 0x049F, 2},
    };

    // dib0090_tuning_table's UHF LNA rows: the code changes at 520/550/650/750/850/900 MHz, not at
    // every frequency (the captures show 0 for 479/485/497 and 3 for 599).
    private static final int[][] UHF_LNA_TUNE = {
            {520000, 0}, {550000, 2}, {650000, 3}, {750000, 5}, {850000, 6}, {900000, 7},
    };

    /** {@code 0x0000 = 0xe000}: P_restart_search. */
    public static final String SEARCH_RESTART = "03800000e000";

    /** {@code 0x0000 = 0x6000}: the captured write that actually starts the search. */
    public static final String SEARCH_TRIGGER = "038000006000";

    /**
     * The tuner writes that move the LO to {@code khz}, from the dib0090 formula.
     *
     * <p>Returns {@code {register: value}} for the 0xC0 tuner writes. FBDiv follows the requested
     * frequency, the rest of the PLL block follows the VCO band. The fractional-rest handling is
     * the kernel's (LPF discriminators, {@link #REST_FULL} full scale), which is why every
     * {@code *.143} capture shows {@code 0x1980}.</p>
     *
     * @throws IllegalArgumentException below CH14 or above the last measured PLL row
     */
    public static Map<Integer, Integer> tunerPll(int khz) {
        int rf = khz + UHF_OFFSET_KHZ;
        int top = VCO_BANDS[VCO_BANDS.length - 1][0];
        if (khz < MIN_KHZ || rf > top) {
            throw new IllegalArgumentException(khz + " kHz is outside the UHF band ("
                    + MIN_KHZ + "-" + (top - UHF_OFFSET_KHZ) + " kHz)");
        }
        int[] band = null;
        for (int[] row : VCO_BANDS) {
            if (rf <= row[0]) {
                band = row;
                break;
            }
        }
        if (band == null) {
            throw new IllegalArgumentException(khz + " kHz is outside the measured tuner band");
        }
        int vcof = 2 * rf;
        int fbdiv = vcof / FREF_KHZ;
        int rest = vcof - fbdiv * FREF_KHZ;
        if (rest < LPF_KHZ) {
            rest = 0;
        } else if (rest < 2 * LPF_KHZ) {
            rest = 2 * LPF_KHZ;
        } else if (rest > FREF_KHZ - LPF_KHZ) {
            rest = 0;
            fbdiv++;
        } else if (rest > FREF_KHZ - 2 * LPF_KHZ) {
            rest = FREF_KHZ - 2 * LPF_KHZ;
        }
        rest = rest * REST_FULL / (FREF_KHZ / 10);

        Map<Integer, Integer> words = new LinkedHashMap<>();
        words.put(0x0B, 0xB802);                                    // analog switch trim
        words.put(0x15, fbdiv);                                     // FBDiv: the frequency word
        words.put(0x16, ((rest != 0 ? 0xFF : 0x01) << 8) | 0x06);   // Den + fref_clock_ratio
        words.put(0x17, rest);                                      // fractional rest
        words.put(0x19, (rest != 0 ? band[1] : band[2]) | (band[3] << 11));
        words.put(0x1C, rest != 0 ? 0xFF2E : 0xFFF8);               // loop filter
        words.put(0x24, 0x228F);                                    // EN_LO | tuner enable
        return words;
    }

    /**
     * The UHF LNA tuning code for {@code khz} (dib0090_tuning_table's banded rows).
     *
     * @throws IllegalArgumentException above the last measured LNA row
     */
    public static int lnaTune(int khz) {
        for (int[] row : UHF_LNA_TUNE) {
            if (khz <= row[0]) return row[1];
        }
        throw new IllegalArgumentException(khz + " kHz is outside the measured tuner band");
    }

    /** The captured frequencies as base states, nearest first (ties by frequency). */
    public static int[] baseCandidates(int khz) {
        int[] out = CAPTURED.clone();
        for (int index = 1; index < out.length; index++) {
            int value = out[index];
            int at = index - 1;
            while (at >= 0 && farther(out[at], value, khz)) {
                out[at + 1] = out[at];
                at--;
            }
            out[at + 1] = value;
        }
        return out;
    }

    private static boolean farther(int left, int right, int khz) {
        int a = Math.abs(left - khz);
        int b = Math.abs(right - khz);
        return a > b || (a == b && left > right);
    }

    /** The captured frequency whose demod state is the closest base for {@code khz}. */
    public static int nearest(int khz) {
        return baseCandidates(khz)[0];
    }

    // --- the captured tune's three parts -----------------------------------------------------

    /** The three landmarks of a captured tune: {@code start}, {@code restart} and the final block. */
    public static final class Split {

        /** First tuner {@code 0x23 = 0x4a30} write (the tune phase, not the power-up). */
        public final int start;

        /** {@code P_restart_search = 1} ({@code 0x0000 = 0xe000}). */
        public final int restart;

        /** First re-write of {@code P_corm_alpha} ({@code 0x0020 = 0x6680}) after the restart. */
        public final int finalIndex;

        Split(int start, int restart, int finalIndex) {
            this.start = start;
            this.restart = restart;
            this.finalIndex = finalIndex;
        }

        /** True when all three were found. */
        public boolean found() {
            return start >= 0 && restart >= 0 && finalIndex >= 0;
        }
    }

    /**
     * Locates a captured tune's three parts.
     *
     * <p>{@code start} is the first tuner register {@code 0x23} write of the tune phase; the
     * power-up writes {@code 0x0a30} instead of {@code 0x4a30}, so the value tells the two phases
     * apart. {@code restart} is {@code P_restart_search = 1}; {@code final} is the first re-write
     * of {@code P_corm_alpha} after it - the demod's final channel block, which carries the layer
     * words learned from the TMCC.</p>
     */
    public static Split splitTune(Record[] records) {
        int start = -1;
        int restart = -1;
        int finalIndex = -1;
        for (int index = 0; index < records.length; index++) {
            Record record = records[index];
            if (!record.out || record.request != 3) continue;
            byte[] data = record.data;
            if (start < 0 && data.length == 5 && (data[1] & 0xFF) == 0xC0
                    && (data[2] & 0xFF) == 0x23 && (data[3] & 0xFF) == 0x4A
                    && (data[4] & 0xFF) == 0x30) {
                start = index;
            } else if (data.length == 6 && startsWith(data, 0x03, 0x80, 0x00, 0x00)
                    && (data[4] & 0xFF) == 0xE0 && (data[5] & 0xFF) == 0x00) {
                restart = index;
            } else if (restart >= 0 && finalIndex < 0 && data.length == 6
                    && startsWith(data, 0x03, 0x80, 0x00, 0x20)
                    && (data[4] & 0xFF) == 0x66 && (data[5] & 0xFF) == 0x80) {
                finalIndex = index;
            }
        }
        return new Split(start, restart, finalIndex);
    }

    /**
     * The captured autosearch trigger after {@code P_restart_search}: its index, or
     * {@code restart} when the capture has none.
     *
     * <p>The tune phase writes {@code 0x0000 = 0xe000} (P_restart_search), reads the IRQ register
     * and then writes {@code 0x0000 = 0x6000}: that last write is what actually starts the search.
     * Replaying only through {@code 0xe000} leaves register {@code 0x0504} at 0 and no search runs
     * (measured on hardware).</p>
     */
    public static int splitSearch(Record[] records, int restart) {
        for (int index = restart + 1; index < records.length; index++) {
            Record record = records[index];
            if (record.out && record.request == 3 && hex(record.data).equals(SEARCH_TRIGGER)) {
                return index;
            }
        }
        return restart;
    }

    /** The first and one-past-last record of a captured tune's dib0090 calibration block. */
    public static final class Span {

        public final int first;
        public final int end;

        Span(int first, int end) {
            this.first = first;
            this.end = end;
        }
    }

    /**
     * Locates a captured tune's dib0090 calibration: the block the live calibration replaces.
     *
     * <p>{@code first} is the {@code 0x23} write that switches DataTX off ({@code 0x4a30 ->
     * 0x0a30}); the block runs through the tuner reads and bus-gate writes to the first record of
     * the demod's own AGC setup.</p>
     */
    public static Span splitCalibration(Record[] records, int start) {
        if (start < 0) start = splitTune(records).start;
        if (start < 0) throw new IllegalArgumentException("capture has no tune phase");
        int first = -1;
        for (int index = start; index < records.length; index++) {
            Record record = records[index];
            if (!tunerWrite(record)) continue;
            byte[] raw = record.data;
            if ((raw[2] & 0xFF) == 0x23 && (((raw[3] & 0xFF) << 8) | (raw[4] & 0xFF)) == 0x0A30) {
                first = index;
                break;
            }
        }
        if (first < 0) throw new IllegalArgumentException("capture has no dib0090 calibration");
        int end = first;
        while (end < records.length && (tunerWrite(records[end]) || busGate(records[end])
                || tunerRead(records[end]))) {
            end++;
        }
        return new Span(first, end);
    }

    private static boolean tunerWrite(Record record) {
        return record.out && record.request == 3 && record.data.length == 5
                && (record.data[1] & 0xFF) == Tuner.ADDRESS;
    }

    private static boolean busGate(Record record) {
        return record.out && record.request == 3
                && startsWith(record.data, 0x03, 0x80, 0x04, 0x01);
    }

    private static boolean tunerRead(Record record) {
        return !record.out && record.request == 2 && record.value == Tuner.READ_VALUE;
    }

    /** The replayable parts of a retune, around the live calibration. */
    public static final class Plan {

        /** The tune preamble through the {@code 0x23} DataTX write. */
        public final Record[] head;

        /** The demod's AGC and autosearch configuration through the search trigger. */
        public final Record[] body;

        /** The channel block whose words are patched from the TMCC read-back. */
        public final Record[] finalBlock;

        /** The tuner block the live calibration writes, for callers that want it. */
        public final Map<Integer, Integer> pll;

        Plan(Record[] head, Record[] body, Record[] finalBlock, Map<Integer, Integer> pll) {
            this.head = head;
            this.body = body;
            this.finalBlock = finalBlock;
            this.pll = pll;
        }
    }

    /**
     * The replayable parts of a retune for {@code khz}, around the live calibration.
     *
     * <p>{@code head} is replayed, then {@link Tuner#calibrate(int, java.util.Map, boolean)} runs
     * for the frequency with live reads, then {@code body} is replayed through the captured search
     * trigger, and finally the caller patches {@code finalBlock} from the TMCC read-back.</p>
     */
    public static Plan retunePlan(Record[] records, int khz) {
        Split split = splitTune(records);
        if (!split.found()) {
            throw new IllegalArgumentException("capture has no tune phase (start=" + split.start
                    + " restart=" + split.restart + " final=" + split.finalIndex + ")");
        }
        Span calibration = splitCalibration(records, split.start);
        int trigger = splitSearch(records, split.restart);
        return new Plan(
                slice(records, split.start, calibration.first),
                slice(records, calibration.end, trigger + 1),
                slice(records, split.finalIndex, records.length),
                tunerPll(khz));
    }

    // --- channel-block patches ---------------------------------------------------------------

    /** Sets the demod's three layer words (registers 2, 3 and 4) in a block. */
    public static Record[] patchLayerWords(Record[] records, int[] values) {
        if (values.length != 3) {
            throw new IllegalArgumentException("expected three layer words, got " + values.length);
        }
        Record[] patched = new Record[records.length];
        for (int index = 0; index < records.length; index++) {
            Record record = records[index];
            byte[] data = record.data;
            if (record.out && record.request == 3 && data.length == 6
                    && (data[1] & 0xFF) == 0x80 && (data[2] & 0xFF) == 0
                    && (data[3] & 0xFF) >= 2 && (data[3] & 0xFF) <= 4) {
                int value = values[(data[3] & 0xFF) - 2] & 0xFFFF;
                patched[index] = replace(record, value);
            } else {
                patched[index] = record;
            }
        }
        return patched;
    }

    /** Sets the demod words whose registers are the keys of {@code values} in a block. */
    public static Record[] patchWords(Record[] records, Map<Integer, Integer> values) {
        Record[] patched = new Record[records.length];
        for (int index = 0; index < records.length; index++) {
            Record record = records[index];
            byte[] data = record.data;
            if (record.out && record.request == 3 && data.length == 6
                    && (data[1] & 0xFF) == 0x80) {
                int register = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                Integer value = values.get(register);
                if (value != null) {
                    patched[index] = replace(record, value);
                    continue;
                }
            }
            patched[index] = record;
        }
        return patched;
    }

    /** Sets the channel-estimate words (registers 116, 80-89 and 215-218) in a block. */
    public static Record[] patchChannelEstimate(Record[] records, Map<Integer, Integer> values) {
        return patchWords(records, values);
    }

    /**
     * Sets the demod's mode, guard and partial-reception words from the decoded channel.
     *
     * <p>{@code dib8000_set_isdbt_common_channel} writes register 0 ({@code mode << 13}),
     * register 1 (the guard interval) and register 274 (partial reception and sound-broadcasting
     * mode) from the parameters the demod found. Replaying the base capture's words is only
     * correct while the target uses the same guard interval and the same partial reception
     * (measured: CH14's 1/8 guard and a partial-0 mux decoded a TMCC and then produced no
     * packets). The four captures confirm the mapping (599 has guard 2 in both its TMCC and its
     * final block).</p>
     *
     * <p>{@code mode} is the demod's own register encoding, <b>1 = 2K, 2 = 4K, 3 = 8K</b>, not
     * the canonical 0/1/2 of {@link Tmcc}; {@link #patchChannelConfig(Record[], Tmcc)} does that
     * translation.</p>
     */
    public static Record[] patchChannelConfig(Record[] records, int mode, int guard, int partial,
            int sb) {
        int modeWord = mode & 0x03;
        int guardWord = guard & 0x03;
        int partialWord = partial & 0x01;
        int sbWord = sb & 0x01;
        Record[] patched = new Record[records.length];
        for (int index = 0; index < records.length; index++) {
            Record record = records[index];
            byte[] data = record.data;
            if (record.out && record.request == 3 && data.length == 6
                    && (data[1] & 0xFF) == 0x80) {
                int register = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                int value = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
                if (register == 0) {
                    value = (value & 0x1FFF) | (modeWord << 13);
                } else if (register == 1) {
                    value = (value & 0xFFFC) | guardWord;
                } else if (register == 274) {
                    value = (value & 0xFFCF) | (partialWord << 5) | (sbWord << 4);
                } else {
                    patched[index] = record;
                    continue;
                }
                patched[index] = replace(record, value);
                continue;
            }
            patched[index] = record;
        }
        return patched;
    }

    /** The same patch from a decoded TMCC: canonical mode back to the register's 1/2/3. */
    public static Record[] patchChannelConfig(Record[] records, Tmcc decoded) {
        return patchChannelConfig(records, demodMode(decoded), valueOrZero(decoded.guard),
                valueOrZero(decoded.partial), valueOrZero(decoded.sb));
    }

    /** The mode/guard/partial-reception writes of a block, patched for one candidate. */
    public static Record[] channelConfigRecords(Record[] records, Combo combo) {
        Record[] patched = patchChannelConfig(records, combo.mode, combo.guard, combo.partial,
                combo.sb);
        List<Record> kept = new ArrayList<>();
        for (Record record : patched) {
            if (isChannelConfig(record)) kept.add(record);
        }
        return kept.toArray(new Record[0]);
    }

    /** The mode/guard/partial-reception writes of a block, patched for a decoded channel. */
    public static Record[] channelConfigRecords(Record[] records, Tmcc decoded) {
        Record[] patched = patchChannelConfig(records, decoded);
        List<Record> kept = new ArrayList<>();
        for (Record record : patched) {
            if (isChannelConfig(record)) kept.add(record);
        }
        return kept.toArray(new Record[0]);
    }

    private static boolean isChannelConfig(Record record) {
        byte[] data = record.data;
        if (!record.out || record.request != 3 || data.length != 6 || (data[1] & 0xFF) != 0x80) {
            return false;
        }
        int register = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        return register == 0 || register == 1 || register == 274;
    }

    // --- TMCC-derived channel words ----------------------------------------------------------

    /** True when the TMCC block describes a real ISDB-T channel (1..13 segments in total). */
    public static boolean plausible(Tmcc decoded) {
        if (decoded == null || decoded.layers == null || decoded.layers.length != 3) return false;
        int total = 0;
        for (Tmcc.Layer layer : decoded.layers) {
            if (layer.segments < 0 || layer.segments > 13) return false;
            total += layer.segments;
        }
        return total >= 1 && total <= 13;
    }

    /**
     * True when two decoded TMCCs describe the same mux: canonical mode, guard interval, partial
     * reception and each layer's segments/modulation/code rate/interleaving.
     *
     * <p>This is the zap cache's staleness test ({@code same_channel} in {@code dib0700/tune.py}):
     * a cached channel block is only replayed without the tune procedure while the demod still
     * reports the mux it was built from. The EWBS flag, inversion and the diagnostic sb word are
     * not part of the mux identity.</p>
     */
    public static boolean sameChannel(Tmcc left, Tmcc right) {
        if (left == null || right == null) return false;
        if (!left.valid() || !right.valid()) return false;
        if (left.mode != right.mode || left.guard != right.guard
                || left.partial != right.partial) {
            return false;
        }
        for (int index = 0; index < left.layers.length; index++) {
            Tmcc.Layer a = left.layers[index];
            Tmcc.Layer b = right.layers[index];
            if (a.segments != b.segments || a.modulation != b.modulation
                    || a.interleave != b.interleave
                    || !java.util.Objects.equals(a.codeRate, b.codeRate)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Registers 2/3/4 for the decoded channel, exactly the words
     * {@code dib8000_set_isdbt_common_channel} writes: {@code (modulation << 10) |
     * (segments << 6) | (fec << 3) | interleaving}.
     *
     * <p>The code-rate field is reconstructed from the 1-based label: 1/2 = 1, 2/3 = 2, 3/4 = 3,
     * 5/6 = 5 and anything else 7/8 = 7, which is the kernel's {@code FEC_INDEX} and the Python
     * port's default for an unread register (a layer's segments are what {@link #plausible}
     * requires).</p>
     */
    public static int[] layerWords(Tmcc decoded) {
        int[] words = new int[3];
        for (int index = 0; index < 3; index++) {
            Tmcc.Layer layer = decoded.layers[index];
            words[index] = ((valueOrZero(layer.modulation) & 0x03) << 10)
                    | ((valueOrZero(layer.segments) & 0x0F) << 6)
                    | (codeRateField(layer) << 3)
                    | (valueOrZero(layer.interleave) & 0x03);
        }
        return words;
    }

    private static int codeRateField(Tmcc.Layer layer) {
        String rate = layer.codeRate;
        if ("1/2".equals(rate)) return 1;
        if ("2/3".equals(rate)) return 2;
        if ("3/4".equals(rate)) return 3;
        if ("5/6".equals(rate)) return 5;
        return 7;
    }

    // dib8000_adp_fine_tune / dib8000_update_ana_gain: the final block's channel-estimate words
    // follow the constellation the demod reported, not the base capture's channel. Replaying the
    // base's values on a mux with a different highest constellation leaves the estimator tuned
    // for the wrong modulation (measured: 497 -> 479 lost whole packets after RS, no TEI).
    private static final int[] ADC_TARGET_16DB = {
            7250, 7238, 7264, 7309, 7338, 7382, 7427, 7456, 7500, 7544};
    private static final int[] ADP_Q64 = {0x0148, 0xFFF0, 0x00A4, 0xFFF8};
    private static final int[] ADP_Q16 = {0x023D, 0xFFDF, 0x00A4, 0xFFF0};
    private static final int[] ADP_QDEFAULT = {0x099A, 0xFFAE, 0x0333, 0xFFF8};
    private static final int ANA_GAIN_ZERO_FE_OFFSET = 355;

    /**
     * Registers 116, 80-89 and 215-218 for the decoded channel.
     *
     * <p>{@code dib8000_adp_fine_tune} picks the adaptive-noise row from the highest constellation
     * of a layer that carries segments, and returns {@code ana_gain} 7 for QAM16/QAM64 and 0
     * otherwise; {@code dib8000_update_ana_gain} writes it and the analog-front-end targets (355
     * lower when {@code ana_gain} is 0). DQPSK and QPSK both take the default row.</p>
     */
    public static Map<Integer, Integer> channelEstimateWords(Tmcc decoded) {
        int constellation = 0;
        for (Tmcc.Layer layer : decoded.layers) {
            if (layer.segments > 0 && layer.modulation > constellation) {
                constellation = layer.modulation;
            }
        }
        int anaGain;
        int[] adp;
        if (constellation == 3) {           // QAM64
            anaGain = 7;
            adp = ADP_Q64;
        } else if (constellation == 2) {    // QAM16
            anaGain = 7;
            adp = ADP_Q16;
        } else {                            // DQPSK / QPSK
            anaGain = 0;
            adp = ADP_QDEFAULT;
        }
        Map<Integer, Integer> words = new LinkedHashMap<>();
        words.put(116, anaGain);
        for (int index = 0; index < ADC_TARGET_16DB.length; index++) {
            words.put(80 + index,
                    anaGain != 0 ? ADC_TARGET_16DB[index]
                            : ADC_TARGET_16DB[index] - ANA_GAIN_ZERO_FE_OFFSET);
        }
        for (int index = 0; index < adp.length; index++) words.put(215 + index, adp[index]);
        return words;
    }

    // dib8000_set_isdbt_common_channel's layer-derived words. The three layer words are not the
    // whole channel block: the differential-segment map, the segment-inhibit masks, the
    // channel-estimation window edges, the small-block words and the TMCC threshold all follow
    // the TMCC's per-layer segment counts and modulations. A final block replayed from a 1+12
    // capture carries that capture's values; on a 1+8+4 mux the demod locks, decodes the TMCC and
    // then streams nothing (measured 2026-11-20).
    private static final int[] PERMU_SEG = {6, 5, 7, 4, 8, 3, 9, 2, 10, 1, 11, 0, 12};
    private static final int SEG_MASK_13SEG = 0x1FFF;
    private static final int MOD_DQPSK = 0;

    /**
     * The differential-segment map {@code dib8000_set_isdbt_common_channel} builds from the layers.
     *
     * <p>A DQPSK layer is demodulated differentially and the demod needs to know which FFT
     * segments those are. ARIB STD-B31 3.15.6.4 numbers the segments from the centre outward
     * (segment 0 is the centre and the partial-reception segment) and allocates the layers in
     * order. {@code permu_seg} is the kernel's segment-number to FFT-segment map. The kernel's own
     * walk assumes the differential segments are the innermost ones, which is true only for a
     * differential layer A; following each layer's own segment numbers is the same map for A or B
     * and keeps the four captured 1+12 blocks byte-identical (none of their layers is
     * differential).</p>
     */
    public static int segDiffMask(Tmcc decoded) {
        if (decoded == null || decoded.layers == null || decoded.layers.length != 3) return 0;
        int mask = 0;
        int start = 0;
        for (Tmcc.Layer layer : decoded.layers) {
            if (layer.segments < 0) return 0;      // an unread segment count: no map to build
            int count = layer.segments & 0x0F;
            if (layer.modulation == MOD_DQPSK) {
                for (int number = start; number < Math.min(start + count, PERMU_SEG.length);
                        number++) {
                    mask |= 1 << PERMU_SEG[number];
                }
            }
            start += count;
        }
        return mask;
    }

    /**
     * The channel block's stream-side words that follow the decoded layer table.
     *
     * <p>{@code dib8000_set_isdbt_common_channel} derives these from the TMCC, not from the
     * frequency: register 0 ({@code (mode << 13) | seg_diff_mask}), 268 (the equaliser control
     * word), 222/223 (the channel-estimate window edges), 189/192/225/288 (segment-inhibit
     * masks), 211 ({@code P_des_seg_enabled}), 266, 352/353 and 290-292 (the TMCC decoding
     * threshold). Register 0 also carries the mode, so a caller applies this after
     * {@link #patchChannelConfig(Record[], Tmcc)}.</p>
     *
     * <p>A sound-broadcasting channel takes {@code dib8000_set_sb_channel}'s own block, which is
     * not derived here: the returned map is empty so the base block is left alone rather than
     * half-configured.</p>
     */
    public static Map<Integer, Integer> streamConfigWords(Tmcc decoded) {
        Map<Integer, Integer> words = new LinkedHashMap<>();
        if (decoded == null || decoded.layers == null || decoded.layers.length != 3) return words;
        if (valueOrZero(decoded.sb) != 0) return words;
        int mode = demodMode(decoded);
        int mask = segDiffMask(decoded);
        int active = SEG_MASK_13SEG & ~mask;
        words.put(0, (mode << 13) | mask);
        // dib8000_defaults: reg 268 = (2 << 9) | 39; a differential layer sets bit 9 and clears 10.
        if (mask != 0) {
            words.put(268, (((2 << 9) | 39) & 0xF9FF) | 0x0200);
        } else {
            words.put(268, (2 << 9) | 39);
        }
        int left = 0;
        int right = 0;
        for (int index = 0; index < 13; index++) {
            if (((mask >> index) & 1) != 0) continue;
            if (index == 0 || ((active >> (index - 1)) & 1) == 0) left += 1 << index;
            if (index == 12 || ((active >> (index + 1)) & 1) == 0) right += 1 << index;
        }
        words.put(222, left);
        words.put(223, right);
        int inactive = (~SEG_MASK_13SEG | mask) & 0xFFFF;
        words.put(189, inactive);
        words.put(192, inactive);
        words.put(225, inactive);
        words.put(288, inactive & 0x1FFF);
        words.put(211, active);
        words.put(266, (inactive | 0x40) & 0xFFFF);
        words.put(352, mask);
        words.put(353, SEG_MASK_13SEG);
        int tmccPower = 0;
        for (Tmcc.Layer layer : decoded.layers) {
            int weight = 1 + (layer.modulation == MOD_DQPSK ? 4 : 0);
            tmccPower += weight * Math.max(layer.segments, 0);
        }
        tmccPower *= 1 << 7;                    // the register is a quarter of the maximum
        words.put(290, tmccPower & 0xFFFF);
        words.put(291, tmccPower & 0xFFFF);
        words.put(292, tmccPower & 0xFFFF);
        return words;
    }

    // --- mode/guard acquisition --------------------------------------------------------------

    private static final int[] ACQUISITION_MODES = {1, 2, 3};      // 2K, 4K, 8K as the register
    private static final int[] ACQUISITION_GUARDS = {0, 1, 2, 3};  // 1/32, 1/16, 1/8, 1/4

    /** One mode x guard acquisition candidate: the demod's own encoding (1 = 2K ... 3 = 8K). */
    public static final class Combo {

        /** Demod mode: 1 = 2K, 2 = 4K, 3 = 8K. */
        public final int mode;

        /** 0 = 1/32, 1 = 1/16, 2 = 1/8, 3 = 1/4. */
        public final int guard;

        /** The partial-reception bit the search assumes (the autosearch forces it on). */
        public final int partial;

        /** The sound-broadcasting bit (0 during acquisition). */
        public final int sb;

        Combo(int mode, int guard, int partial, int sb) {
            this.mode = mode;
            this.guard = guard;
            this.partial = partial;
            this.sb = sb;
        }

        /** {@code "8K 1/16"}: the log and record form. */
        public String text() {
            return Tmcc.modeLabel(mode - 1) + " " + Tmcc.guardLabel(guard);
        }

        @Override
        public String toString() {
            return text();
        }
    }

    /**
     * The twelve mode x guard candidates, in the order the sweep tries them.
     *
     * <p>The demod's own autosearch forces partial reception on while searching, and the TMCC read
     * back after a lock carries the real value.</p>
     */
    public static List<Combo> acquisitionCombos() {
        return acquisitionCombos(1);
    }

    /** The twelve candidates with an explicit partial-reception assumption. */
    public static List<Combo> acquisitionCombos(int partial) {
        List<Combo> combos = new ArrayList<>();
        for (int mode : ACQUISITION_MODES) {
            for (int guard : ACQUISITION_GUARDS) {
                combos.add(new Combo(mode, guard, partial & 0x01, 0));
            }
        }
        return combos;
    }

    // --- helpers -----------------------------------------------------------------------------

    /** Canonical {@link Tmcc} mode back to the demod's register encoding (1/2/3, 8K fallback). */
    private static int demodMode(Tmcc decoded) {
        if (decoded == null || decoded.mode == Tmcc.UNKNOWN) return 3;
        return decoded.mode + 1;
    }

    private static int valueOrZero(int value) {
        return value == Tmcc.UNKNOWN ? 0 : value;
    }

    private static Record replace(Record record, int value) {
        byte[] data = record.data.clone();
        data[4] = (byte) ((value >> 8) & 0xFF);
        data[5] = (byte) (value & 0xFF);
        return new Record(record.out, record.request, record.value, record.index, record.length,
                data, record.t);
    }

    private static Record[] slice(Record[] records, int from, int to) {
        int start = Math.max(0, Math.min(from, records.length));
        int end = Math.max(start, Math.min(to, records.length));
        Record[] out = new Record[end - start];
        System.arraycopy(records, start, out, 0, out.length);
        return out;
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if ((data[index] & 0xFF) != prefix[index]) return false;
        }
        return true;
    }

    /** Lower-case hex, the session's wire form; used to recognise the captured landmarks. */
    static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte value : data) {
            out.append(Character.forDigit((value >> 4) & 0xF, 16));
            out.append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString();
    }
}

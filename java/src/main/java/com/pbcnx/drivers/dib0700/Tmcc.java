/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 The DIB0700 Open Driver Authors.
 *
 * Our own code, no vendor code.
 */

package com.pbcnx.drivers.dib0700;

import java.util.Map;

/**
 * The ISDB-T channel the demod reports: mode, guard interval, partial reception, inversion, the
 * demod's "sb" word and the EWBS activation flag, plus the three layers' segment count,
 * modulation, code rate and time interleaving.
 *
 * <p>This is {@code decode_tmcc} from the Python driver ({@code dib0700/tune.py}) ported field for
 * field. Register 570 (or 572 on the 0x8090 revision when 570's mode bits are clear) carries the
 * mode/guard/inversion, 505 partial, 508 sb, 472 the EWBS flag (0 off, {@code 0x0F} on, measured)
 * and 481+i / 487+i / 493+i / 499+i the layer block. The decode works before lock: the demod gets
 * the TMCC to demodulate at all.</p>
 *
 * <p><b>Mode is canonical here: {@link #MODE_2K 0 = 2K}, {@link #MODE_4K 1 = 4K},
 * {@link #MODE_8K 2 = 8K}.</b> The demod register is 1-based (1/2/3), so {@link #decode}
 * translates it - the shared {@code Tmcc} record's meaning, pinned by the canonical note sent to
 * The host app (2026-11-19). Feeding the register value through would label an 8K mux "4K".</p>
 *
 * <p>A register the demod did not answer stays {@link #UNKNOWN}, never a zero-decoded field: the
 * Python decode defaults a missing word to 0, which would make a failed read look like "2K, 1/32"
 * (or, through the register's 0 = "not reported" fallback, 8K). The host shows "not reported"
 * instead. The code-rate label is the demod's <b>1-based</b> table (1 = 1/2, 2 = 2/3, 3 = 3/4,
 * 5 = 5/6, anything else 7/8); interleave index 3 is the label <b>I4</b> (STD-B31 Table 3-30).</p>
 */
public final class Tmcc {

    /** The explicit unknown sentinel: the demod did not report this field. */
    public static final int UNKNOWN = -1;

    /** The unknown code-rate label, for the same reason as {@link #UNKNOWN}. */
    public static final String UNKNOWN_CODE_RATE = "?";

    /** Canonical transmission modes: the host's encoding, not the register's. */
    public static final int MODE_2K = 0;
    public static final int MODE_4K = 1;
    public static final int MODE_8K = 2;

    private static final String UNKNOWN_LABEL = "?";
    private static final String[] MODE_LABELS = {"2K", "4K", "8K"};
    private static final String[] GUARD_LABELS = {"1/32", "1/16", "1/8", "1/4"};
    private static final String[] MODULATION_LABELS = {"DQPSK", "QPSK", "16QAM", "64QAM"};
    // Index = the demod's 1-based code-rate field (481+i & 0x07). 0/4/6/7 are all 7/8, exactly
    // like dib8000_get_frontend; the layer word keeps the raw value, only this label is derived.
    private static final String[] CODE_RATE_LABELS = {
            "7/8", "1/2", "2/3", "3/4", "7/8", "5/6", "7/8", "7/8"};

    /** One hierarchical layer: L0, L1, L2 in the array's order. */
    public static final class Layer {

        /** 0..13 segments; 0 means the layer is unused (the fields are still as coded). */
        public final int segments;

        /** 0 = DQPSK, 1 = QPSK, 2 = 16QAM, 3 = 64QAM, {@link Tmcc#UNKNOWN} when unread. */
        public final int modulation;

        /** The code-rate label ({@code "1/2"} ... {@code "7/8"}), or {@link Tmcc#UNKNOWN_CODE_RATE}. */
        public final String codeRate;

        /** The time-interleave index 0..3 (index 3 is the label I4), or {@link Tmcc#UNKNOWN}. */
        public final int interleave;

        private Layer(int segments, int modulation, String codeRate, int interleave) {
            this.segments = segments;
            this.modulation = modulation;
            this.codeRate = codeRate;
            this.interleave = interleave;
        }

        /** True when this layer carries data: 1 to 13 segments. */
        public boolean isUsed() {
            return segments > 0 && segments <= 13;
        }

        /**
         * The code rate in the host's canonical numbering (0 = 1/2, 1 = 2/3, 2 = 3/4, 3 = 5/6,
         * 4 = 7/8), or {@link #UNKNOWN}. The host record's {@code Layer.codeRate} is this value;
         * this record keeps the label and offers the index for that mapping.
         */
        public int codeRateIndex() {
            switch (codeRate) {
                case "1/2": return 0;
                case "2/3": return 1;
                case "3/4": return 2;
                case "5/6": return 3;
                case "7/8": return 4;
                default: return UNKNOWN;
            }
        }

        /** {@code "QPSK 1seg code 7/8 I2"} - the layer as the Python CLI prints it. */
        public String text() {
            return modulationLabel(modulation) + " " + number(segments) + "seg code "
                    + (codeRate == null ? UNKNOWN_CODE_RATE : codeRate) + " "
                    + interleaveLabel(interleave);
        }
    }

    /** Canonical mode: 0 = 2K, 1 = 4K, 2 = 8K; {@link #UNKNOWN} when the demod did not say. */
    public final int mode;

    /** Guard interval: 0 = 1/32, 1 = 1/16, 2 = 1/8, 3 = 1/4; {@link #UNKNOWN} when unread. */
    public final int guard;

    /** Partial-reception flag (one-seg), 0/1; {@link #UNKNOWN} when unread. */
    public final int partial;

    /** Spectrum inversion, 0/1 (diagnostic); {@link #UNKNOWN} when unread. */
    public final int inversion;

    /** The demod's "sb" word, 0/1 (diagnostic); {@link #UNKNOWN} when unread. */
    public final int sb;

    /** EWBS activation flag from register 472: 0 off, 1 on; {@link #UNKNOWN} when unread. */
    public final int ewbs;

    /** L0, L1 and L2, always in that order. */
    public final Layer[] layers;

    private Tmcc(int mode, int guard, int partial, int inversion, int sb, int ewbs, Layer[] layers) {
        this.mode = mode;
        this.guard = guard;
        this.partial = partial;
        this.inversion = inversion;
        this.sb = sb;
        this.ewbs = ewbs;
        this.layers = layers;
    }

    /**
     * Decodes the demod's register map, exactly like {@code decode_tmcc} in {@code dib0700/tune.py}.
     * Registers absent from {@code words} (or null) are not decoded as zero: the field they carry
     * stays {@link #UNKNOWN}.
     */
    public static Tmcc decode(Map<Integer, Integer> words) {
        Integer word570 = words.get(570);
        Integer word572 = words.get(572);
        int value = word570 == null ? UNKNOWN : word570;
        int revision = word572 == null ? UNKNOWN : word572;
        // The 0x8090 revision moved the mode/guard word to 572; 570's clear mode bits identify it.
        if (value != UNKNOWN && (value & 0x30) == 0
                && revision != UNKNOWN && (revision & 0x30) != 0) {
            value = revision;
        }

        int mode = UNKNOWN;
        if (value != UNKNOWN) {
            int demodMode = (value >> 4) & 0x03;
            if (demodMode == 0) demodMode = 3;   // "not reported": 8K is the safe reading
            mode = canonicalMode(demodMode);
        }

        Layer[] layers = new Layer[3];
        for (int i = 0; i < layers.length; i++) {
            Integer modulation = words.get(487 + i);
            Integer segments = words.get(493 + i);
            Integer codeRate = words.get(481 + i);
            Integer interleave = words.get(499 + i);
            layers[i] = new Layer(
                    segments == null ? UNKNOWN : segments & 0x0F,
                    modulation == null ? UNKNOWN : modulation & 0x03,
                    codeRate == null ? UNKNOWN_CODE_RATE : codeRateLabel(codeRate),
                    interleave == null ? UNKNOWN : interleave & 0x03);
        }

        return new Tmcc(
                mode,
                value == UNKNOWN ? UNKNOWN : value & 0x03,
                bit(words.get(505)),
                value == UNKNOWN ? UNKNOWN : (value >> 6) & 0x01,
                bit(words.get(508)),
                flag(words.get(472)),
                layers);
    }

    /** Translates the demod's 1-based mode (1 = 2K, 2 = 4K, 3 = 8K) into the canonical value. */
    public static int canonicalMode(int demodMode) {
        switch (demodMode) {
            case 1: return MODE_2K;
            case 2: return MODE_4K;
            case 3: return MODE_8K;
            default: return UNKNOWN;
        }
    }

    private static int bit(Integer word) {
        return word == null ? UNKNOWN : word & 0x01;
    }

    /** EWBS is the word's truthiness, not bit 0: 0 is off, {@code 0x0F} is on (measured). */
    private static int flag(Integer word) {
        if (word == null) return UNKNOWN;
        return word != 0 ? 1 : 0;
    }

    /** The label for the demod's 1-based code-rate field; 1/2, 2/3, 3/4, 5/6, else 7/8. */
    public static String codeRateLabel(int raw) {
        if (raw < 0) return UNKNOWN_CODE_RATE;
        return CODE_RATE_LABELS[raw & 0x07];
    }

    /** The canonical mode's label: 0 -> 2K, 1 -> 4K, 2 -> 8K. */
    public static String modeLabel(int mode) {
        return mode >= 0 && mode < MODE_LABELS.length ? MODE_LABELS[mode] : UNKNOWN_LABEL;
    }

    /** The guard interval's label: 0 -> 1/32, 1 -> 1/16, 2 -> 1/8, 3 -> 1/4. */
    public static String guardLabel(int guard) {
        return guard >= 0 && guard < GUARD_LABELS.length ? GUARD_LABELS[guard] : UNKNOWN_LABEL;
    }

    /** The modulation's label: 0 -> DQPSK, 1 -> QPSK, 2 -> 16QAM, 3 -> 64QAM. */
    public static String modulationLabel(int modulation) {
        return modulation >= 0 && modulation < MODULATION_LABELS.length
                ? MODULATION_LABELS[modulation] : UNKNOWN_LABEL;
    }

    /** The interleave index's label: 0 -> I0, 1 -> I1, 2 -> I2, 3 -> I4 (STD-B31 Table 3-30). */
    public static String interleaveLabel(int index) {
        switch (index) {
            case 0: return "I0";
            case 1: return "I1";
            case 2: return "I2";
            case 3: return "I4";
            default: return UNKNOWN_LABEL;
        }
    }

    private static String number(int value) {
        return value == UNKNOWN ? UNKNOWN_LABEL : Integer.toString(value);
    }

    /**
     * A reading is a channel only when it carries at least one layer segment (IT9175's rule,
     * 2026-11-20): an undecoded register reads {@code 0x0000}, which decodes to a legitimate-looking
     * 2K / 1/32 with no layers.  Gate the display on this, not on "the read succeeded".
     */
    public boolean valid() {
        if (mode == UNKNOWN || guard == UNKNOWN) return false;
        int total = 0;
        for (Layer layer : layers) {
            if (layer != null && layer.segments >= 0) total += layer.segments;
        }
        return total >= 1 && total <= 13;
    }

    /**
     * One line in the shape the Python CLI prints: mode, guard, partial, inversion, sb, ewbs and
     * each layer; {@code ?} wherever the demod did not report a field.
     */
    public String text() {
        StringBuilder line = new StringBuilder();
        line.append("mode ").append(modeLabel(mode))
                .append(" guard ").append(guardLabel(guard))
                .append(" partial ").append(number(partial))
                .append(" inversion ").append(number(inversion))
                .append(" sb ").append(number(sb))
                .append(" ewbs ").append(number(ewbs));
        for (int i = 0; i < layers.length; i++) {
            line.append(" L").append(i).append(':').append(layers[i].text());
        }
        return line.toString();
    }

    @Override
    public String toString() {
        return text();
    }
}

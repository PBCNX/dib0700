# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (c) 2026 The DIB0700 Open Driver Authors.
#
# Our own code, no vendor code.

"""Captured sessions, tunes keyed by frequency, and the parameterised tune.

``dib0700/resources/session-<khz>.json`` is the whole cold session for one frequency (probe, power-up
and tune, captured end-to-end), and ``dib0700/resources/tune-<khz>.json`` is a tune-only capture for the
warm path.

A captured frequency is still replayed whole. Any other frequency uses :func:`retune_plan`: the
tune phase of the nearest capture is replayed with the tuner PLL programmed from the kernel's own
formula (``dib0090_set_params``, reimplemented here from GPL-2.0-only sources as a map only), and
the demod's channel block is regenerated from the TMCC registers the demod itself reports.  That
last part matters: the layer words are *learned*, not tabulated - the 2026-11-19 control experiment
showed a transcript with swapped tune values does not retune, because the tune is a state machine
(write, read, branch, wait), not a value table.

The dib0090 calibration is a read-driven state machine, so it is generated, not replayed: the
device branches on the ADC value it returns, and one captured branch is not the branch another
frequency takes.  :func:`tuner_calibrate` ports the kernel's order (DC offset, WBD, temperature,
PLL, captrim, gain/baseband) with live reads, and :func:`retune_plan` splits the captured tune
into the preamble before it, the live calibration and the demod setup after it - including the
captured ``0x0000 = 0x6000`` search trigger, without which the autosearch never starts.  The
procedure is validated offline against every capture: with the capture's own answers, the live
code reproduces its tuner writes byte for byte.

Frequencies outside the tuner's UHF table (below CH14 or above the last PLL row) are refused rather
than guessed.
"""
import json
import os
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESOURCES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "resources")

DEFAULT_KHZ = 599143

# A ``dvbv5-zap -x`` capture keeps the app's close: 0x0506 = 0x0540 switches the MPEG output on,
# and 1.2 s later 0x0506 = 0x0000 switches it off. Replaying the close leaves the stream silent
# even with the demod locked, so the replay stops right after the enable. The captures are not
# rewritten; the trim happens at load time, and only when a close follows the enable (the
# 479.143 and 599.143 captures are already cut there).
OUTPUT_ENABLE = "038005060540"
OUTPUT_OFF = "038005060000"

# Frequencies with a captured session.  ``nearest`` picks the base state for the parameterised
# tune; the tuning procedure itself comes from the nearest one and is re-run for the target.
CAPTURED = (479143, 485143, 497143, 599143)

# --- tuner PLL: dib0090_set_params ------------------------------------------------------------
# The STK8096GP tuner config (dib0700_devices.c, dib809x_dib0090_config): 12 MHz clock divided by
# 6, UHF offset -63 kHz, integer loop filter disabled.  The demod's own PLL is untouched.
UHF_OFFSET_KHZ = -63
FREF_KHZ = 2000                      # io.clock_khz 12000 / fref_clock_ratio 6
LPF_KHZ = 100                        # loop-filter discrimination step in dib0090_set_params
REST_FULL = 6528                     # 0x1980: what the measured captures write for *.143 MHz
MIN_KHZ = 473143                     # CH14, 473+1/7 MHz: the PH band plan's first channel
# (max rf kHz, lo5 with a fractional rest, lo5 with an exact PLL, hfdiv_code)
# dib0090_pll_table's UHF rows.  The four captures pin the first two rows: 479/485/497 read 0x1c9e,
# 599 switches to 0x141d.  The vendor driver's own copy of the table (mod7700.sys, read for reference
# only) has the identical 580000 / 700000 / 860000 rows with the same lo5 constants, so the table is
# carried whole instead of stopping at 700 MHz.  The 520/550/650/750/850/900 numbers are the *LNA
# tuning* rows (dib0090_tuning_table), not the PLL: UHF_LNA_TUNE carries those.
VCO_BANDS = (
    (580000, 0x049E, 0x049F, 3),
    (700000, 0x041D, 0x041F, 2),
    (860000, 0x049E, 0x049F, 2),
)
# Highest channel the PH plan defines: CH51 = 695+1/7 MHz.  tuner_pll covers the whole UHF table;
# the band plan's edge is the one this driver is expected to tune.
MAX_PH_KHZ = 695143


def trim_at_output_enable(records):
    """Drop the app's close from a capture: keep the records through the first output enable.

    A capture without a close after its enable is returned unchanged.
    """
    enabled = None
    for index, record in enumerate(records):
        data = record.get("data") or ""
        if record.get("request") != 0x03:
            continue
        if enabled is None:
            if data.startswith(OUTPUT_ENABLE):
                enabled = index
        elif data.startswith(OUTPUT_OFF):
            return records[:enabled + 1]
    return records


def resource(name):
    return os.path.join(RESOURCES, name)


def session_path(khz=None):
    khz = DEFAULT_KHZ if khz is None else int(khz)
    return resource("session-%d.json" % khz)


def tune_path(khz=None):
    khz = DEFAULT_KHZ if khz is None else int(khz)
    return resource("tune-%d.json" % khz)


def has_session(khz):
    return os.path.exists(session_path(khz))


def load_session(khz=None):
    """The whole captured session for this frequency, or [] when there is no capture."""
    path = session_path(khz)
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def load(khz=None):
    """The captured tune-only records for this frequency, or [] when there is no capture."""
    path = tune_path(khz)
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def nearest(khz):
    """The captured frequency whose demod state is the closest base for ``khz``."""
    return base_candidates(khz)[0]


def base_candidates(khz):
    """Every captured frequency as a base, nearest first.

    The nearest capture is not always a usable base: the 599.143 capture is the cable-test session
    and does not lock off air, so a tune above ~550 MHz has to fall back to a live capture.  Callers
    walk this list until one locks.
    """
    khz = int(khz)
    return sorted(CAPTURED, key=lambda known: (abs(known - khz), known))


def tuner_pll(khz):
    """The tuner writes that move the LO to ``khz``, from the dib0090 formula.

    Returns ``{register: value}`` for the 0xC0 tuner writes.  This is the part of the tune that is
    genuinely arithmetic: FBDiv follows the requested frequency, the rest of the PLL block follows
    the VCO band.  The fractional-rest handling is the kernel's (LPF discriminators, 6528 full
    scale), which is why the four ``*.143`` captures all show ``0x1980``.

    The band is selected on the RF request (``khz`` plus the UHF offset), the same quantity the
    kernel compares with ``pll->max_freq``, not on the channel centre.
    """
    khz = int(khz)
    rf = khz + UHF_OFFSET_KHZ
    if khz < MIN_KHZ or rf > VCO_BANDS[-1][0]:
        raise ValueError("%d kHz is outside the UHF band (%d-%d kHz)"
                         % (khz, MIN_KHZ, VCO_BANDS[-1][0] - UHF_OFFSET_KHZ))
    band = None
    for limit, fractional, exact, code in VCO_BANDS:
        if rf <= limit:
            band = (fractional, exact, code)
            break
    if band is None:
        raise ValueError("%d kHz is outside the measured tuner band (below %d kHz)"
                         % (khz, VCO_BANDS[-1][0]))
    vcof = 2 * rf
    fbdiv = vcof // FREF_KHZ
    rest = vcof - fbdiv * FREF_KHZ
    if rest < LPF_KHZ:
        rest = 0
    elif rest < 2 * LPF_KHZ:
        rest = 2 * LPF_KHZ
    elif rest > FREF_KHZ - LPF_KHZ:
        rest = 0
        fbdiv += 1
    elif rest > FREF_KHZ - 2 * LPF_KHZ:
        rest = FREF_KHZ - 2 * LPF_KHZ
    rest = rest * REST_FULL // (FREF_KHZ // 10)
    fractional, exact, code = band
    return {
        0x0B: 0xB802,                                   # analog switch trim
        0x15: fbdiv,                                    # FBDiv: the frequency word
        0x16: (0xFF if rest else 0x01) << 8 | 0x06,     # Den + fref_clock_ratio
        0x17: rest,                                     # fractional rest
        0x19: (fractional if rest else exact) | (code << 11),
        0x1C: 0xFF2E if rest else 0xFFF8,               # loop filter
        0x24: 0x228F,                                   # EN_LO | tuner enable
    }


def lna_tune(khz):
    """The UHF LNA tuning code for ``khz`` (dib0090_tuning_table's banded rows)."""
    khz = int(khz)
    for limit, code in UHF_LNA_TUNE:
        if khz <= limit:
            return code
    raise ValueError("%d kHz is outside the measured tuner band (above %d kHz)"
                     % (khz, UHF_LNA_TUNE[-1][0]))


def split_tune(records):
    """Locate the three parts of a captured tune: ``(start, restart, final)``.

    ``start`` is the first tuner register 0x23 write (the tune phase; the power-up also writes
    0x0b, so a raw 0x0b search would land in the init), ``restart`` is P_restart_search = 1
    (0x0000 = 0xe000), and ``final`` is the first re-write of P_corm_alpha (0x0020 = 0x6680) after
    the restart - the demod's final channel block, which carries the layer words learned from TMCC.
    """
    start = restart = final = None
    for index, record in enumerate(records):
        data = record.get("data") or ""
        if record.get("dir") != "out" or record.get("request") != 3:
            continue
        if (start is None and len(data) == 10 and data[2:4] == "c0"
                and data[4:6] == "23" and data[6:10] == "4a30"):
            # the tune phase switches the tuner's DataTX off before calibrating; the power-up
            # writes 0x23 = 0x0a30 instead, so the value tells the two phases apart
            start = index
        elif len(data) == 12 and data.startswith("03800000") and data[8:12] == "e000":
            restart = index
        elif (restart is not None and final is None and len(data) == 12
              and data.startswith("03800020") and data[8:12] == "6680"):
            final = index
    return start, restart, final


SEARCH_RESTART = "03800000e000"
SEARCH_TRIGGER = "038000006000"


def split_search(records, restart):
    """The captured autosearch trigger after P_restart_search: its index, or ``restart``.

    The tune phase writes ``0x0000 = 0xe000`` (P_restart_search), reads the IRQ register and then
    writes ``0x0000 = 0x6000``: that last write is what actually starts the search.  Measured on
    hardware: replaying only through 0xe000 leaves register 0x0504 at 0 and no search ever runs,
    which is why the old plan could not lock even at the base frequency.
    """
    for index in range(restart + 1, len(records)):
        record = records[index]
        if (record.get("dir") == "out" and record.get("request") == 3
                and (record.get("data") or "") == SEARCH_TRIGGER):
            return index
    return restart


# --- dib0090 calibration: the read-driven state machine, reimplemented from the kernel map ------
# The device branches on the slow ADC it returns (register 0x1d), so one tune's calibration
# writes cannot be replayed for another frequency: the captured branch is not the branch the
# device would take.  This section is the procedure, in the kernel's order (``dib0090_tune``):
# DC offset -> WBD -> temperature -> PLL -> captrim -> post-captrim gain/baseband.  Register
# numbers and constants come from the GPL-2.0-only map; the code style is this tree's.

TUNER_ADDRESS = 0xC0            # the 0xC0 tuner behind the demod's I2C bridge
TUNER_READ_VALUE = 0x01C1       # I2C read window: the tuner register is wIndex >> 8
BUS_CONTROL = 0x0401            # demod register that selects which chip the bridge talks to
BUS_TUNER = 0xC000
BUS_DEMOD = 0x0100

DC_OFFSET_START = 0x040D        # force vcm2 = 0.8V before the trims
DC_OFFSET_GAIN = 0x0FFB         # shut the LNA and the LO down in register 0x24
# addr, trim-register offset, pga, CTRL_BB1 value, i (0 = I path, 1 = Q path)
# dib0090_dc_table: BB gain1 = 26 with boost 1, gain 2 = 0, then gain 2 = 29.
DC_TABLE = (
    (0x06, 5, 1, (1 << 13) | (0 << 8) | (26 << 3), 1),
    (0x07, 11, 1, (1 << 13) | (0 << 8) | (26 << 3), 0),
    (0x06, 0, 0, (1 << 13) | (29 << 8) | (26 << 3), 1),
    (0x06, 10, 0, (1 << 13) | (29 << 8) | (26 << 3), 0),
)
CAPTRIM_LO4 = 0xE900            # 0x18 = lo4 | captrim, the trim word the search writes
UHF_ENABLE = 0x22E9             # EN_UHF: LNA2, MIX2, IQADC, PLL, BB, BIAS
CRYSTAL = 0x0002                # EN_CRYSTAL, the analog-output enable
WBD_GAIN_UHF = 6                # dib0090_wbd_table_default: band 470-860 MHz
WBD_MUX = (WBD_GAIN_UHF << 13) | (3 << 11) | 0x781 | (1 << 1)
# The UHF tuning row for the measured band (dib0090_tuning_table, 520 MHz and below): lna_bias 15,
# v2i 0x0300, mix 0x1d12, load 0xb9ce.  The 0x0b switch trim (2) is part of the PLL block.  The LNA
# tuning code follows the band (see UHF_LNA_TUNE).
UHF_TUNING = {"lna_bias": 15, "v2i": 0x0300, "mix": 0x1D12, "load": 0xB9CE}
# dib0090_tuning_table's UHF rows, the ones below the tuner band this module supports: the LNA
# tuning code changes at 520/550/650 MHz, not at every frequency (the four captures show 0 for
# 479/485/497 and 3 for 599).
UHF_LNA_TUNE = ((520000, 0), (550000, 2), (650000, 3), (750000, 5), (850000, 6), (900000, 7))
BANDWIDTH_6MHZ = 2 << 14        # dib0090_set_bandwidth: 6000 kHz -> 2; bb_1_def is 0 here
# The kernel enforces a 10 ms floor between the calibration steps ("the tuner sometimes doesn't
# lock when this timer is lower than 10 ms"); the captured vendor run paces its ADC reads about
# that far apart too.  The search and the baseband writes need no delay.
ADC_SETTLE_MS = 10


def _s16(value):
    """Truncate to a signed 16-bit word, the width the kernel's state uses."""
    value &= 0xFFFF
    return value - 0x10000 if value & 0x8000 else value


def _steps(step):
    """dib0090.c's ``steps(u)``: a trim step above 15 carries its sign in bit 4."""
    return step - 16 if step > 15 else step


class TunerBus:
    """Register access to the 0xC0 tuner through the demod's bus gate.

    The bridge reaches the tuner only while demod register 0x0401 is 0xC000, and the capture
    wraps every tuner read and write that way.  A read that does not come back raises instead of
    returning a guess: a calibration that invents an ADC value tunes blind.
    """

    def __init__(self, link, log=None, sleep=None):
        self.link = link
        self.log = log or (lambda message: None)
        self.sleep = sleep or (lambda milliseconds: time.sleep(milliseconds / 1000.0))
        self.writes = 0
        self.reads = 0

    def _gate(self, value):
        self.link.control_out(0x03, 0, 0,
                              bytes([0x03, 0x80, BUS_CONTROL >> 8, BUS_CONTROL & 0xFF,
                                     (value >> 8) & 0xFF, value & 0xFF]))

    def write(self, register, value):
        self._gate(BUS_TUNER)
        self.link.control_out(0x03, 0, 0,
                              bytes([0x03, TUNER_ADDRESS, register,
                                     (int(value) >> 8) & 0xFF, int(value) & 0xFF]))
        self._gate(BUS_DEMOD)
        self.writes += 1

    def read(self, register):
        self._gate(BUS_TUNER)
        try:
            reply = self.link.control_in(0x02, TUNER_READ_VALUE, register << 8, 2)
        finally:
            self._gate(BUS_DEMOD)
        self.reads += 1
        if not reply or len(reply) < 2:
            raise IOError("tuner register 0x%02x did not answer" % register)
        return (reply[0] << 8) | reply[1]

    def wait(self, milliseconds):
        self.sleep(milliseconds)


def _set_trim(io, addr, offset, step, bb6, bb7):
    """dib0090_set_trim: write the current step into the 0x06/0x07 trim field."""
    mask = ~(0x1F << offset) & 0xFFFF
    field = (step & 0x1F) << offset
    if addr == 0x07:
        bb7 = (bb7 & mask) | field
        value = bb7
    else:
        bb6 = (bb6 & mask) | field
        value = bb6
    io.write(addr, value)
    return bb6, bb7


def dc_offset_calibration(io, log=None):
    """dib0090_dc_offset_calibration: four trims, each a sign search on the 0x1d ADC.

    A trim starts at step 0 and steps up while the ADC difference keeps its sign.  When the sign
    changes - or the step limit is reached - the search stops, stepping back one if the last
    value was farther from zero than the previous one.  The pga rows start on the -16 side
    (step 0x10), which is the bit-15 direction of the same field.
    """
    log = log or (lambda message: None)
    bb6, bb7 = 0x0000, DC_OFFSET_START
    io.write(0x24, io.read(0x24) & DC_OFFSET_GAIN)          # LNA and LO off
    io.write(0x10, (io.read(0x10) & ~(0xFF << 3)) | (0x7 << 3) | 0x3)
    io.write(0x23, io.read(0x23) & ~(1 << 14))              # DataTX off while calibrating
    steps_taken = 0
    for addr, offset, pga, bb1, i in DC_TABLE:
        io.write(0x01, bb1)
        io.write(0x07, bb7 | (i << 7))
        step = 0
        min_adc_diff = 1023
        while True:
            bb6, bb7 = _set_trim(io, addr, offset, step, bb6, bb7)
            io.write(0x1F, 0x7)                             # turn the offset positive
            io.wait(ADC_SETTLE_MS)
            adc_diff = io.read(0x1D)
            io.write(0x1F, 0x4)                             # turn it negative
            io.wait(ADC_SETTLE_MS)
            adc_diff = _s16(adc_diff - io.read(0x1D))
            steps_taken += 1
            if step == 0 and adc_diff < 0:
                min_adc_diff = -1023                        # the minimum itself changed sign
            if step == 0:
                if pga and adc_diff < 0:
                    step = 0x10
                if not pga and adc_diff > 0:
                    step = 0x10
            if (adc_diff & 0x8000) == (min_adc_diff & 0x8000) and _steps(step) < 15:
                step = (step + 1) & 0xFF
                min_adc_diff = adc_diff
                continue
            if abs(adc_diff) > abs(min_adc_diff):
                step = (step - 1) & 0xFF                    # the minimum was the step before
            bb6, bb7 = _set_trim(io, addr, offset, step, bb6, bb7)
            break
    io.write(0x07, bb7 & ~0x0008)
    io.write(0x1F, 0x7)
    log("tuner: DC offset %d steps, 0x06=0x%04x 0x07=0x%04x" % (steps_taken, bb6, bb7))
    return bb6, bb7


def wbd_calibration(io, gain=WBD_GAIN_UHF):
    """dib0090_wbd_calibration: sample the WBD multiplexer offset for the band."""
    io.write(0x10, 0x1B81 | (1 << 10) | (gain << 13) | (1 << 3))
    io.write(0x24, (UHF_ENABLE & 0x0FFF) | CRYSTAL)
    io.wait(18)                                             # the WBDMUX switch and ADC sample
    return io.read(0x1D)


def get_temperature(io):
    """dib0090_get_temperature: two bias settings, one ADC difference, a Celsius estimate."""
    wbdmux = io.read(0x10)
    io.write(0x10, (wbdmux & ~(0xFF << 3)) | (0x8 << 3))
    bias = io.read(0x13)
    io.write(0x13, bias | (0x3 << 8))
    io.wait(ADC_SETTLE_MS)
    hot = io.read(0x1D)
    io.write(0x13, (bias & ~(0x3 << 8)) | (0x2 << 8))
    io.wait(ADC_SETTLE_MS)
    cold = io.read(0x1D)
    temperature = (_s16((cold - hot) * 180) >> 8) + 55
    io.write(0x13, bias)
    io.write(0x10, wbdmux)                                  # write the original WBDMUX back
    return temperature


def captrim_search(io, target=400, start=64):
    """dib0090_captrim_search: the binary search on the 0x18 capacitor trim.

    ``start`` is 64 for a full search; the kernel's "already tuned" variant starts at 4.  Each
    pass halves the step, writes ``0xE900 | captrim``, reads the ADC and moves the trim toward
    the target, keeping the closest trim seen.  Returns ``(final captrim, best distance)``.
    """
    io.write(0x10, 0x02B1)
    io.write(0x1E, 0x0032)
    step = captrim = fcaptrim = start
    best = 3000
    while True:
        step //= 2
        io.write(0x18, CAPTRIM_LO4 | captrim)
        io.wait(ADC_SETTLE_MS)
        adc = io.read(0x1D)
        if adc >= target:
            adc -= target
            sign = -1
        else:
            adc = target - adc
            sign = 1
        if adc < best:
            best = adc
            fcaptrim = captrim
        captrim += sign * step
        if step < 1:
            io.write(0x18, CAPTRIM_LO4 | fcaptrim)          # the final trim configuration
            return fcaptrim, best


# The tuner PLL block's register order, exactly as the captures write it and as dib0090_set_params
# emits it.  ``tuner_calibrate`` writes this after the calibration, and the zap cache replays just
# this block to move the LO back to a frequency the tuner was already calibrated for.
PLL_WRITE_ORDER = (0x0B, 0x15, 0x16, 0x17, 0x19, 0x1C, 0x24)


def write_pll(io, pll):
    """Write a PLL block (``tuner_pll``'s seven words) to the 0xC0 tuner, in the captured order."""
    for register in PLL_WRITE_ORDER:
        if register not in pll:
            raise ValueError("PLL block has no 0x%02x word" % register)
        io.write(register, pll[register])


def tuner_calibrate(io, khz, pll=None, log=None, dc=True):
    """The whole dib0090 tune, live, from the bus and the frequency.

    The caller closes the demod output gate first (``retune`` does).  The order is the kernel's:
    the calibrations, the PLL block for ``khz``, the captrim search on the locked LO, then the
    band's gain/mixer/filter configuration.

    ``dc`` follows the kernel's calibration mask.  A tune after a reset does the DC offset
    calibration (``dib0090_reset`` sets ``DC_CAL | WBD_CAL | TEMP_CAL``); a re-tune keeps the DC
    trims the previous tune left in 0x06/0x07 and does only WBD and temperature.  That matters on
    hardware: re-running the DC search on an already tuned chip searches against a different
    baseband state and lands on a wrong offset (measured: the same-frequency control stopped
    locking), while the kernel never does it.
    """
    log = log or (lambda message: None)
    pll = tuner_pll(khz) if pll is None else pll
    if dc:
        # dib0090_reset leaves 0x24 = EN_UHF | EN_CRYSTAL, and the DC calibration is written to
        # run from that state: its trims are searched against the IQ ADC.  A re-tune inherits the
        # analog-mode word from the previous PLL block (EN_IQADC and EN_PLL cleared), and with
        # the ADC block off the search lands on a different, wrong offset.
        io.write(0x24, UHF_ENABLE | CRYSTAL)
        io.write(0x23, io.read(0x23) & ~(1 << 14))          # DataTX off before the calibration
        dc_offset_calibration(io, log)
    io.write(0x23, io.read(0x23) & ~(1 << 14))
    wbd_offset = wbd_calibration(io)
    io.write(0x23, io.read(0x23) & ~(1 << 14))
    temperature = get_temperature(io)
    write_pll(io, pll)
    io.wait(11)
    rest = pll[0x17]
    captrim, distance = captrim_search(io, target=200 if rest == 0 else 400)
    # After the captrim search: the final WBD mux, the UHF LNA/mixer/load row and the baseband
    # filter (dib0090_tune's step 0 and step 1).  Then the tuner is tuned.
    io.write(0x1E, 0x07FF)
    io.write(0x10, WBD_MUX)
    io.write(0x09, (lna_tune(khz) << 5) | UHF_TUNING["lna_bias"])
    io.write(0x0C, UHF_TUNING["v2i"])
    io.write(0x0D, UHF_TUNING["mix"])
    io.write(0x0E, UHF_TUNING["load"])
    io.write(0x01, BANDWIDTH_6MHZ)                          # be sure of the baseband filter
    io.write(0x03, 0x6008)
    io.write(0x04, 0x0001)
    io.write(0x02, (5 << 11) | (8 << 6) | 22)
    io.write(0x05, 0xABCD)
    log("tuner %d kHz: captrim %d (ADC distance %d), WBD offset %d, temperature %d C"
        % (khz, captrim, distance, wbd_offset, temperature - 30))
    return {"captrim": captrim, "distance": distance, "wbd": wbd_offset,
            "temperature": temperature}


def _tuner_write(record):
    raw = bytes.fromhex(record.get("data") or "")
    return (record.get("dir") == "out" and record.get("request") == 3
            and len(raw) == 5 and raw[1] == TUNER_ADDRESS)


def _bus_gate(record):
    return (record.get("dir") == "out" and record.get("request") == 3
            and (record.get("data") or "").startswith("03800401"))


def _tuner_read(record):
    return (record.get("dir") == "in" and record.get("request") == 2
            and record.get("value") == TUNER_READ_VALUE)


def split_calibration(records, start=None):
    """Locate a captured tune's dib0090 calibration: ``(first, end)`` indices.

    ``first`` is the 0x23 write that switches DataTX off (0x4a30 -> 0x0a30); the block runs
    through the tuner reads and bus-gate writes to the first record of the demod's own AGC
    setup.  This is the block that has to be regenerated from live reads, not replayed.
    """
    if start is None:
        start, _, _ = split_tune(records)
    if start is None:
        raise ValueError("capture has no tune phase")
    first = None
    for index in range(start, len(records)):
        if _tuner_write(records[index]):
            raw = bytes.fromhex(records[index]["data"])
            if raw[2] == 0x23 and ((raw[3] << 8) | raw[4]) == 0x0A30:
                first = index
                break
    if first is None:
        raise ValueError("capture has no dib0090 calibration start")
    end = first
    while end < len(records) and (_tuner_write(records[end]) or _bus_gate(records[end])
                                  or _tuner_read(records[end])):
        end += 1
    return first, end


def retune_plan(records, khz):
    """The replayable parts of a retune, around the live calibration.

    ``head`` is the tune preamble through the 0x23 DataTX write; the caller then runs
    :func:`tuner_calibrate` for ``khz`` with live reads; ``body`` is the demod's AGC and
    autosearch configuration (no tuner state in it) through the captured ``0x0000 = 0x6000``
    search trigger; ``final`` is the channel block whose layer words the caller patches from the
    TMCC read-back (``patch_layer_words``).  ``pll`` is the tuner block the live calibration
    writes, for callers that want it without recomputing.
    """
    start, restart, final = split_tune(records)
    if start is None or restart is None or final is None:
        raise ValueError("capture has no tune phase (start=%r restart=%r final=%r)"
                         % (start, restart, final))
    first, end = split_calibration(records, start)
    trigger = split_search(records, restart)
    return {"head": [dict(record) for record in records[start:first]],
            "body": [dict(record) for record in records[end:trigger + 1]],
            "final": [dict(record) for record in records[final:]],
            "pll": tuner_pll(khz)}


def patch_layer_words(records, values):
    """Set the demod's three layer words (registers 2, 3, 4) in a block."""
    values = list(values)
    if len(values) != 3:
        raise ValueError("expected three layer words, got %d" % len(values))
    patched = []
    for record in records:
        raw = bytes.fromhex(record.get("data") or "")
        if (record.get("dir") == "out" and record.get("request") == 3
                and len(raw) == 6 and raw[1] == 0x80 and raw[2] == 0 and 2 <= raw[3] <= 4):
            value = values[raw[3] - 2]
            record = dict(record)
            record["data"] = (raw[:4] + bytes([value >> 8, value & 0xFF])).hex()
        patched.append(record)
    return patched


# --- TMCC decode: dib8000_get_frontend, reimplemented from the kernel map ----------------------
FEC_INDEX = {1: 1, 2: 2, 3: 3, 5: 5}     # TMCC code rate -> fec enum; anything else is 7/8


def _word(words, register):
    value = words.get(register)
    return 0 if value is None else value


def decode_tmcc(words):
    """The channel the demod found: mode, guard, inversion, partial reception and the 3 layers.

    ``words`` maps demod register -> 16-bit value, as read after the autosearch.  This is the same
    field layout the kernel's ``dib8000_get_frontend`` uses: register 570 (or 572 on the 0x8090
    revision), 505, and the TMCC block 481+i / 487+i / 493+i / 499+i.

    The interleaving stays the raw TMCC value (0..3).  The kernel reports 3 as 4 in the frontend
    property, but the layer word it writes keeps the raw 3 - the 485/497 captures prove it
    (0x0453/0x045b rather than 0x0454/0x045c).
    """
    value = _word(words, 570)
    revision = _word(words, 572)
    if (value & 0x30) == 0 and (revision & 0x30):
        value = revision
    layers = []
    for i in range(3):
        layers.append({
            "modulation": _word(words, 487 + i) & 0x03,
            "segments": _word(words, 493 + i) & 0x0F,
            "fec": FEC_INDEX.get(_word(words, 481 + i) & 0x07, 7),
            "interleaving": _word(words, 499 + i) & 0x03,
        })
    mode = (value >> 4) & 0x03
    return {
        "mode": mode or 3,                 # 0 means "not reported"; 8K is the safe reading
        "guard": value & 0x03,
        "inversion": (value >> 6) & 0x01,
        "partial": _word(words, 505) & 0x01,
        "sb": _word(words, 508) & 0x01,
        # EWBS: register 472 is 0 when the flag is off and 0x0F when the alert is on (measured on the
        # official broadcast and on the DTA's flag on/off runs; 510 moved too but not consistently).
        "ewbs": 1 if _word(words, 472) else 0,
        "layers": layers,
    }


def plausible(decoded):
    """True when the TMCC block describes a real ISDB-T channel (1..13 segments in total)."""
    segments = [layer["segments"] for layer in decoded["layers"]]
    return all(0 <= segment <= 13 for segment in segments) and 1 <= sum(segments) <= 13


def channel_signature(decoded):
    """The mux identity of a decoded TMCC: mode, guard, partial reception and the three layers.

    This is what a zap-cache entry is verified against: a cached channel block is only replayed
    without the tune procedure while the demod still reports the same mux.  The layer tuple holds
    each layer's modulation, segment count, code rate and interleaving - the fields
    ``dib8000_get_frontend`` reports and the final block is built from.
    """
    layers = tuple((int(layer["modulation"]), int(layer["segments"]),
                    int(layer["fec"]), int(layer["interleaving"]))
                   for layer in (decoded.get("layers") or []))
    return (int(decoded.get("mode", 0)), int(decoded.get("guard", 0)),
            int(decoded.get("partial", 0)), layers)


def same_channel(left, right):
    """True when two decoded TMCCs describe the same mux (the cache's staleness test)."""
    if not left or not right:
        return False
    return channel_signature(left) == channel_signature(right)


def layer_words(decoded):
    """Registers 2/3/4 for the decoded channel: the very words ``dib8000_set_isdbt_common_channel``
    writes - ``(modulation << 10) | (segments << 6) | (fec << 3) | interleaving``."""
    return [(layer["modulation"] << 10) | ((layer["segments"] & 0x0F) << 6)
            | (layer["fec"] << 3) | layer["interleaving"] for layer in decoded["layers"]]


# --- channel-estimate fine tuning: dib8000_adp_fine_tune / dib8000_update_ana_gain ----------
# The final channel block carries values that follow the *constellation* the demod reported,
# not the base capture's channel: the analog front-end targets (80-89), P_ana_gain (116) and the
# adaptive-noise words (215-218).  Replaying the base's ones on a mux with a different highest
# constellation leaves the channel estimator tuned for the wrong modulation - measured: 497 ->
# 479 streams with 3-4 k continuity breaks and 20-30 % of the packets missing (no TEI, so the
# packets are lost after RS), while the captured 479 replay is clean.  These are the kernel's
# tables (dib8000.c, GPL-2.0-only: numbers only, reimplemented here), and each capture's own
# final block confirms them byte for byte.
ADC_TARGET_16DB = (7250, 7238, 7264, 7309, 7338, 7382, 7427, 7456, 7500, 7544)
ADP_Q64 = (0x0148, 0xFFF0, 0x00A4, 0xFFF8)
ADP_Q16 = (0x023D, 0xFFDF, 0x00A4, 0xFFF0)
ADP_QDEFAULT = (0x099A, 0xFFAE, 0x0333, 0xFFF8)
ANA_GAIN_ZERO_FE_OFFSET = 355     # ana_gain = 0 targets -22 dB instead of -16 dB


def channel_estimate_words(decoded):
    """Registers 116, 80-89 and 215-218 for the decoded channel.

    ``dib8000_adp_fine_tune`` picks the adaptive-noise row from the highest constellation of a
    layer that carries segments, and returns ``ana_gain`` 7 for QAM16/QAM64 and 0 otherwise;
    ``dib8000_update_ana_gain`` writes it and the analog-front-end targets (355 lower when
    ``ana_gain`` is 0).  DQPSK and QPSK both take the default row.
    """
    active = [layer["modulation"] for layer in decoded["layers"] if layer["segments"] > 0]
    constellation = max(active) if active else 0
    if constellation == 3:                     # QAM64
        ana_gain, adp = 7, ADP_Q64
    elif constellation == 2:                   # QAM16
        ana_gain, adp = 7, ADP_Q16
    else:                                      # DQPSK / QPSK
        ana_gain, adp = 0, ADP_QDEFAULT
    words = {116: ana_gain}
    for index, value in enumerate(ADC_TARGET_16DB):
        words[80 + index] = value if ana_gain else value - ANA_GAIN_ZERO_FE_OFFSET
    for index, value in enumerate(adp):
        words[215 + index] = value
    return words


def patch_words(records, values):
    """Set the demod words whose registers are the keys of ``values`` in a block."""
    patched = []
    for record in records:
        raw = bytes.fromhex(record.get("data") or "")
        if (record.get("dir") == "out" and record.get("request") == 3
                and len(raw) == 6 and raw[1] == 0x80):
            register = (raw[2] << 8) | raw[3]
            if register in values:
                value = values[register]
                record = dict(record)
                record["data"] = (raw[:4] + bytes([value >> 8, value & 0xFF])).hex()
        patched.append(record)
    return patched


def patch_channel_estimate(records, values):
    """Set the channel-estimate words (the keys of ``values``) in a block."""
    return patch_words(records, values)


def patch_channel_config(records, decoded):
    """Set the demod's mode, guard and partial-reception words from the decoded channel.

    ``dib8000_set_isdbt_common_channel`` writes register 0 (``mode << 13``), register 1 (the guard
    interval) and register 274 (partial reception and sound-broadcasting mode) from the channel
    parameters the demod itself found - not from the base capture's channel.  Replaying the base's
    words is only correct while the target uses the same guard interval and the same partial
    reception: measured on hardware, CH14's 1/8 guard and 527.143's partial 0 both decoded a TMCC
    and then produced no packets, while every channel that locked shared the 497 base's 1/4 guard
    and partial 1.  The four captures confirm the mapping (599 has guard 2 in both its TMCC and its
    final block).
    """
    guard = int(decoded.get("guard", 0)) & 0x03
    partial = int(decoded.get("partial", 0)) & 0x01
    sb = int(decoded.get("sb", 0)) & 0x01
    mode = int(decoded.get("mode", 3)) & 0x03
    patched = []
    for record in records:
        raw = bytes.fromhex(record.get("data") or "")
        if (record.get("dir") == "out" and record.get("request") == 3
                and len(raw) == 6 and raw[1] == 0x80):
            register = (raw[2] << 8) | raw[3]
            value = (raw[4] << 8) | raw[5]
            if register == 0:
                value = (value & 0x1FFF) | (mode << 13)
            elif register == 1:
                value = (value & 0xFFFC) | guard
            elif register == 274:
                value = (value & 0xFFCF) | (partial << 5) | (sb << 4)
            else:
                patched.append(record)
                continue
            record = dict(record)
            record["data"] = (raw[:4] + bytes([value >> 8, value & 0xFF])).hex()
        patched.append(record)
    return patched


# --- stream configuration: dib8000_set_isdbt_common_channel's layer-derived words -------------
# The three layer words are not the whole channel block.  ``dib8000_set_isdbt_common_channel``
# writes the same channel into a dozen other words, all computed from the TMCC's per-layer segment
# counts and modulations: the differential-segment map (register 0's low 13 bits, register 268),
# the segment-inhibit masks (189/192/225/288/211/266), the channel-estimation window edges
# (222/223), the small-block fine-tune words (352/353) and the TMCC decoding threshold (290-292).
# A final block replayed from a 1+12 capture carries *that* capture's values; on a 1+8+4 mux the
# demod locks, decodes the TMCC and then streams nothing (measured 2026-11-20: "did not stream" at
# candidates 6/7 of the sweep, 0 packets).  These are the kernel's derivations, reimplemented from
# the GPL-2.0-only map as numbers only.
PERMU_SEG = (6, 5, 7, 4, 8, 3, 9, 2, 10, 1, 11, 0, 12)   # dib8000.c permu_seg
SEG_MASK_13SEG = 0x1FFF                                    # dib8000_set_13seg_channel
MOD_DQPSK = 0                                              # decode_tmcc's modulation code


def seg_diff_mask(decoded):
    """The differential-segment map ``dib8000_set_isdbt_common_channel`` builds from the layers.

    A DQPSK layer is demodulated differentially, and the demod needs to know which FFT segments
    those are: 13 bits, one per segment.  ARIB STD-B31 3.15.6.4 numbers the segments from the
    centre outward - segment 0 is the centre segment and the partial-reception segment - and the
    layers are allocated in order, layer A first, then B, then C.  ``permu_seg`` is the kernel's
    segment-number -> FFT-segment map (``dib8000.c``): segment number 0 is FFT segment 6, 1 is 5,
    2 is 7, and so on outward.

    The kernel's own walk is simpler: it puts a differential layer A on ``permu_seg[0]`` and then
    takes ``permu_seg[1..n]`` for the DQPSK segments of B and C, i.e. it assumes the differential
    segments are always the innermost ones, which is true only for a differential layer A (the
    intended use - the standard puts the partial layer at the centre).  A 1+8+4 mux with DQPSK on
    layer C breaks that assumption: the kernel would mark four of layer B's segments differential,
    and the block built from it would stream nothing (measured 2026-11-20).  Following each
    layer's own segment numbers is the same map for a differential layer A or B - and it keeps the
    four captured 1+12 blocks byte-identical, because none of their layers is differential.
    """
    layers = decoded.get("layers") or []
    if len(layers) != 3:
        return 0
    mask = 0
    start = 0
    for layer in layers:
        count = int(layer["segments"]) & 0x0F
        if int(layer["modulation"]) == MOD_DQPSK:
            for number in range(start, min(start + count, len(PERMU_SEG))):
                mask |= 1 << PERMU_SEG[number]
        start += count
    return mask


def stream_config_words(decoded):
    """The channel block's stream-side words that follow the decoded layer table.

    ``dib8000_set_isdbt_common_channel`` derives these from the TMCC's per-layer segment counts
    and modulations, not from the frequency, so they have to follow the TMCC like the layer words
    do.  Register 0 carries the mode as well (the kernel writes mode and the differential map as
    one word), so a caller applies this after :func:`patch_channel_config`.

    The map (register -> kernel expression):

    * 0 - ``(mode << 13) | seg_diff_mask``;
    * 268 - ``(P_equal_ctrl_synchro | P_equal_speedmode)``: the reset value ``(2 << 9) | 39``, or
      bits 9-10 reduced to bit 9 when a differential layer is present;
    * 222/223 - ``P_cfr_left_edge`` / ``P_cfr_right_edge``, the channel-estimate window edges in
      segment coordinates, walked over the segments that are not differential;
    * 189/192/225 - ``P_lmod4_seg_inh`` / ``P_pha3_seg_inh`` / ``P_tac_seg_inh`` =
      ``~seg_mask | seg_diff_mask``;
    * 288 - ``P_tmcc_seg_eq_inh`` = the same, masked to 13 bits (the final, non-searching block);
    * 211 - ``P_des_seg_enabled`` = ``seg_mask & ~seg_diff_mask``;
    * 266 - ``P_equal_noise_seg_inh`` = ``~seg_mask | seg_diff_mask | 0x40`` (the low-IF tuner
      branch of ``dib8000_set_13seg_channel``, which the captures confirm);
    * 352/353 - ``P_small_seg_diff_mask`` / ``P_small_seg_mask``;
    * 290-292 - ``P_tmcc_dec_thres_2k/4k/8k``, a quarter of the TMCC power: ``1 + 4`` per DQPSK
      segment and ``1`` per segment otherwise, times ``1 << 7``.

    A sound-broadcasting channel takes ``dib8000_set_sb_channel``'s own block - a different
    segment mask, equaliser row and FFT power range - which is not derived here: for one of those
    the base block is left alone rather than half-configured.  The caller can then skip the patch.
    """
    layers = decoded.get("layers") or []
    if len(layers) != 3:
        return {}
    if int(decoded.get("sb", 0)) & 0x01:
        return {}
    mode = int(decoded.get("mode", 3)) & 0x03
    mask = seg_diff_mask(decoded)
    seg_mask = SEG_MASK_13SEG                    # the 13-segment (full-segment) service
    active = seg_mask & ~mask
    words = {0: (mode << 13) | mask}
    # dib8000_defaults: reg 268 = (2 << 9) | 39; a differential layer sets bit 9 and clears 10.
    if mask:
        words[268] = (((2 << 9) | 39) & 0xF9FF) | 0x0200
    else:
        words[268] = (2 << 9) | 39
    left = right = 0
    for index in range(13):
        if (mask >> index) & 1:
            continue
        if index == 0 or not ((active >> (index - 1)) & 1):
            left += 1 << index
        if index == 12 or not ((active >> (index + 1)) & 1):
            right += 1 << index
    words[222] = left
    words[223] = right
    inactive = (~seg_mask | mask) & 0xFFFF
    words[189] = inactive
    words[192] = inactive
    words[225] = inactive
    words[288] = inactive & 0x1FFF
    words[211] = active
    words[266] = (inactive | 0x40) & 0xFFFF
    words[352] = mask
    words[353] = seg_mask
    tmcc_pow = 0
    for layer in layers:
        weight = 1 + (4 if int(layer["modulation"]) == MOD_DQPSK else 0)
        tmcc_pow += weight * int(layer["segments"])
    tmcc_pow *= 1 << 7                           # the register is a quarter of the maximum
    words[290] = words[291] = words[292] = tmcc_pow & 0xFFFF
    return words


TMCC_REGISTERS = (508, 570, 572, 505, 472,
                  481, 482, 483, 487, 488, 489, 493, 494, 495, 499, 500, 501)


# --- mode/guard acquisition: the scan a real receiver does before it can read a TMCC -----------
# A capture is mux-specific: its final channel block fixes the mode, the guard interval and
# partial reception to the channel it was captured on, so replaying it on a broadcaster that uses
# different parameters cannot lock - and without a lock the TMCC that would say what the channel
# really is cannot be read (measured: the 599.143 capture at the DTA's guard 1/16 mux, 2026-11-20).
# The captured tune phase shows the demod's own search is a parameter scan: it runs the FFT
# (mode) phase and then a second phase over all four guard intervals, but that second phase runs
# at the mode of the *captured* channel (the host writes the found-mode value from the capture),
# so another mode is never tried.  The sweep does what the host would: write the channel-config
# words for one candidate, re-trigger the search and poll the autosearch IRQ / lock word.
ACQUISITION_MODES = ((1, "2K"), (2, "4K"), (3, "8K"))
ACQUISITION_GUARDS = ((0, "1/32"), (1, "1/16"), (2, "1/8"), (3, "1/4"))
# The three words dib8000_set_isdbt_common_channel writes from the channel parameters: register 0
# (mode << 13, with bit 15 = P_restart_search), 1 (guard interval in the low two bits) and 274
# (partial reception bit 5, sound-broadcasting bit 4).
CHANNEL_CONFIG_REGISTERS = (0, 1, 274)


def acquisition_combos(partial=1):
    """The twelve mode x guard candidates, in the order the sweep tries them.

    ``partial`` is the partial-reception bit the acquisition assumes.  The demod's own autosearch
    forces it on (``dib8000_set_isdbt_common_channel`` sets ``isdbt_partial_reception = 1`` while
    autosearching), and the TMCC read back after a lock carries the real value.
    """
    return [{"mode": mode, "guard": guard, "partial": int(partial) & 0x01, "sb": 0}
            for mode, _ in ACQUISITION_MODES
            for guard, _ in ACQUISITION_GUARDS]


def combo_text(combo):
    """``8K 1/16`` for a candidate or a decoded channel - the log and record form."""
    modes = dict(ACQUISITION_MODES)
    guards = dict(ACQUISITION_GUARDS)
    return "%s %s" % (modes.get(int(combo.get("mode", 3)), "?"),
                      guards.get(int(combo.get("guard", 0)), "?"))


def channel_config_records(records, decoded):
    """The mode/guard/partial-reception writes of a channel block, patched for ``decoded``.

    :func:`patch_channel_config` holds the encoding; this keeps only registers 0, 1 and 274 - the
    three words that tell the demod which channel to search.  The acquisition sweep writes these
    for one candidate and re-triggers the search, so the same encoding serves acquisition and the
    final block.
    """
    kept = []
    for record in patch_channel_config(records, decoded):
        raw = bytes.fromhex(record.get("data") or "")
        if (record.get("dir") == "out" and record.get("request") == 3
                and len(raw) == 6 and raw[1] == 0x80
                and ((raw[2] << 8) | raw[3]) in CHANNEL_CONFIG_REGISTERS):
            kept.append(record)
    return kept

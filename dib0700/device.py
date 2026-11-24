# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (c) 2026 The DIB0700 Open Driver Authors.
#
# Our own code, no vendor code.

"""DIB0700 bring-up, tuning and streaming.

Order matters and follows the Linux probe:

1. read the firmware version - a cold dib0700 answers 0 and needs the image,
2. download the firmware and start it (``firmware.upload``),
3. replay the captured session (probe, power-up and the tune for one frequency),
4. ``ENABLE_VIDEO`` to start the TS engine,
5. read bulk IN ``0x82`` and cut it into 188-byte packets.

The cold path is the supported one: the whole chain runs from user space, no kernel driver.
On Linux make sure ``dvb_usb_dib0700`` does not bind first (blacklist it, or unbind before
plugging) - a device the kernel already booted is "warm", and the warm branch is not the tested
path.

A frequency with a capture is still replayed whole. Any other frequency in the tuner band uses
:meth:`Dib0700.tune_any`: replay the nearest captured session to get a known demod state, then re-run
the tune procedure (tuner PLL, AGC/calibration, autosearch, TMCC read-back, final channel block)
through :meth:`Dib0700.retune`.  The tune is a state machine, so the demod's answer is used, not a
value table: the layer words come from the TMCC registers the demod reports after its own search.
"""
import time

from . import firmware as firmware_module
from . import init as init_module
from . import power as power_module
from . import tune as tune_module
from .link import (EP_FIRMWARE_OUT, EP_TS_IN, REQUEST_I2C_READ, enable_video,
                   get_version)
from .quality import QualityMeter
from .stream import TsStream

# 0x02 I2C read, wValue 0x0281: wIndex is the demod register, two bytes come back big-endian.
I2C_READ_VALUE = 0x0281
LOCK_REGISTER = 568
STRENGTH_REGISTER = 390
# dib8000_read_lock's word: bits 11/10 (coff/cpil and lmod4) and bit 12 (FEC), then the MPEG lock
# of *any* layer that carries segments - bit 7 layer A, bit 6 layer B, bit 5 layer C
# (dib8000_get_status: FE_HAS_LOCK needs bit 12 and (lock >> 5) & 7).  The old mask asked for
# layer A's bit 7 unconditionally, so a 1-seg-A + 12-seg-B mux whose layer A does not reach MPEG
# lock - measured with the on-air DTA mux, 2026-11-20 - read "lock False" while its stream was
# clean (111,807 packets / 10 s).  A layer's MPEG lock is enough.
LOCK_BITS = 0x1C00
LAYER_LOCK_BITS = 0x00E0
# dib8000_autosearch_irq: bit 0 is "failed", bit 1 is "succeeded"; read after P_restart_search.
SEARCH_IRQ_REGISTER = 0x0504
# The acquisition sweep.  A captured search takes 0.3-0.65 s from the trigger to the success bit
# (599: 0.31 s, 497: 0.62 s), so one candidate gets this long before the next one is tried.
ACQUISITION_TIMEOUT = 1.0
ACQUISITION_POLL = 0.025
# P_search_param_select's low four bits select the guard intervals the search tries
# (dib8000_autosearch_start: 0xf = "look for the 4 different guard intervals").
SEARCH_SELECT_REGISTER = 357
# A cached zap has no calibration and no search to wait for: it writes the PLL block and the final
# channel block, opens the output and only has to see the lock or a short clean stream run.
CACHED_LOCK_TIMEOUT = 1.5
# The demod's TMCC registers update once per frame; a cached burst re-reads them for this long
# before it calls the entry stale (a genuinely different mux fails the whole window).
CACHED_TMCC_TIMEOUT = 0.4
CACHED_TMCC_POLL = 0.1
# The captured autosearch takes 0.31-0.65 s; the cached burst waits for its success bit.  Measured
# 2026-11-20: the final block alone does not acquire a channel, so the search is part of the burst.
CACHED_SEARCH_TIMEOUT = 1.5

# Recovery (state 3): the log field says which step worked, so a field report reads
# "state 3, session/firmware cleared" instead of "it works again".
RECOVER_RESET_UPLOAD_REPLAY = "recover: reset+upload+replay"
RECOVER_REPLAY_ONLY = "recover: replay only"
RECOVER_FAILED = "recover failed"


class Dib0700:
    def __init__(self, link, log=None, cache=True):
        self.link = link
        self.log = log or (lambda message: None)
        self.stream = TsStream()
        self.quality = QualityMeter()
        self.sequence = None
        self.khz = None
        # Packets read while a retune waits for lock, before the caller's stream loop starts.
        # They are served first by read_ts(), so the stream the caller sees stays whole.
        self._pending = bytearray()
        # The zap cache: frequency -> {"pll": tuner PLL words, "final": the channel block that
        # locked, "decoded": the TMCC it was built from}.  A channel change back to a frequency
        # this process already tuned is then the cached burst in ``_cached_zap`` instead of the
        # whole procedure.  It is per device object and in memory only; ``--no-cache`` disables it.
        self.cache = {}
        self.cache_enabled = bool(cache)

    def version(self):
        return get_version(self.link)

    def bring_up(self, image=None, sequence=None, power=True, tune=None, sweep=True, cold=None):
        """Bring the device up and start the TS engine; returns the version.

        ``tune`` is a frequency in kHz (``True`` keeps the 599.143 default). Cold devices replay
        the whole captured session for that frequency; warm devices replay the captured init and
        then the same session without its power-up prefix (the kernel already powered the demod).

        ``sweep`` enables the mode/guard acquisition step when a captured replay does not lock:
        a capture is mux-specific, so a broadcaster using another mode or guard interval is
        unreachable otherwise.  It changes nothing while the capture's own mux locks.

        ``cold`` overrides the version-based guess.  Recovery needs it: after a forced upload the
        firmware is freshly started, so the demod is unpowered even though ``GET_VERSION`` answers,
        and the captured ``SET_GPIO``/``SET_CLOCK`` prefix must be replayed - the warm branch would
        drop exactly those records.
        """
        # A bring-up replays the captured probe/power-up (and then a tune); no cached channel block
        # describes the demod state that comes out of it, so the zap cache starts empty.
        self.clear_cache()
        version = self.version()
        cold = (not version) if cold is None else bool(cold)
        was_warm = bool(version) and not cold
        if not version:
            if image is None:
                raise RuntimeError(
                    "cold device and no firmware image found: pass --firmware PATH or set "
                    "DIB0700_FIRMWARE (the vendor image is not distributed with the driver)")
            version = firmware_module.upload(self.link, image, self.log)
            if not version:
                raise RuntimeError(
                    "the boot ROM accepted the image but never started it: "
                    "GET_VERSION still stalls. See the firmware note.")
        else:
            self.log("firmware already running: 0x%05x" % version)

        khz = None if tune is None or tune is False else int(tune or tune_module.DEFAULT_KHZ)

        # A cold device with a captured session: replay probe + power-up + tune as one sequence.
        # One session holds; the old init+tune pair came from two runs and lost lock after ~2 s.
        # A frequency without its own capture replays the nearest one as the base and then re-runs
        # the tune procedure from that known state.
        if khz is not None and not was_warm:
            base = khz if tune_module.has_session(khz) else tune_module.nearest(khz)
            session = tune_module.load_session(base)
            if session:
                session = tune_module.trim_at_output_enable(session)
                self.log("cold device: replaying the captured %d kHz session (%d records)%s"
                         % (base, len(session),
                            "" if base == khz else " as the base for %d kHz" % khz))
                self.summary = init_module.replay(self.link, session, self.log, verify=True)
                enable_video(self.link, True)
                self.log("streaming enabled")
                self.khz = base
                if base != khz:
                    self.retune(khz, base, sweep=sweep)
                else:
                    self._settle_captured(khz, session, sweep)
                return version

        self.sequence = sequence if sequence is not None else init_module.load()
        replay = list(self.sequence)

        if was_warm:
            # A warm device was already brought up: the demods are powered and answering.
            # Replaying the captured SET_GPIO/SET_CLOCK prefix onto it is what silenced them.
            dropped = sum(1 for r in replay if r.get("request") in (0x0B, 0x0C))
            if dropped:
                replay = [r for r in replay
                          if r.get("request") not in (0x0B, 0x0C)]
                self.log("warm device: dropping %d power-up records" % dropped)
        else:
            power_up_in_sequence = any(record.get("request") in (0x0B, 0x0C)
                                       for record in replay)
            if power and not power_up_in_sequence:
                # Without this the demod never answers I2C and every register read comes back
                # 0x0000, which reads as "no signal".
                power_module.power_up(self.link)
                self.log("demod powered up")
            elif power_up_in_sequence:
                self.log("power-up is part of the captured sequence")

        # verify: comparing every I2C reply with the capture is what separates "the demod is up"
        # from "the writes were accepted but nobody is home"
        self.summary = init_module.replay(self.link, replay, self.log, verify=True)

        if khz is not None:
            self.tune(khz, drop_power=was_warm, sweep=sweep)

        enable_video(self.link, True)
        self.log("streaming enabled")
        return version

    def recover(self, image=None, sweep=True):
        """State-3 recovery (warm wedge) in one call: reset, halt, force firmware, replay.

        The wedge lives in firmware RAM, and every normal bring-up skips the download when
        ``GET_VERSION`` answers - so the step that can overwrite that RAM from user space is the
        upload done *regardless* of the version read (the IT9308 line's ``recover``).  One attempt,
        no loop: the caller gets a label and tells the user only when this fails.

        Steps, in order:

        1. bus reset when the host has one (pyusb ``Device.reset()``); a host without it
           (``NotImplementedError``) skips the step and still goes on,
        2. clear the halt on the firmware (0x01) and stream (0x82) endpoints - a halted 0x82 has
           the same symptom: the demod answers, nothing arrives,
        3. force ``firmware.upload``; a device that will not take the image is reported, not
           retried,
        4. :meth:`bring_up` with ``cold`` set after a forced upload (the firmware has just
           restarted, so the captured power-up prefix must be replayed) and the version-based
           guess otherwise (the warm re-init),
        5. re-tune the last frequency (``self.khz``) when one was tuned, and require a lock.

        Returns ``RECOVER_RESET_UPLOAD_REPLAY`` (the force-upload path ran),
        ``RECOVER_REPLAY_ONLY`` (no image was available, so only the warm re-init ran) or
        ``RECOVER_FAILED``, and logs it: the recovery contract's "which step worked" field.
        """
        # The recovery rebuilds the firmware and the demod from the capture: every cached channel
        # block is gone with the state it described (the rule: recover clears the cache).
        self.clear_cache()
        self.log("recover: state 3 (warm wedge) - reset, clear halt, force firmware, replay")
        # Host-log rule 5: the firmware/address pair in the log, before and after, logged by the
        # driver rather than reconstructed from memory afterwards.
        self.log("recover: pair before - firmware %s, %s"
                 % (self._firmware_text(), self._address_text()))
        reset = self._recover_reset()
        halted = self._recover_clear_halts()
        if not reset and not halted:
            self.log("recover: no bus reset or halt clear available; upload and replay decide")
        if image is None:
            try:
                image = firmware_module.find_image()
            except OSError as error:
                self.log("recover: %s" % error)
                image = None
        uploaded = False
        if image is not None:
            try:
                version = firmware_module.upload(self.link, image, self.log)
            except Exception as error:
                # A failed download is a recovery failure, not a traceback: the caller still has
                # to print the hint and exit cleanly.
                self.log("recover: the forced firmware upload failed: %s" % error)
                return self._recover_report(RECOVER_FAILED)
            if not version:
                self.log("recover: the forced firmware upload started nothing "
                         "(GET_VERSION still 0)")
                return self._recover_report(RECOVER_FAILED)
            uploaded = True
        else:
            self.log("recover: no firmware image available; only the warm re-init can run")
        target = self.khz
        try:
            self.bring_up(image=image, tune=target, sweep=sweep,
                          cold=True if uploaded else None)
        except Exception as error:
            self.log("recover: the replay failed: %s" % error)
            return self._recover_report(RECOVER_FAILED)
        if target is not None:
            if self.khz != target:
                # A retune that did not finish leaves ``khz`` at the base capture it started
                # from; that is a different frequency, so recovery did not reach the target.
                self.log("recover: the replay stopped at %s kHz, not %d kHz"
                         % (self.khz, target))
                return self._recover_report(RECOVER_FAILED)
            if not (self.locked() or self._stream_lock(timeout=1.0)):
                self.log("recover: the replay did not lock %d kHz" % target)
                return self._recover_report(RECOVER_FAILED)
        self.log("recover: pair after - firmware %s, %s"
                 % (self._firmware_text(), self._address_text()))
        return self._recover_report(
            RECOVER_RESET_UPLOAD_REPLAY if uploaded else RECOVER_REPLAY_ONLY)

    def _firmware_text(self):
        """The firmware version as ``0x…`` for the recovery pair (``?`` when it does not answer)."""
        try:
            return "0x%05x" % self.version()
        except Exception:
            return "?"

    def _address_text(self):
        """The USB bus/address for the recovery pair (the host-log shape's rule 5)."""
        device = getattr(self.link, "device", None)
        return "bus %s addr %s" % (getattr(device, "bus", "?"),
                                   getattr(device, "address", "?"))

    def _recover_report(self, outcome):
        """Log and return one recovery outcome; the label is the contract's log field."""
        self.log(outcome)
        return outcome

    def _recover_reset(self):
        """One bus reset when the host has it; False when it does not (never raised)."""
        reset = getattr(self.link, "reset", None)
        if reset is None:
            return False
        try:
            reset()
        except NotImplementedError:
            self.log("recover: the host has no bus reset, skipping")
            return False
        except Exception as error:
            self.log("recover: the bus reset failed (%s), continuing" % error)
            return False
        self.log("recover: bus reset done")
        return True

    def _recover_clear_halts(self):
        """Clear the halt on the firmware and stream endpoints; False when the host cannot."""
        clear = getattr(self.link, "clear_halt", None)
        if clear is None:
            return False
        cleared = 0
        for endpoint in (EP_FIRMWARE_OUT, EP_TS_IN):
            try:
                clear(endpoint)
                cleared += 1
            except NotImplementedError:
                self.log("recover: the host has no clear_halt, skipping")
                break
            except Exception as error:
                self.log("recover: clear_halt 0x%02x failed: %s" % (endpoint, error))
        if cleared:
            self.log("recover: cleared the halt on %d endpoint(s)" % cleared)
        return cleared > 0

    def tune(self, khz, drop_power=False, sweep=True):
        """Tune ``khz``: a captured session is replayed, anything else uses :meth:`tune_any`.

        ``sweep`` enables the mode/guard acquisition step when the captured replay does not lock.
        A capture is mux-specific, so a broadcaster on another mode or guard interval cannot lock
        from the replay alone; the sweep re-runs the search from the capture's channel block.
        """
        khz = int(khz)
        if self._cached_zap(khz):
            return True
        if not tune_module.has_session(khz):
            return self.tune_any(khz, drop_power=drop_power, sweep=sweep)
        session = tune_module.load_session(khz)
        if not session:
            self.log("tune %d kHz: no captured session for this frequency" % khz)
            return False
        records = session
        if drop_power:
            records = [r for r in session if r.get("request") not in (0x0B, 0x0C)]
            self.log("tune %d kHz: warm, dropped %d power-up records"
                     % (khz, len(session) - len(records)))
        before = len(records)
        records = tune_module.trim_at_output_enable(records)
        if len(records) != before:
            self.log("tune %d kHz: dropped the capture's close (%d records)"
                     % (khz, before - len(records)))
        self.log("tune %d kHz: replaying %d captured records" % (khz, len(records)))
        self.tune_summary = init_module.replay(self.link, records, self.log, verify=True)
        enable_video(self.link, True)
        self.khz = khz
        return self._settle_captured(khz, records, sweep)

    def _settle_captured(self, khz, records, sweep=True):
        """Take the lock after a captured replay; on failure acquire mode/guard (``sweep``).

        The lock word alone is not enough here: the DTA's 1-seg-A + 12-seg-B mux streams cleanly
        with layer A's MPEG bit clear (measured 2026-11-20), so the stream is the honest second
        test - 64 whole packets with no resync.  When neither holds, the capture's mux is not the
        broadcaster's and the acquisition sweep re-runs the search from the capture's own channel
        block.  With ``sweep=False`` (the base replay inside :meth:`tune_any`) this is exactly the
        old wait-for-lock behaviour.
        """
        deadline = time.time() + 2.0
        while time.time() < deadline:
            value = self.demod_word(LOCK_REGISTER)
            if value is not None and self._lock_word(value):
                self.log("tune %d kHz: lock 0x%04x" % (khz, value))
                self._cache_captured(khz, records)
                return True
            time.sleep(0.1)
        if not sweep:
            self.log("tune %d kHz: no lock" % khz)
            return False
        if self._stream_lock(timeout=1.0):
            self.log("tune %d kHz: lock from the stream" % khz)
            self._cache_captured(khz, records)
            return True
        # The probe just read the dead mux's bytes.  It was an acquisition attempt, not the
        # delivered stream, so its resyncs and any short run are dropped before the sweep starts
        # (the caller's sync-error count must describe the stream it is given).
        self.stream = TsStream()
        self._pending = bytearray()
        self.quality.reset()
        _, _, final = tune_module.split_tune(records)
        if final is None:
            self.log("tune %d kHz: no lock (the capture has no channel block to acquire from)"
                     % khz)
            return False
        self.log("tune %d kHz: the captured channel did not lock; acquiring mode/guard" % khz)
        if self.acquire(khz, records[final:]):
            return True
        self.log("tune %d kHz: no lock" % khz)
        return False

    def tune_any(self, khz, drop_power=False, sweep=True):
        """Tune any frequency in the tuner band, captured or not.

        Without a capture a captured frequency is brought up first (its session is a known-good
        demod state), then :meth:`retune` runs the tune procedure for ``khz``.  The nearest capture
        is tried first, but it is not always usable as a base: 599.143 is the cable-test session
        and never locks off air, so above ~550 MHz the nearest capture is dead and the tune falls
        back to the next capture until one locks.  Retuning from a base the caller already knows is
        locked skips the replay.  The base replay does not itself sweep (``sweep=False``): the
        target is another frequency, so the acquisition step belongs to the target, in
        :meth:`retune`.
        """
        khz = int(khz)
        if tune_module.has_session(khz):
            return self.tune(khz, drop_power=drop_power, sweep=sweep)
        try:
            tune_module.tuner_pll(khz)
        except ValueError as error:
            self.log("tune %d kHz: %s" % (khz, error))
            return False
        # A frequency this process already tuned: the cached burst (PLL + final block + verify)
        # replaces the whole procedure.  A captured target goes through tune() above, which has
        # the same check; this covers an uncaptured one that a retune filled in earlier.
        if self._cached_zap(khz):
            return True
        # Fast zap: if the current channel is itself a captured lock, retune straight from it -
        # no base replay, whatever the nearest capture is.  This is the scan chain's anchor rule
        # applied to a single channel change.
        if self.locked() and self.khz and tune_module.has_session(self.khz):
            self.log("tune %d kHz: retuning from the locked %d kHz channel" % (khz, self.khz))
            if self.retune(khz, self.khz, sweep=sweep):
                return True
            self.log("tune %d kHz: the locked channel did not retune; using a capture" % khz)
        candidates = tune_module.base_candidates(khz)
        for index, base in enumerate(candidates):
            if self.khz == base and self.locked():
                self.log("tune %d kHz: retuning from the locked %d kHz base" % (khz, base))
                return self.retune(khz, base, sweep=sweep)
            self.log("tune %d kHz: using the %d kHz capture as the base state" % (khz, base))
            if self.tune(base, drop_power=drop_power, sweep=False):
                return self.retune(khz, base, sweep=sweep)
            self.log("tune %d kHz: the %d kHz base did not lock%s"
                     % (khz, base,
                        ", trying the next capture" if index + 1 < len(candidates) else ""))
        self.log("tune %d kHz: no captured base state locks" % khz)
        return False

    def retune(self, khz, base=None, sweep=True):
        """Re-run the tune procedure for ``khz`` from a locked ``base`` state.

        This is the ported state machine: the base capture's tune preamble is replayed, the
        dib0090 calibration runs live (it branches on the ADC, so it cannot be replayed), the
        demod's autosearch is allowed to run, its TMCC answer is read back, and the layer words
        it describes are written into the base capture's final channel block.  The demod finds
        the channel; we do not guess it.

        When the search reports no usable channel - a broadcaster whose mode the captured search
        phase never tried - ``sweep`` re-runs the search over the twelve mode/guard combinations
        (:meth:`acquire`) and finishes from the first one that locks.
        """
        khz = int(khz)
        base = int(base or self.khz or tune_module.nearest(khz))
        # A retune to another frequency leaves the base frequency's demod/tuner state: its cached
        # block described a state the chip no longer holds, so it is dropped rather than trusted
        # blindly on a later visit.  The target's entry is re-derived too; ``_finish`` refreshes it
        # on a success and it stays gone on a failure.
        if base != khz:
            self.cache.pop(base, None)
        self.cache.pop(khz, None)
        records = tune_module.trim_at_output_enable(tune_module.load_session(base))
        plan = tune_module.retune_plan(records, khz)
        pll = plan["pll"]
        self.log("tune %d kHz: retuning from the %d kHz base (FBDiv 0x%04x, %d + %d + %d records)"
                 % (khz, base, pll[0x15], len(plan["head"]), len(plan["body"]),
                    len(plan["final"])))
        # The kernel closes the demod output (OUTMODE_HIGH_Z) before set_frontend, and the captured
        # tune phase expects that: its own output-mode writes only change regs 0x012b/0x012c.  Close
        # the gate and stop the TS engine first, or the tune runs on a streaming demod and the
        # tuner reads/writes collide with the bulk-in pipe.
        enable_video(self.link, False)
        self.link.control_out(0x03, 0, 0, bytes.fromhex("038005060000"))   # reg 1286 = 0
        # A base replay may have drained a few of its own packets while proving its lock; they
        # belong to the wrong frequency, so they are not served after the retune.
        self._pending = bytearray()
        self.quality.reset()
        self.tune_summary = init_module.replay(self.link, plan["head"], self.log, verify=False)
        tuner = tune_module.TunerBus(self.link, self.log)
        # A re-tune keeps the DC offset trims the base tune left behind and calibrates WBD and
        # temperature only, exactly like the kernel's calibration mask after the first tune.
        tune_module.tuner_calibrate(tuner, khz, pll, self.log, dc=False)
        summary = init_module.replay(self.link, plan["body"], self.log, verify=False)
        for key in ("writes", "reads", "stalls", "mismatches"):
            self.tune_summary[key] = self.tune_summary.get(key, 0) + summary.get(key, 0)
        self.tune_summary["tuner_writes"] = tuner.writes
        self.tune_summary["tuner_reads"] = tuner.reads
        self.log("tune %d kHz: %d tuner writes, %d tuner reads"
                 % (khz, tuner.writes, tuner.reads))

        decoded = None
        for attempt in (1, 2):
            if attempt > 1:
                self.log("tune %d kHz: re-asserting the search" % khz)
                self.link.control_out(0x03, 0, 0, bytes.fromhex("03800000e000"))
            found = self._wait_search()
            words = self._read_tmcc()
            decoded = tune_module.decode_tmcc(words)
            if found and tune_module.plausible(decoded):
                break
            self.log("tune %d kHz: autosearch %s, TMCC %s"
                     % (khz, "reported success" if found else "timed out",
                        "plausible" if tune_module.plausible(decoded) else "not plausible"))
        if decoded is not None and tune_module.plausible(decoded):
            if self._finish(khz, plan["final"], decoded):
                self.log("tune %d kHz: lock from the stream" % khz)
                return True
            self.log("tune %d kHz: the decoded channel did not lock" % khz)
        else:
            self.log("tune %d kHz: the demod did not report a usable channel" % khz)
        if not sweep:
            self.log("tune %d kHz: no lock" % khz)
            return False
        if self.acquire(khz, plan["final"]):
            return True
        self.log("tune %d kHz: no lock" % khz)
        return False

    def _finish(self, khz, final, decoded):
        """Patch a channel block from the decoded TMCC, replay it, stream and take the lock.

        ``patch_layer_words`` + ``patch_channel_config`` write what the demod reported: the three
        layer words, the mode, the guard interval and partial reception.  The channel-estimate
        words follow the highest constellation, and the stream-configuration words (the
        differential map, segment masks, estimate window and TMCC threshold) follow the layer
        table.  Success is the stream (a run of whole packets), not the lock word: the DTA's
        layer-B mux streams with layer A's MPEG lock bit clear.
        """
        values = tune_module.layer_words(decoded)
        self.log("tune %d kHz: TMCC mode %s guard %s partial %d layers %s -> 0x%04x 0x%04x 0x%04x"
                 % (khz, {1: "2K", 2: "4K", 3: "8K"}[decoded["mode"]],
                    "1/%d" % (32 // (2 ** decoded["guard"])), decoded["partial"],
                    [layer["segments"] for layer in decoded["layers"]],
                    values[0], values[1], values[2]))
        final = tune_module.patch_layer_words(final, values)
        # The final block also carries the demod's mode/guard/partial-reception setup, which follows
        # the channel the autosearch found, not the base capture's.  A target with another guard
        # interval (CH14 is 1/8) or without partial reception (527.143) decodes a TMCC but never
        # reaches MPEG lock until these words follow the decoded channel.
        final = tune_module.patch_channel_config(final, decoded)
        self.log("tune %d kHz: mode %s guard 1/%d partial %d sb %d"
                 % (khz, {1: "2K", 2: "4K", 3: "8K"}[decoded["mode"]],
                    32 // (2 ** decoded["guard"]), decoded["partial"], decoded["sb"]))
        # The channel-estimate words follow the constellation the demod reported, not the base
        # capture's: replaying the base's analog-FE/P_adp values on a mux with a different
        # highest constellation leaves the estimator mis-tuned (measured: 497 -> 479 loses
        # whole packets after RS, with no TEI).
        estimate = tune_module.channel_estimate_words(decoded)
        final = tune_module.patch_channel_estimate(final, estimate)
        self.log("tune %d kHz: channel estimate ana_gain %d, P_adp 0x%04x 0x%04x 0x%04x 0x%04x"
                 % (khz, estimate[116], estimate[215], estimate[216], estimate[217],
                    estimate[218]))
        # The stream-side channel words - the differential-segment map in register 0, the
        # segment-inhibit masks, the channel-estimation window and the TMCC threshold - are
        # derived from the decoded layer table too, not from the base capture's 1+12 shape.  A
        # block that keeps the base's values decodes the TMCC and then streams nothing on a 1+8+4
        # mux (measured 2026-11-20).  Sound-broadcasting channels are not derived: the kernel
        # takes a different block for them, so the base's is left alone.
        stream = tune_module.stream_config_words(decoded)
        if stream:
            final = tune_module.patch_words(final, stream)
            self.log("tune %d kHz: stream config seg_diff 0x%04x cfr 0x%04x/0x%04x "
                     "tmcc_pow 0x%04x"
                     % (khz, stream[0] & 0x1FFF, stream[222], stream[223], stream[290]))
        init_module.replay(self.link, final, self.log, verify=False)
        enable_video(self.link, True)
        self.khz = khz
        # The final block's last record opens the demod output FIFO; drain from that instant and
        # take the lock from the stream.  The kernel enables the output only after dib8000_tune
        # reports the lock, and the bridge arms its stream path when the feed starts, so on the
        # vendor path an enabled FIFO never waits for a reader.  (Measured: the loss that
        # remained after this change was not the drain but the base's channel-estimate words,
        # patched above.)
        locked = self._stream_lock()
        if locked:
            # Keep exactly what locked, and the TMCC it was built from, so the next visit to this
            # frequency can replay this block as a cached burst and verify it against the mux.
            self._cache_put(khz, final, decoded)
        return locked

    # ---- the zap cache -----------------------------------------------------------------------

    def clear_cache(self):
        """Drop every cached channel block (a recover, a cold bring-up, or ``--no-cache``)."""
        self.cache.clear()

    def _cache_put(self, khz, block, decoded):
        """Keep a tuned frequency's PLL words, final channel block and the TMCC it came from."""
        if not self.cache_enabled or not block or decoded is None:
            return
        if not tune_module.plausible(decoded):
            return
        try:
            pll = tune_module.tuner_pll(khz)
        except ValueError:
            return
        self.cache[int(khz)] = {"pll": dict(pll), "final": [dict(record) for record in block],
                                "decoded": decoded}
        self.log("zap %d: cached the channel block (%d records)" % (khz, len(block)))

    def _cache_captured(self, khz, records):
        """Cache a captured replay's final block, against the TMCC the locked demod now reports."""
        _, _, final = tune_module.split_tune(records)
        if final is None:
            return
        decoded = tune_module.decode_tmcc(self._read_tmcc())
        self._cache_put(khz, records[final:], decoded)

    def _cached_body(self, khz):
        """The demod's AGC/autosearch configuration a tune to ``khz`` runs before its final block.

        Measured on hardware (2026-11-20): the final channel block alone does not acquire a
        channel after a tuner move - 485 -> 479 left the lock word at 0x4000 with no packets, and
        a live tuner calibration did not change that.  The captured tune's *body* - the demod's
        own AGC and search configuration, ending in the ``0x0000 = 0x6000`` search trigger - is
        what starts the acquisition.  The cached burst replays it flat and waits for the search
        success bit; the cached final block then applies the channel it found.

        The body is part of the captured tune, not of the cache entry: a captured frequency uses
        its own capture, an uncaptured one the nearest capture (the same base ``retune`` would
        use).  It is demod-generic (no tuner state), so the base choice does not matter.
        """
        base = khz if tune_module.has_session(khz) else tune_module.nearest(khz)
        records = tune_module.trim_at_output_enable(tune_module.load_session(base))
        if not records:
            return []
        try:
            return tune_module.retune_plan(records, khz)["body"]
        except ValueError:
            return []

    def _close_output(self):
        """Close the demod output and stop the TS engine (the retune's precondition)."""
        enable_video(self.link, False)
        self.link.control_out(0x03, 0, 0, bytes.fromhex("038005060000"))

    def _cached_zap(self, khz):
        """Replay a cached channel block as a short burst; False means "run the full tune".

        The tuner is still on the previous channel, so the cached PLL block is written first (it
        moves the LO), then the captured tune's *body* (the demod AGC/search configuration ending
        in the search trigger - the final block alone does not acquire a channel, measured
        2026-11-20), then the cached final channel block and the output.  Success needs a lock
        (register or a short clean stream run) *and* the same mux from the TMCC.  A cached entry
        that fails is dropped, and the caller runs the full procedure, which refreshes the cache:
        a stale entry is never answered from.
        """
        entry = self.cache.get(khz) if self.cache_enabled else None
        if entry is None:
            return False
        try:
            pll = tune_module.tuner_pll(khz)
        except ValueError:
            self.cache.pop(khz, None)
            return False
        # The stored PLL words are the frequency check: an entry whose block was cached for
        # another frequency (or written before this guard existed) is not replayed.
        if entry.get("pll") != pll or "decoded" not in entry:
            self.cache.pop(khz, None)
            return False
        body = self._cached_body(khz)
        if not body:
            self.cache.pop(khz, None)
            self.log("zap %d: no tune body to acquire from; running the full tune" % khz)
            return False
        self.log("zap %d: cached burst (%d tuner words, %d body records, %d final records)"
                 % (khz, len(pll), len(body), len(entry["final"])))
        self._close_output()
        # Anything a previous wait left in ``_pending`` belongs to another frequency.
        self._pending = bytearray()
        self.quality.reset()
        bus = tune_module.TunerBus(self.link, self.log)
        tune_module.write_pll(bus, pll)
        # The body and the final block are fixed register scripts: replay them flat out.  The
        # capture's gaps are its own lock/status polls; ``_wait_found`` and ``_cached_verify`` do
        # the waiting instead.
        init_module.replay(self.link, body, self.log, verify=False, timed=False)
        found = self._wait_found(CACHED_SEARCH_TIMEOUT)
        self.log("zap %d: cached search %s" % (khz, "found" if found else "timed out"))
        if found:
            init_module.replay(self.link, entry["final"], self.log, verify=False, timed=False)
            enable_video(self.link, True)
            if self._cached_verify(khz, entry):
                self.khz = khz
                self.log("zap %d: cached burst" % khz)
                return True
        self.cache.pop(khz, None)
        self.log("zap %d: cached burst did not verify; running the full tune" % khz)
        # The burst was an attempt, not the delivered stream: drop its packets and resyncs, then
        # close the output again - the full path replays a captured tune, which expects that.
        self.stream = TsStream()
        self._close_output()
        self._pending = bytearray()
        self.quality.reset()
        return False

    def _cached_verify(self, khz, entry):
        """A cached burst worked when it locks and the TMCC still reports the cached mux.

        The TMCC registers are re-read for a short window: they update once per frame, so the
        first read after the output opens can still carry the mux the previous channel left.
        A genuinely different mux fails every read in the window and falls back.
        """
        if not self.locked() and not self._stream_lock(timeout=CACHED_LOCK_TIMEOUT):
            self.log("zap %d: no lock" % khz)
            return False
        deadline = time.time() + CACHED_TMCC_TIMEOUT
        last = None
        while True:
            decoded = tune_module.decode_tmcc(self._read_tmcc())
            if tune_module.plausible(decoded):
                last = decoded
                if tune_module.same_channel(decoded, entry["decoded"]):
                    return True
            if time.time() >= deadline:
                break
            time.sleep(CACHED_TMCC_POLL)
        if last is None:
            self.log("zap %d: no usable TMCC" % khz)
        else:
            self.log("zap %d: the TMCC reports another mux" % khz)
        return False

    def acquire(self, khz, block, combos=None, timeout=ACQUISITION_TIMEOUT):
        """The mode/guard acquisition sweep: find the channel on a tuned, searching demod.

        ``block`` is a channel block (a capture's final block).  The current search state is
        tested first - after a captured replay the demod may already have found the channel, only
        the replay's final block was for the capture's own mux.  Then each of the twelve
        ``combos`` patches the block's mode/guard/partial-reception words
        (:func:`tune.channel_config_records`), narrows the search to that guard interval
        (``P_search_param_select``), re-triggers the search and polls the autosearch IRQ
        (bit 1 = found, bit 0 = failed) and the lock word for ``timeout``.  The first candidate
        that reports a channel gets its TMCC read and the whole block finished from it
        (:meth:`_finish`), which is the packet-level lock test.  Returns True on a lock; after the
        last candidate it returns False, the caller's "no lock".
        """
        khz = int(khz)
        combos = list(combos or tune_module.acquisition_combos())
        self.log("tune %d kHz: acquiring mode/guard (%d candidates)" % (khz, len(combos)))
        if self._try_current(khz, block, "current search"):
            return True
        for index, combo in enumerate(combos, 1):
            label = "%s (%d/%d)" % (tune_module.combo_text(combo), index, len(combos))
            enable_video(self.link, False)
            self.link.control_out(0x03, 0, 0, bytes.fromhex("038005060000"))
            config = tune_module.channel_config_records(block, combo)
            if not config:
                self.log("tune %d kHz: the channel block has no mode/guard words" % khz)
                return False
            init_module.replay(self.link, config, self.log, verify=False)
            # Try only this candidate's guard interval: left at the capture's 0xf, every
            # candidate would search all four and the first mode would lock any mux.
            self._write_word(SEARCH_SELECT_REGISTER, 1 << (int(combo["guard"]) & 0x03))
            self._restart_search()
            if not self._wait_found(timeout):
                self.log("tune %d kHz: %s no lock" % (khz, label))
                continue
            decoded = tune_module.decode_tmcc(self._read_tmcc())
            if not tune_module.plausible(decoded):
                self.log("tune %d kHz: %s the search found no usable TMCC" % (khz, label))
                continue
            self.log("tune %d kHz: %s found %s" % (khz, label,
                                                   tune_module.combo_text(decoded)))
            if self._finish(khz, block, decoded):
                self.log("tune %d kHz: lock from the acquisition sweep at %s" % (khz, label))
                return True
            self.log("tune %d kHz: %s did not stream" % (khz, label))
        self.log("tune %d kHz: no combination locked (%d tried)" % (khz, len(combos)))
        return False

    def _try_current(self, khz, block, label):
        """Finish from the search the demod has already run, when its TMCC is usable."""
        decoded = tune_module.decode_tmcc(self._read_tmcc())
        if not tune_module.plausible(decoded):
            return False
        self.log("tune %d kHz: %s reported %s" % (khz, label, tune_module.combo_text(decoded)))
        if self._finish(khz, block, decoded):
            self.log("tune %d kHz: lock from the %s" % (khz, label))
            return True
        self.log("tune %d kHz: the %s did not lock" % (khz, label))
        return False

    def _stream_lock(self, timeout=2.0, packets=64):
        """Wait for lock in the stream, draining the bulk pipe from the first instant.

        Lock is a run of ``packets`` whole TS packets with no resync in between: the demod
        output is on before its demodulator has converged, so the first bytes are not usable
        and a register read is not evidence of anything.  Every byte read here is kept in
        ``_pending`` and served by :meth:`read_ts`, and fed to the quality meter exactly once.
        """
        deadline = time.time() + timeout
        baseline_errors = self.stream.sync_errors
        baseline_packets = self.stream.packets
        while time.time() < deadline:
            data = self.link.bulk_in(16384, 250)
            if not data:
                continue
            aligned = self.stream.feed(data)
            if aligned:
                self.quality.feed(aligned)
                self._pending += aligned
            if self.stream.sync_errors != baseline_errors:
                # a resync means this run is not a clean one: count from here instead
                baseline_errors = self.stream.sync_errors
                baseline_packets = self.stream.packets
                continue
            if aligned and self.stream.packets - baseline_packets >= packets:
                self.log("tune: %d packets in the first run, no resync"
                         % (self.stream.packets - baseline_packets))
                return True
        return False

    def _write_word(self, register, value):
        """One 16-bit demod register write: ``[0x03][0x80][reg:2][value:2]``."""
        value = int(value) & 0xFFFF
        self.link.control_out(0x03, 0, 0,
                              bytes([0x03, 0x80, (register >> 8) & 0xFF, register & 0xFF,
                                     value >> 8, value & 0xFF]))

    def _restart_search(self):
        """Start a search at the mode the channel block just wrote (register 0 bit 15).

        ``dib8000_autosearch_start`` writes ``(1 << 15) | P_mode`` to register 0, reads the IRQ
        register to clear the pending count, then writes the value back with bit 15 clear.  The
        capture's own trigger is that pair (``0x0000 = 0xe000`` then ``0x6000`` for 8K).
        """
        value = self.demod_word(0)
        if value is None:
            value = 0x6000                                   # 8K, the PH band's mode
        self._write_word(0, value | 0x8000)
        self.demod_word(SEARCH_IRQ_REGISTER)                 # clear the pending count
        self._write_word(0, value & ~0x8000)

    def _wait_found(self, timeout=ACQUISITION_TIMEOUT):
        """Wait for one acquisition attempt: a lock word or the autosearch success bit.

        The full lock word does not appear until the final channel block is replayed (the capture
        reads ``0x7d1e`` right after its search and ``0x7ede`` after the block), so bit 1 of
        ``0x0504`` - the demod's own "search succeeded" - is the signal to read the TMCC.  Bit 0
        ends the attempt early: the search failed for this candidate.
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            value = self.demod_word(LOCK_REGISTER)
            if value is not None and self._lock_word(value):
                return True
            irq = self.demod_word(SEARCH_IRQ_REGISTER)
            if irq is not None:
                if irq & 0x0002:
                    return True
                if irq & 0x0001:
                    return False
            time.sleep(ACQUISITION_POLL)
        return False

    def _wait_search(self, timeout=2.0):
        """Wait for dib8000_autosearch_irq: bit 1 of register 0x0504 means the search succeeded."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            value = self.demod_word(SEARCH_IRQ_REGISTER)
            if value is not None and value & 0x0002:
                return True
            if value is not None and value & 0x0001:
                return False
            time.sleep(0.1)
        return False

    def _read_tmcc(self):
        """The demod's channel report, as a register -> value map (missing reads are omitted)."""
        words = {}
        for register in tune_module.TMCC_REGISTERS:
            value = self.demod_word(register)
            if value is not None:
                words[register] = value
        return words

    def tmcc(self):
        """The decoded ISDB-T channel report (mode, guard, partial reception, the three layers)."""
        words = self._read_tmcc()
        decoded = tune_module.decode_tmcc(words)
        return {"words": words, "decoded": decoded, "plausible": tune_module.plausible(decoded)}

    # ---- demod reads: lock, strength, C/N -----------------------------------------------------

    def demod_word(self, register):
        """One 16-bit demod register, or None when the read fails."""
        try:
            reply = self.link.control_in(REQUEST_I2C_READ, I2C_READ_VALUE, register, 2)
        except Exception:
            return None
        if not reply or len(reply) < 2:
            return None
        return (reply[0] << 8) | reply[1]

    @staticmethod
    def _lock_word(value):
        """True when a lock word shows coff/cpil + FEC and an MPEG lock on any layer.

        ``dib8000_get_status`` sets FE_HAS_LOCK for bit 12 (FEC) with any of bits 7/6/5 (the MPEG
        lock of layer A/B/C); requiring layer A's bit 7 specifically rejected a layer-B mux.
        """
        return (value & LOCK_BITS) == LOCK_BITS and (value & LAYER_LOCK_BITS) != 0

    def locked(self):
        """dib8000_read_lock: coff/cpil and lmod4, FEC, and the MPEG lock of any layer."""
        value = self.demod_word(LOCK_REGISTER)
        return value is not None and self._lock_word(value)

    def strength_percent(self):
        """AGC strength as a percentage, from register 390; None when the read fails."""
        value = self.demod_word(STRENGTH_REGISTER)
        if value is None:
            return None
        raw = 65535 - value
        if raw < 0 or raw > 65535:
            return None
        return int(round(raw * 100.0 / 65535.0))

    def quality_percent(self):
        """Quality from the stream: 100 * clean packets / total, or None until a full window."""
        return self.quality.percent()

    # ---- streaming ---------------------------------------------------------------------------

    def read_ts(self, size=16384, timeout=300):
        """Next aligned TS bytes, or b"" when the read timed out.

        Packets drained while a retune waited for lock are served first; they were fed to the
        quality meter when they arrived, so they are not counted twice.
        """
        if self._pending:
            chunk = bytes(self._pending[:size])
            del self._pending[:size]
            return chunk
        data = self.link.bulk_in(size, timeout)
        if not data:
            return b""
        aligned = self.stream.feed(data)
        if aligned:
            self.quality.feed(aligned)
        return aligned

    def stop(self):
        enable_video(self.link, False)

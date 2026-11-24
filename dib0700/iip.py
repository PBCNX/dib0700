# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (c) 2026 The DIB0700 Open Driver Authors.
#
# Our own code, no vendor code.

"""ISDB-T Information Packet (IIP) — the frame-ahead transmission-control packet.

ARIB STD-B31 v2.2-E1 §5.5.3 (Tables 5-8 and 5-10/5-11). One TSP per multiplex frame, carried in the
*invalid hierarchy* (TMCC layer indicator ``'1000'``) and by none of layers A/B/C; the PID is service
provider defined (0x1FF0 here) and the payload is always 184 bytes:

```text
IIP_packet_pointer                              16  uimsbf
modulation_control_configuration_information   160  bslbf
IIP_branch_number                                8  uimsbf
last_IIP_branch_number                           8  uimsbf
network_synchronization_information_length       8  uimsbf
network_synchronization_information()           ...  bslbf, then 0xFF stuffing
```

The information describes the multiplex frame **after** the one that carries the packet. Unlike the
demod's TMCC block, the IIP carries the transmission **mode** and **guard interval** itself, and its
``TMCC_information`` block repeats the next frame's copy of the EWBS activation flag (B26), so the
alert flag is readable one frame early from the IIP. It carries no area code, descriptor or message.

``modulation_control_configuration_information`` is exactly 160 bits, and its 20 bytes are 16 bytes
of information followed by CRC_32 over those 16 bytes (the ISO/IEC 13818-1 polynomial with the
register initialised to 0xFFFFFFFF and no final inversion):

```text
TMCC_synchronization_word                   1   '0' = w0, '1' = w1 (STD-B31 Table 5-11)
AC_data_effective_position                  1
reserved                                    2
initialization_timing_indicator             4
current_mode                                2   01/10/11 = 2K/4K/8K
current_guard_interval                      2   00..11 = 1/32, 1/16, 1/8, 1/4
next_mode                                   2
next_guard_interval                         2
system_identifier                           2
count_down_Index                            4
switch-on_control_flag_used_for_alert_...   1   the next frame's B26
current_configuration_information:
    partial_reception_flag                  1
    layer A: modulation 3, code rate 3, interleave 3, segments 4   13
    layer B:                                                        13
    layer C:                                                        13
next_configuration_information:
    partial_reception_flag                  1
    layers A/B/C                            3 x 13 = 39
phase_correction_of_CP_in_connected_...     3
TMCC_reserved_future_use                   12
reserved_future_use                        10
CRC_32                                     32
```

**Conventions.** The IIP's code-rate field is the standard's 0-based index (0 = 1/2, 1 = 2/3,
2 = 3/4, 3 = 5/6, 4 = 7/8), unlike the DIB0700 demod's TMCC register, which the kernel reports 1-based
(`decode_tmcc`); the same broadcast reads code-rate 1 in the IIP and raw 2 in the demod register for
2/3. Interleave index 3 is the label **I4** (STD-B31 Table 3-30), as everywhere else in this tree.
A layer that carries nothing has modulation `111` and segments `1111`; the record keeps the raw
values and marks the layer not present, and the text line prints ``-``.

**Provenance.** Written from the field list in STD-B31, and this file is an independent
implementation (no code copied).

The measured vectors are 137 packets from an official EWBS broadcast: 8K / 1/16 / partial 1 /
L0 QPSK 1seg 2/3 I4 / L1 64QAM 12seg 3/4 I1, EWBS 0 in the first payloads and 1 in the later ones.
"""

IIP_PID = 0x1FF0
TS_PACKET = 188
PAYLOAD_BYTES = 184
MCCI_BYTES = 20
MCCI_BIT_COUNT = 8 * MCCI_BYTES

# STD-B31 v2.2-E1 Table 5-11 and Table 3-30.  The unknown sentinel is the display's, as in tmcc.
MODES = {1: "2K", 2: "4K", 3: "8K"}
GUARDS = {0: "1/32", 1: "1/16", 2: "1/8", 3: "1/4"}
MODULATIONS = {0: "DQPSK", 1: "QPSK", 2: "16QAM", 3: "64QAM"}
CODE_RATES = {0: "1/2", 1: "2/3", 2: "3/4", 3: "5/6", 4: "7/8"}
INTERLEAVES = {0: "I0", 1: "I1", 2: "I2", 3: "I4"}
UNKNOWN = "?"

UNUSED_MODULATION = 7           # modulation_scheme '111': the layer carries no data
UNUSED_SEGMENTS = 15            # number_of_segments '1111': same
MCCI_CRC_OFFSET = 16            # CRC_32 covers the first 16 bytes of the 160-bit block
CRC_INITIAL = 0xFFFFFFFF
CRC_POLYNOMIAL = 0x04C11DB7     # x^32 + x^26 + ... + 1, the ISO/IEC 13818-1 polynomial


class BitReader:
    """MSB-first bit reader for one byte string; the IIP block is 160 bits, not byte fields."""

    def __init__(self, data):
        self.data = bytes(data)
        self.position = 0

    def take(self, count):
        value = 0
        for _ in range(count):
            index = self.position
            value = (value << 1) | ((self.data[index >> 3] >> (7 - (index & 7))) & 1)
            self.position += 1
        return value


def crc32_mpeg(data):
    """CRC-32 as ISO/IEC 13818-1 uses it: init 0xFFFFFFFF, no reflection, no final inversion.

    STD-B31 Table 5-11 gives this polynomial for the IIP's CRC_32, over everything from
    ``TMCC_synchronization_word`` through ``reserved_future_use`` (16 bytes).  The captured payloads
    validate byte for byte, which is what pins the block length to 160 bits.
    """
    crc = CRC_INITIAL
    for byte in data:
        crc ^= byte << 24
        for _ in range(8):
            if crc & 0x80000000:
                crc = ((crc << 1) ^ CRC_POLYNOMIAL) & 0xFFFFFFFF
            else:
                crc = (crc << 1) & 0xFFFFFFFF
    return crc


def _layer(reader):
    """One ``transmission_parameters_for_layer_*`` block: 3 + 3 + 3 + 4 bits."""
    modulation = reader.take(3)
    fec = reader.take(3)
    interleaving = reader.take(3)
    segments = reader.take(4)
    unused = modulation == UNUSED_MODULATION and segments == UNUSED_SEGMENTS
    return {
        "present": not unused,
        "modulation": modulation,
        "fec": fec,
        "interleaving": interleaving,
        # A layer with no data carries '1111'; keep the segment count meaningful for present ones.
        "segments": 0 if unused else segments,
    }


def decode(payload):
    """Decode one 184-byte IIP payload (the bytes after the 4-byte TSP header).

    Returns the record as plain Python types, the shape ``decode_tmcc`` uses: raw field values
    (so nothing is lost), a ``present`` flag and the per-layer dicts, plus ``crc_ok``.  Labels are
    the display's job (:func:`text`, and the JSON keeps the raw numbers).
    """
    payload = bytes(payload)
    if len(payload) < 25:
        raise ValueError("IIP payload too short: %d bytes" % len(payload))
    pointer = (payload[0] << 8) | payload[1]
    block = payload[2:2 + MCCI_BYTES]

    reader = BitReader(block)
    synchronization_word = reader.take(1)
    ac_data_effective_position = reader.take(1)
    reader.take(2)                                  # reserved
    initialization_timing = reader.take(4)
    mode = reader.take(2)
    guard = reader.take(2)
    next_mode = reader.take(2)
    next_guard = reader.take(2)
    system_identifier = reader.take(2)
    count_down_index = reader.take(4)
    ewbs = reader.take(1)
    partial = reader.take(1)
    layers = [_layer(reader) for _ in range(3)]
    next_partial = reader.take(1)
    next_layers = [_layer(reader) for _ in range(3)]
    phase_correction = reader.take(3)
    tmcc_reserved = reader.take(12)
    reserved_future_use = reader.take(10)
    assert reader.position == 128, "MCCI information block is 128 bits"

    stored_crc = int.from_bytes(block[MCCI_CRC_OFFSET:MCCI_CRC_OFFSET + 4], "big")
    computed_crc = crc32_mpeg(block[:MCCI_CRC_OFFSET])

    branch = payload[22]
    last_branch = payload[23]
    net_sync_length = payload[24]
    following = payload[25:]
    net_sync = following[:min(net_sync_length, len(following))]
    stuffing = following[len(net_sync):]

    return {
        "pointer": pointer,
        "sync": synchronization_word,                # legal 0 or 1: STD-B31 w0/w1, not a constant
        "ac_data_effective_position": ac_data_effective_position,
        "initialization_timing": initialization_timing,
        "mode": mode,
        "guard": guard,
        "next_mode": next_mode,
        "next_guard": next_guard,
        "system_identifier": system_identifier,
        "count_down_index": count_down_index,
        "ewbs": ewbs,
        "partial": partial,
        "layers": layers,
        "next_partial": next_partial,
        "next_layers": next_layers,
        "phase_correction": phase_correction,
        "tmcc_reserved": tmcc_reserved,
        "reserved_future_use": reserved_future_use,
        "branch": branch,
        "last_branch": last_branch,
        "net_sync_length": net_sync_length,
        "crc": stored_crc,
        "crc_ok": stored_crc == computed_crc,
        "stuffing_ok": all(byte == 0xFF for byte in stuffing),
    }


def layer_text(layer):
    """``QPSK 2/3 I4 1seg`` or ``-`` for a layer that carries no data."""
    if not layer["present"]:
        return "-"
    return "%s %s %s %dseg" % (
        MODULATIONS.get(layer["modulation"], UNKNOWN),
        CODE_RATES.get(layer["fec"], UNKNOWN),
        INTERLEAVES.get(layer["interleaving"], UNKNOWN),
        layer["segments"])


def text(record):
    """The record as one line, the form the IT9308 line uses.

    ``iip mode 8K guard 1/16 partial 1 ewbs 0 L0:QPSK 2/3 I4 1seg L1:64QAM 3/4 I1 12seg L2:-``
    """
    layers = " ".join("L%d:%s" % (index, layer_text(layer))
                      for index, layer in enumerate(record["layers"]))
    return "iip mode %s guard %s partial %d ewbs %d %s" % (
        MODES.get(record["mode"], UNKNOWN),
        GUARDS.get(record["guard"], UNKNOWN),
        record["partial"], record["ewbs"], layers)


def _layer_key(layer):
    return (layer["present"], layer["modulation"], layer["fec"],
            layer["interleaving"], layer["segments"])


def configuration(record):
    """The decoded transmission configuration, without the per-packet fields.

    ``IIP_packet_pointer``, the branch numbers, the network-sync length and the CRC describe the
    packet, not the multiplex; ``TMCC_synchronization_word`` is the frame-synchronisation phase
    (STD-B31 Table 5-11 w0/w1), so two payloads that differ only in it are the same configuration.
    The IIP is frame-ahead: mode, guard and the layers here are the **next** frame's, while
    ``count_down_index`` and the initialization timing are this packet's switch state.
    """
    return (
        record["ac_data_effective_position"], record["initialization_timing"],
        record["mode"], record["guard"], record["next_mode"], record["next_guard"],
        record["system_identifier"], record["count_down_index"], record["ewbs"],
        record["partial"], tuple(_layer_key(layer) for layer in record["layers"]),
        record["next_partial"], tuple(_layer_key(layer) for layer in record["next_layers"]),
        record["phase_correction"],
    )


def payload(packet):
    """The payload of one 188-byte TS packet, or ``b""`` when it carries none.

    ``adaptation_field_control`` 01 is payload only, 10 the adaptation field only, 11 both - the
    payload then starts after ``adaptation_field_length`` bytes, which the IIP path must honour:
    the modulator is free to attach an adaptation field to the 0x1FF0 packet.
    """
    if len(packet) < TS_PACKET or packet[0] != 0x47:
        return b""
    control = (packet[3] >> 4) & 0x03
    if control == 1:
        return packet[4:TS_PACKET]
    if control == 3:
        start = 5 + packet[4]
        if start < TS_PACKET:
            return packet[start:TS_PACKET]
    return b""


def payloads(chunk):
    """Yield the payload of every IIP (PID 0x1FF0) packet in a chunk of aligned 188-byte packets."""
    for offset in range(0, len(chunk) - TS_PACKET + 1, TS_PACKET):
        packet = chunk[offset:offset + TS_PACKET]
        if packet[0] != 0x47:
            continue
        pid = ((packet[1] & 0x1F) << 8) | packet[2]
        if pid != IIP_PID:
            continue
        data = payload(packet)
        if data:
            yield data

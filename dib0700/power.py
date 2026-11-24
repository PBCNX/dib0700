# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (c) 2026 The DIB0700 Open Driver Authors.
#
# Our own code, no vendor code.

"""Power up the demod before the init replay.

The capture starts after probe, so it does not contain the bring-up that wakes
the demod: without it the chip does not answer I2C at all and every register
read comes back as 0x0000, which looks like "no signal" but is actually "no
power".  This mirrors ``stk809x_frontend_attach``:

    GPIO6=1, GPIO9=1, GPIO4=1, GPIO7=1, GPIO10=0, clock 72 MHz,
    GPIO10=1, GPIO0=1

GPIO numbers are the bridge's, not the port pins (``dib07x0.h``):
0, 2, 3, 4, 5, 6, 8, 10, 11, 14, 15.
"""
import time

from .link import REQUEST_SET_CLOCK, REQUEST_SET_GPIO, TIMEOUT_MS

GPIO = {
    0: 0, 1: 2, 2: 3, 3: 4, 4: 5, 5: 6, 6: 8, 7: 10, 8: 11, 9: 14, 10: 15,
}

GPIO_OUT = 1
GPIO_IN = 0


def set_gpio(link, gpio, value, direction=GPIO_OUT):
    """``REQUEST_SET_GPIO``: [0x0c][gpio][dir<<7 | val<<6]."""
    data = bytes([REQUEST_SET_GPIO, GPIO[gpio],
                  ((direction & 1) << 7) | ((value & 1) << 6)])
    return link.control_out(REQUEST_SET_GPIO, 0, 0, data)


def set_clock(link, mhz=72):
    """``REQUEST_SET_CLOCK``: enable the PLL for a 72 MHz demod clock.

    ``dib0700_ctrl_clock(d, 72, 1)`` maps to ``dib0700_set_clock(d, 1, 0, 1,
    1, 2, 24, 0, 0x4c)`` - bypass off, PLL range 1, clock on GPIO3, predivider
    2, loop divider 24.
    """
    if mhz != 72:
        raise ValueError("only the 72 MHz setting is known")
    data = bytes([REQUEST_SET_CLOCK, 0xB0, 0x00, 0x02, 0x00, 0x18,
                  0x00, 0x00, 0x00, 0x4C])
    return link.control_out(REQUEST_SET_CLOCK, 0, 0, data)


def power_up(link, settle=0.010):
    """Bring the demod out of reset; required before any register access."""
    set_gpio(link, 6, 1)
    time.sleep(settle)
    set_gpio(link, 9, 1)
    set_gpio(link, 4, 1)
    set_gpio(link, 7, 1)
    set_gpio(link, 10, 0)
    set_clock(link, 72)
    time.sleep(settle)
    set_gpio(link, 10, 1)
    time.sleep(settle)
    set_gpio(link, 0, 1)
    return True

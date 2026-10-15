# fbot — ESP-FBot at the pinned commit, with three fixes

A copy of `components/fbot` from [ESP-FBot](https://github.com/Ylianst/ESP-FBot)
at commit `2b22754e7691c081b403ed46c3c931136b7e4cec` — the commit
`nodes/van-core.yaml` used to pin from GitHub. Everything here is upstream's
code, byte for byte, except three blocks in `fbot.cpp` marked
`van-supervisor patch 1/2/3`. Apache-2.0, see `LICENSE` (copied from that
commit). Used by `nodes/van-core.yaml` since D-30 (2026-10-15).

All three were found on the laptop test rig (`tools/p310_sim.py` +
`nodes/van-core-pcsim*.yaml`, measurements.md M23) and are invisible on the
real P310 until something goes wrong.

## 1. Notifications were never switched on

ESP-FBot registered for notifications with the raw
`esp_ble_gattc_register_for_notify()` and reported itself established in the
same breath. On ESPHome 2026.8 that lets the BLE client release its GATT cache
before the registration completes, so the client cannot find the notify
characteristic's CCCD, logs

    REG_FOR_NOTIFY after services released, notifications not enabled

and never writes it. The fix goes through `BLEClientBase::register_for_notify()`,
which counts the request and keeps the cache until it completes — the path
ESPHome added for exactly this.

Why the van worked anyway — inference, not measured: the link has run for days
on the real P310 (M12: 21 h, zero drops), so the station must notify whether or
not it was asked to. A station that follows the GATT rules — like Windows —
gets no data at all.

## 2. A station that never answered was never timed out

The poll timeout only started counting after a first reply. A station silent
from the moment of connection — patch 1's failure, for instance — left ESP-FBot
"connected" with no data, forever. The clock now starts at the first poll.

## 3. A station that went quiet wedged the link until a reboot

After `max_poll_failures` ESP-FBot marked the station disconnected and stopped
polling, and only a BLE connect event marked it connected again. The link
itself was still up, so none ever came: every write was refused, including the
fail-safe's AC ON, until van-core rebooted. It now drops the link at that
point, and ESPHome's own reconnect restarts everything.

That makes the timeout ordering matter: ESP-FBot must give up only after the
arbiter's `link_stale` fail-safe has sent its ON command, or the command is
refused. The inequality is spelled out at `poll_timeout` in `van-core.yaml`.

## Before the van relies on it

Run the van build against the real P310 once: the boot log should show no
"notifications not enabled" line, and station data should flow. Patch 1 now
writes the CCCD, which the station has never been sent before.

To move to a newer upstream, diff it against this copy, re-apply the three
blocks, and re-run `nodes/van-core-pcsim-failsafe.yaml` on the rig.

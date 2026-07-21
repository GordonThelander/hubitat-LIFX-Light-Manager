# 1.5.9 correctness patch - live-hub test plan

Covers the four correctness fixes confirmed during external re-review of 1.5.8, as implemented on
the `dev` branch. Everything here runs against the **"(Dev)" app and drivers**, so there is no risk
to the production app or its child devices.

## Setup

1. Upload the updated `apps/LIFX_Light_Manager.groovy` to Hubitat (no driver files changed in this
   patch).
2. Confirm the app page subtitle reads `v1.5.9`.
3. Have at least one Tunable White device installed (for CT-01/02), and ideally one device you can
   delete from/re-add to LIFX Cloud (for LAN-03) - the same kind of test used for the 1.5.6 LAN-only
   verification.

---

## Tunable White colour-temperature preservation

| # | Test | Steps | Expected |
|---|------|-------|----------|
| CT-01 | Master colour command preserves Tunable White CT | Set a Tunable White bulb to a specific colour temperature (e.g. 2700K) directly, confirm it, then send a Master Switch colour command (e.g. "Red") that includes this bulb | The Tunable White bulb's colour temperature stays at 2700K - only brightness changes, no colour temperature reset to 3500K |
| CT-02 | Colour-capable bulbs still unaffected | Same Master Switch colour command as CT-01, check a colour-capable bulb in the same fleet | Receives the actual requested colour normally, no regression |

## LAN-only discovery reliability

| # | Test | Steps | Expected |
|---|------|-------|----------|
| LAN-01 | Discovery no longer skips broadcast on a fully-cached fleet | With all known devices already showing cached IPs (routine steady state), press Discovery | Phase message shows an actual broadcast/discovery run happening, not an instant "Saved device table already has IP addresses for all rows" skip |
| LAN-02 | Cloud-less device found reliably, not just opportunistically | Delete one device from LIFX Cloud (leaving others healthy, as in the earlier 1.5.6 test), leave it powered on the LAN, run Discovery **multiple times in a row** | The cloud-less device shows up in the Device preparation table consistently across repeated runs, not just intermittently |
| LAN-03 | No duplicate row/child when a LAN-only device rejoins Cloud | With a device currently tracked as LAN-only (and ideally already installed as a child from testing LAN-02), re-add it to LIFX Cloud, then run Discovery | The existing row is updated in place (Cloud metadata now populated) rather than a second row appearing for the same physical device; no duplicate child device gets created |
| LAN-04 | Normal cloud-led discovery timing not badly regressed | Time a routine Discovery press on the stable fleet | Should complete well within the existing "2-3 minutes" expectation - the guaranteed minimum broadcast window (~9s) should not be a noticeable regression |

## `durationMs()` overflow guard

| # | Test | Steps | Expected |
|---|------|-------|----------|
| DUR-01 | Normal durations still work | Send a colour/level command with a normal transition duration (e.g. 2 seconds) | Transition behaves as before, no regression |
| DUR-02 | Extreme duration value | Send a colour/level command with an absurd duration value if you can trigger one (e.g. via a custom Rule Machine action passing a huge number) | Clamps to a large-but-valid value instead of erroring or behaving unpredictably |

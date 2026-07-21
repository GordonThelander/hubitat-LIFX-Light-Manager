# 1.5.9-1.5.10 correctness patches - live-hub test plan

Covers the four correctness fixes confirmed during external re-review of 1.5.8 (1.5.9), plus a
per-bulb brightness fix found live while testing CT-01/CT-02 (1.5.10). Everything here runs against
the **"(Dev)" app and drivers**, so there is no risk to the production app or its child devices.

## Setup

1. Upload the updated `apps/LIFX_Light_Manager.groovy` to Hubitat (no driver files changed in this
   patch).
2. Confirm the app page subtitle reads `v1.5.10`.
3. Have at least one Tunable White device installed (for CT-01/02), ideally at least two devices at
   *different* brightness levels from each other (for LVL-01/02), and ideally one device you can
   delete from/re-add to LIFX Cloud (for LAN-03) - the same kind of test used for the 1.5.6 LAN-only
   verification.

---

## Tunable White colour-temperature preservation

| # | Test | Steps | Expected |
|---|------|-------|----------|
| CT-01 | Master colour command preserves Tunable White CT | Set a Tunable White bulb to a specific colour temperature (e.g. 2700K) directly, confirm it, then send a Master Switch colour command (e.g. "Red") that includes this bulb | The Tunable White bulb's colour temperature stays at 2700K, and (as of 1.5.10) its brightness stays unchanged too - see LVL-01/02 below, this was found broken during this exact test |
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

## Per-bulb brightness preservation (1.5.10, found live testing CT-01/02)

`sendBulkSetColorOrLevel()`/`sendBulkSetColorTemperature()` were applying the Master Switch's own
`currentValue('level')` uniformly to every bulb, falling back to a hardcoded 75% when that was
null/stale - a Master colour or CT command could silently drop every bulb to 75% brightness
regardless of what each bulb was actually set to. Fixed to preserve each bulb's own current level.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| LVL-01 | Master colour command preserves per-bulb brightness | Set two+ bulbs to visibly different brightness levels (e.g. 30% and 90%), then send a Master Switch colour command that includes both | Each bulb keeps its own brightness - no snapping to a shared value, and specifically no drop to 75% |
| LVL-02 | Master colour-temperature command preserves per-bulb brightness | Same setup as LVL-01, but send a Master "Set Colour Temperature" command instead | Same result - each bulb's brightness stays at its own level, not a shared/defaulted one |
| LVL-03 | Fresh/never-synced bulb still gets a sane brightness | If you can get a bulb into a state where it has never reported a level (e.g. right after creation, before any refresh), send it a Master colour command | Falls back sensibly (to the Master's own last-known level) rather than erroring - this is the one case where the old 75%-style fallback is still expected to apply, just per-bulb instead of fleet-wide |

# 1.5.9-1.5.13 correctness patches - live-hub test plan

Covers the four correctness fixes confirmed during external re-review of 1.5.8 (1.5.9), a per-bulb
Master Switch brightness fix found live while testing CT-01/CT-02 (1.5.10), the same brightness
problem fixed for individual bulbs' own colour picker (1.5.11), a Breathe/Pulse switch-state fix
found live testing (1.5.12), and Off now cancelling an active Breathe/Pulse effect instead of
letting it resume (1.5.13). Everything here runs against the **"(Dev)" app and drivers**, so there
is no risk to the production app or its child devices.

## Setup

1. Upload the updated `apps/LIFX_Light_Manager.groovy` to Hubitat.
2. **1.5.13 also changes two driver files** - unlike 1.5.9-1.5.12, this release is not app-file-only.
   Upload `drivers/LIFX_Local_Colour_Driver.groovy` and `drivers/LIFX_Local_Plus_Colour_Driver.groovy`
   too. The other three driver files (White Mono, Tunable White, Master Switch) are unchanged.
3. Confirm the app page subtitle reads `v1.5.13`.
4. Have at least one Tunable White device installed (for CT-01/02), ideally at least two devices at
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

## Individual bulb brightness preservation via the built-in colour picker (1.5.11, found live testing LVL)

`childSetColor()` trusted an incoming colour map's `level`/`brightness` field directly. Hubitat's
own built-in "Choose a colour" picker bundles a swatch-specific level with every colour tap (red
85%, purple 41%, green 58% observed live), so simply picking a colour on an individual device
silently changed its brightness. Fixed to preserve the device's own current level, same principle
as the Master Switch fix in 1.5.10. Deliberate trade-off: a `setColor` call that explicitly wants to
set colour and level together in one call now has its level ignored - use a separate `setLevel`
call instead.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| PICK-01 | Colour picker no longer changes brightness | Set an individual bulb to a specific brightness (e.g. 51%), then use its own "Set Color" command's built-in colour-swatch picker to choose a new colour | Brightness stays at 51% - the picker's own bundled level (e.g. 75%, 85%, whatever that swatch carries) is not applied |
| PICK-02 | Colour still changes correctly | Same test as PICK-01 | The new hue/saturation from the picker is still applied correctly - only the level is being overridden/preserved |
| PICK-03 | `setHue`/`setSaturation` unaffected | Use the individual hue-only or saturation-only commands | No change in behaviour - these already preserved level before this fix and still do |

## Breathe/Pulse switch state (1.5.12, found live)

`runColorEffect()` sent a real LIFX power-on packet when starting an effect but never published a
Hubitat event for it - switch/hue/saturation/level/colorMode all stayed stale (switch showing "off")
even though the bulb was genuinely on and breathing/pulsing. Also confirmed (not a bug, LIFX
protocol behaviour): `SET_POWER` off does not cancel an active waveform effect on the bulb - only a
real `SET_COLOR` does, which is why changing level/colour stops Breathe/Pulse but a plain Off then
On does not (the effect resumes once power is restored). Not addressed in this release - see
`BACKLOG.md` for the open question of whether Off should also explicitly cancel an active effect.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| BRTH-01 | Switch attribute updates when a Breathe/Pulse effect starts | Trigger `breathe()` or `pulse()` on a device that's currently off | Hubitat's `switch` attribute shows "on" (and hue/saturation/level/colorMode reflect the base colour), not stuck on "off" |
| BRTH-02 | Master Switch reflects the change | Same as BRTH-01, then check the Master Switch's own aggregate state | Master Switch's switch state updates to reflect this member now being on |
| BRTH-03 | Off then On resumed the effect in 1.5.12 (superseded by RESET-01/02 below in 1.5.13) | Start a Breathe/Pulse effect, turn the device Off, then On again | As of 1.5.12 the effect resumed automatically - this was expected LIFX protocol behaviour at the time; 1.5.13 changes this, see below |
| BRTH-04 | Setting level or colour still stops the effect | Start a Breathe/Pulse effect, then change level or set a plain colour | Effect stops, light shows the requested level/colour - unchanged from before |

## Off cancels an active Breathe/Pulse effect (1.5.13, Gordon's proposed fix)

`SET_POWER` off never cancelled an active `SET_WAVEFORM` effect on the bulb (confirmed LIFX
protocol behaviour, see 1.5.12 notes above) - only a real `SET_COLOR` command does. `runColorEffect()`
now sets an `effectActive` device data flag when starting an effect; `childOff()`/`sendBulkSetPower()`
(app-level) and the individual driver's own `fastPower()` (device-level fast path, used by the
physical device tile, Dashboard, Google Home, and most Rule Machine actions) all check the flag on
off and, if set, send a real `SET_COLOR` reset to 75%/3000K before the power-off packet, instead of
just `SET_POWER`. Conditional on the flag, not unconditional - a bulb sitting at a normal custom
colour that was never running an effect keeps that colour through an ordinary off/on cycle.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| RESET-01 | Off then On no longer resumes the effect, triggered from the app | Start `breathe()` or `pulse()` on an individual device via the app/a Rule Machine custom action, then send `off()` via the same app-level path, then `on()` | Light comes back at a plain 75%/3000K, not breathing/pulsing |
| RESET-02 | Off then On no longer resumes the effect, triggered from the device's own tile/Dashboard | Same as RESET-01, but turn the device off using its own Hubitat device page toggle, a Dashboard tile, or Google Home - not an app-triggered command | Same result - light comes back at 75%/3000K, not resumed. This specifically exercises the driver-level `fastPower()` fix, which is the path most physical off-switch interactions actually take |
| RESET-03 | Master Switch bulk off also cancels effects | Start an effect on one member bulb, then turn the whole fleet off via the Master Switch, then back on individually | The bulb that was breathing/pulsing comes back at 75%/3000K, not resumed; other bulbs unaffected |
| RESET-04 | Normal colour is preserved through an ordinary off/on cycle (no regression) | Set a bulb to a specific custom colour (not via an effect), turn it off, turn it back on | Bulb returns to the same colour it was set to - the reset-on-off behaviour only triggers when an effect was actually active |

# Outstanding live-hub tests

Everything through 1.5.14 (CT-01/02, LAN-01-04, DUR-01/02, LVL-01-03, PICK-01-03, RESET-01/02/03/04,
BRTH-01/02, EFFCLR-01/02/03/04) is confirmed live and tracked in `BACKLOG.md` under "Fixed, pending
backport to main" - not repeated here.

## Setup

1. Confirm the app page subtitle reads `v1.5.17`.
2. **1.5.17 requires re-uploading all four local driver files** - White Mono, Tunable White, Colour
   and Plus Colour all changed (Master Switch is unchanged). Not app-file-only.

## Comment/dead-code cleanup (1.5.17, no functional change)

Removed the accumulating detailed changelog blocks from the app header and README (duplicated in
git log/BACKLOG.md), obsolete version-tagged comments, and 11 confirmed-dead functions with zero
call sites anywhere. No behaviour was intentionally changed - verified via `groovyc` compile-check
across the app and all five driver files together, and every removed function was individually
confirmed to have no callers before deletion. No new test cases; a basic smoke-test after upload
(app page loads and renders, a routine on/off/colour command still works) is enough to catch
anything the compile-check and dead-code analysis couldn't.

## colorName stays accurate instead of frozen at its device-init default (1.5.15, still not yet confirmed)

`colorName` was set once to "Soft White" at device creation and never updated again by any command
handler, regardless of what colour the bulb was actually set to. Now derived from the device's
hue/saturation/colorTemperature (nearest match against the same 11 named colours already used for
Breathe/Pulse) and republished alongside every other colour event - including the response handler
that reconciles state after a colour change made outside Hubitat entirely (LIFX app, physical
control), not just app-triggered commands.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| COLORNAME-01 | Setting a colour updates colorName to match | Use an individual bulb's colour picker to set a clearly non-white colour (e.g. a saturated red or blue) | `colorName` updates to a matching name (e.g. "Red"/"Blue") instead of staying on whatever it showed before |
| COLORNAME-02 | Setting colour temperature updates colorName to a White-family name | Set colour temperature only (no saturation) to something like 2700K vs 6500K | `colorName` reflects a White-family name that tracks the requested kelvin (e.g. closer to "Soft White" at 2700K, closer to "Daylight" at 6500K) - not stuck on one fixed value regardless of kelvin |
| COLORNAME-03 | Starting a Breathe/Pulse effect updates colorName to the base colour | Trigger `breathe()` or `pulse()` with a specific base colour | `colorName` matches the effect's base colour, not left over from whatever the bulb showed before the effect started |
| COLORNAME-04 | A colour change made outside the app is also reconciled | Change a bulb's colour directly from the LIFX app (or another controller), then trigger a refresh/poll in Hubitat | `colorName` updates to match the bulb's actual reported colour, not just events sent by this app's own commands |

## Per-device configurable defaults (1.5.16, still not yet confirmed)

Every local driver now has its own "Default level"/"Default colour temperature" preferences (White
Mono: level only) and an Apply Default command, same pattern as the Master Switch's existing default.
More importantly, the reset sent when Off cancels an active Breathe/Pulse effect now reads each
device's own configured default (via `device.getSetting(...)` on the app side, or the driver's own
local preference values on the fast on/off path) instead of one hardcoded 75%/3000K shared by the
whole fleet. DEFAULT-04/05/06 are the important ones - they're the only way to confirm
`device.getSetting()` actually reads a child device's preference correctly from the app, which
can't be verified by a compile check.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| DEFAULT-01 | Apply Default works on a colour-capable device | Set a Colour or Plus Colour device's "Default level"/"Default colour temperature" preferences to specific values (Save Preferences), then run its Apply Default command | Bulb goes to exactly that level and colour temperature |
| DEFAULT-02 | Apply Default works on Tunable White | Same as DEFAULT-01 on a Tunable White device | Bulb goes to the configured level and colour temperature |
| DEFAULT-03 | Apply Default works on White Mono | Set a White Mono device's "Default level" preference, run Apply Default | Bulb goes to that level (no colour temperature option shown, since Mono has none) |
| DEFAULT-04 | Off-cancels-effect uses the device's own customised default, not the old fixed 75%/3000K | Set a Colour/Plus Colour device's default to something clearly different (e.g. 40%/5000K), start `breathe()` or `pulse()`, then turn it off via the app | Light comes back at 40%/5000K on next on, not 75%/3000K |
| DEFAULT-05 | Same, triggered from the device's own tile/Dashboard | Same setup as DEFAULT-04, but turn off using the device's own physical toggle/Dashboard/Google Home instead of an app-triggered command | Same result - confirms the driver's own fast on/off path reads its local preference correctly, not just the app path |
| DEFAULT-06 | Same, via the Master Switch | Same setup as DEFAULT-04, but turn the whole fleet off via the Master Switch | The bulb that was breathing/pulsing comes back at its own customised default, not the fixed 75%/3000K and not another bulb's default |

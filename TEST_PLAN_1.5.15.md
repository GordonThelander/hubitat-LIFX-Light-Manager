# Outstanding live-hub tests

Everything through 1.5.14 (CT-01/02, LAN-01-04, DUR-01/02, LVL-01-03, PICK-01-03, RESET-01/02/03/04,
BRTH-01/02, EFFCLR-01/02/03/04) is confirmed live and tracked in `BACKLOG.md` under "Fixed, pending
backport to main" - not repeated here.

## Setup

1. Confirm the app page subtitle reads `v1.5.15`.
2. **1.5.15 is app-file-only** - no driver changes.

## colorName stays accurate instead of frozen at its device-init default (1.5.15, found live via a screenshot)

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

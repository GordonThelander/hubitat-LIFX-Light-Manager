# Outstanding live-hub tests

Everything else from the 1.5.9-1.5.13 patch run (CT-01/02, LAN-01-04, DUR-01/02, LVL-01-03,
PICK-01-03, RESET-01/02/03/04, BRTH-01/02) is confirmed live and tracked in `BACKLOG.md` under
"Fixed, pending backport to main" - not repeated here. This file only lists what's still unconfirmed.

## Setup

1. Confirm the app page subtitle reads `v1.5.14`.
2. **1.5.14 is app-file-only** - no driver changes this time, unlike 1.5.13.

## Level/colour/CT commands correctly cancel an active effect (1.5.14, found live testing BRTH-03)

Found live: touching level while a Breathe/Pulse effect was running froze the light on a stale
colour instead of the requested level. Root cause: only Off ever cleared the `effectActive` flag, so
(a) every other command that sends a real SET_COLOR left the flag stuck on `true`, and (b)
`childSetLevel()` specifically replayed the device's cached hue/saturation/colorTemperature to
preserve colour through a brightness-only change - but that cache is frozen at the effect's base
colour from whenever it started, not the bulb's live position. Both fixed: every colour/level/CT
command now clears the flag, and `childSetLevel()`/the Master Switch's level command fall back to
the same defined default (3000K/75%) instead of the stale cached colour when an effect was active.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| EFFCLR-01 | Touching level during an effect no longer freezes on a stale colour | Start `breathe()` or `pulse()` on an individual device, then change its level | Effect stops and the light shows a plain 3000K white at the requested level - not frozen on the effect's base colour |
| EFFCLR-02 | Stopping an effect via level doesn't cause a surprise reset later | Immediately after EFFCLR-01, turn the device off then on again | Light comes back exactly as EFFCLR-01 left it (same level, plain white) - it must NOT get force-reset to 75%/3000K again, which would mean the `effectActive` flag was left stuck on |
| EFFCLR-03 | Stopping an effect via a colour/CT command behaves the same way | Start an effect, then use Set Color or Set Color Temperature instead of touching level | Effect stops and shows the newly requested colour/CT correctly; turning off then on afterward does not trigger an unexpected reset |
| EFFCLR-04 | Same checks via the Master Switch | Start an effect on one member bulb, then adjust level (or colour/CT) via the Master Switch | That member's effect stops correctly (plain default colour if via level, requested colour if via colour/CT) and a later off/on doesn't cause a surprise reset; other bulbs unaffected |

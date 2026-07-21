# Outstanding live-hub tests

Everything through 1.5.17 (CT-01/02, LAN-01-04, DUR-01/02, LVL-01-03, PICK-01-03, RESET-01/02/03/04,
BRTH-01/02, EFFCLR-01/02/03/04, COLORNAME-01/02/03/04, DEFAULT-01 through DEFAULT-06), 1.5.18/1.5.19
(SCROLL-01/02/03, CLEANUP-01/02/03), and 1.6.1's Batch 1 GPT fixes (GPT01-01/02, GPT04-01/02, GPT11-01)
are all confirmed live and tracked in `BACKLOG.md` - not repeated here.

## Setup

1. Confirm the app page subtitle reads `v1.6.2`.
2. App-file-only - no driver changes since 1.5.17.

## Batch 2 of the external ChatGPT review fixes (1.6.2, not yet confirmed)

Two independently-verified findings from the same external review:

- **GPT-03** - `runColorEffect()` only sent power-on when the effect's base brightness was above 0%,
  so a Breathe/Pulse meant to fade in *from* off (0% base, brighter target) never actually powered
  the bulb on and stayed dark the whole cycle. The optimistic `switch` event was also derived purely
  from base brightness, so a light already on before the effect started could incorrectly show
  `switch: off` in Hubitat while genuinely animating. Fixed: power-on now fires when either endpoint
  is above 0%, and the `switch` event also considers the device's prior state.
- **GPT-08** - an unrecognised LIFX product ID defaulted to full colour+CT capability instead of the
  conservative fallback, because `driverModeForProduct()`'s default string happened to satisfy
  `driverTypeForRow()`'s colour-detection substring match. Fixed: the default now returns `"Unknown"`,
  which correctly falls through to `driverTypeForRow()`'s own conservative fallback (White Mono).

| # | Test | Steps | Expected |
|---|------|-------|----------|
| GPT03-01 | Effect fades in from off | Start a Breathe or Pulse with base brightness 0% and target brightness 80%+ on a bulb that's currently off | The bulb visibly powers on and animates between the two brightness levels, instead of staying dark |
| GPT03-02 | Switch state stays "on" for an already-on bulb | Turn a bulb on first, then start a Breathe/Pulse effect with base brightness 0% | The device's `switch` attribute in Hubitat stays `on` throughout the effect, not `off` |
| GPT08-01 | Code inspection only - no live device exercises this path | N/A - every product ID Gordon's fleet actually uses is already mapped in both `driverModeForProduct()` and `driverTypeForRow()`'s own lists, so the changed fallback is unreachable in normal live testing | Confirmed via compile-check and logic trace: `driverModeForProduct()`'s new `"Unknown"` default does not match any of `driverTypeForRow()`'s `hasRealColour`/`ctOnly` substrings, so it correctly falls through to the White Mono fallback |

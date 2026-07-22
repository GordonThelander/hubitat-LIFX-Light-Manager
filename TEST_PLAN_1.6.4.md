# Outstanding live-hub tests

Everything through 1.5.17 (CT-01/02, LAN-01-04, DUR-01/02, LVL-01-03, PICK-01-03, RESET-01/02/03/04,
BRTH-01/02, EFFCLR-01/02/03/04, COLORNAME-01/02/03/04, DEFAULT-01 through DEFAULT-06), 1.5.18/1.5.19
(SCROLL-01/02/03, CLEANUP-01/02/03), and 1.6.1-1.6.2's Batch 1/2 GPT fixes (GPT01-01/02, GPT03-01/02,
GPT04-01/02, GPT08-01, GPT11-01) are all confirmed live and tracked in `BACKLOG.md` - not repeated here.

## Setup

1. Confirm the app page subtitle reads `v1.6.4`.
2. App-file-only - no driver changes since 1.6.3 (which already needed all four local drivers
   re-uploaded; if you did that for 1.6.3, nothing further to re-upload for drivers here).

## WiFi Signal column mislabelling cached dBm readings as "(signal quality)" (1.6.4, not yet confirmed)

Found live: right after upgrading to 1.6.3, several devices' WiFi Signal column showed values like
`-48 (signal quality)` - a negative number should never carry that label, only LIFX's alternate
positive-value encoding should. Root cause: `wifiRssiKind` didn't exist before 1.6.3, so any row
whose `wifiRssi` was cached by pre-1.6.3 code had no `kind` recorded, and the display logic required
an exact `"dbm"` match to show the `dBm` suffix - anything else, including a missing field, fell
through to `"(signal quality)"`. Fixed: the display now defaults to `dBm` unless the row is
explicitly flagged `"quality"`, so old cached values (all genuinely dBm) display correctly again
without needing a fresh check.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| WIFIKIND-01 | Old cached WiFi readings display as dBm again | Open the Device preparation table without re-running a WiFi check on every device | Every row shows `"-XX dBm"` like before 1.6.3 - none show `"(signal quality)"` unless a fresh check on that specific device genuinely reported the alternate encoding |
| WIFIKIND-02 | A fresh check still classifies correctly | Run "Check WiFi signal" against a few real bulbs | Values still show as `"-XX dBm"` - every real device in the fleet reports genuine dBm, so `"(signal quality)"` shouldn't appear for any of them |

## Batch 3 of the external ChatGPT review fixes (1.6.3, not yet confirmed)

Four independently-verified findings from the same external review:

- **GPT-05** - the LIFX-advertised UDP service port was parsed but discarded; every device was
  hardcoded to port 56700. Fixed: `STATE_SERVICE` responses are now actually parsed
  (`parseStateServicePayload()`), and a genuinely-advertised UDP port overrides the 56700 default.
  Every real LIFX device advertises 56700 in practice, so this is a spec-correctness fix rather than
  a behaviour change for any device Gordon owns - the live test below just confirms no regression.
- **GPT-07** - WiFi signal was always labelled dBm using one formula, with no handling for LIFX's
  documented `200` "no signal" sentinel or the alternate positive-value quality-band encoding some
  generations use. Fixed: `parseWifiInfoPayload()` now classifies the result into dBm / no-signal /
  quality-band, and only genuine dBm readings get the `dBm` unit label and suffix.
- **GPT-09** - a device removed from LIFX Cloud was never reconciled out of the saved table, staying
  counted as "expected" indefinitely. Fixed: `mergeCloudIntoCurated()` now marks any previously
  cloud-backed row that's absent from a successful Cloud fetch as `cloudMissing`, excluded from the
  expected/discovered counts - without touching `row.origin` or deleting the row, so an installed
  child device survives exactly like every other reconciliation path already preserves it.
- **GPT-10** - subnet discovery assumed a /24 network with no way to override it, since Hubitat's app
  API doesn't expose the hub's actual subnet mask. Fixed: new optional "Subnet prefix override"
  preference (Advanced section) - blank behaves identically to today's auto-detection.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| GPT05-01 | No regression in normal discovery/control | Run Discovery and confirm all devices are found, controllable, and the Device preparation table's IP/status look normal | No change from prior behaviour - every device still uses port 56700 since that's what they all advertise |
| GPT07-01 | WiFi signal check still shows a sane dBm value | Run "Check WiFi signal" against real bulbs | Displayed value in the Device preparation table looks the same as before (e.g. "-52 dBm"), no regression in the normal case |
| GPT09-01 | Code inspection only - no easy way to trigger live | N/A - would require actually removing a device from the LIFX Cloud account, which isn't worth doing just to test this. Confirmed via logic trace: the reconciliation pass only runs after a genuine 2xx Cloud response, only flags rows already marked `origin: cloud`, and never touches `row.origin` or deletes the row | If Gordon is willing to test this for real at some point: temporarily unlink a bulb from the LIFX account, run Discovery, confirm that device's row shows "No longer in LIFX Cloud" instead of silently staying "expected" forever, and that the other devices are unaffected |
| GPT10-01 | Override left blank behaves identically | Leave "Subnet prefix override" blank, run Discovery | Identical behaviour to before - same subnet auto-detected, same devices found |
| GPT10-02 | A correctly-entered override doesn't break anything | Enter your hub's actual subnet prefix explicitly (e.g. `192.168.1.` if that's what auto-detection already uses) | Discovery behaves identically to leaving it blank |
| GPT10-03 | A malformed override falls back gracefully | Enter something that isn't a valid subnet prefix (e.g. `notanip`) | Discovery still works using the auto-detected subnet - check Logs for a warning about the invalid override, nothing breaks |

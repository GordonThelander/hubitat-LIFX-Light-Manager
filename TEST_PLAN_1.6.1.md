# Outstanding live-hub tests

Everything through 1.5.17 (CT-01/02, LAN-01-04, DUR-01/02, LVL-01-03, PICK-01-03, RESET-01/02/03/04,
BRTH-01/02, EFFCLR-01/02/03/04, COLORNAME-01/02/03/04, DEFAULT-01 through DEFAULT-06) is confirmed
live and tracked in `BACKLOG.md` under "Fixed, pending backport to main" - not repeated here.

## Setup

1. Confirm the app page subtitle reads `v1.6.1`.
2. App-file-only - no driver changes since 1.5.17.

## Mobile: device tables now scroll horizontally (originally 1.5.19) - confirmed live 2026-07-21

SCROLL-01/02/03 all pass. Moved to `BACKLOG.md` under "Backported to main".

## Removed the unreachable "rename an installed child device" feature (originally 1.5.18, still not yet confirmed)

This backend was never reachable through the UI (the render call was missing), so removing it
can't regress anything a user was actually doing. The only thing worth confirming is that nothing
else broke on the way out.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| CLEANUP-01 | App still loads and renders normally | Open the app's main page after upload | No errors, page renders exactly as before - no "Child device rename" section ever appeared, so nothing should look different |
| CLEANUP-02 | Renaming a discovered/installed light still works | Use the existing "Optional alternative local name" field next to a light in the Device preparation table (the one shown in the screenshot), then click Apply local names | Still renames the light correctly, both the row and (if already installed) the live Hubitat device label |
| CLEANUP-03 | Clear saved discovery data still works normally | Click "Clear saved discovery data" | Behaves as before - installed devices unaffected, table clears. (This is the one scenario the removed feature would have handled differently; confirms losing that edge case isn't otherwise disruptive.) |

## Batch 1 of the external ChatGPT review fixes (1.6.1, not yet confirmed)

Three independently-verified findings, all low-risk/mechanical: a zero-value default bug
(`deviceDefaultLevel()`/`deviceDefaultKelvin()` treated an explicit 0 as "unset" and silently
substituted 75%/3000K), incomplete cleanup in "Clear saved discovery data" (a stale WiFi/background
maintenance message could linger, and a pending firmware/WiFi resend timer wasn't cancelled), and
the Master Switch's aggregate `switch` attribute never actually being recomputed in one of its own
refresh paths.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| GPT01-01 | Default level of 0% is respected | Set a Colour/Plus Colour device's "Default level" preference to 0, start a Breathe/Pulse effect, then turn it off | Bulb goes to 0% (effectively off/dark at that colour temperature) on next on, not 75% |
| GPT01-02 | Default colour temperature of a low/edge value is respected | Set "Default colour temperature" to its minimum allowed value, repeat the same off-cancels-effect test | Bulb comes back at the configured value, not the 3000K fallback |
| GPT11-01 | Clear Data fully resets maintenance state | Trigger a Firmware check or WiFi signal check (so a result banner and a pending resend timer both exist), then immediately click "Clear saved discovery data" | No stale WiFi/firmware/background-maintenance result banner appears on the next page load; re-running Discovery afterward doesn't show any leftover resend activity against the freshly repopulated table |
| GPT04-01 | Master Switch reflects membership changes | With at least one active (on) child device, delete that child device (or all active children) from Hubitat, then open the Master Switch's device page | Master Switch's own `switch` attribute updates to `off` - it should not stay stuck `on` with `memberCount: 0` |
| GPT04-02 | A plain refresh/poll reconciles the Master Switch, not just an explicit power toggle | Turn a bulb off directly at the wall (or via the LIFX app) so its actual power state changes outside Hubitat, then trigger a routine Refresh/Poll on that individual device (not the Master Switch) | The Master Switch's aggregate state updates to reflect the change, without needing a separate manual Master Switch refresh |

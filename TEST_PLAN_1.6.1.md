# Outstanding live-hub tests

Everything through 1.5.17 (CT-01/02, LAN-01-04, DUR-01/02, LVL-01-03, PICK-01-03, RESET-01/02/03/04,
BRTH-01/02, EFFCLR-01/02/03/04, COLORNAME-01/02/03/04, DEFAULT-01 through DEFAULT-06) is confirmed
live and tracked in `BACKLOG.md` under "Fixed, pending backport to main" - not repeated here.

## Setup

1. Confirm the app page subtitle reads `v1.6.1`.
2. App-file-only - no driver changes since 1.5.17.

## Mobile: device tables now scroll horizontally (originally 1.5.19) - confirmed live 2026-07-21

SCROLL-01/02/03 all pass. Moved to `BACKLOG.md` under "Backported to main".

## Removed the unreachable "rename an installed child device" feature (originally 1.5.18) - confirmed live 2026-07-21

CLEANUP-01/02/03 all pass. Moved to `BACKLOG.md` under "Backported to main".

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

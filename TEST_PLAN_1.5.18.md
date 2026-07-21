# Outstanding live-hub tests

Everything through 1.5.17 (CT-01/02, LAN-01-04, DUR-01/02, LVL-01-03, PICK-01-03, RESET-01/02/03/04,
BRTH-01/02, EFFCLR-01/02/03/04, COLORNAME-01/02/03/04, DEFAULT-01 through DEFAULT-06) is confirmed
live and tracked in `BACKLOG.md` under "Fixed, pending backport to main" - not repeated here.

## Setup

1. Confirm the app page subtitle reads `v1.5.18`.
2. **1.5.18 is app-file-only** - no driver changes.

## Removed the unreachable "rename an installed child device" feature (1.5.18, no functional change for anyone who could actually use it)

This backend was never reachable through the UI (the render call was missing), so removing it
can't regress anything a user was actually doing. The only thing worth confirming is that nothing
else broke on the way out.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| CLEANUP-01 | App still loads and renders normally | Open the app's main page after upload | No errors, page renders exactly as before - no "Child device rename" section ever appeared, so nothing should look different |
| CLEANUP-02 | Renaming a discovered/installed light still works | Use the existing "Optional alternative local name" field next to a light in the Device preparation table (the one shown in the screenshot), then click Apply local names | Still renames the light correctly, both the row and (if already installed) the live Hubitat device label |
| CLEANUP-03 | Clear saved discovery data still works normally | Click "Clear saved discovery data" | Behaves as before - installed devices unaffected, table clears. (This is the one scenario the removed feature would have handled differently; confirms losing that edge case isn't otherwise disruptive.) |

# Outstanding live-hub tests

Everything through 1.5.17 (CT-01/02, LAN-01-04, DUR-01/02, LVL-01-03, PICK-01-03, RESET-01/02/03/04,
BRTH-01/02, EFFCLR-01/02/03/04, COLORNAME-01/02/03/04, DEFAULT-01 through DEFAULT-06) is confirmed
live and tracked in `BACKLOG.md` under "Fixed, pending backport to main" - not repeated here.

## Setup

1. Confirm the app page subtitle reads `v1.6.0` - `dev`'s own version number was bumped from
   1.5.19 straight to 1.6.0 to stay in sync with `main` after the backport, no functional change
   from what was on 1.5.19.
2. App-file-only - no driver changes since 1.5.17.

## Mobile: device tables now scroll horizontally (originally 1.5.19, not yet confirmed)

The Device preparation, LIFX Cloud source and LAN responses tables were getting clipped at the
screen edge on mobile with no way to reach the remaining columns. `tableOpenHtml()` now wraps its
`<table>` in a `<div style="overflow-x:auto">` container - the table itself is unchanged (same
columns, same widths, same layout), it's just swipeable now instead of clipped.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| SCROLL-01 | Device preparation table scrolls horizontally on mobile | On a phone, open the app's main page and swipe left/right across the Device preparation table | The table scrolls within its own strip to reveal columns that don't fit the screen width, instead of being cut off |
| SCROLL-02 | Advanced diagnostic tables scroll too | Open Advanced, swipe across the LIFX Cloud source table and the LAN responses table | Same scrolling behaviour on both |
| SCROLL-03 | Desktop is unaffected | Open the app's main page on a normal desktop browser window | Tables render and look exactly as before - no visible change when the table already fits |

## Removed the unreachable "rename an installed child device" feature (originally 1.5.18, still not yet confirmed)

This backend was never reachable through the UI (the render call was missing), so removing it
can't regress anything a user was actually doing. The only thing worth confirming is that nothing
else broke on the way out.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| CLEANUP-01 | App still loads and renders normally | Open the app's main page after upload | No errors, page renders exactly as before - no "Child device rename" section ever appeared, so nothing should look different |
| CLEANUP-02 | Renaming a discovered/installed light still works | Use the existing "Optional alternative local name" field next to a light in the Device preparation table (the one shown in the screenshot), then click Apply local names | Still renames the light correctly, both the row and (if already installed) the live Hubitat device label |
| CLEANUP-03 | Clear saved discovery data still works normally | Click "Clear saved discovery data" | Behaves as before - installed devices unaffected, table clears. (This is the one scenario the removed feature would have handled differently; confirms losing that edge case isn't otherwise disruptive.) |

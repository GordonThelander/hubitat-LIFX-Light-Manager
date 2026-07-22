# Outstanding live-hub tests

Everything through 1.6.5 (all F-xx/GPT-xx findings, SCROLL/CLEANUP, WIFIKIND, SUBNET-01-05, SCHED-01)
is confirmed live and tracked in `BACKLOG.md` - not repeated here. SCHED-02/03 (confirming the
firmware/WiFi background jobs genuinely moved off the hourly schedule) still need a multi-hour
observation window and remain open there.

## Setup

1. Confirm the app page subtitle reads `v1.6.6`.
2. App-file-only - no driver changes since 1.6.3.

## Live discovery phase text now shown on the main page (1.6.6, not yet confirmed)

Previously the main page only ever showed one static message the whole time Discovery ran
("Scanning for new devices, please wait..."), with the actual live progress (e.g. "Second broadcast
discovery pass after sweep (up to 45 seconds)") only visible in the collapsed-by-default "Advanced
status" section. Without expanding Advanced, a long-running discovery could look like the app had
hung. Fixed: the same live phase line `statusHtml()` already showed under Advanced (now factored out
into a shared `phaseHtml()` helper) is also shown directly on the main page, right under the existing
static message, while discovery is running. The main page already auto-refreshes every 3 seconds
during discovery, so this updates live for free - no new polling added.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| PHASE-01 | Live phase text appears on the main page during discovery | Click Discovery and watch the main page (don't expand Advanced) | Below the existing "Scanning for new devices..." message, a second red line appears showing the current live phase (e.g. "First broadcast pulse...", "Sweep phase...", "Second broadcast discovery pass after sweep..."), and it updates every few seconds as discovery progresses |
| PHASE-02 | Advanced status still shows the same phase text | With discovery running, expand Advanced and check the "Advanced status" section | Same phase text as the main page, still shown correctly - no regression from factoring the phase line out into a shared helper |
| PHASE-03 | No stray phase text when idle or complete | Open the app when nothing is running, and again right after Discovery Complete | No live phase line shown outside of an active discovery run - only the existing "Idle"/"Discovery Complete" messaging as before |

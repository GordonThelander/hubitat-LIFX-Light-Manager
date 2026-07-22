# Outstanding live-hub tests

Everything through 1.6.5 (all F-xx/GPT-xx findings, SCROLL/CLEANUP, WIFIKIND, SUBNET-01-05, SCHED-01)
is confirmed live and tracked in `BACKLOG.md` - not repeated here. SCHED-02/03 (confirming the
firmware/WiFi background jobs genuinely moved off the hourly schedule) still need a multi-hour
observation window and remain open there.

## Setup

1. Confirm the app page subtitle reads `v1.6.7`.
2. App-file-only - no driver changes since 1.6.3.

## Live discovery phase text now shown on the main page (originally 1.6.6, not yet confirmed)

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

## (estimated % complete) added alongside the phase text (1.6.7, not yet confirmed)

Requested by Gordon after seeing the 1.6.6 phase text - discovery's phases run for uneven, variable
lengths of time (a slow Cloud API response, an early exit once all expected devices are found, etc.
all shift real timing), so this is explicitly labelled "estimated", not exact. New
`estimatedDiscoveryPercent()` maps the current `atomicState.status` (and, within `broadcast`/`sweep`,
real sub-progress - elapsed time within the broadcast window, or `sweepSent`/`sweepTotal` within a
sweep pass) onto a percentage band: validating ~5%, cloud/starting-lan ~15%, initial broadcast
20-40%, fast sweep 40-60%, second broadcast 60-75%, retry sweep passes 75-92%, slow sweep 92-99%.
Appears as e.g. `"Fast /24 locator sweep - 150 ms per host (estimated 52% complete)"` next to the
phase text in both places (main page and Advanced status) - null/hidden outside an active run, same
as the phase text itself.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| PCT-01 | Percentage appears alongside phase text during discovery | Click Discovery and watch either the main page or Advanced status | The phase line now ends with "(estimated NN% complete)" |
| PCT-02 | Percentage generally increases over the course of a run | Watch across a few refreshes (or a full run) | The number trends upward overall as discovery progresses through validating → cloud → broadcast → sweep → complete - it doesn't need to be perfectly smooth, but shouldn't jump backwards within the same phase |
| PCT-03 | No percentage shown when idle or complete | Open the app when nothing is running, and again right after Discovery Complete | No "(estimated...)" text - matches PHASE-03's existing expectation for the phase text itself |
| PCT-04 | Sweep phase percentage reflects real progress within that pass | Watch during a sweep phase specifically (Advanced status also shows "Sweep sent: X / Y") | The percentage moves within its band (40-60% for fast sweep, etc.) roughly in proportion to X/Y, not stuck at one value the whole pass |

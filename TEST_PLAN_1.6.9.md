# Outstanding live-hub tests

Everything through 1.6.5 (all F-xx/GPT-xx findings, SCROLL/CLEANUP, WIFIKIND, SUBNET-01-05, SCHED-01)
is confirmed live and tracked in `BACKLOG.md` - not repeated here. SCHED-02/03 (confirming the
firmware/WiFi background jobs genuinely moved off the hourly schedule) still need a multi-hour
observation window and remain open there.

## Setup

1. Confirm the app page subtitle reads `v1.6.9`.
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

## Discovery step progress line (originally 1.6.7 as bracketed text, redesigned 1.6.8, root cause fixed 1.6.9 - not yet confirmed)

1.6.7 appended `"(estimated NN% complete)"` onto the red phase line; 1.6.8 replaced that with a
separate blue line ("Discovery steps at X% complete, now on step Y of Z") and redesigned the
percentage to be step-based, starting at 0% for step 1 in principle. But on a first-ever run with no
saved devices, Gordon still saw the very first render already at "25% complete, step 3 of 8" instead
of 0%. Root cause found by tracing the actual execution: `startCombinedDiscovery()` sets
`status = "validating"` and then called `startValidationProbe()` **directly** - and when there are no
known IPs yet, that function immediately falls through, synchronously, into the cloud fetch and then
the first broadcast pulse, all within the same request as the Discovery button click. The page that
click returns is the page render, but by the time that render happens the app has *already* raced
through steps 1-2 in the background before ever painting - so the user's very first paint reflected
step 3, not step 1. Fixed: `startCombinedDiscovery()` now defers that call via
`runInMillis(DISCOVERY_START_RENDER_DELAY_MS, "startValidationProbe")` (100ms) instead of calling it
directly, so the button click's own page render always reflects the genuine starting point - step 1,
0% - before any of the synchronous cloud-fetch/broadcast work has had a chance to run. A subsequent
page refresh (3 seconds later) can then legitimately show real progress already made in the
background, which is expected, not a bug.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| STEP-01 | Blue step line appears below the phase text | Click Discovery and watch either the main page or Advanced status | A new blue line appears directly below the red phase text, reading e.g. "Discovery steps at 0% complete, now on step 1 of 8" - no brackets |
| STEP-02 | Genuinely starts at 0% on a first-ever run (no saved devices) | Clear saved discovery data, then click Discovery and look at the page immediately after the click resolves (the very first paint, before any 3-second auto-refresh) | Reads "Discovery steps at 0% complete, now on step 1 of 8" - not step 3/25% like before this fix |
| STEP-03 | Genuinely starts at 0% on a normal re-discovery run too (saved devices already exist) | With devices already saved, click Discovery and look immediately | Same - "Discovery steps at 0% complete, now on step 1 of 8" (this case likely already worked correctly before 1.6.9, since validating has real per-device work to do here, but confirm no regression) |
| STEP-04 | Step number and percentage both advance together after that first paint | Watch across a full run (let it auto-refresh a few times) | Step number counts up (1→8, though most runs won't reach 8 before finishing early) and the percentage rises alongside it, without jumping backwards within the same step |
| STEP-05 | No stray step line when idle or complete | Open the app when nothing is running, and again right after Discovery Complete | No blue "Discovery steps..." line shown outside an active run, matching PHASE-03's existing behaviour for the phase text |
| STEP-06 | Sweep phase percentage still reflects real progress within that pass | Watch during a sweep phase specifically (Advanced status also shows "Sweep sent: X / Y") | The percentage still moves smoothly within its band in proportion to X/Y, not stuck at one value for the whole pass |

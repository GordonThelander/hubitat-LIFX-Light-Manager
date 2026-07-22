# Outstanding live-hub tests

Everything through 1.6.5 (all F-xx/GPT-xx findings, SCROLL/CLEANUP, WIFIKIND, SUBNET-01-05, SCHED-01)
is confirmed live and tracked in `BACKLOG.md` - not repeated here. SCHED-02/03 (confirming the
firmware/WiFi background jobs genuinely moved off the hourly schedule) still need a multi-hour
observation window and remain open there.

## Setup

1. Confirm the app page subtitle reads `v1.6.8`.
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

## Discovery step progress rewritten as a plain blue line (originally 1.6.7 as bracketed text, redesigned 1.6.8 - not yet confirmed)

1.6.7 appended `"(estimated NN% complete)"` directly onto the red phase line. Gordon asked for that
removed and replaced with a separate line: no brackets, blue text, reading "Discovery steps at X%
complete, now on step Y of Z" - and confirmed the percentage should start at 0%, not partway in.
Fixed: new `discoveryStepIndex()` assigns each phase a step number out of a fixed
`DISCOVERY_STEP_COUNT` = 8 (validating, cloud/starting-lan, initial broadcast, fast sweep, second
broadcast, retry sweep pass 1, retry sweep pass 2, slow sweep - the full worst-case chain; most runs
finish early and never reach the later steps). `estimatedDiscoveryPercent()` now derives its band
directly from that step number (step 1 starts the run at 0%), refined within `broadcast`/`sweep`
using the same real sub-progress signals as before (elapsed time in the broadcast window, or
`sweepSent`/`sweepTotal`). New `discoveryStepHtml()` renders the blue line
(`<div style='color:#0066cc'>...</div>`, no brackets) directly below the red phase line, in both the
main page and Advanced status - hidden outside an active run, same convention as the phase text.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| STEP-01 | Blue step line appears below the phase text | Click Discovery and watch either the main page or Advanced status | A new blue line appears directly below the red phase text, reading e.g. "Discovery steps at 12% complete, now on step 2 of 8" - no brackets, no longer attached to the phase text itself |
| STEP-02 | Starts at 0% | Click Discovery and check the very first moment (validating, or cloud if no known IPs to validate) | The blue line reads "Discovery steps at 0% complete, now on step 1 of 8" (or step 2 if validating was skipped) - not a nonzero starting value |
| STEP-03 | Step number and percentage both advance together | Watch across a full run | Step number counts up (1→8, though most runs won't reach 8 before finishing early) and the percentage rises alongside it, without jumping backwards within the same step |
| STEP-04 | No stray step line when idle or complete | Open the app when nothing is running, and again right after Discovery Complete | No blue "Discovery steps..." line shown outside an active run, matching PHASE-03's existing behaviour for the phase text |
| STEP-05 | Sweep phase percentage still reflects real progress within that pass | Watch during a sweep phase specifically (Advanced status also shows "Sweep sent: X / Y") | The percentage still moves smoothly within its band in proportion to X/Y, not stuck at one value for the whole pass |

# 1.5.8 backlog-grooming patch - live-hub test plan

Covers the four correctness fixes and two wording-only fixes from the groomed backlog, as
implemented on the `dev` branch. Everything here runs against the **"(Dev)" app and drivers**,
so there is no risk to the production app or its child devices - they use a separate DNI
namespace (`lifxdev-*` vs `lifx-*`) and can create independent child devices for the same
physical bulbs.

Not covered here: F-11 (Cloud snapshot ageing, deferred to 1.6.0 by the original report) and
any of the E-01 through E-12 architecture items - none of those are part of this patch.

## Setup

1. On the dev branch, upload `apps/LIFX_Light_Manager.groovy` to Hubitat (or update the
   existing "(Dev)" app code if already installed). No driver files changed in this patch.
2. Confirm the app page subtitle reads `v1.5.8`.
3. Have at least one bulb you can create/update a child device for, and the LIFX MASTER
   SWITCH (Dev) device either not yet installed or removable, for the AGG tests.

---

## Master-only create/update now reports success

| # | Test | Steps | Expected |
|---|------|-------|----------|
| AGG-01 | Master-only create | Remove the LIFX MASTER SWITCH (Dev) device from Hubitat's Devices page if it exists. Untick every light in "Select and optionally rename discovered lights", leave only the Master Switch checkbox ticked, press "Create / update selected child devices" | Result message shows "LIFX MASTER SWITCH" with "Created LIFX MASTER SWITCH device: ..." - not "No changes were required" |
| AGG-02 | Master-only update | With the Master Switch already installed, repeat the same action (no other lights ticked) | Result message shows "LIFX MASTER SWITCH" with "Updated LIFX MASTER SWITCH device: ..." - not "No changes were required" |
| AGG-03 | Master alongside other lights | Tick one light plus the Master Switch checkbox, create/update | Result message shows both the light's Created/Updated section and the Master Switch section together, as before |

## F-10 - driver-mismatch no longer overwrites bookkeeping with the expected driver

This one is awkward to trigger cleanly since Hubitat won't let the app change an installed
child's driver - the easiest repro is to manually change a child's driver type in Hubitat's
device edit page to a different LIFX Local driver, then run create/update for that row.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| DRV-01 | Manual driver mismatch | Manually change an installed LIFX child's driver (Type field) to a different one of the LIFX Local drivers via Hubitat's device page, then press "Create / update" for that row | Skipped message still names the actual installed driver correctly; the row is skipped, not modified |
| DRV-02 | Capability behaviour after mismatch | After DRV-01, if the device page's Data section shows `Driver Type`, confirm it still reflects the driver you manually set, not the app's originally expected one | Data value is not overwritten to the expected type |
| DRV-03 | Master Switch bulk command safety | After DRV-01, send a Master Switch colour or CT command that would include this device | The mismatched device does not receive a command type its actual installed driver can't handle (e.g. a colour command sent to a driver with no setColor) |

## F-09 - installedDriverName() fallback chain

Hard to force `typeName` to be blank under normal operation - this is mostly a code-path
confirmation rather than something independently observable in the UI.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| DRV-04 | Normal driver-match still works | Run create/update on an already-installed, correctly-matched device | No regression: row updates normally, no spurious "Driver mismatch" |

## resolvePeriodMs() overflow guard

| # | Test | Steps | Expected |
|---|------|-------|----------|
| FX-03 | Normal speeds still work | Trigger breathe/pulse with speed `0.2`, `3.5`, `60` | Same behaviour as before - periods match, no regression |
| FX-04 | Extreme speed value | Trigger breathe/pulse with an absurd speed value, e.g. `999999999999` | Effect runs at the clamped maximum (60s), not an unpredictable/wrapped value; no error |

## "Remove rows without an installed device" (renamed from "Remove stale saved rows")

| # | Test | Steps | Expected |
|---|------|-------|----------|
| UI-04 | Section renamed | Open Advanced | Section header reads "Remove rows without an installed device"; paragraph leads with "not yet installed", not "stale" |
| UI-05 | Fresh discovery still lists correctly | Run a first-time Discovery before creating any children | All discovered rows still appear here (same underlying behaviour as before - only the wording changed) |
| UI-06 | Installed rows still excluded | With some children created | Those rows are still absent from this list, same as before |

## "Clear saved discovery data" (renamed from "Clear all Data")

| # | Test | Steps | Expected |
|---|------|-------|----------|
| UI-07 | Button renamed with clarifying note | Open the main app page | Button reads "Clear saved discovery data"; a paragraph above it states installed child devices are not affected |
| UI-08 | Behaviour unchanged | Press the button with some installed children present | Saved device-preparation table resets to empty; installed Hubitat child devices are untouched and still fully functional (same as the old "Clear all Data" behaviour - only wording changed) |

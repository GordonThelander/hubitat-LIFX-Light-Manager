# 1.5.3 correctness patch - live-hub test plan

Covers F-01 through F-08 from the 1.5.2 code review report, as implemented on the `dev`
branch (commit `7e150c1`). Everything here runs against the **"(Dev)" app and drivers**,
so there is no risk to the production app or its child devices - they use a separate
DNI namespace (`lifxdev-*` vs `lifx-*`) and can create independent child devices for the
same physical bulbs.

Not covered here: F-09 through F-12 (driver-name fallback, driver-mismatch metadata,
Cloud snapshot ageing, Clear all Data relabelling) and any of the section-6 architecture
items - none of those were part of this patch.

## Setup

1. On the dev branch, upload `apps/LIFX_Light_Manager.groovy` and all 5 files in
   `drivers/` to Hubitat (or update the existing "(Dev)" app/driver code if already
   installed from the earlier branch commit).
2. Confirm the app page subtitle reads `v1.5.3`.
3. Use at least one **colour-capable** bulb (for hue/saturation/CT tests) and make sure
   the LIFX MASTER SWITCH (Dev) device is installed (for the AGG tests).

---

## F-01 - zero values are preserved (was: silently replaced by 75/100)

| # | Test | Steps | Expected |
|---|------|-------|----------|
| VAL-01a | Master default level 0 | Master Switch device page -> Apply Default with the driver's "Default level" preference set to 0 | Bulb goes to 0% brightness, not 75% |
| VAL-01b | Cached saturation 0 -> setHue | On a colour child, set saturation to 0 (fully white), then invoke setHue | Saturation stays 0; bulb does not jump back to a saturated colour |
| VAL-01c | Cached level 0 -> setHue/setSaturation | Set a child's level to 0 via setLevel, then invoke setHue or setSaturation | Level stays 0; bulb does not wake to full brightness as a side effect |
| VAL-01d | Master colour-temperature bulk level 0 | Command the Master Switch's setColorTemperature with level 0 | All targeted bulbs go to 0% brightness, not 75% |
| VAL-01e | Breathe/Pulse base or target brightness "0" | Trigger `breathe(base, target, speed, "0", "100")` from Rule Machine | Effect starts from 0% base brightness, not 100% |

## F-02 - level-0 colour/CT commands send a real power-off

| # | Test | Steps | Expected |
|---|------|-------|----------|
| PWR-01 | Individual child colour, level 0 | Invoke `setColor([level: 0, ...])` on one child, then check the physical bulb / do a status poll | Bulb physically powers off; Hubitat shows off; a later poll does not flip it back to "on" |
| PWR-02 | Individual child CT, level 0 | Invoke `setColorTemperature(temp, 0)` | Same as PWR-01 |
| PWR-03 | Master colour bulk, level 0 | Command the Master Switch's setColor with the cached level at 0 (or via setLevel(0) then setColor) | All targeted bulbs physically power off, not just brightness-0-while-on |
| PWR-04 | Master CT bulk, level 0 | Command the Master Switch's setColorTemperature with level 0 | Same as PWR-03 |

For each: confirm via a physical check (bulb visibly off) or a firmware/WiFi-style refresh, not just the Hubitat event log - the whole point of this fix is that the *optimistic* event was already correct; what was missing was the real packet.

## F-03 - decimal Breathe/Pulse periods

| # | Test | Steps | Expected |
|---|------|-------|----------|
| FX-01 | Decimal speeds | From Rule Machine, trigger breathe/pulse with speed values `0.2`, `0.8`, `2.5`, `3.5`, `60` | Effect period visibly matches each value (200ms/800ms/2.5s/3.5s/60s), not silently falling back to the 3.5s/0.8s default |
| FX-02 | Invalid speeds | Try blank, `abc`, `-1`, `9999` | Blank/invalid uses the documented default (3.5s Breathe / 0.8s Pulse); `9999` clamps to 60s, not rejected outright |

This is the one most likely to have gone unnoticed - the earlier live tests this session used the default speeds, not custom decimal ones.

## F-04 - Master Switch checkbox stays unticked

| # | Test | Steps | Expected |
|---|------|-------|----------|
| UI-01 | Untick and reload | On the main app page, untick "LIFX MASTER SWITCH" (selectMasterSwitch), save, then reopen the app page (and separately, trigger Discovery) several times | The checkbox stays unticked across every reload; the Master Switch device is not recreated |

## F-05 - IP-change row selection stays unticked once you deselect it

| # | Test | Steps | Expected |
|---|------|-------|----------|
| UI-02 | Untick a changed-IP row | Get a device into the "IP changed" state (or simulate by re-running Discovery after a DHCP change), confirm its row is auto-ticked, untick it, then reload the page | The row stays unticked on reload, even though the IP-change condition is still true |

## F-06 - child update doesn't reset polling preferences

| # | Test | Steps | Expected |
|---|------|-------|----------|
| UI-03a | Disable polling, then update a child | Turn off status polling (or set interval to 10 min), then create/update an unrelated child device | Polling stays off / stays at 10 min - not reset to enabled/2min |
| UI-03b | First-ever child creation still defaults | On a fresh install with no polling settings saved yet, create the first child device | Polling defaults to enabled, 2 minutes, as before |

## F-07 - Master Switch reconciles after individual child changes

| # | Test | Steps | Expected |
|---|------|-------|----------|
| AGG-01 | External state change | Turn one bulb on/off from the LIFX mobile app (not Hubitat), wait for the next status poll | Within ~1 second of the poll response, the Master Switch's own `switch` attribute updates to match (any-member-on semantics) |
| AGG-02 | Individual Hubitat command | Turn one child on/off directly from its Hubitat device page (not via the Master Switch) | Master Switch `switch` attribute updates shortly after, without needing a manual Master refresh |
| AGG-03 | Burst coalescing | Trigger a status poll across many children at once (or watch logs during a scheduled poll of a multi-bulb fleet) | Only one `reconcileMasterSwitchState` recomputation fires for the whole burst, not one per child response - check logs for repeated calls if debug logging is on |

## F-08 - create/update results are visible

| # | Test | Steps | Expected |
|---|------|-------|----------|
| RES-01 | All-success creation | Select several not-yet-created devices, click create/update | Result shows "Created (N)" with device names listed - not blank |
| RES-02 | All-success update | Re-run create/update on already-installed devices with no changes needed | Result shows "Updated (N)" - not blank |
| RES-03 | Mixed outcome | Include one device that will fail (e.g. missing IP) alongside successful ones | Result shows Created/Updated *and* Skipped/Failed sections together |
| RES-04 | Truly nothing to do | Run create/update with an empty selection state that resolves to no actionable rows | Result reads "No changes were required." rather than blank |

---

## Regression sanity pass

Since F-02 and F-07 touch the most heavily-used command paths, do one pass of ordinary
use afterward to confirm nothing broke:

- Normal on/off, level, colour, and colour-temperature commands at *non-zero* values still work exactly as before (no power-off packet should fire when level > 0).
- Breathe/Pulse at default speed (no speed argument) still looks the same as it did in the 1.5.2 live tests.
- Master Switch Apply Default still works with its default (non-zero) preference values.

## Sign-off

Once the above passes on the dev branch against real hardware, the fixes are ready to
port to `main` for a real 1.5.3 release (version strings already match; the only
remaining step is re-applying the same diff without the "(Dev)" naming/DNI changes, or
merging and re-diverging the naming layer).

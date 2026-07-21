# Backlog

Known gaps not yet fixed, tracked here since there's no issue tracker for this project. Finding IDs (F-xx) trace back to the `LIFX_Light_Manager_1.5.2_Code_Review_and_Enhancement_Report.docx` external review; items without an F-xx were found during live-hub verification or later external re-review. Line numbers current as of 1.5.14 (`dev`), verified against code, not taken on any reviewer's word.

## Open — confirmed bugs, not yet fixed

- **`colorName` attribute is permanently stale, set once at device init and never updated again.** Found live 2026-07-21 while confirming RESET-04: after setting a bulb to a custom colour (hue 67, saturation 50), `colorName` still read "Soft White" - nowhere close to the actual colour. `initialiseGoogleSafeState()` (`LIFX_Local_Colour_Driver.groovy:63`, `LIFX_Local_Plus_Colour_Driver.groovy:66`) sets `colorName` to "Soft White" once, only if it was never set before, at device creation. No command handler anywhere - not `childSetColor()`, `childSetHue()`, `childSetSaturation()`, `childSetColorTemperature()`, `childSetLevel()`, nor `runColorEffect()` in the app, nor anything in either driver - ever publishes an updated `colorName` afterward, so it's permanently frozen at its init-time default regardless of what colour the bulb is actually showing. Possible fix: derive and publish an accurate `colorName` (nearest named-colour match, or a generic hue-range name like "Blue"/"Violet" with colorTemperature-based White names when saturation is near 0) alongside the other colour events in every handler that changes colour.

## Open — enhancement ideas

Feature/design suggestions, not bugs - not yet decided whether to pursue.

- **Master Switch bulk commands are genuinely sequential unicast, not broadcast - real "popcorning" risk on larger fleets.**
  `apps/LIFX_Light_Manager.groovy:2976-2991` (`sendFastUdpToIp()`): every bulk command loop sends one unicast UDP packet per device via `destinationAddress: "${row.ip}:${port}"`, confirmed not a single broadcast send. LIFX tagged broadcast packets would synchronize the whole fleet's transition instead of a visible ripple. Trade-off worth weighing before pursuing: this app already caused one real hub crash from LAN traffic overload earlier in development, with substantial pacing work done since specifically to avoid a repeat - a tagged broadcast packet is processed by *every* LIFX device on the LAN, not just this app's managed ones, a different reliability/rate profile than the current tuned-against-a-real-incident approach.

- **No proactive offline/health detection - a device that stops responding is never flagged.**
  No offline/presence/unresponsive handling exists anywhere in the app. `pollManagedChildSwitchStatus()` (`apps/LIFX_Light_Manager.groovy:2462-2483`) sends `GET_POWER` on a schedule and updates state only when a response arrives; there is no timeout-based negative case, so a bulb powered off at the wall keeps showing its last-known state indefinitely. The existing "optional lightweight LAN polling" feature sounds similar but only covers the success path.

Checked and not added: "advanced capability extension" (multizone/Tile/Beam/waveforms) is already a declared limitation in `README.md`. "Custom transition times" was factually wrong as a suggestion - already implemented end to end via `setLevel(value, duration = 0)`, `setColorTemperature(value, level = null, duration = 0)`, and `setColor(Map value)` (reading `value?.duration`) on every local driver.

Not yet verified from the ChatGPT re-review (vaguer, no code citations checked): "metadata not consistently persisted," "some events emitted for unsupported capabilities," "edge case where Master state may not reconcile." Its Architecture Observations (state-heavy design, no canonical identity model, discovery modes not properly separated) are design-quality opinions in the same category as the already-declined E-01-E-12 items below, not concrete bugs.

## Deferred by design (from the original F-01-F-12 report, not re-litigated)

- **F-11 — Cloud snapshot rows accumulate without aging.** No successful-snapshot expiry/aging for Cloud-discovered rows. Scoped by the original report to 1.6.0; not started. Related but distinct from the 1.5.6 LAN-only fix - that made *new* cloud-less devices trackable, this is about *stale* cloud-linked rows never expiring.

## Fixed, pending backport to main

`main` is at 1.5.8 locally (committed, not yet pushed to GitHub). All items below already shipped on `dev` across 1.5.5-1.5.14, live-hub tested and confirmed. Note that backporting 1.5.13 also requires the two driver files (`LIFX_Local_Colour_Driver.groovy`/`LIFX_Local_Plus_Colour_Driver.groovy`), not just the app:

- Canonical identity overwrite (1.5.5) - `childDniForRow()`/`clearLanFieldsForRow()` identity preservation
- LAN-only devices uncreatable unless entire cloud fetch failed (1.5.6) - superseded by the deeper discovery-reliability fix in 1.5.9, but the original create/track path fix still stands
- Device preparation table relabel/restructure (1.5.7, cosmetic)
- Master-only create/update under-reported success (1.5.8)
- F-10 driver-mismatch branch overwrote actual metadata with expected (1.5.8)
- F-09 `installedDriverName()` short-circuited on blank `typeName` (1.5.8)
- `resolvePeriodMs()` unbounded overflow (1.5.8)
- "Remove stale saved rows" / "Clear all Data" wording fixes (1.5.8)
- Master Switch colour commands reset Tunable White bulbs' colour temperature to a hardcoded 3500K (1.5.9) - confirmed CT-01/02
- LAN-only discovery reliability, one root cause/three symptoms - `allExpectedFound()` no longer short-circuits before a cloud-less device gets a chance, dead `forceFullLanDiscovery` flag removed, rows carry explicit `origin` so LAN-only rows stop being miscounted as cloud-backed (1.5.9) - confirmed LAN-01/02/04
- Duplicate row/child device risk when a LAN-only device rejoins LIFX Cloud - `mergeCloudIntoCurated()` now reconciles via `reconcilableLanOnlyKey()` (1.5.9) - confirmed LAN-03
- `durationMs()` unbounded overflow, same pattern as `resolvePeriodMs()` (1.5.9) - confirmed DUR-01, DUR-02 not independently observable live (any sufficiently large value looks identical - see test plan note)
- Master Switch colour/CT commands forced the whole fleet to one shared (sometimes hardcoded-75%) brightness level instead of preserving each bulb's own (1.5.10) - confirmed live with two bulbs at different levels, both kept their own level through a shared colour change
- Individual bulb colour picker silently changed brightness, same root cause as the 1.5.10 Master Switch fix - Hubitat's own built-in "Choose a colour" picker bundles a swatch-specific level with every colour tap; `childSetColor()` now preserves the device's own current level instead (1.5.11) - confirmed live: brightness stays constant through the picker, re-confirmed Master Switch colour preservation across lights, and Breathe/Pulse unaffected (they never call `childSetColor()`)
- Off doesn't cancel an active Breathe/Pulse waveform effect - `SET_POWER` off never cancelled an active `SET_WAVEFORM` on the bulb, only a real `SET_COLOR` command does, so a breathing/pulsing light turned off and back on resumed the effect. Fixed via a new conditional `effectActive` device data flag, at both the app level (`childOff()`/`sendBulkSetPower()`) and the individual driver's own fast on/off path (`LIFX_Local_Colour_Driver.groovy`/`LIFX_Local_Plus_Colour_Driver.groovy`, since that path bypasses the parent app entirely) (1.5.13) - confirmed live: lights reset to 3000K/75% after a breathe/pulse followed by off/on, on both an individual light and via the Master Switch. Also confirmed RESET-04 (no-regression case): a bulb set to a custom colour with no effect involved keeps that exact colour through an ordinary off/on cycle - the reset-on-off logic only ever triggers when `effectActive` was genuinely set
- Breathe/Pulse never told Hubitat the bulb was on - `runColorEffect()` sends a real LIFX power-on packet via `sendPowerOnIfNeeded()` when starting an effect, but never published any Hubitat event of its own, unlike every other command handler in this file - `switch`/`hue`/`saturation`/`level`/`colorMode` all stayed stale (switch showing "off") even though the bulb was genuinely powered on and actively running the effect. Also never called `requestMasterStateReconciliation()`, so the Master Switch's own aggregate state didn't pick it up either. Both fixed (1.5.12) - confirmed live BRTH-01/02: switch attribute updates correctly and Master Switch reflects it
- Touching level/colour/CT while a Breathe/Pulse effect was active froze the light on a stale colour, and could cause a surprise reset later - only `childOff()`/`sendBulkSetPower()` ever cleared the `effectActive` flag, so every other colour-sending command left it stuck at `true`, and `childSetLevel()` separately replayed the effect's stale cached colour instead of the requested level. New shared `effectActiveAndClear(device)` helper clears the flag everywhere a real SET_COLOR is sent; `childSetLevel()`/the Master Switch's level command fall back to a plain 3000K/75% default instead of the stale colour when an effect was active (1.5.14) - confirmed live EFFCLR-01/02/03/04: level/colour/CT commands correctly stop an active effect and show the right result, with no surprise reset on a later off/on

Full detail on each in `TEST_PLAN_1.5.8.md` and the 1.5.5-1.5.14 entries in `README.md`. The current test plan is always named for the version under test - nothing is currently outstanding, so `TEST_PLAN_1.5.14.md` is empty pending the next fix that needs live-hub confirmation.

## Explicitly not pursued

E-01 through E-12 (architecture hardening: monolith split, canonical device registry, async Cloud HTTP, polling jitter, ACK-based target learning, product registry consolidation, multi-CIDR, etc.) — declined as impractical for a single-user, no-CI, live-hub-only project. Speculative hardening for failure modes that haven't actually occurred isn't worth the regression risk here. Individual items can be revisited if one becomes a real, observed problem.

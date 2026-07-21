# Backlog

Known gaps not yet fixed, tracked here since there's no issue tracker for this project. Finding IDs (F-xx) trace back to the `LIFX_Light_Manager_1.5.2_Code_Review_and_Enhancement_Report.docx` external review; items without an F-xx were found during live-hub verification or later external re-review. Line numbers current as of 1.5.8 (`dev`), verified against code, not taken on any reviewer's word. Pre-existing in both `main` and `dev` unless noted otherwise - none of the items below were touched by the 1.5.5-1.5.8 work.

## Open — correctness, ranked by real-world impact

1. **Master Switch colour commands reset Tunable White bulbs' colour temperature to a hardcoded 3500K.** Routine-impact, not an edge case - fires on any ordinary Master Switch colour command (e.g. "Red") that doesn't explicitly carry a colour-temperature field, which is the normal case. Affects every Tunable White device in the fleet, including yours (Back Door).
   `apps/LIFX_Light_Manager.groovy:2685` (`groupChildSetColor()`): `requestedKelvin` defaults to `3500` whenever the incoming command has no explicit colour-temperature field. `sendBulkSetColorOrLevel()`'s non-colour branch (`:2899-2905`) sends that hardcoded value to every non-colour-capable device as part of the HSBK packet (saturation 0 + this kelvin = "white at 3500K"), silently overwriting whatever CT the bulb actually had. Fix needs to use the row's own last-known colour temperature instead of the command's (usually-defaulted) kelvin for non-colour-capable targets.

2. **LAN-only discovery is unreliable - one root cause, three symptoms.** This is the core reliability gap in the feature just shipped in 1.5.6.
   `allExpectedFound()` (`apps/LIFX_Light_Manager.groovy:1065-1070`) is scoped only to cloud-known devices, and is checked as an early-exit at *every* phase transition in the discovery pipeline:
   - `startLanDiscovery()` (`:660`) skips starting LAN discovery *at all* if cloud-known devices already have cached IPs from a previous run - true on most Discovery presses against a stable fleet.
   - Every broadcast pulse / sweep-phase transition thereafter (`:810`, `:830`, `:865`, `:873`, `:881`) aborts the *entire run* the moment cloud-known devices are satisfied, regardless of whether an unmatched (cloud-less) device has had a chance to respond yet.
   - This is why the 1.5.6 fix (which made a cloud-less device *trackable once matched*) only worked in live testing when the fleet's cached state happened not to already satisfy `allExpectedFound()` - opportunistic, not deterministic.
   - **Symptom A - dead escape hatch:** `forceFullLanDiscovery` (`:561, 624`) is set `false` in both places it's assigned and never set `true` anywhere - the "force a full scan past the early-exit" option doesn't actually exist.
   - **Symptom B - miscounted rows:** `cloudUidForRow()` (`:1862-1864`, `row.id ?: row.uid ?: row.cloudUid`) can't distinguish a real cloud ID from a LAN MAC reused as one by `mergeLanOnlyIntoCurated()`, so `expectedCloudLanDiscoveryCount()`/`discoveredCloudLanCount()` (`:1072-1090`) silently fold LAN-only rows into the cloud-completion count they were never meant to be part of.
   - A genuine fix needs LAN-only discovery to not be short-circuited by cloud-only completion, plus either removing the dead flag or wiring it up as a real "force full scan" control.

3. **Potential duplicate row/child device if a LAN-only device is later re-added to LIFX Cloud.**
   `apps/LIFX_Light_Manager.groovy:671-677` (`mergeCloudIntoCurated()`) looks up `curated[cloudId]` - a different dictionary key than the LAN-only row's `curated[lanMac]` entry whenever there's a cloud+1-style offset (the common case here). Creates a second, orphaned row instead of merging into the existing one. If a user creates a child from that new row before LAN discovery reconciles it, `lanUidForRow()`'s fallback chain falls through to the cloud ID and produces a different DNI - a genuine duplicate Hubitat child device, not just a duplicate row. Narrower trigger condition than items 1-2 (requires a device to leave and rejoin Cloud), lower priority than the discovery reliability issue but same root cause family (no explicit cloud/lan/mixed identity provenance on a row).

4. **`durationMs()` has the same unbounded-overflow pattern as the `resolvePeriodMs()` bug fixed in 1.5.8, in a sibling function that wasn't touched.** Lowest priority of the four - same class of bug, but requires an extreme/adversarial numeric input to trigger, same as the original.
   `apps/LIFX_Light_Manager.groovy:3387-3390`. `Math.round((value as BigDecimal) * 1000.0d) as Integer` with no pre-clamp - `Math.round()` returns a `long`, and the narrowing `as Integer` cast on an out-of-range value silently wraps instead of throwing. Feeds the `duration`/`transitionTime` parameter on setColor/setLevel/setColorTemperature commands.

Not yet verified from the ChatGPT re-review (vaguer, no code citations checked): "metadata not consistently persisted," "some events emitted for unsupported capabilities," "edge case where Master state may not reconcile." Its Architecture Observations (state-heavy design, no canonical identity model, discovery modes not properly separated) are design-quality opinions in the same category as the already-declined E-01-E-12 items below, not concrete bugs.

## Open — enhancement ideas (external architecture review, Gemini, 2026-07-21)

Feature/design suggestions, not bugs - not yet decided whether to pursue.

- **Master Switch bulk commands are genuinely sequential unicast, not broadcast - real "popcorning" risk on larger fleets.**
  `apps/LIFX_Light_Manager.groovy:2976-2991` (`sendFastUdpToIp()`): every bulk command loop sends one unicast UDP packet per device via `destinationAddress: "${row.ip}:${port}"`, confirmed not a single broadcast send. LIFX tagged broadcast packets would synchronize the whole fleet's transition instead of a visible ripple. Trade-off worth weighing before pursuing: this app already caused one real hub crash from LAN traffic overload earlier in development, with substantial pacing work done since specifically to avoid a repeat - a tagged broadcast packet is processed by *every* LIFX device on the LAN, not just this app's managed ones, a different reliability/rate profile than the current tuned-against-a-real-incident approach.

- **No proactive offline/health detection - a device that stops responding is never flagged.**
  No offline/presence/unresponsive handling exists anywhere in the app. `pollManagedChildSwitchStatus()` (`apps/LIFX_Light_Manager.groovy:2462-2483`) sends `GET_POWER` on a schedule and updates state only when a response arrives; there is no timeout-based negative case, so a bulb powered off at the wall keeps showing its last-known state indefinitely. The existing "optional lightweight LAN polling" feature sounds similar but only covers the success path.

Checked and not added: "advanced capability extension" (multizone/Tile/Beam/waveforms) is already a declared limitation in `README.md`. "Custom transition times" was factually wrong as a suggestion - already implemented end to end via `setLevel(value, duration = 0)`, `setColorTemperature(value, level = null, duration = 0)`, and `setColor(Map value)` (reading `value?.duration`) on every local driver.

## Deferred by design (from the original F-01-F-12 report, not re-litigated)

- **F-11 — Cloud snapshot rows accumulate without aging.** No successful-snapshot expiry/aging for Cloud-discovered rows. Scoped by the original report to 1.6.0; not started. Related but distinct from the 1.5.6 LAN-only fix - that made *new* cloud-less devices trackable, this is about *stale* cloud-linked rows never expiring.

## Fixed, pending backport to main

`main` is at 1.5.8 locally (committed, not yet pushed to GitHub as of this grooming pass). All items below already shipped on `dev` across 1.5.5-1.5.8:

- Canonical identity overwrite (1.5.5) - `childDniForRow()`/`clearLanFieldsForRow()` identity preservation
- LAN-only devices uncreatable unless entire cloud fetch failed (1.5.6) - superseded in practice by the deeper discovery-reliability issue above, but the create/track path itself is fixed
- Device preparation table relabel/restructure (1.5.7, cosmetic)
- Master-only create/update under-reported success (1.5.8)
- F-10 driver-mismatch branch overwrote actual metadata with expected (1.5.8)
- F-09 `installedDriverName()` short-circuited on blank `typeName` (1.5.8)
- `resolvePeriodMs()` unbounded overflow (1.5.8)
- "Remove stale saved rows" / "Clear all Data" wording fixes (1.5.8)

Full detail on each in `TEST_PLAN_1.5.8.md` and the 1.5.5-1.5.8 entries in `README.md`.

## Explicitly not pursued

E-01 through E-12 (architecture hardening: monolith split, canonical device registry, async Cloud HTTP, polling jitter, ACK-based target learning, product registry consolidation, multi-CIDR, etc.) — declined as impractical for a single-user, no-CI, live-hub-only project. Speculative hardening for failure modes that haven't actually occurred isn't worth the regression risk here. Individual items can be revisited if one becomes a real, observed problem.

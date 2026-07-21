# Backlog

Known gaps not yet fixed, tracked here since there's no issue tracker for this project. Finding IDs (F-xx) trace back to the `LIFX_Light_Manager_1.5.2_Code_Review_and_Enhancement_Report.docx` external review; items without an F-xx were found during live-hub verification or later external re-review. Line numbers current as of 1.5.9 (`dev`), verified against code, not taken on any reviewer's word.

## Open — enhancement ideas (external architecture review, Gemini, 2026-07-21)

Feature/design suggestions, not bugs - not yet decided whether to pursue.

- **Master Switch bulk commands are genuinely sequential unicast, not broadcast - real "popcorning" risk on larger fleets.**
  `apps/LIFX_Light_Manager.groovy:2976-2991` (`sendFastUdpToIp()`): every bulk command loop sends one unicast UDP packet per device via `destinationAddress: "${row.ip}:${port}"`, confirmed not a single broadcast send. LIFX tagged broadcast packets would synchronize the whole fleet's transition instead of a visible ripple. Trade-off worth weighing before pursuing: this app already caused one real hub crash from LAN traffic overload earlier in development, with substantial pacing work done since specifically to avoid a repeat - a tagged broadcast packet is processed by *every* LIFX device on the LAN, not just this app's managed ones, a different reliability/rate profile than the current tuned-against-a-real-incident approach.

- **No proactive offline/health detection - a device that stops responding is never flagged.**
  No offline/presence/unresponsive handling exists anywhere in the app. `pollManagedChildSwitchStatus()` (`apps/LIFX_Light_Manager.groovy:2462-2483`) sends `GET_POWER` on a schedule and updates state only when a response arrives; there is no timeout-based negative case, so a bulb powered off at the wall keeps showing its last-known state indefinitely. The existing "optional lightweight LAN polling" feature sounds similar but only covers the success path.

Checked and not added: "advanced capability extension" (multizone/Tile/Beam/waveforms) is already a declared limitation in `README.md`. "Custom transition times" was factually wrong as a suggestion - already implemented end to end via `setLevel(value, duration = 0)`, `setColorTemperature(value, level = null, duration = 0)`, and `setColor(Map value)` (reading `value?.duration`) on every local driver.

Not yet verified from the ChatGPT re-review (vaguer, no code citations checked): "metadata not consistently persisted," "some events emitted for unsupported capabilities," "edge case where Master state may not reconcile." Its Architecture Observations (state-heavy design, no canonical identity model, discovery modes not properly separated) are design-quality opinions in the same category as the already-declined E-01-E-12 items below, not concrete bugs.

## Deferred by design (from the original F-01-F-12 report, not re-litigated)

- **F-11 — Cloud snapshot rows accumulate without aging.** No successful-snapshot expiry/aging for Cloud-discovered rows. Scoped by the original report to 1.6.0; not started. Related but distinct from the 1.5.6 LAN-only fix - that made *new* cloud-less devices trackable, this is about *stale* cloud-linked rows never expiring.

## Fixed, awaiting live-hub testing (1.5.9)

Implemented and compile-checked, not yet confirmed on real hardware - see `TEST_PLAN_1.5.9.md`.

- **Master Switch colour commands reset Tunable White bulbs' colour temperature to a hardcoded 3500K.** `sendBulkSetColorOrLevel()`'s non-colour branch now preserves each bulb's own current colour temperature (`child.currentValue('colorTemperature')`, falling back to row defaults) instead of the command's kelvin, which defaulted to 3500 on any ordinary RGB colour command. Test plan: CT-01/02.

- **LAN-only discovery reliability - one root cause, three symptoms, all fixed together.** `startLanDiscovery()` no longer skips broadcasting entirely when cloud-known devices are already cached; the initial broadcast pass now guarantees a minimum listening window (`MIN_INITIAL_BROADCAST_PULSES`, ~9s) before honouring early completion, since it's the only phase that can discover a device with no cloud presence at all; the dead `forceFullLanDiscovery` flag is removed entirely; rows now carry an explicit `origin` ("cloud" vs "lan-only") so `expectedCloudLanDiscoveryCount()`/`discoveredCloudLanCount()` stop counting LAN-only rows as cloud-backed. Test plan: LAN-01/02/04.

- **Potential duplicate row/child device if a LAN-only device is later re-added to LIFX Cloud.** `mergeCloudIntoCurated()` now reconciles into an existing LAN-only row (matched by exact or adjacent UID via the new `reconcilableLanOnlyKey()`) instead of creating a second, orphaned row. Test plan: LAN-03.

- **`durationMs()` unbounded-overflow, same pattern as the `resolvePeriodMs()` bug fixed in 1.5.8.** Now clamps in `BigDecimal` space before the narrowing `Integer` cast. Test plan: DUR-01/02.

## Fixed, pending backport to main

`main` is at 1.5.8 locally (committed, not yet pushed to GitHub). All items below already shipped on `dev` across 1.5.5-1.5.8, live-hub tested and confirmed:

- Canonical identity overwrite (1.5.5) - `childDniForRow()`/`clearLanFieldsForRow()` identity preservation
- LAN-only devices uncreatable unless entire cloud fetch failed (1.5.6) - superseded in practice by the deeper discovery-reliability fix in 1.5.9 above, but the original create/track path fix still stands
- Device preparation table relabel/restructure (1.5.7, cosmetic)
- Master-only create/update under-reported success (1.5.8)
- F-10 driver-mismatch branch overwrote actual metadata with expected (1.5.8)
- F-09 `installedDriverName()` short-circuited on blank `typeName` (1.5.8)
- `resolvePeriodMs()` unbounded overflow (1.5.8)
- "Remove stale saved rows" / "Clear all Data" wording fixes (1.5.8)

Full detail on each in `TEST_PLAN_1.5.8.md` and the 1.5.5-1.5.8 entries in `README.md`. 1.5.9's four items above join this list once live-hub tested.

## Explicitly not pursued

E-01 through E-12 (architecture hardening: monolith split, canonical device registry, async Cloud HTTP, polling jitter, ACK-based target learning, product registry consolidation, multi-CIDR, etc.) — declined as impractical for a single-user, no-CI, live-hub-only project. Speculative hardening for failure modes that haven't actually occurred isn't worth the regression risk here. Individual items can be revisited if one becomes a real, observed problem.

# Backlog

Known gaps not yet fixed, tracked here since there's no issue tracker for this project. Finding IDs (F-xx) trace back to the `LIFX_Light_Manager_1.5.2_Code_Review_and_Enhancement_Report.docx` external review; items without an F-xx were found during live-hub verification. Line numbers current as of 1.5.8 (`dev`).

## Open — correctness (external re-review of 1.5.8, confirmed 2026-07-21)

All six confirmed directly against code (file/line citations below), not taken on the reviewer's word. Pre-existing in both `main` and `dev` - none of these functions were touched by any of the 1.5.5-1.5.8 work, so the bugs are identical on both branches.

- **LAN-only discovery is unreliable - the early-exit is scoped only to cloud-known devices, not the whole fleet.**
  `apps/LIFX_Light_Manager.groovy:1065-1070` (`allExpectedFound()`), checked at every phase transition in the discovery pipeline: `startLanDiscovery()` (`:660`, skips starting LAN discovery *at all* if cloud-known devices already have cached IPs from a previous run), and every broadcast pulse / sweep-phase transition thereafter (`:810`, `:830`, `:865`, `:873`, `:881`). Each of these aborts the *entire run* the moment cloud-known devices are satisfied, regardless of whether an unmatched (cloud-less) device has had a chance to respond yet. This is why the 1.5.6 fix (which made a cloud-less device *trackable once matched*) only worked in live testing when the fleet's cached state happened not to already satisfy `allExpectedFound()` - it is opportunistic, not deterministic. A genuinely reliable fix needs LAN-only discovery to not be short-circuited by cloud-only completion.

- **`forceFullLanDiscovery` is dead code - never set `true` anywhere.**
  `apps/LIFX_Light_Manager.groovy:561, 624`. Both existing assignments set it to `false`; nothing in the file ever sets it `true`. This is presumably meant to be the escape hatch past the early-exit above (a "force a full scan" option), but there's no UI control or code path that engages it - the flag is fully inert. Fixing the item above should either remove this dead flag or actually wire it up.

- **LAN-only rows are silently counted as cloud-backed devices in discovery-completion tracking.**
  `apps/LIFX_Light_Manager.groovy:1862-1864` (`cloudUidForRow()` returns `row.id ?: row.uid ?: row.cloudUid`) combined with `mergeLanOnlyIntoCurated()` setting `row.id`/`row.uid` to the LAN MAC for a cloud-less row - there's no field distinguishing a real cloud ID from a LAN MAC standing in for one. `expectedCloudLanDiscoveryCount()`/`discoveredCloudLanCount()` (`:1072-1090`) therefore fold LAN-only rows into the "cloud" completion count, even though their own doc comment says cloud-led discovery should stop when *cloud-backed* rows have LAN details - LAN-only rows were never meant to be part of that count at all.

- **Potential duplicate row/child device if a LAN-only device is later re-added to LIFX Cloud.**
  `apps/LIFX_Light_Manager.groovy:671-677` (`mergeCloudIntoCurated()`) looks up `curated[cloudId]` - a different dictionary key than the LAN-only row's `curated[lanMac]` entry whenever there's a cloud+1-style offset (the common case in this app). That creates a second, orphaned row for the same physical device instead of merging into the existing one. If a user creates a child from that new row before LAN discovery reconciles it, `lanUidForRow()`'s fallback chain falls through to the cloud ID and produces a different DNI - a genuine duplicate Hubitat child device, not just a cosmetic duplicate row.

- **`durationMs()` has the same unbounded-overflow pattern as the `resolvePeriodMs()` bug fixed in 1.5.8, in a sibling function that wasn't touched.**
  `apps/LIFX_Light_Manager.groovy:3387-3390`. `Math.round((value as BigDecimal) * 1000.0d) as Integer` with no pre-clamp - `Math.round()` returns a `long`, and the narrowing `as Integer` cast on an out-of-range value silently wraps instead of throwing, same failure mode as the `resolvePeriodMs()` fix. This feeds the `duration`/`transitionTime` parameter on setColor/setLevel/setColorTemperature commands.

- **Master Switch colour commands reset Tunable White bulbs' colour temperature to a hardcoded 3500K, discarding their actual current value.**
  `apps/LIFX_Light_Manager.groovy:2685` (`groupChildSetColor()`): `requestedKelvin` defaults to `3500` whenever the incoming command has no explicit colour-temperature field - true of any normal RGB colour command (e.g. "Red"). `sendBulkSetColorOrLevel()`'s non-colour branch (`:2899-2905`) then sends that hardcoded value to every non-colour-capable device in the fleet as part of the HSBK packet (saturation 0 + this kelvin = "white at 3500K"), silently overwriting whatever CT the Tunable White bulb was actually set to. Needs to use the row's own last-known colour temperature instead of the command's (usually-defaulted) kelvin value for non-colour-capable targets.

Not yet verified from the same review (vaguer, no code citations checked): "metadata not consistently persisted," "some events emitted for unsupported capabilities," "edge case where Master state may not reconcile." The review's Architecture Observations (state-heavy design, no canonical identity model, discovery modes not properly separated) are design-quality opinions in the same category as the already-declined E-01-E-12 items below, not concrete bugs - noted but not queued as standalone work.

## Deferred by design (from the original report, not re-litigated)

- **F-11 — Cloud snapshot rows accumulate without aging.**
  No successful-snapshot expiry/aging for Cloud-discovered rows. Scoped by the original report to 1.6.0; not started. Related but distinct from the 1.5.6 LAN-only fix below - that made *new* cloud-less devices trackable, this is about *stale* cloud-linked rows never expiring.

## Fixed (pending backport to main)

`main` is still at 1.5.4. All fixes below exist only on `dev` (currently 1.5.8).

- **Canonical identity overwrite** (found during external review of 1.5.4, not part of the original F-01–F-12 report). Fixed in 1.5.5: `childDniForRow()` now prefers a row's persisted `childDni` over re-deriving from `lanUid`, and `clearLanFieldsForRow()` no longer blanks identity fields for a row with an installed child. See the 1.5.5 entry in `README.md`.

- **LAN-only devices were uncreatable unless the entire cloud fetch failed** (found during live testing, not part of the original report). Fixed in 1.5.6: a device with no cloud match now gets tracked and made creatable regardless of whether the rest of the fleet's cloud connectivity is healthy, not just during a whole-fleet cloud outage. Confirmed live: deleting one device from LIFX Cloud while 13 others remained previously left it undiscoverable in the app indefinitely.

- **Device preparation table relabel/restructure** (cosmetic, user-requested, 1.5.7): `UID`→`Cloud ID`, `Label`→`Cloud Name`, `Status`→`Current Status`, `Cloud connected` moved next to `Cloud Name`, `Capabilities` column removed (redundant with `Driver mode`, which is now labelled `Driver Capabilities`). Not a bug fix, but also not yet on `main`.

- **Master-only create/update under-reported success.**
  Fixed in 1.5.8. `masterResult` was only appended to the result message when it contained `"failed"` or `"not installed"`. A successful Master-only create/update produced "No changes were required." despite actually having created or updated the device. Now reported unconditionally whenever non-blank. See `TEST_PLAN_1.5.8.md` (AGG-01/02/03).

- **F-10 — driver-mismatch branch overwrote actual metadata with expected.**
  Fixed in 1.5.8. `updateChildDataValues(existing, row, driverType)` is no longer called on a mismatched child (it would have pushed the *expected* driver's data onto a device running a *different* one), and `row.childDriver` now records `currentDriver` (actual) rather than `driverType` (expected) - this matters because `rowIsColourCapable()`/`rowSupportsColorTemperature()` trust `row.childDriver` to decide which commands are safe to send. See `TEST_PLAN_1.5.8.md` (DRV-01/02/03).

- **F-09 — `installedDriverName()` short-circuited on blank `typeName`.**
  Fixed in 1.5.8. The three fallbacks (`typeName`, `getTypeName()`, persisted `driverType` data value) now correctly try each in turn, only falling through when the previous one came back genuinely blank - previously a blank `typeName` returned `""` immediately via the Elvis operator without throwing, making the other two fallbacks dead code. Feeds directly into F-10's mismatch detection. See `TEST_PLAN_1.5.8.md` (DRV-04).

- **`resolvePeriodMs()` had no upper bound before the intermediate multiply/cast.**
  Fixed in 1.5.8. Now clamps in `BigDecimal` space (`.min(60000G).max(200G)`) before calling `.intValue()`, since `BigDecimal.intValue()` silently wraps on overflow instead of throwing - an extreme numeric string in the Breathe/Pulse speed field could previously produce an unpredictable (even negative, pre-clamp) result. See `TEST_PLAN_1.5.8.md` (FX-04).

- **"Remove stale saved rows" mislabelled freshly-discovered, not-yet-installed rows as stale.**
  Fixed in 1.5.8 (wording only, `removableSavedRows()` logic unchanged). Renamed to "Remove rows without an installed device"; paragraph now leads with "not yet installed" rather than implying staleness. Confirmed twice live (2026-07-21) before the fix: a fully healthy, just-discovered fleet with no children created yet showed up here in its entirety. See `TEST_PLAN_1.5.8.md` (UI-04/05/06).

- **F-12 — "Clear all Data" was broader-sounding than its actual behaviour.**
  Fixed in 1.5.8 (wording only, `clearAllData()` logic unchanged). Renamed to "Clear saved discovery data" with an explicit note that installed Hubitat child devices are not affected - the old name read as if it could delete real devices. See `TEST_PLAN_1.5.8.md` (UI-07/08).

## Explicitly not pursued

E-01 through E-12 (architecture hardening: monolith split, canonical device registry, async Cloud HTTP, polling jitter, ACK-based target learning, product registry consolidation, multi-CIDR, etc.) — declined as impractical for a single-user, no-CI, live-hub-only project. Speculative hardening for failure modes that haven't actually occurred isn't worth the regression risk here. Individual items can be revisited if one becomes a real, observed problem.

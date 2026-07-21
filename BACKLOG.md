# Backlog

Known gaps not yet fixed, tracked here since there's no issue tracker for this project. Finding IDs (F-xx) trace back to the `LIFX_Light_Manager_1.5.2_Code_Review_and_Enhancement_Report.docx` external review; items without an F-xx were found during live-hub verification. Line numbers current as of 1.5.8 (`dev`).

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

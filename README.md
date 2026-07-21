# LIFX Light Manager for Hubitat

**Version:** 1.5.16

LIFX Light Manager is a Hubitat app and driver package for discovering, creating and locally controlling LIFX lights. It combines LIFX Cloud metadata with local LAN discovery so devices can be named and classified accurately, then controlled locally over the network after child devices are created.

> **This is the `dev` branch — a private test channel.** The app and drivers here are renamed to **LIFX Light Manager (Dev)** with a separate namespace/DNI prefix, so this can be installed and tested alongside a production install on the same hub, against the same physical bulbs, without affecting production devices or automations. Aside from that naming and the version currently ahead of production, everything in this document describes the same functionality as the production release.

## What it does

- Uses a LIFX Personal Access Token for discovery and metadata enrichment.
- Discovers LIFX lights on the local LAN and records their current IP addresses.
- Maintains a device preparation table showing label, local name, IP address, group, product, firmware version, capabilities, driver mode, cloud connection and status.
- Supports optional local Hubitat names before child-device creation.
- Handles Cloud ID to LAN UID mismatches, including the observed `cloud+1` MAC address anomaly edge case.
- Creates Hubitat child devices for mono, tunable white, colour and Plus/IR-capable LIFX lights.
- Detects changed IP addresses and marks affected devices for update.
- Updates child device data and visible current-state attributes after IP remediation.
- Provides a **LIFX Master Switch** aggregate device for whole-fleet control.
- Supports fast local on/off control for individual children and the Master Switch.
- Supports Master Switch colour and colour-temperature control, applying colour to colour-capable lights and level-only changes to non-colour lights.
- Supports optional lightweight LAN polling of installed child devices.
- Supports an on-demand firmware version check over LAN for every saved device, including ones not yet installed as child devices.
- Supports an on-demand WiFi signal strength check over LAN, shown in dBm in the device preparation table.
- Supports optional hourly background maintenance (Discovery, firmware check, WiFi signal check) so the device table stays current without opening the app.
- Supports native LIFX **Breathe** and **Pulse** colour effects as Rule Machine Custom Actions, see [Breathe / Pulse colour effects](#breathe--pulse-colour-effects) below.
- Google Home compatible via standard Hubitat capability exposure across all local drivers and the Master Switch, including full colour control.

## Components

### App

- `apps/LIFX_Light_Manager.groovy`

### Drivers

- `drivers/LIFX_Local_White_Mono_Driver.groovy`
- `drivers/LIFX_Local_Tunable_White_Driver.groovy`
- `drivers/LIFX_Local_Colour_Driver.groovy`
- `drivers/LIFX_Local_Plus_Colour_Driver.groovy`
- `drivers/LIFX_Master_Switch_Driver.groovy`

The filenames are intentionally stable for HPM. Versioning is handled in `packageManifest.json` and in the file comments.

## HPM installation

This is the private test channel, not part of the official HPM curated list. Use this manifest URL in Hubitat Package Manager:

```text
https://raw.githubusercontent.com/GordonThelander/hubitat-LIFX-Light-Manager/dev/packageManifest.json
```

Recommended repository layout:

```text
apps/LIFX_Light_Manager.groovy
drivers/LIFX_Local_White_Mono_Driver.groovy
drivers/LIFX_Local_Tunable_White_Driver.groovy
drivers/LIFX_Local_Colour_Driver.groovy
drivers/LIFX_Local_Plus_Colour_Driver.groovy
drivers/LIFX_Master_Switch_Driver.groovy
packageManifest.json
README.md
```

## Configuration

1. Install the package through HPM.
2. Add the **LIFX Light Manager (Dev)** app in Hubitat.
3. Enter your own LIFX Personal Access Token.
4. Press **Discovery**.
5. Wait for network discovery to complete. This typically takes 2-3 minutes, and can take longer the first time or after using Clear saved discovery data - the app paces its LAN traffic conservatively to stay within Hubitat's own outbound command rate limits.
6. Select the child devices to create or update.
7. Use **Create / update selected child devices** or **Create / update all listed child devices**.

The package does **not** include a hard-coded LIFX Cloud token.

## Discovery and control model

Cloud discovery is used for labels, groups, product information and capability metadata. LAN discovery is used to find the current local IP address and to support local control.

After child devices are created, routine on/off and colour commands are sent locally over LAN UDP. The Cloud token is not used for normal light control.

## LIFX Master Switch

The **LIFX Master Switch** is an aggregate child device that represents the installed LIFX child devices. It is created from the child-device preparation list and can be used for whole-fleet control.

It supports:

- On
- Off
- Refresh
- Poll
- Set colour
- Set colour temperature
- Level handling

Colour-capable lights receive colour commands. Non-colour lights receive level-only changes where appropriate.

The Master Switch exposes `Light`, `ColorControl` and `ColorTemperature` capabilities, so sharing it with Google Home makes it appear there as a full colour light, the same as the individual colour-capable bulb drivers. Confirmed working with Google Home.

## Breathe / Pulse colour effects

Colour-capable local drivers (Colour, Plus Colour) and the Master Switch expose two native LIFX LAN effects as custom commands, for use as a Rule Machine **Custom Action**:

```text
breathe(baseColour, targetColour, speedSeconds, baseBrightness, targetBrightness)
pulse(baseColour, targetColour, speedSeconds, baseBrightness, targetBrightness)
```

- **Breathe** is a smooth sine-wave fade between the two colours. **Pulse** is a sharp on/off-style switch between them. Same parameters, different waveform shape.
- Only `baseColour` and `targetColour` are required. `speedSeconds`, `baseBrightness` and `targetBrightness` are optional and can be left blank.
- Valid colour names (case-insensitive): `Soft White`, `White`, `Daylight`, `Warm White`, `Red`, `Orange`, `Yellow`, `Green`, `Blue`, `Purple`, `Pink`. An unrecognised name falls back to White.
- `speedSeconds` is a plain number of seconds (e.g. `6`). Blank defaults to 3.5s for Breathe, 0.8s for Pulse.
- `baseBrightness`/`targetBrightness` are 0-100 (%). Blank defaults to 100 (full brightness).
- The effect runs until interrupted by a later command (`setColor`, `on`, `off`, etc.) sent to the same device, not for a fixed duration. Interrupting only takes effect once the current cycle finishes, so it can lag by up to one `speedSeconds` period, plan rule timing (delays, "Off" actions) accordingly.
- Wide hue swings between very different fully-saturated colours (e.g. Green to Red) can look choppy rather than smooth, this is the bulb's own colour interpolation, not a bug. Narrower hue gaps or saturation-based pairs (e.g. White to Blue) fade more gently.
- Sent to the Master Switch, the effect only reaches colour-capable bulbs in the fleet; Mono White and Tunable White bulbs have no physical way to render a hue and are skipped.

**Rule Machine parameters are positional, not named.** When building the Custom Action, Rule Machine passes whatever you type into parameter slot 1, 2, 3... as arguments 1, 2, 3... in that same order, it does not read the parameter's label to figure out what a value means. The order must match exactly: Base colour, Target colour, Speed, Base brightness, Target brightness. Get the order wrong (e.g. brightness before speed) and values get silently misinterpreted with no error.

Also: for each parameter, Rule Machine's "parameter type" dropdown must be set to **string**, not "number". A NUMBER-type parameter on a custom command triggers Rule Machine's "Meter" behaviour, which re-invokes the command repeatedly at that interval instead of passing it as a one-time value, breaking Breathe's continuous fade. All five parameters here are declared as STRING specifically to avoid this.

## IP address change remediation

When discovery finds a different IP address for an already-created child device, the app marks that device for update with:

```text
Update required due to IP address change
```

Updating the selected child refreshes the stored device data and the visible `Lan Ip` current-state value. The Master Switch membership is recalculated after child updates.

## Current version

| Component | Version |
|---|---|
| Package | 1.5.16 |
| App | 1.5.16 |
| White Mono driver | 1.5.5 |
| Tunable White driver | 1.5.5 |
| Colour driver | 1.5.6 |
| Plus Colour driver | 1.5.6 |
| Master Switch driver | 1.5.4 |

Tested on Hubitat Elevation platform version 2.5.0.159.

## Known limitations

- Requires a LIFX Cloud token for discovery/enrichment.
- Control is local after devices are created.
- Multizone, Tile, Beam and advanced LIFX effects are not fully supported in this beta.
- Public release status should retain appropriate attribution and respect upstream licensing constraints.

## Attribution

This project takes its structural cues for the LIFX LAN packet layer from Robert Alan Heyes' **LIFX Master** integration (`robheyes`), with the framing and byte-level encoding tracing back to that reference. Everything built on top of that foundation, including Cloud-assisted discovery, UID matching, and the Master Switch model, is this project's own design.

## Status

**1.5.16:** Each local driver (Colour, Plus Colour, Tunable White, White Mono) now has its own configurable "Default level"/"Default colour temperature" preferences and an Apply Default command, the same pattern the Master Switch already used. More importantly, the hardcoded 75%/3000K reset sent when Off cancels an active Breathe/Pulse effect is now each bulb's own configured default instead of one fixed value shared by the whole fleet - includes the driver's own fast on/off path, not just app-triggered commands. White Mono only gets a level default, since it has no colour temperature capability at all. Requires re-uploading all four local driver files alongside the app.

**1.5.15:** Fixes `colorName` being permanently stuck at its device-init default ("Soft White") forever, regardless of what colour the bulb was actually set to - no command handler ever republished it after the one-time default was set at device creation. Now derived and kept in sync (nearest match against the same 11 named colours already used for Breathe/Pulse presets) by every command that changes colour, including the response handler that reconciles state after a colour change made outside Hubitat entirely (LIFX app, physical control). App-file-only, no driver changes.

**1.5.14:** Fixes a follow-on bug found while testing 1.5.13: touching level, colour or colour temperature while a Breathe/Pulse effect was running froze the light on a stale colour instead of the requested change, and could also cause a later Off to force an unwanted colour reset on a bulb the user had already set deliberately. Every command that sends a real colour packet now correctly cancels the effect tracking, not just Off; a level change specifically now falls back to a plain default colour instead of replaying the effect's stale cached colour. App-file-only, no driver changes.

**1.5.13:** Turning a breathing/pulsing light off and back on no longer resumes the effect. `SET_POWER` off doesn't cancel an active LIFX waveform effect on the bulb, only a real colour command does, so Off now sends a colour reset (to a plain 75%/3000K) before powering off, but only when an effect was actually running - an ordinary light at a normal custom colour keeps that colour through a regular off/on cycle. This applies both to app-triggered Off and to the individual driver's own on/off, which is the path used by the physical device tile, Dashboard, Google Home and most Rule Machine actions, since that path talks to the bulb directly for speed rather than going through the app. Requires re-uploading the Colour and Plus Colour driver files (now 1.5.5) alongside the app.

**1.5.12:** Fixes Breathe/Pulse never updating Hubitat's own switch/colour state when an effect started - the bulb was genuinely powered on and actively breathing or pulsing, but Hubitat's switch attribute stayed showing "off," and the Master Switch's own aggregate state didn't pick up the change either. Both now update correctly. Also confirmed (not a bug, LIFX protocol behaviour): turning a breathing/pulsing light off and back on resumes the effect, since a plain power command doesn't cancel it - only changing the level or colour does.

**1.5.11:** Fixes the same brightness-side-effect problem as 1.5.10, but for individual bulbs found live while re-testing that fix. Hubitat's own built-in "Choose a colour" picker bundles a swatch-specific brightness with every colour tap (different colours carried different levels), and picking a colour on an individual device was silently applying that bundled level instead of preserving the bulb's own brightness. Individual colour commands now preserve the device's own current brightness the same way the Master Switch already does - confirmed live, including that Breathe/Pulse effects are unaffected since they never go through this code path.

**1.5.10:** Fixes a bug found live while testing 1.5.9: Master Switch colour and colour-temperature commands were forcing every bulb in the fleet to a single shared brightness level (the Master Switch's own current level, which fell back to a hardcoded 75% whenever that was null or stale), silently changing every bulb's brightness as a side effect of a pure colour or colour-temperature command. Each bulb's own current brightness is now preserved individually instead - confirmed live with two bulbs at different brightness levels, both keeping their own level through a shared colour change.

**1.5.9:** Fixes four correctness issues confirmed during external re-review of 1.5.8. Master Switch colour commands (e.g. a plain colour change) no longer reset every Tunable White bulb's colour temperature to a hardcoded 3500K default - each bulb's own current colour temperature is preserved instead. LAN-only discovery is now reliably given a chance to find a cloud-less device on every Discovery run, rather than only when the rest of the fleet's cached state happened not to already satisfy completion tracking; LAN-only rows are also no longer miscounted as cloud-backed devices internally. A device that leaves and later rejoins LIFX Cloud now reconciles into its existing row instead of risking a duplicate row or duplicate child device. A transition-duration parsing overflow was fixed using the same safeguard applied to the Breathe/Pulse speed parser in 1.5.8.

**1.5.8:** Backlog-grooming maintenance release. A Master Switch-only create/update now reports success in the result message instead of being silently dropped whenever no other lights were selected in the same action. A detected driver mismatch no longer overwrites a row's own record of the installed driver with the one that was expected. The driver-name lookup used for mismatch detection now correctly tries all of its fallbacks in turn instead of stopping at the first blank result. Breathe/Pulse speed parsing now clamps before converting to milliseconds, avoiding a silent overflow on an extreme input value. Two labels were reworded for clarity: "Remove stale saved rows" is now "Remove rows without an installed device", and "Clear all Data" is now "Clear saved discovery data" with an explicit note that installed devices are unaffected.

**1.5.7:** The device preparation table was relabelled and restructured for clarity: UID is now Cloud ID, Label is now Cloud Name, Status is now Current Status, Cloud connected moved next to Cloud Name, and the Capabilities column was removed as redundant with Driver mode, which is now labelled Driver Capabilities.

**1.5.6:** LAN-discovered devices with no LIFX Cloud presence are now trackable and creatable even when the rest of the fleet's cloud connectivity is healthy, not just during a full cloud outage. Previously, a device removed from (or never added to) LIFX Cloud would answer LAN discovery but never enter the saved device table unless the entire fleet's cloud fetch had failed at once.

**1.5.5:** Fixes a canonical-identity overwrite found during external review of 1.5.4. `childDniForRow()` now prefers a row's persisted child DNI over re-deriving it from the LAN UID; previously, a row that missed a validation cycle had its LAN UID cleared for reachability reasons, which could make the DNI lookup fall back toward the Cloud UID and wrongly conclude no child was installed, allowing a later rediscovery to overwrite the canonical identity and potentially create a duplicate child device, or allowing "Remove stale saved rows" to offer to delete a row whose child device still exists. Clearing LAN fields for reachability reasons no longer blanks identity fields for a row with an installed child, only its IP and last-seen timestamp.

**1.5.4:** Correctness and reliability maintenance release, verified through live-hub testing rather than static review alone. Fixed several zero-value command paths (Master defaults, Breathe/Pulse brightness, cached hue/saturation) where an explicit 0 was silently replaced by a nonzero default. Level-0 colour and colour-temperature commands now send a real power-off packet instead of just publishing an optimistic `switch: off` event while the bulb stays lit. Breathe/Pulse speed now accepts decimal seconds instead of silently falling back to the default. The Master Switch checkbox and an IP-changed row's selection no longer force themselves back to true on every page render, and child create/update no longer resets status-polling preferences to their defaults on routine updates. The Master Switch aggregate state now reconciles after individual child switch changes, including the fast local on/off path, with a debounce that stays correct under hub load. Child create/update results now report Created/Updated counts, not just Skipped/Failed. Fixed an identity-matching edge case where a device's LAN MAC can be reported two different ways depending on which response answered, which could silently orphan a row from its installed device on rediscovery. Widened Discovery's validation timing budget for larger fleets. Several smaller UI fixes: child-select checkboxes clear after a successful create/update instead of staying ticked, result messages clear after being shown once instead of persisting into a later session, create/update now picks up a pending local-name rename itself rather than requiring a separate step first, the discovered-lights list sorts by IP instead of label, and a duplicate infrared command was removed.

**1.5.2:** Adds native LIFX Breathe and Pulse colour effects as Rule Machine Custom Actions on colour-capable bulbs and the Master Switch, see [Breathe / Pulse colour effects](#breathe--pulse-colour-effects) above for full details.

**1.5.1:** Adds an on-demand WiFi signal strength check (under Advanced) that queries every saved device over LAN and adds a WiFi Signal column to the device preparation table, shown in dBm. Adds optional hourly background maintenance (on by default) that runs Discovery, Firmware check and WiFi signal check automatically on a staggered schedule (:00, :15 and :30 past each hour), so the device table stays current without needing to open the app.

**1.5.0:** Final beta. Fixes a discovery-reliability bug where devices could disappear on repeat scans, adds capability-aware device events, firmware checking and fall back to LAN-only discovery if LIFX Cloud is unreachable. Full changelog in the README.

**1.4.7:** Resilience patch from the same external code review, plus two urgent discovery-reliability fixes found while testing it. Fixed the actual root cause of devices being wiped to "LAN IP missing" on repeat Discovery runs: the adjacent-UID matching heuristic could refuse to match a device once enough devices in the fleet had cloud UIDs within 1 of each other's LAN MACs (very likely with bulbs bought in the same batch), even though the device had matched successfully before - it now prefers a device's already-confirmed UID first. Also paced the validation-probe LAN sends, which were firing as an unpaced burst. Separately: raised the discovery stale-run threshold to comfortably exceed a legitimate full run's worst-case duration; LAN refresh responses no longer emit hue/saturation/colour-temperature events to devices whose driver doesn't declare those capabilities, and the Master Switch now declares ColorMode; a bad LIFX Cloud response (rate limited, auth failure, server error) now falls back to LAN-only discovery instead of stopping combined discovery outright; the Check Firmware action now persists firmware version and build to the installed child device instead of only the diagnostic table; and refresh no longer overwrites a locally renamed device's label with the bulb's factory label.

**1.4.6:** Correctness patch from an external static code review, verified against current code before fixing. Explicit zero values (saturation 0, level 0) are no longer silently overwritten by defaults. `setLevel(0)` now sends a real LIFX power-off instead of just dimming to black while staying powered. Master Switch off no longer clobbers cached child brightness, and Master Switch refresh no longer collapses level to a binary 100/0. A light recoloured outside Hubitat (e.g. from the LIFX app) now has its colour mode reconciled on refresh instead of going white on the next brightness change. Discovery's scheduled jobs are now isolated from status polling and the firmware-check resend, so starting or stopping Discovery no longer silently disables them. Colour-temperature events are now gated to devices that actually support colour temperature. Individual child commands and LAN response parsing now survive Clear all Data for already-installed devices, matching how the Master Switch already behaved.

**1.4.5:** Adds an on-demand Firmware version check (under Advanced) that queries every saved device over LAN, including devices not yet installed as child devices, with a Firmware column added to the Device preparation table and a single automatic resend for any device that doesn't respond the first time. The Device preparation, LIFX Cloud source and LAN responses tables now auto-size their columns to content instead of using fixed pixel widths, wrapping any unexpectedly long value instead of stretching the table, while the shared identity columns (UID, Label, Local name, IP address, Last seen) stay aligned across all three tables. Reworded the Attribution section for accuracy after reviewing the original LIFX Master source directly.

**1.4.4:** Internal code-quality refactor of the app file - no behaviour change. Introduces named constants for the LIFX port, message-type codes, discovery pacing values and clamp bounds, and removes duplicated packet-building logic between individual child-device commands and Master Switch bulk commands (a shared `buildHsbkPayload`/`buildSetPowerPayload`/`sendPowerOnIfNeeded` are now used by both). This directly targets the class of bug that required two separate fixes in 1.4.2/1.4.3.

**1.4.3:** The LIFX Master Switch now exposes ColorControl and ColorTemperature as real capabilities (plus Light, restoring its light classification in Google Home/Dashboard/Rule Machine), adding Set Hue and Set Saturation support. Non-colour-capable member bulbs remain unaffected by colour commands, since the app only sends hue/saturation data to bulbs that actually support it. Set Color Temperature's parameter order now matches Hubitat's standard signature (temperature, level, transitionTime) so Rule Machine's own UI lines up correctly, while still treating level as an independent setting. Colour/CT/level commands now also send an explicit power-on when the light isn't already known to be on, matching Hue-style rule behaviour instead of requiring a separate On action.

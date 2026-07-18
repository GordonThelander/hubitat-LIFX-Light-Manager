# LIFX Light Manager for Hubitat

**Version:** 1.4.5

LIFX Light Manager is a Hubitat app and driver package for discovering, creating and locally controlling LIFX lights. It combines LIFX Cloud metadata with local LAN discovery so devices can be named and classified accurately, then controlled locally over the network after child devices are created.

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
- Driver format compatibility with Google Home that exposes device capabilities (known issue that colour has to be set once outside of Goole Home and then to use Google to change the colour manually to lock in the RGB capability)  

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

Use this manifest URL in Hubitat Package Manager:

```text
https://raw.githubusercontent.com/GordonThelander/hubitat-LIFX-Light-Manager/main/packageManifest.json
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
2. Add the **LIFX Light Manager** app in Hubitat.
3. Enter your own LIFX Personal Access Token.
4. Press **Discovery**.
5. Wait for network discovery to complete. This typically takes 2-3 minutes, and can take longer the first time or after using Clear all Data - the app paces its LAN traffic conservatively to stay within Hubitat's own outbound command rate limits.
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

## IP address change remediation

When discovery finds a different IP address for an already-created child device, the app marks that device for update with:

```text
Update required due to IP address change
```

Updating the selected child refreshes the stored device data and the visible `Lan Ip` current-state value. The Master Switch membership is recalculated after child updates.

## Current version

| Component | Version |
|---|---|
| Package | 1.4.5 |
| App | 1.4.5 |
| White Mono driver | 1.4.5 |
| Tunable White driver | 1.4.5 |
| Colour driver | 1.4.5 |
| Plus Colour driver | 1.4.5 |
| Master Switch driver | 1.4.5 |

## Known limitations

- Requires a LIFX Cloud token for discovery/enrichment.
- Control is local after devices are created.
- Multizone, Tile, Beam and advanced LIFX effects are not fully supported in this beta.
- Public release status should retain appropriate attribution and respect upstream licensing constraints.

## Attribution

This project takes its structural cues for the LIFX LAN packet layer from Robert Alan Heyes' **LIFX Master** integration (`robheyes`), with the framing and byte-level encoding tracing back to that reference. Everything built on top of that foundation, including Cloud-assisted discovery, UID matching, and the Master Switch model, is this project's own design.

## Status

**1.4.5:** Adds an on-demand Firmware version check (under Advanced) that queries every saved device over LAN, including devices not yet installed as child devices, with a Firmware column added to the Device preparation table and a single automatic resend for any device that doesn't respond the first time. The Device preparation, LIFX Cloud source and LAN responses tables now auto-size their columns to content instead of using fixed pixel widths, wrapping any unexpectedly long value instead of stretching the table, while the shared identity columns (UID, Label, Local name, IP address, Last seen) stay aligned across all three tables. Reworded the Attribution section for accuracy after reviewing the original LIFX Master source directly.

**1.4.4:** Internal code-quality refactor of the app file - no behaviour change. Introduces named constants for the LIFX port, message-type codes, discovery pacing values and clamp bounds, and removes duplicated packet-building logic between individual child-device commands and Master Switch bulk commands (a shared `buildHsbkPayload`/`buildSetPowerPayload`/`sendPowerOnIfNeeded` are now used by both). This directly targets the class of bug that required two separate fixes in 1.4.2/1.4.3.

**1.4.3:** The LIFX Master Switch now exposes ColorControl and ColorTemperature as real capabilities (plus Light, restoring its light classification in Google Home/Dashboard/Rule Machine), adding Set Hue and Set Saturation support. Non-colour-capable member bulbs remain unaffected by colour commands, since the app only sends hue/saturation data to bulbs that actually support it. Set Color Temperature's parameter order now matches Hubitat's standard signature (temperature, level, transitionTime) so Rule Machine's own UI lines up correctly, while still treating level as an independent setting. Colour/CT/level commands now also send an explicit power-on when the light isn't already known to be on, matching Hue-style rule behaviour instead of requiring a separate On action.

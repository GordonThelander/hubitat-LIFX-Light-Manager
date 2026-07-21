# LIFX Light Manager for Hubitat

**Version:** 1.5.18

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
| Package | 1.5.18 |
| App | 1.5.18 |
| White Mono driver | 1.5.6 |
| Tunable White driver | 1.5.6 |
| Colour driver | 1.5.7 |
| Plus Colour driver | 1.5.7 |
| Master Switch driver | 1.5.4 |

Tested on Hubitat Elevation platform version 2.5.0.159.

## Known limitations

- Requires a LIFX Cloud token for discovery/enrichment.
- Control is local after devices are created.
- Multizone, Tile, Beam and advanced LIFX effects are not fully supported in this beta.
- Public release status should retain appropriate attribution and respect upstream licensing constraints.

## Attribution

This project takes its structural cues for the LIFX LAN packet layer from Robert Alan Heyes' **LIFX Master** integration (`robheyes`), with the framing and byte-level encoding tracing back to that reference. Everything built on top of that foundation, including Cloud-assisted discovery, UID matching, and the Master Switch model, is this project's own design.

Full version history: see git log and `BACKLOG.md`.

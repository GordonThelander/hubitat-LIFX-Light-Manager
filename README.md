# LIFX Light Manager for Hubitat

**Version:** 1.4

LIFX Light Manager is a Hubitat app and driver package for discovering, creating and locally controlling LIFX lights. It combines LIFX Cloud metadata with local LAN discovery so devices can be named and classified accurately, then controlled locally over the network after child devices are created.

## What it does

- Uses a LIFX Personal Access Token for discovery and metadata enrichment.
- Discovers LIFX lights on the local LAN and records their current IP addresses.
- Maintains a device preparation table showing label, local name, IP address, group, product, capabilities, driver mode, cloud connection and status.
- Supports optional local Hubitat names before child-device creation.
- Handles Cloud ID to LAN UID mismatches, including the observed `cloud+1` MAC address anomaly edge case.
- Creates Hubitat child devices for mono, tunable white, colour and Plus/IR-capable LIFX lights.
- Detects changed IP addresses and marks affected devices for update.
- Updates child device data and visible current-state attributes after IP remediation.
- Provides a **LIFX Master Switch** aggregate device for whole-fleet control.
- Supports fast local on/off control for individual children and the Master Switch.
- Supports Master Switch colour and colour-temperature control, applying colour to colour-capable lights and level-only changes to non-colour lights.
- Supports optional lightweight LAN polling of installed child devices.
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
5. Wait for network discovery to complete. On a standard `/24` network this can take up to 2 minutes.
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

## IP address change remediation

When discovery finds a different IP address for an already-created child device, the app marks that device for update with:

```text
Update required due to IP address change
```

Updating the selected child refreshes the stored device data and the visible `Lan Ip` current-state value. The Master Switch membership is recalculated after child updates.

## Current version

| Component | Version |
|---|---|
| Package | 1.4.0 |
| App | 1.4 |
| White Mono driver | 1.4 |
| Tunable White driver | 1.4 |
| Colour driver | 1.4 |
| Plus Colour driver | 1.4 |
| Master Switch driver | 1.4 |

## Known limitations

- Requires a LIFX Cloud token for discovery/enrichment.
- Control is local after devices are created.
- Multizone, Tile, Beam and advanced LIFX effects are not fully supported in this beta.
- Public release status should retain appropriate attribution and respect upstream licensing constraints.

## Attribution

This project was developed after reviewing the original **LIFX Master** Hubitat integration by Robert Alan Heyes. It uses similar LIFX LAN protocol concepts, particularly around local UDP command dispatch, while adding a new cloud-assisted device discovery workflow and Master Switch management model.

## Status

1.3 is intended as a beta release for testing through Hubitat Package Manager.

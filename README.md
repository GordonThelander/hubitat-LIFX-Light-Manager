# LIFX Light Manager for Hubitat

**LIFX Light Manager** is a Hubitat Elevation app and driver package for discovering, creating and locally controlling LIFX lights on your LAN.

The app is designed around a curated device workflow: it uses LIFX Cloud metadata to enrich device names, product types and capabilities, then matches that data to locally discovered LAN devices so the resulting Hubitat child devices can be controlled locally and quickly.

## What it does

- Discovers LIFX lights using a cloud-assisted and LAN-matched workflow.
- Builds a curated device table showing device identity, label, IP address, product/capability data and child-device status.
- Creates Hubitat child devices for selected lights.
- Assigns drivers automatically based on detected device capability.
- Handles known Cloud UID / LAN UID mismatch cases, including adjacent `+1` / `-1` UID matching.
- Controls lights locally over the LIFX LAN protocol once devices are created.
- Provides fast individual `on` / `off` control suitable for Hubitat rules and groups.
- Provides an optional **LIFX Master Switch** aggregate device for all-LIFX on/off management.
- Supports IR-capable LIFX Plus devices through the Plus Colour driver.

## Package contents

```text
apps/
  LIFX_Light_Manager.groovy

drivers/
  LIFX_Curated_Local_White_Mono_Driver.groovy
  LIFX_Curated_Local_Tunable_White_Driver.groovy
  LIFX_Curated_Local_Colour_Driver.groovy
  LIFX_Curated_Local_Plus_Colour_Driver.groovy
  LIFX_Master_Switch_Driver.groovy

packageManifest.json
README.md
```

## Device drivers

| Driver | Used for |
|---|---|
| `LIFX Curated Local White Mono` | Fixed white / mono LIFX lights |
| `LIFX Curated Local Tunable White` | Tunable white / Day & Dusk style LIFX lights |
| `LIFX Curated Local Colour` | Colour-capable LIFX lights |
| `LIFX Curated Local Plus Colour` | Colour-capable LIFX Plus / IR-capable lights |
| `LIFX Master Switch` | Optional aggregate all-LIFX on/off device |

IR-capable devices are deliberately allocated to the Plus Colour driver so IR management commands and attributes are available.

## Installation using Hubitat Package Manager

Use this manifest URL in HPM:

```text
https://raw.githubusercontent.com/GordonThelander/hubitat-LIFX-Light-Manager/main/packageManifest.json
```

Recommended HPM flow:

1. Open **Hubitat Package Manager**.
2. Choose **Install**.
3. Choose **From a package manifest URL**.
4. Paste the manifest URL above.
5. Let HPM install the app and drivers.
6. Go to **Apps** and add the **LIFX Light Manager** user app.

## Manual installation

Manual installation is not recommended for normal use, but can be used for testing.

1. In Hubitat, go to **Apps Code**.
2. Add `apps/LIFX_Light_Manager.groovy`.
3. In **Drivers Code**, add each driver under `drivers/`.
4. Go to **Apps** and add the **LIFX Light Manager** app.

## Basic usage

1. Open **LIFX Light Manager** in Hubitat.
2. Enter your LIFX Cloud token.
3. Run **Discovery**.
4. Review the curated device table.
5. Select the devices you want to create.
6. Click **Create / update selected child devices**.
7. Optionally create the **LIFX Master Switch** for whole-fleet on/off management.

After child devices are created, normal light control is local over the LAN. The cloud token is used for discovery/enrichment, not routine on/off control.

## LIFX Master Switch

The **LIFX Master Switch** is an optional aggregate device that includes all curated LIFX devices with known LAN IP addresses.

Use it for:

- all LIFX lights on/off,
- quick manual fleet control,
- simple whole-house LIFX management,
- future whole-fleet functions.

It does not replace the individual child devices. Existing Hubitat rules can still target the individual lights directly.

## Performance design

The current performance baseline uses lightweight local UDP control for normal child-device `on` / `off` actions. This avoids heavy per-device discovery or UID-candidate processing during routine switching.

The intended behaviour is:

- individual child devices respond quickly,
- Hubitat rules that trigger multiple LIFX children respond near-instantly,
- the LIFX Master Switch provides an immediate bulk-control path.

## Current version

| Component | Version |
|---|---|
| Package | 4.7.6 |
| App | 4.7.6 |
| White Mono driver | 1.1.6 |
| Tunable White driver | 1.1.6 |
| Colour driver | 1.1.6 |
| Plus Colour driver | 1.1.7 |
| LIFX Master Switch driver | 1.0.1 |

## Notes and limitations

- LIFX Cloud access is required for the current discovery/enrichment workflow.
- Routine control is local after devices are created.
- Device creation depends on successful Cloud-to-LAN matching.
- Multizone, Tile and Beam-specific advanced features are not currently the focus of this package.
- If a child device was previously created with the wrong driver, delete and recreate it after updating the app and drivers.

## Attribution

This project was developed after reviewing the original Hubitat **LIFX Master** integration by Robert Alan Heyes. The original integration remains an important reference for LIFX LAN protocol handling and efficient local UDP command dispatch.

This package adds a different cloud-assisted discovery, curated device creation and management workflow for a specific Hubitat/LIFX use case.

## Repository layout for HPM

Keep filenames stable for HPM. Put versioning in `packageManifest.json`, app comments and driver comments, not in filenames.

Correct layout:

```text
hubitat-LIFX-Light-Manager/
  apps/
    LIFX_Light_Manager.groovy
  drivers/
    LIFX_Curated_Local_White_Mono_Driver.groovy
    LIFX_Curated_Local_Tunable_White_Driver.groovy
    LIFX_Curated_Local_Colour_Driver.groovy
    LIFX_Curated_Local_Plus_Colour_Driver.groovy
    LIFX_Master_Switch_Driver.groovy
  packageManifest.json
  README.md
```

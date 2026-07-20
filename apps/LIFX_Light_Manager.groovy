/*
 * LIFX Light Manager (Dev)
 * Namespace: Hubitat Integrations
 * Version: 1.5.3
 *
 * DEV BRANCH: renamed app/driver/DNI namespace so this can be installed and removed
 * freely alongside the production "LIFX Light Manager" app on the same hub, against
 * the same physical bulbs, without touching production child devices or automations.
 *
 * 1.5.3 correctness patch (F-01 to F-08 from the 1.5.2 code review):
 * - F-01: preserve valid 0 values in level/brightness/saturation command paths
 * - F-02: level-0 colour/CT commands now send a real SET_POWER off, not just an optimistic event
 * - F-03: Breathe/Pulse speed accepts decimal seconds (was silently ignored, fell back to default)
 * - F-04: Master Switch checkbox no longer forces itself back to true on every page render
 * - F-05: an unticked IP-changed row no longer forces itself back to selected on page reload
 * - F-06: child create/update no longer resets polling prefs to enabled/2min on every update
 * - F-07: Master Switch aggregate state now reconciles after individual child switch changes
 * - F-08: child create/update results now report Created/Updated counts, not just Skipped/Failed
 *
 * Purpose:
 * - Save only the curated child-driver preparation table between app launches
 * - Run LIFX Cloud discovery and LAN IP discovery as separate actions
 * - Cloud discovery updates the saved device table with labels, groups, products and capabilities
 * - LAN discovery updates the saved device table with local IPs by matching UID, including adjacent UID matching
 * - Source Cloud/LAN tables are runtime diagnostics only
 * - First four table columns aligned: UID, Label, IP address, Last seen
 * - LAN-only discovery can populate the saved device table when Cloud is unavailable
 * - Simplified normal UI: token field, Discovery button, device table, Clear all Data button
 * - Cloud and LAN discovery run sequentially from one Discovery button
 * - Cloud/LAN diagnostic tables are hidden behind an Advanced button
 * - Child device creation uses saved per-device checkboxes, editable prefix, corrected driver assignment, LAN UID for child DNI, and protocol target UID for control
 * - Child device creation enables lightweight LAN on/off polling at a 2-minute default interval
 *
 */

import groovy.transform.Field

definition(
    name: "LIFX Light Manager (Dev)",
    namespace: "Hubitat Integrations",
    author: "Gordon Thelander",
    description: "Maintains a saved LIFX device table from Cloud data and updates LAN IPs separately by UID matching.",
    category: "Convenience",
    menu: "Integrations",
    iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/icons/blank.png",
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/icons/blank.png",
    singleInstance: true,
    installOnOpen: true
)

preferences {
    page(name: "mainPage", title: "LIFX Light Manager (Dev)", install: true, uninstall: true)
}

// ---------------- Constants ----------------
// Not yet referenced anywhere - added as its own isolated step so a hub-side compile
// problem with @Field (unused elsewhere in this file until now) can be caught before
// anything depends on it.

// Shown as the main page's subtitle (see mainPage()) so the running app's version is visible
// without opening the code editor. Bump alongside the header comment above on every release.
@Field static final String APP_VERSION = "1.5.3"

@Field static final Integer LIFX_PORT = 56700

@Field static final Map LIFX_MSG = [
    GET_SERVICE: 2, STATE_SERVICE: 3, GET_VERSION: 32, STATE_VERSION: 33,
    GET_HOST_FIRMWARE: 14, STATE_HOST_FIRMWARE: 15,
    GET_WIFI_INFO: 16, STATE_WIFI_INFO: 17,
    GET_GROUP: 51, STATE_GROUP: 53, GET_LOCATION: 48, STATE_LOCATION: 50,
    GET_LABEL: 23, STATE_LABEL: 25, ACK: 45,
    LIGHT_GET: 101, LIGHT_SET_COLOR: 102, LIGHT_SET_WAVEFORM: 103, LIGHT_STATE: 107,
    LIGHT_GET_POWER: 116, LIGHT_SET_POWER: 117, LIGHT_STATE_POWER: 118,
    LIGHT_GET_INFRARED: 120, LIGHT_STATE_INFRARED: 121, LIGHT_SET_INFRARED: 122
].asImmutable()

// Breathe/Pulse waveform shapes (LIFX LAN protocol SetWaveform "waveform" byte).
@Field static final Integer WAVEFORM_SINE = 1
@Field static final Integer WAVEFORM_PULSE = 4
// No true "run forever" value exists in the protocol - a very large cycle count is used instead,
// since these effects are only ever expected to be stopped by a later rule action (a plain
// setColor/on/off), not by naturally exhausting their cycle count.
@Field static final Float WAVEFORM_INDEFINITE_CYCLES = 1000000.0f
// Default periods, used when the optional speed parameter is left blank. Rule Machine's Custom
// Action has no way to pass a plain literal NUMBER to a custom command - its only mechanism for a
// NUMBER parameter ("Meter") re-invokes the command repeatedly at that interval instead of
// calling it once with that value, which breaks Breathe's continuous fade (each re-invocation
// snaps back to the base colour and restarts it). STRING parameters don't have this problem
// (confirmed live), so the speed override is typed as a plain number of seconds in a STRING field.
@Field static final Integer BREATHE_PERIOD_MS = 3500
@Field static final Integer PULSE_PERIOD_MS = 800

// Named colour presets for Breathe/Pulse base/target colours, passed as plain ENUM command
// parameters (each rule picks its own pair directly) rather than read from a shared device -
// ENUM/String parameters don't have the NUMBER "Meter" problem above, so this is a reliable,
// per-rule-configurable Custom Action input instead of a global setting. Names and values match
// Rule Machine's own "Set Color" preset list. [hue100, saturation100, kelvin] - kelvin only
// matters when saturation is 0.
@Field static final Map<String, List<Integer>> NAMED_COLORS = [
    "Soft White": [0, 0, 2700],
    "White": [0, 0, 4000],
    "Daylight": [0, 0, 6500],
    "Warm White": [0, 0, 2200],
    "Red": [0, 100, 3500],
    "Orange": [8, 100, 3500],
    "Yellow": [17, 100, 3500],
    "Green": [33, 100, 3500],
    "Blue": [67, 100, 3500],
    "Purple": [75, 100, 3500],
    "Pink": [83, 50, 3500]
].asImmutable()

@Field static final Integer LIFX_FULL_ON = 65535
@Field static final Integer LIFX_FULL_OFF = 0

// Validation probes used to fire as an unpaced burst (one sendHubCommand per known device, back
// to back) - the same packet-burst pattern that caused the discovery sweep's "pending hub
// commands" backlog earlier, just at validation-probe scale. With a fleet of a dozen-plus devices
// this could delay some probes/responses past the window below, wrongly wiping a reachable
// device's LAN data as "unconfirmed". Pacing the sends and widening the window fixes both the
// cause and gives slower responses more room.
//
// Live-hub testing against a 13-device fleet showed the resulting 4.5s total budget
// (RESEND_DELAY + FINISH_DELAY) still wasn't enough - roughly half the fleet was wrongly wiped
// as unconfirmed in a single run despite every device being genuinely reachable. Widened further
// to an 8s total budget.
@Field static final Integer VALIDATION_PROBE_PACE_MS = 30
@Field static final Integer VALIDATION_RESEND_DELAY_MS = 3000
@Field static final Integer VALIDATION_FINISH_DELAY_MS = 5000
@Field static final Integer BROADCAST_PULSE_START_DELAY_MS = 50
@Field static final Integer BROADCAST_PULSE_INTERVAL_MS = 3000
@Field static final Integer SWEEP_BATCH_START_DELAY_MS = 20
@Field static final Integer SWEEP_BATCH_INTERVAL_MS = 300
@Field static final Integer SWEEP_PAUSE_FAST_MS = 150
@Field static final Integer SWEEP_PAUSE_SLOW_MS = 250
@Field static final Integer SWEEP_BATCH_SIZE = 2
@Field static final Long BROADCAST_DURATION_MS = 45000L
// A legitimate full run (validation + both broadcasts + fast/retry x2/slow sweeps at current
// pacing) can take up to ~423s worst case, so this stays comfortably above that rather than
// risking a second Discovery click superseding a run that's still legitimately working.
@Field static final Long STALE_RUN_THRESHOLD_MS = 600000L
@Field static final Long RECENT_REMOVAL_WINDOW_MS = 30000L
@Field static final Integer FIRMWARE_RESEND_DELAY_MS = 2500
@Field static final Integer WIFI_CHECK_RESEND_DELAY_MS = 2500
@Field static final Integer MASTER_RECONCILE_DEBOUNCE_MS = 500

@Field static final Integer PERCENT_MIN = 0
@Field static final Integer PERCENT_MAX = 100

// Named so discovery can cancel only its own scheduled callbacks (see cancelDiscoveryJobs()),
// instead of a bare unschedule() that also wipes out unrelated jobs like status polling and the
// firmware-check resend.
@Field static final List<String> DISCOVERY_SCHEDULED_JOBS = [
    "resendValidationProbe", "finishValidationProbe", "broadcastPulse", "processSweepBatch"
].asImmutable()

// Background maintenance jobs are staggered within the hour (0/15/30 past) rather than firing
// together, since discovery alone can legitimately take several minutes worst-case (see
// STALE_RUN_THRESHOLD_MS above) - starting firmware/WiFi checks mid-discovery would just add to
// the same outbound command budget discovery is already using.
@Field static final List<String> BACKGROUND_MAINTENANCE_JOBS = [
    "scheduledBackgroundDiscovery", "scheduledBackgroundFirmwareCheck", "scheduledBackgroundWifiCheck"
].asImmutable()

// ---------------- Hubitat lifecycle ----------------

def installed() { initialiseState() }
def updated() {
    initialiseState()
    // Advanced starts hidden on every visit, not just first install - updated() runs before
    // appButtonHandler() for the same click, so if this click was the Advanced button itself,
    // toggleAdvanced() still correctly shows it for this render; any other click leaves it hidden.
    atomicState.showAdvanced = false
    handleDiscoveryButtonFallback()
    configureStatusPolling(false)
    configureBackgroundMaintenance(false)
}
def uninstalled() { unschedule() }

def appButtonHandler(String btn) {
    if (btn == "discoverBtn") startCombinedDiscovery()
    if (btn == "createSelectedChildrenBtn") createOrUpdateSelectedChildDevicesFromCurated()
    if (btn == "createAllChildrenBtn") createOrUpdateAllChildDevicesFromCurated()
    if (btn == "renameDiscoveredLightsBtn") renameDiscoveredLightsFromSettings()
    if (btn == "applyStatusPollingBtn") configureStatusPolling()
    if (btn == "pollStatusNowBtn") pollManagedChildSwitchStatus()
    if (btn == "checkFirmwareBtn") checkDeviceFirmware()
    if (btn == "checkWifiBtn") checkWifiSignal()
    if (btn == "applyBackgroundMaintenanceBtn") configureBackgroundMaintenance()
    if (btn == "renameChildDevicesBtn") renameManagedChildDevicesFromSettings()
    if (btn == "createFastGroupBtn") createOrUpdateFastGroupChildDevice()
    if (btn == "advancedBtn") toggleAdvanced()
    if (btn == "clearAllBtn") clearAllData()
    if (btn?.startsWith("removeRow_")) removeSavedRowIfNoInstalledChild(btn.substring("removeRow_".length()))
}

void handleDiscoveryButtonFallback() {
    // Hubitat can occasionally persist a stray "discoverBtn" setting value across the
    // preferences update cycle even when it was not a real, fresh button click (e.g. every
    // time updated() fires while a discovery run is genuinely still in progress). Auto-starting
    // discovery from here proved unreliable in practice - it could re-trigger mid-flight and
    // reset a run that was already progressing normally. Just clear the stray value; if a click
    // is ever genuinely missed, the user can press Discovery again.
    try { app.removeSetting("discoverBtn") } catch (Throwable t) { log.debug "app.removeSetting(...) failed: ${t.message}" }
}

def mainPage(params = null) {
    initialiseState()
    Boolean advanced = atomicState.showAdvanced == true

    // Auto-refresh only while discovery is running. Leaving refresh on permanently makes
    // Hubitat re-render the page while editing text, ticking boxes, or selecting table text.
    if (isDiscoveryRunning()) {
        return dynamicPage(name: "mainPage", title: "v${APP_VERSION}", install: true, uninstall: true, refreshInterval: 3) {
            renderMainPageContent(advanced)
        }
    }

    return dynamicPage(name: "mainPage", title: "v${APP_VERSION}", install: true, uninstall: true) {
        renderMainPageContent(advanced)
    }
}

void renderMainPageContent(Boolean advanced) {
    section("<b>LIFX discovery</b>") {
        paragraph "Go to <a href='https://cloud.lifx.com/' target='_blank'>https://cloud.lifx.com/</a>, log in using your LIFX credentials, then use the top-right account menu to acquire a Personal Access Token. Paste that token below before running Discovery."
        input "lifxCloudToken", "password",
            title: "LIFX Personal Access Token",
            defaultValue: "",
            required: false,
            submitOnChange: true
        if (atomicState.tokenError) {
            paragraph "<div style='font-weight:bold;color:#cc0000'>${atomicState.tokenError}</div>"
        }
        input "discoverBtn", "button", title: "Discovery", submitOnChange: true
        if ((atomicState.status ?: "idle") == "validating") {
            paragraph "<div style='font-weight:bold;color:#0066cc'>Validating existing devices...</div>"
        } else if (isDiscoveryRunning()) {
            paragraph "<div style='font-weight:bold;color:#cc0000'>Scanning for new devices, please wait - this typically takes 2-3 minutes, and can take longer the first time or after Clear all Data</div>"
        } else if ((atomicState.status ?: "idle") == "complete") {
            paragraph "<div style='font-weight:bold;color:#008000'>Discovery Complete</div>"
        }
        input "childNamePrefix", "text",
            title: "<b>Optional child device name prefix</b>",
            description: "Example: Lounge. Leave blank to use the detected label exactly.",
            required: false,
            submitOnChange: false

        Map childOptions = childCreationOptions()
        if (childOptions) {
            paragraph childCreationActionHtml()
            paragraph "<b>Select and optionally rename discovered lights</b><br/>Tick the lights to create or update. To rename a light, enter a local Hubitat name directly below that discovered light entry, then click Apply local names before creating/updating child devices. Blank rename fields are ignored. This does not rename the light in LIFX Cloud."
            childOptions.each { uid, title ->
                String uidText = uid.toString()
                Map row = childCreationRowsByUid()[normalisedUid(uidText)] as Map
                String detected = html((row?.label ?: uidText).toString())
                String currentLocal = html((row?.customLabel ?: row?.localLabel ?: "").toString().trim())

                Boolean defaultSelected = childSelectDefault(uidText)
                // Only persist the default the first time this row's checkbox setting appears -
                // otherwise an explicit untick is undone on every subsequent page render for as
                // long as the row's IP-change condition remains true.
                if (defaultSelected && settings[childSelectSettingName(uidText)] == null) {
                    try { app.updateSetting(childSelectSettingName(uidText), [type: "bool", value: true]) } catch (Throwable t) { log.debug "app.updateSetting(...) failed: ${t.message}" }
                }
                input childSelectSettingName(uidText), "bool",
                    title: title.toString(),
                    defaultValue: defaultSelected,
                    required: false,
                    submitOnChange: true

                input discoveredLightRenameSettingName(uidText), "text",
                    title: "",
                    description: currentLocal ? "Current override: ${currentLocal}. Leave blank to keep it." : "Leave blank to keep detected name: ${detected}",
                    defaultValue: "Optional alternative local name",
                    required: false,
                    submitOnChange: false
            }
            input "renameDiscoveredLightsBtn", "button", title: "Apply local names", submitOnChange: true
            input masterSwitchSelectSettingName(), "bool",
                title: masterSwitchListEntryTitle(),
                defaultValue: true,
                required: false,
                submitOnChange: true
            input "createSelectedChildrenBtn", "button", title: "Create / update selected child devices", submitOnChange: true
            input "createAllChildrenBtn", "button", title: "Create / update all listed child devices", submitOnChange: true
        } else {
            paragraph "No creation-ready devices yet. Run Discovery first."
        }

        input "clearAllBtn", "button", title: "Clear all Data", submitOnChange: true
        input "advancedBtn", "button", title: advanced ? "Hide advanced" : "Advanced", submitOnChange: true
    }

    if (atomicState.childCreateResult) {
        section("<b>Child device creation</b>") {
            paragraph atomicState.childCreateResult
        }
    }

    if (atomicState.discoveredRenameResult) {
        section("<b>Discovered light rename</b>") {
            paragraph atomicState.discoveredRenameResult
        }
    }

    if (atomicState.childRenameResult) {
        section("<b>Child device rename</b>") {
            paragraph atomicState.childRenameResult
        }
    }

    section("<b>Device preparation table</b>") {
        paragraph curatedTableHtml()
    }

    if (advanced) {
        section("<b>Advanced status</b>") {
            paragraph statusHtml()
        }
        section("<b>Optional child status polling</b>") {
            paragraph "Polls installed LIFX child devices over LAN for on/off state only. This is intentionally lightweight and does not run firmware discovery or full device refresh."
            input "lifxStatusPollingEnabled", "bool",
                title: "Enable timed on/off status polling",
                defaultValue: false,
                required: false,
                submitOnChange: true
            input "lifxStatusPollIntervalMinutes", "enum",
                title: "Status polling interval",
                options: ["1":"1 minute", "2":"2 minutes", "5":"5 minutes", "10":"10 minutes", "15":"15 minutes", "30":"30 minutes"],
                defaultValue: "2",
                required: false,
                submitOnChange: true
            input "applyStatusPollingBtn", "button", title: "Apply polling settings", submitOnChange: true
            input "pollStatusNowBtn", "button", title: "Poll status now", submitOnChange: true
            if (atomicState.statusPollingResult) paragraph atomicState.statusPollingResult
        }
        section("<b>Firmware check</b>") {
            paragraph "Queries the LIFX LAN firmware version for every saved device with a known IP address, including devices not yet installed as child devices. Manual/on-demand only - not part of automatic Discovery."
            input "checkFirmwareBtn", "button", title: "Check firmware", submitOnChange: true
            if (atomicState.firmwareCheckResult) paragraph atomicState.firmwareCheckResult
        }
        section("<b>WiFi signal check</b>") {
            paragraph "Queries the LIFX LAN WiFi signal strength for every saved device with a known IP address, including devices not yet installed as child devices. Manual/on-demand only - not part of automatic Discovery."
            input "checkWifiBtn", "button", title: "Check WiFi signal", submitOnChange: true
            if (atomicState.wifiCheckResult) paragraph atomicState.wifiCheckResult
        }
        section("<b>Background maintenance</b>") {
            paragraph "Runs Discovery, Firmware check and WiFi signal check automatically once an hour (staggered a few minutes apart), so the device table stays reasonably current without needing to open the app. On by default."
            input "backgroundMaintenanceOn", "bool",
                title: "Enable hourly background maintenance",
                defaultValue: true,
                required: false,
                submitOnChange: true
            input "applyBackgroundMaintenanceBtn", "button", title: "Apply background maintenance settings", submitOnChange: true
            if (atomicState.backgroundMaintenanceResult) paragraph atomicState.backgroundMaintenanceResult
        }
        section("<b>Breathe / Pulse colour effects</b>") {
            paragraph "Trigger breathe(baseColour, targetColour, speedSeconds, baseBrightness, targetBrightness) or pulse(same) as a Custom Action on any colour-capable bulb or the Master Switch - each rule types its own values (Rule Machine has no dropdown support for custom command parameters). Only the first two are required. Valid colour names: Soft White, White, Daylight, Warm White, Red, Orange, Yellow, Green, Blue, Purple, Pink (case-insensitive). Speed is a plain number of seconds (e.g. 6), leave blank for the default (3.5s breathe, 0.8s pulse). Brightness is 0-100%, leave blank for full brightness. Both run until stopped by a later command (setColor/on/off). Wide hue swings (e.g. green to red) can look choppy rather than smooth - narrower hue gaps or saturation-based pairs (e.g. White to Blue) fade more gently."
        }
        section("<b>Remove stale saved rows</b>") {
            paragraph "Rows below have no installed Hubitat child device and can be safely removed from the saved device table - for example, a device deleted from LIFX Cloud. Rows with an installed child device are not listed here; remove the child device from Hubitat's Devices page first if one of those needs clearing."
            List<Map> removable = removableSavedRows()
            if (!removable) {
                paragraph "No removable rows right now."
            } else {
                removable.each { row ->
                    String uid = normalisedUid(row.id ?: row.uid)
                    if (!uid) return
                    input removeRowButtonName(uid), "button",
                        title: "Remove ${html(childLabelForRow(row))} (${uid})",
                        submitOnChange: true
                }
            }
            if (atomicState.rowRemovalResult) paragraph atomicState.rowRemovalResult
        }
        section("<b>LIFX Cloud source table</b>") {
            paragraph cloudTableHtml()
        }
        section("<b>LAN responses</b>") {
            paragraph lanTableHtml()
        }
    }
}

void initialiseState() {
    if (atomicState.records == null) atomicState.records = [:]
    if (atomicState.byIp == null) atomicState.byIp = [:]
    if (atomicState.cloudLights == null) atomicState.cloudLights = [:]
    if (atomicState.expectedIds == null) atomicState.expectedIds = [:]
    if (state.sequence == null) state.sequence = 1
    if (atomicState.status == null) atomicState.status = "idle"
    if (atomicState.phase == null) atomicState.phase = "Idle"
    if (atomicState.stats == null) atomicState.stats = emptyStats()
    if (atomicState.cloudStatus == null) atomicState.cloudStatus = "not tested"
    if (atomicState.curatedReady == null) atomicState.curatedReady = true
    if (atomicState.curatedRows == null) atomicState.curatedRows = [:]
    if (atomicState.discoveryMode == null) atomicState.discoveryMode = "cloud-led"
    if (atomicState.showAdvanced == null) atomicState.showAdvanced = false
    if (atomicState.childCreateResult == null) atomicState.childCreateResult = ""
    if (atomicState.discoveredRenameResult == null) atomicState.discoveredRenameResult = ""
    if (atomicState.childRenameResult == null) atomicState.childRenameResult = ""
    if (atomicState.statusPollingResult == null) atomicState.statusPollingResult = ""
    if (atomicState.firmwareCheckResult == null) atomicState.firmwareCheckResult = ""
    if (atomicState.rowRemovalResult == null) atomicState.rowRemovalResult = ""
    if (lifxCloudToken == null) app.updateSetting("lifxCloudToken", [type: "password", value: ""])
    if (fastGroupName == null) app.updateSetting("fastGroupName", [type: "text", value: "LIFX MASTER SWITCH"])
    // Default only when unset - this runs on every mainPage() render, so writing the default
    // unconditionally here would silently re-tick an explicit false back to true on the next page load.
    try {
        String settingName = masterSwitchSelectSettingName()
        if (settings[settingName] == null) app.updateSetting(settingName, [type: "bool", value: true])
    } catch (Throwable t) { log.debug "app.updateSetting(...) failed: ${t.message}" }
}

Map emptyStats() {
    [cloudLights:0, expected:0, matched:0, missing:0, broadcastPulses:0, secondBroadcastPulses:0, sweepSent:0, sweepTotal:0, sweepSkipped:0, retryPass:0, rawResponses:0, service:0, version:0, errors:0, lanOnly:0]
}

// ---------------- Cloud-first locator flow ----------------

Boolean isDiscoveryRunning() {
    return ((atomicState.status ?: "idle") in ["validating", "cloud", "starting-lan", "broadcast", "sweep"])
}

// A run that has looked "in progress" for far longer than the documented worst case (2 minutes)
// is treated as abandoned rather than blocking every future Discovery click forever. This is a
// deliberately generous ceiling - it only exists as a recovery path if something upstream leaves
// status stuck without ever reaching "complete" or an error state.
Boolean isDiscoveryRunStale() {
    Long startedAt = (atomicState.discoveryRunStartedAt ?: 0L) as Long
    if (!startedAt) return true
    return (now() - startedAt) > STALE_RUN_THRESHOLD_MS
}

// Cancels only discovery's own scheduled callbacks, leaving unrelated jobs (status polling,
// firmware-check resend) untouched - a bare unschedule() previously wiped out every scheduled
// job app-wide whenever discovery started or stopped.
void cancelDiscoveryJobs() {
    DISCOVERY_SCHEDULED_JOBS.each { jobName ->
        try { unschedule(jobName) } catch (Throwable t) { log.debug "unschedule(${jobName}) failed: ${t.message}" }
    }
}

void toggleAdvanced() {
    atomicState.showAdvanced = !(atomicState.showAdvanced == true)
}

void startCombinedDiscovery() {
    // Hubitat can route a single Discovery click through both appButtonHandler() and the
    // updated()-triggered handleDiscoveryButtonFallback() (see that method's comment). Without
    // this guard, the second invocation would reset validationStartedAt and the in-flight probe
    // state from the first invocation mid-flight, making every row look unconfirmed and falling
    // through to a full rediscovery even though nothing actually failed to respond.
    if (isDiscoveryRunning() && !isDiscoveryRunStale()) {
        log.info "Discovery already running - ignoring duplicate start request"
        return
    }
    cancelDiscoveryJobs()
    String token = configuredLifxCloudToken()
    if (!token) {
        atomicState.status = "idle"
        atomicState.phase = "Discovery not started"
        atomicState.tokenError = "LIFX Personal Access Token is required before Discovery can run. Go to https://cloud.lifx.com/, log in with your LIFX credentials, then use the top-right account menu to acquire a Personal Access Token."
        atomicState.lastDiscoveryStatus = atomicState.tokenError
        log.warn atomicState.tokenError
        return
    }
    atomicState.tokenError = null
    atomicState.runLanAfterCloud = true
    // Self-rescheduling callbacks (broadcastPulse, processSweepBatch) capture this at entry and
    // re-check it before rescheduling themselves, so a callback from a superseded run stops
    // instead of continuing to run in parallel with - and corrupting the state of - a fresh run.
    atomicState.discoveryRunId = ((atomicState.discoveryRunId ?: 0) as Integer) + 1
    atomicState.discoveryRunStartedAt = now()
    atomicState.records = [:]
    atomicState.byIp = [:]
    atomicState.stats = emptyStats()
    atomicState.status = "validating"
    atomicState.discoveryMode = "cloud-led"
    atomicState.phase = "Validating existing device IP addresses before rediscovery"
    atomicState.cloudStatus = "not tested"
    atomicState.curatedReady = false
    state.sweepQueue = []
    startValidationProbe()
}

// Before wiping any saved IP/UID data, send a unicast probe to each row's currently-known IP.
// Rows that answer within the validation window are left untouched; only rows that fail to
// respond are cleared and handed to the full broadcast/sweep rediscovery cycle. The probe is
// sent twice (immediate + a follow-up resend) and given a multi-second window, mirroring the
// resilience of the existing broadcast phase rather than relying on a single quick packet.
void startValidationProbe() {
    Map curated = atomicState.curatedRows ?: [:]
    List<String> knownIps = curated.values()
        .collect { (it as Map)?.ip?.toString()?.trim() }
        .findAll { it }
        .unique()

    if (!knownIps) {
        beginFullLanRediscoveryCycle()
        return
    }

    atomicState.validationStartedAt = now()
    atomicState.phase = "Validating ${knownIps.size()} known device IP address(es) before rediscovery"
    state.validationIps = knownIps
    knownIps.each { ip -> sendLifx(ip, LIFX_MSG.GET_SERVICE); pauseExecution(VALIDATION_PROBE_PACE_MS) } // unicast probe
    runInMillis(VALIDATION_RESEND_DELAY_MS, "resendValidationProbe")
}

@SuppressWarnings("unused")
void resendValidationProbe() {
    if ((atomicState.status ?: "") != "validating") return
    Integer myRunId = (atomicState.discoveryRunId ?: 0) as Integer
    List<String> ips = (state.validationIps ?: []) as List<String>
    if (allValidationIpsConfirmed(ips)) {
        clearUnconfirmedLanDiscoveryFields()
        beginFullLanRediscoveryCycle()
        return
    }
    ips.each { ip -> sendLifx(ip, LIFX_MSG.GET_SERVICE); pauseExecution(VALIDATION_PROBE_PACE_MS) } // unicast probe, resent in case the first packet was dropped
    if (((atomicState.discoveryRunId ?: 0) as Integer) != myRunId) return
    runInMillis(VALIDATION_FINISH_DELAY_MS, "finishValidationProbe")
}

@SuppressWarnings("unused")
void finishValidationProbe() {
    if ((atomicState.status ?: "") != "validating") return
    clearUnconfirmedLanDiscoveryFields()
    beginFullLanRediscoveryCycle()
}

Boolean allValidationIpsConfirmed(List<String> ips) {
    if (!ips) return true
    Map curated = atomicState.curatedRows ?: [:]
    Long validationStartedAt = (atomicState.validationStartedAt ?: 0L) as Long
    List<String> confirmedIps = curated.values()
        .collect { it as Map }
        .findAll { row -> row.ip && row.lanLastSeen && (row.lanLastSeen as Long) >= validationStartedAt }
        .collect { (it as Map).ip.toString() }
    return ips.every { confirmedIps.contains(it) }
}

void beginFullLanRediscoveryCycle() {
    atomicState.forceFullLanDiscovery = false
    atomicState.status = "cloud"
    atomicState.phase = "Network Discovery in progress - this typically takes 2-3 minutes, and can take longer the first time or after Clear all Data, please wait for it to complete"
    atomicState.cloudStatus = "retrieving"
    fetchCloudLights(false)
}

void clearLanFieldsForRow(Map row) {
    row.previousIp = row.ip ?: row.previousIp
    row.ip = ""
    row.port = row.port ?: LIFX_PORT
    row.lanUid = ""
    row.lanMac = ""
    row.mac = ""
    row.sourceMac = ""
    row.controlUid = ""
    row.protocolTargetUid = ""
    row.target = ""
    row.lanLastSeen = null
    row.status = "Awaiting LAN rediscovery"
}

// Only clears rows whose IP did not answer the validation probe sent from startValidationProbe().
// A row counts as confirmed when its lanLastSeen timestamp was refreshed at or after the probe
// was sent, since parseLifx() updates matching curated rows in place as responses arrive.
void clearUnconfirmedLanDiscoveryFields() {
    Map curated = atomicState.curatedRows ?: [:]
    if (!curated) return
    Long validationStartedAt = (atomicState.validationStartedAt ?: 0L) as Long
    curated.each { k, v ->
        Map row = (v ?: [:]) as Map
        Long lanLastSeen = row.lanLastSeen as Long
        Boolean confirmedDuringValidation = row.ip && lanLastSeen && lanLastSeen >= validationStartedAt
        if (!confirmedDuringValidation) clearLanFieldsForRow(row)
        curated[k] = row
    }
    atomicState.curatedRows = curated
}

void startCloudDiscovery() {
    cancelDiscoveryJobs()
    atomicState.runLanAfterCloud = false
    atomicState.cloudLights = [:]
    atomicState.expectedIds = [:]
    atomicState.stats = emptyStats()
    atomicState.status = "cloud"
    atomicState.discoveryMode = "cloud-led"
    atomicState.phase = "Fetching LIFX Cloud devices and updating saved device table"
    atomicState.cloudStatus = "retrieving"
    atomicState.curatedReady = true
    fetchCloudLights(true)
}

void startLanDiscovery() {
    cancelDiscoveryJobs()
    Boolean forceFullScan = atomicState.forceFullLanDiscovery == true
    atomicState.forceFullLanDiscovery = false
    atomicState.records = [:]
    atomicState.byIp = [:]
    atomicState.stats = emptyStats()
    atomicState.status = "starting-lan"
    atomicState.phase = "Preparing LAN discovery from saved device table"
    atomicState.curatedReady = true
    atomicState.started = now()
    atomicState.lastResponse = 0L
    state.sweepQueue = []

    Map curated = atomicState.curatedRows ?: [:]

    if (!curated) {
        atomicState.discoveryMode = "lan-only"
        atomicState.expectedIds = [:]
        Map stats = atomicState.stats ?: emptyStats()
        stats.cloudLights = 0
        stats.expected = 0
        stats.matched = 0
        stats.missing = 0
        atomicState.stats = stats
        atomicState.phase = "No saved cloud device table - LAN discovery will build LAN-only curated rows"
        startInitialBroadcast()
        return
    }

    atomicState.discoveryMode = "cloud-led"
    atomicState.expectedIds = curated.collectEntries { k, v -> [(k.toString()): true] }
    Map stats = atomicState.stats ?: emptyStats()
    stats.cloudLights = curated.size()
    stats.expected = expectedCloudLanDiscoveryCount() ?: curated.size()
    stats.matched = discoveredCloudLanCount()
    stats.missing = Math.max(0, ((stats.expected ?: 0) as Integer) - discoveredCloudLanCount())
    atomicState.stats = stats

    if (allExpectedFound() && !forceFullScan) {
        atomicState.status = "complete"
        atomicState.phase = "Saved device table already has IP addresses for all rows"
        configureStatusPolling(false)
        return
    }

    startInitialBroadcast()
}


void mergeCloudIntoCurated(Map cloud) {
    Map curated = atomicState.curatedRows ?: [:]
    cloud.each { id, light ->
        Map c = light as Map
        String uid = normaliseCloudId(id)
        if (!uid) return
        Map row = (curated[uid] ?: [:]) as Map
        row.id = uid
        row.uid = uid
        row.label = c.label ?: row.label
        row.groupName = c.groupName ?: row.groupName
        row.locationName = c.locationName ?: row.locationName
        row.connected = c.connected
        row.productName = c.productName ?: row.productName
        row.productIdentifier = c.productIdentifier ?: row.productIdentifier
        row.hasColor = c.hasColor
        row.hasVariableColorTemp = c.hasVariableColorTemp
        row.hasIr = c.hasIr
        row.hasChain = c.hasChain
        row.hasMatrix = c.hasMatrix
        row.hasMultizone = c.hasMultizone
        row.minKelvin = c.minKelvin
        row.maxKelvin = c.maxKelvin
        row.lastSeenCloud = c.lastSeenCloud
        row.secondsSinceSeen = c.secondsSinceSeen
        row.capability = cloudCapability(row)
        row.driverMode = cloudDriverMode(row)
        row.cloudUpdated = now()
        row.status = row.ip ? (row.status ?: "Cloud refreshed - LAN IP retained") : "Cloud refreshed - LAN IP missing"
        curated[uid] = row
    }
    atomicState.curatedRows = curated
}

String defaultLifxCloudToken() {
    return ""
}

String configuredLifxCloudToken() {
    String token = lifxCloudToken?.toString()?.trim()
    return token
}

void fetchCloudLights(Boolean cloudOnly) {
    String token = configuredLifxCloudToken()
    if (!token) {
        atomicState.lastDiscoveryStatus = "LIFX Cloud token is required for cloud discovery. Enter a Personal Access Token and run Discovery again."
        log.warn "LIFX Cloud token is not configured"
        return
    }

    try {
        httpGet([
            uri: "https://api.lifx.com",
            path: "/v1/lights/all",
            headers: [
                "Authorization": "Bearer ${token}",
                "Accept": "application/json"
            ],
            contentType: "application/json",
            timeout: 20
        ]) { resp ->
            Integer code = resp?.status as Integer
            if (code < 200 || code >= 300) {
                // A rate-limited, auth-failed or transient cloud response used to just stop here,
                // even when LAN-only discovery using saved metadata could still proceed - mirror
                // the catch block below's fallback behaviour instead of stranding it.
                String reason = (code == 401 || code == 403) ? "authentication failed - check your LIFX Cloud token" :
                    code == 429 ? "rate limited by LIFX Cloud" :
                    code >= 500 ? "LIFX Cloud server error" : "unexpected response"
                atomicState.cloudStatus = "HTTP ${code} - ${reason}"
                if (atomicState.runLanAfterCloud == true) {
                    atomicState.runLanAfterCloud = false
                    atomicState.phase = "Cloud API ${reason} (HTTP ${code}) - falling back to LAN-only discovery"
                    startLanDiscovery()
                } else {
                    atomicState.status = "cloud-error"
                    atomicState.phase = "Cloud API ${reason} (HTTP ${code})"
                    atomicState.curatedReady = true
                }
                return
            }

            List lights = []
            if (resp?.data instanceof List) lights = resp.data as List
            else if (resp?.data) lights = [resp.data]

            Map cloud = [:]
            lights.each { item ->
                Map light = flattenCloudLight(item as Map)
                String id = normaliseCloudId(light.id)
                if (id) {
                    light.id = id
                    cloud[id] = light
                }
            }

            atomicState.cloudLights = cloud
            mergeCloudIntoCurated(cloud)
            atomicState.expectedIds = (atomicState.curatedRows ?: [:]).collectEntries { k, v -> [(k.toString()): true] }

            Map stats = atomicState.stats ?: emptyStats()
            stats.cloudLights = cloud.size()
            stats.expected = expectedCloudLanDiscoveryCount() ?: (atomicState.curatedRows ?: [:]).size()
            stats.matched = discoveredCloudLanCount()
            stats.missing = Math.max(0, stats.expected - stats.matched)
            atomicState.stats = stats

            atomicState.cloudStatus = "OK - ${cloud.size()} cloud light(s) returned"
            if (atomicState.runLanAfterCloud == true) {
                atomicState.runLanAfterCloud = false
                startLanDiscovery()
            } else {
                atomicState.status = "complete"
                atomicState.phase = cloud.isEmpty() ? "No cloud lights returned" : "Cloud discovery complete - saved device table updated"
                atomicState.curatedReady = true
            }
        }
    } catch (Throwable t) {
        atomicState.cloudStatus = "ERROR - ${safeMessage(t.message)}"
        if (atomicState.runLanAfterCloud == true) {
            atomicState.runLanAfterCloud = false
            startLanDiscovery()
        } else {
            atomicState.status = "cloud-error"
            atomicState.phase = "Cloud API error"
            atomicState.curatedReady = true
        }
    }
}

// Broadcast reaches every device on the subnet for a handful of packets, versus the sweep's
// one-packet-per-candidate-address cost, so it's worth giving it a much longer, more patient
// run before falling back to the expensive full sweep - see broadcastPulse() for pacing.
void startInitialBroadcast() {
    startBroadcastPhase("initial", "Initial broadcast discovery (up to 45 seconds)", BROADCAST_DURATION_MS)
}

void startSecondBroadcast() {
    if (allExpectedFound()) {
        finishLocator("Discovery completed - all expected cloud devices have LAN IPs")
        return
    }
    startBroadcastPhase("second", "Second broadcast discovery pass after sweep (up to 45 seconds)", BROADCAST_DURATION_MS)
}

void startBroadcastPhase(String stage, String phaseText, Long durationMs) {
    atomicState.status = "broadcast"
    atomicState.broadcastStage = stage
    atomicState.phase = phaseText
    atomicState.broadcastUntil = now() + durationMs
    runInMillis(BROADCAST_PULSE_START_DELAY_MS, "broadcastPulse")
}

@SuppressWarnings("unused")
void broadcastPulse() {
    if ((atomicState.status ?: "") != "broadcast") return
    Integer myRunId = (atomicState.discoveryRunId ?: 0) as Integer

    if (allExpectedFound()) {
        finishLocator("Discovery completed - all expected cloud devices have LAN IPs")
        return
    }

    String stage = atomicState.broadcastStage ?: "initial"
    if (now() >= ((atomicState.broadcastUntil ?: 0L) as Long)) {
        if (stage == "initial") {
            startSweepPhase("fast", 1, SWEEP_PAUSE_FAST_MS, "secondBroadcast")
        } else {
            startRetrySweep(1)
        }
        return
    }

    broadcastTargets().each { ip ->
        sendLifx(ip.toString(), LIFX_MSG.GET_SERVICE)
        sendLifx(ip.toString(), LIFX_MSG.GET_VERSION)
    }

    Map stats = atomicState.stats ?: emptyStats()
    if (stage == "second") {
        stats.secondBroadcastPulses = ((stats.secondBroadcastPulses ?: 0) as Integer) + 1
    } else {
        stats.broadcastPulses = ((stats.broadcastPulses ?: 0) as Integer) + 1
    }
    atomicState.stats = stats
    if (((atomicState.discoveryRunId ?: 0) as Integer) != myRunId) return
    // Broadcast pulses go through the same rate-limited hub command queue as the sweep, so this
    // is paced conservatively too (4 packets/pulse) rather than the original 500ms/8-packets-
    // per-second rate that contributed to the "pending hub commands" backlog.
    runInMillis(BROADCAST_PULSE_INTERVAL_MS, "broadcastPulse")
}

void startRetrySweep(Integer pass) {
    if (allExpectedFound()) {
        finishLocator("Discovery completed - all expected cloud devices have LAN IPs")
        return
    }
    startSweepPhase("retry", pass, SWEEP_PAUSE_FAST_MS, pass < 2 ? "retryNext" : "slowSweep")
}

void startSlowSweep() {
    if (allExpectedFound()) {
        finishLocator("Discovery completed - all expected cloud devices have LAN IPs")
        return
    }
    startSweepPhase("slow", 1, SWEEP_PAUSE_SLOW_MS, "finish")
}

void startSweepPhase(String kind, Integer pass, Integer pauseMs, String after) {
    if (allExpectedFound()) {
        finishLocator("Discovery completed - all expected cloud devices have LAN IPs")
        return
    }

    atomicState.status = "sweep"
    atomicState.sweepKind = kind
    atomicState.sweepPass = pass
    atomicState.sweepPauseMs = pauseMs
    atomicState.afterSweep = after

    if (kind == "fast") {
        atomicState.phase = "Fast /24 locator sweep - ${pauseMs} ms per host"
    } else if (kind == "retry") {
        atomicState.phase = "Retry sweep ${pass} of 2 - ${pauseMs} ms per host, skipping already matched cloud devices"
    } else {
        atomicState.phase = "Slow fallback sweep - ${pauseMs} ms per host, skipping already matched cloud devices"
    }

    String subnet = hubSubnet()
    if (!subnet) {
        atomicState.status = "error"
        atomicState.phase = "Unable to determine hub subnet"
        atomicState.curatedReady = true
        return
    }

    Map byIp = atomicState.byIp ?: [:]
    List queue = []
    Integer skipped = 0
    for (int host = 1; host <= 254; host++) {
        String ip = "${subnet}${host}"
        Boolean skip = false
        if (kind == "fast") {
            // Initial fast sweep skips IPs that already responded during first broadcast.
            skip = byIp[ip] != null
        } else {
            // Retry sweeps only skip IPs already matched to cloud. LAN-only IPs may be retried.
            skip = isIpAlreadyMatchedToCloud(ip)
        }
        if (skip) skipped++ else queue << ip
    }

    state.sweepQueue = queue
    Map stats = atomicState.stats ?: emptyStats()
    stats.sweepTotal = queue.size()
    stats.sweepSent = 0
    stats.sweepSkipped = skipped
    stats.retryPass = kind == "retry" ? pass : (kind == "slow" ? 3 : 0)
    atomicState.stats = stats

    runInMillis(SWEEP_BATCH_START_DELAY_MS, "processSweepBatch")
}

@SuppressWarnings("unused")
void processSweepBatch() {
    if ((atomicState.status ?: "") != "sweep") return
    Integer myRunId = (atomicState.discoveryRunId ?: 0) as Integer

    if (allExpectedFound()) {
        finishLocator("Discovery completed - all expected cloud devices have LAN IPs")
        return
    }

    List queue = state.sweepQueue ?: []
    if (!queue) {
        String after = atomicState.afterSweep ?: "finish"
        if (after == "secondBroadcast") {
            startSecondBroadcast()
        } else if (after == "retryNext") {
            startRetrySweep(((atomicState.sweepPass ?: 1) as Integer) + 1)
        } else if (after == "slowSweep") {
            startSlowSweep()
        } else {
            Map curatedSnapshot = atomicState.curatedRows ?: [:]
            List<String> snapshotDump = curatedSnapshot.values()
                .collect { it as Map }
                .collect { r -> "${r.id ?: r.uid}:${r.label ?: '?'}:connected=${r.connected}:ip=${(r.ip ?: '').toString() ?: 'NONE'}".toString() }
            log.debug "finishLocator triggered from processSweepBatch() queue-exhausted path. expected=${expectedCloudLanDiscoveryCount()}, found=${discoveredCloudLanCount()}. Full snapshot: ${snapshotDump}"
            finishLocator("Resilient IP location cycle complete")
        }
        return
    }

    Integer pauseMs = ((atomicState.sweepPauseMs ?: 50) as Integer)
    // Batch size and inter-batch delay were previously 10 / 20ms - aggressive enough that
    // Hubitat's own "pending hub commands" backpressure warning reached 80+ queued sends during
    // a sweep (escalating to platform-level "consider disabling app/device" errors), meaning
    // outbound LAN packets were being queued far faster than the hub could actually dispatch
    // them (seen directly in hub logs). Sending fewer packets per batch and giving more time
    // between batches lets the hub's outbound queue drain in between.
    Integer batchSize = Math.min(SWEEP_BATCH_SIZE, queue.size())
    for (int i = 0; i < batchSize; i++) {
        if (allExpectedFound()) break
        String ip = queue.remove(0).toString()
        sendLifx(ip, LIFX_MSG.GET_VERSION) // exposes MAC + product/version
        Map stats = atomicState.stats ?: emptyStats()
        stats.sweepSent = ((stats.sweepSent ?: 0) as Integer) + 1
        atomicState.stats = stats
        // Blocks this scheduled job (processSweepBatch, self-rescheduled via runInMillis) for up
        // to batchSize x pauseMs ms per batch cycle across the whole subnet sweep.
        pauseExecution(pauseMs)
    }
    state.sweepQueue = queue
    if (((atomicState.discoveryRunId ?: 0) as Integer) != myRunId) return
    runInMillis(SWEEP_BATCH_INTERVAL_MS, "processSweepBatch")
}

Boolean isIpAlreadyMatchedToCloud(String ip) {
    if (!ip) return false
    Map byIp = atomicState.byIp ?: [:]
    String lanUid = byIp[ip]?.toString()
    if (!lanUid) return false
    return findCuratedMatchForLanUid(lanUid, atomicState.curatedRows ?: [:]) != null
}

void finishLocator(String reason) {
    cancelDiscoveryJobs()
    state.sweepQueue = []
    updateMatchStats()
    atomicState.status = "complete"
    atomicState.phase = "Discovery completed"
    atomicState.curatedReady = true
    refreshMasterSwitchMembership()
    configureStatusPolling(false)
}

void stopLocator() {
    cancelDiscoveryJobs()
    if ((atomicState.status ?: "idle") in ["validating", "cloud", "broadcast", "sweep"]) {
        updateMatchStats()
        atomicState.status = "stopped"
        atomicState.phase = "Stopped"
        atomicState.curatedReady = true
    }
    // Previously left status polling disabled until manually reconfigured, since this used to be
    // a bare unschedule() that also cancelled the polling job - re-arm it here, matching
    // finishLocator()'s existing behaviour.
    configureStatusPolling(false)
}

void clearSourceTables() {
    stopLocator()
    atomicState.records = [:]
    atomicState.byIp = [:]
    atomicState.cloudLights = [:]
    atomicState.expectedIds = [:]
    atomicState.stats = emptyStats()
    atomicState.status = "idle"
    atomicState.phase = "Source tables cleared. Saved device table retained."
    atomicState.cloudStatus = "not tested"
    atomicState.curatedReady = true
    state.sweepQueue = []
}

void clearSavedCuratedTable() {
    clearAllData()
}

void clearAllData() {
    stopLocator()
    clearChildSelectionSettings()
    atomicState.records = [:]
    atomicState.byIp = [:]
    atomicState.cloudLights = [:]
    atomicState.expectedIds = [:]
    atomicState.curatedRows = [:]
    atomicState.stats = emptyStats()
    atomicState.status = "idle"
    atomicState.phase = "All discovery data cleared."
    atomicState.cloudStatus = "not tested"
    atomicState.curatedReady = true
    atomicState.runLanAfterCloud = false
    atomicState.childCreateResult = ""
    atomicState.discoveredRenameResult = ""
    atomicState.childRenameResult = ""
    atomicState.statusPollingResult = ""
    atomicState.firmwareCheckResult = ""
    atomicState.rowRemovalResult = ""
    atomicState.recentRowRemovals = [:]
    try { app.removeSetting("selectedChildUids") } catch (Throwable t) { log.debug "app.removeSetting(...) failed: ${t.message}" }
    state.sweepQueue = []
}

Boolean allExpectedFound() {
    if ((atomicState.discoveryMode ?: "cloud-led") == "lan-only") return false
    Integer expected = expectedCloudLanDiscoveryCount()
    if (expected <= 0) return false
    return discoveredCloudLanCount() >= expected
}

Integer expectedCloudLanDiscoveryCount() {
    Map curated = atomicState.curatedRows ?: [:]
    if (!curated) return 0
    // Cloud-led discovery should stop when all discoverable cloud-backed rows have LAN details.
    // Offline cloud rows are excluded because they cannot reliably answer LAN probes.
    return curated.values().findAll { item ->
        Map row = item as Map
        return cloudUidForRow(row) && row.connected != false
    }.size() as Integer
}

Integer discoveredCloudLanCount() {
    Map curated = atomicState.curatedRows ?: [:]
    if (!curated) return 0
    return curated.values().findAll { item ->
        Map row = item as Map
        return cloudUidForRow(row) && row.connected != false && ((row.ip ?: '').toString().trim())
    }.size() as Integer
}

Integer cloudRowCount() {
    return (atomicState.cloudLights ?: [:]).size() as Integer
}

Integer curatedRowCount() {
    return (atomicState.curatedRows ?: [:]).size() as Integer
}

Integer curatedWithIpCount() {
    Map curated = atomicState.curatedRows ?: [:]
    if (!curated) return 0
    return curated.values().findAll { row -> ((row as Map).ip ?: '').toString().trim() }.size() as Integer
}

Integer curatedMatchedCount() {
    return curatedWithIpCount()
}

void updateMatchStats() {
    Map curated = atomicState.curatedRows ?: [:]
    Map records = atomicState.records ?: [:]

    Integer matched = discoveredCloudLanCount()

    Integer lanOnly = 0
    records.each { mac, dev ->
        Map match = findCuratedMatchForLanUid(mac.toString(), curated)
        if (!match) lanOnly++
    }

    Map stats = atomicState.stats ?: emptyStats()
    stats.expected = expectedCloudLanDiscoveryCount() ?: curated.size()
    stats.matched = matched
    stats.missing = Math.max(0, ((stats.expected ?: 0) as Integer) - matched)
    stats.lanOnly = lanOnly
    atomicState.stats = stats
}

// ---------------- Cloud model ----------------
// ---------------- Cloud model ----------------

Map flattenCloudLight(Map item) {
    Map group = item?.group instanceof Map ? item.group as Map : [:]
    Map locationMap = item?.location instanceof Map ? item.location as Map : [:]
    Map product = item?.product instanceof Map ? item.product as Map : [:]
    Map caps = product?.capabilities instanceof Map ? product.capabilities as Map : [:]
    Map color = item?.color instanceof Map ? item.color as Map : [:]

    [
        id: item?.id,
        uuid: item?.uuid,
        label: item?.label,
        connected: item?.connected,
        power: item?.power,
        brightness: item?.brightness,
        kelvin: color?.kelvin,
        effect: item?.effect,
        groupId: group?.id,
        groupName: group?.name,
        locationId: locationMap?.id,
        locationName: locationMap?.name,
        productName: product?.name,
        productIdentifier: product?.identifier,
        productCompany: product?.company,
        hasColor: caps?.has_color,
        hasVariableColorTemp: caps?.has_variable_color_temp,
        hasIr: caps?.has_ir,
        hasChain: caps?.has_chain,
        hasMatrix: caps?.has_matrix,
        hasMultizone: caps?.has_multizone,
        minKelvin: caps?.min_kelvin,
        maxKelvin: caps?.max_kelvin,
        lastSeenCloud: item?.last_seen,
        secondsSinceSeen: item?.seconds_since_seen
    ]
}

String normaliseCloudId(value) {
    if (!value) return ""
    String s = value.toString().replaceAll("[^0-9A-Fa-f]", "").toLowerCase()
    if (s.size() == 12) return s
    if (s.size() > 12) return s.substring(s.size() - 12)
    return s
}

// ---------------- LIFX response parser ----------------

@SuppressWarnings("unused")
def parseLifx(response) {
    Map parsed = parseLifxResponse(response)
    if (!parsed || parsed.error) {
        Map stats = atomicState.stats ?: emptyStats()
        stats.errors = ((stats.errors ?: 0) as Integer) + 1
        atomicState.stats = stats
        return
    }

    String ip = parsed.ip
    // Hubitat's response description can expose two related identifiers:
    // 1) params.mac/sourceMac: the Wi-Fi/LAN interface MAC, often Cloud UID + 1
    // 2) LIFX header target: the protocol target/serial, usually the Cloud UID
    // Use sourceMac for LAN matching, but keep protocolTargetUid for child command packets.
    String sourceMac = normaliseMac(parsed.sourceMac ?: parsed.mac ?: parsed.target)
    String protocolTargetUid = normaliseMac(parsed.target ?: parsed.protocolTarget ?: parsed.mac)
    String mac = sourceMac
    if (!ip || !mac || mac == "000000000000") return
    if (!protocolTargetUid || protocolTargetUid == "000000000000") protocolTargetUid = mac

    atomicState.lastResponse = now()

    Map records = atomicState.records ?: [:]
    Map byIp = atomicState.byIp ?: [:]
    Map expected = atomicState.expectedIds ?: [:]
    Map curated = atomicState.curatedRows ?: [:]

    Map dev = (records[mac] ?: [:]) as Map
    dev.ip = ip
    dev.port = LIFX_PORT
    dev.mac = mac
    dev.sourceMac = sourceMac
    dev.target = protocolTargetUid
    dev.protocolTargetUid = protocolTargetUid
    dev.controlUid = protocolTargetUid
    dev.firstSeen = dev.firstSeen ?: now()
    dev.lastSeen = now()
    dev.rawTypes = ((dev.rawTypes ?: []) + [parsed.type]).unique()
    dev.payloadHex = parsed.payloadHex ?: dev.payloadHex

    Map cloudMatch = findCuratedMatchForLanUid(mac, curated)
    Map c = cloudMatch?.curated as Map
    dev.expectedFromCloud = cloudMatch != null
    dev.cloudMatchType = cloudMatch?.matchType ?: ""

    if (c) {
        dev.cloudMatched = true
        dev.cloudId = c.id
        dev.cloudUid = c.id
        dev.lanUid = mac
        dev.controlUid = protocolTargetUid
        dev.protocolTargetUid = protocolTargetUid
        dev.cloudUuid = c.uuid
        dev.cloudLabel = c.label
        dev.cloudGroup = c.groupName
        dev.cloudLocation = c.locationName
        dev.cloudConnected = c.connected
        dev.cloudProductName = c.productName
        dev.cloudProductIdentifier = c.productIdentifier
        dev.cloudHasColor = c.hasColor
        dev.cloudHasVariableColorTemp = c.hasVariableColorTemp
        dev.cloudHasIr = c.hasIr
        dev.cloudHasChain = c.hasChain
        dev.cloudHasMatrix = c.hasMatrix
        dev.cloudHasMultizone = c.hasMultizone
        dev.cloudMinKelvin = c.minKelvin
        dev.cloudMaxKelvin = c.maxKelvin
        dev.baseCapability = cloudCapability(c)
        dev.driverMode = cloudDriverMode(c)
    } else {
        dev.cloudMatched = false
        dev.lanUid = mac
        dev.controlUid = protocolTargetUid
        dev.protocolTargetUid = protocolTargetUid
    }

    Map stats = atomicState.stats ?: emptyStats()
    stats.rawResponses = ((stats.rawResponses ?: 0) as Integer) + 1

    switch (parsed.type as Integer) {
        case LIFX_MSG.STATE_SERVICE:
            stats.service = ((stats.service ?: 0) as Integer) + 1
            break
        case LIFX_MSG.STATE_VERSION:
            Map version = parseStateVersion(parsed.payloadHex)
            dev.vendor = version.vendor ?: dev.vendor
            dev.product = version.product ?: dev.product
            dev.version = version.version ?: dev.version
            if (!dev.baseCapability) dev.baseCapability = capabilityForProduct(dev.product)
            if (!dev.driverMode) dev.driverMode = driverModeForProduct(dev.product)
            if (c) {
                c.lanProduct = dev.product ?: c.lanProduct
                c.status = c.status ?: "LAN found - ${cloudMatch?.matchType ?: ''}"
            } else {
                // Only chase group/location/label over LAN when there's no cloud match to supply
                // them already - this chain used to fire unconditionally for every responding
                // device, adding 3 unthrottled extra round-trips each and badly backing up the
                // hub's outbound command queue during a normal cloud-led discovery run.
                sendLifx(ip, LIFX_MSG.GET_GROUP)
            }
            stats.version = ((stats.version ?: 0) as Integer) + 1
            break
        case LIFX_MSG.STATE_HOST_FIRMWARE:
            // Only sent in response to the manual Check firmware button, not automatic discovery -
            // reuses the same cloud-matched write-back path as STATE_VERSION above.
            Map fw = parseFirmwarePayload(parsed.payloadHex)
            if (c) {
                c.firmwareVersion = fw.version ?: c.firmwareVersion
                c.firmwareBuild = fw.build ?: c.firmwareBuild
                c.firmwareCheckedAt = now()
                // Every driver already declares hostFirmware/hostFirmwareBuild attributes and
                // reads them back from device data on initialize() - only this write-back was
                // missing, leaving those fields permanently blank on the installed device.
                def firmwareChild = getChildDevice(childDniForRow(c))
                if (firmwareChild) {
                    try {
                        if (fw.version) {
                            firmwareChild.updateDataValue('hostFirmware', fw.version.toString())
                            firmwareChild.sendEvent(name: 'hostFirmware', value: fw.version)
                        }
                        if (fw.build) {
                            firmwareChild.updateDataValue('hostFirmwareBuild', fw.build.toString())
                            firmwareChild.sendEvent(name: 'hostFirmwareBuild', value: fw.build.toString())
                        }
                    } catch (Throwable t) { log.debug "firmware child data update failed: ${t.message}" }
                }
            }
            break
        case LIFX_MSG.STATE_WIFI_INFO:
            // Only sent in response to the manual Check WiFi signal button - reuses the same
            // cloud-matched write-back path as STATE_HOST_FIRMWARE above.
            Map wifi = parseWifiInfoPayload(parsed.payloadHex)
            if (c && wifi.rssi != null) {
                c.wifiRssi = wifi.rssi
                c.wifiCheckedAt = now()
                def wifiChild = getChildDevice(childDniForRow(c))
                if (wifiChild) {
                    try {
                        wifiChild.updateDataValue('rssi', wifi.rssi.toString())
                        wifiChild.sendEvent(name: 'rssi', value: wifi.rssi, unit: 'dBm')
                    } catch (Throwable t) { log.debug "wifi child data update failed: ${t.message}" }
                }
            }
            break
        case LIFX_MSG.STATE_GROUP:
            Map groupData = parseLabelPayload(parsed.payloadHex)
            if (groupData.label) dev.group = groupData.label
            sendLifx(ip, LIFX_MSG.GET_LOCATION)
            break
        case LIFX_MSG.STATE_LOCATION:
            Map locationData = parseLabelPayload(parsed.payloadHex)
            if (locationData.label) dev.location = locationData.label
            sendLifx(ip, LIFX_MSG.GET_LABEL)
            break
        case LIFX_MSG.STATE_LABEL:
            String label = decodeLabel(parsed.payloadHex)
            if (label) dev.label = officialDeviceName(label)
            break
    }

    if (!c && (atomicState.discoveryMode ?: "cloud-led") == "lan-only") {
        mergeLanOnlyIntoCurated(dev)
    }


    if (c) {
        String cloudId = cloudMatch.cloudId?.toString() ?: c.id?.toString()
        if (cloudId) {
            c.ip = ip
            c.port = LIFX_PORT
            // Once a row has an installed child device, its lanUid is already the DNI this app
            // committed to (lifxdev-curated-<lanUid>) - overwriting it here would silently orphan
            // the row from its real installed device the next time a response happens to report
            // the LIFX identity's other flavour (see findCuratedMatchForLanUid() for why a device
            // can report two different values). Record the alternate flavour separately instead,
            // so future responses using either flavour still match this row without ever changing
            // which DNI it resolves to. Before installation there's no DNI to protect yet, so the
            // row is free to keep refining its lanUid as discovery narrows in on the best match.
            Boolean hasInstalledChild = getChildDevice(childDniForRow(c)) != null
            if (!hasInstalledChild) {
                c.lanUid = mac
            } else if (normalisedUid(c.lanUid) != normalisedUid(mac)) {
                c.lanUidAlt = mac
            }
            c.protocolTargetUid = protocolTargetUid
            c.controlUid = protocolTargetUid
            c.cloudMatchType = cloudMatch.matchType ?: ""
            c.lanLastSeen = now()
            c.status = "LAN found - ${c.cloudMatchType}${mac && mac != cloudId ? ' - LAN UID ' + mac : ''}${protocolTargetUid && protocolTargetUid != mac ? ' - target ' + protocolTargetUid : ''}"
            if (dev.product) c.lanProduct = dev.product
            curated[cloudId] = c
            atomicState.curatedRows = curated
        }
    }

    records[mac] = dev
    byIp[ip] = mac
    atomicState.records = records
    atomicState.byIp = byIp
    atomicState.stats = stats

    if ((atomicState.status ?: "") in ["broadcast", "sweep"] && allExpectedFound()) {
        Map curatedSnapshot = atomicState.curatedRows ?: [:]
        List<String> snapshotDump = curatedSnapshot.values()
            .collect { it as Map }
            .collect { r -> "${r.id ?: r.uid}:${r.label ?: '?'}:connected=${r.connected}:ip=${(r.ip ?: '').toString() ?: 'NONE'}".toString() }
        log.debug "finishLocator triggered from parseLifx() by response from mac=${mac} ip=${ip}. expected=${expectedCloudLanDiscoveryCount()}, found=${discoveredCloudLanCount()}, savedRowCount=${curatedSnapshot.size()}. Full snapshot: ${snapshotDump}"
        finishLocator("Discovery completed - all expected cloud devices have LAN IPs")
    } else {
        updateMatchStats()
    }
}

Map parseLifxResponse(response) {
    try {
        Map params = parseDeviceParams(response.description)
        String payloadHex = params.payload ?: ""
        if (!payloadHex || payloadHex.size() < 72) return [error: "shortPayload"]
        Integer msgType = leU16(payloadHex, 64)
        String targetFromHeader = normaliseMac(payloadHex.substring(16, 28).toLowerCase())
        String sourceMac = normaliseMac(params.mac)
        String mac = normaliseMac(sourceMac ?: targetFromHeader)
        String ip = hexToIp(params.ip)
        return [
            type: msgType,
            target: targetFromHeader,
            protocolTarget: targetFromHeader,
            sourceMac: sourceMac,
            mac: mac,
            ip: ip,
            payloadHex: payloadHex.size() > 72 ? payloadHex.substring(72) : ""
        ]
    } catch (Throwable t) {
        return [error: t.message]
    }
}

Map parseDeviceParams(String description) {
    Map out = [:]
    description?.findAll(~/(\w+):([0-9A-Fa-f]+)/) { match -> out[match[1]] = match[2] }
    return out
}

Map parseStateVersion(String payloadHex) {
    if (!payloadHex || payloadHex.size() < 24) return [:]
    [vendor: leU32(payloadHex, 0), product: leU32(payloadHex, 8), version: leU32(payloadHex, 16)]
}

Map parseFirmwarePayload(String payloadHex) {
    // STATE_HOST_FIRMWARE payload: build(u64) + reserved(u64) + minor(u16) + major(u16).
    if (!payloadHex || payloadHex.size() < 40) return [:]
    Long build = leU64(payloadHex, 0)
    Integer minor = leU16(payloadHex, 32)
    Integer major = leU16(payloadHex, 36)
    return [build: build, major: major, minor: minor, version: "${major}.${minor}".toString()]
}

Map parseWifiInfoPayload(String payloadHex) {
    // STATE_WIFI_INFO payload: signal(float32) + tx(u32) + rx(u32) + reserved(i16). Only signal
    // is used - it's a raw linear value, not already in dBm, hence the log10 conversion below
    // (the same conversion community LIFX drivers use for this same field).
    if (!payloadHex || payloadHex.size() < 8) return [:]
    Float signal = leFloat32(payloadHex, 0)
    if (signal == null || signal <= 0f) return [:]
    Integer rssi = Math.floor(10 * Math.log10(signal) + 0.5).toInteger()
    return [rssi: rssi]
}



Map parseLabelPayload(String payloadHex) {
    if (!payloadHex || payloadHex.size() < 96) return [:]
    return [label: officialDeviceName(decodeLabel(payloadHex.substring(32)))]
}

String decodeLabel(String payloadHex) {
    try {
        String labelHex = (payloadHex ?: "").take(64)
        byte[] bytes = hexToBytes(labelHex) as byte[]
        return new String(bytes, "UTF-8").replaceAll("\u0000", "").trim()
    } catch (Throwable t) {
        log.debug "decodeLabel() failed: ${t.message}"
        return ""
    }
}

String officialDeviceName(String label) {
    if (!label) return ""
    return label.toString().replaceAll("[\u0000-\u001F]", "").trim()
}

void mergeLanOnlyIntoCurated(Map dev) {
    if (!dev?.mac) return
    Map curated = atomicState.curatedRows ?: [:]
    String uid = normalisedUid(dev.mac)
    if (!uid) return
    Map row = (curated[uid] ?: [:]) as Map
    row.id = uid
    row.uid = uid
    row.label = dev.label ?: row.label ?: "LIFX ${uid}"
    row.ip = dev.ip ?: row.ip
    row.port = dev.port ?: LIFX_PORT
    row.lanUid = uid
    row.controlUid = normalisedUid(dev.controlUid ?: dev.protocolTargetUid ?: dev.target ?: uid)
    row.protocolTargetUid = row.controlUid
    row.groupName = dev.group ?: row.groupName ?: ""
    row.locationName = dev.location ?: row.locationName ?: ""
    row.lanProduct = dev.product ?: row.lanProduct
    row.productName = row.productName ?: (dev.product ? "LAN product ${dev.product}" : "")
    row.capability = dev.baseCapability ?: capabilityForProduct(dev.product)
    row.driverMode = dev.driverMode ?: driverModeForProduct(dev.product)
    row.connected = row.connected
    row.lanLastSeen = now()
    row.status = "LAN discovered - cloud unavailable/not loaded"
    curated[uid] = row
    atomicState.curatedRows = curated
}


// ---------------- Child device creation and local control ----------------

List<Map> childCreationRows() {
    Map curated = atomicState.curatedRows ?: [:]
    if (!curated) return []
    return curated.values()
        .collect { it as Map }
        .findAll { rowReadyForChild(it as Map) }
        .sort { a, b ->
            String al = ((a as Map).label ?: "").toString().toLowerCase()
            String bl = ((b as Map).label ?: "").toString().toLowerCase()
            Integer cmp = al <=> bl
            if (cmp != 0) return cmp
            return compareUid((a as Map).id ?: (a as Map).uid ?: (a as Map).lanUid, (b as Map).id ?: (b as Map).uid ?: (b as Map).lanUid)
        }
}

String driverDisplayName(String driverName) {
    return normaliseDriverDisplayName(driverName)
}

Map childCreationOptions() {
    Map options = [:]
    childCreationRows().each { item ->
        Map row = item as Map
        String uid = normalisedUid(row.id ?: row.uid ?: row.lanUid)
        if (!uid) return
        def child = getChildDevice(childDniForRow(row))
        String driver = driverTypeForRow(row)
        String driverDisplay = driverDisplayName(driver)
        String currentDriver = installedDriverName(child)
        String currentDriverDisplay = driverDisplayName(currentDriver)
        Boolean ipChanged = rowHasInstalledIpChange(row)
        String installed = child ? (currentDriver && !driverNamesEquivalent(currentDriver, driver) ? "installed as ${currentDriverDisplay}; expected ${driverDisplay}" : "installed") : "<span style='color:#cc6600;font-weight:bold'>not installed</span>"
        String label = html(childLabelForRow(row))
        String ip = (row.ip ?: "no IP").toString()
        String note = ipChanged ? " - <span style='color:#cc0000;font-weight:bold'>Update required due to IP address change</span>" : ""
        options[uid] = "${label} - ${ip} - ${driverDisplay} - ${installed}${note}".toString()
    }
    return options
}

Map childCreationRowsByUid() {
    Map out = [:]
    childCreationRows().each { item ->
        Map row = item as Map
        String uid = normalisedUid(row.id ?: row.uid ?: row.lanUid)
        if (uid) out[uid] = row
    }
    return out
}

Boolean rowHasInstalledIpChange(Map row) {
    if (!rowReadyForChild(row)) return false
    def child = getChildDevice(childDniForRow(row))
    if (!child) return false
    String newIp = (row.ip ?: "").toString().trim()
    String oldIp = ""
    try { oldIp = (child.getDataValue('ip') ?: "").toString().trim() } catch (Throwable t) { log.debug "oldIp assignment failed: ${t.message}" }
    return oldIp && newIp && oldIp != newIp
}

Boolean childSelectDefault(String uidValue) {
    String uid = normalisedUid(uidValue)
    Map row = childCreationRowsByUid()[uid] as Map
    return rowHasInstalledIpChange(row)
}

String childCreationActionHtml() {
    return "Tick one or more devices. Each tick is saved immediately, then click Create / update selected child devices. Use the all-listed button only when you intentionally want every listed device created or updated. The LIFX MASTER SWITCH appears at the bottom of this list and is enabled by default as the aggregate control device."
}

String masterSwitchSelectSettingName() {
    return "selectMasterSwitch"
}

String masterSwitchListEntryTitle() {
    String status = getChildDevice(fastGroupDni()) ? "installed" : "not installed"
    String displayName = html(fastGroupLabel())
    return "${displayName} - provides overall control of all the above LIFX lights - ${status}".toString()
}

Boolean isMasterSwitchSelected() {
    String settingName = masterSwitchSelectSettingName()
    try {
        def value = settings[settingName]
        if (value == null) return true
        return truthy(value)
    } catch (Throwable t) { log.debug "isMasterSwitchSelected() settings lookup failed: ${t.message}" }
    try {
        def value = this."${settingName}"
        if (value == null) return true
        return truthy(value)
    } catch (Throwable t) { log.debug "isMasterSwitchSelected() property lookup failed: ${t.message}" }
    return true
}

String childSelectSettingName(String uidValue) {
    String uid = normalisedUid(uidValue)
    return uid ? "selectChild_${uid}".toString() : "selectChild_invalid"
}

Boolean isChildUidSelected(String uidValue) {
    String settingName = childSelectSettingName(uidValue)
    try { return truthy(settings[settingName]) } catch (Throwable t) { log.debug "truthy(...) failed: ${t.message}" }
    try { return truthy(this."${settingName}") } catch (Throwable t) { log.debug "truthy(...) failed: ${t.message}" }
    return false
}

List selectedChildUidsFromCheckboxes() {
    Map options = childCreationOptions()
    List selected = []
    options?.keySet()?.each { uid ->
        if (isChildUidSelected(uid.toString())) selected << uid.toString()
    }
    return selected.unique()
}

void clearChildSelectionSettings() {
    try {
        childCreationOptions()?.keySet()?.each { uid ->
            try { app.removeSetting(childSelectSettingName(uid.toString())) } catch (Throwable t) { log.debug "removeSetting(selectChild) failed: ${t.message}" }
        }
    } catch (Throwable t) { log.debug "clearChildSelectionSettings() failed: ${t.message}" }
}


String discoveredLightRenameActionHtml() {
    List rows = childCreationRows()
    if (!rows) return "No discovered lights are ready to rename yet. Run Discovery first."
    return "Enter a local Hubitat name beside any discovered light you want renamed before child-device creation, then click Apply discovered light names. Blank fields are ignored. This does not rename the light in LIFX Cloud."
}

String discoveredLightRenameSettingName(String uidValue) {
    String uid = normalisedUid(uidValue)
    String safe = (uid ?: "").replaceAll("[^A-Za-z0-9_]", "_")
    return safe ? "renameDiscovered_${safe}".toString() : "renameDiscovered_invalid"
}

void renderDiscoveredLightRenameInputs() {
    List rows = childCreationRows()
    if (!rows) return

    rows.each { item ->
        Map row = item as Map
        String uid = normalisedUid(row.id ?: row.uid ?: row.lanUid)
        if (!uid) return
        String cloudName = html((row.label ?: uid).toString())
        String localName = html((row.customLabel ?: row.localLabel ?: "").toString().trim())
        String current = localName ?: cloudName
        String ip = (row.ip ?: "no IP").toString()
        input discoveredLightRenameSettingName(uid), "text",
            title: "${current} - ${ip}",
            description: localName ? "Current local name override. Leave blank to keep it." : "Detected name: ${cloudName}. Leave blank to keep it.",
            required: false,
            submitOnChange: false
    }
    input "renameDiscoveredLightsBtn", "button", title: "Apply discovered light names", submitOnChange: true
}

String discoveredLightRenameSettingValue(String uidValue) {
    String settingName = discoveredLightRenameSettingName(uidValue)
    String value = ""
    try { value = (settings[settingName] ?: "").toString().trim() } catch (Throwable t) { log.debug "value assignment failed: ${t.message}" }
    if (!value) {
        try { value = (this."${settingName}" ?: "").toString().trim() } catch (Throwable t) { log.debug "value assignment failed: ${t.message}" }
    }
    if (value.equalsIgnoreCase("Optional alternative local name")) return ""
    return value
}

void renameDiscoveredLightsFromSettings() {
    Map curated = atomicState.curatedRows ?: [:]
    if (!curated) {
        atomicState.discoveredRenameResult = "No discovered lights found to rename. Run Discovery first."
        return
    }

    List renamed = []
    List skipped = []
    List failed = []

    childCreationRows().each { item ->
        Map row = item as Map
        String uid = normalisedUid(row.id ?: row.uid ?: row.lanUid)
        if (!uid) return

        String newName = discoveredLightRenameSettingValue(uid)
        if (!newName) return

        if (newName.size() > 80) {
            skipped << "${html(row.label ?: uid)}: name is too long; keep it under 80 characters."
            return
        }

        try {
            String oldDisplay = childLabelForRow(row)
            row.customLabel = newName
            row.localLabel = newName
            row.childStatus = row.childDni ? "Local name updated" : "Local name set"
            curated[row.id ?: row.uid ?: uid] = row

            def existing = getChildDevice(childDniForRow(row))
            if (existing) {
                try { existing.setLabel(childLabelForRow(row)) } catch (Throwable t) { log.debug "existing.setLabel(...) failed: ${t.message}" }
                try { existing.updateDataValue("displayLabel", childLabelForRow(row)) } catch (Throwable t) { log.debug "existing.updateDataValue(...) failed: ${t.message}" }
                try { existing.updateDataValue("localLabel", newName) } catch (Throwable t) { log.debug "existing.updateDataValue(...) failed: ${t.message}" }
                try { existing.sendEvent(name: "label", value: childLabelForRow(row), displayed: false) } catch (Throwable t) { log.debug "sendEvent(label) failed: ${t.message}" }
            }

            renamed << "${html(oldDisplay)} &rarr; ${html(childLabelForRow(row))}"
            try { app.updateSetting(discoveredLightRenameSettingName(uid), [type: "text", value: ""]) } catch (Throwable t) { log.debug "app.updateSetting(...) failed: ${t.message}" }
        } catch (Throwable t) {
            failed << "${html(row.label ?: uid)}: ${html(safeMessage(t.message))}"
        }
    }

    atomicState.curatedRows = curated
    refreshMasterSwitchMembership()

    String result = "<b>Discovered-light rename action</b><br/>"
    if (renamed) result += "<br/><b>Renamed</b><br/>${renamed.join('<br/>')}<br/>"
    if (skipped) result += "<br/><b>Skipped</b><br/>${skipped.join('<br/>')}<br/>"
    if (failed) result += "<br/><b>Failed</b><br/>${failed.join('<br/>')}<br/>"
    atomicState.discoveredRenameResult = (renamed || skipped || failed) ? result : "No discovered-light rename fields were completed."
}

String childRenameActionHtml() {
    List children = managedLifxLightChildren()
    if (!children) return "No installed LIFX child devices found yet. Create child devices first, then return here to rename them."
    return "Enter a new Hubitat child-device name beside any device you want renamed, then click Apply child device renames. Blank fields are ignored. These are local Hubitat names only; they do not rename the light in the LIFX cloud or LIFX mobile app."
}

String childRenameSettingNameForDni(String dniValue) {
    String safe = (dniValue ?: "").toString().replaceAll("[^A-Za-z0-9_]", "_")
    return safe ? "renameChild_${safe}".toString() : "renameChild_invalid"
}

void renderChildRenameInputs() {
    List children = managedLifxLightChildren()
    if (!children) return

    children.each { child ->
        String dni = ""
        String currentName = ""
        try { dni = child.deviceNetworkId?.toString() ?: "" } catch (Throwable t) { log.debug "dni assignment failed: ${t.message}" }
        try { currentName = html(child.displayName?.toString() ?: child.name?.toString() ?: dni) } catch (Throwable t) { log.debug "currentName assignment failed: ${t.message}"; currentName = dni }
        input childRenameSettingNameForDni(dni), "text",
            title: "Rename ${currentName}",
            description: "Leave blank to keep current name.",
            required: false,
            submitOnChange: false
    }
    input "renameChildDevicesBtn", "button", title: "Apply child device renames", submitOnChange: true
}

String childRenameSettingValue(String dniValue) {
    String settingName = childRenameSettingNameForDni(dniValue)
    try { return (settings[settingName] ?: "").toString().trim() } catch (Throwable t) { log.debug "childRenameSettingValue() settings lookup failed: ${t.message}" }
    try { return (this."${settingName}" ?: "").toString().trim() } catch (Throwable t) { log.debug "childRenameSettingValue() property lookup failed: ${t.message}" }
    return ""
}

Map curatedRowsByChildDni() {
    Map out = [:]
    Map curated = atomicState.curatedRows ?: [:]
    curated.each { k, v ->
        Map row = (v ?: [:]) as Map
        String dni = row.childDni?.toString() ?: childDniForRow(row)
        if (dni) out[dni] = [key: k, row: row]
    }
    return out
}

void renameManagedChildDevicesFromSettings() {
    List children = managedLifxLightChildren()
    if (!children) {
        atomicState.childRenameResult = "No installed LIFX child devices found to rename."
        return
    }

    Map curated = atomicState.curatedRows ?: [:]
    Map byDni = curatedRowsByChildDni()
    List renamed = []
    List skipped = []
    List failed = []

    children.each { child ->
        String dni = ""
        String oldName = ""
        try { dni = child.deviceNetworkId?.toString() ?: "" } catch (Throwable t) { log.debug "dni assignment failed: ${t.message}" }
        try { oldName = child.displayName?.toString() ?: child.name?.toString() ?: dni } catch (Throwable t) { log.debug "oldName assignment failed: ${t.message}"; oldName = dni }

        String newName = childRenameSettingValue(dni)
        if (!newName) return

        if (newName.size() > 80) {
            skipped << "${html(oldName)}: name is too long; keep it under 80 characters."
            return
        }

        try {
            child.setLabel(newName)
            Map match = byDni[dni] as Map
            if (match?.row) {
                Map row = match.row as Map
                row.customLabel = newName
                row.localLabel = newName
                row.childStatus = "Renamed"
                String key = (match.key ?: row.id ?: row.uid ?: row.lanUid)?.toString()
                if (key) curated[key] = row
                try { child.updateDataValue("displayLabel", newName) } catch (Throwable t) { log.debug "child.updateDataValue(...) failed: ${t.message}" }
                try { child.updateDataValue("localLabel", newName) } catch (Throwable t) { log.debug "child.updateDataValue(...) failed: ${t.message}" }
                try { child.sendEvent(name: "label", value: newName, displayed: false) } catch (Throwable t) { log.debug "sendEvent(label) failed: ${t.message}" }
            }
            renamed << "${html(oldName)} &rarr; ${html(newName)}"
            try { app.updateSetting(childRenameSettingNameForDni(dni), [type: "text", value: ""]) } catch (Throwable t) { log.debug "app.updateSetting(...) failed: ${t.message}" }
        } catch (Throwable t) {
            failed << "${html(oldName)}: ${html(safeMessage(t.message))}"
        }
    }

    atomicState.curatedRows = curated
    refreshMasterSwitchMembership()

    String result = "<b>Child-device rename action</b><br/>"
    if (renamed) result += "<br/><b>Renamed</b><br/>${renamed.join('<br/>')}<br/>"
    if (skipped) result += "<br/><b>Skipped</b><br/>${skipped.join('<br/>')}<br/>"
    if (failed) result += "<br/><b>Failed</b><br/>${failed.join('<br/>')}<br/>"
    atomicState.childRenameResult = (renamed || skipped || failed) ? result : "No rename fields were completed."
}

String childLabelForRow(Map row) {
    String custom = (row?.customLabel ?: row?.localLabel ?: "").toString().trim()
    if (custom) return custom
    String base = (row?.label ?: row?.id ?: row?.uid ?: "LIFX Device").toString().trim()
    String prefix = (childNamePrefix ?: "").toString().trim()
    return prefix ? "${prefix} ${base}".toString() : base
}

// row.childLabel is only a cached snapshot written when child devices are created/updated - it
// goes stale (blank) after Clear all Data rebuilds the row fresh, even though the actual
// installed child device is untouched and still has its real name. Read the live device's
// current label when one is installed, rather than trusting that cached field.
String localNameForRow(Map row) {
    def child = getChildDevice(childDniForRow(row))
    if (child) {
        try { return (child.getLabel() ?: child.displayName ?: "").toString() } catch (Throwable ignored) { return "" }
    }
    return (row?.customLabel ?: row?.localLabel ?: "").toString().trim()
}

String cloudUidForRow(Map row) {
    return normalisedUid(row?.id ?: row?.uid ?: row?.cloudUid)
}

String lanUidForRow(Map row) {
    // LAN/source UID from the Hubitat response. This is used for child DNI and matching.
    // Some LIFX devices expose this as Cloud UID + 1.
    return normalisedUid(row?.lanUid ?: row?.lanMac ?: row?.mac ?: row?.sourceMac ?: row?.id ?: row?.uid)
}

String controlUidForRow(Map row) {
    List<String> candidates = controlUidCandidatesForRow(row)
    return candidates ? candidates[0] : ""
}

List<String> controlUidCandidatesForRow(Map row) {
    // LIFX devices in this environment expose two related identifiers:
    // - Cloud UID / serial, usually the protocol target that non-exact devices respond to
    // - LAN/source UID, sometimes Cloud UID + 1, and sometimes the correct target for exact devices
    // To avoid breaking either class, send control packets to a bounded, de-duplicated candidate list.
    List<String> out = []
    Closure add = { val ->
        String uid = normalisedUid(val)
        if (uid && uid != "000000000000" && !out.contains(uid)) out << uid
    }

    // Preferred learned target first.
    add(row?.controlUid)
    add(row?.protocolTargetUid)
    add(row?.target)

    // Then explicit Cloud serial and LAN/source UID fallbacks.
    add(row?.cloudUid)
    add(row?.id)
    add(row?.uid)
    add(row?.lanUid)
    add(row?.lanMac)
    add(row?.mac)
    add(row?.sourceMac)

    return out.take(4)
}

String childDniForRow(Map row) {
    String uid = lanUidForRow(row)
    return uid ? "lifxdev-curated-${uid}".toString() : ""
}

String removeRowButtonName(String uidValue) {
    String uid = normalisedUid(uidValue)
    return uid ? "removeRow_${uid}".toString() : "removeRow_invalid"
}

// Rows with no installed child device are safe to drop from the saved table without
// affecting anything already created in Hubitat (e.g. a device deleted from LIFX Cloud).
List<Map> removableSavedRows() {
    Map curated = atomicState.curatedRows ?: [:]
    if (!curated) return []
    Map recentRemovals = recentRowRemovals()
    return curated.values()
        .collect { it as Map }
        .findAll { row ->
            String uid = normalisedUid(row.id ?: row.uid)
            !recentRemovals.containsKey(uid) && !getChildDevice(childDniForRow(row))
        }
        .sort { a, b -> compareUid(a.id ?: a.uid, b.id ?: b.uid) }
}

// Rows removed within this window are hidden from removableSavedRows() even if something
// causes a stale re-read of curatedRows right after the click that processed the removal
// (observed in testing: the confirmation message showed correctly, but the button list still
// showed the just-removed row on that same render). This closes the gap regardless of the
// exact platform-level cause. Removals older than the window naturally age out, so a device
// that's legitimately rediscovered later isn't permanently hidden.
Map recentRowRemovals() {
    Map recent = (atomicState.recentRowRemovals ?: [:]) as Map
    Long cutoff = now() - RECENT_REMOVAL_WINDOW_MS
    return recent.findAll { k, ts -> ((ts ?: 0L) as Long) >= cutoff }
}

void removeSavedRowIfNoInstalledChild(String uidValue) {
    String uid = normalisedUid(uidValue)
    if (!uid) return
    Map curated = atomicState.curatedRows ?: [:]
    Map row = curated[uid] as Map
    if (!row) return

    if (getChildDevice(childDniForRow(row))) {
        atomicState.rowRemovalResult = "Cannot remove ${html(childLabelForRow(row))} - an installed child device exists for this row. Remove the device from Hubitat's Devices page first."
        return
    }

    curated.remove(uid)
    atomicState.curatedRows = curated
    try { app.removeSetting(childSelectSettingName(uid)) } catch (Throwable t) { log.debug "app.removeSetting(...) failed: ${t.message}" }
    try { app.removeSetting(discoveredLightRenameSettingName(uid)) } catch (Throwable t) { log.debug "app.removeSetting(...) failed: ${t.message}" }
    try { app.removeSetting(removeRowButtonName(uid)) } catch (Throwable t) { log.debug "app.removeSetting(...) failed: ${t.message}" }

    Map recentRemovals = recentRowRemovals()
    recentRemovals[uid] = now()
    atomicState.recentRowRemovals = recentRemovals

    atomicState.rowRemovalResult = "Removed ${html(childLabelForRow(row))} (${uid}) from the saved device table."
}

String driverTypeForRow(Map row) {
    if (!row) return "LIFX Local Unknown"

    String cap = (row.capability ?: "").toString().toLowerCase()
    String mode = (row.driverMode ?: "").toString().toLowerCase()
    String product = (row.productName ?: row.productIdentifier ?: "").toString().toLowerCase()
    Integer lanProduct = safeInt(row.lanProduct ?: row.productId ?: row.product)

    // v4.7.7: IR capability is authoritative. Any IR-capable light must use the
    // Plus driver so the infrared management command/attributes are available.
    Boolean ir = truthy(row.hasIr) || cap.contains("infrared") || cap.contains(" ir") || cap.endsWith("ir") || mode.endsWith("ir") || mode.contains("+ ir") || mode.contains("infrared")
    if (ir) return "LIFX Local Plus Colour (Dev)"

    // Product ID is the strongest local signal after explicit IR capability.
    if (lanProduct in [10,11,18,19,51,61,66,82,85,87,88,100,101]) return "LIFX Local White Mono (Dev)"
    if (lanProduct in [39,50,60,81,96]) return "LIFX Local Tunable White (Dev)"
    if (lanProduct in [29,30,45,46,64,65,109,110,111]) return "LIFX Local Plus Colour (Dev)"
    if (lanProduct in [1,3,20,22,27,28,36,37,40,43,44,49,52,57,59,62,63,68,91,92,93,94,97,98,99,112,130,182]) return "LIFX Local Colour (Dev)"

    // Product family is the next strongest signal. This prevents CT-only wording being
    // mistaken for RGB colour support.
    Boolean productTunableWhite = product.contains("day and dusk") || product.contains("white to warm") || product.contains("warm to white")
    Boolean productWhiteMono = product.contains("mini white") || product.contains("filament") || product.contains("white mono") || product.contains("white 800") || product.contains("white 900")
    if (productWhiteMono) return "LIFX Local White Mono (Dev)"
    if (productTunableWhite) return "LIFX Local Tunable White (Dev)"

    Boolean hasRealColour = truthy(row.hasColor) ||
        cap.contains("colour light") || cap.contains("color light") ||
        cap.contains("multizone colour") || cap.contains("multizone color") ||
        cap.contains("matrix/tile colour") || cap.contains("matrix/tile color") ||
        mode.contains("colour + ct") || mode.contains("color + ct") ||
        mode.contains("colour + colour temperature") || mode.contains("color + color temperature") ||
        mode.contains("+ colour +") || mode.contains("+ color +")

    Boolean ctOnly = truthy(row.hasVariableColorTemp) ||
        cap.contains("tunable white") || cap.contains("variable ct") ||
        cap.contains("colour temperature") || cap.contains("color temperature") ||
        mode.contains("colour temperature") || mode.contains("color temperature") ||
        mode.contains("+ ct")

    if (hasRealColour && ir) return "LIFX Local Plus Colour (Dev)"
    if (hasRealColour) return "LIFX Local Colour (Dev)"
    if (ctOnly) return "LIFX Local Tunable White (Dev)"
    return "LIFX Local White Mono (Dev)"
}


Boolean driverNamesEquivalent(String installedName, String expectedName) {
    String a = normaliseDriverDisplayName(installedName)
    String b = normaliseDriverDisplayName(expectedName)
    return a && b && a == b
}

String normaliseDriverDisplayName(String value) {
    return (value ?: "").toString().replace("LIFX Local", "LIFX Local").trim()
}

String installedDriverName(child) {
    if (!child) return ""
    try { return child.typeName?.toString() ?: "" } catch (Throwable t) { log.debug "child lookup failed: ${t.message}" }
    try { return child.getTypeName()?.toString() ?: "" } catch (Throwable t) { log.debug "child.getTypeName(...) failed: ${t.message}" }
    try { return child.getDataValue('driverType')?.toString() ?: "" } catch (Throwable t) { log.debug "child.getDataValue(...) failed: ${t.message}" }
    return ""
}

Boolean rowReadyForChild(Map row) {
    return row && (row.ip ?: '').toString().trim() && (row.label ?: '').toString().trim() && lanUidForRow(row) && controlUidForRow(row)
}

void createOrUpdateSelectedChildDevicesFromCurated() {
    List selected = selectedChildUidsFromCheckboxes()
    createOrUpdateChildDevicesForUids(selected, "selected child devices", isMasterSwitchSelected())
}

void createOrUpdateAllChildDevicesFromCurated() {
    List uids = childCreationOptions().keySet().collect { it.toString() }
    createOrUpdateChildDevicesForUids(uids, "all listed child devices", true)
}

void createOrUpdateChildDeviceForUid(String uidValue) {
    String uid = normalisedUid(uidValue)
    if (!uid) {
        atomicState.childCreateResult = "No child UID supplied."
        return
    }
    createOrUpdateChildDevicesForUids([uid], uid, true)
}

void createOrUpdateChildDevicesForUids(List selectedUids, String actionLabel = "selected child devices", Boolean ensureMasterSwitch = false) {
    Map curated = atomicState.curatedRows ?: [:]
    if (!curated) {
        atomicState.childCreateResult = "No curated rows available. Run Discovery first."
        return
    }

    List selected = (selectedUids ?: []).collect { normalisedUid(it) }.findAll { it }.unique()
    if (!selected && !ensureMasterSwitch) {
        atomicState.childCreateResult = "No child devices selected."
        return
    }

    List created = []
    List updated = []
    List skipped = []
    List failed = []

    curated.values()
        .collect { it as Map }
        .findAll { row -> selected.contains(normalisedUid((row as Map).id ?: (row as Map).uid ?: (row as Map).lanUid)) }
        .sort { a, b ->
            String al = ((a as Map).label ?: "").toString().toLowerCase()
            String bl = ((b as Map).label ?: "").toString().toLowerCase()
            Integer cmp = al <=> bl
            if (cmp != 0) return cmp
            return compareUid((a as Map).id ?: (a as Map).uid ?: (a as Map).lanUid, (b as Map).id ?: (b as Map).uid ?: (b as Map).lanUid)
        }
        .each { item ->
        Map row = item as Map
        String cloudUid = cloudUidForRow(row)
        String lanUid = lanUidForRow(row)
        String uid = lanUid ?: cloudUid
        String label = childLabelForRow(row)
        String dni = childDniForRow(row)
        String driverType = driverTypeForRow(row)

        if (!rowReadyForChild(row)) {
            skipped << "${html(row.label ?: uid ?: 'unknown')}: missing Label, UID or IP address"
            return
        }

        try {
            def existing = getChildDevice(dni)
            if (existing) {
                String currentDriver = installedDriverName(existing)
                if (currentDriver && !driverNamesEquivalent(currentDriver, driverType)) {
                    updateChildDataValues(existing, row, driverType)
                    row.childDni = dni
                    row.childDriver = driverType
                    row.childStatus = "Driver mismatch: installed ${currentDriver}; expected ${driverType}"
                    skipped << "${html(label)}: installed driver is '${html(currentDriver)}', expected '${html(driverType)}'. Hubitat cannot safely change this child driver from the parent app; delete/recreate the child or change its driver manually."
                    return
                }
                try { existing.setLabel(label) } catch (Throwable t) { log.debug "existing.setLabel(...) failed: ${t.message}" }
                updateChildDataValues(existing, row, driverType)
                row.childDni = dni
                row.childDriver = driverType
                row.childLabel = label
                row.childStatus = "Updated"
                updated << "${html(label)} (${html(driverType)})"
            } else {
                def child = addChildDevice(
                    "Hubitat Integrations",
                    driverType,
                    dni,
                    [
                        label: label,
                        name: driverType,
                        isComponent: false,
                        data: childDataMap(row, driverType)
                    ]
                )
                row.childDni = dni
                row.childDriver = driverType
                row.childLabel = label
                row.childStatus = "Created"
                created << "${html(label)} (${html(driverType)})"
                syncChildRuntimeAttributes(child, row, driverType)
                try { child.refresh() } catch (Throwable t) { log.debug "child.refresh(...) failed: ${t.message}" }
            }
            curated[row.id ?: row.uid ?: uid] = row
        } catch (com.hubitat.app.exception.UnknownDeviceTypeException e) {
            row.childStatus = "Driver missing: ${driverType}"
            failed << "${html(label)}: driver '${html(driverType)}' is not installed"
        } catch (Throwable t) {
            row.childStatus = "Failed: ${safeMessage(t.message)}"
            failed << "${html(label)}: ${html(safeMessage(t.message))}"
        }
        // Blocks the button-handler request that the user's click is waiting on; total blocking
        // time scales linearly with the number of devices selected for create/update at once.
        pauseExecution(100)
    }

    atomicState.curatedRows = curated

    List<String> sections = []
    if (created) sections << "<b>Created (${created.size()})</b><br/>${created.join('<br/>')}"
    if (updated) sections << "<b>Updated (${updated.size()})</b><br/>${updated.join('<br/>')}"
    if (skipped) sections << "<b>Skipped (${skipped.size()})</b><br/>${skipped.join('<br/>')}"
    if (failed) sections << "<b>Failed (${failed.size()})</b><br/>${failed.join('<br/>')}"
    String childResult = sections ? sections.join('<br/><br/>') : "No changes were required."
    if (ensureMasterSwitch) {
        createOrUpdateFastGroupChildDevice()
        String masterResult = (atomicState.childCreateResult ?: "").toString()
        refreshMasterSwitchMembership()
        if (masterResult?.toLowerCase()?.contains("failed") || masterResult?.toLowerCase()?.contains("not installed")) {
            childResult = childResult + "<br/><br/><b>LIFX MASTER SWITCH</b><br/>" + masterResult
        }
    } else {
        refreshMasterSwitchMembership()
    }
    if (created) {
        // Only a genuinely new child gets the default-polling treatment - an ordinary update
        // (e.g. IP resync) must not reset an already-configured polling preference.
        enableDefaultStatusPollingAfterChildCreation()
    } else if (updated) {
        try { configureStatusPolling(false) } catch (Throwable t) { log.debug "configureStatusPolling(...) failed: ${t.message}" }
    }

    atomicState.childCreateResult = childResult
}

void enableDefaultStatusPollingAfterChildCreation() {
    // Safe default after child creation: LAN-only LIGHT.GET_POWER polling every 2 minutes.
    // This does not call the LIFX Cloud API, firmware discovery, product/version lookup,
    // brightness polling, colour polling or full child refresh. Only defaults settings that are
    // still unset, so an existing installation's polling choice survives adding one more child.
    try { if (settings["lifxStatusPollingEnabled"] == null) app.updateSetting("lifxStatusPollingEnabled", [type: "bool", value: true]) } catch (Throwable t) { log.debug "app.updateSetting(...) failed: ${t.message}" }
    try { if (settings["lifxStatusPollIntervalMinutes"] == null) app.updateSetting("lifxStatusPollIntervalMinutes", [type: "enum", value: "2"]) } catch (Throwable t) { log.debug "app.updateSetting(...) failed: ${t.message}" }
    try {
        configureStatusPolling(false)
        atomicState.statusPollingResult = "Timed child status polling enabled by child-device creation: on/off status only, every 2 minutes."
    } catch (Throwable t) {
        atomicState.statusPollingResult = "Timed child status polling could not be enabled after child-device creation: ${html(safeMessage(t.message))}"
        log.warn atomicState.statusPollingResult
    }
}

Map childDataMap(Map row, String driverType) {
    String cloudUid = cloudUidForRow(row)
    String lanUid = lanUidForRow(row)
    String controlUid = controlUidForRow(row)
    [
        // uid/lanUid are the LAN source UID used for stable Hubitat child identity.
        // controlUid is the LIFX protocol target used inside command packets.
        uid: "${lanUid ?: ''}",
        lanUid: "${lanUid ?: ''}",
        controlUid: "${controlUid ?: ''}",
        protocolTargetUid: "${controlUid ?: ''}",
        controlUidCandidates: "${controlUidCandidatesForRow(row).join(',')}",
        cloudUid: "${cloudUid ?: ''}",
        ip: "${row.ip ?: ''}",
        port: "${row.port ?: LIFX_PORT}",
        label: "${row.label ?: ''}",
        displayLabel: "${childLabelForRow(row)}",
        localLabel: "${row.customLabel ?: row.localLabel ?: ''}",
        group: "${row.groupName ?: ''}",
        productName: "${row.productName ?: ''}",
        productIdentifier: "${row.productIdentifier ?: ''}",
        capability: "${row.capability ?: cloudCapability(row)}",
        driverMode: "${row.driverMode ?: cloudDriverMode(row)}",
        driverType: "${driverType}",
        hasColor: "${truthy(row.hasColor)}",
        hasVariableColorTemp: "${truthy(row.hasVariableColorTemp)}",
        hasIr: "${truthy(row.hasIr)}",
        minKelvin: "${row.minKelvin ?: ''}",
        maxKelvin: "${row.maxKelvin ?: ''}"
    ]
}

void updateChildDataValues(child, Map row, String driverType) {
    childDataMap(row, driverType).each { k, v ->
        try { child.updateDataValue(k.toString(), v == null ? "" : v.toString()) } catch (Throwable t) { log.debug "child.updateDataValue(...) failed: ${t.message}" }
    }
    syncChildRuntimeAttributes(child, row, driverType)
}

void syncChildRuntimeAttributes(child, Map row, String driverType = "") {
    if (!child || !row) return

    // Data values drive command routing, but Hubitat's Commands page shows current-state
    // attributes. When an IP address changes, update both immediately so users do not
    // need to manually Initialize the child device to clear the old Lan Ip display.
    try { child.sendEvent(name: "uid", value: "${lanUidForRow(row) ?: cloudUidForRow(row) ?: ''}", displayed: false) } catch (Throwable t) { log.debug "sendEvent(uid) failed: ${t.message}" }
    try { child.sendEvent(name: "lanIp", value: "${row.ip ?: ''}", displayed: false) } catch (Throwable t) { log.debug "sendEvent(lanIp) failed: ${t.message}" }
    try { child.sendEvent(name: "label", value: "${childLabelForRow(row)}", displayed: false) } catch (Throwable t) { log.debug "sendEvent(label) failed: ${t.message}" }

    if (truthy(row.hasVariableColorTemp)) {
        Integer kelvin = safeInt(row.kelvin) ?: safeInt(row.colorTemperature) ?: null
        if (kelvin) {
            try { child.sendEvent(name: "colorTemperature", value: kelvin, displayed: false) } catch (Throwable t) { log.debug "sendEvent(colorTemperature) failed: ${t.message}" }
        }
    }
    if (driverType) {
        try { child.updateDataValue("driverType", driverType.toString()) } catch (Throwable t) { log.debug "child.updateDataValue(...) failed: ${t.message}" }
    }
}


// ---------------- Fast aggregate / bulk control ----------------

String fastGroupDni() {
    return "lifxdev-master-switch"
}

String fastGroupLabel() {
    String prefix = (childNamePrefix ?: "").toString().trim()
    return prefix ? "${prefix} LIFX MASTER SWITCH".toString() : "LIFX MASTER SWITCH"
}

void createOrUpdateFastGroupChildDevice() {
    String dni = fastGroupDni()
    String label = fastGroupLabel()
    String driverType = "LIFX Master Switch (Dev)"
    try {
        def existing = getChildDevice(dni)
        if (existing) {
            try { existing.setLabel(label) } catch (Throwable t) { log.debug "existing.setLabel(...) failed: ${t.message}" }
            try { existing.updateDataValue("driverType", driverType) } catch (Throwable t) { log.debug "existing.updateDataValue(...) failed: ${t.message}" }
            try { existing.updateDataValue("groupMode", "fast-bulk") } catch (Throwable t) { log.debug "existing.updateDataValue(...) failed: ${t.message}" }
            try { existing.updateDataValue("managedBy", "LIFX Light Manager (Dev)") } catch (Throwable t) { log.debug "existing.updateDataValue(...) failed: ${t.message}" }
            refreshMasterSwitchMembership(existing)
            atomicState.childCreateResult = "Updated LIFX MASTER SWITCH device: ${html(label)}"
            return
        }
        addChildDevice(
            "Hubitat Integrations",
            driverType,
            dni,
            [
                label: label,
                name: driverType,
                isComponent: false,
                data: [driverType: driverType, groupMode: "fast-bulk", managedBy: "LIFX Light Manager (Dev)"]
            ]
        )
        refreshMasterSwitchMembership()
        atomicState.childCreateResult = "Created LIFX MASTER SWITCH device: ${html(label)}"
    } catch (com.hubitat.app.exception.UnknownDeviceTypeException e) {
        atomicState.childCreateResult = "LIFX Master Switch (Dev) driver is not installed: ${html(driverType)}"
    } catch (Throwable t) {
        atomicState.childCreateResult = "Failed to create LIFX MASTER SWITCH: ${html(safeMessage(t.message))}"
    }
}

List<String> managedLifxDriverNames() {
    return [
        "LIFX Local White Mono (Dev)",
        "LIFX Local Tunable White (Dev)",
        "LIFX Local Colour (Dev)",
        "LIFX Local Plus Colour (Dev)"
    ]
}

String childDataValue(child, String key) {
    if (!child || !key) return ""
    try { return child.getDataValue(key)?.toString() ?: "" } catch (Throwable t) { log.debug "child.getDataValue(...) failed: ${t.message}" }
    return ""
}

Boolean isManagedLifxLightChild(child) {
    if (!child) return false
    String dni = ""
    String type = ""
    try { dni = child.deviceNetworkId?.toString() ?: "" } catch (Throwable t) { log.debug "dni assignment failed: ${t.message}" }
    if (dni == fastGroupDni()) return false
    try { type = child.typeName?.toString() ?: "" } catch (Throwable t) { log.debug "type assignment failed: ${t.message}" }
    if (!type) type = childDataValue(child, "driverType")
    return managedLifxDriverNames().contains(type)
}

List managedLifxLightChildren() {
    return getChildDevices()
        .findAll { child -> isManagedLifxLightChild(child) }
        .sort { a, b ->
            String al = ""
            String bl = ""
            try { al = a.displayName?.toString()?.toLowerCase() ?: "" } catch (Throwable t) { log.debug "al assignment failed: ${t.message}" }
            try { bl = b.displayName?.toString()?.toLowerCase() ?: "" } catch (Throwable t) { log.debug "bl assignment failed: ${t.message}" }
            return al <=> bl
        }
}

Map rowForManagedChild(child) {
    if (!child) return [:]

    Map row = rowForChild(child) ?: [:]

    // Rebuild from the child device itself so the Master Switch never depends on a stale
    // creation-time cache. This is important when lights are added after the Master Switch
    // was first created, or if the Master Switch is deleted and recreated later.
    String dni = ""
    String type = ""
    String label = ""
    try { dni = child.deviceNetworkId?.toString() ?: "" } catch (Throwable t) { log.debug "dni assignment failed: ${t.message}" }
    try { type = child.typeName?.toString() ?: "" } catch (Throwable t) { log.debug "type assignment failed: ${t.message}" }
    try { label = child.displayName?.toString() ?: "" } catch (Throwable t) { log.debug "label assignment failed: ${t.message}" }

    String uidFromDni = dni.startsWith("lifx-curated-") ? dni.replaceFirst("lifx-curated-", "") : dni
    String lanUid = normalisedUid(childDataValue(child, "lanUid") ?: childDataValue(child, "uid") ?: uidFromDni)
    String controlUid = normalisedUid(childDataValue(child, "controlUid") ?: childDataValue(child, "protocolTargetUid") ?: childDataValue(child, "cloudUid") ?: lanUid)

    row.label = row.label ?: childDataValue(child, "label") ?: label
    row.ip = childDataValue(child, "ip") ?: row.ip
    row.port = childDataValue(child, "port") ?: row.port ?: LIFX_PORT
    row.lanUid = row.lanUid ?: lanUid
    row.uid = row.uid ?: lanUid
    row.id = row.id ?: childDataValue(child, "cloudUid") ?: lanUid
    row.cloudUid = row.cloudUid ?: childDataValue(child, "cloudUid")
    row.controlUid = row.controlUid ?: controlUid
    row.protocolTargetUid = row.protocolTargetUid ?: controlUid
    row.productName = row.productName ?: childDataValue(child, "productName")
    row.productIdentifier = row.productIdentifier ?: childDataValue(child, "productIdentifier")
    row.capability = row.capability ?: childDataValue(child, "capability")
    row.driverMode = row.driverMode ?: childDataValue(child, "driverMode")
    row.hasColor = row.hasColor ?: childDataValue(child, "hasColor")
    row.hasVariableColorTemp = row.hasVariableColorTemp ?: childDataValue(child, "hasVariableColorTemp")
    row.hasIr = row.hasIr ?: childDataValue(child, "hasIr")
    row.minKelvin = row.minKelvin ?: childDataValue(child, "minKelvin")
    row.maxKelvin = row.maxKelvin ?: childDataValue(child, "maxKelvin")
    row.childDni = dni
    row.childDriver = type ?: childDataValue(child, "driverType")

    return row
}

String childDniForBulkRow(Map row) {
    String dni = row?.childDni?.toString() ?: ""
    return dni ?: childDniForRow(row)
}

List<Map> bulkControlRows() {
    // Authoritative source is the current installed child-device list, not atomicState.curatedRows.
    // This ensures LIFX MASTER SWITCH includes all installed lights, including devices added
    // after initial Master Switch creation and after Master Switch recreation.
    return managedLifxLightChildren()
        .collect { child -> rowForManagedChild(child) as Map }
        .findAll { row -> row && (row.ip ?: '').toString().trim() && lanUidForRow(row as Map) }
        .sort { a, b -> compareUid(lanUidForRow(a as Map), lanUidForRow(b as Map)) }
}


Integer statusPollIntervalMinutes() {
    Integer minutes = safeInt(lifxStatusPollIntervalMinutes) ?: 2
    return clampInt(minutes, 1, 30)
}

Boolean statusPollingEnabled() {
    try { return lifxStatusPollingEnabled == true || lifxStatusPollingEnabled?.toString() == "true" } catch (Throwable t) { log.debug "lifxStatusPollingEnabled lookup failed: ${t.message}" }
    return false
}

void configureStatusPolling(Boolean showResult = true) {
    try { unschedule("pollManagedChildSwitchStatus") } catch (Throwable t) { log.debug "unschedule(...) failed: ${t.message}" }

    if (!statusPollingEnabled()) {
        if (showResult) atomicState.statusPollingResult = "Timed child status polling is disabled."
        return
    }

    Integer minutes = statusPollIntervalMinutes()
    try {
        schedule("0 0/${minutes} * * * ?", "pollManagedChildSwitchStatus")
        atomicState.statusPollingResult = "Timed child status polling enabled: on/off status only, every ${minutes} minute${minutes == 1 ? '' : 's'}."
    } catch (Throwable t) {
        atomicState.statusPollingResult = "Timed child status polling could not be scheduled: ${html(safeMessage(t.message))}"
        log.warn atomicState.statusPollingResult
    }
}

@SuppressWarnings("unused")
def pollManagedChildSwitchStatus() {
    if (!statusPollingEnabled()) {
        try { unschedule("pollManagedChildSwitchStatus") } catch (Throwable t) { log.debug "unschedule(...) failed: ${t.message}" }
        return
    }

    List<Map> rows = bulkControlRows()
    if (!rows) {
        atomicState.statusPollingResult = "No installed LIFX child devices available to poll."
        return
    }

    Integer sent = 0
    Integer skipped = 0
    Long started = now()
    rows.each { item ->
        Map row = item as Map
        if ((row.ip ?: '').toString().trim()) {
            sendLifxToRow(row, LIFX_MSG.LIGHT_GET_POWER, [], false, true, "parseChildLifx", 1, 0) // on/off status only
            sent++
            // Blocks this scheduled cron job (schedule("0 0/N * * * ?")) once per managed light on
            // every poll tick; total blocking time scales with fleet size.
            pauseExecution(60)
        } else {
            skipped++
        }
    }

    atomicState.statusPollingResult = "Last on/off status poll sent to ${sent} child device${sent == 1 ? '' : 's'}${skipped ? "; skipped ${skipped} without IP" : ''}. Responses update child switch state as they arrive."
    refreshMasterSwitchMembership()
}

Boolean backgroundMaintenanceEnabled() {
    try { return backgroundMaintenanceOn == true || backgroundMaintenanceOn?.toString() == "true" } catch (Throwable t) { log.debug "backgroundMaintenanceOn lookup failed: ${t.message}" }
    return false
}

void configureBackgroundMaintenance(Boolean showResult = true) {
    BACKGROUND_MAINTENANCE_JOBS.each { jobName ->
        try { unschedule(jobName) } catch (Throwable t) { log.debug "unschedule(${jobName}) failed: ${t.message}" }
    }

    if (!backgroundMaintenanceEnabled()) {
        if (showResult) atomicState.backgroundMaintenanceResult = "Hourly background maintenance is disabled."
        return
    }

    try {
        // DEV BRANCH: offset +5 minutes from production's :00/:15/:30 schedule so this instance's
        // background maintenance never fires at the same moment as production's against the same
        // physical fleet - confirmed via live-hub testing that a simultaneous collision causes
        // Hubitat's own "excessive hub load" throttling, which can silently drop LAN responses
        // (including IP-change detection) mid-run.
        schedule("0 5 * * * ?", "scheduledBackgroundDiscovery")
        schedule("0 20 * * * ?", "scheduledBackgroundFirmwareCheck")
        schedule("0 35 * * * ?", "scheduledBackgroundWifiCheck")
        atomicState.backgroundMaintenanceResult = "Hourly background maintenance enabled: Discovery at :05, firmware check at :20, WiFi signal check at :35 past each hour."
    } catch (Throwable t) {
        atomicState.backgroundMaintenanceResult = "Hourly background maintenance could not be scheduled: ${html(safeMessage(t.message))}"
        log.warn atomicState.backgroundMaintenanceResult
    }
}

@SuppressWarnings("unused")
void scheduledBackgroundDiscovery() {
    if (!backgroundMaintenanceEnabled()) { try { unschedule("scheduledBackgroundDiscovery") } catch (Throwable t) { log.debug "unschedule(...) failed: ${t.message}" }; return }
    // startCombinedDiscovery() already guards against overlapping a still-in-progress run (manual
    // or otherwise), so this is safe to fire unconditionally on the schedule.
    startCombinedDiscovery()
}

@SuppressWarnings("unused")
void scheduledBackgroundFirmwareCheck() {
    if (!backgroundMaintenanceEnabled()) { try { unschedule("scheduledBackgroundFirmwareCheck") } catch (Throwable t) { log.debug "unschedule(...) failed: ${t.message}" }; return }
    checkDeviceFirmware()
}

@SuppressWarnings("unused")
void scheduledBackgroundWifiCheck() {
    if (!backgroundMaintenanceEnabled()) { try { unschedule("scheduledBackgroundWifiCheck") } catch (Throwable t) { log.debug "unschedule(...) failed: ${t.message}" }; return }
    checkWifiSignal()
}

@SuppressWarnings("unused")
void checkDeviceFirmware() {
    // Runs over every saved row with an IP (not just installed children) so not-yet-installed
    // devices get checked too. Manual/on-demand only - firmware isn't available from LIFX Cloud,
    // so this is a separate action from Discovery rather than an extra per-device LAN round-trip
    // added to the already carefully-paced discovery flow.
    atomicState.firmwareCheckStartedAt = now()
    List<Map> rows = (atomicState.curatedRows ?: [:]).values().collect { it as Map }
    Integer sent = 0
    Integer skipped = 0
    rows.each { row ->
        if ((row.ip ?: '').toString().trim()) {
            sendLifx(row.ip.toString(), LIFX_MSG.GET_HOST_FIRMWARE)
            sent++
            pauseExecution(60)
        } else {
            skipped++
        }
    }
    atomicState.firmwareCheckResult = "Firmware check sent to ${sent} device${sent == 1 ? '' : 's'}${skipped ? "; skipped ${skipped} without IP" : ''}. Responses update the Firmware column as they arrive."
    // A single fire-and-forget UDP query per device can drop occasionally, so any row that
    // hasn't responded by this point gets one automatic resend - not a full retry loop.
    if (sent > 0) runInMillis(FIRMWARE_RESEND_DELAY_MS, "resendFirmwareCheck")
}

@SuppressWarnings("unused")
void resendFirmwareCheck() {
    Long startedAt = (atomicState.firmwareCheckStartedAt ?: 0L) as Long
    List<Map> rows = (atomicState.curatedRows ?: [:]).values().collect { it as Map }
        .findAll { (it.ip ?: '').toString().trim() && ((it.firmwareCheckedAt ?: 0L) as Long) < startedAt }
    if (!rows) return
    rows.each { row ->
        sendLifx(row.ip.toString(), LIFX_MSG.GET_HOST_FIRMWARE)
        pauseExecution(60)
    }
    atomicState.firmwareCheckResult = "Firmware check resent to ${rows.size()} device${rows.size() == 1 ? '' : 's'} that hadn't responded yet."
}

@SuppressWarnings("unused")
void checkWifiSignal() {
    // Mirrors checkDeviceFirmware() - runs over every saved row with an IP, manual/on-demand
    // only, since WiFi signal isn't available from LIFX Cloud either.
    atomicState.wifiCheckStartedAt = now()
    List<Map> rows = (atomicState.curatedRows ?: [:]).values().collect { it as Map }
    Integer sent = 0
    Integer skipped = 0
    rows.each { row ->
        if ((row.ip ?: '').toString().trim()) {
            sendLifx(row.ip.toString(), LIFX_MSG.GET_WIFI_INFO)
            sent++
            pauseExecution(60)
        } else {
            skipped++
        }
    }
    atomicState.wifiCheckResult = "WiFi signal check sent to ${sent} device${sent == 1 ? '' : 's'}${skipped ? "; skipped ${skipped} without IP" : ''}. Responses update the WiFi Signal column as they arrive."
    if (sent > 0) runInMillis(WIFI_CHECK_RESEND_DELAY_MS, "resendWifiCheck")
}

@SuppressWarnings("unused")
void resendWifiCheck() {
    Long startedAt = (atomicState.wifiCheckStartedAt ?: 0L) as Long
    List<Map> rows = (atomicState.curatedRows ?: [:]).values().collect { it as Map }
        .findAll { (it.ip ?: '').toString().trim() && ((it.wifiCheckedAt ?: 0L) as Long) < startedAt }
    if (!rows) return
    rows.each { row ->
        sendLifx(row.ip.toString(), LIFX_MSG.GET_WIFI_INFO)
        pauseExecution(60)
    }
    atomicState.wifiCheckResult = "WiFi signal check resent to ${rows.size()} device${rows.size() == 1 ? '' : 's'} that hadn't responded yet."
}

void refreshMasterSwitchMembership(masterDevice = null) {
    def dev = masterDevice ?: getChildDevice(fastGroupDni())
    if (!dev) return
    List<Map> rows = bulkControlRows()
    Integer total = rows.size()
    Integer onCount = 0
    rows.each { row ->
        def child = getChildDevice(childDniForBulkRow(row as Map))
        try { if (child?.currentValue('switch') == 'on') onCount++ } catch (Throwable t) { log.debug "child.currentValue(switch) failed: ${t.message}" }
    }
    try { dev.updateDataValue("memberCount", total.toString()) } catch (Throwable t) { log.debug "dev.updateDataValue(...) failed: ${t.message}" }
    try { dev.sendEvent(name: "memberCount", value: total) } catch (Throwable t) { log.debug "sendEvent(memberCount) failed: ${t.message}" }
    try { dev.sendEvent(name: "onMemberCount", value: onCount) } catch (Throwable t) { log.debug "sendEvent(onMemberCount) failed: ${t.message}" }
}

// Individual child commands and parsed LAN responses update that one child's own switch state,
// but never previously touched the Master Switch's aggregate switch attribute - it only changed
// via direct Master Switch commands or a manual refresh. This debounces a real aggregate
// recomputation (groupChildRefresh(), which already implements any-member-on semantics) so a
// burst of individual updates - e.g. a status-poll response wave - triggers one recomputation
// rather than one per response.
//
// unschedule() + runInMillis() alone is not a reliable debounce under load: live-hub testing
// during a status-poll burst showed more executions of reconcileMasterSwitchState() than calls
// to requestMasterStateReconciliation() that should have produced them - unschedule() can lose
// the race against a runInMillis() that's still registering, letting more than one timer survive.
// A token guard makes that race harmless: every request stamps the latest token, and a stale
// execution (from a timer unschedule() failed to cancel) checks its own token against the
// current one and no-ops instead of doing real work, regardless of how many timers survive.
void requestMasterStateReconciliation() {
    Integer token = ((state.reconcileToken ?: 0) as Integer) + 1
    state.reconcileToken = token
    try { unschedule("reconcileMasterSwitchState") } catch (Throwable t) { log.debug "unschedule(...) failed: ${t.message}" }
    runInMillis(MASTER_RECONCILE_DEBOUNCE_MS, "reconcileMasterSwitchState", [data: [token: token]])
}

@SuppressWarnings("unused")
def reconcileMasterSwitchState(Map data = null) {
    Integer token = data?.token as Integer
    if (token != null && token != (state.reconcileToken as Integer)) return
    def master = getChildDevice(fastGroupDni())
    if (master) groupChildRefresh(master)
}

void groupChildOn(device) {
    sendBulkSetPower(LIFX_FULL_ON, 0)
    try { device.sendEvent(name: "switch", value: "on") } catch (Throwable t) { log.debug "sendEvent(switch) failed: ${t.message}" }
}

void groupChildOff(device) {
    sendBulkSetPower(LIFX_FULL_OFF, 0)
    try { device.sendEvent(name: "switch", value: "off") } catch (Throwable t) { log.debug "sendEvent(switch) failed: ${t.message}" }
}

void groupChildSetLevel(device, value, duration = 0) {
    Integer level = clampInt(safeInt(value) ?: 0, PERCENT_MIN, PERCENT_MAX)
    sendBulkSetLevel(level, durationMs(duration))
    try {
        device.sendEvent(name: "level", value: level)
        device.sendEvent(name: "switch", value: level > 0 ? "on" : "off")
    } catch (Throwable t) { log.debug "groupChildSetLevel() sendEvent failed: ${t.message}" }
}

void groupChildSetColor(device, Map colorMap, duration = 0) {
    Map cmap = colorMap ?: [:]
    Integer hue100 = clampInt(safeInt(cmap.hue) ?: 0, PERCENT_MIN, PERCENT_MAX)
    Integer sat100 = clampInt(intOrDefault(cmap.saturation, 100), PERCENT_MIN, PERCENT_MAX)
    // Level is an independent setting controlled via setLevel() - colour commands preserve the
    // Master Switch's current level rather than accepting their own, since a colour-map input's
    // level field is easy to submit stale/unedited and shouldn't be able to silently change
    // brightness as a side effect of a colour change.
    Integer lvl = clampInt(intOrDefault(device.currentValue('level'), 75), PERCENT_MIN, PERCENT_MAX)
    Integer requestedKelvin = safeInt(cmap.colorTemperature ?: cmap.kelvin) ?: 3500
    Integer dur = durationMs(cmap.duration ?: duration ?: 0)
    sendBulkSetColorOrLevel(hue100, sat100, lvl, requestedKelvin, dur)
    try {
        device.sendEvent(name: "hue", value: hue100)
        device.sendEvent(name: "saturation", value: sat100)
        device.sendEvent(name: "colorTemperature", value: requestedKelvin)
        device.sendEvent(name: "colorMode", value: "RGB")
        device.sendEvent(name: "switch", value: lvl > 0 ? "on" : "off")
    } catch (Throwable t) { log.debug "groupChildSetColor() sendEvent failed: ${t.message}" }
}

void groupChildSetHue(device, value) {
    groupChildSetColor(device, [hue: value, saturation: intOrDefault(device.currentValue('saturation'), 100)], 0)
}

void groupChildSetSaturation(device, value) {
    groupChildSetColor(device, [hue: device.currentValue('hue') ?: 0, saturation: value], 0)
}

void groupChildSetColorTemperature(device, temperature = 3000, duration = 0) {
    Integer kelvin = clampInt(safeInt(temperature) ?: 3000, 1500, 9000)
    // Same principle as groupChildSetColor(): level is independent, preserved from current state.
    Integer lvl = clampInt(intOrDefault(device.currentValue('level'), 75), PERCENT_MIN, PERCENT_MAX)
    sendBulkSetColorTemperature(kelvin, lvl, durationMs(duration))
    try {
        device.sendEvent(name: "colorTemperature", value: kelvin)
        device.sendEvent(name: "colorMode", value: "CT")
        device.sendEvent(name: "switch", value: lvl > 0 ? "on" : "off")
    } catch (Throwable t) { log.debug "groupChildSetColorTemperature() sendEvent failed: ${t.message}" }
}

void groupChildRefresh(device) {
    List<Map> rows = bulkControlRows()
    Integer total = rows.size()
    Integer onCount = 0
    rows.each { row ->
        def child = getChildDevice(childDniForBulkRow(row as Map))
        try { if (child?.currentValue('switch') == 'on') onCount++ } catch (Throwable t) { log.debug "child.currentValue(switch) failed: ${t.message}" }
    }
    // Level is not touched here - the Master Switch is a virtual aggregate with no physical
    // brightness of its own to refresh from the network, so a refresh should preserve whatever
    // level was last actually commanded rather than inventing a binary 100/0 from on/off count.
    try {
        device.sendEvent(name: "switch", value: onCount > 0 ? "on" : "off")
        device.sendEvent(name: "memberCount", value: total)
        device.sendEvent(name: "onMemberCount", value: onCount)
    } catch (Throwable t) { log.debug "groupChildRefresh() sendEvent failed: ${t.message}" }
}

private void runColorEffectGroup(String baseColorName, String targetColorName, Integer periodMs, Integer waveform, Integer baseBrightness, Integer targetBrightness) {
    // Breathe/Pulse are colour effects (hue/saturation, not just brightness) - Mono White and
    // Tunable White bulbs have no physical way to render a hue at all, so they're excluded here
    // the same way the Master Switch's other colour commands (setColor/setColorTemperature)
    // already only send hue/saturation data to bulbs that actually support it.
    List<Map> rows = bulkControlRows().findAll { rowIsColourCapable(it as Map) }
    if (!rows) return
    rows.eachWithIndex { item, idx ->
        Map row = item as Map
        def child = getChildDevice(childDniForBulkRow(row))
        runColorEffect(row, child, baseColorName, targetColorName, periodMs, waveform, baseBrightness, targetBrightness)
        // Two packets per bulb (colour + waveform) - paced the same as the other bulk-fleet
        // commands to stay within Hubitat's outbound command rate limit.
        if (idx < rows.size() - 1) pauseExecution(60)
    }
}

void groupChildBreathe(device, baseColorName, targetColorName, speedSeconds = null, baseBrightness = null, targetBrightness = null) {
    runColorEffectGroup(baseColorName as String, targetColorName as String, resolvePeriodMs(speedSeconds as String, BREATHE_PERIOD_MS), WAVEFORM_SINE,
        resolveBrightnessPercent(baseBrightness as String), resolveBrightnessPercent(targetBrightness as String))
}

void groupChildPulse(device, baseColorName, targetColorName, speedSeconds = null, baseBrightness = null, targetBrightness = null) {
    runColorEffectGroup(baseColorName as String, targetColorName as String, resolvePeriodMs(speedSeconds as String, PULSE_PERIOD_MS), WAVEFORM_PULSE,
        resolveBrightnessPercent(baseBrightness as String), resolveBrightnessPercent(targetBrightness as String))
}

// Shared HSBK/power payload builders - both the individual child-device and Master Switch bulk
// command paths build these same LIFX packet payload shapes, so they're factored out here rather
// than duplicated inline at every call site.
List<Integer> buildHsbkPayload(Integer hue, Integer saturation, Integer brightness, Integer kelvin, Integer durationMs = 0) {
    return [0] + u16le(hue ?: 0) + u16le(saturation ?: 0) + u16le(brightness ?: 0) + u16le(kelvin ?: 0) + u32le(durationMs ?: 0)
}

// SetWaveform payload: reserved(1) + transient(1) + HSBK(8) + period(u32) + cycles(f32) +
// skew_ratio(i16, 0=symmetric) + waveform(1). transient=1 means the light settles back to
// whatever it was showing immediately before this packet once the effect naturally finishes -
// combined with sendSetColor(baseColor) sent right before this, that "before" state is the
// chosen base colour, so a very large cycle count plus a later interrupting command is how these
// effects are meant to be stopped, not by exhausting the cycle count.
List<Integer> buildWaveformPayload(Integer hue, Integer saturation, Integer brightness, Integer kelvin, Integer periodMs, Integer waveform) {
    return [0, 1] + u16le(hue ?: 0) + u16le(saturation ?: 0) + u16le(brightness ?: 0) + u16le(kelvin ?: 0) +
        u32le((periodMs ?: 0) as Long) + f32le(WAVEFORM_INDEFINITE_CYCLES) + u16le(0) + [waveform ?: WAVEFORM_SINE]
}

List<Integer> buildSetPowerPayload(Integer power, Integer durationMs = 0) {
    return u16le(power ?: 0) + u32le(durationMs ?: 0)
}

String fastSetPowerPacketHex(Integer power, Integer durationMs = 0) {
    // Exposed for child drivers so individual child on/off can mirror the original Rob Heyes dispatch pattern:
    // driver calls parent to build the lightweight zero-target packet, then the driver sends one UDP packet
    // directly to its stored IP with ignoreResponse=true.
    return buildLifxPacketForTarget(LIFX_MSG.LIGHT_SET_POWER, buildSetPowerPayload(power, durationMs), "", true, false, false)
}

void sendBulkSetPower(Integer power, Integer durationMs = 0) {
    List<Map> rows = bulkControlRows()
    if (!rows) return
    List<Integer> payload = buildSetPowerPayload(power, durationMs)

    // v4.7.3: app/fast-group power switching uses the original Rob Heyes style fast path:
    // one IP-directed, zero-target/tagged SET_POWER packet per light, no ACK,
    // no response, no callback, no UID-candidate fan-out.
    rows.each { row -> sendFastSetToRow(row as Map, LIFX_MSG.LIGHT_SET_POWER, payload) }

    // A pure power command never invents level state - it only reflects the actual power sent,
    // matching childOff()'s existing behaviour. Overwriting level to 0 here (and not restoring it
    // on the next power-on) made a bulb's cached brightness diverge from reality after an off/on
    // cycle via the Master Switch.
    String sw = (power ?: 0) > 0 ? "on" : "off"
    rows.each { row ->
        def child = getChildDevice(childDniForBulkRow(row as Map))
        if (child) {
            try { child.sendEvent(name: "switch", value: sw) } catch (Throwable t) { log.debug "sendEvent(switch) failed: ${t.message}" }
        }
    }
}

void sendBulkSetLevel(Integer level, Integer durationMs = 0) {
    List<Map> rows = bulkControlRows()
    if (!rows) return
    Integer brightness = scalePercentToLifx(level)

    // Important for Google Home: a Master dimmer action must be brightness-only.
    // LIFX has no separate "set brightness only" packet, so SET_COLOR must be sent
    // with each bulb's existing H/S/K values preserved. Sending hue=0/sat=0 here
    // makes Google dimming look like a colour/hue change.
    rows.each { item ->
        Map row = item as Map
        def child = getChildDevice(childDniForBulkRow(row))
        if (level <= 0) {
            // LIFX tracks power separately from brightness, so dimming to 0 via SET_COLOR alone
            // leaves the bulb physically powered - a genuine level-0 request needs a real
            // SET_POWER off, matching what an explicit Off command sends.
            sendFastSetToRow(row, LIFX_MSG.LIGHT_SET_POWER, buildSetPowerPayload(LIFX_FULL_OFF, durationMs))
        } else {
            Integer kelvin = clampKelvin(row, safeInt(child?.currentValue('colorTemperature')) ?: safeInt(row.defaultKelvin) ?: safeInt(row.minKelvin) ?: 3500)
            Integer hue = 0
            Integer sat = 0
            // Only replay the cached colour if the light is actually showing it right now (colorMode
            // RGB). If it's in CT/white mode, sending its stale leftover hue/saturation here would drag
            // it back to that old colour as a side effect of a pure brightness change.
            if (rowIsColourCapable(row) && (child?.currentValue('colorMode') ?: '').toString() == 'RGB') {
                hue = scalePercentToLifx(safeInt(child?.currentValue('hue')) ?: 0)
                sat = scalePercentToLifx(safeInt(child?.currentValue('saturation')) ?: 0)
            }
            List<Integer> payload = buildHsbkPayload(hue, sat, brightness, kelvin, durationMs)
            sendFastSetToRow(row, LIFX_MSG.LIGHT_SET_COLOR, payload)
            sendPowerOnIfNeeded(row, child, durationMs)
        }
    }
    rows.each { row ->
        def child = getChildDevice(childDniForBulkRow(row as Map))
        if (child) {
            try {
                child.sendEvent(name: "level", value: level)
                child.sendEvent(name: "switch", value: level > 0 ? "on" : "off")
            } catch (Throwable t) { log.debug "sendBulkSetLevel() child sendEvent failed: ${t.message}" }
        }
    }
}

private void sendPowerOnIfNeeded(Map row, child, Integer durationMs = 0) {
    // LIFX tracks power state separately from colour/brightness, so a colour/CT/level command alone
    // won't wake a bulb that's physically off - matching Hue-style "set colour implies on" behaviour
    // requires an explicit power-on packet whenever the light isn't already known to be on.
    try {
        if ((child?.currentValue('switch') ?: 'off') != 'on') {
            sendFastSetToRow(row, LIFX_MSG.LIGHT_SET_POWER, buildSetPowerPayload(LIFX_FULL_ON, durationMs))
        }
    } catch (Throwable t) { log.debug "sendPowerOnIfNeeded() failed: ${t.message}" }
}

Boolean rowIsColourCapable(Map row) {
    if (!row) return false
    if (truthy(row.hasColor)) return true
    String driver = (row.childDriver ?: driverTypeForRow(row)).toString().toLowerCase()
    String cap = (row.capability ?: '').toString().toLowerCase()
    String mode = (row.driverMode ?: '').toString().toLowerCase()
    return driver.contains('colour') && !driver.contains('white') ||
        cap.contains('colour light') || cap.contains('color light') ||
        mode.contains('colour +') || mode.contains('color +')
}

// True for anything that can actually show a colour temperature (colour bulbs and Tunable White),
// false only for pure Mono White, which declares no ColorTemperature attribute/capability.
Boolean rowSupportsColorTemperature(Map row) {
    if (!row) return false
    if (rowIsColourCapable(row)) return true
    String driver = (row.childDriver ?: driverTypeForRow(row)).toString().toLowerCase()
    String cap = (row.capability ?: '').toString().toLowerCase()
    String mode = (row.driverMode ?: '').toString().toLowerCase()
    return driver.contains('tunable') || cap.contains('colour temperature') || cap.contains('color temperature') ||
        mode.contains('colour temperature') || mode.contains('color temperature')
}

void sendBulkSetColorOrLevel(Integer hue100, Integer sat100, Integer level, Integer kelvin, Integer durationMs = 0) {
    List<Map> rows = bulkControlRows()
    if (!rows) return
    Integer brightness = scalePercentToLifx(level)
    rows.each { item ->
        Map row = item as Map
        def child = getChildDevice(childDniForBulkRow(row))
        Integer safeKelvin = clampKelvin(row, kelvin ?: 3500)
        List<Integer> payload
        if (rowIsColourCapable(row)) {
            payload = buildHsbkPayload(scalePercentToLifx(hue100), scalePercentToLifx(sat100), brightness, safeKelvin, durationMs)
        } else {
            // Non-colour-capable lights receive only the requested brightness level.
            payload = buildHsbkPayload(0, 0, brightness, safeKelvin, durationMs)
        }
        sendFastSetToRow(row, LIFX_MSG.LIGHT_SET_COLOR, payload)
        if (level > 0) {
            sendPowerOnIfNeeded(row, child, durationMs)
        } else {
            // LIFX tracks power separately from brightness, so a level-0 colour packet alone
            // leaves the bulb physically powered - send the matching SET_POWER off so the
            // switch:off event published below reflects real bulb state.
            sendFastSetToRow(row, LIFX_MSG.LIGHT_SET_POWER, buildSetPowerPayload(LIFX_FULL_OFF, durationMs))
        }
    }
    rows.each { row ->
        def child = getChildDevice(childDniForBulkRow(row as Map))
        if (child) {
            try {
                if (rowIsColourCapable(row as Map)) {
                    child.sendEvent(name: "hue", value: hue100)
                    child.sendEvent(name: "saturation", value: sat100)
                    child.sendEvent(name: "colorMode", value: "RGB")
                }
                child.sendEvent(name: "level", value: level)
                child.sendEvent(name: "switch", value: level > 0 ? "on" : "off")
            } catch (Throwable t) { log.debug "sendBulkSetColorOrLevel() child sendEvent failed: ${t.message}" }
        }
    }
}

void sendBulkSetColorTemperature(Integer kelvin, Integer level = 75, Integer durationMs = 0) {
    List<Map> rows = bulkControlRows()
    if (!rows) return
    Integer lvl = clampInt(intOrDefault(level, 75), PERCENT_MIN, PERCENT_MAX)
    Integer brightness = scalePercentToLifx(lvl)
    rows.each { item ->
        Map row = item as Map
        def child = getChildDevice(childDniForBulkRow(row))
        Integer safeKelvin = clampKelvin(row, kelvin ?: 3000)
        List<Integer> payload = buildHsbkPayload(0, 0, brightness, safeKelvin, durationMs)
        sendFastSetToRow(row, LIFX_MSG.LIGHT_SET_COLOR, payload)
        if (lvl > 0) {
            sendPowerOnIfNeeded(row, child, durationMs)
        } else {
            // See sendBulkSetColorOrLevel(): a level-0 colour-temperature packet alone doesn't
            // power the bulb off, so send the matching SET_POWER off explicitly.
            sendFastSetToRow(row, LIFX_MSG.LIGHT_SET_POWER, buildSetPowerPayload(LIFX_FULL_OFF, durationMs))
        }
    }
    rows.each { row ->
        def child = getChildDevice(childDniForBulkRow(row as Map))
        if (child) {
            try {
                // Mono White devices declare no ColorTemperature attribute/capability, so only
                // emit CT-related events to rows that actually support it - level/switch apply to
                // every device type regardless.
                if (rowSupportsColorTemperature(row as Map)) {
                    child.sendEvent(name: "colorTemperature", value: clampKelvin(row as Map, kelvin ?: 3000))
                    child.sendEvent(name: "hue", value: 0)
                    child.sendEvent(name: "saturation", value: 0)
                    child.sendEvent(name: "colorMode", value: "CT")
                }
                child.sendEvent(name: "level", value: lvl)
                child.sendEvent(name: "switch", value: lvl > 0 ? "on" : "off")
            } catch (Throwable t) { log.debug "sendBulkSetColorTemperature() child sendEvent failed: ${t.message}" }
        }
    }
}

void sendFastSetToRow(Map row, Integer messageType, List<Integer> payload = []) {
    sendFastUdpToIp(row, messageType, payload ?: [])
}

void sendFastUdpToIp(Map row, Integer messageType, List<Integer> payload = []) {
    if (!row?.ip) return
    Integer port = safeInt(row.port) ?: LIFX_PORT
    String packet = buildLifxPacketForTarget(messageType, payload ?: [], "", true, false, false)
    sendHubCommand(new hubitat.device.HubAction(
        packet,
        hubitat.device.Protocol.LAN,
        [
            type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
            destinationAddress: "${row.ip}:${port}",
            encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
            ignoreWarning: true,
            parseWarning: false,
            ignoreResponse: true,
            timeout: 1
        ]
    ))
}

Map rowForChild(device) {
    if (!device) return [:]
    String dni = device.deviceNetworkId?.toString()
    Map curated = atomicState.curatedRows ?: [:]
    Map found = curated.values().find { row -> childDniForRow(row as Map) == dni } as Map
    if (found) return found

    String uid = normalisedUid(device.getDataValue('lanUid') ?: device.getDataValue('uid') ?: dni)
    if (uid && curated[uid]) return curated[uid] as Map

    // Curated rows are normally keyed by Cloud UID, while children are now keyed by LAN UID.
    Map byLan = curated.values().find { row -> lanUidForRow(row as Map) == uid } as Map
    if (byLan) return byLan

    String cloudUid = normalisedUid(device.getDataValue('cloudUid'))
    if (cloudUid && curated[cloudUid]) return curated[cloudUid] as Map
    return [:]
}

void childOnOffFallback(device, String value) {
    if ((value ?: "").toString() == "off") childOff(device) else childOn(device)
}

void childOn(device) {
    Map row = rowForManagedChild(device)
    sendSetPower(row, LIFX_FULL_ON, 0)
    try { device.sendEvent(name: "switch", value: "on") } catch (Throwable t) { log.debug "sendEvent(switch) failed: ${t.message}" }
    requestMasterStateReconciliation()
}

void childOff(device) {
    Map row = rowForManagedChild(device)
    sendSetPower(row, LIFX_FULL_OFF, 0)
    try { device.sendEvent(name: "switch", value: "off") } catch (Throwable t) { log.debug "sendEvent(switch) failed: ${t.message}" }
    requestMasterStateReconciliation()
}

void childSetLevel(device, value, duration = 0) {
    Integer level = clampInt(safeInt(value) ?: 0, PERCENT_MIN, PERCENT_MAX)
    Map row = rowForManagedChild(device)
    Integer durMs = durationMs(duration)
    if (level <= 0) {
        // LIFX tracks power separately from brightness, so dimming to 0 via SET_COLOR alone
        // leaves the bulb physically powered - a genuine level-0 request needs a real
        // SET_POWER off, matching what an explicit Off command sends.
        sendSetPower(row, LIFX_FULL_OFF, durMs)
        try {
            device.sendEvent(name: "level", value: 0)
            device.sendEvent(name: "switch", value: "off")
        } catch (Throwable t) { log.debug "childSetLevel() sendEvent failed: ${t.message}" }
        requestMasterStateReconciliation()
        return
    }
    Integer kelvin = clampKelvin(row, safeInt(device.currentValue('colorTemperature')) ?: safeInt(row.minKelvin) ?: 3500)
    // Only replay the cached colour if the light is actually showing it right now (colorMode RGB) -
    // otherwise a stale leftover hue/saturation would drag a CT/white light back to an old colour as
    // a side effect of a pure brightness change.
    Boolean isRgb = (device.currentValue('colorMode') ?: '').toString() == 'RGB'
    Integer hue = isRgb ? scalePercentToLifx(device.currentValue('hue') ?: 0) : 0
    Integer sat = isRgb ? scalePercentToLifx(device.currentValue('saturation') ?: 0) : 0
    Integer bri = scalePercentToLifx(level)
    sendSetColor(row, hue, sat, bri, kelvin, durMs)
    // LIFX tracks power separately from brightness, so setLevel() alone won't wake a bulb that's
    // physically off - send an explicit power-on to match standard SwitchLevel/Hue-style behaviour.
    sendPowerOnIfNeeded(row, device, durMs)
    try {
        device.sendEvent(name: "level", value: level)
        device.sendEvent(name: "switch", value: "on")
    } catch (Throwable t) { log.debug "childSetLevel() sendEvent failed: ${t.message}" }
    requestMasterStateReconciliation()
    // v4.7.3: no blocking inline refresh after SET commands; original app updates events optimistically.
}

void childSetColorTemperature(device, temp, level = null, duration = 0) {
    Map row = rowForManagedChild(device)
    Integer kelvin = clampKelvin(row, safeInt(temp) ?: 3500)
    Integer lvl = clampInt(firstNonNullInt([level, device.currentValue('level')], 100), PERCENT_MIN, PERCENT_MAX)
    Integer durMs = durationMs(duration)
    sendSetColor(row, 0, 0, scalePercentToLifx(lvl), kelvin, durMs)
    // Colour-temperature commands should wake the bulb, matching Hue-style rule behaviour. A
    // level-0 request is the flip side of that contract - LIFX tracks power separately from
    // brightness, so the colour packet alone won't turn the bulb off.
    if (lvl > 0) {
        sendPowerOnIfNeeded(row, device, durMs)
    } else {
        sendSetPower(row, LIFX_FULL_OFF, durMs)
    }
    try {
        device.sendEvent(name: "colorTemperature", value: kelvin)
        device.sendEvent(name: "level", value: lvl)
        device.sendEvent(name: "hue", value: 0)
        device.sendEvent(name: "saturation", value: 0)
        device.sendEvent(name: "colorMode", value: "CT")
        device.sendEvent(name: "switch", value: lvl > 0 ? "on" : "off")
    } catch (Throwable t) { log.debug "childSetColorTemperature() sendEvent failed: ${t.message}" }
    requestMasterStateReconciliation()
    // v4.7.3: no blocking inline refresh after SET commands; original app updates events optimistically.
}

void childSetColor(device, Map colorMap, duration = 0) {
    Map row = rowForManagedChild(device)
    Integer hue100 = clampInt(safeInt(colorMap?.hue) ?: 0, PERCENT_MIN, PERCENT_MAX)
    Integer sat100 = clampInt(intOrDefault(colorMap?.saturation, 100), PERCENT_MIN, PERCENT_MAX)
    Integer lvl = clampInt(firstNonNullInt([colorMap?.level, colorMap?.brightness], 100), PERCENT_MIN, PERCENT_MAX)
    Integer kelvin = clampKelvin(row, safeInt(colorMap?.colorTemperature ?: colorMap?.kelvin) ?: safeInt(device.currentValue('colorTemperature')) ?: 3500)
    Integer durMs = durationMs(duration)
    sendSetColor(row, scalePercentToLifx(hue100), scalePercentToLifx(sat100), scalePercentToLifx(lvl), kelvin, durMs)
    // Colour commands should wake the bulb, matching Hue-style rule behaviour - LIFX tracks power
    // separately from colour, so a colour-only packet won't turn on a bulb that's physically off.
    // The flip side of that contract: a level-0 request must send a real SET_POWER off, since the
    // switch:off event published below would otherwise misrepresent a bulb still powered at 0%.
    if (lvl > 0) {
        sendPowerOnIfNeeded(row, device, durMs)
    } else {
        sendSetPower(row, LIFX_FULL_OFF, durMs)
    }
    try {
        device.sendEvent(name: "hue", value: hue100)
        device.sendEvent(name: "saturation", value: sat100)
        device.sendEvent(name: "level", value: lvl)
        device.sendEvent(name: "colorTemperature", value: kelvin)
        device.sendEvent(name: "colorMode", value: "RGB")
        device.sendEvent(name: "switch", value: lvl > 0 ? "on" : "off")
    } catch (Throwable t) { log.debug "childSetColor() sendEvent failed: ${t.message}" }
    requestMasterStateReconciliation()
    // v4.7.3: no blocking inline refresh after SET commands; original app updates events optimistically.
}

void childSetHue(device, value) {
    childSetColor(device, [hue: value, saturation: intOrDefault(device.currentValue('saturation'), 100), level: intOrDefault(device.currentValue('level'), 100)], 0)
}

void childSetSaturation(device, value) {
    childSetColor(device, [hue: device.currentValue('hue') ?: 0, saturation: value, level: intOrDefault(device.currentValue('level'), 100)], 0)
}

void childSetInfraredLevel(device, value) {
    Map row = rowForManagedChild(device)
    Integer level = clampInt(safeInt(value) ?: 0, PERCENT_MIN, PERCENT_MAX)
    sendLifxToRow(row, LIFX_MSG.LIGHT_SET_INFRARED, u16le(scalePercentToLifx(level)), true, false, "parseChildLifx", 1, 0) // ack requested
    try { device.sendEvent(name: "IRLevel", value: level) } catch (Throwable t) { log.debug "sendEvent(IRLevel) failed: ${t.message}" }
    try { device.sendEvent(name: "infraredLevel", value: level) } catch (Throwable t) { log.debug "sendEvent(infraredLevel) failed: ${t.message}" }
    // v4.7.3: no blocking inline refresh after SET commands; original app updates events optimistically.
}

// Resolves a named colour preset (see NAMED_COLORS) to [hue100, saturation100, kelvin] - falls
// back to White for an unrecognised or blank name rather than failing the whole command. Case-
// insensitive and trims whitespace, since Rule Machine's Custom Action has no ENUM/dropdown
// support for custom command parameters (confirmed live - only string/number/decimal are
// offered), so this value is always typed by hand rather than picked from a list.
List<Integer> resolveNamedColor(String name) {
    String key = (name ?: "").toString().trim()
    return NAMED_COLORS[key] ?: (NAMED_COLORS.find { k, v -> k.equalsIgnoreCase(key) }?.value as List<Integer>) ?: NAMED_COLORS["White"]
}

// Optional speed override, typed as a plain number of seconds in a STRING field (see the
// BREATHE_PERIOD_MS comment for why STRING rather than NUMBER). Blank or unparseable falls back
// to the given default; clamped to a sane range either way.
Integer resolvePeriodMs(String secondsText, Integer defaultMs) {
    String text = secondsText?.trim()
    if (!text) return defaultMs
    try {
        BigDecimal seconds = new BigDecimal(text)
        if (seconds <= 0G) return defaultMs
        return clampInt((seconds * 1000G).intValue(), 200, 60000)
    } catch (NumberFormatException ignored) {
        return defaultMs
    }
}

// Optional brightness override (0-100%), typed as a plain number in a STRING field for the same
// reason speed is (see resolvePeriodMs). Blank or unparseable falls back to full brightness.
Integer resolveBrightnessPercent(String percentText) {
    Integer pct = safeInt(percentText)
    if (pct == null) return 100
    return clampInt(pct, PERCENT_MIN, PERCENT_MAX)
}

private void runColorEffect(Map row, device, String baseColorName, String targetColorName, Integer periodMs, Integer waveform, Integer baseBrightness, Integer targetBrightness) {
    if (!row?.ip) {
        log.debug "runColorEffect: no row/IP for ${device?.displayName} - nothing sent."
        return
    }
    List<Integer> base = resolveNamedColor(baseColorName)
    List<Integer> target = resolveNamedColor(targetColorName)
    Integer baseKelvin = clampKelvin(row, base[2])
    Integer baseLevel = intOrDefault(baseBrightness, 100)
    Integer targetLevel = intOrDefault(targetBrightness, 100)
    sendSetColor(row, scalePercentToLifx(base[0]), scalePercentToLifx(base[1]), scalePercentToLifx(baseLevel), baseKelvin, 0)
    if (baseLevel > 0) sendPowerOnIfNeeded(row, device, 0)
    List<Integer> payload = buildWaveformPayload(
        scalePercentToLifx(target[0]), scalePercentToLifx(target[1]),
        scalePercentToLifx(targetLevel), clampKelvin(row, target[2]), periodMs, waveform)
    sendLifxToRow(row, LIFX_MSG.LIGHT_SET_WAVEFORM, payload, false, false, "parseChildLifx", 1, 0)
}

void childBreathe(device, baseColorName, targetColorName, speedSeconds = null, baseBrightness = null, targetBrightness = null) {
    Integer periodMs = resolvePeriodMs(speedSeconds as String, BREATHE_PERIOD_MS)
    runColorEffect(rowForManagedChild(device), device, baseColorName as String, targetColorName as String, periodMs, WAVEFORM_SINE,
        resolveBrightnessPercent(baseBrightness as String), resolveBrightnessPercent(targetBrightness as String))
}

void childPulse(device, baseColorName, targetColorName, speedSeconds = null, baseBrightness = null, targetBrightness = null) {
    Integer periodMs = resolvePeriodMs(speedSeconds as String, PULSE_PERIOD_MS)
    runColorEffect(rowForManagedChild(device), device, baseColorName as String, targetColorName as String, periodMs, WAVEFORM_PULSE,
        resolveBrightnessPercent(baseBrightness as String), resolveBrightnessPercent(targetBrightness as String))
}

void childRefresh(device) {
    // Both pauseExecution calls below block whatever calling context invoked childRefresh (Rule
    // Machine, dashboard, Google Home) - up to 160ms total for IR-capable devices.
    Map row = rowForManagedChild(device)
    sendLifxToRow(row, LIFX_MSG.LIGHT_GET, [], false, true, "parseChildLifx", 1, 0)         // response requested
    pauseExecution(80)
    sendLifxToRow(row, LIFX_MSG.LIGHT_GET_POWER, [], false, true, "parseChildLifx", 1, 0)   // response requested
    if (truthy(row.hasIr)) {
        pauseExecution(80)
        sendLifxToRow(row, LIFX_MSG.LIGHT_GET_INFRARED, [], false, true, "parseChildLifx", 1, 0) // response requested
    }
}

void refreshChildSoon(device, Integer delayMs = 350) {
    // Hubitat cannot pass a device wrapper reliably through runInMillis, so do a bounded inline refresh delay.
    // Blocks whatever called this (up to 1200ms) - not just a scheduled job internally.
    try {
        pauseExecution(clampInt(delayMs ?: 350, 100, 1200))
        childRefresh(device)
    } catch (Throwable t) { log.debug "refreshChildSoon() failed: ${t.message}" }
}

void sendSetPower(Map row, Integer power, Integer durationMs = 0) {
    // v4.7.1: same fast packet path as the aggregate group. This matters when a
    // Hubitat group calls every child on/off individually; each child now emits
    // one lightweight UDP packet instead of a multi-candidate ACK command.
    sendFastUdpToIp(row, LIFX_MSG.LIGHT_SET_POWER, buildSetPowerPayload(power, durationMs))
}

void sendSetColor(Map row, Integer hue, Integer saturation, Integer brightness, Integer kelvin, Integer durationMs = 0) {
    List<Integer> payload = buildHsbkPayload(hue, saturation, brightness, clampKelvin(row, kelvin ?: 3500), durationMs)
    sendLifxToRow(row, LIFX_MSG.LIGHT_SET_COLOR, payload, true, false, "parseChildLifx", 1, 0) // ack requested
}

void sendLifxToRow(Map row, Integer messageType, List<Integer> payload = [], Boolean ackRequested = false, Boolean responseRequired = false, String callback = "parseChildLifx", Integer repeats = 1, Integer repeatPauseMs = 0) {
    if (!row?.ip) return
    List<String> targets = controlUidCandidatesForRow(row)
    if (!targets) return
    Integer port = safeInt(row.port) ?: LIFX_PORT
    Integer count = clampInt(repeats ?: 1, 1, 3)

    targets.eachWithIndex { target, targetIndex ->
        String packet = buildLifxPacketForTarget(messageType, payload ?: [], target.toString(), false, ackRequested == true, responseRequired == true)
        for (int i = 0; i < count; i++) {
            sendHubCommand(new hubitat.device.HubAction(
                packet,
                hubitat.device.Protocol.LAN,
                [
                    type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
                    destinationAddress: "${row.ip}:${port}",
                    encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
                    ignoreWarning: true,
                    parseWarning: false,
                    timeout: 4,
                    callback: callback
                ]
            ))
            // Blocks whatever command handler called sendLifxToRow (button, Rule Machine, Google
            // Home); up to (targets x repeats) x repeatPauseMs ms total per invocation.
            if ((i < count - 1 || targetIndex < targets.size() - 1) && (repeatPauseMs ?: 0) > 0) pauseExecution(clampInt(repeatPauseMs ?: 50, 20, 200))
        }
    }
}

@SuppressWarnings("unused")
def parseChildLifx(response) {
    Map parsed = parseLifxResponse(response)
    if (!parsed || parsed.error) return

    String sourceMac = normaliseMac(parsed.sourceMac ?: parsed.mac ?: parsed.target)
    String protocolTargetUid = normaliseMac(parsed.target ?: parsed.protocolTarget ?: parsed.mac)
    Map match = findCuratedMatchForLanUid(sourceMac, atomicState.curatedRows ?: [:])
    if (!match && protocolTargetUid) match = findCuratedMatchForControlUid(protocolTargetUid, atomicState.curatedRows ?: [:])
    Map row = match?.curated as Map
    def child = row ? getChildDevice(childDniForRow(row)) : null
    if (!child) {
        // Curated row lookup failed (e.g. after Clear all Data) - fall back to matching directly
        // against installed child devices' own persisted identity, so refresh/state responses for
        // already-installed devices keep working even if the saved device table is gone.
        child = managedLifxLightChildren().find { c ->
            String lan = normalisedUid(childDataValue(c, "lanUid") ?: childDataValue(c, "uid"))
            String control = normalisedUid(childDataValue(c, "controlUid") ?: childDataValue(c, "protocolTargetUid"))
            (lan && lan == sourceMac) || (control && control == protocolTargetUid)
        }
        if (child) row = rowForManagedChild(child)
    }
    if (!child) return

    try {
        if ((parsed.type as Integer) == LIFX_MSG.ACK) {
            return // LIFX acknowledgement for SET workflows
        } else if ((parsed.type as Integer) == LIFX_MSG.LIGHT_STATE) {
            Map light = parseLightState(parsed.payloadHex)
            Integer saturation = scaleDown100(light.saturation ?: 0)
            // "label" reflects Hubitat's own local naming (see childLabelForRow()), set at child
            // creation/rename time - the physical bulb's raw label is intentionally not synced
            // back on refresh, since that would silently overwrite a user's local rename.
            child.sendEvent(name: "switch", value: ((light.power ?: 0) as Integer) > 0 ? "on" : "off")
            child.sendEvent(name: "level", value: scaleDown100(light.brightness ?: 0))
            // Mono White declares neither ColorControl nor ColorTemperature, and Tunable White
            // declares ColorTemperature but not ColorControl - only emit the events a device's
            // driver actually declares a capability for.
            if (rowIsColourCapable(row)) {
                child.sendEvent(name: "hue", value: scaleDown100(light.hue ?: 0))
                child.sendEvent(name: "saturation", value: saturation)
            }
            if (rowSupportsColorTemperature(row)) {
                child.sendEvent(name: "colorTemperature", value: light.kelvin ?: 3500)
            }
            // A LightState response is the only place colorMode can be reconciled with the bulb's
            // actual reported saturation - without this, a light recoloured outside Hubitat (e.g.
            // from the LIFX app) kept a stale colorMode, and the next brightness-only command would
            // then desaturate it back to white.
            if (rowIsColourCapable(row)) {
                child.sendEvent(name: "colorMode", value: saturation > 0 ? "RGB" : "CT")
            }
        } else if ((parsed.type as Integer) == LIFX_MSG.LIGHT_STATE_POWER) {
            Integer power = leU16(parsed.payloadHex, 0)
            child.sendEvent(name: "switch", value: power > 0 ? "on" : "off")
            // A power-state response can reflect a change made outside Hubitat (LIFX app,
            // physical switch, another controller) - reconcile the Master Switch aggregate too.
            requestMasterStateReconciliation()
        } else if ((parsed.type as Integer) == LIFX_MSG.LIGHT_STATE_INFRARED) {
            Integer ir = leU16(parsed.payloadHex, 0)
            Integer irLevel = scaleDown100(ir)
            child.sendEvent(name: "IRLevel", value: irLevel)
            child.sendEvent(name: "infraredLevel", value: irLevel)
        }
    } catch (Throwable t) {
        log.warn "LIFX child parse error: ${t.message}"
    }
}

Map parseLightState(String payloadHex) {
    if (!payloadHex || payloadHex.size() < 88) return [:]
    [
        hue: leU16(payloadHex, 0),
        saturation: leU16(payloadHex, 4),
        brightness: leU16(payloadHex, 8),
        kelvin: leU16(payloadHex, 12),
        power: leU16(payloadHex, 20),
        label: officialDeviceName(decodeLabel(payloadHex.substring(24)))
    ]
}

String buildLifxPacketForTarget(Integer messageType, List<Integer> payload, String targetHex, Boolean tagged, Boolean ackRequested, Boolean responseRequired) {
    Integer size = 36 + (payload?.size() ?: 0)
    List<Integer> bytes = []
    bytes += u16le(size)
    Integer flags = 0x0400 | 0x1000
    if (tagged) flags = flags | 0x2000
    bytes += u16le(flags)
    bytes += u32le(0x47544842)
    if (tagged) {
        bytes += [0,0,0,0,0,0,0,0]
    } else {
        List targetBytes = hexToBytes(targetHex ?: "").take(8)
        while (targetBytes.size() < 8) targetBytes << 0
        bytes += targetBytes
    }
    bytes += [0,0,0,0,0,0]
    Integer responseFlags = (ackRequested ? 0x02 : 0x00) | (responseRequired ? 0x01 : 0x00)
    bytes += [responseFlags]
    bytes += [nextSequence()]
    bytes += [0,0,0,0,0,0,0,0]
    bytes += u16le(messageType)
    bytes += [0,0]
    if (payload) bytes += payload
    return bytesToHex(bytes)
}

Integer scalePercentToLifx(value) {
    Integer v = clampInt(safeInt(value) ?: 0, PERCENT_MIN, PERCENT_MAX)
    return Math.round((v * 65535.0d) / 100.0d) as Integer
}

Integer scaleDown100(value) {
    try { return Math.round(((value as Integer) * 100.0d) / 65535.0d) as Integer } catch (Throwable t) { log.debug "scaleDown100() failed: ${t.message}"; return 0 }
}

Integer durationMs(value) {
    if (value == null) return 0
    try { return Math.max(0, Math.round((value as BigDecimal) * 1000.0d) as Integer) } catch (Throwable t) { log.debug "durationMs() failed: ${t.message}"; return 0 }
}

Integer clampInt(Integer v, Integer lo, Integer hi) {
    return Math.max(lo, Math.min(hi, v))
}

Integer clampKelvin(Map row, value) {
    Integer minK = safeInt(row?.minKelvin) ?: 1500
    Integer maxK = safeInt(row?.maxKelvin) ?: 9000
    Integer k = safeInt(value) ?: 3500
    if (maxK < minK) { Integer tmp = minK; minK = maxK; maxK = tmp }
    return clampInt(k, minK, maxK)
}

// ---------------- UI rendering ----------------

String statusHtml() {
    updateMatchStats()
    Map stats = atomicState.stats ?: emptyStats()
    Integer savedRows = curatedRowCount()
    Integer rowsWithIp = curatedWithIpCount()
    Integer rowsMissingIp = Math.max(0, savedRows - rowsWithIp)
    String colour = ((atomicState.status ?: "idle") == "complete") ? "#008000" : (((atomicState.status ?: "idle") in ["validating", "cloud", "broadcast", "sweep"]) ? "#cc0000" : "#777777")
    return "<div style='font-weight:bold;color:${colour}'>${html(atomicState.phase ?: 'Idle')}</div>" +
        "<b>Status:</b> ${html(atomicState.status ?: 'idle')}<br/>" +
        "<b>Cloud status:</b> ${html(atomicState.cloudStatus ?: 'not tested')}<br/>" +
        "<b>Saved curated rows:</b> ${savedRows}<br/>" +
        "<b>Device rows with IP:</b> ${rowsWithIp} / ${savedRows}<br/>" +
        "<b>Device rows missing IP:</b> ${rowsMissingIp}<br/>" +
        "<b>LAN-only devices:</b> ${stats.lanOnly ?: 0}<br/>" +
        "<b>First broadcast pulses:</b> ${stats.broadcastPulses ?: 0}<br/>" +
        "<b>Second broadcast pulses:</b> ${stats.secondBroadcastPulses ?: 0}<br/>" +
        "<b>Sweep phase:</b> ${html((atomicState.sweepKind ?: '').toString())} ${atomicState.sweepPass ?: ''} at ${atomicState.sweepPauseMs ?: ''} ms/host<br/>" +
        "<b>Sweep sent:</b> ${stats.sweepSent ?: 0} / ${stats.sweepTotal ?: 0}<br/>" +
        "<b>Sweep skipped:</b> ${stats.sweepSkipped ?: 0}<br/>" +
        "<b>LAN responses:</b> service ${stats.service ?: 0}, version ${stats.version ?: 0}, raw ${stats.rawResponses ?: 0}, errors ${stats.errors ?: 0}"
}

String curatedTableHtml() {
    Map curated = atomicState.curatedRows ?: [:]
    if (!curated) return "No saved curated rows yet. Run Cloud discovery, or run LAN discovery to build LAN-only rows."

    List rows = curated.values().collect { it as Map }.sort { a, b ->
        Integer cmp = compareByIp((a as Map).ip as String, (b as Map).ip as String)
        if (cmp != 0) return cmp
        return compareUid((a as Map).id ?: (a as Map).uid, (b as Map).id ?: (b as Map).uid)
    }

    StringBuilder b = new StringBuilder()
    b << tableOpenHtml()
    b << "<tr>"
    Map curatedHeaderKeys = ["UID":"uid", "Label":"label", "Local name":"localName", "IP address":"ip", "Last seen":"lastSeen", "Cloud connected":"connected"]
    ["UID", "Label", "Local name", "IP address", "Last seen", "Group", "Product", "Firmware", "WiFi Signal", "Capabilities", "Driver mode", "Cloud connected", "Status"].eachWithIndex { h, idx ->
        b << headerCell(h, idx, curatedHeaderKeys[h])
    }
    b << "</tr>"
    rows.each { r ->
        b << "<tr>"
        b << cell(r.id ?: r.uid, 0, "uid")
        b << cell(r.label, 1, "label")
        b << cell(localNameForRow(r), 1, "localName")
        b << cell(r.ip, 2, "ip")
        b << cell(curatedLastSeen(r), 3, "lastSeen")
        b << cell(r.groupName, 4)
        String productDisplay = r.productName ?: r.productIdentifier ?: (r.lanProduct ? "LAN product ${r.lanProduct}" : "")
        b << cell(productDisplay, 5)
        b << cell(r.firmwareVersion ?: "")
        b << cell(r.wifiRssi != null ? "${r.wifiRssi} dBm" : "")
        b << cell(r.capability ?: cloudCapability(r), 6)
        b << cell(r.driverMode ?: cloudDriverMode(r), 7)
        b << cell(r.connected == null ? "" : r.connected, null, "connected")
        b << cell(r.status ?: (r.ip ? "LAN IP saved" : "LAN IP missing"), 9)
        b << "</tr>"
    }
    b << "</table>"
    return b.toString()
}

String cloudTableHtml() {
    Map cloud = atomicState.cloudLights ?: [:]
    if (!cloud) return "No cloud lights loaded yet."
    StringBuilder b = new StringBuilder()
    b << tableOpenHtml()
    b << "<tr>"
    Map cloudHeaderKeys = ["UID":"uid", "Label":"label", "IP address":"ip", "Last seen":"lastSeen", "Connected":"connected"]
    ["UID", "Label", "IP address", "Last seen", "Group", "Product", "Capabilities", "Driver mode", "Connected", "Status"].eachWithIndex { h, idx ->
        b << headerCell(h, idx, cloudHeaderKeys[h])
    }
    b << "</tr>"
    Map curated = atomicState.curatedRows ?: [:]
    cloud.values().sort { a, c ->
        Map ca = curated[normaliseCloudId(a.id)] as Map
        Map cc = curated[normaliseCloudId(c.id)] as Map
        Integer cmp = compareByIp(ca?.ip as String, cc?.ip as String)
        if (cmp != 0) return cmp
        return compareUid(a.id, c.id)
    }.each { light ->
        Map d = curated[normaliseCloudId(light.id)] as Map ?: [:]
        Map l = light as Map
        b << "<tr>"
        b << cell(light.id, 0, "uid")
        b << cell(light.label, 1, "label")
        b << cell(d.ip ?: "", 2, "ip")
        b << cell(formatDateTime(light.lastSeenCloud), 3, "lastSeen")
        b << cell(light.groupName, 4)
        b << cell(light.productName ?: light.productIdentifier, 5)
        b << cell(capabilityFlags(l), 6)
        b << cell(cloudDriverMode(l), 7)
        b << cell(light.connected, null, "connected")
        b << cell(d.ip ? "LAN IP found" : "LAN IP missing", 9)
        b << "</tr>"
    }
    b << "</table>"
    return b.toString()
}

String lanTableHtml() {
    Map records = atomicState.records ?: [:]
    if (!records) return "No LAN responses yet."
    StringBuilder b = new StringBuilder()
    b << tableOpenHtml()
    b << "<tr>"
    Map lanHeaderKeys = ["UID":"uid", "Label":"label", "IP address":"ip", "Last seen":"lastSeen", "Expected from cloud":"status"]
    ["UID", "Label", "IP address", "Last seen", "Expected from cloud"].eachWithIndex { h, idx ->
        b << headerCell(h, idx, lanHeaderKeys[h])
    }
    b << "</tr>"
    records.values().sort { a, c ->
        Integer cmp = compareByIp((a as Map).ip as String, (c as Map).ip as String)
        if (cmp != 0) return cmp
        return compareUid((a as Map).mac, (c as Map).mac)
    }.each { dev ->
        b << "<tr>"
        b << cell(dev.mac, 0, "uid")
        b << cell(dev.cloudLabel ?: dev.label ?: "", 1, "label")
        b << cell(dev.ip, 2, "ip")
        b << cell(formatDateTime(dev.lastSeen), 3, "lastSeen")
        b << cell(dev.expectedFromCloud == true ? "yes - ${dev.cloudMatchType ?: 'matched'}" : "no", null, "status")
        b << "</tr>"
    }
    b << "</table>"
    return b.toString()
}

String normalisedUid(value) {
    if (value == null) return ""
    return value.toString().replaceAll("[^0-9A-Fa-f]", "").toLowerCase()
}

Integer compareUid(a, b) {
    String aa = normalisedUid(a)
    String bb = normalisedUid(b)
    if (!aa && !bb) return 0
    if (!aa) return 1
    if (!bb) return -1
    return aa <=> bb
}

// Numeric sort key for a dotted-quad IPv4 address so "10.0.0.9" sorts before "10.0.0.10".
// Returns null for blank/malformed values so callers can push those rows to the bottom.
Long ipSortKey(String ip) {
    if (!ip) return null
    List<String> parts = ip.tokenize(".")
    if (parts.size() != 4) return null
    try {
        Long key = 0L
        parts.each { p -> key = (key << 8) | (p.toInteger() & 0xff) }
        return key
    } catch (Throwable t) {
        log.debug "ipSortKey() failed: ${t.message}"
        return null
    }
}

// Ascending IP order with blank/unparsable IPs sorted after every real address.
Integer compareByIp(String ipA, String ipB) {
    Long ka = ipSortKey(ipA)
    Long kb = ipSortKey(ipB)
    if (ka == null && kb == null) return 0
    if (ka == null) return 1
    if (kb == null) return -1
    return ka <=> kb
}

String capabilityFlags(Map light) {
    List out = []
    if (truthy(light.hasColor)) out << "colour"
    if (truthy(light.hasVariableColorTemp)) out << "CT ${light.minKelvin ?: ''}-${light.maxKelvin ?: ''}K".trim()
    if (truthy(light.hasIr)) out << "IR"
    if (truthy(light.hasMultizone)) out << "multizone"
    if (truthy(light.hasMatrix)) out << "matrix"
    if (truthy(light.hasChain)) out << "chain"
    return out ? out.join(', ') : "basic"
}

// ---------------- UID matching helpers ----------------

Map findCuratedMatchForControlUid(String controlUid, Map cloud) {
    String ctrl = normalisedUid(controlUid)
    if (!ctrl || !cloud) return null

    Map exact = cloud[ctrl] as Map
    if (exact) return [cloudId: ctrl, curated: exact, matchType: "control-exact"]

    Map found = cloud.find { k, v -> normalisedUid((v as Map)?.controlUid ?: (v as Map)?.protocolTargetUid ?: (v as Map)?.target) == ctrl }?.value as Map
    if (found) return [cloudId: normalisedUid(found.id ?: found.uid ?: ctrl), curated: found, matchType: "control-target"]
    return null
}

Map findCuratedMatchForLanUid(String lanUid, Map cloud) {
    String lan = normalisedUid(lanUid)
    if (!lan || !cloud) return null

    // 0. A row already matched before has its confirmed lanUid persisted (see the parseLifx()
    // write-back) - prefer that over re-deriving a match every time. Re-deriving via the adjacent-
    // UID heuristic below becomes ambiguous, and therefore fails, once a fleet has enough devices
    // that more than one cloud UID happens to be +-1 of a given LAN MAC - which is close to
    // guaranteed once a household has a handful of LIFX bulbs bought in the same batch with
    // sequential MACs. This was silently wiping a previously-confirmed device's LAN data on every
    // later Discovery run whenever that ambiguity occurred, regardless of the device's own
    // reachability.
    //
    // Some LIFX devices themselves report their identifying MAC two different ways depending on
    // which response type answered (Hubitat's own sourceMac field, "often Cloud UID + 1", vs the
    // LIFX protocol header's target field, "usually the Cloud UID" with no offset - see parseLifx()).
    // lanUidAlt holds whichever of those two flavours isn't the confirmed primary, so a response
    // reporting the other flavour still matches this same row instead of being treated as unmatched
    // or silently overwriting the confirmed primary (which is what created child DNIs are built from).
    Map learned = cloud.find { k, v ->
        Map row = v as Map
        String vLan = normalisedUid(row?.lanUid)
        String vAlt = normalisedUid(row?.lanUidAlt)
        (vLan && vLan == lan) || (vAlt && vAlt == lan)
    }?.value as Map
    if (learned) return [cloudId: normalisedUid(learned.id ?: learned.uid ?: lan), curated: learned, matchType: "learned"]

    // 1. Exact match wins.
    Map exact = cloud[lan] as Map
    if (exact) return [cloudId: lan, curated: exact, matchType: "exact"]

    // 2. Adjacent UID match. Some LIFX devices expose cloud serial and LAN MAC as adjacent values.
    List matches = []
    cloud.each { cloudId, light ->
        String type = uidMatchType(cloudId?.toString(), lan)
        if (type == "cloud+1" || type == "cloud-1") {
            matches << [cloudId: cloudId.toString(), curated: light as Map, matchType: type]
        }
    }

    // Do not guess if more than one adjacent candidate exists.
    return matches.size() == 1 ? matches[0] as Map : null
}

Map findLanRecordForCloudId(String cloudId, Map records) {
    String cid = normalisedUid(cloudId)
    if (!cid || !records) return null

    // 1. Exact match wins.
    Map exact = records[cid] as Map
    if (exact) return exact

    // 2. Unique adjacent match.
    List matches = []
    records.each { lanUid, dev ->
        String type = uidMatchType(cid, lanUid?.toString())
        if (type == "cloud+1" || type == "cloud-1") matches << (dev as Map)
    }

    return matches.size() == 1 ? matches[0] as Map : null
}

String uidMatchType(String cloudUid, String lanUid) {
    String c = normalisedUid(cloudUid)
    String l = normalisedUid(lanUid)
    if (!c || !l) return ""
    if (c == l) return "exact"

    try {
        BigInteger cb = new BigInteger(c, 16)
        BigInteger lb = new BigInteger(l, 16)
        if (lb == cb.add(BigInteger.ONE)) return "cloud+1"
        if (lb == cb.subtract(BigInteger.ONE)) return "cloud-1"
    } catch (Throwable t) {
        log.debug "uidMatchType() failed: ${t.message}"
    }
    return ""
}

String cloudCapability(Map c) {
    if (!c) return "Unknown"
    String product = (c.productName ?: c.productIdentifier ?: "").toString().toLowerCase()
    if (product.contains("day and dusk") || product.contains("white to warm") || product.contains("warm to white")) return "Tunable white light"
    if (product.contains("mini white") || product.contains("filament") || product.contains("white mono")) return "White mono light"
    if (truthy(c.hasIr)) return "Colour light with infrared"
    if (truthy(c.hasMatrix)) return "Matrix/tile colour light"
    if (truthy(c.hasMultizone) || truthy(c.hasChain)) return "Multizone colour light"
    if (truthy(c.hasColor)) return "Colour light"
    if (truthy(c.hasVariableColorTemp)) return "Tunable white light"
    return "White mono light"
}

String cloudDriverMode(Map c) {
    if (!c) return "Unknown"
    String product = (c.productName ?: c.productIdentifier ?: "").toString().toLowerCase()
    if (product.contains("day and dusk") || product.contains("white to warm") || product.contains("warm to white")) return "Switch + level + colour temperature"
    if (product.contains("mini white") || product.contains("filament") || product.contains("white mono")) return "Switch + level"
    if (truthy(c.hasIr)) return "Switch + level + colour + CT + IR"
    if (truthy(c.hasMatrix)) return "Tile/matrix driver later; basic colour fallback"
    if (truthy(c.hasMultizone) || truthy(c.hasChain)) return "Multizone driver later; basic colour fallback"
    if (truthy(c.hasColor)) return "Switch + level + colour + CT"
    if (truthy(c.hasVariableColorTemp)) return "Switch + level + colour temperature"
    return "Switch + level"
}

Boolean truthy(value) {
    if (value == null) return false
    if (value instanceof Boolean) return value
    return value.toString().equalsIgnoreCase("true")
}

String tableOpenHtml() {
    "<table style='width:auto;border-collapse:collapse;font-size:12px;table-layout:auto;user-select:text'>"
}

// Each <table> auto-sizes its own columns independently, so the same UID/Label/IP/Last seen
// columns can otherwise end up a different width in each table depending on what else that
// table contains, making it hard to visually cross-reference a device between tables. These
// "identity" columns get a fixed width by semantic key instead, shared across all three tables;
// everything else stays auto-sized with a wrap cap for anything unexpectedly long.
String alignedColumnWidth(String key) {
    if (key == "uid") return "width:105px;min-width:105px;max-width:105px;"
    if (key == "label") return "width:165px;min-width:165px;max-width:165px;"
    if (key == "localName") return "width:120px;min-width:120px;max-width:120px;"
    if (key == "ip") return "width:90px;min-width:90px;max-width:90px;"
    if (key == "lastSeen") return "width:90px;min-width:90px;max-width:90px;"
    return ""
}

// Headers are always short, controlled text (unlike cell data, which can be arbitrarily long),
// so they stay on one line rather than wrapping - this also gives the column a sane minimum
// width so short data values (e.g. "3.90") don't leave the header squeezed narrower than itself.
String headerCell(value, Integer index = null, String key = null) {
    String extra = key ? alignedColumnWidth(key) : ""
    "<th style='text-align:left;border:1px solid #ccc;padding:3px;vertical-align:top;user-select:text;white-space:nowrap;${extra}'>${html(value == null ? '' : value.toString())}</th>"
}

String cell(value, Integer index = null, String key = null) {
    String extra = key ? alignedColumnWidth(key) : ""
    "<td style='border:1px solid #ccc;padding:3px;vertical-align:top;overflow:visible;text-overflow:clip;white-space:normal;word-break:break-word;max-width:400px;user-select:text;${extra}'>${html(value == null ? '' : value.toString())}</td>"
}

String html(String s) {
    if (s == null) return ""
    s.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;')
}

String safeMessage(String s) {
    if (!s) return "unknown error"
    s.take(200)
}

String curatedLastSeen(Map row) {
    Long cloudMs = epochMillis(row?.lastSeenCloud)
    Long lanMs = epochMillis(row?.lanLastSeen)
    Long best = Math.max(cloudMs ?: 0L, lanMs ?: 0L)
    return best > 0L ? formatDateTime(best) : ""
}

String formatDateTime(value) {
    Long ms = epochMillis(value)
    if (!ms) return ""
    try { return new Date(ms).format("dd-MMM HH:mm", location.timeZone) } catch (Throwable t) { log.debug "formatDateTime() failed: ${t.message}"; return "" }
}

Long epochMillis(value) {
    if (!value) return 0L
    // Trying several representations/formats in turn is expected, normal control flow here
    // (LIFX Cloud timestamps and locally-recorded millis are both passed through this same
    // helper) - only the final, total failure is worth a log line, not each interim attempt.
    if (value instanceof Number) return (value as Long)

    String s = value.toString()?.trim()
    if (!s) return 0L

    try { return (s as Long) } catch (Throwable ignored) { }

    List patterns = [
        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "yyyy-MM-dd'T'HH:mm:ssX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd HH:mm:ss"
    ]

    for (String pattern : patterns) {
        try {
            def parser = new java.text.SimpleDateFormat(pattern)
            parser.setTimeZone(java.util.TimeZone.getTimeZone("UTC"))
            return parser.parse(s).time
        } catch (Throwable ignored) { }
    }

    log.debug "epochMillis() could not parse '${s}' with any known format"
    return 0L
}

// ---------------- Product fallback capability mapping ----------------

String capabilityForProduct(value) {
    Integer p = safeInt(value)
    if (p == null) return "Unknown"
    if (p in [31,32,38,56]) return "Multizone colour light"
    if (p in [55]) return "Matrix/tile colour light"
    if (p in [10,11,18,19,51,61,66,82,85,87,88,100,101]) return "White mono light"
    if (p in [39,50,60,81,96]) return "Tunable white light"
    if (p in [29,30,45,46,64,65,109,110,111]) return "Colour light with infrared"
    if (p in [1,3,20,22,27,28,36,37,40,43,44,49,52,57,59,62,63,68,91,92,93,94,97,98,99,112,130,182]) return "Colour light"
    return "Unknown LIFX product - generic light"
}

String driverModeForProduct(value) {
    Integer p = safeInt(value)
    if (p == null) return "Unknown"
    if (p in [31,32,38,56]) return "Multizone driver later; basic colour fallback"
    if (p in [55]) return "Tile/matrix driver later; basic colour fallback"
    if (p in [10,11,18,19,51,61,66,82,85,87,88,100,101]) return "Switch + level"
    if (p in [39,50,60,81,96]) return "Switch + level + colour temperature"
    if (p in [29,30,45,46,64,65,109,110,111]) return "Switch + level + colour + CT + IR"
    return "Switch + level + colour + CT"
}

Integer safeInt(value) {
    try { return value == null ? null : (value as Integer) } catch (Throwable t) { log.debug "safeInt() failed: ${t.message}"; return null }
}

// Groovy's ?: treats 0 as falsy, so `safeInt(x) ?: N` silently replaces an explicit 0 with N
// whenever N != 0. These null-aware variants only fall back when the value is genuinely absent
// or unparseable, so a real 0 (e.g. saturation 0, level 0) is preserved.
Integer intOrDefault(value, Integer fallback) {
    Integer parsed = safeInt(value)
    return parsed == null ? fallback : parsed
}

Integer firstNonNullInt(List values, Integer fallback) {
    for (v in values) {
        Integer parsed = safeInt(v)
        if (parsed != null) return parsed
    }
    return fallback
}

// ---------------- Packet construction ----------------

void sendLifx(String ip, Integer messageType) {
    if (!ip) return
    String packet = buildLifxPacket(messageType)
    sendHubCommand(new hubitat.device.HubAction(
        packet,
        hubitat.device.Protocol.LAN,
        [
            type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
            destinationAddress: "${ip}:${LIFX_PORT}",
            encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
            ignoreWarning: true,
            parseWarning: false,
            timeout: 4,
            callback: "parseLifx"
        ]
    ))
}

String buildLifxPacket(Integer messageType) {
    List<Integer> bytes = []
    bytes += u16le(36)
    bytes += [0x00, 0x34]       // protocol 1024, addressable + tagged
    bytes += u32le(0x47544842)  // arbitrary source
    bytes += [0,0,0,0,0,0,0,0]  // zero target
    bytes += [0,0,0,0,0,0]
    bytes += [0x01]             // response required
    bytes += [nextSequence()]
    bytes += [0,0,0,0,0,0,0,0]
    bytes += u16le(messageType)
    bytes += [0,0]
    return bytesToHex(bytes)
}

Integer nextSequence() {
    // Not read back anywhere for response correlation, so a lazily-persisted counter
    // in `state` avoids the synchronous per-packet write cost of `atomicState`.
    Integer seq = ((state.sequence ?: 1) as Integer) & 0xff
    state.sequence = ((seq + 1) & 0xff)
    return seq
}

// ---------------- Network/helpers ----------------

String hubSubnet() {
    try {
        String hubIp = location.hubs[0]?.localIP ?: location.hub?.localIP
        def m = hubIp =~ /^(\d{1,3}\.\d{1,3}\.\d{1,3})\.\d{1,3}$/
        if (m) return "${m[0][1]}."
    } catch (Throwable t) { log.debug "hubSubnet() failed: ${t.message}" }
    return null
}

List broadcastTargets() {
    String subnet = hubSubnet()
    List out = ["255.255.255.255"]
    if (subnet) out << "${subnet}255"
    return out.unique()
}

String normaliseMac(value) {
    if (!value) return ""
    String s = value.toString().replaceAll("[^0-9A-Fa-f]", "").toLowerCase()
    return s.size() >= 12 ? s.substring(0, 12) : s
}


List<Integer> hexToBytes(String hex) {
    if (!hex) return []
    return hex.replaceAll("\\s", "").split("(?<=\\G.{2})").findAll { it }.collect { Integer.parseInt(it, 16) }
}

List<Integer> u16le(Integer v) { [v & 0xff, (v >> 8) & 0xff] }
List<Integer> u32le(Long v) { [(v) & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, (v >> 24) & 0xff] as List<Integer> }
List<Integer> f32le(Float v) { u32le(Float.floatToIntBits(v ?: 0f) as Long) }

Integer leU16(String hex, Integer offset) {
    if (!hex || hex.size() < offset + 4) return 0
    Integer b0 = Integer.parseInt(hex.substring(offset, offset + 2), 16)
    Integer b1 = Integer.parseInt(hex.substring(offset + 2, offset + 4), 16)
    return b0 | (b1 << 8)
}

Long leU32(String hex, Integer offset) {
    if (!hex || hex.size() < offset + 8) return 0L
    Long b0 = Long.parseLong(hex.substring(offset, offset + 2), 16)
    Long b1 = Long.parseLong(hex.substring(offset + 2, offset + 4), 16)
    Long b2 = Long.parseLong(hex.substring(offset + 4, offset + 6), 16)
    Long b3 = Long.parseLong(hex.substring(offset + 6, offset + 8), 16)
    return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)
}

Float leFloat32(String hex, Integer offset) {
    Long bits = leU32(hex, offset)
    return Float.intBitsToFloat(bits.intValue())
}

Long leU64(String hex, Integer offset) {
    if (!hex || hex.size() < offset + 16) return 0L
    BigInteger value = BigInteger.ZERO
    for (int i = 7; i >= 0; i--) {
        Integer pos = offset + (i * 2)
        value = value.shiftLeft(8).add(new BigInteger(hex.substring(pos, pos + 2), 16))
    }
    return value.longValue()
}

String bytesToHex(List<Integer> bytes) {
    bytes.collect { String.format("%02x", it & 0xff) }.join()
}

String hexToIp(String hex) {
    if (!hex || hex.size() < 8) return ""
    return [0,2,4,6].collect { Integer.parseInt(hex.substring(it, it + 2), 16) }.join(".")
}

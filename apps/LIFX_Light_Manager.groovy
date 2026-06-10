/*
 * LIFX Light Manager
 * Namespace: Hubitat Integrations
 * Version: B1.1
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
 *
 */

definition(
    name: "LIFX Light Manager",
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
    page(name: "mainPage", title: "LIFX Light Manager", install: true, uninstall: true)
}

// ---------------- Hubitat lifecycle ----------------

def installed() { initialiseState() }
def updated() { initialiseState() }
def uninstalled() { unschedule() }

def appButtonHandler(String btn) {
    if (btn == "discoverBtn") startCombinedDiscovery()
    if (btn == "createSelectedChildrenBtn") createOrUpdateSelectedChildDevicesFromCurated()
    if (btn == "createAllChildrenBtn") createOrUpdateAllChildDevicesFromCurated()
    if (btn == "createFastGroupBtn") createOrUpdateFastGroupChildDevice()
    if (btn == "advancedBtn") toggleAdvanced()
    if (btn == "clearAllBtn") clearAllData()
}

def mainPage(params = null) {
    initialiseState()
    Boolean advanced = atomicState.showAdvanced == true

    // Auto-refresh only while discovery is running. Leaving refresh on permanently makes
    // Hubitat re-render the page while editing text, ticking boxes, or selecting table text.
    if (isDiscoveryRunning()) {
        return dynamicPage(name: "mainPage", title: "LIFX Light Manager", install: true, uninstall: true, refreshInterval: 3) {
            renderMainPageContent(advanced)
        }
    }

    return dynamicPage(name: "mainPage", title: "LIFX Light Manager", install: true, uninstall: true) {
        renderMainPageContent(advanced)
    }
}

void renderMainPageContent(Boolean advanced) {
    section("LIFX discovery") {
        paragraph "Go to <a href='https://cloud.lifx.com/' target='_blank'>https://cloud.lifx.com/</a>, log in using your LIFX credentials, then use the top-right account menu to acquire a Personal Access Token. Paste that token below before running Discovery."
        input "lifxCloudToken", "string",
            title: "LIFX Personal Access Token",
            defaultValue: "",
            required: false,
            submitOnChange: true
        if (atomicState.tokenError) {
            paragraph "<div style='font-weight:bold;color:#cc0000'>${atomicState.tokenError}</div>"
        }
        input "discoverBtn", "button", title: "Discovery", submitOnChange: true
        input "childNamePrefix", "text",
            title: "Optional child device name prefix",
            description: "Example: Lounge. Leave blank to use the detected label exactly.",
            required: false,
            submitOnChange: false

        Map childOptions = childCreationOptions()
        if (childOptions) {
            paragraph childCreationActionHtml()
            childOptions.each { uid, title ->
                Boolean defaultSelected = childSelectDefault(uid.toString())
                if (defaultSelected) {
                    try { app.updateSetting(childSelectSettingName(uid.toString()), [type: "bool", value: true]) } catch (Throwable ignored) { }
                }
                input childSelectSettingName(uid.toString()), "bool",
                    title: title.toString(),
                    defaultValue: defaultSelected,
                    required: false,
                    submitOnChange: true
            }
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

    if (isDiscoveryRunning()) {
        section("Status") {
            paragraph "<div style='font-weight:bold;color:#cc0000'>Network Discovery in progress - this could take up to 2 minutes on a standard /24 network, please wait for it to complete</div>"
        }
    } else if ((atomicState.status ?: "idle") == "complete") {
        section("Status") {
            paragraph "<div style='font-weight:bold;color:#008000'>Discovery completed</div>"
        }
    }

    if (atomicState.childCreateResult) {
        section("Child device creation") {
            paragraph atomicState.childCreateResult
        }
    }

    section("Device preparation table") {
        paragraph curatedTableHtml()
    }

    if (advanced) {
        section("Advanced status") {
            paragraph statusHtml()
        }
        section("LIFX Cloud source table") {
            paragraph cloudTableHtml()
        }
        section("LAN responses") {
            paragraph lanTableHtml()
        }
    }
}

void initialiseState() {
    if (atomicState.records == null) atomicState.records = [:]
    if (atomicState.byIp == null) atomicState.byIp = [:]
    if (atomicState.cloudLights == null) atomicState.cloudLights = [:]
    if (atomicState.expectedIds == null) atomicState.expectedIds = [:]
    if (atomicState.sequence == null) atomicState.sequence = 1
    if (atomicState.status == null) atomicState.status = "idle"
    if (atomicState.phase == null) atomicState.phase = "Idle"
    if (atomicState.stats == null) atomicState.stats = emptyStats()
    if (atomicState.cloudStatus == null) atomicState.cloudStatus = "not tested"
    if (atomicState.curatedReady == null) atomicState.curatedReady = true
    if (atomicState.curatedRows == null) atomicState.curatedRows = [:]
    if (atomicState.discoveryMode == null) atomicState.discoveryMode = "cloud-led"
    if (atomicState.showAdvanced == null) atomicState.showAdvanced = false
    if (atomicState.childCreateResult == null) atomicState.childCreateResult = ""
    if (lifxCloudToken == null) app.updateSetting("lifxCloudToken", [type: "string", value: ""])
    if (fastGroupName == null) app.updateSetting("fastGroupName", [type: "text", value: "LIFX MASTER SWITCH"])
    try { app.updateSetting(masterSwitchSelectSettingName(), [type: "bool", value: true]) } catch (Throwable ignored) { }
}

Map emptyStats() {
    [cloudLights:0, expected:0, matched:0, missing:0, broadcastPulses:0, secondBroadcastPulses:0, sweepSent:0, sweepTotal:0, sweepSkipped:0, retryPass:0, rawResponses:0, service:0, version:0, errors:0, lanOnly:0]
}

// ---------------- Cloud-first locator flow ----------------

Boolean isDiscoveryRunning() {
    return ((atomicState.status ?: "idle") in ["cloud", "starting-lan", "broadcast", "sweep"])
}

void toggleAdvanced() {
    atomicState.showAdvanced = !(atomicState.showAdvanced == true)
}

void startCombinedDiscovery() {
    unschedule()
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
    atomicState.forceFullLanDiscovery = true
    atomicState.records = [:]
    atomicState.byIp = [:]
    clearSavedLanDiscoveryFields()
    atomicState.stats = emptyStats()
    atomicState.status = "cloud"
    atomicState.discoveryMode = "cloud-led"
    atomicState.phase = "Network Discovery in progress - this could take up to 2 minutes on a standard /24 network, please wait for it to complete"
    atomicState.cloudStatus = "retrieving"
    atomicState.curatedReady = false
    state.sweepQueue = []
    fetchCloudLights(false)
}

void clearSavedLanDiscoveryFields() {
    Map curated = atomicState.curatedRows ?: [:]
    if (!curated) return
    curated.each { k, v ->
        Map row = (v ?: [:]) as Map
        row.previousIp = row.ip ?: row.previousIp
        row.ip = ""
        row.port = row.port ?: 56700
        row.lanUid = ""
        row.lanMac = ""
        row.mac = ""
        row.sourceMac = ""
        row.controlUid = ""
        row.protocolTargetUid = ""
        row.target = ""
        row.lanLastSeen = null
        row.status = "Awaiting LAN rediscovery"
        curated[k] = row
    }
    atomicState.curatedRows = curated
}

void startCloudDiscovery() {
    unschedule()
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
    unschedule()
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

    if (allExpectedFound() && atomicState.forceFullLanDiscovery != true) {
        atomicState.status = "complete"
        atomicState.phase = "Saved device table already has IP addresses for all rows"
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
                atomicState.cloudStatus = "HTTP ${code}"
                atomicState.status = "cloud-error"
                atomicState.phase = "Cloud API returned HTTP ${code}"
                atomicState.curatedReady = true
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

void startInitialBroadcast() {
    startBroadcastPhase("initial", "Initial 5 second broadcast pulse", 5000L)
}

void startSecondBroadcast() {
    if (allExpectedFound()) {
        finishLocator("Discovery completed - all expected cloud devices have LAN IPs")
        return
    }
    startBroadcastPhase("second", "Second 5 second broadcast pulse after sweep", 5000L)
}

void startBroadcastPhase(String stage, String phaseText, Long durationMs) {
    atomicState.status = "broadcast"
    atomicState.broadcastStage = stage
    atomicState.phase = phaseText
    atomicState.broadcastUntil = now() + durationMs
    runInMillis(50, "broadcastPulse")
}

@SuppressWarnings("unused")
void broadcastPulse() {
    if ((atomicState.status ?: "") != "broadcast") return

    if (allExpectedFound()) {
        finishLocator("Discovery completed - all expected cloud devices have LAN IPs")
        return
    }

    String stage = atomicState.broadcastStage ?: "initial"
    if (now() >= ((atomicState.broadcastUntil ?: 0L) as Long)) {
        if (stage == "initial") {
            startSweepPhase("fast", 1, 50, "secondBroadcast")
        } else {
            startRetrySweep(1)
        }
        return
    }

    broadcastTargets().each { ip ->
        sendLifx(ip.toString(), 2)   // DEVICE.GET_SERVICE
        sendLifx(ip.toString(), 32)  // DEVICE.GET_VERSION
    }

    Map stats = atomicState.stats ?: emptyStats()
    if (stage == "second") {
        stats.secondBroadcastPulses = ((stats.secondBroadcastPulses ?: 0) as Integer) + 1
    } else {
        stats.broadcastPulses = ((stats.broadcastPulses ?: 0) as Integer) + 1
    }
    atomicState.stats = stats
    runInMillis(500, "broadcastPulse")
}

void startRetrySweep(Integer pass) {
    if (allExpectedFound()) {
        finishLocator("Discovery completed - all expected cloud devices have LAN IPs")
        return
    }
    startSweepPhase("retry", pass, 50, pass < 2 ? "retryNext" : "slowSweep")
}

void startSlowSweep() {
    if (allExpectedFound()) {
        finishLocator("Discovery completed - all expected cloud devices have LAN IPs")
        return
    }
    startSweepPhase("slow", 1, 100, "finish")
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
    stats.sweepSkipped = skipped
    stats.retryPass = kind == "retry" ? pass : (kind == "slow" ? 3 : 0)
    atomicState.stats = stats

    runInMillis(20, "processSweepBatch")
}

@SuppressWarnings("unused")
void processSweepBatch() {
    if ((atomicState.status ?: "") != "sweep") return

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
            finishLocator("Resilient IP location cycle complete")
        }
        return
    }

    Integer pauseMs = ((atomicState.sweepPauseMs ?: 50) as Integer)
    Integer batchSize = Math.min(10, queue.size())
    for (int i = 0; i < batchSize; i++) {
        if (allExpectedFound()) break
        String ip = queue.remove(0).toString()
        sendLifx(ip, 32) // DEVICE.GET_VERSION exposes MAC + product/version
        Map stats = atomicState.stats ?: emptyStats()
        stats.sweepSent = ((stats.sweepSent ?: 0) as Integer) + 1
        atomicState.stats = stats
        pauseExecution(pauseMs)
    }
    state.sweepQueue = queue
    runInMillis(20, "processSweepBatch")
}

Boolean isIpAlreadyMatchedToCloud(String ip) {
    if (!ip) return false
    Map byIp = atomicState.byIp ?: [:]
    String lanUid = byIp[ip]?.toString()
    if (!lanUid) return false
    return findCuratedMatchForLanUid(lanUid, atomicState.curatedRows ?: [:]) != null
}

void finishLocator(String reason) {
    unschedule()
    state.sweepQueue = []
    updateMatchStats()
    atomicState.status = "complete"
    atomicState.phase = "Discovery completed"
    atomicState.curatedReady = true
    refreshMasterSwitchMembership()
}

void stopLocator() {
    unschedule()
    if ((atomicState.status ?: "idle") in ["cloud", "broadcast", "sweep"]) {
        updateMatchStats()
        atomicState.status = "stopped"
        atomicState.phase = "Stopped"
        atomicState.curatedReady = true
    }
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
    try { app.removeSetting("selectedChildUids") } catch (Throwable ignored) { }
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
    dev.port = 56700
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
        case 3:
            stats.service = ((stats.service ?: 0) as Integer) + 1
            break
        case 33:
            Map version = parseStateVersion(parsed.payloadHex)
            dev.vendor = version.vendor ?: dev.vendor
            dev.product = version.product ?: dev.product
            dev.version = version.version ?: dev.version
            if (!dev.baseCapability) dev.baseCapability = capabilityForProduct(dev.product)
            if (!dev.driverMode) dev.driverMode = driverModeForProduct(dev.product)
            if (c) {
                c.lanProduct = dev.product ?: c.lanProduct
                c.status = c.status ?: "LAN found - ${cloudMatch?.matchType ?: ''}"
            }
            // Restore original discovery chain for LAN-only fallback naming.
            sendLifx(ip, 51) // DEVICE.GET_GROUP
            stats.version = ((stats.version ?: 0) as Integer) + 1
            break
        case 53:
            Map groupData = parseLabelPayload(parsed.payloadHex)
            if (groupData.label) dev.group = groupData.label
            sendLifx(ip, 48) // DEVICE.GET_LOCATION
            break
        case 50:
            Map locationData = parseLabelPayload(parsed.payloadHex)
            if (locationData.label) dev.location = locationData.label
            sendLifx(ip, 23) // DEVICE.GET_LABEL
            break
        case 25:
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
            c.port = 56700
            c.lanUid = mac
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


Map parseLabelPayload(String payloadHex) {
    if (!payloadHex || payloadHex.size() < 96) return [:]
    return [label: officialDeviceName(decodeLabel(payloadHex.substring(32)))]
}

String decodeLabel(String payloadHex) {
    try {
        String labelHex = (payloadHex ?: "").take(64)
        byte[] bytes = hexToBytes(labelHex) as byte[]
        return new String(bytes, "UTF-8").replaceAll("\u0000", "").trim()
    } catch (Throwable ignored) {
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
    row.port = dev.port ?: 56700
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
        String installed = child ? (currentDriver && !driverNamesEquivalent(currentDriver, driver) ? "installed as ${currentDriverDisplay}; expected ${driverDisplay}" : "installed") : "not installed"
        String label = (row.label ?: uid).toString()
        String ip = (row.ip ?: "no IP").toString()
        String note = ipChanged ? " - Update required due to IP address change" : ""
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
    try { oldIp = (child.getDataValue('ip') ?: "").toString().trim() } catch (Throwable ignored) { }
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
    return "LIFX MASTER SWITCH - provides overall control of all the above LIFX lights - ${status}".toString()
}

Boolean isMasterSwitchSelected() {
    String settingName = masterSwitchSelectSettingName()
    try {
        def value = settings[settingName]
        if (value == null) return true
        return truthy(value)
    } catch (Throwable ignored) { }
    try {
        def value = this."${settingName}"
        if (value == null) return true
        return truthy(value)
    } catch (Throwable ignored) { }
    return true
}

String childSelectSettingName(String uidValue) {
    String uid = normalisedUid(uidValue)
    return uid ? "selectChild_${uid}".toString() : "selectChild_invalid"
}

Boolean isChildUidSelected(String uidValue) {
    String settingName = childSelectSettingName(uidValue)
    try { return truthy(settings[settingName]) } catch (Throwable ignored) { }
    try { return truthy(this."${settingName}") } catch (Throwable ignored) { }
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
            try { app.removeSetting(childSelectSettingName(uid.toString())) } catch (Throwable ignoredInner) { }
        }
    } catch (Throwable ignored) { }
}

String childLabelForRow(Map row) {
    String base = (row?.label ?: row?.id ?: row?.uid ?: "LIFX Device").toString().trim()
    String prefix = (childNamePrefix ?: "").toString().trim()
    return prefix ? "${prefix} ${base}".toString() : base
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
    return uid ? "lifx-curated-${uid}".toString() : ""
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
    if (ir) return "LIFX Local Plus Colour"

    // Product ID is the strongest local signal after explicit IR capability.
    if (lanProduct in [10,11,18,19,51,61,66,82,85,87,88,100,101]) return "LIFX Local White Mono"
    if (lanProduct in [39,50,60,81,96]) return "LIFX Local Tunable White"
    if (lanProduct in [29,30,45,46,64,65,109,110,111]) return "LIFX Local Plus Colour"
    if (lanProduct in [1,3,20,22,27,28,36,37,40,43,44,49,52,57,59,62,63,68,91,92,93,94,97,98,99,112,130,182]) return "LIFX Local Colour"

    // Product family is the next strongest signal. This prevents CT-only wording being
    // mistaken for RGB colour support.
    Boolean productTunableWhite = product.contains("day and dusk") || product.contains("white to warm") || product.contains("warm to white")
    Boolean productWhiteMono = product.contains("mini white") || product.contains("filament") || product.contains("white mono") || product.contains("white 800") || product.contains("white 900")
    if (productWhiteMono) return "LIFX Local White Mono"
    if (productTunableWhite) return "LIFX Local Tunable White"

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

    if (hasRealColour && ir) return "LIFX Local Plus Colour"
    if (hasRealColour) return "LIFX Local Colour"
    if (ctOnly) return "LIFX Local Tunable White"
    return "LIFX Local White Mono"
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
    try { return child.typeName?.toString() ?: "" } catch (Throwable ignored) { }
    try { return child.getTypeName()?.toString() ?: "" } catch (Throwable ignored) { }
    try { return child.getDataValue('driverType')?.toString() ?: "" } catch (Throwable ignored) { }
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
                try { existing.setLabel(label) } catch (Throwable ignored) { }
                updateChildDataValues(existing, row, driverType)
                row.childDni = dni
                row.childDriver = driverType
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
                row.childStatus = "Created"
                created << "${html(label)} (${html(driverType)})"
                syncChildRuntimeAttributes(child, row, driverType)
                try { child.refresh() } catch (Throwable ignored) { }
            }
            curated[row.id ?: row.uid ?: uid] = row
        } catch (com.hubitat.app.exception.UnknownDeviceTypeException e) {
            row.childStatus = "Driver missing: ${driverType}"
            failed << "${html(label)}: driver '${html(driverType)}' is not installed"
        } catch (Throwable t) {
            row.childStatus = "Failed: ${safeMessage(t.message)}"
            failed << "${html(label)}: ${html(safeMessage(t.message))}"
        }
        pauseExecution(100)
    }

    atomicState.curatedRows = curated

    String result = "<b>Child-device action:</b> ${html(actionLabel ?: '')}<br/>"
    if (created) result += "<br/><b>Created</b><br/>${created.join('<br/>')}<br/>"
    if (updated) result += "<br/><b>Updated</b><br/>${updated.join('<br/>')}<br/>"
    if (skipped) result += "<br/><b>Skipped</b><br/>${skipped.join('<br/>')}<br/>"
    if (failed) result += "<br/><b>Failed</b><br/>${failed.join('<br/>')}<br/>"

    String childResult = (created || updated || skipped || failed) ? result : "No child-device changes made."
    if (ensureMasterSwitch) {
        String before = childResult
        createOrUpdateFastGroupChildDevice()
        refreshMasterSwitchMembership()
        childResult = before + "<br/><br/><b>LIFX MASTER SWITCH</b><br/>" + (atomicState.childCreateResult ?: "Created/updated LIFX MASTER SWITCH")
    } else {
        refreshMasterSwitchMembership()
    }
    atomicState.childCreateResult = childResult
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
        port: "${row.port ?: 56700}",
        label: "${row.label ?: ''}",
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
        try { child.updateDataValue(k.toString(), v == null ? "" : v.toString()) } catch (Throwable ignored) { }
    }
    syncChildRuntimeAttributes(child, row, driverType)
}

void syncChildRuntimeAttributes(child, Map row, String driverType = "") {
    if (!child || !row) return

    // Data values drive command routing, but Hubitat's Commands page shows current-state
    // attributes. When an IP address changes, update both immediately so users do not
    // need to manually Initialize the child device to clear the old Lan Ip display.
    try { child.sendEvent(name: "uid", value: "${lanUidForRow(row) ?: cloudUidForRow(row) ?: ''}", displayed: false) } catch (Throwable ignored) { }
    try { child.sendEvent(name: "lanIp", value: "${row.ip ?: ''}", displayed: false) } catch (Throwable ignored) { }
    try { child.sendEvent(name: "label", value: "${row.label ?: ''}", displayed: false) } catch (Throwable ignored) { }

    if (truthy(row.hasVariableColorTemp)) {
        Integer kelvin = safeInt(row.kelvin) ?: safeInt(row.colorTemperature) ?: null
        if (kelvin) {
            try { child.sendEvent(name: "colorTemperature", value: kelvin, displayed: false) } catch (Throwable ignored) { }
        }
    }
    if (driverType) {
        try { child.updateDataValue("driverType", driverType.toString()) } catch (Throwable ignored) { }
    }
}


// ---------------- Fast aggregate / bulk control ----------------

String fastGroupDni() {
    return "lifx-master-switch"
}

String fastGroupLabel() {
    String prefix = (childNamePrefix ?: "").toString().trim()
    return prefix ? "${prefix} LIFX MASTER SWITCH".toString() : "LIFX MASTER SWITCH"
}

void createOrUpdateFastGroupChildDevice() {
    String dni = fastGroupDni()
    String label = fastGroupLabel()
    String driverType = "LIFX Master Switch"
    try {
        def existing = getChildDevice(dni)
        if (existing) {
            try { existing.setLabel(label) } catch (Throwable ignored) { }
            try { existing.updateDataValue("driverType", driverType) } catch (Throwable ignored) { }
            try { existing.updateDataValue("groupMode", "fast-bulk") } catch (Throwable ignored) { }
            try { existing.updateDataValue("managedBy", "LIFX Light Manager") } catch (Throwable ignored) { }
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
                data: [driverType: driverType, groupMode: "fast-bulk", managedBy: "LIFX Light Manager"]
            ]
        )
        refreshMasterSwitchMembership()
        atomicState.childCreateResult = "Created LIFX MASTER SWITCH device: ${html(label)}"
    } catch (com.hubitat.app.exception.UnknownDeviceTypeException e) {
        atomicState.childCreateResult = "LIFX Master Switch driver is not installed: ${html(driverType)}"
    } catch (Throwable t) {
        atomicState.childCreateResult = "Failed to create LIFX MASTER SWITCH: ${html(safeMessage(t.message))}"
    }
}

List<String> managedLifxDriverNames() {
    return [
        "LIFX Local White Mono",
        "LIFX Local Tunable White",
        "LIFX Local Colour",
        "LIFX Local Plus Colour"
    ]
}

String childDataValue(child, String key) {
    if (!child || !key) return ""
    try { return child.getDataValue(key)?.toString() ?: "" } catch (Throwable ignored) { }
    return ""
}

Boolean isManagedLifxLightChild(child) {
    if (!child) return false
    String dni = ""
    String type = ""
    try { dni = child.deviceNetworkId?.toString() ?: "" } catch (Throwable ignored) { }
    if (dni == fastGroupDni()) return false
    try { type = child.typeName?.toString() ?: "" } catch (Throwable ignored) { }
    if (!type) type = childDataValue(child, "driverType")
    return managedLifxDriverNames().contains(type)
}

List managedLifxLightChildren() {
    return getChildDevices()
        .findAll { child -> isManagedLifxLightChild(child) }
        .sort { a, b ->
            String al = ""
            String bl = ""
            try { al = a.displayName?.toString()?.toLowerCase() ?: "" } catch (Throwable ignored) { }
            try { bl = b.displayName?.toString()?.toLowerCase() ?: "" } catch (Throwable ignored) { }
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
    try { dni = child.deviceNetworkId?.toString() ?: "" } catch (Throwable ignored) { }
    try { type = child.typeName?.toString() ?: "" } catch (Throwable ignored) { }
    try { label = child.displayName?.toString() ?: "" } catch (Throwable ignored) { }

    String uidFromDni = dni.startsWith("lifx-curated-") ? dni.replaceFirst("lifx-curated-", "") : dni
    String lanUid = normalisedUid(childDataValue(child, "lanUid") ?: childDataValue(child, "uid") ?: uidFromDni)
    String controlUid = normalisedUid(childDataValue(child, "controlUid") ?: childDataValue(child, "protocolTargetUid") ?: childDataValue(child, "cloudUid") ?: lanUid)

    row.label = row.label ?: childDataValue(child, "label") ?: label
    row.ip = childDataValue(child, "ip") ?: row.ip
    row.port = childDataValue(child, "port") ?: row.port ?: 56700
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

void refreshMasterSwitchMembership(masterDevice = null) {
    def dev = masterDevice ?: getChildDevice(fastGroupDni())
    if (!dev) return
    List<Map> rows = bulkControlRows()
    Integer total = rows.size()
    Integer onCount = 0
    rows.each { row ->
        def child = getChildDevice(childDniForBulkRow(row as Map))
        try { if (child?.currentValue('switch') == 'on') onCount++ } catch (Throwable ignored) { }
    }
    try { dev.updateDataValue("memberCount", total.toString()) } catch (Throwable ignored) { }
    try { dev.sendEvent(name: "memberCount", value: total) } catch (Throwable ignored) { }
    try { dev.sendEvent(name: "onMemberCount", value: onCount) } catch (Throwable ignored) { }
}

void groupChildOn(device) {
    sendBulkSetPower(65535, 0)
    try { device.sendEvent(name: "switch", value: "on") } catch (Throwable ignored) { }
}

void groupChildOff(device) {
    sendBulkSetPower(0, 0)
    try { device.sendEvent(name: "switch", value: "off") } catch (Throwable ignored) { }
}

void groupChildSetLevel(device, value, duration = 0) {
    Integer level = clampInt(safeInt(value) ?: 0, 0, 100)
    sendBulkSetLevel(level, durationMs(duration))
    try {
        device.sendEvent(name: "level", value: level)
        device.sendEvent(name: "switch", value: level > 0 ? "on" : "off")
    } catch (Throwable ignored) { }
}

void groupChildSetColor(device, Map colorMap, duration = 0) {
    Map cmap = colorMap ?: [:]
    Integer hue100 = clampInt(safeInt(cmap.hue) ?: 0, 0, 100)
    Integer sat100 = clampInt(safeInt(cmap.saturation) ?: 100, 0, 100)
    Integer lvl = clampInt(safeInt(cmap.level ?: cmap.brightness) ?: 75, 0, 100)
    Integer requestedKelvin = safeInt(cmap.colorTemperature ?: cmap.kelvin) ?: 3500
    Integer dur = durationMs(cmap.duration ?: duration ?: 0)
    sendBulkSetColorOrLevel(hue100, sat100, lvl, requestedKelvin, dur)
    try {
        device.sendEvent(name: "hue", value: hue100)
        device.sendEvent(name: "saturation", value: sat100)
        device.sendEvent(name: "level", value: lvl)
        device.sendEvent(name: "colorTemperature", value: requestedKelvin)
        device.sendEvent(name: "colorMode", value: "RGB")
        device.sendEvent(name: "switch", value: lvl > 0 ? "on" : "off")
    } catch (Throwable ignored) { }
}

void groupChildSetColorTemperature(device, temperature = 3000, level = 75, duration = 0) {
    Integer kelvin = clampInt(safeInt(temperature) ?: 3000, 1500, 9000)
    Integer lvl = clampInt(safeInt(level) ?: 75, 0, 100)
    sendBulkSetColorTemperature(kelvin, lvl, durationMs(duration))
    try {
        device.sendEvent(name: "colorTemperature", value: kelvin)
        device.sendEvent(name: "level", value: lvl)
        device.sendEvent(name: "colorMode", value: "CT")
        device.sendEvent(name: "switch", value: lvl > 0 ? "on" : "off")
    } catch (Throwable ignored) { }
}

void groupChildRefresh(device) {
    List<Map> rows = bulkControlRows()
    Integer total = rows.size()
    Integer onCount = 0
    rows.each { row ->
        def child = getChildDevice(childDniForBulkRow(row as Map))
        try { if (child?.currentValue('switch') == 'on') onCount++ } catch (Throwable ignored) { }
    }
    try {
        device.sendEvent(name: "switch", value: onCount > 0 ? "on" : "off")
        device.sendEvent(name: "level", value: onCount > 0 ? 100 : 0)
        device.sendEvent(name: "memberCount", value: total)
        device.sendEvent(name: "onMemberCount", value: onCount)
    } catch (Throwable ignored) { }
}

String fastSetPowerPacketHex(Integer power, Integer durationMs = 0) {
    // Exposed for child drivers so individual child on/off can mirror the original Rob Heyes dispatch pattern:
    // driver calls parent to build the lightweight zero-target packet, then the driver sends one UDP packet
    // directly to its stored IP with ignoreResponse=true.
    return buildLifxPacketForTarget(117, u16le(power ?: 0) + u32le(durationMs ?: 0), "", true, false, false)
}

void sendBulkSetPower(Integer power, Integer durationMs = 0) {
    List<Map> rows = bulkControlRows()
    if (!rows) return
    List<Integer> payload = u16le(power ?: 0) + u32le(durationMs ?: 0)

    // v4.7.3: app/fast-group power switching uses the original Rob Heyes style fast path:
    // one IP-directed, zero-target/tagged SET_POWER packet per light, no ACK,
    // no response, no callback, no UID-candidate fan-out.
    rows.each { row -> sendFastSetToRow(row as Map, 117, payload) }

    String sw = (power ?: 0) > 0 ? "on" : "off"
    rows.each { row ->
        def child = getChildDevice(childDniForBulkRow(row as Map))
        if (child) {
            try { child.sendEvent(name: "switch", value: sw) } catch (Throwable ignored) { }
            if ((power ?: 0) == 0) {
                try { child.sendEvent(name: "level", value: 0) } catch (Throwable ignored) { }
            }
        }
    }
}

void sendBulkSetLevel(Integer level, Integer durationMs = 0) {
    List<Map> rows = bulkControlRows()
    if (!rows) return
    Integer brightness = scalePercentToLifx(level)
    rows.each { item ->
        Map row = item as Map
        Integer kelvin = clampKelvin(row, safeInt(row.defaultKelvin) ?: safeInt(row.minKelvin) ?: 3500)
        List<Integer> payload = [0] + u16le(0) + u16le(0) + u16le(brightness) + u16le(kelvin) + u32le(durationMs ?: 0)
        sendFastSetToRow(row, 102, payload)
    }
    rows.each { row ->
        def child = getChildDevice(childDniForBulkRow(row as Map))
        if (child) {
            try {
                child.sendEvent(name: "level", value: level)
                child.sendEvent(name: "switch", value: level > 0 ? "on" : "off")
            } catch (Throwable ignored) { }
        }
    }
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

void sendBulkSetColorOrLevel(Integer hue100, Integer sat100, Integer level, Integer kelvin, Integer durationMs = 0) {
    List<Map> rows = bulkControlRows()
    if (!rows) return
    Integer brightness = scalePercentToLifx(level)
    rows.each { item ->
        Map row = item as Map
        Integer safeKelvin = clampKelvin(row, kelvin ?: 3500)
        List<Integer> payload
        if (rowIsColourCapable(row)) {
            payload = [0] + u16le(scalePercentToLifx(hue100)) + u16le(scalePercentToLifx(sat100)) + u16le(brightness) + u16le(safeKelvin) + u32le(durationMs ?: 0)
        } else {
            // Non-colour-capable lights receive only the requested brightness level.
            payload = [0] + u16le(0) + u16le(0) + u16le(brightness) + u16le(safeKelvin) + u32le(durationMs ?: 0)
        }
        sendFastSetToRow(row, 102, payload)
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
            } catch (Throwable ignored) { }
        }
    }
}

void sendBulkSetColorTemperature(Integer kelvin, Integer level = 75, Integer durationMs = 0) {
    List<Map> rows = bulkControlRows()
    if (!rows) return
    Integer lvl = clampInt(level ?: 75, 0, 100)
    Integer brightness = scalePercentToLifx(lvl)
    rows.each { item ->
        Map row = item as Map
        Integer safeKelvin = clampKelvin(row, kelvin ?: 3000)
        List<Integer> payload = [0] + u16le(0) + u16le(0) + u16le(brightness) + u16le(safeKelvin) + u32le(durationMs ?: 0)
        sendFastSetToRow(row, 102, payload)
    }
    rows.each { row ->
        def child = getChildDevice(childDniForBulkRow(row as Map))
        if (child) {
            try {
                child.sendEvent(name: "colorTemperature", value: clampKelvin(row as Map, kelvin ?: 3000))
                child.sendEvent(name: "level", value: lvl)
                child.sendEvent(name: "colorMode", value: "CT")
                child.sendEvent(name: "switch", value: lvl > 0 ? "on" : "off")
            } catch (Throwable ignored) { }
        }
    }
}

void sendFastSetToRow(Map row, Integer messageType, List<Integer> payload = []) {
    sendFastUdpToIp(row, messageType, payload ?: [])
}

void sendFastUdpToIp(Map row, Integer messageType, List<Integer> payload = []) {
    if (!row?.ip) return
    Integer port = safeInt(row.port) ?: 56700
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
    Map row = rowForChild(device)
    sendSetPower(row, 65535, 0)
    try { device.sendEvent(name: "switch", value: "on") } catch (Throwable ignored) { }
}

void childOff(device) {
    Map row = rowForChild(device)
    sendSetPower(row, 0, 0)
    try { device.sendEvent(name: "switch", value: "off") } catch (Throwable ignored) { }
}

void childSetLevel(device, value, duration = 0) {
    Integer level = clampInt(safeInt(value) ?: 0, 0, 100)
    Map row = rowForChild(device)
    Integer kelvin = clampKelvin(row, safeInt(device.currentValue('colorTemperature')) ?: safeInt(row.minKelvin) ?: 3500)
    Integer hue = scalePercentToLifx(device.currentValue('hue') ?: 0)
    Integer sat = scalePercentToLifx(device.currentValue('saturation') ?: 0)
    Integer bri = scalePercentToLifx(level)
    sendSetColor(row, hue, sat, bri, kelvin, durationMs(duration))
    try {
        device.sendEvent(name: "level", value: level)
        device.sendEvent(name: "switch", value: level > 0 ? "on" : "off")
    } catch (Throwable ignored) { }
    // v4.7.3: no blocking inline refresh after SET commands; original app updates events optimistically.
}

void childSetColorTemperature(device, temp, level = null, duration = 0) {
    Map row = rowForChild(device)
    Integer kelvin = clampKelvin(row, safeInt(temp) ?: 3500)
    Integer lvl = clampInt(safeInt(level) ?: safeInt(device.currentValue('level')) ?: 100, 0, 100)
    sendSetColor(row, 0, 0, scalePercentToLifx(lvl), kelvin, durationMs(duration))
    try {
        device.sendEvent(name: "colorTemperature", value: kelvin)
        device.sendEvent(name: "level", value: lvl)
        device.sendEvent(name: "colorMode", value: "CT")
        device.sendEvent(name: "switch", value: lvl > 0 ? "on" : "off")
    } catch (Throwable ignored) { }
    // v4.7.3: no blocking inline refresh after SET commands; original app updates events optimistically.
}

void childSetColor(device, Map colorMap, duration = 0) {
    Map row = rowForChild(device)
    Integer hue100 = clampInt(safeInt(colorMap?.hue) ?: 0, 0, 100)
    Integer sat100 = clampInt(safeInt(colorMap?.saturation) ?: 100, 0, 100)
    Integer lvl = clampInt(safeInt(colorMap?.level ?: colorMap?.brightness ?: 100) ?: 100, 0, 100)
    Integer kelvin = clampKelvin(row, safeInt(colorMap?.colorTemperature ?: colorMap?.kelvin) ?: safeInt(device.currentValue('colorTemperature')) ?: 3500)
    sendSetColor(row, scalePercentToLifx(hue100), scalePercentToLifx(sat100), scalePercentToLifx(lvl), kelvin, durationMs(duration))
    try {
        device.sendEvent(name: "hue", value: hue100)
        device.sendEvent(name: "saturation", value: sat100)
        device.sendEvent(name: "level", value: lvl)
        device.sendEvent(name: "colorTemperature", value: kelvin)
        device.sendEvent(name: "colorMode", value: "RGB")
        device.sendEvent(name: "switch", value: lvl > 0 ? "on" : "off")
    } catch (Throwable ignored) { }
    // v4.7.3: no blocking inline refresh after SET commands; original app updates events optimistically.
}

void childSetHue(device, value) {
    childSetColor(device, [hue: value, saturation: device.currentValue('saturation') ?: 100, level: device.currentValue('level') ?: 100], 0)
}

void childSetSaturation(device, value) {
    childSetColor(device, [hue: device.currentValue('hue') ?: 0, saturation: value, level: device.currentValue('level') ?: 100], 0)
}

void childSetInfraredLevel(device, value) {
    Map row = rowForChild(device)
    Integer level = clampInt(safeInt(value) ?: 0, 0, 100)
    sendLifxToRow(row, 122, u16le(scalePercentToLifx(level)), true, false, "parseChildLifx", 1, 0) // LIGHT.SET_INFRARED, ack requested
    try { device.sendEvent(name: "IRLevel", value: level) } catch (Throwable ignored) { }
    try { device.sendEvent(name: "infraredLevel", value: level) } catch (Throwable ignored) { }
    // v4.7.3: no blocking inline refresh after SET commands; original app updates events optimistically.
}

void childRefresh(device) {
    Map row = rowForChild(device)
    sendLifxToRow(row, 101, [], false, true, "parseChildLifx", 1, 0)   // LIGHT.GET, response requested
    pauseExecution(80)
    sendLifxToRow(row, 116, [], false, true, "parseChildLifx", 1, 0)   // LIGHT.GET_POWER, response requested
    if (truthy(row.hasIr)) {
        pauseExecution(80)
        sendLifxToRow(row, 120, [], false, true, "parseChildLifx", 1, 0) // LIGHT.GET_INFRARED, response requested
    }
}

void refreshChildSoon(device, Integer delayMs = 350) {
    // Hubitat cannot pass a device wrapper reliably through runInMillis, so do a bounded inline refresh delay.
    try {
        pauseExecution(clampInt(delayMs ?: 350, 100, 1200))
        childRefresh(device)
    } catch (Throwable ignored) { }
}

void sendSetPower(Map row, Integer power, Integer durationMs = 0) {
    // v4.7.1: same fast packet path as the aggregate group. This matters when a
    // Hubitat group calls every child on/off individually; each child now emits
    // one lightweight UDP packet instead of a multi-candidate ACK command.
    sendFastUdpToIp(row, 117, u16le(power ?: 0) + u32le(durationMs ?: 0)) // LIGHT.SET_POWER
}

void sendSetColor(Map row, Integer hue, Integer saturation, Integer brightness, Integer kelvin, Integer durationMs = 0) {
    // lifxlan reference payload: reserved uint8 + HSBK uint16[4] + duration uint32
    List<Integer> payload = [0] + u16le(hue ?: 0) + u16le(saturation ?: 0) + u16le(brightness ?: 0) + u16le(clampKelvin(row, kelvin ?: 3500)) + u32le(durationMs ?: 0)
    sendLifxToRow(row, 102, payload, true, false, "parseChildLifx", 1, 0) // LIGHT.SET_COLOR, ack requested
}

void sendLifxToRow(Map row, Integer messageType, List<Integer> payload = [], Boolean ackRequested = false, Boolean responseRequired = false, String callback = "parseChildLifx", Integer repeats = 1, Integer repeatPauseMs = 0) {
    if (!row?.ip) return
    List<String> targets = controlUidCandidatesForRow(row)
    if (!targets) return
    Integer port = safeInt(row.port) ?: 56700
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
    if (!row) return

    def child = getChildDevice(childDniForRow(row))
    if (!child) return

    try {
        if ((parsed.type as Integer) == 45) {
            return // LIFX acknowledgement for SET workflows
        } else if ((parsed.type as Integer) == 107) {
            Map light = parseLightState(parsed.payloadHex)
            if (light.label) child.sendEvent(name: "label", value: light.label)
            child.sendEvent(name: "switch", value: ((light.power ?: 0) as Integer) > 0 ? "on" : "off")
            child.sendEvent(name: "level", value: scaleDown100(light.brightness ?: 0))
            child.sendEvent(name: "hue", value: scaleDown100(light.hue ?: 0))
            child.sendEvent(name: "saturation", value: scaleDown100(light.saturation ?: 0))
            child.sendEvent(name: "colorTemperature", value: light.kelvin ?: 3500)
        } else if ((parsed.type as Integer) == 118) {
            Integer power = leU16(parsed.payloadHex, 0)
            child.sendEvent(name: "switch", value: power > 0 ? "on" : "off")
        } else if ((parsed.type as Integer) == 121) {
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
    Integer v = clampInt(safeInt(value) ?: 0, 0, 100)
    return Math.round((v * 65535.0d) / 100.0d) as Integer
}

Integer scaleDown100(value) {
    try { return Math.round(((value as Integer) * 100.0d) / 65535.0d) as Integer } catch (Throwable ignored) { return 0 }
}

Integer durationMs(value) {
    try { return Math.max(0, Math.round((value as BigDecimal) * 1000.0d) as Integer) } catch (Throwable ignored) { return 0 }
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
    String colour = ((atomicState.status ?: "idle") == "complete") ? "#008000" : (((atomicState.status ?: "idle") in ["cloud", "broadcast", "sweep"]) ? "#cc0000" : "#777777")
    return "<div style='font-weight:bold;color:${colour}'>${html(atomicState.phase ?: 'Idle')}</div>" +
        "<b>Status:</b> ${html(atomicState.status ?: 'idle')}<br/>" +
        "<b>Cloud status:</b> ${html(atomicState.cloudStatus ?: 'not tested')}<br/>" +
        "<b>Saved curated rows:</b> ${stats.expected ?: 0}<br/>" +
        "<b>Device rows with IP:</b> ${stats.matched ?: 0} / ${stats.expected ?: 0}<br/>" +
        "<b>Device rows missing IP:</b> ${stats.missing ?: 0}<br/>" +
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

    List rows = curated.values().collect { it as Map }.sort { a, b -> compareUid(a.id ?: a.uid, b.id ?: b.uid) }

    StringBuilder b = new StringBuilder()
    b << tableOpenHtml()
    b << "<tr>"
    ["UID", "Label", "IP address", "Last seen", "Group", "Product", "Capabilities", "Driver mode", "Cloud connected", "Status"].eachWithIndex { h, idx ->
        b << headerCell(h, idx, h == "Cloud connected" ? "connected" : null)
    }
    b << "</tr>"
    rows.each { r ->
        b << "<tr>"
        b << cell(r.id ?: r.uid, 0)
        b << cell(r.label, 1)
        b << cell(r.ip, 2)
        b << cell(curatedLastSeen(r), 3)
        b << cell(r.groupName, 4)
        String productDisplay = r.productName ?: r.productIdentifier ?: (r.lanProduct ? "LAN product ${r.lanProduct}" : "")
        b << cell(productDisplay, 5)
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
    ["UID", "Label", "IP address", "Last seen", "Group", "Product", "Capabilities", "Driver mode", "Connected", "Status"].eachWithIndex { h, idx ->
        b << headerCell(h, idx, h == "Connected" ? "connected" : null)
    }
    b << "</tr>"
    Map curated = atomicState.curatedRows ?: [:]
    cloud.values().sort { a, c -> compareUid(a.id, c.id) }.each { light ->
        Map d = curated[normaliseCloudId(light.id)] as Map ?: [:]
        Map l = light as Map
        b << "<tr>"
        b << cell(light.id, 0)
        b << cell(light.label, 1)
        b << cell(d.ip ?: "", 2)
        b << cell(formatDateTime(light.lastSeenCloud), 3)
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
    ["UID", "Label", "IP address", "Last seen", "Expected from cloud"].eachWithIndex { h, idx ->
        b << headerCell(h, idx, h == "Expected from cloud" ? "status" : null)
    }
    b << "</tr>"
    records.values().sort { a, c -> compareUid(a.mac, c.mac) }.each { dev ->
        b << "<tr>"
        b << cell(dev.mac, 0)
        b << cell(dev.cloudLabel ?: dev.label ?: "", 1)
        b << cell(dev.ip, 2)
        b << cell(formatDateTime(dev.lastSeen), 3)
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
    } catch (Throwable ignored) {
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
    "<table style='width:auto;border-collapse:collapse;font-size:12px;table-layout:fixed;user-select:text'>"
}

String fixedColumnStyle(Integer index) {
    // Right-sized to the actual LIFX curated data: short UIDs/IPs, moderate labels/groups,
    // product names, longer driver/status text.
    if (index == 0) return "width:105px;min-width:105px;max-width:105px;"      // UID
    if (index == 1) return "width:165px;min-width:165px;max-width:165px;"      // Label
    if (index == 2) return "width:90px;min-width:90px;max-width:90px;"         // IP address
    if (index == 3) return "width:90px;min-width:90px;max-width:90px;"         // Last seen
    if (index == 4) return "width:135px;min-width:135px;max-width:135px;"      // Group
    if (index == 5) return "width:210px;min-width:210px;max-width:210px;"      // Product
    if (index == 6) return "width:175px;min-width:175px;max-width:175px;"      // Capabilities
    if (index == 7) return "width:215px;min-width:215px;max-width:215px;"      // Driver mode
    if (index == 9) return "width:360px;min-width:360px;max-width:360px;"      // Status
    return ""
}

String fixedColumnStyleByKey(String key) {
    if (key == "connected") return "width:75px;min-width:75px;max-width:75px;"
    if (key == "status") return "width:360px;min-width:360px;max-width:360px;"
    return ""
}

String headerCell(value, Integer index = null, String key = null) {
    String extra = key ? fixedColumnStyleByKey(key) : (index == null ? "" : fixedColumnStyle(index))
    "<th style='text-align:left;border:1px solid #ccc;padding:3px;vertical-align:top;user-select:text;${extra}'>${html(value == null ? '' : value.toString())}</th>"
}

String cell(value, Integer index = null, String key = null) {
    String extra = key ? fixedColumnStyleByKey(key) : (index == null ? "" : fixedColumnStyle(index))
    "<td style='border:1px solid #ccc;padding:3px;vertical-align:top;overflow:visible;text-overflow:clip;white-space:normal;user-select:text;${extra}'>${html(value == null ? '' : value.toString())}</td>"
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
    try { return new Date(ms).format("dd-MMM HH:mm", location.timeZone) } catch (Throwable ignored) { return "" }
}

Long epochMillis(value) {
    if (!value) return 0L
    try {
        if (value instanceof Number) return (value as Long)
    } catch (Throwable ignored) { }

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

    return 0L
}

Integer compareIp(a, b) {
    Long av = ipSortValue(a)
    Long bv = ipSortValue(b)
    return av <=> bv
}

Long ipSortValue(value) {
    if (!value) return Long.MAX_VALUE
    try {
        List parts = value.toString().tokenize('.')
        if (parts.size() != 4) return Long.MAX_VALUE
        Long result = 0L
        parts.each { part ->
            Integer n = part as Integer
            if (n < 0 || n > 255) throw new RuntimeException("Invalid IP octet")
            result = (result << 8) + n
        }
        return result
    } catch (Throwable ignored) {
        return Long.MAX_VALUE
    }
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
    try { return value == null ? null : (value as Integer) } catch (Throwable ignored) { return null }
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
            destinationAddress: "${ip}:56700",
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
    Integer seq = ((atomicState.sequence ?: 1) as Integer) & 0xff
    atomicState.sequence = ((seq + 1) & 0xff)
    return seq
}

// ---------------- Network/helpers ----------------

String hubSubnet() {
    try {
        String hubIp = location.hubs[0]?.localIP ?: location.hub?.localIP
        def m = hubIp =~ /^(\d{1,3}\.\d{1,3}\.\d{1,3})\.\d{1,3}$/
        if (m) return "${m[0][1]}."
    } catch (Throwable ignored) { }
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

/*
 * LIFX Local Colour
 * Namespace: Hubitat Integrations
 * Version: 1.5.8
 * Parent app: LIFX Light Manager 1.6.9+
 * Full version history: see git log and the README.
 * Google Home compatibility notes:
 * - Exposes only standard Hubitat light capabilities for this device type.
 * - Custom metadata is kept as attributes only and should not map to Google traits.
 */
metadata {
    definition(name: "LIFX Local Colour", namespace: "Hubitat Integrations", author: "Gordon Thelander") {
        capability "Actuator"
        capability "Switch"
        capability "Light"
        capability "SwitchLevel"
        capability "ColorControl"
        capability "ColorTemperature"
        capability "ColorMode"
        capability "Refresh"
        capability "Polling"
        capability "Initialize"
        capability "SignalStrength"
        attribute "label", "string"
        attribute "uid", "string"
        attribute "lanIp", "string"
        attribute "colorName", "string"
        attribute "hostFirmware", "string"
        attribute "hostFirmwareBuild", "string"
        // Rule Machine's Custom Action has no ENUM/dropdown support for custom command
        // parameters (confirmed live) - only string/number/decimal are offered, so these are
        // typed by hand. Valid names: Soft White, White, Daylight, Warm White, Red, Orange,
        // Yellow, Green, Blue, Purple, Pink (case-insensitive; unrecognised falls back to White).
        command "breathe", [[name: "Base colour*", type: "STRING"], [name: "Target colour*", type: "STRING"], [name: "Speed (seconds, optional, default 3.5)", type: "STRING"], [name: "Base brightness % (optional, default 100)", type: "STRING"], [name: "Target brightness % (optional, default 100)", type: "STRING"]]
        command "pulse", [[name: "Base colour*", type: "STRING"], [name: "Target colour*", type: "STRING"], [name: "Speed (seconds, optional, default 0.8)", type: "STRING"], [name: "Base brightness % (optional, default 100)", type: "STRING"], [name: "Target brightness % (optional, default 100)", type: "STRING"]]
        command "applyDefault"
    }
    preferences {
        input "defaultColorTemperature", "number", title: "Default colour temperature (used by Apply Default, and when Off cancels an active Breathe/Pulse effect)", defaultValue: 3000, range: "1500..9000", required: true
        input "defaultLevel", "number", title: "Default level (used by Apply Default, and when Off cancels an active Breathe/Pulse effect)", defaultValue: 75, range: "0..100", required: true
        input "debugLogging", "bool", title: "Enable debug logging", defaultValue: false, required: false
    }
}

def installed() { initialize() }
def updated() { initialize() }
def initialize() {
    try { sendEvent(name: "uid", value: getDataValue('uid') ?: deviceNetworkId) } catch (Throwable t) { log.debug "sendEvent(uid) failed: ${t.message}" }
    try { sendEvent(name: "lanIp", value: getDataValue('ip') ?: '') } catch (Throwable t) { log.debug "sendEvent(lanIp) failed: ${t.message}" }
    try { sendEvent(name: "hostFirmware", value: getDataValue('hostFirmware') ?: '') } catch (Throwable t) { log.debug "sendEvent(hostFirmware) failed: ${t.message}" }
    try { sendEvent(name: "hostFirmwareBuild", value: getDataValue('hostFirmwareBuild') ?: '') } catch (Throwable t) { log.debug "sendEvent(hostFirmwareBuild) failed: ${t.message}" }
    try { if (getDataValue('rssi')) sendEvent(name: "rssi", value: getDataValue('rssi'), unit: "dBm") } catch (Throwable t) { log.debug "sendEvent(rssi) failed: ${t.message}" }
    initialiseGoogleSafeState()
    try { refresh() } catch (Throwable t) { log.debug "refresh(...) failed: ${t.message}" }
}

private void initialiseGoogleSafeState() {
    try { if (device.currentValue("switch") == null) sendEvent(name: "switch", value: "off", displayed: false) } catch (Throwable t) { log.debug "sendEvent(switch) failed: ${t.message}" }
    try { if (device.currentValue("level") == null) sendEvent(name: "level", value: 100, unit: "%", displayed: false) } catch (Throwable t) { log.debug "sendEvent(level) failed: ${t.message}" }
    try { if (device.currentValue("colorTemperature") == null) sendEvent(name: "colorTemperature", value: 3500, unit: "K", displayed: false) } catch (Throwable t) { log.debug "sendEvent(colorTemperature) failed: ${t.message}" }
    try { if (device.currentValue("hue") == null) sendEvent(name: "hue", value: 0, displayed: false) } catch (Throwable t) { log.debug "sendEvent(hue) failed: ${t.message}" }
    try { if (device.currentValue("saturation") == null) sendEvent(name: "saturation", value: 0, displayed: false) } catch (Throwable t) { log.debug "sendEvent(saturation) failed: ${t.message}" }
    try { if (device.currentValue("colorMode") == null) sendEvent(name: "colorMode", value: "CT", displayed: false) } catch (Throwable t) { log.debug "sendEvent(colorMode) failed: ${t.message}" }
    try { if (device.currentValue("colorName") == null) sendEvent(name: "colorName", value: "Soft White", displayed: false) } catch (Throwable t) { log.debug "sendEvent(colorName) failed: ${t.message}" }
}
def poll() { refresh() }
def refresh() { if (!requireParent()) return; parent.childRefresh(device) }
def on() { fastPower("on") }
def off() { fastPower("off") }
def applyDefault() {
    setLevel(defaultLevel == null ? 75 : defaultLevel, 0)
    setColorTemperature(defaultColorTemperature ?: 3000)
}

private Boolean requireParent() {
    if (parent) return true
    log.warn "No parent app; ${device.displayName} is orphaned and cannot be controlled"
    return false
}

private String lifxIp() {
    try { return (device.getDataValue('ip') ?: '').toString().trim() } catch (Throwable t) { log.debug "lifxIp() failed: ${t.message}"; return '' }
}

private Integer lifxPort() {
    try { return ((device.getDataValue('port') ?: '56700') as String).toInteger() } catch (Throwable t) { log.debug "lifxPort() failed: ${t.message}"; return 56700 }
}

private void fastPower(String value) {
    String ip = lifxIp()
    if (!ip) {
        try { parent.childOnOffFallback(device, value) } catch (Throwable t) { log.warn "No LIFX IP stored for ${device.displayName}; cannot send fast ${value}: ${t.message}" }
        return
    }
    Integer power = (value == 'on') ? 65535 : 0

    if (value == 'off' && getDataValue('effectActive') == 'true') {
        // A running Breathe/Pulse waveform survives a plain LIFX power cycle - only a real
        // SET_COLOR command cancels it. Reset to this device's own configured default colour/level
        // before the power-off packet below, so the bulb comes back to that default next time it's
        // turned on instead of silently resuming the effect.
        Integer resetLevelPct = (defaultLevel == null ? 75 : defaultLevel) as Integer
        Integer resetKelvin = (defaultColorTemperature ?: 3000) as Integer
        Integer resetLevelLifx = Math.round((resetLevelPct * 65535.0d) / 100.0d) as Integer
        String colorPacket = fastSetColorPacketHex(0, 0, resetLevelLifx, resetKelvin, 0)
        sendHubCommand(new hubitat.device.HubAction(
            colorPacket,
            hubitat.device.Protocol.LAN,
            [
                type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
                destinationAddress: "${ip}:${lifxPort()}",
                encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
                ignoreWarning: true,
                parseWarning: false,
                ignoreResponse: true,
                timeout: 1
            ]
        ))
        try {
            sendEvent(name: "hue", value: 0, displayed: false)
            sendEvent(name: "saturation", value: 0, displayed: false)
            sendEvent(name: "level", value: resetLevelPct, displayed: false)
            sendEvent(name: "colorTemperature", value: resetKelvin, displayed: false)
            sendEvent(name: "colorMode", value: "CT", displayed: false)
        } catch (Throwable t) { log.debug "fastPower() effect-reset sendEvent failed: ${t.message}" }
        try { updateDataValue("effectActive", "false") } catch (Throwable t) { log.debug "fastPower() updateDataValue failed: ${t.message}" }
    }

    // Builds the zero-target/tagged SET_POWER packet inside the child driver itself, removing
    // one parent-app round-trip per child command when Hubitat Rule Machine invokes a group of
    // individual child devices sequentially.
    String packet = fastSetPowerPacketHex(power, 0)

    sendHubCommand(new hubitat.device.HubAction(
        packet,
        hubitat.device.Protocol.LAN,
        [
            type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
            destinationAddress: "${ip}:${lifxPort()}",
            encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
            ignoreWarning: true,
            parseWarning: false,
            ignoreResponse: true,
            timeout: 1
        ]
    ))
    try { sendEvent(name: "switch", value: value, displayed: false) } catch (Throwable t) { log.debug "sendEvent(switch) failed: ${t.message}" }
    // SetLightPower (117) does not change brightness on the bulb, so `level` is left untouched here.
    // Fast path bypasses the parent app entirely for speed, so it must separately trigger Master
    // Switch reconciliation - childOn()/childOff() in the app do this, but this path never calls them.
    try { parent.requestMasterStateReconciliation() } catch (Throwable t) { log.debug "requestMasterStateReconciliation() failed: ${t.message}" }
}

private String fastSetPowerPacketHex(Integer power, Integer durationMs = 0) {
    List<Integer> payload = u16le(power ?: 0) + u32le(durationMs ?: 0)
    return buildZeroTargetTaggedPacket(117, payload)
}

private String fastSetColorPacketHex(Integer hue, Integer saturation, Integer brightness, Integer kelvin, Integer durationMs = 0) {
    List<Integer> payload = [0] + u16le(hue ?: 0) + u16le(saturation ?: 0) + u16le(brightness ?: 0) + u16le(kelvin ?: 0) + u32le(durationMs ?: 0)
    return buildZeroTargetTaggedPacket(102, payload)
}

// Known accepted limitation: tagged=1 with a zero target is protocol-
// non-conformant (tagged semantically means broadcast/all-devices) - this relies on IP-level UDP
// unicast addressing alone to reach the right device. Works reliably in extensive live testing
// with zero observed cross-device bleed. Deliberately not fixed - a real fix exists (the device's
// own controlUid data value) but this is the most heavily live-tested path in the app for a
// currently-theoretical risk.
private String buildZeroTargetTaggedPacket(Integer messageType, List<Integer> payload = []) {
    Integer size = 36 + (payload?.size() ?: 0)
    List<Integer> bytes = []
    bytes += u16le(size)
    Integer flags = 0x0400 | 0x1000 | 0x2000   // protocol 1024, addressable, tagged
    bytes += u16le(flags)
    bytes += u32le(0x47544842)                 // source GT HB
    bytes += [0,0,0,0,0,0,0,0]                 // zero target for tagged IP-directed send
    bytes += [0,0,0,0,0,0]
    bytes += [0]                               // no ACK, no response required
    bytes += [nextSequence()]
    bytes += [0,0,0,0,0,0,0,0]
    bytes += u16le(messageType ?: 117)
    bytes += [0,0]
    if (payload) bytes += payload
    return bytesToHex(bytes)
}

private Integer nextSequence() {
    try {
        Integer seq = ((state.fastSequence ?: 0) as Integer)
        seq = (seq + 1) & 0xFF
        state.fastSequence = seq
        return seq
    } catch (Throwable t) {
        log.debug "nextSequence() failed: ${t.message}"
        return 1
    }
}

private List<Integer> u16le(Integer value) {
    Integer v = value ?: 0
    return [v & 0xFF, (v >> 8) & 0xFF]
}

private List<Integer> u32le(Integer value) {
    Long v = (value ?: 0) as Long
    return [(v & 0xFF) as Integer, ((v >> 8) & 0xFF) as Integer, ((v >> 16) & 0xFF) as Integer, ((v >> 24) & 0xFF) as Integer]
}

private String bytesToHex(List<Integer> bytes) {
    return (bytes ?: []).collect { String.format('%02X', (it ?: 0) & 0xFF) }.join('')
}

def setLevel(value, duration = 0) { if (!requireParent()) return; parent.childSetLevel(device, value, duration) }
def setColorTemperature(value, level = null, duration = 0) { if (!requireParent()) return; parent.childSetColorTemperature(device, value, level, duration) }
def setColor(Map value) { if (!requireParent()) return; parent.childSetColor(device, value ?: [:], value?.duration ?: 0) }
def setHue(value) { if (!requireParent()) return; parent.childSetHue(device, value) }
def setSaturation(value) { if (!requireParent()) return; parent.childSetSaturation(device, value) }
def breathe(baseColour, targetColour, speedSeconds = null, baseBrightness = null, targetBrightness = null) { if (!requireParent()) return; parent.childBreathe(device, baseColour, targetColour, speedSeconds, baseBrightness, targetBrightness) }
def pulse(baseColour, targetColour, speedSeconds = null, baseBrightness = null, targetBrightness = null) { if (!requireParent()) return; parent.childPulse(device, baseColour, targetColour, speedSeconds, baseBrightness, targetBrightness) }

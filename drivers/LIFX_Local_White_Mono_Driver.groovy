/*
 * LIFX Local White Mono (Dev)
 * Namespace: Hubitat Integrations
 * Version: 1.5.2
 * Parent app: LIFX Light Manager (Dev) 1.5.2+
 * Google Home compatibility notes:
 * - Exposes only standard Hubitat light capabilities for this device type.
 * - Custom metadata is kept as attributes only and should not map to Google traits.
 * LAN command handling is delegated to the parent app, which follows the lifxlan packet/workflow model.
 */
metadata {
    definition(name: "LIFX Local White Mono (Dev)", namespace: "Hubitat Integrations", author: "Gordon Thelander") {
        capability "Actuator"
        capability "Switch"
        capability "Light"
        capability "SwitchLevel"
        capability "Refresh"
        capability "Polling"
        capability "Initialize"
        capability "SignalStrength"
        attribute "label", "string"
        attribute "uid", "string"
        attribute "lanIp", "string"
        attribute "hostFirmware", "string"
        attribute "hostFirmwareBuild", "string"
    }
    preferences {
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
}
def poll() { refresh() }
def refresh() { if (!requireParent()) return; parent.childRefresh(device) }
def on() { fastPower("on") }
def off() { fastPower("off") }

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

    // v1.1.6: build the zero-target/tagged SET_POWER packet inside the child driver; sequence uses driver state, not java.lang.System.
    // This removes one parent-app round-trip per child command when Hubitat Rule Machine
    // invokes a group of individual child devices sequentially.
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
}

private String fastSetPowerPacketHex(Integer power, Integer durationMs = 0) {
    List<Integer> payload = u16le(power ?: 0) + u32le(durationMs ?: 0)
    return buildZeroTargetTaggedPacket(117, payload)
}

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

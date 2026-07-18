/*
 * LIFX Master Switch
 * Namespace: Hubitat Integrations
 * Version: 1.4.1
 * Parent app: LIFX Light Manager 1.4.1+
 *
 * Purpose:
 * - Aggregate master switch used for fast whole-fleet LAN control.
 * - Commands delegate to the parent app, which sends rapid whole-group UDP bursts.
 * - Google-friendly aggregate device: exposes Switch + SwitchLevel only.
 * - Full colour group commands remain implemented for Hubitat/rule use, but ColorControl is not
 *   advertised as standard capabilities to Google Home to avoid colour/CT ambiguity.
 */
metadata {
    definition(name: "LIFX Master Switch", namespace: "Hubitat Integrations", author: "Gordon Thelander") {
        capability "Actuator"
        capability "Switch"
        capability "SwitchLevel"
        capability "Refresh"
        capability "Polling"
        capability "Initialize"
        attribute "level", "number"
        attribute "memberCount", "number"
        attribute "onMemberCount", "number"
        command "applyDefaultColorTemperature"
        command "setMasterColorTemperature", [[name: "Colour temperature", type: "NUMBER"], [name: "Level", type: "NUMBER"]]
        command "setColor", [[name: "Color Map*", type: "COLOR_MAP", description: "Color map settings [hue*:(0 to 100), saturation*:(0 to 100), level:(0 to 100)]"]]
    }
    preferences {
        input "defaultColorTemperature", "number", title: "Default colour temperature for Master Switch", defaultValue: 3000, range: "1500..9000", required: true
        input "defaultCtLevel", "number", title: "Default level for Master Switch colour temperature", defaultValue: 75, range: "0..100", required: true
        input "debugLogging", "bool", title: "Enable debug logging", defaultValue: false, required: false
    }
}

def installed() { initialize() }
def updated() { initialize() }
def initialize() {
    try { if (device.currentValue("level") == null) sendEvent(name: "level", value: (defaultCtLevel ?: 75) as Integer, unit: "%") } catch (Throwable t) { log.debug "sendEvent(level) failed: ${t.message}" }
    try { if (device.currentValue("switch") == null) sendEvent(name: "switch", value: "off") } catch (Throwable t) { log.debug "sendEvent(switch) failed: ${t.message}" }
    try { refresh() } catch (Throwable t) { log.debug "refresh(...) failed: ${t.message}" }
}
def poll() { refresh() }
def refresh() { if (!requireParent()) return; parent.groupChildRefresh(device) }
def on() { if (!requireParent()) return; parent.groupChildOn(device) }
def off() { if (!requireParent()) return; parent.groupChildOff(device) }
def setLevel(value, duration = 0) {
    if (!requireParent()) return
    parent.groupChildSetLevel(device, value, duration)
}

private Boolean requireParent() {
    if (parent) return true
    log.warn "No parent app; ${device.displayName} is orphaned and cannot be controlled"
    return false
}

// Declared as a standalone command (not via the ColorControl capability) so it stays hidden
// from Google Home, which only sees capability-driven commands. Use individual colour child
// devices for Google colour control.
def setColor(Map colorMap) {
    if (!requireParent()) return
    Map cmap = colorMap ?: [:]
    parent.groupChildSetColor(device, cmap, cmap.duration ?: 0)
}
def setColorTemperature(temperature, level = null, duration = 0) {
    if (!requireParent()) return
    parent.groupChildSetColorTemperature(device, temperature ?: (defaultColorTemperature ?: 3000), level ?: (defaultCtLevel ?: 75), duration)
}

def setMasterColorTemperature(temperature, level = null) {
    setColorTemperature(temperature ?: (defaultColorTemperature ?: 3000), level ?: (defaultCtLevel ?: 75), 0)
}

def applyDefaultColorTemperature() {
    setColorTemperature(defaultColorTemperature ?: 3000, defaultCtLevel ?: 75, 0)
}

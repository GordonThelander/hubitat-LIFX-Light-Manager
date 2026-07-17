/*
 * LIFX Master Switch
 * Namespace: Hubitat Integrations
 * Version: 1.3
 * Parent app: LIFX Light Manager 1.3+
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
        command "setMasterColor", [[name: "Colour map", type: "STRING", description: "Hubitat private use: hue/saturation/level map or JSON-like text"]]
        command "setMasterHue", [[name: "Hue", type: "NUMBER"]]
        command "setMasterSaturation", [[name: "Saturation", type: "NUMBER"]]
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
    try { if (device.currentValue("level") == null) sendEvent(name: "level", value: (defaultCtLevel ?: 75) as Integer) } catch (Throwable ignored) { }
    try { if (device.currentValue("switch") == null) sendEvent(name: "switch", value: "off") } catch (Throwable ignored) { }
    try { refresh() } catch (Throwable ignored) { }
}
def poll() { refresh() }
def refresh() { parent.groupChildRefresh(device) }
def on() { parent.groupChildOn(device) }
def off() { parent.groupChildOff(device) }
def setLevel(value, duration = 0) {
    parent.groupChildSetLevel(device, value, duration)
}

// Private Hubitat colour path. Not exposed to Google Home because this driver intentionally
// does not declare ColorControl. Use individual colour child devices for Google colour control.
def setColor(Map colorMap) {
    Map cmap = colorMap ?: [:]
    parent.groupChildSetColor(device, cmap, cmap.duration ?: 0)
}
def setHue(value) {
    setColor([hue: value, saturation: device.currentValue('saturation') ?: 100, level: device.currentValue('level') ?: (defaultCtLevel ?: 75)])
}
def setSaturation(value) {
    setColor([hue: device.currentValue('hue') ?: 0, saturation: value, level: device.currentValue('level') ?: (defaultCtLevel ?: 75)])
}
def setColorTemperature(temperature, level = null, duration = 0) {
    parent.groupChildSetColorTemperature(device, temperature ?: (defaultColorTemperature ?: 3000), level ?: (defaultCtLevel ?: 75), duration)
}

def setMasterColor(value) {
    Map cmap = [:]
    if (value instanceof Map) {
        cmap = value as Map
    } else {
        String raw = value?.toString() ?: ""
        raw.split(",").each { part ->
            def bits = part.split(":")
            if (bits.size() == 2) cmap[bits[0].trim()] = bits[1].trim()
        }
    }
    setColor(cmap)
}

def setMasterHue(value) { setHue(value) }
def setMasterSaturation(value) { setSaturation(value) }

def setMasterColorTemperature(temperature, level = null) {
    setColorTemperature(temperature ?: (defaultColorTemperature ?: 3000), level ?: (defaultCtLevel ?: 75), 0)
}

def applyDefaultColorTemperature() {
    setColorTemperature(defaultColorTemperature ?: 3000, defaultCtLevel ?: 75, 0)
}

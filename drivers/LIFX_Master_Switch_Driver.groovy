/*
 * LIFX Master Switch
 * Namespace: Hubitat Integrations
 * Version: B1.0
 * Parent app: LIFX Light Manager B1.0+
 *
 * Purpose:
 * - Aggregate master switch used for fast whole-fleet LAN control.
 * - Commands delegate to the parent app, which sends rapid whole-group UDP bursts.
 * - Colour commands are applied to colour-capable lights; non-colour lights receive the requested level.
 */
metadata {
    definition(name: "LIFX Master Switch", namespace: "Hubitat Integrations", author: "Gordon Thelander") {
        capability "Actuator"
        capability "Switch"
        capability "ColorControl"
        capability "ColorTemperature"
        capability "Refresh"
        capability "Polling"
        capability "Initialize"
        attribute "level", "number"
        attribute "memberCount", "number"
        attribute "onMemberCount", "number"
        command "applyDefaultColorTemperature"
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
    sendEvent(name: "colorTemperature", value: (defaultColorTemperature ?: 3000) as Integer)
    sendEvent(name: "level", value: (defaultCtLevel ?: 75) as Integer)
    refresh()
}
def poll() { refresh() }
def refresh() { parent.groupChildRefresh(device) }
def on() { parent.groupChildOn(device) }
def off() { parent.groupChildOff(device) }
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

def applyDefaultColorTemperature() {
    setColorTemperature(defaultColorTemperature ?: 3000, defaultCtLevel ?: 75, 0)
}

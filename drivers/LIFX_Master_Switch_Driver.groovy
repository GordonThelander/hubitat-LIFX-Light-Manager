/*
 * LIFX Master Switch
 * Namespace: Hubitat Integrations
 * Version: 1.0.1
 * Parent app: LIFX Light Manager v4.7.5+
 *
 * Purpose:
 * - Aggregate master switch used for fast whole-fleet LAN control.
 * - Commands delegate to the parent app, which sends rapid whole-group UDP bursts.
 */
metadata {
    definition(name: "LIFX Master Switch", namespace: "Hubitat Integrations", author: "Gordon Thelander") {
        capability "Actuator"
        capability "Switch"
        capability "SwitchLevel"
        capability "Refresh"
        capability "Polling"
        capability "Initialize"
        attribute "memberCount", "number"
        attribute "onMemberCount", "number"
    }
    preferences {
        input "debugLogging", "bool", title: "Enable debug logging", defaultValue: false, required: false
    }
}

def installed() { initialize() }
def updated() { initialize() }
def initialize() { refresh() }
def poll() { refresh() }
def refresh() { parent.groupChildRefresh(device) }
def on() { parent.groupChildOn(device) }
def off() { parent.groupChildOff(device) }
def setLevel(value, duration = 0) { parent.groupChildSetLevel(device, value, duration) }

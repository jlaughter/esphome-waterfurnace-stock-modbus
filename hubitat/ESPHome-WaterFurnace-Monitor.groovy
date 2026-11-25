/**
 *  ESPHome WaterFurnace Monitor
 *
 *  Hubitat driver for jlaughter/esphome-waterfurnace-stock-modbus. Works with
 *  BOTH device builds in that repo:
 *
 *    - waterfurnace-monitor.yaml          (stock modbus_controller, any ABC firmware)
 *    - waterfurnace-monitor-upstream.yaml (espforge/esphome-waterfurnace component,
 *                                          ABC firmware with 0x41/0x42 support)
 *
 *  Each build publishes a different entity set. The driver maps whatever the
 *  device announces and, after each entity discovery, deletes any attribute the
 *  current build no longer provides (e.g. the stock build's Compressor Stage 1/2
 *  after flashing the upstream build), so nothing is left frozen at a stale value.
 *  Attribute names shared by both builds are identical, so rules keep working
 *  across a reflash in either direction.
 *
 *  Read-only: neither build exposes control entities. The only command is
 *  reboot(), via the YAML's `button: - platform: restart`.
 *
 *  Built on Jonathan Bradshaw's ESPHome Native API Library toolkit
 *  (namespace 'esphome', library 'espHomeApiHelper') -- same toolkit and
 *  entity-discovery pattern as ESPHome-PumpSaver.groovy.
 *
 *  MIT License
 */
metadata {
    definition(
        name: 'ESPHome WaterFurnace Monitor',
        namespace: 'esphome',
        author: 'jlaughter',
        singleThreaded: true,
        importUrl: 'https://raw.githubusercontent.com/jlaughter/esphome-waterfurnace-stock-modbus/main/hubitat/ESPHome-WaterFurnace-Monitor.groovy') {

        capability 'Sensor'
        capability 'Refresh'
        capability 'Initialize'

        command 'reboot'

        // --- Operating status (both builds) ---
        attribute 'Current Mode', 'string'
        attribute 'Blower Running', 'enum', ['true', 'false']
        attribute 'Blower ECM Speed', 'number'
        // stock build only
        attribute 'Compressor Stage 1', 'enum', ['true', 'false']
        attribute 'Compressor Stage 2', 'enum', ['true', 'false']
        attribute 'Aux Heat Stage 1', 'enum', ['true', 'false']
        attribute 'Aux Heat Stage 2', 'enum', ['true', 'false']
        // upstream build only
        attribute 'Compressor Running', 'enum', ['true', 'false']
        attribute 'Aux Heat Running', 'enum', ['true', 'false']
        attribute 'Aux Heat Stage', 'number'
        attribute 'Anti-Short-Cycle Remaining (s)', 'number'

        // --- Lockout / faults ---
        attribute 'System Locked Out', 'enum', ['true', 'false']
        attribute 'Last Fault Code', 'number'
        attribute 'Last Fault Description', 'string'
        // Historical only: register 26 records the most recent lockout EVENT and
        // does not clear on recovery. Use 'System Locked Out' for live state.
        attribute 'Last Lockout Event Code', 'number'
        attribute 'Last Lockout Event Description', 'string'
        // upstream build only: per-code occurrence counters since last clear
        attribute 'Fault History', 'string'

        // --- Safety inputs (true = tripped/active in both builds) ---
        attribute 'Low Pressure Switch', 'enum', ['true', 'false']
        attribute 'High Pressure Switch', 'enum', ['true', 'false']
        attribute 'Emergency Shutdown', 'enum', ['true', 'false']
        attribute 'System Alarm', 'enum', ['true', 'false']   // stock build only
        attribute 'Load Shed', 'enum', ['true', 'false']      // upstream build only

        // --- Electrical ---
        attribute 'Line Voltage (V)', 'number'
        attribute 'Compressor 1 Current (A)', 'number'
        attribute 'Compressor 2 Current (A)', 'number'
        attribute 'Blower Current (A)', 'number'
        attribute 'Aux Heat Current (A)', 'number'           // upstream build only

        // --- Power ---
        attribute 'Total Wattage (W)', 'number'              // upstream build only
        attribute 'Compressor Wattage (W)', 'number'
        attribute 'Blower Wattage (W)', 'number'
        attribute 'Aux Wattage (W)', 'number'
        attribute 'Open Loop Wattage (W)', 'number'

        // --- Water / air ---
        attribute 'Water Flow (gpm)', 'number'
        attribute 'Entering Water Temp (F)', 'number'
        attribute 'Leaving Water Temp (F)', 'number'
        attribute 'Entering Air Temp (F)', 'number'
        attribute 'Leaving Air Temp (F)', 'number'

        // --- Efficiency (upstream build only) ---
        attribute 'COP', 'number'
        attribute 'Water Delta-T (F)', 'number'
        attribute 'Approach Temperature (F)', 'number'
        attribute 'Heat of Extraction (BTU/h)', 'number'
        attribute 'Heat of Rejection (BTU/h)', 'number'

        // --- Unit info (upstream build only) ---
        attribute 'Model Number', 'string'
        attribute 'Serial Number', 'string'
        attribute 'Hardware Config', 'string'
        attribute 'Pump Type', 'string'

        // --- Device diagnostics ---
        attribute 'RS-485 Link', 'enum', ['true', 'false']
        attribute 'Uptime (s)', 'number'
        attribute 'WiFi Signal (dBm)', 'number'

        // populated automatically by the ESPHome API Library
        attribute 'networkStatus', 'enum', ['connecting', 'online', 'offline']
    }

    preferences {
        input name: 'ipAddress', // required setting for API library
            type: 'text',
            title: '<b>Device IP Address</b>',
            required: true

        input name: 'password', // optional setting for API library
            type: 'text',
            title: '<b>Device Password</b>',
            description: '<i>(if required)</i>',
            required: false

        input name: 'txtEnable', type: 'bool', title: '<b>Enable descriptionText logging</b>',
            defaultValue: true

        input name: 'logEnable', type: 'bool', title: '<b>Enable debug logging</b>',
            defaultValue: false, description: '<i>Turns on debug logging for 30 minutes.</i>'
    }
}

/*
 * Entity table: internal key -> [attribute, type, unit, decimals]
 *   type: 'bool' (published as 'true'/'false'), 'int', 'num', 'str'
 * Every attribute this driver can populate must appear here exactly once;
 * cleanupStaleAttributes() relies on it.
 */
@groovy.transform.Field static final Map<String, List> ENTITY_TABLE = [
    link_ok              : ['RS-485 Link', 'bool'],
    locked_out           : ['System Locked Out', 'bool'],
    fault_code           : ['Last Fault Code', 'int'],
    fault_description    : ['Last Fault Description', 'str'],
    lockout_code         : ['Last Lockout Event Code', 'int'],
    lockout_description  : ['Last Lockout Event Description', 'str'],
    fault_history        : ['Fault History', 'str'],
    current_mode         : ['Current Mode', 'str'],
    compressor_stage1    : ['Compressor Stage 1', 'bool'],
    compressor_stage2    : ['Compressor Stage 2', 'bool'],
    compressor_running   : ['Compressor Running', 'bool'],
    blower_running       : ['Blower Running', 'bool'],
    blower_ecm_speed     : ['Blower ECM Speed', 'int'],
    aux_heat_1           : ['Aux Heat Stage 1', 'bool'],
    aux_heat_2           : ['Aux Heat Stage 2', 'bool'],
    aux_heat_running     : ['Aux Heat Running', 'bool'],
    aux_heat_stage       : ['Aux Heat Stage', 'int'],
    anti_short_cycle     : ['Anti-Short-Cycle Remaining (s)', 'int', 's'],
    system_alarm         : ['System Alarm', 'bool'],
    low_pressure         : ['Low Pressure Switch', 'bool'],
    high_pressure        : ['High Pressure Switch', 'bool'],
    emergency_shutdown   : ['Emergency Shutdown', 'bool'],
    load_shed            : ['Load Shed', 'bool'],
    line_voltage         : ['Line Voltage (V)', 'num', 'V', 0],
    compressor_1_amps    : ['Compressor 1 Current (A)', 'num', 'A', 1],
    compressor_2_amps    : ['Compressor 2 Current (A)', 'num', 'A', 1],
    blower_amps          : ['Blower Current (A)', 'num', 'A', 1],
    aux_amps             : ['Aux Heat Current (A)', 'num', 'A', 1],
    total_watts          : ['Total Wattage (W)', 'num', 'W', 0],
    compressor_watts     : ['Compressor Wattage (W)', 'num', 'W', 0],
    blower_watts         : ['Blower Wattage (W)', 'num', 'W', 0],
    aux_watts            : ['Aux Wattage (W)', 'num', 'W', 0],
    open_loop_watts      : ['Open Loop Wattage (W)', 'num', 'W', 0],
    waterflow            : ['Water Flow (gpm)', 'num', 'gpm', 1],
    entering_water       : ['Entering Water Temp (F)', 'num', 'F', 1],
    leaving_water        : ['Leaving Water Temp (F)', 'num', 'F', 1],
    entering_air         : ['Entering Air Temp (F)', 'num', 'F', 1],
    leaving_air          : ['Leaving Air Temp (F)', 'num', 'F', 1],
    cop                  : ['COP', 'num', null, 2],
    water_delta_t        : ['Water Delta-T (F)', 'num', 'F', 1],
    approach_temp        : ['Approach Temperature (F)', 'num', 'F', 1],
    heat_of_extraction   : ['Heat of Extraction (BTU/h)', 'num', 'BTU/h', 0],
    heat_of_rejection    : ['Heat of Rejection (BTU/h)', 'num', 'BTU/h', 0],
    model_number         : ['Model Number', 'str'],
    serial_number        : ['Serial Number', 'str'],
    hardware_config      : ['Hardware Config', 'str'],
    pump_type            : ['Pump Type', 'str'],
    uptime               : ['Uptime (s)', 'int', 's'],
    wifi_signal          : ['WiFi Signal (dBm)', 'int', 'dBm'],
]

void installed() {
    log.info "${device} driver installed"
}

void initialize() {
    state.entities = state.entities ?: [:]
    // API library command to open the socket; it auto-reconnects as needed
    openSocket()

    if (logEnable) {
        runIn(1800, 'logsOff')
    }
}

void updated() {
    log.info "${device} driver configuration updated"
    initialize()
}

void refresh() {
    log.info "${device} refresh"
    espHomeDeviceInfoRequest()
    // re-request entity list + live states in case anything was missed
    espHomeListEntitiesRequest()
    espHomeSubscribeStatesRequest()
}

void reboot() {
    if (state.rebootKey) {
        log.warn "${device} sending reboot command"
        espHomeButtonCommand(key: state.rebootKey)
    } else {
        log.warn "${device}: no Restart button entity discovered yet -- add " +
            "'button: - platform: restart' to the device's YAML, or wait for " +
            "the next refresh/reconnect to pick it up"
    }
}

void uninstalled() {
    closeSocket('driver uninstalled')
    log.info "${device} driver uninstalled"
}

void logsOff() {
    device.updateSetting('logEnable', false)
    log.info "${device} debug logging disabled"
}

// the parse method is invoked by the API library when messages are received
void parse(final Map message) {
    if (logEnable) { log.debug "ESPHome received: ${message}" }

    switch (message.type) {
        case 'entity':
            parseKeys(message)
            break
        case 'state':
            parseState(message)
            break
        default:
            break
    }
}

/**
 * Map a discovered entity to an internal key by its objectId (ESPHome's
 * snake_case of the entity name). The device announces its full entity list
 * on every (re)connect; an entity message arriving after a quiet gap marks the
 * start of a new listing, so the map is rebuilt from scratch -- that's what lets
 * a reflash to a different build drop the old build's entities.
 */
void parseKeys(final Map message) {
    final long nowMs = now()
    if (state.entities == null || nowMs - ((state.lastEntityMs ?: 0L) as long) > 10000L) {
        state.entities = [:]
        state.remove('rebootKey')
    }
    state.lastEntityMs = nowMs
    // once the listing goes quiet, remove attributes this build doesn't publish
    runIn(15, 'cleanupStaleAttributes', [overwrite: true])

    final long key = message.key as long
    final String id = (message.objectId ?: message.name ?: '').toString().toLowerCase()

    // the restart button is matched on platform, not name
    if (message.platform == 'button') {
        state.rebootKey = key
        if (txtEnable) { log.info "${device}: found Restart button entity" }
        return
    }

    final String mapped = mapEntityId(id)
    if (mapped) {
        state.entities[mapped] = key
    } else if (logEnable) {
        log.debug "ESPHome entity not mapped: ${message}"
    }
}

/*
 * Order matters: more specific patterns first. Deliberately unmapped:
 * 'System Output Lockout (secondary)' (stock build diagnostic cross-check)
 * and 'WiFi BSSID' (temporary diagnostic).
 */
private String mapEntityId(final String id) {
    if (id.contains('link')) { return 'link_ok' }
    if (id.contains('system') && id.contains('locked')) { return 'locked_out' }
    if (id.contains('fault') && id.contains('history')) { return 'fault_history' }
    if (id.contains('lockout') && id.contains('description')) { return 'lockout_description' }
    if (id.contains('lockout') && id.contains('code')) { return 'lockout_code' }
    if (id.contains('fault') && id.contains('description')) { return 'fault_description' }
    if (id.contains('fault') && id.contains('code')) { return 'fault_code' }
    if (id.contains('current') && id.contains('mode')) { return 'current_mode' }

    if (id.contains('compressor') && id.contains('running')) { return 'compressor_running' }
    if (id.contains('compressor') && id.contains('stage') && id.contains('1')) { return 'compressor_stage1' }
    if (id.contains('compressor') && id.contains('stage') && id.contains('2')) { return 'compressor_stage2' }
    if (id.contains('compressor') && id.contains('current') && id.contains('1')) { return 'compressor_1_amps' }
    if (id.contains('compressor') && id.contains('current') && id.contains('2')) { return 'compressor_2_amps' }
    if (id.contains('compressor') && id.contains('wattage')) { return 'compressor_watts' }

    if (id.contains('blower') && id.contains('current')) { return 'blower_amps' }
    if (id.contains('blower') && id.contains('wattage')) { return 'blower_watts' }
    if (id.contains('blower') && id.contains('running')) { return 'blower_running' }
    if (id.contains('blower') && id.contains('ecm')) { return 'blower_ecm_speed' }

    if (id.contains('aux') && id.contains('wattage')) { return 'aux_watts' }
    if (id.contains('aux') && id.contains('current')) { return 'aux_amps' }
    if (id.contains('aux') && id.contains('running')) { return 'aux_heat_running' }
    if (id.contains('aux') && id.contains('stage') && id.contains('1')) { return 'aux_heat_1' }
    if (id.contains('aux') && id.contains('stage') && id.contains('2')) { return 'aux_heat_2' }
    if (id.contains('aux') && id.contains('stage')) { return 'aux_heat_stage' }

    if (id.contains('short') && id.contains('cycle')) { return 'anti_short_cycle' }
    if (id.contains('system') && id.contains('alarm')) { return 'system_alarm' }
    if (id.contains('low') && id.contains('pressure')) { return 'low_pressure' }
    if (id.contains('high') && id.contains('pressure')) { return 'high_pressure' }
    if (id.contains('emergency')) { return 'emergency_shutdown' }
    if (id.contains('load') && id.contains('shed')) { return 'load_shed' }

    if (id.contains('line') && id.contains('voltage')) { return 'line_voltage' }
    if (id.contains('total') && id.contains('wattage')) { return 'total_watts' }
    if (id.contains('open') && id.contains('loop')) { return 'open_loop_watts' }

    if (id.contains('water') && id.contains('delta')) { return 'water_delta_t' }
    if (id.contains('approach')) { return 'approach_temp' }
    if (id.contains('extraction')) { return 'heat_of_extraction' }
    if (id.contains('rejection')) { return 'heat_of_rejection' }
    if (id == 'cop') { return 'cop' }

    if (id.contains('water') && id.contains('flow')) { return 'waterflow' }
    if (id.contains('entering') && id.contains('water')) { return 'entering_water' }
    if (id.contains('leaving') && id.contains('water')) { return 'leaving_water' }
    if (id.contains('entering') && id.contains('air')) { return 'entering_air' }
    if (id.contains('leaving') && id.contains('air')) { return 'leaving_air' }

    if (id.contains('model')) { return 'model_number' }
    if (id.contains('serial')) { return 'serial_number' }
    if (id.contains('hardware')) { return 'hardware_config' }
    if (id.contains('pump') && id.contains('type')) { return 'pump_type' }

    if (id.contains('uptime')) { return 'uptime' }
    if (id.contains('wifi') && id.contains('signal')) { return 'wifi_signal' }
    return null
}

/**
 * Remove any attribute whose entity the current build doesn't publish, so a
 * build change never leaves values frozen. Runs 15s after the last entity
 * message of a listing.
 */
void cleanupStaleAttributes() {
    final Map ents = state.entities ?: [:]
    if (ents.isEmpty()) { return }  // listing didn't happen; don't wipe everything
    ENTITY_TABLE.each { String k, List spec ->
        final String attr = spec[0]
        if (!ents.containsKey(k) && device.currentValue(attr) != null) {
            device.deleteCurrentState(attr)
            if (txtEnable) { log.info "${device}: removed '${attr}' (not published by this build)" }
        }
    }
}

void parseState(final Map message) {
    if (message.key == null) { return }
    final long key = message.key as long

    final String entityKey = (state.entities ?: [:]).find { k, v -> (v as long) == key }?.key
    if (!entityKey) { return }
    final List spec = ENTITY_TABLE[entityKey]
    if (!spec) { return }

    final String attr = spec[0]
    if (message.hasState == false) {
        // Entity exists but has no valid reading (e.g. upstream's Entering Air
        // Temp, suppressed without a communicating thermostat, or Approach
        // Temperature with the compressor off): clear rather than leave a stale value.
        if (device.currentValue(attr) != null) {
            device.deleteCurrentState(attr)
            if (logEnable) { log.debug "${device}: cleared '${attr}' (no valid reading)" }
        }
        return
    }
    final String type = spec[1]
    final String unit = spec.size() > 2 ? spec[2] : null
    final Integer decimals = spec.size() > 3 ? spec[3] : null
    final Object raw = message.state

    switch (type) {
        case 'bool':
            updateAttribute(attr, raw ? 'true' : 'false')
            if (entityKey == 'locked_out' && raw) {
                final String fault = device.currentValue('Last Fault Description') ?: 'unknown fault'
                log.warn "WaterFurnace LOCKOUT: ${fault}"
            }
            break
        case 'str':
            updateAttribute(attr, raw as String)
            break
        default:  // 'int' / 'num'
            if (!(raw instanceof Number) || ((raw as Double).isNaN()) || ((raw as Double).isInfinite())) {
                // No valid reading (e.g. Approach Temperature with the compressor off):
                // clear the attribute rather than show a stale or bogus number.
                if (device.currentValue(attr) != null) { device.deleteCurrentState(attr) }
                return
            }
            if (type == 'int') {
                updateAttribute(attr, Math.round(raw as Double) as Integer, unit)
            } else {
                updateAttribute(attr, raw, unit, decimals)
            }
            break
    }
}

/**
 * Update a device attribute and log if it changed and descriptionText logging is on.
 * decimals, if given, rounds the value cleanly -- values arrive as 32-bit
 * floats over the wire, which can't exactly represent most decimal fractions.
 * Only sends an event when the value actually changed.
 */
private void updateAttribute(final String attribute, final Object rawValue, final String unit = null,
                              final Integer decimals = null) {
    Object value = rawValue
    if (decimals != null && value instanceof Number) {
        BigDecimal rounded = new BigDecimal(value.toString()).setScale(decimals, BigDecimal.ROUND_HALF_UP)
        value = decimals == 0 ? rounded.intValue() : rounded
    }
    if (device.currentValue(attribute) == value) { return }
    final String descriptionText = "${attribute} was set to ${value}${unit ? ' ' + unit : ''}"
    if (settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: attribute, value: value, unit: unit, descriptionText: descriptionText)
}

// Put this line at the end of the driver to include the ESPHome API library helper
#include esphome.espHomeApiHelper

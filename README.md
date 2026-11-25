# ESPHome WaterFurnace Aurora Monitor: Stock Modbus

A read-only ESPHome monitor for WaterFurnace Aurora geothermal heat pumps. It uses
**only standard Modbus function codes**, so it works on any Aurora ABC firmware,
including boards too old for the full-featured WaterFurnace ESPHome components.

It runs on an ESP32 with a MAX485 transceiver and an optional 128×64 OLED, and it
exposes operating status, lockout and fault alerts, and live telemetry to Home
Assistant, Hubitat, or anything else that speaks the ESPHome native API.

## Which project should you use?

| Your situation | Use |
|---|---|
| The full component never gets past setup and cycles **Idle → Link Down** forever | **This project** |
| Your ABC board answers WaterFurnace's proprietary function codes 0x41/0x42 and you want everything (control, thermostat zones, refrigeration detail, DHW) | The full upstream component: [espforge/esphome-waterfurnace](https://github.com/espforge/esphome-waterfurnace) or [daemonp/esphome_waterfurnace_aurora](https://github.com/daemonp/esphome_waterfurnace_aurora) |
| You only want monitoring and alerting and would rather not depend on proprietary function codes | Either one works. This project is deliberately smaller |

### Two variants in this repo

| File | Needs | Notes |
|---|---|---|
| `waterfurnace-monitor.yaml` | Any ABC firmware | **Stock Modbus.** Only standard function codes; the original reason this repo exists |
| `waterfurnace-monitor-upstream.yaml` | ABC firmware that answers 0x41/0x42 (e.g. v4.x) | **Upstream component**, kept read-only. Adds hardware auto-detection, COP, water delta-T, approach temp, heat of extraction/rejection, total watts, aux amps, fault history, model/serial. **Experimental**: under evaluation on the reference unit |

Both variants use the same hardware, pins, OLED layout and Hubitat-friendly
reporting filters, so you can swap between them by reflashing. The upstream
variant declares the hub directly instead of importing upstream's package. That
leaves out upstream's climate, water heater, switch, number, select and button
platforms, all of which write to the board, so it stays strictly read-only.

Upstream variant differences to be aware of:
- There are no separate compressor **stage 1/2** entities. You get "Compressor
  Running" plus a mode string with no stage number. Stage still shows
  indirectly through blower ECM speed and compressor amps/watts.
- If you use a generic MAX485 module and setup never completes (request-echo
  timeouts in the log), point `component_source` at the fork noted in the YAML
  until espforge/esphome-waterfurnace#5 is merged.

### Why this exists

The full components poll efficiently using WaterFurnace's proprietary function
codes **0x41** (read ranges) and **0x42** (read scattered registers). An ABC
board on firmware **v2.02** rejected every 0x42 request with exception `0xC2 / 01`
("Illegal Function"), so the full component never finished setup.

Standard **0x03 (Read Holding Registers)** worked fine. This config drops the
custom component and uses only ESPHome's stock `modbus:` and `modbus_controller:`
components, which only use standard function codes.

**Tested on:** ABC v2.02 (the original reason this exists) and ABC v4.03 /
AXB v4.01 after a firmware update. It works on both unchanged.

## What it monitors

- **Status:** compressor stage 1/2, aux heat stage 1/2, blower, a derived
  "Current Mode" (Heating 1/2, Cooling 1/2, Aux Heat, Fan Only, Idle)
- **Alerts:** live lockout flag, current fault code plus a human-readable
  description (67-code table ported from upstream), system alarm, emergency
  shutdown, RS-485 link health
- **Diagnostics:** last lockout *event* code and description (a sticky history
  register; see below), low/high pressure switch inputs
- **Telemetry (AXB required):** blower ECM speed, line voltage, compressor and
  blower amps, compressor/blower/aux/open-loop wattage, water flow,
  entering/leaving water temp, entering/leaving air temp
- **OLED:** six rotating pages (HVAC, Fault, Air, Water, two Energy pages) and
  a diagnostics page that takes over automatically if the RS-485 link drops

It is **strictly read-only**. There are no control entities, and it never
writes to the heat pump.

## Hardware

- ESP32 dev board (classic ESP32-WROOM-32)
- MAX485 RS-485 transceiver module, **powered from 3.3 V**
- Optional: SSD1306 128×64 I2C OLED (the yellow/blue variant suits the header
  layout)
- A connection to the Aurora **AID tool port** for RS-485 A/B and ground

### Wiring

| MAX485 | ESP32 |
|---|---|
| RO | GPIO16 (RX2) |
| DI | GPIO17 (TX2) |
| DE + RE (tied together) | GPIO4 |
| VCC | 3.3 V (**not 5 V**) |
| GND | GND (common with the AID port reference) |
| A / B | AID port A / B. Check polarity: if you get no responses, swap them |

| OLED | ESP32 |
|---|---|
| SDA | GPIO21 |
| SCL | GPIO22 |

Power the ESP32 separately rather than from the AID port's 24 VAC. That keeps
the AID port comms-only and means an HVAC power loss shows up as a link-down
alert instead of the monitor going dark.

Pins are set in the `substitutions:` block at the top of the YAML.

## Install

1. Copy `waterfurnace-monitor.yaml` into your ESPHome config directory.
2. Provide `wifi_ssid` and `wifi_password` in your `secrets.yaml`.
3. Check the `substitutions:` block (pins, Modbus address, poll interval).
4. Build and flash as usual.

## Things to check on your own install

- **Water temperatures:** both YAMLs use the standard mapping (entering 1111,
  leaving 1110). To check it, watch a *steady* heating cycle, about 10 minutes
  in. Leaving water should be colder than entering, and
  gpm × ΔT × 500 should roughly match Heat of Extraction. Don't trust the first
  minute or two of a cycle: an early reading on the reference unit falsely
  suggested the sensors were reversed. If they really are reversed on your
  install, swap the two addresses in the stock YAML, or set
  `water_temps_swapped: true` in the upstream YAML.
- **Entering air temp (register 567)** may not be populated without a
  communicating thermostat. A raw 0 is reported as N/A, not 0°F.
- **Leaving air** uses AXB register **1112**, not register 900, because
  register 900 needs AWL AXB firmware.
- **Pressure switch polarity:** the status bits are set when the switch is
  *closed*, which is normal. The YAML inverts them, so `true` means tripped and
  they're safe to alert on. This was verified on the reference unit and matches
  upstream.
- **"Last Lockout Event" is history, not live state.** Register 26 records the
  most recent lockout and stays set after recovery. Use **System Locked Out**
  (register 25, bit 15) for live alerting.
- **Reversing valve:** RV energized means Cooling, regardless of the B/O DIP
  switch. This matches upstream's logic and the AID tool on the reference unit.

## Wi-Fi power save is turned off (important)

The YAML sets `power_save_mode: none` under `wifi:`. **Keep it.** With the
ESP32's default light power-save, traffic *to* the device (your hub's
keepalive pings and API requests) can be delayed or dropped for seconds to
minutes while traffic *from* it keeps flowing. The device never reboots or
loses Wi-Fi, but the hub sees pings go unanswered and repeatedly marks it
offline. Signal strength doesn't matter: it happened at a strong -48 dBm on the
reference install, and turning power save off stopped it completely. The
monitor is mains-powered, so the extra power draw doesn't matter.

## Reporting cadence (why the filters are there)

`modbus_controller` re-publishes every entity on every poll, even when nothing
changed. That came to about 20 API pushes a minute, far more than a monitor
needs. Each numeric sensor therefore uses:

```yaml
filters:
  - or:
      - throttle: 300s   # slow heartbeat
      - delta: 0.5       # real changes publish on the very next poll
```

The mode and fault text sensors only publish when their text changes, and
binary sensors only publish on change by default. In steady state the traffic
is a trickle, but compressor, aux and blower changes, temperature swings and
fault codes still go out within one poll (60 s by default).

## Hubitat driver

`hubitat/ESPHome-WaterFurnace-Monitor.groovy` is a read-only Hubitat driver
that works with **both** variants. It's built on Jonathan Bradshaw's ESPHome
Native API library (`esphome.espHomeApiHelper`), which must already be
installed on the hub.

- Attributes that both builds share have the same names, so rules keep working
  after a reflash in either direction.
- After each entity discovery, the driver removes any attribute the current
  build doesn't publish. Switching from stock to upstream drops the
  Compressor/Aux Stage 1/2 attributes instead of leaving them frozen.
- Readings with no valid value (e.g. Approach Temperature while the compressor
  is off) clear the attribute instead of showing a stale number.
- The only command is `reboot()`, which uses the YAML's restart button.

To install, add it under **Drivers Code** (or import from the raw GitHub URL),
create a virtual device or switch an existing one to type
**ESPHome WaterFurnace Monitor**, set the device's IP address, and save.

## Credits

All register addresses, data types, scaling and fault descriptions come from
the upstream projects. This config only reads them in a different way:

- [espforge/esphome-waterfurnace](https://github.com/espforge/esphome-waterfurnace)
- [daemonp/esphome_waterfurnace_aurora](https://github.com/daemonp/esphome_waterfurnace_aurora),
  which provided the register map (`registers.h`) and fault table (`registers.cpp`)

## License

MIT. See [LICENSE](LICENSE).

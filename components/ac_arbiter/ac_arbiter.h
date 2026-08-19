// ac_arbiter.h - ESPHome adapter around ArbiterCore.
//
// This file is deliberately dumb. It reads sensors, tracks their freshness,
// hands a snapshot to the state machine, and writes the result to exactly one
// switch. Any `if` that decides whether the inverter should be on belongs in
// arbiter_core.cpp, where it can be unit-tested on a host.
//
// CLAUDE.md section 5.3, single writer: this component is the only thing in the
// firmware permitted to touch the P310 `ac` switch.

#pragma once

#include <cmath>

#include "esphome/core/component.h"
#include "esphome/core/hal.h"
#include "esphome/components/sensor/sensor.h"
#include "esphome/components/binary_sensor/binary_sensor.h"
#include "esphome/components/switch/switch.h"

#include "arbiter_core.h"

namespace esphome {
namespace ac_arbiter {

// A sensor value plus when it last arrived. ESPHome sensors keep their last
// state forever; a stale value that looks fresh is exactly how a fail-safe
// arbiter gets quietly defeated.
class FreshValue {
 public:
  void set(float v) {
    value_ = v;
    stamp_ = millis();
    seen_ = true;
  }
  bool valid(uint32_t max_age_ms) const {
    return seen_ && !std::isnan(value_) && (millis() - stamp_) < max_age_ms;
  }
  float value() const { return value_; }

 protected:
  float value_{NAN};
  uint32_t stamp_{0};
  bool seen_{false};
};

class AcArbiter : public PollingComponent {
 public:
  void setup() override;
  void update() override;
  void dump_config() override;
  // Run before almost everything so the first decision (force ON) is made
  // before any other component can publish a contradictory state.
  float get_setup_priority() const override { return setup_priority::AFTER_CONNECTION; }

  // --- wiring, set from codegen ---
  void set_fridge_temperature(sensor::Sensor *s);
  void set_output_power(sensor::Sensor *s);
  void set_input_power(sensor::Sensor *s);
  void set_battery_level(sensor::Sensor *s);
  void set_ble_connected(binary_sensor::BinarySensor *s);
  void set_ac_switch(switch_::Switch *s) { ac_switch_ = s; }
  void set_sensor_max_age(uint32_t ms) { sensor_max_age_ms_ = ms; }

  // --- runtime inputs from YAML ---
  void set_sleep_mode(bool on) { sleep_mode_ = on; }
  void set_drive_inhibit(bool on) { drive_inhibit_ = on; }
  void manual_press() { core_.manual_press(millis()); update(); }
  void manual_cancel() { core_.manual_cancel(millis()); update(); }

  // --- outputs for display / web / logging ---
  bool ac_on() const { return core_.outputs().ac_on; }
  bool force_on() const { return core_.outputs().force_on; }
  bool fridge_req() const { return core_.outputs().fridge_req; }
  bool manual_req() const { return core_.outputs().manual_req; }
  bool surplus_req() const { return core_.outputs().surplus_req; }
  bool manual_warning() const { return core_.outputs().manual_warning; }
  uint32_t manual_remaining_s() const { return core_.outputs().manual_remaining_s; }
  const char *reason() const { return van::ac_reason_str(core_.outputs().reason); }

  van::ArbiterConfig &config() { return core_.config(); }

 protected:
  van::ArbiterCore core_;

  FreshValue fridge_temp_;
  FreshValue output_power_;
  FreshValue input_power_;
  FreshValue soc_;

  binary_sensor::BinarySensor *ble_connected_{nullptr};
  switch_::Switch *ac_switch_{nullptr};

  uint32_t sensor_max_age_ms_{30000};
  bool sleep_mode_{false};
  bool drive_inhibit_{false};

  bool last_written_{false};
  bool written_once_{false};
  uint32_t last_write_ms_{0};
};

}  // namespace ac_arbiter
}  // namespace esphome

#pragma once
// fbot - a SIMULATED P310 for nodes/van-core-home.yaml. NOT ESP-FBot.
//
// Stands in for the station at the end of the BLE link, and for what is
// plugged into it, so van-core's own logic - arbiter, scheduler, fail-safes,
// manual timer, parked and sleep modes, display, UI, log - can be exercised
// at home exactly as it runs in the van. It mirrors ESP-FBot where van-core
// can tell the difference:
//   - a switch write while disconnected is dropped and the switch reads OFF
//   - a write that matches the switch's current state is skipped
//   - accepted writes publish optimistically; the station applies them after
//     a short latency and the next poll reports what it actually did
//   - "connected" drops at once when the link goes; nothing else is published
//     until it is back
//
// The plant numbers below are PLAUSIBLE, NOT MEASURED. They exist so the
// arbiter has something that answers back; they are not a model to tune
// thresholds against (CLAUDE.md section 12: measure, do not estimate).

#include "esphome/components/binary_sensor/binary_sensor.h"
#include "esphome/components/number/number.h"
#include "esphome/components/sensor/sensor.h"
#include "esphome/components/switch/switch.h"
#include "esphome/core/component.h"

namespace esphome {
namespace fbot {

// Shared by the hub, switch and number (LOG_SWITCH / LOG_NUMBER expect `TAG`).
static const char *const TAG = "fbot.sim";

// Index order matches the tables in sensor.py, binary_sensor.py,
// switch/__init__.py and number/__init__.py.
enum SensorIdx : uint8_t {
  S_BATTERY, S_INPUT, S_OUTPUT, S_SYSTEM, S_AC_INPUT, S_DC_INPUT,
  S_TOTAL, S_REMAINING, S_AC_VOLT, S_CHARGE_LEVEL, S_COUNT
};
enum BinaryIdx : uint8_t { B_CONNECTED, B_AC, B_DC, B_USB, B_COUNT };
enum SwitchIdx : uint8_t { W_AC, W_DC, W_USB, W_AC_SILENT, W_COUNT };
enum NumberIdx : uint8_t { N_CHARGE_MAX, N_DISCHARGE_MIN, N_COUNT };

class Fbot : public Component {
 public:
  void setup() override;
  void loop() override;
  void dump_config() override;
  float get_setup_priority() const override { return setup_priority::DATA; }

  void set_polling_interval(uint32_t ms) { this->poll_ms_ = ms; }
  void set_settings_polling_interval(uint32_t ms) { this->settings_ms_ = ms; }

  void set_sensor(uint8_t i, sensor::Sensor *s) { this->sensors_[i] = s; }
  void set_binary_sensor(uint8_t i, binary_sensor::BinarySensor *s) { this->binaries_[i] = s; }
  void set_switch(uint8_t i, switch_::Switch *s) { this->switches_[i] = s; }
  void set_number(uint8_t i, number::Number *n) { this->numbers_[i] = n; }

  bool is_connected() const { return this->link_up_; }
  // A command from van-core over the simulated link. The caller has already
  // checked is_connected(), as ESP-FBot's switch and number do.
  void write_switch(uint8_t i, bool on);
  void write_number(uint8_t i, float value);

  // ---- simulation controls, for the "Sim:" entities in van-core-home.yaml --
  bool sim_link_up() const { return this->link_up_; }
  void sim_set_link_up(bool up);
  // Link claims to be up, but the station has stopped answering: the case
  // the arbiter's link_stale fail-safe exists for. Writes still land.
  bool sim_frozen() const { return this->frozen_; }
  void sim_set_frozen(bool frozen);
  bool sim_probe_failed() const { return this->probe_failed_; }
  void sim_set_probe_failed(bool failed);
  float sim_solar_w() const { return this->solar_w_; }
  void sim_set_solar_w(float w) { this->solar_w_ = w; }
  float sim_cooking_w() const { return this->cooking_w_; }
  void sim_set_cooking_w(float w) { this->cooking_w_ = w; }
  float sim_ambient_c() const { return this->ambient_c_; }
  void sim_set_ambient_c(float c) { this->ambient_c_ = c; }
  // Speeds up the plant only (cabinet temperature, battery). The arbiter's
  // own timers stay in real time.
  float sim_time_factor() const { return this->time_factor_; }
  void sim_set_time_factor(float f) { this->time_factor_ = f; }
  float sim_soc() const { return this->soc_; }
  void sim_set_soc(float pct);
  float sim_fridge_c() const { return this->fridge_c_; }
  void sim_set_fridge_c(float c) { this->fridge_c_ = c; }
  bool sim_compressor_running() const { return this->compressor_; }

 protected:
  void step_(float dt_s);
  void apply_due_(uint32_t now);
  void publish_live_();
  void publish_settings_();
  void pub_(uint8_t i, float v) {
    if (this->sensors_[i] != nullptr)
      this->sensors_[i]->publish_state(v);
  }
  void pub_b_(uint8_t i, bool v) {
    if (this->binaries_[i] != nullptr)
      this->binaries_[i]->publish_state(v);
  }

  sensor::Sensor *sensors_[S_COUNT]{};
  binary_sensor::BinarySensor *binaries_[B_COUNT]{};
  switch_::Switch *switches_[W_COUNT]{};
  number::Number *numbers_[N_COUNT]{};

  // A write the station has received but not yet acted on.
  struct Pending {
    bool active{false};
    float value{0};
    uint32_t due{0};
  };
  Pending pend_sw_[W_COUNT];
  Pending pend_num_[N_COUNT];

  // ---- the station ----
  bool out_[W_COUNT]{true, true, true, false};  // ac, dc, usb, ac_silent
  float charge_max_{100};
  float discharge_min_{10};
  float soc_{80};
  float in_w_{0}, out_w_{0}, ac_out_w_{0}, overhead_w_{0};

  // ---- the fridge and the rest of the van ----
  float fridge_c_{5};
  bool compressor_{false};
  float solar_w_{0};
  float cooking_w_{0};
  float ambient_c_{24};
  float time_factor_{1};

  // ---- the link ----
  bool link_up_{true};
  bool frozen_{false};
  bool probe_failed_{false};

  uint32_t poll_ms_{2000};
  uint32_t settings_ms_{60000};
  uint32_t last_step_ms_{0};
  uint32_t last_poll_ms_{0};
  uint32_t last_settings_ms_{0};
};

}  // namespace fbot
}  // namespace esphome

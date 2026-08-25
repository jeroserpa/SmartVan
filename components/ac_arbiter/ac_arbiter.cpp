#include "ac_arbiter.h"
#include "esphome/core/log.h"

namespace esphome {
namespace ac_arbiter {

static const char *const TAG = "ac_arbiter";

// Re-assert the switch periodically even when our decision has not changed.
// The P310 can drop and re-establish BLE, or be poked from its own front panel;
// the arbiter is the authority and says so once a minute.
static const uint32_t REASSERT_MS = 60000;

void AcArbiter::set_fridge_temperature(sensor::Sensor *s) {
  s->add_on_state_callback([this](float v) { this->fridge_temp_.set(v); });
}
void AcArbiter::set_cabin_temperature(sensor::Sensor *s) {
  s->add_on_state_callback([this](float v) { this->cabin_temp_.set(v); });
}
void AcArbiter::set_output_power(sensor::Sensor *s) {
  s->add_on_state_callback([this](float v) { this->output_power_.set(v); });
}
void AcArbiter::set_input_power(sensor::Sensor *s) {
  s->add_on_state_callback([this](float v) { this->input_power_.set(v); });
}
void AcArbiter::set_battery_level(sensor::Sensor *s) {
  s->add_on_state_callback([this](float v) { this->soc_.set(v); });
}
void AcArbiter::set_ble_connected(binary_sensor::BinarySensor *s) { this->ble_connected_ = s; }

void AcArbiter::setup() {
  this->core_.begin(millis());
  // Fail toward powered from the first millisecond, before any sensor has had
  // a chance to report and before BLE has connected.
  //
  // A van rebooting in parked mode therefore pulses AC on for the fraction of
  // a second before the parked switch restores from flash and calls
  // set_parked(). That is the correct trade: the alternative is holding AC off
  // until a flash read completes, which puts a storage feature on the fridge's
  // critical path. Accepted; visible in the log as an ON immediately followed
  // by OFF (parked).
  if (this->ac_switch_ != nullptr)
    this->ac_switch_->turn_on();
}

void AcArbiter::update() {
  const uint32_t now = millis();

  van::ArbiterInputs in;
  // A binary_sensor with no state yet reports false, which is the safe reading
  // here: no known BLE link means force_on.
  in.ble_connected = this->ble_connected_ != nullptr && this->ble_connected_->state;

  in.temp_valid = this->fridge_temp_.valid(this->sensor_max_age_ms_);
  in.fridge_temp_c = this->fridge_temp_.value();
  // Cabin ambient updates slowly (30s), so it gets its own generous window.
  // It feeds the parked-mode arming interlock and nothing else.
  in.cabin_valid = this->cabin_temp_.valid(this->sensor_max_age_ms_ * 3);
  in.cabin_temp_c = this->cabin_temp_.value();
  in.power_valid = this->output_power_.valid(this->sensor_max_age_ms_);
  in.output_power_w = this->output_power_.value();
  in.input_power_valid = this->input_power_.valid(this->sensor_max_age_ms_);
  in.input_power_w = this->input_power_.value();
  in.soc_valid = this->soc_.valid(this->sensor_max_age_ms_);
  in.soc_pct = this->soc_.value();

  in.sleep_mode = this->sleep_mode_;
  in.drive_inhibit = this->drive_inhibit_;

  const van::ArbiterOutputs &out = this->core_.tick(now, in);

  // A write attempted while the link was down never reached the station, but it
  // still updated last_written_ - so on reconnect the arbiter believes AC is
  // already what it asked for and the re-assert would not go out for up to
  // REASSERT_MS. That is a minute of fridge-off after the link is back, in
  // exactly the scenario the fail-safe exists for. Treat the down->up edge as
  // "nothing is known to be written" and re-send on the next tick.
  if (in.ble_connected && !this->ble_was_connected_)
    this->written_once_ = false;
  this->ble_was_connected_ = in.ble_connected;

  const bool changed = !this->written_once_ || out.ac_on != this->last_written_;
  const bool due = (now - this->last_write_ms_) >= REASSERT_MS;
  if (this->ac_switch_ != nullptr && (changed || due)) {
    if (changed)
      ESP_LOGI(TAG, "AC %s (%s)", out.ac_on ? "ON" : "OFF", van::ac_reason_str(out.reason));
    if (out.ac_on)
      this->ac_switch_->turn_on();
    else
      this->ac_switch_->turn_off();
    this->last_written_ = out.ac_on;
    this->written_once_ = true;
    this->last_write_ms_ = now;
  }
}

void AcArbiter::dump_config() {
  const van::ArbiterConfig &c = this->core_.config();
  ESP_LOGCONFIG(TAG, "AC arbiter:");
  ESP_LOGCONFIG(TAG, "  fridge on/off/hard: %.1f / %.1f / %.1f C", c.temp_on_c, c.temp_off_c,
                c.temp_hard_c);
  ESP_LOGCONFIG(TAG, "  sleep ceiling/target: %.1f / %.1f C", c.sleep_ceiling_c, c.sleep_target_c);
  ESP_LOGCONFIG(TAG, "  compressor idle: <%.0f W for %u s", c.compressor_idle_w,
                c.compressor_idle_ms / 1000u);
  ESP_LOGCONFIG(TAG, "  min on/off: %u / %u s", c.min_on_ms / 1000u, c.min_off_ms / 1000u);
  ESP_LOGCONFIG(TAG, "  sensor max age: %u s", this->sensor_max_age_ms_ / 1000u);
  ESP_LOGCONFIG(TAG, "  parked arm interlock: cabinet within %.1f C of cabin",
                c.parked_arm_delta_c);
  if (this->core_.parked())
    ESP_LOGW(TAG, "  PARKED: AC held off and the fail-safe is disarmed");
}

}  // namespace ac_arbiter
}  // namespace esphome

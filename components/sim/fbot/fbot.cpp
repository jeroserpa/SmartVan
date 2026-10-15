#include "fbot.h"

#include <algorithm>
#include <cmath>

#include "esphome/core/hal.h"
#include "esphome/core/log.h"

namespace esphome {
namespace fbot {

// ---- plant constants: PLAUSIBLE, NOT MEASURED ------------------------------
// Station
static const float CAPACITY_WH = 3900.0f;      // usable, M2
static const float STATION_BASE_W = 8.0f;      // BMS, display, fan at rest
static const float INVERTER_IDLE_W = 40.0f;    // base + this = M2's ~48 W with AC on;
                                               // the real split is UNKNOWN (idle-test)
static const float DC_LOAD_W = 4.0f;           // the 12 V bus: van-core and friends
static const uint32_t LATENCY_MS = 1500;       // write -> station acts
// Fridge: its own thermostat around SET_C, as after a power cut (medium, M16)
static const float SET_C = 3.0f;
static const float HYST_C = 0.5f;
static const float COOL_C_PER_H = 4.0f;        // pull-down while the compressor runs
static const float LEAK_TAU_H = 20.0f;         // drift towards ambient
static const float FRIDGE_RUN_W = 30.0f;       // M6: 24-34 W
static const float FRIDGE_IDLE_W = 2.0f;       // controller, compressor stopped

static const char *const SWITCH_NAMES[W_COUNT] = {"AC", "DC", "USB", "silent charging"};
static const char *const NUMBER_NAMES[N_COUNT] = {"charge max", "discharge min"};

void Fbot::setup() {
  ESP_LOGW(TAG, "SIMULATED P310 - no BLE, no station. Home testing only.");
  const uint32_t now = millis();
  this->last_step_ms_ = now;
  this->step_(0.0f);
  this->publish_live_();
  this->publish_settings_();
  this->last_poll_ms_ = now;
  this->last_settings_ms_ = now;
}

void Fbot::dump_config() {
  ESP_LOGCONFIG(TAG, "Fbot: SIMULATED P310 (components/sim/fbot) - not the real station");
  ESP_LOGCONFIG(TAG, "  Poll: %u ms, settings: %u ms", (unsigned) this->poll_ms_,
                (unsigned) this->settings_ms_);
}

void Fbot::loop() {
  const uint32_t now = millis();
  this->apply_due_(now);

  if (now - this->last_step_ms_ >= 1000) {
    this->step_((now - this->last_step_ms_) / 1000.0f * this->time_factor_);
    this->last_step_ms_ = now;
  }

  // A silent station publishes nothing: no link, or a wedged one.
  if (!this->link_up_ || this->frozen_)
    return;
  if (now - this->last_poll_ms_ >= this->poll_ms_) {
    this->last_poll_ms_ = now;
    this->publish_live_();
  }
  if (now - this->last_settings_ms_ >= this->settings_ms_) {
    this->last_settings_ms_ = now;
    this->publish_settings_();
  }
}

void Fbot::write_switch(uint8_t i, bool on) {
  ESP_LOGI(TAG, "station received: %s %s", SWITCH_NAMES[i], on ? "ON" : "OFF");
  this->pend_sw_[i] = {true, on ? 1.0f : 0.0f, millis() + LATENCY_MS};
}

void Fbot::write_number(uint8_t i, float value) {
  ESP_LOGI(TAG, "station received: %s = %.0f %%", NUMBER_NAMES[i], value);
  this->pend_num_[i] = {true, value, millis() + LATENCY_MS};
}

void Fbot::apply_due_(uint32_t now) {
  for (uint8_t i = 0; i < W_COUNT; i++) {
    Pending &p = this->pend_sw_[i];
    if (!p.active || (int32_t) (now - p.due) < 0)
      continue;
    p.active = false;
    this->out_[i] = p.value > 0.5f;
    ESP_LOGI(TAG, "station: %s is now %s", SWITCH_NAMES[i], this->out_[i] ? "ON" : "OFF");
  }
  for (uint8_t i = 0; i < N_COUNT; i++) {
    Pending &p = this->pend_num_[i];
    if (!p.active || (int32_t) (now - p.due) < 0)
      continue;
    p.active = false;
    (i == N_CHARGE_MAX ? this->charge_max_ : this->discharge_min_) = p.value;
  }
}

void Fbot::step_(float dt_s) {
  const bool ac = this->out_[W_AC];
  const float dt_h = dt_s / 3600.0f;

  // The fridge's own thermostat, which only has power while AC is on.
  if (!ac)
    this->compressor_ = false;
  else if (this->fridge_c_ > SET_C + HYST_C)
    this->compressor_ = true;
  else if (this->fridge_c_ < SET_C - HYST_C)
    this->compressor_ = false;
  this->fridge_c_ += ((this->ambient_c_ - this->fridge_c_) / LEAK_TAU_H -
                      (this->compressor_ ? COOL_C_PER_H : 0.0f)) * dt_h;

  const float fridge_w = !ac ? 0.0f : this->compressor_ ? FRIDGE_RUN_W : FRIDGE_IDLE_W;
  this->ac_out_w_ = ac ? fridge_w + this->cooking_w_ : 0.0f;
  this->out_w_ = this->ac_out_w_ + (this->out_[W_DC] ? DC_LOAD_W : 0.0f);
  this->overhead_w_ = STATION_BASE_W + (ac ? INVERTER_IDLE_W : 0.0f);

  // At the charge limit the MPPT throttles to what is being used.
  this->in_w_ = this->solar_w_;
  if (this->soc_ >= this->charge_max_)
    this->in_w_ = std::min(this->in_w_, this->out_w_ + this->overhead_w_);

  const float net_w = this->in_w_ - this->out_w_ - this->overhead_w_;
  this->soc_ = std::max(0.0f, std::min(100.0f, this->soc_ + net_w * dt_h / CAPACITY_WH * 100.0f));
}

void Fbot::publish_live_() {
  this->pub_b_(B_CONNECTED, true);
  this->pub_b_(B_AC, this->out_[W_AC]);
  this->pub_b_(B_DC, this->out_[W_DC]);
  this->pub_b_(B_USB, this->out_[W_USB]);
  // The readback: what the station actually did, over any optimistic state.
  for (uint8_t i = 0; i < W_COUNT; i++)
    if (this->switches_[i] != nullptr)
      this->switches_[i]->publish_state(this->out_[i]);

  const float net_w = this->in_w_ - this->out_w_ - this->overhead_w_;
  float remaining_min = 5999.0f;
  if (net_w < -1.0f)
    remaining_min = std::min(5999.0f, this->soc_ / 100.0f * CAPACITY_WH / -net_w * 60.0f);

  this->pub_(S_BATTERY, this->soc_);
  this->pub_(S_INPUT, roundf(this->in_w_));
  this->pub_(S_OUTPUT, roundf(this->out_w_));
  this->pub_(S_SYSTEM, 20.0f);  // reg 21 is not a power (ble-registers.md); ~20 with no AC input
  this->pub_(S_AC_INPUT, 0.0f);
  this->pub_(S_DC_INPUT, roundf(this->in_w_));
  this->pub_(S_TOTAL, roundf(this->ac_out_w_));
  this->pub_(S_REMAINING, roundf(remaining_min));
  this->pub_(S_AC_VOLT, this->out_[W_AC] ? 230.0f : 0.0f);
  this->pub_(S_CHARGE_LEVEL, 500.0f);  // ESP-FBot's raw value for knob level 2 (800 W)
}

void Fbot::publish_settings_() {
  if (this->numbers_[N_CHARGE_MAX] != nullptr)
    this->numbers_[N_CHARGE_MAX]->publish_state(this->charge_max_);
  if (this->numbers_[N_DISCHARGE_MIN] != nullptr)
    this->numbers_[N_DISCHARGE_MIN]->publish_state(this->discharge_min_);
}

void Fbot::sim_set_link_up(bool up) {
  if (up == this->link_up_)
    return;
  this->link_up_ = up;
  if (!up) {
    // The BLE stack notices a lost link at once; the data simply stops.
    ESP_LOGW(TAG, "SIM: link DOWN");
    this->pub_b_(B_CONNECTED, false);
    return;
  }
  ESP_LOGW(TAG, "SIM: link up");
  if (!this->frozen_) {
    this->publish_live_();
    this->publish_settings_();
  }
}

void Fbot::sim_set_frozen(bool frozen) {
  this->frozen_ = frozen;
  ESP_LOGW(TAG, "SIM: station data %s", frozen ? "FROZEN (link still claims up)" : "flowing");
}

void Fbot::sim_set_probe_failed(bool failed) {
  this->probe_failed_ = failed;
  ESP_LOGW(TAG, "SIM: fridge probe %s", failed ? "FAILED (silent)" : "ok");
}

void Fbot::sim_set_soc(float pct) { this->soc_ = std::max(0.0f, std::min(100.0f, pct)); }

}  // namespace fbot
}  // namespace esphome

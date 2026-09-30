#include "van_test.h"

#include <cstring>

#include <esp_http_client.h>
#include <esp_timer.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

#include "esphome/core/application.h"
#include "esphome/core/hal.h"
#include "esphome/core/helpers.h"
#include "esphome/core/log.h"

namespace esphome::van_test {

static const char *const TAG = "van_test";
static const uint32_t MAGIC = 0x56544531;  // "VTE1"; bump when Persist changes

static constexpr uint32_t MIN = 60;
static constexpr uint32_t HOUR = 3600;

// Step table. `planned` is the fixed length of timed steps, or the timeout of
// the ones that wait for a condition; it is what the display counts down to.
// Durations are UNVERIFIED choices, sized as argued in van-core-test.yaml.
struct StepInfo {
  const char *name;
  uint32_t planned_s;
  bool resumable;  // after an unexpected reboot, continue the clock (true)
                   // or restart the step from its entry action (false)
};
static const StepInfo STEPS[S_COUNT] = {
    {"ready", 15 * MIN, false},
    {"cmd off", 90, false},
    {"cmd off hold", 10 * MIN, false},
    {"cmd on", 120, false},
    {"fridge restart", 30 * MIN, false},
    {"plug off", 90, false},
    {"plug off hold", 10 * MIN, false},
    {"plug on", 90, false},
    {"fs reboot arm", 15 * MIN, false},
    {"fs reboot verify", 15 * MIN, false},
    {"fs ble arm", 15 * MIN, false},
    {"fs ble drop", 3 * MIN, false},
    {"fs ble verify", 10 * MIN, false},
    {"fs probe arm", 15 * MIN, false},
    {"fs probe stale", 10 * MIN, false},
    {"day normal", 16 * HOUR, true},
    {"night ac on 1", 3 * HOUR, true},
    {"night ac off", 4 * HOUR + 30 * MIN, true},
    {"night ac on 2", 2 * HOUR + 30 * MIN, true},
    {"recovery", 5 * HOUR, true},
    {"ab continuous 1", 2 * HOUR, true},
    {"ab cycle 30/30", 4 * HOUR, true},
    {"ab continuous 2", 2 * HOUR, true},
    {"done", 0, false},
    {"ABORTED", 0, false},
};

static const char *const TEST_NAMES[T_COUNT] = {
    "cmd_off", "off_hold", "cmd_on", "restart", "plug_off", "plug_on",
    "fs_reboot", "fs_ble_link", "fs_ble_ac", "fs_probe", "dark", "night",
};

// Station below this and the test stops spending energy on measurements.
static constexpr float SOC_FLOOR = 20.0f;
// "Dark": total input below this, continuously, for DARK_HOLD_S. Was 3 W:
// the P310's solar input reads 0-5 W of noise after sunset (test day log,
// 26 Sep), so a 3 W reading reset the window every few minutes and the night
// never started. Real dusk input is tens of watts.
static constexpr float DARK_W = 8.0f;
static constexpr uint32_t DARK_HOLD_S = 30 * MIN;
static constexpr float FRIDGE_RUNNING_W = 10.0f;

static uint32_t task_ms() { return static_cast<uint32_t>(esp_timer_get_time() / 1000); }

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

void VanTest::setup() {
  // The profiles are all written relative to what the arbiter would do on its
  // own, so they cannot drift from the production defaults.
  this->defaults_ = this->arbiter_->config();

  this->pref_ = global_preferences->make_preference<Persist>(fnv1_hash("van_test_v1"));
  Persist saved{};
  const bool have = this->pref_.load(&saved) && saved.magic == MAGIC;

  xTaskCreatePinnedToCore(VanTest::plug_task_, "van_test_plug", 4096, this, 1, nullptr, 1);

  if (!have) {
    this->p_ = Persist{};
    this->p_.magic = MAGIC;
    this->enter_(S_READY);
    return;
  }

  this->p_ = saved;
  uint8_t s = this->p_.step < S_COUNT ? this->p_.step : static_cast<uint8_t>(S_READY);
  const bool deliberate = this->p_.deliberate_reboot != 0;
  this->p_.deliberate_reboot = 0;
  if (!deliberate && s != S_READY && s != S_DONE && s != S_ABORTED) {
    this->p_.unexpected_boots++;
    ESP_LOGW(TAG, "unexpected reboot #%u during '%s'", this->p_.unexpected_boots, STEPS[s].name);
    // A test that keeps crashing the node is itself the finding. Stop testing
    // and fall back to the production behaviour.
    if (this->p_.unexpected_boots > 3) {
      this->enter_(S_ABORTED);
      return;
    }
  }
  this->enter_(s, STEPS[s].resumable ? this->p_.step_elapsed_s : 0);
}

void VanTest::loop() {
  const uint32_t now = millis();
  if (now - this->last_tick_ms_ < 1000)
    return;
  this->last_tick_ms_ = now;
  if (this->rebooting_)
    return;
  this->tick_();
  // Elapsed time for the resumable steps. Not flushed: preferences batch to
  // flash on their own interval, and losing a minute of a 4 h phase is fine.
  if (now - this->last_save_ms_ >= 5 * MIN * 1000) {
    this->last_save_ms_ = now;
    this->save_(false);
  }
}

void VanTest::dump_config() {
  ESP_LOGCONFIG(TAG, "van_test sequencer:");
  ESP_LOGCONFIG(TAG, "  step: %s (%u s in)", this->step_name(), this->step_elapsed_s());
  ESP_LOGCONFIG(TAG, "  plug: %s/switch/%s", this->plug_base_.c_str(), this->plug_relay_.c_str());
  ESP_LOGCONFIG(TAG, "  unexpected reboots: %u", this->p_.unexpected_boots);
  ESP_LOGCONFIG(TAG, "  results: %s", this->results().c_str());
}

// ---------------------------------------------------------------------------
// Steps
// ---------------------------------------------------------------------------

void VanTest::enter_(uint8_t step, uint32_t resume_s) {
  const uint32_t now = millis();
  this->step_ = step;
  this->step_start_ms_ = now - resume_s * 1000u;
  this->mark_ms_ = now;
  this->cond_ms_ = 0;
  this->flag_ = false;
  this->flag2_ = false;
  this->peak_ = 0.0f;
  this->p_.step = step;
  ESP_LOGW(TAG, "=== step %u: %s%s", step, STEPS[step].name, resume_s ? " (resumed)" : "");

  // Every step starts from the same baseline. Fault injections are undone
  // here, so no path out of a fault step - timeout, skip, abort - can leave
  // BLE disabled or the probe silent.
  if (!this->ble_enabled_) {
    this->ble_client_->set_enabled(true);
    this->ble_enabled_ = true;
  }
  if (this->probe_stopped_) {
    this->probe_poller_->start_poller();
    this->probe_stopped_ = false;
  }

  Profile prof = Profile::HOLD_ON;
  int8_t plug = 1;
  switch (step) {
    case S_CMD_OFF:
    case S_CMD_OFF_HOLD:
      prof = Profile::PARKED;
      break;
    case S_PLUG_OFF:
    case S_PLUG_OFF_HOLD:
      plug = 0;
      break;
    case S_FS_REBOOT_ARM:
    case S_FS_BLE_ARM:
    case S_FS_BLE_DROP:
    case S_FS_PROBE_ARM:
    case S_FS_PROBE_STALE:
      prof = Profile::HOLD_OFF;
      break;
    case S_DAY_NORMAL:
    case S_DONE:
      prof = Profile::NORMAL;
      break;
    case S_NIGHT_ON_1:
    case S_NIGHT_OFF:
    case S_NIGHT_ON_2:
      // Fridge disconnected for the whole night, so the AC-on phases measure
      // the inverter with nothing on it. Kept OFF through the parked phase
      // too: when AC returns the plug boots ON, and this switches it straight
      // back off. Without proven plug control the fridge stays connected and
      // the analysis has to subtract it (T_PLUG_OFF says which).
      prof = step == S_NIGHT_OFF ? Profile::PARKED : Profile::HOLD_ON;
      plug = this->p_.plug_ok ? 0 : 1;
      if (step == S_NIGHT_ON_1 && resume_s == 0)
        this->p_.value[T_NIGHT] = 0.0f;
      break;
    case S_AB_CYCLE:
      prof = Profile::CYCLE;
      break;
    case S_ABORTED:
      // If the command path itself failed, nothing that relies on it is
      // trusted: back to the status quo, inverter on continuously.
      prof = (this->p_.verdict[T_CMD_OFF] == V_FAIL || this->p_.verdict[T_CMD_ON] == V_FAIL)
                 ? Profile::HOLD_ON
                 : Profile::NORMAL;
      break;
    default:
      break;
  }
  this->plug_want_ = plug;
  this->apply_profile_(prof);

  switch (step) {
    case S_FS_BLE_DROP:
      this->ble_client_->set_enabled(false);
      this->ble_enabled_ = false;
      break;
    case S_FS_PROBE_STALE:
      this->probe_poller_->stop_poller();
      this->probe_stopped_ = true;
      break;
    default:
      break;
  }
  this->save_(true);
}

void VanTest::tick_() {
  const uint32_t el = this->step_elapsed_s();
  const uint32_t now = millis();
  const float fw = val_(this->fridge_w_);
  const float in = val_(this->in_w_);
  const float soc = val_(this->soc_);
  if (this->plug_state_ >= 0)
    this->plug_seen_ = true;

  if (this->step_ != S_DONE && this->step_ != S_ABORTED && !std::isnan(soc) && soc < SOC_FLOOR) {
    ESP_LOGE(TAG, "SOC %.1f%% below %.0f%%: stopping the test", soc, SOC_FLOOR);
    this->enter_(S_ABORTED);
    return;
  }

  switch (this->step_) {
    case S_READY:
      // Two minutes of settling, then go once the station, the probe and the
      // AC readback are all live. The plug is waited for but not required.
      if (el >= 2 * MIN && this->ble_up_() && this->ac_on_() && this->probe_->has_state() &&
          (this->plug_seen_ || el >= STEPS[S_READY].planned_s)) {
        if (!this->plug_seen_)
          ESP_LOGW(TAG, "plug relay never answered: relay steps will be skipped");
        this->enter_(S_CMD_OFF);
      }
      break;

    // --- 1. The command path: can this node turn the inverter off and back
    // on, and does the station say so? Everything else depends on it. ---
    case S_CMD_OFF:
      if (this->ble_up_() && this->ac_off_()) {
        this->set_result_(T_CMD_OFF, V_PASS, el);
        this->enter_(S_CMD_OFF_HOLD);
      } else if (el >= STEPS[S_CMD_OFF].planned_s) {
        this->set_result_(T_CMD_OFF, V_FAIL, NAN);
        this->enter_(S_ABORTED);
      }
      break;

    case S_CMD_OFF_HOLD:
      // The station must keep AC off on its own, and the socket must really be
      // dead: the plug going unreachable is the independent witness.
      if (this->ac_on_())
        this->flag2_ = true;
      if (el >= MIN && this->plug_state_ < 0)
        this->flag_ = true;
      if (el >= STEPS[S_CMD_OFF_HOLD].planned_s) {
        this->set_result_(T_OFF_HOLD, this->flag2_ ? V_FAIL : V_PASS, this->flag_ ? 1.0f : 0.0f);
        this->enter_(S_CMD_ON);
      }
      break;

    case S_CMD_ON:
      if (this->ac_on_()) {
        this->set_result_(T_CMD_ON, V_PASS, el);
        this->enter_(S_FRIDGE_RESTART);
      } else if (el >= STEPS[S_CMD_ON].planned_s) {
        // AC stuck off. ABORTED holds AC on and reboots the node if the
        // station still disagrees after ten minutes.
        this->set_result_(T_CMD_ON, V_FAIL, NAN);
        this->enter_(S_ABORTED);
      }
      break;

    case S_FRIDGE_RESTART: {
      // Power comes from the plug's meter, which works on the old plug
      // firmware too; only the relay steps need van-fridge-plug-test.yaml.
      bool done = true;
      if (!std::isnan(fw) && fw > FRIDGE_RUNNING_W) {
        this->set_result_(T_RESTART, V_PASS, el);
      } else if (std::isnan(fw) && el >= 5 * MIN) {
        this->set_result_(T_RESTART, V_SKIP);  // no meter reading at all
      } else if (el >= STEPS[S_FRIDGE_RESTART].planned_s) {
        this->set_result_(T_RESTART, V_FAIL, NAN);
      } else {
        done = false;
      }
      if (done) {
        if (this->plug_seen_) {
          this->enter_(S_PLUG_OFF);
        } else {
          ESP_LOGW(TAG, "plug relay not reachable (old plug firmware?): relay steps skipped");
          this->set_result_(T_PLUG_OFF, V_SKIP);
          this->set_result_(T_PLUG_ON, V_SKIP);
          this->enter_(S_FS_REBOOT_ARM);
        }
      }
      break;
    }

    // --- 2. Plug relay control, needed to unload the inverter at night. ---
    case S_PLUG_OFF:
      if (this->plug_state_ == 0 && !std::isnan(fw) && fw < 2.0f) {
        this->p_.plug_ok = 1;
        this->set_result_(T_PLUG_OFF, V_PASS, el);
        this->enter_(S_PLUG_OFF_HOLD);
      } else if (el >= STEPS[S_PLUG_OFF].planned_s) {
        this->p_.plug_ok = 0;
        this->set_result_(T_PLUG_OFF, V_FAIL, NAN);
        this->enter_(S_PLUG_ON);
      }
      break;

    case S_PLUG_OFF_HOLD:
      if (el >= STEPS[S_PLUG_OFF_HOLD].planned_s)
        this->enter_(S_PLUG_ON);
      break;

    case S_PLUG_ON:
      if (this->plug_state_ == 1) {
        this->set_result_(T_PLUG_ON, V_PASS, el);
        this->enter_(S_FS_REBOOT_ARM);
      } else if (el >= STEPS[S_PLUG_ON].planned_s) {
        // The lease brings it back within 4 h regardless; just do not trust
        // it for the night.
        this->p_.plug_ok = 0;
        this->set_result_(T_PLUG_ON, V_FAIL, NAN);
        this->enter_(S_FS_REBOOT_ARM);
      }
      break;

    // --- 3. Fail-safes, each started from AC OFF (section 11). ---
    // Reboot mid-OFF-block: the boot force-on must bring AC back.
    case S_FS_REBOOT_ARM:
      if (!this->flag_ && this->ble_up_() && this->ac_off_()) {
        this->flag_ = true;
        this->mark_ms_ = now;
      }
      if (this->flag_ && since_(this->mark_ms_) >= 2 * MIN) {
        this->deliberate_reboot_(S_FS_REBOOT_VERIFY);
      } else if (!this->flag_ && el >= STEPS[S_FS_REBOOT_ARM].planned_s) {
        this->set_result_(T_FS_REBOOT, V_FAIL, NAN);
        this->enter_(S_FS_BLE_ARM);
      }
      break;

    case S_FS_REBOOT_VERIFY: {
      // Counted from boot, not from step entry: this is the whole outage.
      const uint32_t up_s = now / 1000u;
      if (this->ac_on_()) {
        this->set_result_(T_FS_REBOOT, up_s <= 180 ? V_PASS : V_FAIL, up_s);
        this->enter_(S_FS_BLE_ARM);
      } else if (el >= STEPS[S_FS_REBOOT_VERIFY].planned_s) {
        this->set_result_(T_FS_REBOOT, V_FAIL, NAN);
        this->enter_(S_FS_BLE_ARM);
      }
      break;
    }

    // Link lost mid-OFF-block, and a reason for AC arises while it is down
    // (simulated by switching to HOLD_ON). On reconnect the arbiter must
    // re-send at once, not on its next one-minute re-assert (ac_arbiter.cpp,
    // the written_once_ reset). This is the recoverable half of section 5.2.
    case S_FS_BLE_ARM:
      if (!this->flag_ && this->ble_up_() && this->ac_off_()) {
        this->flag_ = true;
        this->mark_ms_ = now;
      }
      if (this->flag_ && since_(this->mark_ms_) >= MIN) {
        this->enter_(S_FS_BLE_DROP);
      } else if (!this->flag_ && el >= STEPS[S_FS_BLE_ARM].planned_s) {
        this->set_result_(T_FS_BLE_LINK, V_FAIL, NAN);
        this->set_result_(T_FS_BLE_AC, V_SKIP);
        this->enter_(S_FS_PROBE_ARM);
      }
      break;

    case S_FS_BLE_DROP:
      if (!this->ble_up_())
        this->flag2_ = true;  // the stack really let go
      if (!this->flag_ && el >= 2 * MIN) {
        this->flag_ = true;
        this->apply_profile_(Profile::HOLD_ON);
      }
      if (el >= STEPS[S_FS_BLE_DROP].planned_s) {
        if (!this->flag2_) {
          ESP_LOGW(TAG, "BLE never reported down: drop test invalid");
          this->set_result_(T_FS_BLE_LINK, V_SKIP);
          this->set_result_(T_FS_BLE_AC, V_SKIP);
          this->enter_(S_FS_PROBE_ARM);
        } else {
          this->enter_(S_FS_BLE_VERIFY);  // re-enables BLE, keeps HOLD_ON
        }
      }
      break;

    case S_FS_BLE_VERIFY:
      if (!this->flag_ && this->ble_up_()) {
        this->flag_ = true;
        this->cond_ms_ = now;
        const uint32_t s = el;
        this->set_result_(T_FS_BLE_LINK, s <= 120 ? V_PASS : V_FAIL, s);
      }
      if (this->flag_) {
        const uint32_t s = since_(this->cond_ms_);
        if (this->ac_on_()) {
          // 60 s is the re-assert period: anything near it means the
          // reconnect edge did not trigger a write.
          this->set_result_(T_FS_BLE_AC, s <= 30 ? V_PASS : V_FAIL, s);
          this->enter_(S_FS_PROBE_ARM);
        } else if (s >= 5 * MIN) {
          this->set_result_(T_FS_BLE_AC, V_FAIL, NAN);
          this->enter_(S_FS_PROBE_ARM);
        }
      } else if (el >= STEPS[S_FS_BLE_VERIFY].planned_s) {
        // BLE will not come back by itself. A boot did bring it back in M12,
        // and boot force-on restores AC as well.
        this->set_result_(T_FS_BLE_LINK, V_FAIL, NAN);
        this->set_result_(T_FS_BLE_AC, V_SKIP);
        this->deliberate_reboot_(S_FS_PROBE_ARM);
      }
      break;

    // Probe goes silent mid-OFF-block: stale probe must force AC on. The
    // poller is stopped rather than the wire pulled, so this proves the
    // software path, not the 1-Wire bus's behaviour when unplugged.
    case S_FS_PROBE_ARM:
      if (!this->flag_ && this->ble_up_() && this->ac_off_()) {
        this->flag_ = true;
        this->mark_ms_ = now;
      }
      if (this->flag_ && since_(this->mark_ms_) >= MIN) {
        this->enter_(S_FS_PROBE_STALE);
      } else if (!this->flag_ && el >= STEPS[S_FS_PROBE_ARM].planned_s) {
        this->set_result_(T_FS_PROBE, V_FAIL, NAN);
        this->enter_(S_DAY_NORMAL);
      }
      break;

    case S_FS_PROBE_STALE:
      if (this->ac_on_()) {
        const bool right_reason = strcmp(this->arbiter_->reason(), "probe stale") == 0;
        if (!right_reason)
          ESP_LOGW(TAG, "AC came on for '%s', not 'probe stale'", this->arbiter_->reason());
        // Expected ~5 min 45 s: 45 s to go invalid plus the 5 min stale limit.
        this->set_result_(T_FS_PROBE, right_reason && el <= 9 * MIN ? V_PASS : V_FAIL, el);
        this->enter_(S_DAY_NORMAL);
      } else if (el >= STEPS[S_FS_PROBE_STALE].planned_s) {
        this->set_result_(T_FS_PROBE, V_FAIL, NAN);
        this->enter_(S_DAY_NORMAL);
      }
      break;

    // --- 4. The production scheduler until dark. There is no clock to trust,
    // so "night" is the station seeing no input for half an hour. ---
    case S_DAY_NORMAL:
      if (!std::isnan(in)) {
        if (in < DARK_W) {
          if (this->cond_ms_ == 0)
            this->cond_ms_ = now;
        } else {
          this->cond_ms_ = 0;
        }
      }
      if (el >= 30 * MIN && this->cond_ms_ != 0 && since_(this->cond_ms_) >= DARK_HOLD_S) {
        this->set_result_(T_DARK, V_PASS, since_(this->cond_ms_));
        this->enter_(S_NIGHT_ON_1);
      } else if (el >= STEPS[S_DAY_NORMAL].planned_s) {
        this->set_result_(T_DARK, V_FAIL, NAN);
        this->enter_(S_NIGHT_ON_1);
      }
      break;

    // --- 5. Idle split, SOC-slope method. ON-OFF-ON so a drift in the
    // station's own draw (temperature, SOC curvature) shows up as a
    // difference between the two ON phases instead of biasing the answer. ---
    case S_NIGHT_ON_1:
    case S_NIGHT_OFF:
    case S_NIGHT_ON_2:
      if (!std::isnan(in) && in > this->p_.value[T_NIGHT])
        this->p_.value[T_NIGHT] = in;
      if (el >= STEPS[this->step_].planned_s) {
        if (this->step_ == S_NIGHT_ON_2) {
          const float peak = this->p_.value[T_NIGHT];
          // Above the 0-5 W night-time noise on the solar reading (see DARK_W).
          this->set_result_(T_NIGHT, peak < DARK_W ? V_PASS : V_FAIL, peak);
        }
        this->enter_(this->step_ + 1);
      }
      break;

    // --- 6. Pull the (now warm) cabinet back down, then the pulldown A/B:
    // continuous / imposed 30-30 / continuous, symmetric about the middle so a
    // linear ambient drift cancels. Fridge energy is on the plug column. ---
    case S_RECOVERY:
      // Done when the thermostat is satisfied: a natural stop, 10 min long.
      if (!std::isnan(fw) && fw < 5.0f) {
        if (this->cond_ms_ == 0)
          this->cond_ms_ = now;
      } else {
        this->cond_ms_ = 0;
      }
      if ((el >= 90 * MIN && this->cond_ms_ != 0 && since_(this->cond_ms_) >= 10 * MIN) ||
          el >= STEPS[S_RECOVERY].planned_s)
        this->enter_(S_AB_CONT_1);
      break;

    case S_AB_CONT_1:
    case S_AB_CYCLE:
    case S_AB_CONT_2:
      if (el >= STEPS[this->step_].planned_s)
        this->enter_(this->step_ + 1);
      break;

    // --- terminal: the station must follow the arbiter. If it has not for
    // ten minutes with the link up, reboot (at most three times). ---
    case S_DONE:
    case S_ABORTED:
      if (this->ble_up_() && this->ac_known_() && this->arbiter_->ac_on() != this->ac_active_->state) {
        if (this->cond_ms_ == 0)
          this->cond_ms_ = now;
      } else {
        this->cond_ms_ = 0;
      }
      if (this->cond_ms_ != 0 && since_(this->cond_ms_) >= 10 * MIN && this->p_.reboots_in_abort < 3) {
        this->p_.reboots_in_abort++;
        ESP_LOGE(TAG, "station disagrees with the arbiter for 10 min: reboot %u/3",
                 this->p_.reboots_in_abort);
        this->deliberate_reboot_(this->step_);
      }
      break;

    default:
      break;
  }
}

// Every profile starts from the arbiter's own defaults, and only NORMAL is
// left untouched. Nothing here writes the AC switch: the arbiter does.
void VanTest::apply_profile_(Profile p) {
  van::ArbiterConfig &c = this->arbiter_->config();
  c = this->defaults_;
  const uint32_t day = 48u * HOUR * 1000u;
  switch (p) {
    case Profile::NORMAL:
    case Profile::PARKED:
      break;
    case Profile::HOLD_ON:
      // Always "too warm", never "cold", the block never times out, and the
      // compressor never counts as stopped (it will be, with the plug off).
      c.temp_on_c = -40.0f;
      c.temp_off_c = -50.0f;
      c.run_block_ms = day;
      c.compressor_idle_w = -1000.0f;
      c.min_off_ms = 0;  // coming from an OFF step must not wait out a lockout
      break;
    case Profile::HOLD_OFF:
      // Never too warm, always "cold", so the arbiter releases through its
      // ordinary path on the next tick. Fail-safes stay armed. The hard
      // override is raised out of reach: water bottles, not food.
      c.temp_on_c = 50.0f;
      c.temp_off_c = 50.0f;
      c.temp_hard_c = 60.0f;
      c.rest_block_ms = day;
      c.min_on_ms = 0;
      break;
    case Profile::CYCLE:
      // Pure fixed blocks: no ceiling, no floor, no compressor-stop exit, so
      // the A/B compares imposed 30/30 against continuous and nothing else.
      c.temp_on_c = 50.0f;
      c.temp_off_c = -50.0f;
      c.temp_hard_c = 60.0f;
      c.compressor_idle_w = -1000.0f;
      c.run_block_ms = 30u * MIN * 1000u;
      c.rest_block_ms = 30u * MIN * 1000u;
      break;
  }
  // CYCLE must start from a clean request. A warm cabinet (the night coast)
  // latches the 10 C hard override during recovery, and CYCLE's -50 C floor
  // means that latch can never release: the 26-29 Sep run held AC on through
  // the whole "30/30" block. One pass through parked clears every latch; the
  // exit re-arms the 60 s boot force-on, then the blocks start.
  if (p == Profile::CYCLE && !this->arbiter_->parked()) {
    this->arbiter_->set_parked(true);
    this->arbiter_->set_parked(false);
    return;
  }
  const bool park = p == Profile::PARKED;
  if (park != this->arbiter_->parked())
    this->arbiter_->set_parked(park);  // runs an arbiter update
  else
    this->arbiter_->update();
}

void VanTest::set_result_(Test t, Verdict v, float value) {
  this->p_.verdict[t] = v;
  this->p_.value[t] = value;
  static const char *const V[] = {"-", "PASS", "FAIL", "SKIP"};
  ESP_LOGW(TAG, "RESULT %s: %s %.1f", TEST_NAMES[t], V[v], value);
  this->save_(true);
}

void VanTest::save_(bool flush) {
  this->p_.step_elapsed_s = this->step_elapsed_s();
  this->pref_.save(&this->p_);
  if (flush)
    global_preferences->sync();
}

void VanTest::deliberate_reboot_(uint8_t resume_step) {
  ESP_LOGW(TAG, "deliberate reboot, resuming at '%s'", STEPS[resume_step].name);
  this->rebooting_ = true;
  this->p_.step = resume_step;
  this->p_.deliberate_reboot = 1;
  this->p_.step_elapsed_s = 0;
  this->pref_.save(&this->p_);
  global_preferences->sync();
  this->set_timeout("reboot", 500, []() { App.safe_reboot(); });
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

void VanTest::restart_test() {
  this->p_ = Persist{};
  this->p_.magic = MAGIC;
  this->enter_(S_READY);
}

void VanTest::skip_step() {
  if (this->step_ < S_DONE)
    this->enter_(this->step_ + 1);
}

void VanTest::abort_test() {
  if (this->step_ != S_ABORTED)
    this->enter_(S_ABORTED);
}

// ---------------------------------------------------------------------------
// Readouts
// ---------------------------------------------------------------------------

const char *VanTest::step_name() const { return STEPS[this->step_].name; }

uint32_t VanTest::step_elapsed_s() const { return (millis() - this->step_start_ms_) / 1000u; }

uint32_t VanTest::step_planned_s() const { return STEPS[this->step_].planned_s; }

float VanTest::plug_relay() const {
  const int8_t s = this->plug_state_;
  return s < 0 ? NAN : static_cast<float>(s);
}

std::string VanTest::results() const {
  static const char *const V[] = {"-", "P", "F", "S"};
  std::string out;
  char b[40];
  for (uint8_t t = 0; t < T_COUNT; t++) {
    const float v = this->p_.value[t];
    if (this->p_.verdict[t] == V_NOT_RUN)
      snprintf(b, sizeof(b), "%s%s -", t ? " | " : "", TEST_NAMES[t]);
    else if (std::isnan(v))
      snprintf(b, sizeof(b), "%s%s %s", t ? " | " : "", TEST_NAMES[t], V[this->p_.verdict[t]]);
    else
      snprintf(b, sizeof(b), "%s%s %s %.0f", t ? " | " : "", TEST_NAMES[t], V[this->p_.verdict[t]], v);
    out += b;
  }
  return out;
}

uint8_t VanTest::passes() const {
  uint8_t n = 0;
  for (uint8_t v : this->p_.verdict)
    n += v == V_PASS;
  return n;
}

uint8_t VanTest::fails() const {
  uint8_t n = 0;
  for (uint8_t v : this->p_.verdict)
    n += v == V_FAIL;
  return n;
}

// ---------------------------------------------------------------------------
// Plug - its own task
// ---------------------------------------------------------------------------

// The plug's REST API: GET  /switch/<name>          {"value":true,...}
//                      POST /switch/<name>/turn_on|turn_off
static std::string encode_name(const std::string &n) {
  std::string o;
  for (char ch : n) {
    if (ch == ' ')
      o += "%20";
    else
      o += ch;
  }
  return o;
}

int8_t VanTest::plug_get_() {
  const std::string url = this->plug_base_ + "/switch/" + encode_name(this->plug_relay_);
  esp_http_client_config_t cfg{};
  cfg.url = url.c_str();
  cfg.timeout_ms = 2000;
  cfg.buffer_size = 512;
  cfg.disable_auto_redirect = true;
  esp_http_client_handle_t c = esp_http_client_init(&cfg);
  if (c == nullptr)
    return -1;
  int8_t res = -1;
  char body[256];
  if (esp_http_client_open(c, 0) == ESP_OK) {
    esp_http_client_fetch_headers(c);
    if (esp_http_client_get_status_code(c) == 200) {
      int len = 0;
      while (len < (int) sizeof(body) - 1) {
        int r = esp_http_client_read(c, body + len, sizeof(body) - 1 - len);
        if (r <= 0)
          break;
        len += r;
      }
      body[len] = '\0';
      if (strstr(body, "\"value\":true") != nullptr)
        res = 1;
      else if (strstr(body, "\"value\":false") != nullptr)
        res = 0;
    }
  }
  esp_http_client_close(c);
  esp_http_client_cleanup(c);
  return res;
}

bool VanTest::plug_post_(bool on) {
  const std::string url =
      this->plug_base_ + "/switch/" + encode_name(this->plug_relay_) + (on ? "/turn_on" : "/turn_off");
  esp_http_client_config_t cfg{};
  cfg.url = url.c_str();
  cfg.timeout_ms = 2000;
  cfg.method = HTTP_METHOD_POST;
  cfg.disable_auto_redirect = true;
  esp_http_client_handle_t c = esp_http_client_init(&cfg);
  if (c == nullptr)
    return false;
  bool ok = false;
  if (esp_http_client_open(c, 0) == ESP_OK) {
    esp_http_client_fetch_headers(c);
    ok = esp_http_client_get_status_code(c) == 200;
  }
  esp_http_client_close(c);
  esp_http_client_cleanup(c);
  return ok;
}

void VanTest::plug_task_(void *arg) {
  auto *self = static_cast<VanTest *>(arg);
  for (;;) {
    int8_t s = self->plug_get_();
    const int8_t want = self->plug_want_;
    if (s >= 0 && s != want) {
      ESP_LOGI(TAG, "plug relay %s -> %s", s ? "on" : "off", want ? "on" : "off");
      if (self->plug_post_(want == 1)) {
        const int8_t s2 = self->plug_get_();
        if (s2 >= 0)
          s = s2;
      }
    }
    if (s >= 0)
      self->plug_ok_ms_ = task_ms();
    self->plug_state_ = s;
    vTaskDelay(pdMS_TO_TICKS(3000));
  }
}

}  // namespace esphome::van_test

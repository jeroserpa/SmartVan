// van_test.h - unattended test-day sequencer for van-core (van-core-test.yaml).
//
// Runs every test that needs the inverter commanded, with nobody in the van,
// and logs each phase into soak_log so tools can cut the CSV by step.
//
// It is NOT a second writer of the AC switch (CLAUDE.md section 5.3). The real
// ac_arbiter stays the single writer; this component only
//   - rewrites the arbiter's tunables (config()) into a few test profiles, and
//   - parks / un-parks it,
// so every AC transition in the test goes through the production code path,
// fail-safes included. The fridge plug is the only thing it drives directly,
// and the plug firmware (van-fridge-plug-test.yaml) turns itself back on after
// a 4 h lease whatever this component does.
//
// Profiles, all derived from the arbiter's defaults:
//   NORMAL    the production scheduler, untouched (10 C hard override live)
//   HOLD_ON   AC on continuously; the fridge runs on its own thermostat
//   HOLD_OFF  AC off through the arbiter's ordinary release, NOT parked, so
//             the fail-safes stay armed - the state the fail-safe tests start
//             from (section 11: "test it from AC OFF")
//   CYCLE     fixed 30/30 blocks, no temperature exits: the pulldown A/B
//   PARKED    AC off with the fail-safe disarmed: the idle-measurement phase
//
// HOLD_OFF and CYCLE raise the 10 C hard override out of reach. Correct only
// because the cabinet holds water bottles, not food; the test is refused
// nothing on that basis, so do not run this firmware with food inside.
//
// Progress survives reboots (flash preferences): a deliberate reboot is a
// test step, an unexpected one resumes the current step where it left off,
// and more than three unexpected ones abort to NORMAL.

#pragma once

#include <atomic>
#include <cmath>
#include <string>

#include "esphome/components/ac_arbiter/ac_arbiter.h"
#include "esphome/components/ble_client/ble_client.h"
#include "esphome/components/binary_sensor/binary_sensor.h"
#include "esphome/components/sensor/sensor.h"
#include "esphome/core/component.h"
#include "esphome/core/preferences.h"

namespace esphome::van_test {

enum Step : uint8_t {
  S_READY = 0,
  S_CMD_OFF,
  S_CMD_OFF_HOLD,
  S_CMD_ON,
  S_FRIDGE_RESTART,
  S_PLUG_OFF,
  S_PLUG_OFF_HOLD,
  S_PLUG_ON,
  S_FS_REBOOT_ARM,
  S_FS_REBOOT_VERIFY,
  S_FS_BLE_ARM,
  S_FS_BLE_DROP,
  S_FS_BLE_VERIFY,
  S_FS_PROBE_ARM,
  S_FS_PROBE_STALE,
  S_DAY_NORMAL,
  S_NIGHT_ON_1,
  S_NIGHT_OFF,
  S_NIGHT_ON_2,
  S_RECOVERY,
  S_AB_CONT_1,
  S_AB_CYCLE,
  S_AB_CONT_2,
  S_DONE,
  S_ABORTED,
  S_COUNT
};

// One result slot per check. The CSV carries the step column; these are the
// verdicts, so the day can be read off the web UI without the log.
enum Test : uint8_t {
  T_CMD_OFF = 0,   // parked -> station reports AC off; value = latency s
  T_OFF_HOLD,      // AC stayed off 10 min, socket went dark; value = plug dark (1/0)
  T_CMD_ON,        // un-park -> station reports AC on; value = latency s
  T_RESTART,       // fridge draws power again after a cut; value = s from AC on
  T_PLUG_OFF,      // plug relay off, fridge at 0 W; value = latency s
  T_PLUG_ON,       // plug relay back on; value = latency s
  T_FS_REBOOT,     // reboot during an OFF block -> AC on; value = s from boot
  T_FS_BLE_LINK,   // BLE re-enabled -> connected; value = s
  T_FS_BLE_AC,     // connected -> AC on (reconnect re-write); value = s
  T_FS_PROBE,      // probe silent during an OFF block -> AC on; value = s
  T_DARK,          // night started on darkness (PASS) or on timeout (FAIL)
  T_NIGHT,         // no charging input during the idle phases; value = max W
  T_COUNT
};

enum Verdict : uint8_t { V_NOT_RUN = 0, V_PASS, V_FAIL, V_SKIP };

enum class Profile : uint8_t { NORMAL, HOLD_ON, HOLD_OFF, CYCLE, PARKED };

class VanTest : public Component {
 public:
  void set_arbiter(ac_arbiter::AcArbiter *a) { arbiter_ = a; }
  void set_ac_active(binary_sensor::BinarySensor *b) { ac_active_ = b; }
  void set_ble_connected(binary_sensor::BinarySensor *b) { ble_connected_ = b; }
  void set_ble_client(ble_client::BLEClient *c) { ble_client_ = c; }
  void set_fridge_probe(sensor::Sensor *s, PollingComponent *poller) {
    probe_ = s;
    probe_poller_ = poller;
  }
  void set_fridge_power(sensor::Sensor *s) { fridge_w_ = s; }
  void set_input_power(sensor::Sensor *s) { in_w_ = s; }
  void set_battery_level(sensor::Sensor *s) { soc_ = s; }
  void set_plug_base_url(const std::string &u) { plug_base_ = u; }
  void set_plug_relay_name(const std::string &n) { plug_relay_ = n; }

  void setup() override;
  void loop() override;
  void dump_config() override;
  // After the arbiter's setup(), so its defaults exist to copy.
  float get_setup_priority() const override { return setup_priority::LATE; }

  // --- web UI buttons ---
  void restart_test();
  void skip_step();
  void abort_test();

  // --- for display, template sensors and the log ---
  uint8_t step() const { return step_; }
  const char *step_name() const;
  uint32_t step_elapsed_s() const;
  uint32_t step_planned_s() const;  // 0 = open-ended
  float plug_relay() const;         // 1 on, 0 off, NAN unknown/unreachable
  bool ble_enabled() const { return ble_enabled_; }
  std::string results() const;
  uint8_t passes() const;
  uint8_t fails() const;

 protected:
  struct Persist {
    uint32_t magic;
    uint8_t step;
    uint8_t unexpected_boots;
    uint8_t deliberate_reboot;
    uint8_t reboots_in_abort;
    uint32_t step_elapsed_s;
    uint8_t verdict[T_COUNT];
    float value[T_COUNT];
    uint8_t plug_ok;  // relay control proven, so the night uses it
  };

  void enter_(uint8_t step, uint32_t resume_s = 0);
  void tick_();
  void apply_profile_(Profile p);
  void set_result_(Test t, Verdict v, float value = NAN);
  void save_(bool flush);
  void deliberate_reboot_(uint8_t resume_step);

  bool ac_known_() const { return ac_active_ != nullptr && ac_active_->has_state(); }
  bool ac_on_() const { return ac_known_() && ac_active_->state; }
  bool ac_off_() const { return ac_known_() && !ac_active_->state; }
  bool ble_up_() const { return ble_connected_ != nullptr && ble_connected_->state; }
  static float val_(sensor::Sensor *s) { return (s != nullptr && s->has_state()) ? s->state : NAN; }
  uint32_t since_(uint32_t mark) const { return (millis() - mark) / 1000u; }

  // Plug control runs on its own task: a plug that is unpowered (AC off) costs
  // that task a timeout and costs the BLE loop nothing.
  static void plug_task_(void *arg);
  int8_t plug_get_();              // 1/0, -1 unreachable
  bool plug_post_(bool on);

  ac_arbiter::AcArbiter *arbiter_{nullptr};
  binary_sensor::BinarySensor *ac_active_{nullptr};
  binary_sensor::BinarySensor *ble_connected_{nullptr};
  ble_client::BLEClient *ble_client_{nullptr};
  sensor::Sensor *probe_{nullptr};
  PollingComponent *probe_poller_{nullptr};
  sensor::Sensor *fridge_w_{nullptr};
  sensor::Sensor *in_w_{nullptr};
  sensor::Sensor *soc_{nullptr};
  std::string plug_base_;
  std::string plug_relay_;

  van::ArbiterConfig defaults_;
  ESPPreferenceObject pref_;
  Persist p_{};

  uint8_t step_{S_READY};
  uint32_t step_start_ms_{0};
  uint32_t last_tick_ms_{0};
  uint32_t last_save_ms_{0};
  bool ble_enabled_{true};
  bool probe_stopped_{false};
  bool plug_seen_{false};   // the plug answered at least once this boot
  bool rebooting_{false};

  // per-step scratch, reset by enter_()
  uint32_t mark_ms_{0};      // step-specific timestamp
  uint32_t cond_ms_{0};      // start of a "continuously true" window, 0 = not in one
  bool flag_{false};         // step-specific
  bool flag2_{false};
  float peak_{0.0f};

  std::atomic<int8_t> plug_want_{1};   // 1 on, 0 off
  std::atomic<int8_t> plug_state_{-1}; // 1 on, 0 off, -1 unknown
  std::atomic<uint32_t> plug_ok_ms_{0};
};

}  // namespace esphome::van_test

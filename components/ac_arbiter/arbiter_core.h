// arbiter_core.h - the AC inverter arbiter state machine, CLAUDE.md section 6.
//
// Deliberately free of every ESPHome header so it host-compiles and unit-tests
// without hardware (CLAUDE.md section 11, "Firmware architecture"). The ESPHome
// component in ac_arbiter.h is a thin adapter over this class: it does sensor
// plumbing and nothing else. All control decisions live here.
//
// Time is passed in as a caller-supplied millisecond counter. All comparisons
// use wrap-safe unsigned subtraction, so the 49.7-day uint32 rollover is a
// non-event; a van node is expected to run for months between reboots.

#pragma once

#include <cstdint>

namespace van {

// ---------------------------------------------------------------------------
// Tunables. Every one of these is exposed as an ESPHome `number` in YAML -
// field tuning must never require a laptop and a reflash (CLAUDE.md section 11).
// Values here are the power-on defaults, not magic constants.
// ---------------------------------------------------------------------------
struct ArbiterConfig {
  // --- fridge thermostat ---
  float temp_on_c = 7.0f;    // request cooling above this
  float temp_off_c = 4.0f;   // release below this
  float temp_hard_c = 10.0f; // hard override: cool regardless of everything

  // --- sleep mode / drive inhibit coasting (section 6 "Sleep mode") ---
  float sleep_ceiling_c = 6.0f; // raised ceiling while coasting
  float sleep_target_c = 1.0f;  // when a coast cycle does run, go all the way down

  // --- compressor-satisfied detection ---
  // The tail between compressor stop and inverter shutdown is pure idle waste,
  // incurred every cycle; 90s is debounce, not caution. Push it lower once the
  // 24h log exists.
  float compressor_idle_w = 15.0f;
  uint32_t compressor_idle_ms = 90u * 1000u;

  // --- cycle shaping ---
  uint32_t min_on_ms = 10u * 60u * 1000u; // do not release before this
  uint32_t min_off_ms = 5u * 60u * 1000u; // anti-short-cycle

  // --- manual (cooking) button ---
  uint32_t manual_initial_ms = 45u * 60u * 1000u;
  uint32_t manual_extend_ms = 30u * 60u * 1000u;
  uint32_t manual_max_ms = 3u * 60u * 60u * 1000u;
  float manual_release_w = 150.0f; // above fridge (35W), below any cooking load
  uint32_t manual_release_ms = 10u * 60u * 1000u;
  uint32_t manual_grace_ms = 10u * 60u * 1000u;  // no auto-release before this
  uint32_t manual_warn_ms = 2u * 60u * 1000u;    // buzzer lead-in

  // --- opportunistic surplus ---
  float surplus_margin_w = 200.0f;
  float surplus_soc_pct = 85.0f;
  uint32_t surplus_min_on_ms = 10u * 60u * 1000u; // do not flap on cloud edges

  // --- fail-safe ---
  uint32_t boot_force_ms = 60u * 1000u;      // hold AC on after boot
  uint32_t temp_stale_ms = 5u * 60u * 1000u; // stale probe => force on
  uint32_t watchdog_ms = 30u * 1000u;        // tick starvation => force on

  // --- drive inhibit (Phase 4, default off; suppressor, never a request) ---
  uint32_t inhibit_max_ms = 4u * 60u * 60u * 1000u; // stuck-true must expire
};

// Everything the arbiter is allowed to know about the outside world.
// `*_valid` flags are the honest expression of sensor staleness: an invalid
// input never argues for OFF.
struct ArbiterInputs {
  bool ble_connected = false;

  bool temp_valid = false;
  float fridge_temp_c = 0.0f;

  bool power_valid = false;
  float output_power_w = 0.0f;

  bool input_power_valid = false;
  float input_power_w = 0.0f;

  bool soc_valid = false;
  float soc_pct = 0.0f;

  bool sleep_mode = false;
  bool drive_inhibit = false;
};

// Why the inverter is on. Ordered by precedence, and shown on the display -
// "the inverter is on" without a reason is an undebuggable system.
enum class AcReason : uint8_t {
  OFF = 0,
  BOOT,
  BLE_LOST,
  TEMP_STALE,
  WATCHDOG,
  FRIDGE_HARD, // temp above the hard override
  FRIDGE,
  MANUAL,
  SURPLUS,
};

struct ArbiterOutputs {
  bool ac_on = true; // fail toward powered, from the very first instant
  bool force_on = true;
  bool fridge_req = false;
  bool fridge_hard = false;  // cooling because of the 10 C override; outranks the inhibit
  bool manual_req = false;
  bool surplus_req = false;
  bool inhibit_active = false; // drive inhibit, after its hard timeout
  bool manual_warning = false; // within manual_warn_ms of auto-release
  uint32_t manual_remaining_s = 0;
  AcReason reason = AcReason::BOOT;
};

const char *ac_reason_str(AcReason r);

class ArbiterCore {
 public:
  // now_ms must be monotonic. Call once before any tick() so boot timers have
  // an origin; the ESPHome adapter does this in setup().
  void begin(uint32_t now_ms);

  // Run one arbitration pass. Safe to call at any rate; 5s is the design point
  // (CLAUDE.md section 6) but the logic is rate-independent.
  const ArbiterOutputs &tick(uint32_t now_ms, const ArbiterInputs &in);

  // --- button events, edge-triggered from the ESPHome binary_sensor ---
  void manual_press(uint32_t now_ms);  // short press: arm, or extend
  void manual_cancel(uint32_t now_ms); // long press: drop immediately

  ArbiterConfig &config() { return cfg_; }
  const ArbiterOutputs &outputs() const { return out_; }

 private:
  void update_fridge_(uint32_t now_ms, const ArbiterInputs &in);
  void update_manual_(uint32_t now_ms, const ArbiterInputs &in);
  void update_surplus_(uint32_t now_ms, const ArbiterInputs &in);

  ArbiterConfig cfg_;
  ArbiterOutputs out_;

  bool started_ = false;
  uint32_t boot_ms_ = 0;
  uint32_t last_tick_ms_ = 0;

  // fridge
  uint32_t fridge_since_ms_ = 0;    // last fridge_req transition
  uint32_t temp_fresh_ms_ = 0;      // last time temp_valid was true
  uint32_t compressor_busy_ms_ = 0; // last time output_power was above idle

  // manual
  uint32_t manual_start_ms_ = 0;
  uint32_t manual_deadline_ms_ = 0;
  uint32_t manual_busy_ms_ = 0; // last time output_power was above release floor

  // surplus
  uint32_t surplus_since_ms_ = 0;

  // drive inhibit
  bool inhibit_seen_ = false;
  uint32_t inhibit_since_ms_ = 0;
};

// Wrap-safe "has at least `span` elapsed since `mark`".
inline bool elapsed(uint32_t now, uint32_t mark, uint32_t span) {
  return static_cast<uint32_t>(now - mark) >= span;
}

}  // namespace van

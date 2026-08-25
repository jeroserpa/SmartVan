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

  // --- opportunistic early release (ANALYSIS 4.2 "Strategy A") ---
  // The compressor genuinely stopping is worth acting on, but it is NOT the
  // release condition. This fridge has a variable-speed inverter compressor: it
  // modulates for hours and at high ambient never stops at all, so a release
  // that *required* a quiet compressor could never fire and latched fridge_req
  // true forever (ANALYSIS 4.1). It is now one of several ways a run block can
  // end, never the only one.
  float compressor_idle_w = 15.0f;
  uint32_t compressor_idle_ms = 90u * 1000u;

  // --- block schedule (ANALYSIS 4.2 "Strategy B") ---
  // The supervisor picks the cycles; the appliance no longer does. Both lengths
  // are `UNVERIFIED` - they follow from the coast rate and the pulldown penalty,
  // neither of which is measured yet. 30 min is the low end of the 20-30 min
  // floor in CLAUDE.md section 6: an inverter compressor dislikes restarts, and
  // the pressure-equalisation penalty scales with cycle *count*, so err long.
  uint32_t run_block_ms = 30u * 60u * 1000u;
  uint32_t rest_block_ms = 30u * 60u * 1000u;

  // --- cycle shaping ---
  uint32_t min_on_ms = 10u * 60u * 1000u;  // do not release before this
  uint32_t min_off_ms = 20u * 60u * 1000u; // anti-short-cycle, sized to the block

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

  // Station data stalled while the link still claims to be up. This is the
  // *pre-emptive* fail-safe and the only one that can actually save the fridge:
  // by the time ble_connected drops there is no link left to carry an ON
  // command, so the request must go out while the link is merely degrading.
  // Sized just above sensor_max_age (45s) so it trails the sensors going
  // invalid rather than racing them.
  uint32_t link_stale_ms = 60u * 1000u;

  // --- drive inhibit (Phase 4, default off; suppressor, never a request) ---
  uint32_t inhibit_max_ms = 4u * 60u * 60u * 1000u; // stuck-true must expire

  // --- parked mode (van left at home, fridge emptied, AC hard off) ---
  // `REVISED 2026-08-25`: parking is a manual decision confirmed twice, not an
  // inference from temperatures. The old interlock refused to arm while the
  // cabinet read more than 3 C below cabin, on the theory that a cold cabinet
  // means a loaded fridge. It also refused whenever either probe was missing,
  // which made a storage feature depend on two sensors it does not otherwise
  // need. See decisions.md D-14.
  //
  // The window for the second, confirming request. Long enough for two bezel
  // long-presses or two taps in a web UI; short enough that an arm left
  // hanging cannot commit hours later.
  uint32_t park_confirm_ms = 30u * 1000u;
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

  // Cabin ambient. Not a control input: it exists only so parked mode can tell
  // an emptied fridge from a working one before it disarms the fail-safe.
  bool cabin_valid = false;
  float cabin_temp_c = 0.0f;

  bool sleep_mode = false;
  bool drive_inhibit = false;
};

// Why the inverter is on. Ordered by precedence, and shown on the display -
// "the inverter is on" without a reason is an undebuggable system.
enum class AcReason : uint8_t {
  OFF = 0,
  PARKED, // off, and deliberately not coming back on
  BOOT,
  BLE_LOST,
  LINK_STALE, // connected, but the station stopped talking
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
  bool parked = false;         // long-term storage: AC held off, fail-safe disarmed
  bool park_pending = false;   // armed by one request, waiting for the confirming one
  uint32_t parked_for_s = 0;   // since entry, or since the last reboot while parked
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

  // --- parked mode ---
  // Two-step, and deliberately so. The first call arms; a second call within
  // park_confirm_ms commits and returns true. An arm that is not confirmed
  // lapses on its own. There is no temperature interlock and no probe
  // requirement: whether the fridge is empty is a fact only the human knows,
  // and asking twice is a better guard than inferring it (D-14).
  bool park_request(uint32_t now_ms);

  // Single-step, no confirmation. This is the reboot-restore path only: a van
  // meant to stay parked for three weeks must not need a human to re-confirm
  // after every brownout. Never wire a button to it.
  void set_parked(uint32_t now_ms, bool on);

  void park_exit(uint32_t now_ms);
  bool parked() const { return parked_; }

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
  uint32_t link_fresh_ms_ = 0;      // last time the station sent anything
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

  // parked mode
  bool parked_ = false;
  uint32_t parked_since_ms_ = 0;
  bool park_armed_ = false;
  uint32_t park_armed_ms_ = 0;
  ArbiterInputs last_in_;  // snapshot for the arming interlock
};

// Wrap-safe "has at least `span` elapsed since `mark`".
inline bool elapsed(uint32_t now, uint32_t mark, uint32_t span) {
  return static_cast<uint32_t>(now - mark) >= span;
}

}  // namespace van

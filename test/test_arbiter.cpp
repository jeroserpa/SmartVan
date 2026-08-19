// Host-compiled tests for the AC arbiter state machine.
//
//   cd test && make && ./test_arbiter
//
// No ESPHome, no hardware, no network. The point of splitting the state machine
// out of the YAML was to make this file possible: a fail-safe arbiter whose
// fail-safe path is only ever exercised in a van is not a fail-safe arbiter.

#include "../components/ac_arbiter/arbiter_core.h"

#include <cstdio>
#include <cstring>

using namespace van;

static int g_failures = 0;
static const char *g_case = "";

#define CASE(name) do { g_case = name; printf("-- %s\n", name); } while (0)

#define CHECK(cond)                                                          \
  do {                                                                       \
    if (!(cond)) {                                                           \
      printf("   FAIL %s:%d  [%s]  %s\n", __FILE__, __LINE__, g_case, #cond); \
      ++g_failures;                                                          \
    }                                                                        \
  } while (0)

static const uint32_t SEC = 1000u;
static const uint32_t MIN = 60u * 1000u;

// Test rig: holds the core plus a mutable input snapshot and a clock, and ticks
// at the same 5s cadence the real component uses.
struct Sim {
  ArbiterCore core;
  ArbiterInputs in;
  uint32_t t = 0;

  explicit Sim(uint32_t t0 = 0) : t(t0) {
    core.begin(t);
    // A healthy, boring baseline: BLE up, fridge cold, nothing drawing power.
    in.ble_connected = true;
    in.temp_valid = true;
    in.fridge_temp_c = 3.0f;
    in.power_valid = true;
    in.output_power_w = 0.0f;
    in.input_power_valid = true;
    in.input_power_w = 0.0f;
    in.soc_valid = true;
    in.soc_pct = 90.0f;
  }

  const ArbiterOutputs &tick() { return core.tick(t, in); }

  // Advance `span` in 5s steps, ticking as we go.
  const ArbiterOutputs &run(uint32_t span) {
    const uint32_t step = 5 * SEC;
    for (uint32_t done = 0; done < span; done += step) {
      t += step;
      core.tick(t, in);
    }
    return core.outputs();
  }

  // Get past the post-boot force_on window into normal operation.
  const ArbiterOutputs &settle() { return run(2 * MIN); }
};

// ---------------------------------------------------------------------------
// Fail-safe: every one of these must resolve to the inverter ON.
// ---------------------------------------------------------------------------
static void test_failsafe() {
  CASE("boot forces AC on before anything is known");
  {
    Sim s;
    const ArbiterOutputs &o = s.tick();
    CHECK(o.ac_on);
    CHECK(o.force_on);
    CHECK(o.reason == AcReason::BOOT);
    // ...and it holds for the full boot window even with a cold fridge.
    s.run(30 * SEC);
    CHECK(s.core.outputs().ac_on);
    CHECK(s.core.outputs().reason == AcReason::BOOT);
  }

  CASE("BLE loss forces AC on");
  {
    Sim s;
    s.settle();
    CHECK(!s.core.outputs().ac_on);
    s.in.ble_connected = false;
    const ArbiterOutputs &o = s.run(10 * SEC);
    CHECK(o.ac_on);
    CHECK(o.force_on);
    CHECK(o.reason == AcReason::BLE_LOST);
  }

  CASE("stale fridge probe forces AC on after the stale timeout, not before");
  {
    Sim s;
    s.settle();
    s.in.temp_valid = false;
    s.run(4 * MIN);
    CHECK(!s.core.outputs().ac_on);  // still trusting the last reading
    s.run(2 * MIN);
    CHECK(s.core.outputs().ac_on);
    CHECK(s.core.outputs().reason == AcReason::TEMP_STALE);
  }

  CASE("starved loop trips the watchdog");
  {
    Sim s;
    s.settle();
    CHECK(!s.core.outputs().ac_on);
    s.t += 45 * SEC;  // one tick, long after it was due
    const ArbiterOutputs &o = s.tick();
    CHECK(o.ac_on);
    CHECK(o.reason == AcReason::WATCHDOG);
    // Recovers on the next healthy tick.
    s.t += 5 * SEC;
    CHECK(!s.tick().ac_on);
  }

  CASE("unreadable power sensor never releases the inverter early");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 9.0f;
    s.run(10 * SEC);
    CHECK(s.core.outputs().fridge_req);
    s.in.fridge_temp_c = 2.0f;
    s.in.power_valid = false;  // BLE up but the power register is not arriving
    s.run(30 * MIN);
    CHECK(s.core.outputs().fridge_req);  // unknown power counts as compressor busy
  }
}

// ---------------------------------------------------------------------------
// Fridge thermostat
// ---------------------------------------------------------------------------
static void test_fridge() {
  CASE("cold fridge with a quiet inverter turns AC off");
  {
    Sim s;
    const ArbiterOutputs &o = s.settle();
    CHECK(!o.ac_on);
    CHECK(!o.force_on);
    CHECK(!o.fridge_req);
    CHECK(o.reason == AcReason::OFF);
  }

  CASE("crossing the on-threshold requests cooling");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 6.9f;
    s.run(10 * SEC);
    CHECK(!s.core.outputs().fridge_req);
    s.in.fridge_temp_c = 7.1f;
    s.run(10 * SEC);
    CHECK(s.core.outputs().fridge_req);
    CHECK(s.core.outputs().reason == AcReason::FRIDGE);
  }

  CASE("release needs cold AND quiet AND minimum on-time, all three");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 8.0f;
    s.run(10 * SEC);
    CHECK(s.core.outputs().fridge_req);

    // Compressor running, pulling the temperature down.
    s.in.output_power_w = 35.0f;
    s.run(9 * MIN);
    s.in.fridge_temp_c = 3.0f;
    s.run(1 * MIN);
    CHECK(s.core.outputs().fridge_req);  // cold and settled, but still drawing

    s.in.output_power_w = 5.0f;  // compressor stops
    s.run(60 * SEC);
    CHECK(s.core.outputs().fridge_req);  // 90s debounce not yet elapsed
    s.run(45 * SEC);
    CHECK(!s.core.outputs().fridge_req);
    CHECK(!s.core.outputs().ac_on);
  }

  CASE("minimum on-time holds even if the fridge is already cold");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 8.0f;
    s.run(10 * SEC);
    s.in.fridge_temp_c = 1.0f;
    s.in.output_power_w = 0.0f;
    s.run(5 * MIN);
    CHECK(s.core.outputs().fridge_req);  // min_on is 10 min
    s.run(6 * MIN);
    CHECK(!s.core.outputs().fridge_req);
  }

  CASE("anti-short-cycle blocks an immediate restart");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 8.0f;
    s.run(10 * SEC);
    s.in.fridge_temp_c = 1.0f;
    s.run(12 * MIN);
    CHECK(!s.core.outputs().fridge_req);
    s.in.fridge_temp_c = 8.0f;  // door left open
    s.run(2 * MIN);
    CHECK(!s.core.outputs().fridge_req);  // 5 min minimum off
    s.run(4 * MIN);
    CHECK(s.core.outputs().fridge_req);
  }

  CASE("hard override beats the anti-short-cycle timer");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 8.0f;
    s.run(10 * SEC);
    s.in.fridge_temp_c = 1.0f;
    s.run(12 * MIN);
    CHECK(!s.core.outputs().fridge_req);
    s.in.fridge_temp_c = 10.5f;
    s.run(10 * SEC);  // well inside the 5 min lockout
    CHECK(s.core.outputs().fridge_req);
    CHECK(s.core.outputs().reason == AcReason::FRIDGE_HARD);
  }
}

// ---------------------------------------------------------------------------
// Sleep mode / coasting
// ---------------------------------------------------------------------------
static void test_sleep() {
  CASE("sleep mode raises the ceiling and coasts through it");
  {
    Sim s;
    s.settle();
    s.in.sleep_mode = true;
    s.in.fridge_temp_c = 5.5f;  // above the normal 4, below the sleep ceiling of 6
    s.run(1 * MIN);
    CHECK(!s.core.outputs().fridge_req);
    CHECK(!s.core.outputs().ac_on);
  }

  CASE("a sleep-mode cycle runs all the way down to the coast target");
  {
    Sim s;
    s.settle();
    s.in.sleep_mode = true;
    s.in.fridge_temp_c = 6.5f;
    s.run(10 * SEC);
    CHECK(s.core.outputs().fridge_req);
    s.in.fridge_temp_c = 3.0f;  // would satisfy the normal 4 C setpoint
    s.run(15 * MIN);
    CHECK(s.core.outputs().fridge_req);  // keep going: target is 1 C
    s.in.fridge_temp_c = 0.8f;
    s.run(3 * MIN);
    CHECK(!s.core.outputs().fridge_req);
  }

  CASE("the 10 C hard override still applies during sleep");
  {
    Sim s;
    s.settle();
    s.in.sleep_mode = true;
    s.in.fridge_temp_c = 10.5f;
    s.run(10 * SEC);
    CHECK(s.core.outputs().ac_on);
    CHECK(s.core.outputs().reason == AcReason::FRIDGE_HARD);
  }

  CASE("sleep mode suppresses surplus entirely");
  {
    Sim s;
    s.settle();
    s.in.sleep_mode = true;
    s.in.input_power_w = 600.0f;
    s.in.soc_pct = 99.0f;
    s.run(2 * MIN);
    CHECK(!s.core.outputs().surplus_req);
    CHECK(!s.core.outputs().ac_on);
  }
}

// ---------------------------------------------------------------------------
// Manual (cooking) button
// ---------------------------------------------------------------------------
static void test_manual() {
  CASE("short press arms AC for 45 min");
  {
    Sim s;
    s.settle();
    s.core.manual_press(s.t);
    const ArbiterOutputs &o = s.run(5 * SEC);
    CHECK(o.manual_req);
    CHECK(o.ac_on);
    CHECK(o.reason == AcReason::MANUAL);
    CHECK(o.manual_remaining_s > 44 * 60 && o.manual_remaining_s <= 45 * 60);
  }

  CASE("long press cancels immediately");
  {
    Sim s;
    s.settle();
    s.core.manual_press(s.t);
    s.run(1 * MIN);
    CHECK(s.core.outputs().manual_req);
    s.core.manual_cancel(s.t);
    CHECK(!s.core.outputs().manual_req);
    CHECK(!s.run(5 * SEC).ac_on);
  }

  CASE("a second press extends, and the 3h ceiling is absolute");
  {
    Sim s;
    s.settle();
    s.in.output_power_w = 1500.0f;  // induction plate: keeps auto-release away
    s.core.manual_press(s.t);
    for (int i = 0; i < 10; i++) {
      s.run(1 * MIN);
      s.core.manual_press(s.t);  // ten extensions would be 5h15 if uncapped
    }
    s.run(2 * 60 * MIN);
    CHECK(s.core.outputs().manual_req);
    s.run(70 * MIN);  // past 3h from the first press
    CHECK(!s.core.outputs().manual_req);
  }

  CASE("auto-release when nothing above the release floor is drawing");
  {
    Sim s;
    s.settle();
    s.in.output_power_w = 1800.0f;
    s.core.manual_press(s.t);
    s.run(15 * MIN);
    CHECK(s.core.outputs().manual_req);
    s.in.output_power_w = 35.0f;  // cooking done, fridge compressor still runs
    s.run(9 * MIN);
    CHECK(s.core.outputs().manual_req);
    CHECK(s.core.outputs().manual_warning);  // buzzer lead-in
    s.run(2 * MIN);
    CHECK(!s.core.outputs().manual_req);
  }

  CASE("a running fridge cannot hold the manual timer open");
  {
    Sim s;
    s.settle();
    s.in.output_power_w = 35.0f;
    s.core.manual_press(s.t);
    s.run(9 * MIN);
    CHECK(s.core.outputs().manual_req);  // grace period protects an early start
    s.run(3 * MIN);
    CHECK(!s.core.outputs().manual_req);
  }
}

// ---------------------------------------------------------------------------
// Opportunistic surplus
// ---------------------------------------------------------------------------
static void test_surplus() {
  CASE("surplus requires both headroom and a high SOC");
  {
    Sim s;
    s.settle();
    s.in.input_power_w = 600.0f;
    s.in.soc_pct = 70.0f;
    s.run(1 * MIN);
    CHECK(!s.core.outputs().surplus_req);
    s.in.soc_pct = 90.0f;
    s.run(1 * MIN);
    CHECK(s.core.outputs().surplus_req);
    CHECK(s.core.outputs().reason == AcReason::SURPLUS);
  }

  CASE("a passing cloud does not cost an inverter start");
  {
    Sim s;
    s.settle();
    s.in.input_power_w = 600.0f;
    s.run(1 * MIN);
    CHECK(s.core.outputs().surplus_req);
    s.in.input_power_w = 0.0f;
    s.run(3 * MIN);
    CHECK(s.core.outputs().surplus_req);  // held by the minimum on-time
    s.run(9 * MIN);
    CHECK(!s.core.outputs().surplus_req);
  }
}

// ---------------------------------------------------------------------------
// Drive-time inhibit (Phase 4)
// ---------------------------------------------------------------------------
static void test_inhibit() {
  CASE("inhibit suppresses a fridge request but never force_on");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 8.0f;
    s.run(10 * SEC);
    CHECK(s.core.outputs().ac_on);
    s.in.drive_inhibit = true;
    s.run(10 * SEC);
    CHECK(!s.core.outputs().ac_on);
    // BLE drops mid-drive: force_on wins over the inhibit.
    s.in.ble_connected = false;
    s.run(10 * SEC);
    CHECK(s.core.outputs().ac_on);
  }

  CASE("a stuck-true engine signal expires on its own");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 8.0f;
    s.in.drive_inhibit = true;
    s.run(3 * 60 * MIN);
    CHECK(!s.core.outputs().ac_on);
    s.run(70 * MIN);  // past the 4h hard timeout
    CHECK(s.core.outputs().ac_on);
    CHECK(!s.core.outputs().inhibit_active);
  }

  CASE("the 10 C hard override survives the inhibit");
  {
    Sim s;
    s.settle();
    s.in.drive_inhibit = true;
    s.in.fridge_temp_c = 11.0f;
    s.run(10 * SEC);
    CHECK(s.core.outputs().fridge_req);
    CHECK(s.core.outputs().ac_on);
  }
}

// ---------------------------------------------------------------------------
// The 49.7-day millis() rollover. A van node runs for months.
// ---------------------------------------------------------------------------
static void test_millis_rollover() {
  CASE("timers survive the uint32 millis wrap");
  {
    Sim s(0xFFFFFF00u);  // ~4 minutes before the wrap
    s.settle();          // ticks straight through 0
    CHECK(!s.core.outputs().ac_on);
    CHECK(!s.core.outputs().force_on);

    s.in.fridge_temp_c = 8.0f;
    s.run(10 * SEC);
    CHECK(s.core.outputs().fridge_req);
    s.in.fridge_temp_c = 1.0f;
    s.run(12 * MIN);
    CHECK(!s.core.outputs().fridge_req);
  }

  CASE("the manual timer survives a wrap mid-countdown");
  {
    Sim s(0xFFFFFF00u - 5 * MIN);
    s.settle();
    s.in.output_power_w = 1500.0f;
    s.core.manual_press(s.t);
    s.run(40 * MIN);  // deadline lands after the wrap
    CHECK(s.core.outputs().manual_req);
    s.run(10 * MIN);
    CHECK(!s.core.outputs().manual_req);
  }
}

int main() {
  test_failsafe();
  test_fridge();
  test_sleep();
  test_manual();
  test_surplus();
  test_inhibit();
  test_millis_rollover();

  if (g_failures == 0) {
    printf("\nall arbiter tests passed\n");
    return 0;
  }
  printf("\n%d failure(s)\n", g_failures);
  return 1;
}

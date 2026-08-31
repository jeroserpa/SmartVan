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
    in.cabin_valid = true;
    in.cabin_temp_c = 20.0f;
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

  // Parking is two-step by design (D-14). Tests that simply want the van
  // parked say so once and let this do the confirming press.
  bool park() {
    core.park_request(t);
    return core.park_request(t);
  }
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

  // Named for what it actually asserts. The request goes true; the inverter
  // does NOT come on, because the write has no link to travel over. See
  // CLAUDE.md 5.2 - this is recovery-on-reconnect, not a fail-safe.
  CASE("BLE loss holds the request on so the reconnect restores AC");
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

  // The distinction this whole group exists to police: BLE_LOST is a recovery
  // behaviour, LINK_STALE is the fail-safe. Only the second one still has a
  // link to carry the ON command it asks for.
  CASE("a wedged-but-connected link forces AC on before the stack notices");
  {
    Sim s;
    s.settle();
    CHECK(!s.core.outputs().ac_on);
    // The stack still claims a connection; the station has simply stopped
    // sending. The local probe keeps reporting, so nothing else trips.
    s.in.power_valid = false;
    s.in.soc_valid = false;
    s.in.input_power_valid = false;
    CHECK(!s.run(30 * SEC).ac_on);  // not yet - link_stale_ms is 60s
    const ArbiterOutputs &o = s.run(40 * SEC);
    CHECK(o.ac_on);
    CHECK(o.force_on);
    CHECK(o.reason == AcReason::LINK_STALE);
  }

  CASE("station data flowing keeps the link fresh even with a dead probe");
  {
    Sim s;
    s.settle();
    // Probe gone, station fine: this must be TEMP_STALE, never LINK_STALE.
    s.in.temp_valid = false;
    const ArbiterOutputs &o = s.run(6 * MIN);
    CHECK(o.ac_on);
    CHECK(o.reason == AcReason::TEMP_STALE);
  }

  CASE("link staleness clears once the station talks again");
  {
    Sim s;
    s.settle();
    s.in.power_valid = false;
    s.in.soc_valid = false;
    s.in.input_power_valid = false;
    CHECK(s.run(2 * MIN).reason == AcReason::LINK_STALE);
    s.in.power_valid = true;
    s.in.soc_valid = true;
    s.in.input_power_valid = true;
    const ArbiterOutputs &o = s.run(10 * SEC);
    CHECK(!o.force_on);
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

  // D-03 still holds, but it is now about the *early* release only: with the
  // block scheduler, temperature releases on its own authority and does not
  // consult the power register at all.
  CASE("unreadable power never triggers the compressor-stopped early release");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 9.0f;
    s.run(10 * SEC);
    CHECK(s.core.outputs().fridge_req);
    // Still above the floor, so nothing but a stopped compressor could end the
    // block this early - and unknown power must not be read as stopped.
    s.in.fridge_temp_c = 6.0f;
    s.in.power_valid = false;  // BLE up but the power register is not arriving
    s.run(15 * MIN);
    CHECK(s.core.outputs().fridge_req);
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

  // THE regression test for PATCHES P2 / ANALYSIS 4.1. This appliance has a
  // variable-speed inverter compressor: it modulates for hours and at high
  // ambient never stops, so output_power never falls quiet. The old release
  // required quiet, so fridge_req latched true forever and the inverter ran
  // 24/7 - the project's entire saving, silently zero. The block timer must end
  // the block with the compressor still drawing and the cabinet still warm.
  CASE("an inverter compressor that never stops still gets its block ended");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 8.0f;
    s.in.output_power_w = 30.0f;  // and it stays there. It always stays there.
    s.run(10 * SEC);
    CHECK(s.core.outputs().fridge_req);

    // Modulating away, cabinet coming down but not to the floor.
    s.in.fridge_temp_c = 5.0f;
    CHECK(s.run(20 * MIN).fridge_req);  // run_block_ms is 30 min
    const ArbiterOutputs &o = s.run(11 * MIN);
    CHECK(!o.fridge_req);
    CHECK(!o.ac_on);
  }

  CASE("a run block ends on any of cold, the block timer, or a stopped compressor");
  {
    // (a) cold, while still drawing: temperature releases on its own authority.
    Sim a;
    a.settle();
    a.in.fridge_temp_c = 8.0f;
    a.run(10 * SEC);
    a.in.output_power_w = 35.0f;
    a.run(9 * MIN);
    a.in.fridge_temp_c = 3.0f;
    CHECK(!a.run(2 * MIN).fridge_req);

    // (b) the compressor genuinely stopping, cabinet still above the floor.
    // Strategy A, salvaged - taken when available, never required.
    Sim b;
    b.settle();
    b.in.fridge_temp_c = 8.0f;
    b.run(10 * SEC);
    b.in.output_power_w = 35.0f;
    b.run(11 * MIN);
    b.in.fridge_temp_c = 6.0f;
    CHECK(b.run(1 * MIN).fridge_req);
    b.in.output_power_w = 5.0f;
    CHECK(b.run(60 * SEC).fridge_req);  // 90s debounce not yet elapsed
    CHECK(!b.run(45 * SEC).fridge_req);
  }

  CASE("the schedule starts a block with the cabinet inside the deadband");
  {
    Sim s;
    s.settle();  // t = 2 min
    // 5 C: below the 7 C ceiling, above the 4 C floor. The old thermostat would
    // sit here indefinitely; the scheduler banks cold on its own initiative.
    s.in.fridge_temp_c = 5.0f;
    // A real draw, not the rig's 0 W default - at 0 W the compressor reads as
    // permanently stopped and the early release pre-empts the schedule. This
    // appliance draws ~30 W continuously (measurements.md M6).
    s.in.output_power_w = 30.0f;
    // begin() back-dates fridge_since_ms_ by min_off (D-04), so the rest block
    // is measured from t = -20 min and expires at t = 10 min.
    CHECK(!s.run(5 * MIN).fridge_req);  // t = 7 min
    CHECK(s.run(5 * MIN).fridge_req);   // t = 12 min
  }

  CASE("coasting has no schedule, so a quiet night stays quiet");
  {
    Sim s;
    s.settle();
    s.in.sleep_mode = true;
    s.in.fridge_temp_c = 5.0f;  // under the 6 C sleep ceiling
    s.in.output_power_w = 30.0f;
    // Two full rest blocks pass and nothing starts. This is P3: sleep mode is
    // no longer suppressing cycles the appliance chose, it is simply the
    // supervisor declining to schedule any.
    CHECK(!s.run(70 * MIN).fridge_req);
  }

  CASE("no scheduled block while the cabinet is already at the target");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 2.0f;  // below the floor: nothing to gain
    CHECK(!s.run(90 * MIN).fridge_req);
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
    s.run(15 * MIN);
    CHECK(!s.core.outputs().fridge_req);  // min_off is 20 min, sized to the block
    s.run(6 * MIN);
    CHECK(s.core.outputs().fridge_req);
    // And the 10 C hard override is the safety net that makes a 20 min lockout
    // acceptable - covered by the next case.
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

// ---------------------------------------------------------------------------
// Parked mode: the van left at home, fridge emptied, AC off for weeks. This is
// the one mode that inverts the fail-safe direction, so it gets the most
// suspicious tests in the file.
// ---------------------------------------------------------------------------
static void test_parked() {
  // The arming interlock is gone (D-14). It inferred "there is food in there"
  // from a cabinet colder than cabin, which is a guess about a fact only the
  // owner knows - and it refused whenever a probe was missing, making a
  // storage feature depend on two sensors it does not otherwise need. A
  // deliberate second press is the guard now.
  CASE("one request only arms; it does not park");
  {
    Sim s;
    s.settle();
    CHECK(!s.core.park_request(s.t));
    CHECK(s.core.outputs().park_pending);
    CHECK(!s.core.outputs().parked);
    // Still an ordinary van: arbitrating normally, fail-safe armed.
    const ArbiterOutputs &o = s.run(1 * MIN);
    CHECK(!o.parked);
    CHECK(o.reason != AcReason::PARKED);
    s.in.ble_connected = false;
    CHECK(s.run(10 * SEC).ac_on);  // the fail-safe is NOT disarmed
  }

  CASE("a second request inside the window parks");
  {
    Sim s;
    s.settle();
    CHECK(!s.core.park_request(s.t));
    s.t += 5 * SEC;
    CHECK(s.park());
    const ArbiterOutputs &o = s.run(1 * MIN);
    CHECK(o.parked);
    CHECK(!o.park_pending);
    CHECK(!o.ac_on);
    CHECK(o.reason == AcReason::PARKED);
  }

  CASE("an arm that is not confirmed lapses, and the next press re-arms");
  {
    Sim s;
    s.settle();
    CHECK(!s.core.park_request(s.t));
    CHECK(!s.run(2 * MIN).park_pending);  // window is 30s
    // The stale arm must not be completable: this press arms afresh.
    CHECK(!s.core.park_request(s.t));
    CHECK(s.core.outputs().park_pending);
    CHECK(!s.core.outputs().parked);
  }

  CASE("a cold, loaded, working fridge is no longer an obstacle to parking");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 3.0f;   // cold
    s.in.cabin_temp_c = 20.0f;   // the old interlock refused exactly this
    s.tick();
    CHECK(s.park());
    CHECK(s.core.outputs().parked);
  }

  CASE("missing probes do not block parking");
  {
    Sim s;
    s.settle();
    s.in.temp_valid = false;
    s.in.cabin_valid = false;
    s.tick();
    CHECK(s.park());
    CHECK(s.core.outputs().parked);
    // And the disarmed fail-safe still holds with no probe at all.
    CHECK(!s.run(10 * MIN).ac_on);
  }

  CASE("parked, the fail-safe is inverted: BLE loss must NOT power the inverter");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 19.0f;
    s.tick();
    CHECK(s.park());
    s.in.ble_connected = false;
    s.run(10 * MIN);
    CHECK(!s.core.outputs().ac_on);
    CHECK(!s.core.outputs().force_on);
    CHECK(s.core.outputs().reason == AcReason::PARKED);
  }

  CASE("parked, a dead probe and a warm cabinet must NOT power the inverter");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 19.0f;
    s.tick();
    CHECK(s.park());
    s.in.temp_valid = false;
    s.run(30 * MIN);          // well past temp_stale_ms
    CHECK(!s.core.outputs().ac_on);
    s.in.temp_valid = true;
    s.in.fridge_temp_c = 25.0f;  // above the 10 C hard override
    s.run(10 * MIN);
    CHECK(!s.core.outputs().ac_on);
    CHECK(!s.core.outputs().fridge_hard);
  }

  CASE("parked suppresses surplus, so solar never starts the inverter");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 19.0f;
    s.tick();
    CHECK(s.park());
    s.in.input_power_w = 700.0f;
    s.in.soc_pct = 99.0f;
    s.run(30 * MIN);
    CHECK(!s.core.outputs().surplus_req);
    CHECK(!s.core.outputs().ac_on);
  }

  CASE("a reboot while parked stays parked, interlock notwithstanding");
  {
    Sim s;
    s.core.set_parked(s.t, true);  // the restore path: no probe has reported yet
    s.tick();
    CHECK(s.core.outputs().parked);
    CHECK(!s.core.outputs().ac_on);
    CHECK(!s.core.outputs().force_on);   // the boot force-on window is overridden
  }

  CASE("the cooking button is the exit gesture, and arms manual as usual");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 19.0f;
    s.tick();
    CHECK(s.park());
    s.run(2 * MIN);
    s.core.manual_press(s.t);
    const ArbiterOutputs &o = s.tick();
    CHECK(!o.parked);
    CHECK(o.manual_req);
    CHECK(o.ac_on);
  }

  CASE("leaving parked mode powers the inverter immediately, then hands over");
  {
    Sim s;
    s.settle();
    s.in.fridge_temp_c = 19.0f;
    s.tick();
    CHECK(s.park());
    s.run(3 * 24 * 60 * MIN);   // three days of storage
    CHECK(s.core.outputs().parked_for_s > 3u * 24u * 3600u - 60u);
    s.core.park_exit(s.t);
    const ArbiterOutputs &o = s.tick();
    CHECK(o.ac_on);
    CHECK(o.force_on);          // boot window re-armed: load food, get cold
    CHECK(o.reason == AcReason::BOOT);

    // ...and once it expires the thermostat takes over on a warm cabinet with
    // no anti-short-cycle lockout inherited from storage.
    s.run(2 * MIN);
    CHECK(s.core.outputs().fridge_req);
    CHECK(s.core.outputs().ac_on);
  }

  CASE("parked mode survives the millis wrap");
  {
    Sim s(0xFFFFFF00u - 10 * MIN);
    s.settle();
    s.in.fridge_temp_c = 19.0f;
    s.tick();
    CHECK(s.park());
    s.run(60 * MIN);            // straight through the wrap
    CHECK(s.core.outputs().parked);
    CHECK(!s.core.outputs().ac_on);
    CHECK(s.core.outputs().parked_for_s > 55u * 60u);
  }
}

int main() {
  test_failsafe();
  test_fridge();
  test_sleep();
  test_manual();
  test_surplus();
  test_inhibit();
  test_parked();
  test_millis_rollover();

  if (g_failures == 0) {
    printf("\nall arbiter tests passed\n");
    return 0;
  }
  printf("\n%d failure(s)\n", g_failures);
  return 1;
}

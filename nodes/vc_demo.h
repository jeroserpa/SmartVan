// vc_demo.h - synthetic panel data for design review. BENCH ONLY.
//
// The panel has to be judged against real-looking values, and on a bench there
// are none: no P310 in range, no probes on the 1-Wire bus, so every field reads
// "--" and the layout cannot be assessed at all. This generates a plausible
// stream instead, and sweeps deliberately through the cases that break layouts:
// the widest glyph runs (100%, -10.5 degrees), every frame colour, and both
// degraded paths (no station data, dead probe).
//
// ============================================================================
// THIS IS A PRESENTATION-LAYER FAKE AND MUST STAY ONE.
//
// The bench rig this idea comes from (van-core-ttgo.yaml) had NO arbiter, so
// its mock entities were harmless. This node has one, and it writes the P310's
// inverter switch over BLE. So nothing here is wired to a sensor, and nothing
// here reaches ac_arbiter: the arbiter goes on reading the real (absent)
// probes, goes on failing toward AC ON, and never sees a byte of this file.
// Only pixels change.
//
// Wiring these values into the sensors instead would create a path where a
// bench toggle commands the real inverter OFF - which is the failure section
// 5.2 exists to prevent, arriving through a debugging feature. If this ever
// needs to drive the arbiter for a control test, that is a separate, named,
// loudly-logged mode - not an extension of this one.
// ============================================================================
//
// Gated by the `bench_review` substitution in van-core.yaml. Set it to 0 and
// this file is still compiled but never called.

#pragma once

#include <cmath>
#include <cstdint>

namespace esphome {
namespace vc {
namespace demo {

// Everything the display lambda reads, and nothing else.
struct Frame {
  bool ble, probe, parked, park_q, force, manual, sleep_, drive, ac_on;
  bool have_soc, have_flow;
  float soc, win, wout, fridge_c, cabin_c, remaining_h, parked_d;
  const char *reason;
};

// One scenario per frame colour and per degraded path. Held for HOLD_MS each.
struct Scene {
  bool ble, probe, force, manual, sleep_, drive, ac_on, parked, park_q, flow;
  float win, wout;
  const char *reason;
};

inline Frame frame(uint32_t ms) {
  // clang-format off
  static const Scene SCENES[] = {
    // ble prb frc man slp drv ac  prk pq  flow   win    wout  reason
    {  1,  1,  0,  0,  0,  0,  0,  0,  0,  1,    420.f,   35.f, "off"         }, // quiet, sun
    {  1,  1,  0,  0,  0,  0,  1,  0,  0,  1,    380.f,  310.f, "fridge"      }, // amber
    {  1,  1,  0,  1,  0,  0,  1,  0,  0,  1,    120.f, 1850.f, "manual"      }, // green, cooking
    {  1,  1,  0,  0,  1,  0,  0,  0,  0,  1,      0.f,   12.f, "off"         }, // sleep, coasting
    {  1,  1,  0,  0,  0,  1,  1,  0,  0,  1,    810.f,  340.f, "fridge"      }, // driving
    {  0,  1,  1,  0,  0,  0,  1,  0,  0,  0,      0.f,    0.f, "ble lost"    }, // red + NO DATA
    {  1,  0,  1,  0,  0,  0,  1,  0,  0,  1,    300.f,  290.f, "probe stale" }, // red + "--" hero
    {  1,  1,  0,  0,  0,  0,  0,  1,  0,  1,    210.f,    8.f, "parked"      }, // blue
    {  1,  1,  0,  0,  0,  0,  1,  0,  1,  1,    210.f,   40.f, "fridge"      }, // park armed
    {  1,  1,  0,  0,  0,  0,  1,  0,  0,  1,   1490.f,  980.f, "surplus"     }, // big solar
  };
  // clang-format on
  const int N = sizeof(SCENES) / sizeof(SCENES[0]);
  const uint32_t HOLD_MS = 7000;
  const Scene &s = SCENES[(ms / HOLD_MS) % N];

  Frame f{};
  f.ble = s.ble; f.probe = s.probe; f.force = s.force; f.manual = s.manual;
  f.sleep_ = s.sleep_; f.drive = s.drive; f.ac_on = s.ac_on;
  f.parked = s.parked; f.park_q = s.park_q;
  f.have_flow = s.flow; f.have_soc = s.flow;
  f.win = s.win; f.wout = s.wout;
  f.reason = s.reason;

  // SOC and temperature sweep CONTINUOUSLY, independent of the scene, on
  // periods that are coprime with the scene hold. Every state therefore gets
  // seen at several digit widths rather than always at the same convenient
  // one - which is the whole reason "100%" and "-10.5" are worth reaching.
  const float soc_ph = (float) (ms % 47000u) / 47000.0f;
  f.soc = 100.0f - 96.0f * soc_ph;              // 100 -> 4

  const float t_ph = (float) (ms % 31000u) / 31000.0f;
  f.fridge_c = -9.9f + 22.4f * t_ph;            // -9.9 -> 12.5, spans both
                                                // the amber and the 10 C red
  f.cabin_c = 19.0f + 4.0f * sinf(t_ph * 6.2832f);
  f.remaining_h = 0.4f + 26.0f * (f.soc / 100.0f);
  f.parked_d = 12.4f;
  return f;
}

}  // namespace demo
}  // namespace vc
}  // namespace esphome

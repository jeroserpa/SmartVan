// vc_panel.h - every screen of the van-core panel, drawn from one plain struct.
//
// REDESIGN 2026-10-15 (docs/decisions.md D-36). The layout, wording and
// behaviour of D-26/D-31/D-33 are kept; what changed is the visual language:
//
//   - The glass has rounded corners, so everything that touches the edge
//     follows them: the state rim, cards, chips, bars. The old 5 px square
//     frame had its corners cut off by the bezel.
//   - Anti-aliased shapes (vc_draw.h) instead of ESPHome's aliased 1 px ones.
//   - Barlow - a typeface drawn from California highway signs and plates,
//     i.e. for reading at distance - in place of Roboto. Semi Condensed for
//     the numerals, so they can be TALLER in the same width: the hero's
//     digits went from 80 to ~100 px (8.2 -> 10.3 mm), 2.0 -> 2.6 m by the
//     250x-cap-height rule in van-core.yaml's font notes.
//   - The unit is no longer as big as the number. A full-size "%" cost as
//     much width as a digit and said nothing from across the van.
//   - Every page has the same skeleton: an analogue column on the left that
//     reads from furthest away (battery vessel, fridge scale, action icon),
//     the hero number to its right, one walk-up line underneath.
//   - The state is named in a chip, not only coloured in the frame, so it
//     still reads in daylight through a printed bezel and to colour-blind
//     eyes ("colour never carries meaning alone", unchanged).
//   - The web dashboard's colours (ui/index.html, same OKLCH hues) on a black
//     ground, and its SOC bands, 30 / 15 % - the panel alone still had 40 / 20.
//
// WHY A STRUCT. The lambda in van-core.yaml reads every id() into State and
// calls draw(). That keeps this file free of ESPHome component ids, which is
// what lets tools/panel_preview/ compile it on a laptop and render every
// screen to PNG - so the panel is reviewed as pixels, not imagined from code.
// It draws only; it never reaches the arbiter (section 5.3, single writer).

#pragma once

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include "esphome/components/display/display.h"
#include "vc_draw.h"

namespace esphome {
namespace vc {
namespace panel {

using display::BaseFont;
using display::Display;
using display::TextAlign;

// ---- geometry ---------------------------------------------------------------
constexpr int W = 320, H = 172;
// `VERIFY on the panel`: the LCD-1.47's corner radius is not in any datasheet
// found. 22 px (2.3 mm) is read off Waveshare's product photo. Too large is the
// safe side: the rim then pulls away from the glass corner into black, which
// looks deliberate; too small and the glass clips the rim at the corners.
constexpr int GLASS_R = 22;
constexpr int RIM_IN = 1;    // rim inset from the panel edge
constexpr int RIM_T = 3;     // solid band
constexpr int RIM_GLOW = 9;  // fade inwards
constexpr int PAD = 16;      // content inset; the rim and its fade live outside it
constexpr int COL_X = 18, COL_W = 46;  // the analogue column
constexpr int HERO_L = 76;             // hero area, right of the column
constexpr int HERO_R = W - PAD;
constexpr int BASE_HERO = 134;   // hero baseline; its digits reach up to y=40
constexpr int BASE_WALK = 161;   // walk-up line baseline
constexpr int STRIP_MID = 21;    // vertical centre of the top strip
// "°" beside the hero sits with its top on the digits' top: the hero's digit
// height (94 px at 132) minus the unit font's degree height (42 px at 52).
// Follows the two sizes in van-core.yaml; tools/panel_preview shows a drift.
constexpr int SUP_RAISE = 52;
// Action screens (COOKING / SLEEP / PARK) and toasts: the icon tile, sized to
// sit level with the 80 px ON / OFF beside it (owner, 2026-10-16: at 50 px it
// looked like a footnote). The state word is centred in what is left.
constexpr int TILE = 66, TILE_CX = 48, ACT_CY = 88;
constexpr int ACT_CX = (TILE_CX + TILE / 2 + 8 + HERO_R) / 2;
// The hold bar (D-33): its label, and the strip the button's fast path
// redraws and pushes on its own while the button is held.
constexpr int HOLD_LABEL = 152, HOLD_BAR_Y = 157, HOLD_BAR_H = 7;

// ---- palette ----------------------------------------------------------------
// ui/index.html's hues (OKLCH 80 / 27 / 245, neutrals at 85) pushed a little
// brighter for a black panel seen through a bezel. Red still means parked or
// broken and nothing else (D-31). Green is the one exception: warmer and
// calmer than the page's (owner, 2026-10-16: "too flashy" at hue 155, C .17).
namespace pal {
inline Color black() { return Color(0x00, 0x00, 0x00); }
inline Color fg() { return Color(0xf4, 0xf1, 0xed); }       // oklch .96 .006 85
inline Color muted() { return Color(0xae, 0xaa, 0xa4); }    // .74 - secondary text
inline Color dim() { return Color(0x71, 0x6e, 0x68); }      // .54 - tertiary, idle icons
inline Color track() { return Color(0x28, 0x26, 0x22); }    // .27 - empty tracks, pips
inline Color surface() { return Color(0x17, 0x16, 0x13); }  // .20 - chip when AC is off
inline Color ink() { return Color(0x12, 0x10, 0x0e); }      // text on a filled chip
inline Color ok() { return Color(0x89, 0xcc, 0x7b); }       // .78 .13 140 cooking, charge
inline Color warn() { return Color(0xfd, 0xba, 0x2f); }     // .83 .16 80  fridge AC, low
inline Color bad() { return Color(0xfc, 0x45, 0x40); }      // .66 .22 27  parked, fault
inline Color alt() { return Color(0x54, 0xb3, 0xfd); }      // .74 .14 245 sleep, water
// Fridge scale zones: the four colours above, dark enough to sit behind a
// bright marker.
inline Color z_cold() { return Color(0x1d, 0x4c, 0x72); }
inline Color z_ok() { return Color(0x3a, 0x37, 0x32); }
inline Color z_warm() { return Color(0x70, 0x4e, 0x0a); }
inline Color z_hard() { return Color(0x7c, 0x2b, 0x25); }
}  // namespace pal

// ---- icons: Material Design Icons code points (van-core.yaml lists them) -----
namespace icon {
constexpr const char *BT = "\U000F00AF";
constexpr const char *BT_OFF = "\U000F00B2";
constexpr const char *MOON = "\U000F0594";
constexpr const char *POT = "\U000F065A";
constexpr const char *CAR = "\U000F010B";
constexpr const char *SNOW = "\U000F0717";
constexpr const char *DROP = "\U000F058C";
constexpr const char *PARK = "\U000F03E3";
constexpr const char *UP = "\U000F0737";
constexpr const char *DOWN = "\U000F072E";
constexpr const char *HOLD = "\U000F0D77";
constexpr const char *CHECK = "\U000F012C";
constexpr const char *ALERT = "\U000F0026";
}  // namespace icon

struct Fonts {
  BaseFont *hero;    // Barlow Semi Condensed 700 - the one number per page
  BaseFont *unit;    // Barlow Semi Condensed 600 - "%" beside the hero
  BaseFont *big;     // Barlow Semi Condensed 700 - action screens and toasts
  BaseFont *title;   // Barlow 600 - strip title, chips (upper case)
  BaseFont *body;    // Barlow 500 - the walk-up line
  BaseFont *small;   // Barlow 500 - DIAG, secondary lines
  BaseFont *icon_s;  // MDI - strip
  BaseFont *icon_m;  // MDI - beside walk-up text
  BaseFont *icon_l;  // MDI - action screens, toasts
  BaseFont *soc;     // Barlow Semi Condensed 700 - ENERGY's charge, beside the cabin
  BaseFont *mid;     // Barlow Semi Condensed 600 - the cabin, and the charge's "%"
};

// Everything the panel shows, read once per frame. Plain values only.
struct State {
  // inverter and why
  bool ble = false, probe = false, parked = false, park_q = false, force = false;
  bool boot = false, manual = false, sleep = false, drive = false, ac_on = false;
  bool fridge_req = false, surplus_req = false;
  const char *reason = "";
  // energy
  bool have_soc = false, have_flow = false, have_rem = false;
  float soc = 0, win = 0, wout = 0, remaining_h = 0;
  // fridge
  float fridge_c = NAN, cabin_c = NAN;
  bool have_cabin = false;
  float t_off = 4, t_on = 7, t_hard = 10, sleep_ceiling = 6;
  float parked_d = 0;
  uint32_t manual_left_s = 0, manual_max_s = 3 * 3600;
  // paging (D-33)
  // 0 ENERGY 1 FRIDGE 2 WATER 3 COOKING (only while cooking) 4 SLEEP 5 PARK
  // 6 DIAG - the last stop, since nobody reads it in passing (owner, 2026-10-16)
  int page = 0;
  bool parked_view = false;  // the full-screen PARKED a wake shows (D-31)
  float hold_s = 3;          // `Button hold time`
  bool rim = true;           // `Panel frame`: the coloured state frame, on or off
  bool blink = false;        // flips each second: PARK?'s frame blinks like its LED
  bool holding = false;      // held past a tap, not yet acted
  float hold_frac = 0;
  // action toast (D-31): 0 none, 1 AC on, 2 extended, 3 clipped, 4 cancelled,
  // 5 parked off + cooking, 6 sleep on, 7 sleep off, 8 parked off
  int toast = 0;
  uint32_t toast_added_s = 0;
  bool toast_was_on = false;
  // diagnostics
  uint32_t uptime_s = 0, heap_kb = 0;
  bool have_charge_cap = false;
  float charge_cap = 0;
};

// =============================================================================
// Small helpers
// =============================================================================

inline int text_w(Display &it, BaseFont *f, const char *s) {
  int x1, y1, w, h;
  it.get_text_bounds(0, 0, s, f, TextAlign::TOP_LEFT, &x1, &y1, &w, &h);
  return w;
}

// What the inverter is doing, as a colour (the rim) and a word (the chip).
struct Look {
  Color c;
  const char *word;
  bool lit;  // false: AC off - no rim, quiet chip
};

inline Look look(const State &s) {
  if (s.parked) return {pal::bad(), "PARKED", true};
  if (s.park_q) return {pal::bad(), "PARK?", true};
  if (s.force && !s.boot) return {pal::bad(), "FAIL-SAFE", true};
  if (s.boot) return {pal::warn(), "STARTING", true};
  if (s.manual) return {pal::ok(), "COOKING", true};
  if (s.ac_on) return {pal::warn(), strcmp(s.reason, "surplus") == 0 ? "SOLAR" : "FRIDGE", true};
  return {pal::muted(), "AC OFF", false};
}

// The red cues follow the status LED (D-34) and are drawn whatever the `Panel
// frame` switch says (owner, 2026-10-16): steady red for a fault, blinking for
// PARK? - the LED's fast blink, at the panel's once a second - and steady red
// while parked. Only the other colours are a matter of taste.
inline bool rim_colour(const State &s, Color *c) {
  if (s.parked || (s.force && !s.boot)) { *c = pal::bad(); return true; }
  if (s.park_q) { *c = pal::bad(); return s.blink; }
  if (!s.rim) return false;
  if (s.boot && s.ac_on) { *c = pal::warn(); return true; }
  if (s.manual) { *c = pal::ok(); return true; }
  if (s.ac_on) { *c = pal::warn(); return true; }
  return false;
}

inline void draw_rim(Display &it, Color c) {
  rim(it, RIM_IN, RIM_IN, W - 2 * RIM_IN, H - 2 * RIM_IN, GLASS_R - RIM_IN, RIM_T, RIM_GLOW, c,
      pal::black());
}

// A filled chip with dark text, or a quiet one with light text.
inline int chip(Display &it, BaseFont *f, int right, int cy, const char *word, Color c, bool lit) {
  const int h = 20, w = text_w(it, f, word) + 18;
  const int x = right - w, y = cy - h / 2;
  const Color fill = lit ? c : pal::surface();
  capsule(it, x, y, w, h, fill, pal::black());
  it.print(x + w / 2, cy + 5, f, lit ? pal::ink() : c, TextAlign::BASELINE_CENTER, word, fill);
  return x;
}

// An icon glyph whose box is centred on (cx, cy). MDI glyphs fill their em
// square, so centring on the line box centres the drawing.
inline void glyph(Display &it, BaseFont *f, int cx, int cy, const char *g, Color c,
                  Color under = Color(0, 0, 0)) {
  it.print(cx, cy, f, c, TextAlign::CENTER, g, under);
}

// A number with its unit beside it in a smaller face, centred as one group on
// cx. `raise` lifts the unit off the baseline ("°" sits on the digits' top).
inline void number_unit(Display &it, BaseFont *nf, BaseFont *uf, int cx, int base,
                        const char *num, Color c, const char *unit, Color unit_c, int raise = 0) {
  const int gap = raise ? 2 : 4;
  const int wn = text_w(it, nf, num);
  const int wu = unit ? text_w(it, uf, unit) + gap : 0;
  const int x = cx - (wn + wu) / 2;
  it.print(x, base, nf, c, TextAlign::BASELINE_LEFT, num);
  if (unit) it.print(x + wn + gap, base - raise, uf, unit_c, TextAlign::BASELINE_LEFT, unit);
}

// The hero number: the 132 px face, with "%" on its baseline or "°" (`sup`)
// on its digits' top.
inline void hero(Display &it, const Fonts &f, int cx, const char *num, Color c,
                 const char *unit = nullptr, Color unit_c = Color(0, 0, 0), bool sup = false) {
  number_unit(it, f.hero, f.unit, cx, BASE_HERO, num, c, unit, unit_c, sup ? SUP_RAISE : 0);
}

// The walk-up line: an optional icon, then text, from x.
inline void walk(Display &it, const Fonts &f, int x, const char *ic, Color ic_c, const char *txt,
                 Color c) {
  if (ic) {
    glyph(it, f.icon_m, x + 10, BASE_WALK - 7, ic, ic_c);
    x += 25;
  }
  it.print(x, BASE_WALK, f.body, c, TextAlign::BASELINE_LEFT, txt);
}

inline void walk_centred(Display &it, const Fonts &f, int cx, const char *ic, Color ic_c,
                         const char *txt, Color c) {
  const int w = text_w(it, f.body, txt) + (ic ? 25 : 0);
  walk(it, f, cx - w / 2, ic, ic_c, txt, c);
}

// SOC bands shared with the web page and the widget (30 / 15 %).
inline Color soc_colour(const State &s) {
  if (!s.have_soc) return pal::dim();
  return s.soc > 30.0f ? pal::ok() : s.soc > 15.0f ? pal::warn() : pal::bad();
}

// =============================================================================
// Analogue column
// =============================================================================

// The battery as a vessel - the widget's LiquidFill shape (D-26) - filled to
// SOC. Length reads from further away than digits, and degrades gracefully:
// at four metres it is still obviously nearly empty.
inline void vessel(Display &it, int x, int y, int w, int h, float frac, Color fill, Color edge) {
  round_rect(it, x + w / 2 - 9, y - 6, 18, 9, 3, edge, pal::black());  // terminal
  round_stroke(it, x, y, w, h, 12, 3, edge, pal::black());
  if (frac <= 0.0f) return;
  frac = std::min(1.0f, frac);
  const int ix = x + 6, iy = y + 6, iw = w - 12, ih = h - 12;
  const float level = iy + ih * (1.0f - frac);
  sdf_fill(it, ix, (int) floorf(level), ix + iw, iy + ih, fill, pal::black(),
           [&](float px, float py) {
             return std::max(sd_round_box(px, py, ix, iy, ix + iw, iy + ih, 7), level - py);
           });
}

// The fridge's deadband as a vertical scale, warm at the top: not "4.6" but
// "4.6, and this far from the next run". Zones are satisfied / hysteresis /
// cooling wanted / the hard override, from 0 to 12 degC.
inline void fridge_scale(Display &it, const State &s, int x, int y, int w, int h, float t_ceil,
                         Color marker) {
  const float LO = 0.0f, HI = 12.0f;
  auto ypos = [&](float v) {
    v = std::max(LO, std::min(HI, v));
    return y + h * (1.0f - (v - LO) / (HI - LO));
  };
  const float y_off = ypos(s.t_off), y_on = std::min(ypos(t_ceil), y_off), y_hard = ypos(s.t_hard);
  const int r = w / 2;
  sdf_shade(
      it, x, y, x + w, y + h, pal::black(),
      [&](float px, float py) { return sd_round_box(px, py, x, y, x + w, y + h, r); },
      [&](float, float py) {
        return py > y_off ? pal::z_cold() : py > y_on ? pal::z_ok() : py > y_hard ? pal::z_warm()
                                                                                  : pal::z_hard();
      });
  if (!s.probe) return;
  const int my = (int) lroundf(ypos(s.fridge_c));
  capsule(it, x - 6, my - 6, w + 12, 12, pal::black(), pal::black());  // knock-out
  capsule(it, x - 4, my - 4, w + 8, 8, marker, pal::black());
}

// A tinted rounded tile with an icon: the action screens' left column.
inline void tile(Display &it, const Fonts &f, int cx, int cy, const char *ic, Color c, bool lit) {
  const int s = TILE;
  const Color fill = lit ? mix(pal::black(), c, 0.22f) : pal::surface();
  round_rect(it, cx - s / 2, cy - s / 2, s, s, 18, fill, pal::black());
  glyph(it, f.icon_l, cx, cy, ic, lit ? c : pal::dim(), fill);
}

// =============================================================================
// Strip: page name, where it sits in the cycle, link state, the AC chip
// =============================================================================

inline void strip(Display &it, const Fonts &f, const State &s) {
  static const char *NAMES[7] = {"ENERGY", "FRIDGE", "WATER", "COOKING", "SLEEP", "PARK", "DIAG"};
  // Right to left first: the state, then the icons. Whatever room is left
  // belongs to the page name and its pips.
  const Look lk = look(s);
  int right = chip(it, f.title, W - PAD, STRIP_MID, lk.word, lk.c, lk.lit) - 7;
  // AC itself, whatever asked for it (owner, 2026-10-16): lit when the
  // inverter is on, struck when it is off. The chip beside it says why. Always
  // drawn, so the icons to its left never shift as AC comes and goes.
  ac_mark(it, right - 19, STRIP_MID - 9.5f, 19, s.ac_on ? pal::fg() : pal::dim(), pal::black(),
          !s.ac_on, 1.9f);
  right -= 25;
  // Then the modes and exceptions, each only while it holds.
  auto icon_at = [&](const char *g, Color c) {
    glyph(it, f.icon_s, right - 10, STRIP_MID, g, c);
    right -= 24;
  };
  if (s.manual) icon_at(icon::POT, pal::ok());
  if (s.sleep) icon_at(icon::MOON, pal::alt());
  if (s.drive) icon_at(icon::CAR, pal::muted());
  // The link only when it is gone: connected is the normal case, and the
  // strip has room for news, not reassurance.
  if (!s.ble) icon_at(icon::BT_OFF, pal::bad());

  int x = PAD + 2;
  it.print(x, STRIP_MID + 5, f.title, pal::muted(), TextAlign::BASELINE_LEFT, NAMES[s.page]);
  x += text_w(it, f.title, NAMES[s.page]) + 12;
  // One pip per screen in the cycle; COOKING only while there is any (D-33).
  // Left out when the icons need the room - rare (cooking in sleep mode with
  // drive inhibit on), and the page name above still says where you are.
  const int n = s.manual ? 7 : 6;
  if (x + 16 + (n - 1) * 9 > right + 4) return;
  for (int i = 0; i < 7; i++) {
    if (i == 3 && !s.manual) continue;
    if (i == s.page) {
      capsule(it, x, STRIP_MID - 2, 12, 5, pal::fg(), pal::black());
      x += 16;
    } else {
      disc(it, x + 2.5f, STRIP_MID + 0.5f, 2.5f, pal::track(), pal::black());
      x += 9;
    }
  }
}

// =============================================================================
// Pages
// =============================================================================

// ENERGY (owner, 2026-10-16): the charge, smaller than the other pages' hero
// to make room for the cabin beside it, and in / out under the cabin, each
// only while something flows. The net and the time left stay on the walk-up
// line. The vessel is still the part that reads from across the van.
constexpr int E_DIV = 202;        // the column rule
constexpr int E_BASE_SOC = 118;   // the charge's baseline; digits up to y=52

inline void page_energy(Display &it, const Fonts &f, const State &s) {
  const Color c = soc_colour(s);
  vessel(it, COL_X, 44, COL_W, 116, s.have_soc ? s.soc / 100.0f : 0.0f, c, pal::dim());
  char b[24];
  const int cx = (COL_X + COL_W + E_DIV) / 2 + 1;
  if (s.have_soc) {
    snprintf(b, sizeof b, "%.0f", s.soc);
    // "100 %" does not fit the column at this size; a full vessel says it anyway.
    number_unit(it, f.soc, f.mid, cx, E_BASE_SOC, b, c, s.soc < 99.5f ? "%" : nullptr,
                pal::muted());
  } else {
    number_unit(it, f.soc, f.mid, cx, E_BASE_SOC, "--", pal::dim(), nullptr, pal::dim());
  }

  it.vertical_line(E_DIV, 48, 90, pal::track());
  const int rx = E_DIV + 9, rr = HERO_R;
  it.print(rx, 54, f.small, pal::dim(), TextAlign::BASELINE_LEFT, "CABIN");
  if (s.have_cabin) snprintf(b, sizeof b, "%.1f\u00b0", s.cabin_c);
  it.print(rx, 88, f.mid, s.have_cabin ? pal::fg() : pal::dim(), TextAlign::BASELINE_LEFT,
           s.have_cabin ? b : "--");
  int y = 116;
  auto flow = [&](const char *k, float w, Color vc) {
    it.print(rx, y, f.small, pal::dim(), TextAlign::BASELINE_LEFT, k);
    // A kilowatt load in watts is one digit too wide for the column.
    if (w >= 999.5f) snprintf(b, sizeof b, "%.1f kW", w / 1000.0f);
    else snprintf(b, sizeof b, "%.0f W", w);
    it.print(rr, y, f.body, vc, TextAlign::BASELINE_RIGHT, b);
    y += 22;
  };
  if (s.have_flow && s.win >= 5.0f) flow("IN", s.win, pal::ok());
  if (s.have_flow && s.wout >= 5.0f) flow("OUT", s.wout, pal::fg());

  if (!s.have_flow) {
    walk(it, f, HERO_L, nullptr, pal::dim(), "no station data", pal::dim());
    return;
  }
  const float net = s.win - s.wout;
  const bool flat = fabsf(net) < 5.0f;
  const Color nc = flat ? pal::muted() : net > 0 ? pal::ok() : pal::warn();
  snprintf(b, sizeof b, "%+.0f W", net);
  walk(it, f, HERO_L, flat ? nullptr : net > 0 ? icon::UP : icon::DOWN, nc, b, nc);
  if (!s.have_rem) {
    it.print(HERO_R, BASE_WALK, f.body, pal::dim(), TextAlign::BASELINE_RIGHT, "--");
  } else if (net > 5.0f) {
    it.print(HERO_R, BASE_WALK, f.body, pal::ok(), TextAlign::BASELINE_RIGHT, "charging");
  } else {
    // Rounded to what it can know (D-26): tenths only while it is short.
    if (s.remaining_h < 10.0f) snprintf(b, sizeof b, "%.1f h left", s.remaining_h);
    else snprintf(b, sizeof b, "%.0f h left", s.remaining_h);
    it.print(HERO_R, BASE_WALK, f.body, pal::muted(), TextAlign::BASELINE_RIGHT, b);
  }
}

inline void page_fridge(Display &it, const Fonts &f, const State &s, uint32_t manual_min) {
  // In sleep mode the ceiling IS the on-threshold, and an 8 degC ceiling is a
  // food decision the automation must never make silently (section 6).
  const float ceil_c = s.sleep ? s.sleep_ceiling : s.t_on;
  Color c;
  char b[48];
  if (s.probe) {
    c = s.fridge_c > s.t_hard ? pal::bad() : s.fridge_c > ceil_c ? pal::warn() : pal::fg();
    // -10.5 is five glyphs and does not fit; below -10 nobody needs tenths.
    snprintf(b, sizeof b, s.fridge_c <= -10.0f ? "%.0f" : "%.1f", s.fridge_c);
  } else {
    c = pal::bad();
    snprintf(b, sizeof b, "--");
  }
  fridge_scale(it, s, COL_X + 9, 42, 28, 120, ceil_c, c);
  hero(it, f, (HERO_L + HERO_R) / 2, b, c, s.probe ? "°" : nullptr, pal::muted(), true);

  // Which mode owns the fridge right now. The ceiling in force is named in
  // every state that has one (never silent, section 6).
  if (!s.probe) {
    walk(it, f, HERO_L, icon::ALERT, pal::bad(), "probe stale · AC on", pal::bad());
  } else if (s.parked) {
    snprintf(b, sizeof b, "parked %.1f d · fridge off", s.parked_d);
    walk(it, f, HERO_L, icon::PARK, pal::bad(), b, pal::bad());
  } else if (s.park_q) {
    walk(it, f, HERO_L, icon::PARK, pal::bad(), "PARK? not confirmed", pal::bad());
  } else if (s.manual) {
    snprintf(b, sizeof b, "cooking · %u min left", (unsigned) manual_min);
    walk(it, f, HERO_L, icon::POT, pal::ok(), b, pal::ok());
  } else if (s.sleep) {
    snprintf(b, sizeof b, "coasting up to %.1f°", ceil_c);
    walk(it, f, HERO_L, icon::MOON, pal::alt(), b, pal::alt());
  } else if (s.drive) {
    walk(it, f, HERO_L, icon::CAR, pal::muted(), "drive inhibit · coasting", pal::muted());
  } else if (s.fridge_req) {
    snprintf(b, sizeof b, "cooling to %.1f°", s.t_off);
    walk(it, f, HERO_L, icon::SNOW, pal::warn(), b, pal::warn());
  } else {
    snprintf(b, sizeof b, "cools again above %.1f°", ceil_c);
    walk(it, f, HERO_L, icon::CHECK, pal::muted(), b, pal::muted());
  }
}

inline void page_water(Display &it, const Fonts &f, const State &) {
  // Degrades rather than blanks: van-water is a separate box and its absence
  // is normal, never a fault.
  // HOOK: when it lands, the hero is the BINDING CONSTRAINT in litres -
  // min(fresh remaining, grey headroom) - with the binding tank named in the
  // walk-up line. Two independent bars invite reading the reassuring one
  // (section 9 Phase 2).
  vessel(it, COL_X, 44, COL_W, 116, 0.0f, pal::alt(), pal::track());
  hero(it, f, (HERO_L + HERO_R) / 2, "--", pal::dim());
  walk(it, f, HERO_L, icon::DROP, pal::dim(), "van-water not installed", pal::dim());
}

inline void page_diag(Display &it, const Fonts &f, const State &s) {
  // DELIBERATELY DENSE, and the one page with no hero: it is read at 30 cm
  // with a multimeter in the other hand.
  char b[32];
  auto row = [&](int col, int n, const char *k, Color vc, const char *v) {
    const int x0 = col ? 168 : PAD + 2, x1 = col ? W - PAD - 2 : 152;
    const int y = 54 + n * 20;
    it.print(x0, y, f.small, pal::dim(), TextAlign::BASELINE_LEFT, k);
    it.print(x1, y, f.small, vc, TextAlign::BASELINE_RIGHT, v);
    if (n < 5) it.horizontal_line(x0, y + 6, x1 - x0, pal::track());
  };
  row(0, 0, "BLE", s.ble ? pal::fg() : pal::bad(), s.ble ? "up" : "DOWN");
  row(0, 1, "probe", s.probe ? pal::fg() : pal::bad(), s.probe ? "ok" : "STALE");
  if (s.probe) snprintf(b, sizeof b, "%.1f°", s.fridge_c);
  row(0, 2, "fridge", s.probe ? pal::fg() : pal::bad(), s.probe ? b : "--");
  if (s.have_cabin) snprintf(b, sizeof b, "%.1f°", s.cabin_c);
  row(0, 3, "cabin", s.have_cabin ? pal::fg() : pal::dim(), s.have_cabin ? b : "--");
  snprintf(b, sizeof b, "%uh %um", (unsigned) (s.uptime_s / 3600u), (unsigned) ((s.uptime_s / 60u) % 60u));
  row(0, 4, "uptime", pal::fg(), b);
  row(0, 5, "reason", s.ac_on ? pal::warn() : pal::muted(), s.reason);

  row(1, 0, "AP", pal::fg(), "192.168.4.1");
  snprintf(b, sizeof b, "%u kB", (unsigned) s.heap_kb);
  row(1, 1, "heap", pal::fg(), b);
  snprintf(b, sizeof b, "F%d M%d S%d X%d P%d", s.fridge_req, s.manual, s.surplus_req, s.force,
           s.parked);
  row(1, 2, "flags", pal::fg(), b);
  row(1, 3, "sleep", s.sleep ? pal::alt() : pal::muted(), s.sleep ? "on" : "off");
  row(1, 4, "drive inh", s.drive ? pal::fg() : pal::muted(), s.drive ? "on" : "off");
  if (s.have_charge_cap) snprintf(b, sizeof b, "%.0f%%", s.charge_cap);
  row(1, 5, "charge cap", s.have_charge_cap ? pal::fg() : pal::dim(), s.have_charge_cap ? b : "--");
}

// COOKING / SLEEP / PARK (D-33): what a hold does here. The big word is the
// state, a second line states a consequence where there is one, and the
// walk-up line says what holding does. The tile is short, so the walk-up line
// gets the full width here - the hold wording is the longest on the panel.
inline void action_body(Display &it, const Fonts &f, const char *word, Color c, const char *line2,
                        Color c2) {
  it.print(ACT_CX, line2 ? 112 : 117, f.big, c, TextAlign::BASELINE_CENTER, word);
  if (line2) it.print(ACT_CX, 134, f.small, c2, TextAlign::BASELINE_CENTER, line2);
}

inline void page_action(Display &it, const Fonts &f, const State &s) {
  char b[48], h[48];
  const int cx = TILE_CX, cy = ACT_CY;
  Color hc = pal::muted();
  if (s.page == 3) {
    // The ring is the time left as a share of the 3 h ceiling.
    const float frac = s.manual_max_s ? (float) s.manual_left_s / s.manual_max_s : 0.0f;
    ring_gauge(it, cx, cy, TILE / 2 - 4, 7, frac, pal::ok(), pal::track(), pal::black());
    glyph(it, f.icon_l, cx, cy, icon::POT, pal::ok());
    snprintf(b, sizeof b, "%u:%02u", (unsigned) (s.manual_left_s / 3600u),
             (unsigned) ((s.manual_left_s / 60u) % 60u));
    action_body(it, f, b, pal::ok(), nullptr, pal::muted());
    snprintf(h, sizeof h, "hold %.0f s · stop cooking", s.hold_s);
  } else if (s.page == 4) {
    tile(it, f, cx, cy, icon::MOON, pal::alt(), s.sleep);
    if (s.sleep) snprintf(b, sizeof b, "fridge ceiling %.1f° tonight", s.sleep_ceiling);
    action_body(it, f, s.sleep ? "ON" : "OFF", s.sleep ? pal::alt() : pal::dim(),
                s.sleep ? b : nullptr, pal::muted());
    snprintf(h, sizeof h, "hold %.0f s · sleep %s", s.hold_s, s.sleep ? "off" : "on");
  } else {
    // Arming is the first hold; the second confirms (D-14), and the
    // consequence is spelled out before it, not after.
    tile(it, f, cx, cy, icon::PARK, pal::bad(), s.parked || s.park_q);
    if (s.parked) {
      action_body(it, f, "ON", pal::bad(), "fridge off, fail-safe disarmed", pal::muted());
      snprintf(h, sizeof h, "hold %.0f s · leave parked mode", s.hold_s);
    } else if (s.park_q) {
      action_body(it, f, "PARK?", pal::bad(), "fridge OFF, fail-safe disarmed", pal::fg());
      snprintf(h, sizeof h, "hold %.0f s again to confirm", s.hold_s);
      hc = pal::bad();
    } else {
      action_body(it, f, "OFF", pal::dim(), nullptr, pal::dim());
      snprintf(h, sizeof h, "hold %.0f s · park (asks first)", s.hold_s);
    }
  }
  walk_centred(it, f, W / 2, icon::HOLD, pal::dim(), h, hc);
}

// The full-screen PARKED a wake shows while parked (D-31). A solid red card,
// because the hazard in a parked van is loading food into a fridge that is off
// on purpose (section 6), and a red card is the one thing on this panel that
// reads from the door. No rim: the card is the signal, a rim around it is noise.
inline void parked_view(Display &it, const Fonts &f, const State &s) {
  const int in = 7;
  round_rect(it, in, in, W - 2 * in, 122, GLASS_R - in, pal::bad(), pal::black());
  it.print(W / 2, 86, f.big, pal::ink(), TextAlign::BASELINE_CENTER, "PARKED", pal::bad());
  char b[48];
  snprintf(b, sizeof b, "fridge OFF · %.1f days", s.parked_d);
  it.print(W / 2, 114, f.body, pal::ink(), TextAlign::BASELINE_CENTER, b, pal::bad());
  snprintf(b, sizeof b, "hold %.0f s · leave + cooking AC", s.hold_s);
  walk_centred(it, f, W / 2, icon::HOLD, pal::dim(), b, pal::muted());
}

// =============================================================================
// Toasts (D-31): what the button just did, for 2.5 s. They own the whole
// panel, rim included, so one cannot be read as a page.
// =============================================================================

inline void toast(Display &it, const Fonts &f, const State &s) {
  const int k = s.toast;
  const unsigned lh = s.manual_left_s / 3600u, lm = (s.manual_left_s / 60u) % 60u;
  const Color col = k == 4 ? pal::muted() : k == 3 ? pal::warn() : (k == 6 || k == 7) ? pal::alt()
                                                                                      : pal::ok();
  if (s.rim) draw_rim(it, k == 4 ? pal::dim() : col);
  char b[48], left[24];
  if (lh) snprintf(left, sizeof left, "%u h %02u left", lh, lm);
  else snprintf(left, sizeof left, "%u min left", lm);

  const int icx = 66, icy = 84, wx = 210, wy = 112;
  const bool chip_top = k == 5 || k == 8;
  if (chip_top) {
    // Leaving parked mode re-arms the fail-safe: the one toast about more
    // than the timer, so it says so before anything else.
    const int w = text_w(it, f.title, "PARKED MODE OFF") + 24;
    capsule(it, W / 2 - w / 2, 14, w, 22, pal::bad(), pal::black());
    it.print(W / 2, 30, f.title, pal::ink(), TextAlign::BASELINE_CENTER, "PARKED MODE OFF",
             pal::bad());
  }
  const int dy = chip_top ? 8 : 0;

  if (k == 6 || k == 7) {
    tile(it, f, icx, icy, icon::MOON, pal::alt(), k == 6);
    it.print(wx, wy, f.big, k == 6 ? pal::alt() : pal::fg(), TextAlign::BASELINE_CENTER,
             k == 6 ? "ON" : "OFF");
    if (k == 6) snprintf(b, sizeof b, "fridge ceiling %.1f° tonight", s.sleep_ceiling);
    else snprintf(b, sizeof b, "normal schedule");
    walk_centred(it, f, W / 2, nullptr, col, b, pal::muted());
    return;
  }
  if (k == 8) {
    tile(it, f, icx, icy + dy, icon::PARK, pal::ok(), true);
    it.print(wx, wy + dy, f.big, pal::ok(), TextAlign::BASELINE_CENTER, "OFF");
    walk_centred(it, f, W / 2, nullptr, col, "fail-safe armed again", pal::ok());
    return;
  }
  if (k == 1 || k == 4 || k == 5) {
    ac_mark(it, icx - 34, icy + dy - 34, 68, k == 4 ? pal::muted() : col, pal::black(), k == 4, 5.0f);
    it.print(wx, wy + dy, f.big, k == 4 ? pal::fg() : col, TextAlign::BASELINE_CENTER,
             k == 4 ? "OFF" : "ON");
  } else {
    // A stopwatch whose ring is the time left as a share of the 3 h
    // ceiling, so "+30" also shows how much room is left above it.
    const float frac = s.manual_max_s ? (float) s.manual_left_s / s.manual_max_s : 0.0f;
    ring_gauge(it, icx, icy, 32, 8, frac, col, pal::track(), pal::black());
    snprintf(b, sizeof b, "%u:%02u", lh, lm);
    it.print(icx, icy + 7, f.body, pal::fg(), TextAlign::BASELINE_CENTER, b);
    const unsigned add_m = s.toast_added_s / 60u;
    if (k == 2 || add_m > 0) snprintf(b, sizeof b, "+%u", add_m);
    else snprintf(b, sizeof b, "MAX");
    it.print(wx, wy, f.big, col, TextAlign::BASELINE_CENTER, b);
  }
  if (k == 1 || k == 2) snprintf(b, sizeof b, "cooking AC · %s", left);
  else if (k == 5) snprintf(b, sizeof b, "fail-safe armed · cooking %u min", (unsigned) (s.manual_left_s / 60u));
  else if (k == 3) snprintf(b, sizeof b, "3 h ceiling · %s", left);
  else if (!s.toast_was_on) snprintf(b, sizeof b, "cooking AC was not on");
  else if (s.ac_on) snprintf(b, sizeof b, "AC stays on: %s", s.reason);
  else snprintf(b, sizeof b, "cooking AC cancelled");
  walk_centred(it, f, W / 2, nullptr, col, b, k == 4 ? pal::muted() : col);
}

// =============================================================================
// Hold bar (D-33): while the button is held past a tap and before the hold
// acts, what it is about to do, filling towards the hold time. Letting go
// before it fills is how you back out, so the words say what you would get.
// =============================================================================

// Where the bar sits and what it says - shared by the full redraw and the
// fast path, so the two cannot disagree.
struct HoldLook {
  const char *what;
  Color c;
  int x0, x1;
};

inline HoldLook hold_look(const State &s) {
  const int hp = (s.parked && s.parked_view) ? -1 : s.page;
  HoldLook h;
  if (hp == 3) h.what = "STOP COOKING";
  else if (hp == 4) h.what = s.sleep ? "SLEEP OFF" : "SLEEP ON";
  else if (hp == 5) h.what = s.parked ? "LEAVE PARKED MODE" : s.park_q ? "CONFIRM: PARK" : "PARK (asks first)";
  else h.what = s.parked ? "LEAVE PARKED + COOKING" : s.manual ? "COOKING +30 MIN" : "COOKING ON";
  h.c = (hp == 5 && !s.parked) ? pal::bad() : pal::ok();
  // Over the walk-up line only. Where the analogue column runs down beside
  // it (ENERGY, FRIDGE, WATER) the column stays whole.
  const bool wide = hp < 0 || hp >= 3;
  h.x0 = wide ? PAD : HERO_L - 4;
  h.x1 = HERO_R;
  return h;
}

// A rectangle, [x0, x1) x [y0, y1), in panel coordinates.
struct Rect {
  int x0, y0, x1, y1;
};

// The moving part alone. The button's fast path (van-core.yaml) calls this
// every 40 ms while the button is held and pushes only the returned strip to
// the glass - a few kB instead of the 110 kB a full redraw sends - which is
// what makes the fill smooth instead of stepping twice a second.
inline Rect hold_progress(Display &it, const State &s) {
  const HoldLook h = hold_look(s);
  const int x = h.x0 + 4, w = h.x1 - x;
  progress_bar(it, x, HOLD_BAR_Y, w, HOLD_BAR_H, s.hold_frac, h.c, pal::track(), pal::black());
  return {x, HOLD_BAR_Y, x + w, HOLD_BAR_Y + HOLD_BAR_H};
}

inline void hold_bar(Display &it, const Fonts &f, const State &s) {
  if (!s.holding) return;
  const HoldLook h = hold_look(s);
  it.filled_rectangle(h.x0, 140, h.x1 - h.x0, 26, pal::black());
  it.print(h.x0 + 4, HOLD_LABEL, f.title, pal::fg(), TextAlign::BASELINE_LEFT, h.what);
  hold_progress(it, s);
}

// =============================================================================

// A reading that exists but is NaN is a missing reading. ESPHome sensors hold
// NaN after a failed read or a timeout filter (a cabin probe that is not on
// the bus, 2026-10-16), and printed it is "nan" - in faces that have no letters.
inline State sanitised(const State &in) {
  State s = in;
  if (std::isnan(s.soc)) s.have_soc = false;
  if (std::isnan(s.win) || std::isnan(s.wout)) s.have_flow = false;
  if (std::isnan(s.remaining_h)) s.have_rem = false;
  if (std::isnan(s.cabin_c)) s.have_cabin = false;
  if (std::isnan(s.fridge_c)) s.probe = false;
  return s;
}

inline void draw(Display &it, const State &in, const Fonts &f) {
  const State s = sanitised(in);
  it.fill(pal::black());
  if (s.toast != 0) {
    toast(it, f, s);
    return;
  }
  if (s.parked && s.parked_view) {
    parked_view(it, f, s);
    hold_bar(it, f, s);
    return;
  }
  Color rc;
  if (rim_colour(s, &rc)) draw_rim(it, rc);
  strip(it, f, s);
  switch (s.page) {
    case 0: page_energy(it, f, s); break;
    case 1: page_fridge(it, f, s, s.manual_left_s / 60u); break;
    case 2: page_water(it, f, s); break;
    case 6: page_diag(it, f, s); break;
    default: page_action(it, f, s); break;
  }
  hold_bar(it, f, s);
}

}  // namespace panel
}  // namespace vc
}  // namespace esphome

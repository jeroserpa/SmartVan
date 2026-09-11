// vc_draw.h - drawing primitives the van-core panel needs and ESPHome lacks.
//
// Ported from the "van-core display simulator" artifact, which is the source of
// truth for this panel. Three things in that design have no ESPHome primitive:
// the segmented arc gauge, the IEC alternating-current mark, and the flag
// pills. They live here as free functions taking only plain values, so the page
// code in the YAML reads as LAYOUT rather than as trigonometry.
//
// Geometry note: the design is native 320x172 (van-core.yaml, the LCD-1.47);
// the bench rig is 240x135 and its call sites scale coordinates. Nothing in
// this file assumes either size.

#pragma once

#include <cmath>

#include "esphome/components/display/display.h"

namespace esphome {
namespace vc {

// The mockup's literal palette, so mockup and firmware cannot drift apart.
// This is NOT ui/index.html's palette: the panel is a black-background
// instrument, the web page is a dark-grey document. Deliberately different.
inline Color bg() { return Color(0x00, 0x00, 0x00); }
inline Color ink() { return Color(0x05, 0x05, 0x05); }   // text on a filled block
inline Color fg() { return Color(0xf2, 0xf2, 0xea); }
inline Color dim() { return Color(0x78, 0x80, 0x6f); }
inline Color faint() { return Color(0x31, 0x35, 0x2e); }  // unlit segments, hairlines
inline Color amber() { return Color(0xe8, 0x91, 0x2f); }
inline Color good() { return Color(0x2f, 0xd6, 0xc4); }   // turquoise
inline Color bad() { return Color(0xe2, 0x4b, 0x3a); }
inline Color water() { return Color(0x4a, 0x9f, 0xe0); }

// Segmented arc gauge: 24 wedges over a 240 degree sweep starting at 150.
//
// Discrete segments rather than a smooth sweep, straight from the design: it
// quantises to ~4%, which is honest about the resolution the P310 actually
// reports, and it draws as a few hundred short radial lines instead of
// per-pixel maths. Each wedge is filled by stepping a radial line across its
// angular width - crude, but the whole gauge costs well under a millisecond.
inline void arc_gauge(display::Display &it, int cx, int cy, int r_out, int r_in, float pct,
                      Color on, Color off) {
  const float START = 150.0f * (float) M_PI / 180.0f;
  const float SWEEP = 240.0f * (float) M_PI / 180.0f;
  const int N = 24;
  const float PAD = 0.013f;
  for (int i = 0; i < N; i++) {
    float a0 = START + (SWEEP / N) * i + PAD;
    float a1 = START + (SWEEP / N) * (i + 1) - PAD;
    Color c = (((i + 0.5f) / N * 100.0f) <= pct) ? on : off;
    int steps = (int) ((a1 - a0) * r_out) + 2;  // fine enough that radials overlap
    for (int k = 0; k <= steps; k++) {
      float a = a0 + (a1 - a0) * k / steps;
      float ca = cosf(a), sa = sinf(a);
      it.line((int) (cx + r_in * ca), (int) (cy + r_in * sa), (int) (cx + r_out * ca),
              (int) (cy + r_out * sa), c);
    }
  }
}

// The IEC alternating-current mark: one full sine period inside a circle.
// MDI has no circle-and-sine glyph, so the design draws it and so does this.
// `struck` adds the diagonal for the AC-off state.
inline void ac_symbol(display::Display &it, int x, int y, int size, Color c, bool struck) {
  float s = size / 24.0f;
  int cx = x + (int) (12 * s), cy = y + (int) (12 * s);
  it.circle(cx, cy, (int) (10.3f * s), c);
  int px = 0, py = 0;
  for (int i = 0; i <= 24; i++) {
    float t = i / 24.0f;
    int nx = x + (int) ((5 + t * 14) * s);
    int ny = y + (int) ((12 - sinf(t * 2 * (float) M_PI) * 4.3f) * s);
    if (i > 0)
      it.line(px, py, nx, ny, c);
    px = nx;
    py = ny;
  }
  if (struck)
    it.line(x + (int) (4 * s), y + (int) (4 * s), x + (int) (20 * s), y + (int) (20 * s), c);
}

// A request-flag pill: filled when asserted, hairline outline when not. Shape
// carries the state as well as colour, so it still reads on a washed-out panel
// in daylight. The label is blended against the fill, not against black:
// with 4-bit fonts a glyph anti-aliased toward the wrong ground grows a fringe.
inline void pill(display::Display &it, display::BaseFont *font, int x, int y, int w, int h,
                 const char *label, bool on, Color on_color) {
  if (on) {
    it.filled_rectangle(x, y, w, h, on_color);
    it.print(x + w / 2, y + (h - 9) / 2, font, ink(), display::TextAlign::TOP_CENTER, label,
             on_color);
  } else {
    it.rectangle(x, y, w, h, faint());
    it.print(x + w / 2, y + (h - 9) / 2, font, dim(), display::TextAlign::TOP_CENTER, label);
  }
}

}  // namespace vc
}  // namespace esphome

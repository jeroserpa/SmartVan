// vc_draw.h - drawing primitives the van-core panel needs and ESPHome lacks.
//
// Two layers:
//
//   1. The original primitives (palette, arc_gauge, ac_symbol, pill), ported
//      from the "van-core display simulator" artifact. van-core-ttgo.yaml (the
//      240x135 bench rig) still draws with them, so they stay exactly as they
//      were.
//   2. Anti-aliased shapes for the 2026-10 panel redesign (nodes/vc_panel.h):
//      rounded rectangles, capsules, strokes, arcs and polylines. ESPHome draws
//      every shape aliased and 1 px wide, which is most of why the first panel
//      looked like a 1990s test pattern; its fonts were already anti-aliased,
//      its shapes never were.
//
// HOW THE ANTI-ALIASING WORKS, and its one rule. Each shape is a signed
// distance function: negative inside, positive outside, in pixels. A pixel's
// coverage is 0.5 - distance, clamped to [0, 1], and the pixel is written as
// a blend from `under` towards the shape colour by that coverage. The display
// buffer cannot be read back, so `under` MUST be the colour actually beneath
// the shape (black, or the fill of the card it sits on). Pass the wrong one
// and the edge grows a fringe - the same rule the 4-bit fonts already follow.
//
// Cost: only edge pixels are evaluated per pixel; flat areas go through
// filled_rectangle. Every shape on the panel together is well under the
// redraw's own cost at 1 Hz (section 2: the BLE task must not be starved).
//
// Geometry note: the redesign is native 320x172 (van-core.yaml, the LCD-1.47).
// Nothing in this file assumes a size.

#pragma once

#include <algorithm>
#include <cmath>

#include "esphome/components/display/display.h"

namespace esphome {
namespace vc {

// =============================================================================
// 1. Original primitives - van-core-ttgo.yaml draws with these. Do not change.
// =============================================================================

// The first mockup's literal palette. vc_panel.h has the redesign's.
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

// The IEC alternating-current mark, 1 px lines. `struck` adds the diagonal.
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

inline void ac_symbol_bold(display::Display &it, int x, int y, int size, Color c, bool struck,
                           int w = 3) {
  for (int dx = 0; dx < w; dx++)
    for (int dy = 0; dy < w; dy++)
      ac_symbol(it, x + dx, y + dy, size, c, struck);
}

// A request-flag pill, square-cornered: filled when asserted, outline when not.
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

// =============================================================================
// 2. Anti-aliased shapes (the 2026-10 redesign)
// =============================================================================

// a -> b by t in [0, 1]. Colour maths in gamma space: wrong in theory, and on a
// 16-bit panel indistinguishable from right.
inline Color mix(Color a, Color b, float t) {
  if (t <= 0.0f) return a;
  if (t >= 1.0f) return b;
  return Color((uint8_t) (a.r + (b.r - a.r) * t + 0.5f), (uint8_t) (a.g + (b.g - a.g) * t + 0.5f),
               (uint8_t) (a.b + (b.b - a.b) * t + 0.5f));
}

// Coverage of a pixel whose centre is `d` px from the edge (negative = inside).
inline float coverage(float d) { return d <= -0.5f ? 1.0f : d >= 0.5f ? 0.0f : 0.5f - d; }

// Signed distance from (px, py) to a rounded box spanning [x0, x1) x [y0, y1).
inline float sd_round_box(float px, float py, float x0, float y0, float x1, float y1, float r) {
  const float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f;
  const float qx = fabsf(px - cx) - ((x1 - x0) * 0.5f - r);
  const float qy = fabsf(py - cy) - ((y1 - y0) * 0.5f - r);
  const float ox = std::max(qx, 0.0f), oy = std::max(qy, 0.0f);
  return sqrtf(ox * ox + oy * oy) + std::min(std::max(qx, qy), 0.0f) - r;
}

// Signed distance from (px, py) to the segment a-b.
inline float sd_segment(float px, float py, float ax, float ay, float bx, float by) {
  const float vx = bx - ax, vy = by - ay, wx = px - ax, wy = py - ay;
  const float l2 = vx * vx + vy * vy;
  float t = l2 > 0.0f ? (wx * vx + wy * vy) / l2 : 0.0f;
  t = std::max(0.0f, std::min(1.0f, t));
  const float dx = wx - vx * t, dy = wy - vy * t;
  return sqrtf(dx * dx + dy * dy);
}

// Rasterise any signed distance function over the box [x0, x1) x [y0, y1).
// The general tool: everything below either calls this or is a fast path
// around it for the flat parts.
template<typename SDF>
void sdf_fill(display::Display &it, int x0, int y0, int x1, int y1, Color c, Color under, SDF sdf) {
  for (int y = y0; y < y1; y++)
    for (int x = x0; x < x1; x++) {
      const float a = coverage(sdf(x + 0.5f, y + 0.5f));
      if (a <= 0.0f) continue;
      it.draw_pixel_at(x, y, a >= 1.0f ? c : mix(under, c, a));
    }
}

// The same, with the colour chosen per pixel - for shapes whose fill varies
// inside them (the fridge scale's zones). `shade(px, py)` returns the colour.
template<typename SDF, typename SHADE>
void sdf_shade(display::Display &it, int x0, int y0, int x1, int y1, Color under, SDF sdf,
               SHADE shade) {
  for (int y = y0; y < y1; y++)
    for (int x = x0; x < x1; x++) {
      const float a = coverage(sdf(x + 0.5f, y + 0.5f));
      if (a <= 0.0f) continue;
      const Color c = shade(x + 0.5f, y + 0.5f);
      it.draw_pixel_at(x, y, a >= 1.0f ? c : mix(under, c, a));
    }
}

// Filled rounded rectangle. The body goes through filled_rectangle; only the
// four r x r corners are evaluated per pixel.
inline void round_rect(display::Display &it, int x, int y, int w, int h, int r, Color c,
                       Color under) {
  if (w <= 0 || h <= 0) return;
  r = std::max(0, std::min(r, std::min(w, h) / 2));
  if (r == 0) {
    it.filled_rectangle(x, y, w, h, c);
    return;
  }
  it.filled_rectangle(x + r, y, w - 2 * r, h, c);
  it.filled_rectangle(x, y + r, r, h - 2 * r, c);
  it.filled_rectangle(x + w - r, y + r, r, h - 2 * r, c);
  auto sd = [&](float px, float py) { return sd_round_box(px, py, x, y, x + w, y + h, r); };
  sdf_fill(it, x, y, x + r, y + r, c, under, sd);
  sdf_fill(it, x + w - r, y, x + w, y + r, c, under, sd);
  sdf_fill(it, x, y + h - r, x + r, y + h, c, under, sd);
  sdf_fill(it, x + w - r, y + h - r, x + w, y + h, c, under, sd);
}

// Capsule: a rounded rectangle whose ends are full semicircles.
inline void capsule(display::Display &it, int x, int y, int w, int h, Color c, Color under) {
  round_rect(it, x, y, w, h, std::min(w, h) / 2, c, under);
}

// Rounded-rectangle outline `t` px thick, drawn inside the box.
inline void round_stroke(display::Display &it, int x, int y, int w, int h, int r, int t, Color c,
                         Color under) {
  r = std::max(t, std::min(r, std::min(w, h) / 2));
  it.filled_rectangle(x + r, y, w - 2 * r, t, c);
  it.filled_rectangle(x + r, y + h - t, w - 2 * r, t, c);
  it.filled_rectangle(x, y + r, t, h - 2 * r, c);
  it.filled_rectangle(x + w - t, y + r, t, h - 2 * r, c);
  auto sd = [&](float px, float py) {
    const float o = sd_round_box(px, py, x, y, x + w, y + h, r);
    const float i = sd_round_box(px, py, x + t, y + t, x + w - t, y + h - t, r - t);
    return std::max(o, -i);
  };
  sdf_fill(it, x, y, x + r, y + r, c, under, sd);
  sdf_fill(it, x + w - r, y, x + w, y + r, c, under, sd);
  sdf_fill(it, x, y + h - r, x + r, y + h, c, under, sd);
  sdf_fill(it, x + w - r, y + h - r, x + w, y + h, c, under, sd);
}

inline void disc(display::Display &it, float cx, float cy, float r, Color c, Color under) {
  sdf_fill(it, (int) floorf(cx - r - 1), (int) floorf(cy - r - 1), (int) ceilf(cx + r + 1),
           (int) ceilf(cy + r + 1), c, under, [&](float px, float py) {
             return sqrtf((px - cx) * (px - cx) + (py - cy) * (py - cy)) - r;
           });
}

// The state rim: a solid band `t` px wide that follows the glass's rounded
// corners, fading inwards over `glow` px.
//
// It replaces a 5 px square frame whose corners the glass cut off, which was
// the single most "old" thing on the panel. The fade is not decoration: it
// roughly triples the lit area of the panel's at-distance channel without
// taking a pixel from the content, because content already keeps clear of the
// edge. Straight runs are one line per depth; only the corners are per pixel.
inline void rim(display::Display &it, int x, int y, int w, int h, int r, int t, int glow, Color c,
                Color under, float glow_from = 0.42f) {
  const int depth = t + glow;
  auto col_at = [&](float d) -> float {  // d: px inside the outer edge -> alpha
    if (d < t) return 1.0f;
    const float u = (d - t) / (float) glow;
    if (u >= 1.0f) return 0.0f;
    return glow_from * (1.0f - u) * (1.0f - u);
  };
  const int cb = std::max(r, depth);  // corner box: wide enough to hold the fade
  for (int k = 0; k < depth; k++) {
    const float a = col_at(k + 0.5f);
    if (a <= 0.0f) continue;
    const Color ck = mix(under, c, a);
    it.horizontal_line(x + cb, y + k, w - 2 * cb, ck);
    it.horizontal_line(x + cb, y + h - 1 - k, w - 2 * cb, ck);
    it.vertical_line(x + k, y + cb, h - 2 * cb, ck);
    it.vertical_line(x + w - 1 - k, y + cb, h - 2 * cb, ck);
  }
  auto corner = [&](int bx, int by) {
    for (int py = by; py < by + cb; py++)
      for (int px = bx; px < bx + cb; px++) {
        const float sd = sd_round_box(px + 0.5f, py + 0.5f, x, y, x + w, y + h, r);
        if (sd >= 0.5f) continue;                 // outside the glass
        const float edge = coverage(sd);          // AA on the outer curve
        const float a = col_at(std::max(0.0f, -sd)) * edge;
        if (a <= 0.004f) continue;
        it.draw_pixel_at(px, py, mix(under, c, a));
      }
  };
  corner(x, y);
  corner(x + w - cb, y);
  corner(x, y + h - cb);
  corner(x + w - cb, y + h - cb);
}

// An arc band of radius `rm` (centre line) and width `w`, with round caps,
// from angle a0 to a1 in radians, clockwise from 12 o'clock. Composited in
// one pass over `track` (the full ring, if it has one) so the progress band's
// edges blend into the track instead of into black.
inline void ring_gauge(display::Display &it, float cx, float cy, float rm, float w, float frac,
                       Color c, Color track, Color under) {
  const float hw = w * 0.5f;
  const float sweep = std::max(0.0f, std::min(1.0f, frac)) * 2.0f * (float) M_PI;
  const float ex = cx + rm * sinf(sweep), ey = cy - rm * cosf(sweep);  // end cap
  const float sx = cx, sy = cy - rm;                                    // start cap
  const int x0 = (int) floorf(cx - rm - hw - 1), x1 = (int) ceilf(cx + rm + hw + 1);
  const int y0 = (int) floorf(cy - rm - hw - 1), y1 = (int) ceilf(cy + rm + hw + 1);
  for (int y = y0; y < y1; y++)
    for (int x = x0; x < x1; x++) {
      const float px = x + 0.5f - cx, py = y + 0.5f - cy;
      const float d = sqrtf(px * px + py * py);
      const float ring = fabsf(d - rm) - hw;
      const float at = coverage(ring);
      if (at <= 0.0f) continue;
      float ang = atan2f(px, -py);  // 0 at 12 o'clock, clockwise positive
      if (ang < 0) ang += 2.0f * (float) M_PI;
      float dp;
      if (frac <= 0.0f) dp = 1e9f;
      else if (ang <= sweep) dp = ring;
      else
        dp = std::min(sd_segment(x + 0.5f, y + 0.5f, sx, sy, sx, sy),
                      sd_segment(x + 0.5f, y + 0.5f, ex, ey, ex, ey)) - hw;
      const float ap = coverage(dp);
      Color out = mix(under, track, at);
      out = mix(out, c, ap);
      it.draw_pixel_at(x, y, out);
    }
}

// A capsule progress bar filled to `frac`, its moving end anti-aliased at
// sub-pixel position so the fill glides rather than stepping a pixel at a
// time. Track and fill composited in one pass, so their edges blend into each
// other instead of into black.
inline void progress_bar(display::Display &it, int x, int y, int w, int h, float frac, Color c,
                         Color track, Color under) {
  frac = std::max(0.0f, std::min(1.0f, frac));
  const float r = h * 0.5f;
  const float end = x + std::max((float) h, w * frac);  // a dot, then a bar
  for (int py = y; py < y + h; py++)
    for (int px = x; px < x + w; px++) {
      const float cx = px + 0.5f, cy = py + 0.5f;
      const float at = coverage(sd_round_box(cx, cy, x, y, x + w, y + h, r));
      if (at <= 0.0f) continue;
      Color out = mix(under, track, at);
      if (frac > 0.0f) out = mix(out, c, coverage(sd_round_box(cx, cy, x, y, end, y + h, r)));
      it.draw_pixel_at(px, py, out);
    }
}

// The IEC alternating-current mark - one sine period inside a circle - drawn
// as real strokes. `size` is the circle's diameter; `struck` adds the bar.
inline void ac_mark(display::Display &it, float x, float y, float size, Color c, Color under,
                    bool struck, float stroke = 0.0f) {
  const float s = size / 24.0f;
  const float hw = (stroke > 0 ? stroke : std::max(1.6f, 2.1f * s)) * 0.5f;
  const float cx = x + 12 * s, cy = y + 12 * s, r = 10.6f * s;
  const int N = 16;
  float pts[N + 1][2];
  for (int i = 0; i <= N; i++) {
    const float t = i / (float) N;
    pts[i][0] = x + (5.4f + t * 13.2f) * s;
    pts[i][1] = y + (12.0f - sinf(t * 2.0f * (float) M_PI) * 4.4f) * s;
  }
  sdf_fill(it, (int) floorf(x - 1), (int) floorf(y - 1), (int) ceilf(x + size + 1),
           (int) ceilf(y + size + 1), c, under, [&](float px, float py) {
             float d = fabsf(sqrtf((px - cx) * (px - cx) + (py - cy) * (py - cy)) - r);
             for (int i = 0; i < N; i++)
               d = std::min(d, sd_segment(px, py, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1]));
             float shape = d - hw;
             if (!struck) return shape;
             // The bar knocks a gap out of the mark around itself, or at small
             // sizes it merges into the circle and the mark just looks smudged.
             const float bar = sd_segment(px, py, x + 3.2f * s, y + 3.2f * s, x + 20.8f * s,
                                          y + 20.8f * s);
             shape = std::max(shape, -(bar - 2.8f * hw));
             return std::min(shape, bar - hw);
           });
}

}  // namespace vc
}  // namespace esphome

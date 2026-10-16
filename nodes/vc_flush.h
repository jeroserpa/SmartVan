// vc_flush.h - push one rectangle of the framebuffer to the ST7789, not all of it.
//
// ESPHome's st7789v driver has one way to reach the glass: update(), which
// redraws the whole frame and sends all 110 kB at 20 MHz - ~150 ms of loop
// (M26). Fine once a second; far too slow for the hold bar (D-33), which at
// two full redraws a second visibly stepped instead of filling (owner,
// 2026-10-16). This sends just the strip that changed: the hold bar's few kB
// take a couple of milliseconds, so the bar can move 25 times a second.
//
// HOW, and why it is safe to rely on:
//   - The framebuffer is DisplayBuffer::buffer_, reached through a member
//     pointer formed in a class derived from DisplayBuffer. That is ordinary,
//     well-defined C++ (ST7789V itself is `final`, DisplayBuffer is not).
//   - The bus is driven with the SPI device's public calls, in the same
//     sequence ESPHome's own write_display_data() uses: CASET, RASET, RAMWR,
//     then pixels, with D/C low for each command byte.
//   - D/C is the panel's GPIO (pin_lcd_dc), set with gpio_set_level, because
//     the driver's pin object is protected. It is the same pin, already an
//     output, and only ever touched from the main loop, as the driver does.
//
// What it does NOT do: draw. The caller draws into the framebuffer through
// the display as usual (vc::panel::hold_progress) and then calls this. A wrong
// rectangle can only show stale pixels until the next full redraw, a second
// later - this file never reaches the arbiter or anything that commands.
//
// Device only: tools/panel_preview has no st7789v, and does not need this.

#pragma once

#include <algorithm>

#include "driver/gpio.h"
#include "esphome/components/st7789v/st7789v.h"

namespace esphome {
namespace vc {

// The panel as van-core.yaml's display: block configures it. Keep in step.
struct PanelGeom {
  int native_w;   // `width:`  (the panel's own, unrotated)
  int native_h;   // `height:`
  int off_x;      // `offset_width:`
  int off_y;      // `offset_height:`
  gpio_num_t dc;  // `dc_pin:`
};

namespace detail {
struct BufferOf : display::DisplayBuffer {
  static uint8_t *display::DisplayBuffer::*ptr() { return &BufferOf::buffer_; }
};
}  // namespace detail

// Push the logical rectangle [x0, x1) x [y0, y1) - display coordinates, after
// `rotation: 270` - from the framebuffer to the glass.
inline void flush_rect(st7789v::ST7789V *d, const PanelGeom &g, int x0, int y0, int x1, int y1) {
  uint8_t *buf = static_cast<display::DisplayBuffer *>(d)->*detail::BufferOf::ptr();
  if (buf == nullptr) return;
  // Rotation 270 (DisplayBuffer::draw_pixel_at): native x = logical y,
  // native y = native_h - 1 - logical x.
  x0 = std::max(0, x0);
  y0 = std::max(0, y0);
  x1 = std::min(g.native_h, x1);
  y1 = std::min(g.native_w, y1);
  if (x1 <= x0 || y1 <= y0) return;
  const int nx0 = y0, nx1 = y1 - 1;
  const int ny0 = g.native_h - x1, ny1 = g.native_h - 1 - x0;

  auto command = [&](uint8_t c) {
    gpio_set_level(g.dc, 0);
    d->write_byte(c);
    gpio_set_level(g.dc, 1);
  };
  auto window = [&](uint16_t a, uint16_t b) {
    const uint8_t w[4] = {(uint8_t) (a >> 8), (uint8_t) a, (uint8_t) (b >> 8), (uint8_t) b};
    d->write_array(w, 4);
  };

  d->enable();
  command(st7789v::ST7789_CASET);
  window(g.off_x + nx0, g.off_x + nx1);
  command(st7789v::ST7789_RASET);
  window(g.off_y + ny0, g.off_y + ny1);
  command(st7789v::ST7789_RAMWR);
  // Native rows are contiguous in the buffer (RGB565, big-endian, as the
  // driver stores them); gather a few into one transfer rather than one
  // SPI transaction per 14-byte row.
  const int row_bytes = (nx1 - nx0 + 1) * 2;
  static uint8_t chunk[1024];
  int used = 0;
  for (int ny = ny0; ny <= ny1; ny++) {
    if (used + row_bytes > (int) sizeof(chunk)) {
      d->write_array(chunk, used);
      used = 0;
    }
    const uint8_t *src = buf + (size_t) (nx0 + ny * g.native_w) * 2;
    if (row_bytes > (int) sizeof(chunk)) {  // a strip wider than the chunk
      d->write_array(src, row_bytes);
      continue;
    }
    std::copy(src, src + row_bytes, chunk + used);
    used += row_bytes;
  }
  if (used) d->write_array(chunk, used);
  d->disable();
}

}  // namespace vc
}  // namespace esphome

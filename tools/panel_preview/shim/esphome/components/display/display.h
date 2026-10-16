// Host stand-in for ESPHome's display.h - just enough of it for
// nodes/vc_draw.h and nodes/vc_panel.h to compile unchanged on a laptop.
//
// Every method here mirrors the ESPHome 2026.8 implementation it replaces
// (display.cpp, font.cpp): same per-pixel loops, same text alignment
// arithmetic, same 4-bit glyph blending. If ESPHome changes how it draws,
// this file is what has to follow, or the preview starts flattering the panel.

#pragma once

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace esphome {

struct Color {
  uint8_t r, g, b, w;
  constexpr Color() : r(0), g(0), b(0), w(0) {}
  constexpr Color(uint8_t red, uint8_t green, uint8_t blue) : r(red), g(green), b(blue), w(0) {}
  constexpr Color(uint8_t red, uint8_t green, uint8_t blue, uint8_t white)
      : r(red), g(green), b(blue), w(white) {}
};
static constexpr Color COLOR_OFF(0, 0, 0, 0);
static constexpr Color COLOR_ON(255, 255, 255, 255);

namespace display {

enum class TextAlign {
  TOP = 0x00,
  CENTER_VERTICAL = 0x01,
  BASELINE = 0x02,
  BOTTOM = 0x04,
  LEFT = 0x00,
  CENTER_HORIZONTAL = 0x08,
  RIGHT = 0x10,
  TOP_LEFT = TOP | LEFT,
  TOP_CENTER = TOP | CENTER_HORIZONTAL,
  TOP_RIGHT = TOP | RIGHT,
  CENTER_LEFT = CENTER_VERTICAL | LEFT,
  CENTER = CENTER_VERTICAL | CENTER_HORIZONTAL,
  CENTER_RIGHT = CENTER_VERTICAL | RIGHT,
  BASELINE_LEFT = BASELINE | LEFT,
  BASELINE_CENTER = BASELINE | CENTER_HORIZONTAL,
  BASELINE_RIGHT = BASELINE | RIGHT,
  BOTTOM_LEFT = BOTTOM | LEFT,
  BOTTOM_CENTER = BOTTOM | CENTER_HORIZONTAL,
  BOTTOM_RIGHT = BOTTOM | RIGHT,
};

class Display;

class BaseFont {
 public:
  virtual ~BaseFont() = default;
  virtual void print(int x, int y, Display *display, Color color, const char *text,
                     Color background) = 0;
  virtual void measure(const char *str, int *width, int *x_offset, int *baseline,
                       int *height) = 0;
};

class Display {
 public:
  virtual ~Display() = default;
  virtual void draw_pixel_at(int x, int y, Color color) = 0;
  virtual int get_width() = 0;
  virtual int get_height() = 0;

  void fill(Color color) { filled_rectangle(0, 0, get_width(), get_height(), color); }

  void horizontal_line(int x, int y, int width, Color color) {
    for (int i = x; i < x + width; i++) draw_pixel_at(i, y, color);
  }
  void vertical_line(int x, int y, int height, Color color) {
    for (int i = y; i < y + height; i++) draw_pixel_at(x, i, color);
  }
  void filled_rectangle(int x1, int y1, int width, int height, Color color) {
    for (int i = y1; i < y1 + height; i++) horizontal_line(x1, i, width, color);
  }
  void rectangle(int x1, int y1, int width, int height, Color color) {
    horizontal_line(x1, y1, width, color);
    horizontal_line(x1, y1 + height - 1, width, color);
    vertical_line(x1, y1, height, color);
    vertical_line(x1 + width - 1, y1, height, color);
  }
  void line(int x1, int y1, int x2, int y2, Color color) {
    const int32_t dx = abs(x2 - x1), sx = x1 < x2 ? 1 : -1;
    const int32_t dy = -abs(y2 - y1), sy = y1 < y2 ? 1 : -1;
    int32_t err = dx + dy;
    while (true) {
      draw_pixel_at(x1, y1, color);
      if (x1 == x2 && y1 == y2) break;
      int32_t e2 = 2 * err;
      if (e2 >= dy) { err += dy; x1 += sx; }
      if (e2 <= dx) { err += dx; y1 += sy; }
    }
  }
  void circle(int center_x, int center_xy, int radius, Color color) {
    int dx = -radius, dy = 0, err = 2 - 2 * radius, e2;
    do {
      draw_pixel_at(center_x - dx, center_xy + dy, color);
      draw_pixel_at(center_x + dx, center_xy + dy, color);
      draw_pixel_at(center_x + dx, center_xy - dy, color);
      draw_pixel_at(center_x - dx, center_xy - dy, color);
      e2 = err;
      if (e2 < dy) { err += ++dy * 2 + 1; if (-dx == dy && e2 <= dx) e2 = 0; }
      if (e2 > dx) err += ++dx * 2 + 1;
    } while (dx <= 0);
  }

  void get_text_bounds(int x, int y, const char *text, BaseFont *font, TextAlign align, int *x1,
                       int *y1, int *width, int *height) {
    int x_offset, baseline;
    font->measure(text, width, &x_offset, &baseline, height);
    auto x_align = TextAlign(int(align) & 0x18);
    auto y_align = TextAlign(int(align) & 0x07);
    switch (x_align) {
      case TextAlign::RIGHT: *x1 = x - *width - x_offset; break;
      case TextAlign::CENTER_HORIZONTAL: *x1 = x - (*width + x_offset) / 2; break;
      default: *x1 = x; break;
    }
    switch (y_align) {
      case TextAlign::BOTTOM: *y1 = y - *height; break;
      case TextAlign::BASELINE: *y1 = y - baseline; break;
      case TextAlign::CENTER_VERTICAL: *y1 = y - (*height) / 2; break;
      default: *y1 = y; break;
    }
  }

  void print(int x, int y, BaseFont *font, Color color, TextAlign align, const char *text,
             Color background = COLOR_OFF) {
    int x_start, y_start, width, height;
    get_text_bounds(x, y, text, font, align, &x_start, &y_start, &width, &height);
    font->print(x_start, y_start, this, color, text, background);
  }
  void print(int x, int y, BaseFont *font, Color color, const char *text,
             Color background = COLOR_OFF) {
    print(x, y, font, color, TextAlign::TOP_LEFT, text, background);
  }
  void printf(int x, int y, BaseFont *font, Color color, TextAlign align, const char *format, ...) {
    char buffer[256];
    va_list arg;
    va_start(arg, format);
    int ret = vsnprintf(buffer, sizeof(buffer), format, arg);
    va_end(arg);
    if (ret > 0) print(x, y, font, color, align, buffer);
  }
  void printf(int x, int y, BaseFont *font, Color color, const char *format, ...) {
    char buffer[256];
    va_list arg;
    va_start(arg, format);
    int ret = vsnprintf(buffer, sizeof(buffer), format, arg);
    va_end(arg);
    if (ret > 0) print(x, y, font, color, TextAlign::TOP_LEFT, buffer);
  }
};

}  // namespace display
}  // namespace esphome

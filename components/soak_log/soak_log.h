// soak_log.h - PSRAM ring-buffer logger, downloadable as CSV from a phone.
//
// The stand-in for the microSD log (CLAUDE.md section 9) until a card is
// fitted. Every `interval` it samples a fixed list of sensors into one binary
// row: 12 bytes of time/boot bookkeeping plus a float per column. At 10s and
// ~20 columns that is ~0.8 MB/day, so the default 4 MB holds about five days.
// When full, the oldest rows are overwritten.
//
// What survives what - the buffer sits in `.ext_ram_noinit`:
//   crash, watchdog, OTA, restart button   kept (header CRC + layout checked)
//   power cut, brownout                    LOST - PSRAM is volatile
//   reflash with a different column list   discarded (layout hash differs)
// So download before anything that cuts power to the board.
//
// Clock: there is no RTC and no SNTP. The /soak page sends the phone's time
// when it is opened, and every row after that carries wall-clock time. Across
// a software reset the clock is carried forward from the last row, with an
// error of about one interval plus the boot time; such rows are flagged
// `clock=2`, phone-synced rows `clock=1`, unknown `clock=0`.
//
// Routes, registered ahead of the stock web_server:
//   GET  /soak              status page, download and clear buttons
//   GET  /ui                the same page, with `serve_at_ui: true`
//   GET  /soak/status       JSON
//   GET  /soak/log.csv      CSV, streamed in chunks; ?last=N for the newest N rows
//   POST /soak/clock        ms=<unix ms>&tz=<minutes east of UTC>
//   POST /soak/clear        wipe the buffer
//
// Remote sensors: values from other nodes' REST API, fetched by a FreeRTOS
// task pinned away from the main loop. A missing peer costs that task a
// timeout and costs the BLE loop nothing.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "esphome/components/binary_sensor/binary_sensor.h"
#include "esphome/components/sensor/sensor.h"
#include "esphome/components/web_server_base/web_server_base.h"
#include "esphome/core/component.h"
#include "esphome/core/defines.h"
#include "esphome/core/helpers.h"

namespace esphome::soak_log {

static constexpr uint8_t MAX_BOOTS = 32;
static constexpr size_t MAX_COLUMNS = 40;  // keep in step with __init__.py

struct BootRecord {
  uint16_t boot;
  uint8_t reason;  // esp_reset_reason_t
  uint8_t clock;   // clock state at boot: 0 unknown, 2 carried
  uint32_t epoch;  // 0 if unknown
  uint32_t seq;    // rows ever written before this boot
};

struct Header {
  uint32_t magic;
  uint32_t layout;    // hash of the column list, row size and capacity
  uint32_t capacity;  // rows
  uint32_t head;      // next slot to write
  uint32_t count;     // valid rows
  uint32_t seq;       // rows ever written, monotonic across reboots
  uint32_t last_epoch;
  int32_t tz_min;     // phone's UTC offset, for the local-time column
  uint16_t boot;
  uint16_t nboots;    // total, the ring below holds the last MAX_BOOTS
  BootRecord boots[MAX_BOOTS];
  uint32_t crc;       // over everything above
};

class SoakLog : public Component, public AsyncWebHandler {
 public:
  void set_base(web_server_base::WebServerBase *base) { this->base_ = base; }
  void set_row_interval(uint32_t ms) { this->interval_ms_ = ms; }
  void set_stale_after(uint32_t ms) { this->stale_ms_ = ms; }
  void set_remote_interval(uint32_t ms) { this->remote_interval_ms_ = ms; }
  void set_serve_ui(bool on) { this->serve_ui_ = on; }
  void add_sensor_column(const char *name, sensor::Sensor *s, uint8_t decimals) {
    this->columns_.push_back({name, s, nullptr, decimals});
  }
  void add_binary_column(const char *name, binary_sensor::BinarySensor *b) {
    this->columns_.push_back({name, nullptr, b, 0});
  }
  void add_remote(const char *url, sensor::Sensor *s) {
    auto *r = new Remote();  // NOLINT(cppcoreguidelines-owning-memory) lives forever
    r->url = url;
    r->sensor = s;
    this->remotes_.push_back(r);
  }

  void setup() override;
  void loop() override;
  void dump_config() override;
  // Before web_server (WIFI - 1): handlers are tried in registration order.
  float get_setup_priority() const override { return setup_priority::WIFI + 1.0f; }

  bool canHandle(AsyncWebServerRequest *request) const override;
  void handleRequest(AsyncWebServerRequest *request) override;
  bool isRequestHandlerTrivial() const override { return false; }

  uint32_t rows() const { return this->hdr_->count; }
  uint16_t boot() const { return this->hdr_->boot; }
  // 0 unknown, 1 phone-synced, 2 carried across a reboot.
  uint8_t clock_state() const { return this->clock_state_; }

 protected:
  struct Column {
    const char *name;
    sensor::Sensor *sensor;
    binary_sensor::BinarySensor *binary;
    uint8_t decimals;
  };
  struct Remote {
    std::string url;
    sensor::Sensor *sensor{nullptr};
    float value{NAN};
    int64_t last_ok_ms{0};
    uint32_t ok{0};
    uint32_t fail{0};
    bool fresh{false};
    bool stale_published{false};
  };

  size_t row_size_() const { return 12 + 4 * this->columns_.size(); }
  uint8_t *row_ptr_(uint32_t slot) const;
  uint32_t layout_hash_() const;
  void seal_();  // recompute header CRC; call with mutex_ held
  bool header_valid_() const;
  void reset_buffer_();
  void write_row_();
  uint32_t now_epoch_() const;

  static void remote_task_(void *arg);
  static bool fetch_value_(const std::string &url, float *out);

  void send_page_(AsyncWebServerRequest *request);
  void send_status_(AsyncWebServerRequest *request);
  void send_csv_(AsyncWebServerRequest *request);

  web_server_base::WebServerBase *base_{nullptr};
  uint32_t interval_ms_{10000};
  uint32_t stale_ms_{60000};
  uint32_t remote_interval_ms_{10000};
  bool serve_ui_{false};
  std::vector<Column> columns_;
  std::vector<Remote *> remotes_;

  Header *hdr_{nullptr};
  uint32_t capacity_{0};
  bool kept_{false};        // buffer survived the last reset
  uint8_t reset_reason_{0};
  // Wall clock = esp_timer ms + offset. Written from the httpd task, read
  // from the main loop; always under mutex_.
  int64_t epoch_offset_ms_{0};
  uint8_t clock_state_{0};

  // Guards the header, the rows, the clock and the remotes: the web server
  // and the remote poller both run on their own tasks.
  mutable Mutex mutex_;
};

}  // namespace esphome::soak_log

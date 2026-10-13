#pragma once

// van-core's sockets, kept and counted (docs/decisions.md D-29). See
// __init__.py for why this exists.

#include "esphome/core/component.h"
#include "esphome/components/sensor/sensor.h"

namespace esphome {
namespace net_guard {

class NetGuard : public PollingComponent {
 public:
  void set_keepalive(int idle_s, int interval_s, int count) {
    this->idle_s_ = idle_s;
    this->interval_s_ = interval_s;
    this->count_ = count;
  }
  void set_open_sockets_sensor(sensor::Sensor *s) { this->open_sensor_ = s; }

  void update() override;
  void dump_config() override;
  float get_setup_priority() const override { return setup_priority::AFTER_WIFI; }

 protected:
  int idle_s_{60};
  int interval_s_{10};
  int count_{3};
  sensor::Sensor *open_sensor_{nullptr};
  /** Sockets armed since boot, for the log. */
  uint32_t armed_total_{0};
  /** The most open at once since boot. */
  int peak_{0};
  bool warned_{false};
};

}  // namespace net_guard
}  // namespace esphome

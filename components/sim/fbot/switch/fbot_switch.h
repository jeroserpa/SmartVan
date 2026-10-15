#pragma once

#include "esphome/components/switch/switch.h"
#include "esphome/core/component.h"
#include "esphome/core/log.h"
#include "../fbot.h"

namespace esphome {
namespace fbot {

// Write semantics copied from ESP-FBot's FbotSwitch::write_state, because the
// arbiter's resend and reconnect logic was built against them (M17).
class FbotSwitch : public switch_::Switch, public Component {
 public:
  void set_parent(Fbot *parent) { this->parent_ = parent; }
  void set_index(uint8_t index) { this->index_ = index; }
  void dump_config() override { LOG_SWITCH("", "Fbot Switch (simulated)", this); }

 protected:
  void write_state(bool state) override {
    if (!this->parent_->is_connected()) {
      ESP_LOGW(TAG, "write to '%s' LOST: link down", this->get_name().c_str());
      this->publish_state(false);
      return;
    }
    if (state == this->state)
      return;
    this->parent_->write_switch(this->index_, state);
    this->publish_state(state);
  }

  Fbot *parent_{nullptr};
  uint8_t index_{0};
};

}  // namespace fbot
}  // namespace esphome

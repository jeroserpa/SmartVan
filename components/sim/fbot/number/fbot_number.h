#pragma once

#include "esphome/components/number/number.h"
#include "esphome/core/component.h"
#include "esphome/core/log.h"
#include "../fbot.h"

namespace esphome {
namespace fbot {

class FbotNumber : public number::Number, public Component {
 public:
  void set_parent(Fbot *parent) { this->parent_ = parent; }
  void set_index(uint8_t index) { this->index_ = index; }
  void dump_config() override { LOG_NUMBER("", "Fbot Number (simulated)", this); }

 protected:
  void control(float value) override {
    if (!this->parent_->is_connected()) {
      ESP_LOGW(TAG, "write to '%s' LOST: link down", this->get_name().c_str());
      return;
    }
    this->parent_->write_number(this->index_, value);
    this->publish_state(value);
  }

  Fbot *parent_{nullptr};
  uint8_t index_{0};
};

}  // namespace fbot
}  // namespace esphome

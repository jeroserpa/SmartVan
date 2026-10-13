#include "net_guard.h"

#ifdef USE_ESP32

#include "esphome/core/log.h"

#include <cinttypes>
#include <lwip/sockets.h>
#include <sdkconfig.h>

namespace esphome {
namespace net_guard {

static const char *const TAG = "net_guard";

// lwIP's descriptors are the top CONFIG_LWIP_MAX_SOCKETS of the fd space,
// starting at LWIP_SOCKET_OFFSET (lwipopts.h). A descriptor in that range
// that answers SO_TYPE is open; one that does not, is free.
void NetGuard::update() {
  int open = 0;
  uint32_t armed = 0;
  const int idle = this->idle_s_, intvl = this->interval_s_, cnt = this->count_;

  for (int fd = LWIP_SOCKET_OFFSET; fd < LWIP_SOCKET_OFFSET + CONFIG_LWIP_MAX_SOCKETS; fd++) {
    int type = 0;
    socklen_t len = sizeof(type);
    if (lwip_getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &len) != 0)
      continue;  // free slot
    open++;
    if (type != SOCK_STREAM)
      continue;  // UDP: httpd's control socket, DHCP, DNS - nothing to keep alive

    // Only a connected socket has a peer to lose. A listener, or a client
    // still connecting, is looked at again on the next pass.
    struct sockaddr_storage peer;
    socklen_t plen = sizeof(peer);
    if (lwip_getpeername(fd, reinterpret_cast<struct sockaddr *>(&peer), &plen) != 0)
      continue;

    int on = 0;
    len = sizeof(on);
    if (lwip_getsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &on, &len) == 0 && on)
      continue;  // armed on an earlier pass

    // Sockets belong to other tasks (httpd, OTA, the log's poller). lwIP is
    // built with full-duplex netconns and core locking here, so setting an
    // option from this task is safe; if the owner closes the socket in
    // between, these simply fail, and if a new one took the slot, arming
    // it is what would have happened on the next pass anyway.
    int one = 1;
    if (lwip_setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one)) != 0)
      continue;
    lwip_setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof(idle));
    lwip_setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &intvl, sizeof(intvl));
    lwip_setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &cnt, sizeof(cnt));
    armed++;
  }

  this->armed_total_ += armed;
  if (open > this->peak_)
    this->peak_ = open;
  if (armed > 0) {
    ESP_LOGV(TAG, "Armed keep-alive on %" PRIu32 " socket(s); %d of %d open", armed, open,
             CONFIG_LWIP_MAX_SOCKETS);
  }

  // Said once per crossing, not every pass: a full table is what made the
  // 2026-10-13 flash fail, and it should be in the log before that happens.
  const bool near_full = open >= CONFIG_LWIP_MAX_SOCKETS - 2;
  if (near_full && !this->warned_)
    ESP_LOGW(TAG, "%d of %d sockets in use (peak %d): new connections may be refused", open,
             CONFIG_LWIP_MAX_SOCKETS, this->peak_);
  this->warned_ = near_full;

  if (this->open_sensor_ != nullptr)
    this->open_sensor_->publish_state(open);
}

void NetGuard::dump_config() {
  ESP_LOGCONFIG(TAG, "Net guard:");
  ESP_LOGCONFIG(TAG, "  Sockets: %d (CONFIG_LWIP_MAX_SOCKETS), peak %d", CONFIG_LWIP_MAX_SOCKETS, this->peak_);
  ESP_LOGCONFIG(TAG, "  TCP keep-alive: idle %d s, then %d probes %d s apart", this->idle_s_, this->count_,
                this->interval_s_);
  LOG_UPDATE_INTERVAL(this);
}

}  // namespace net_guard
}  // namespace esphome

#endif  // USE_ESP32

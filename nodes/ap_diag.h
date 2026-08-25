// ap_diag.h - make SoftAP client join/leave visible in the log.
//
// DIAGNOSTIC SCAFFOLDING, not a permanent part of the design. Delete once the
// AP-dropout question (measurements.md M9) is closed.
//
// Why this has to exist: ESPHome's wifi component logs nothing whatsoever for
// AP-side association events. wifi_component.cpp handles WIFI_EVENT_AP_STACONNECTED
// and WIFI_EVENT_AP_STADISCONNECTED but emits no log line for either, so a phone
// dropping off the SoftAP is completely invisible - the device log looks
// perfectly healthy while the user cannot reach the UI. That is exactly the
// situation this file was written to end.
//
// The valuable field is `reason` on disconnect. IEEE 802.11 reason codes worth
// recognising here:
//     2  AUTH_EXPIRE            - association aged out
//     4  ASSOC_EXPIRE           - AP evicted an idle client
//     8  ASSOC_LEAVE            - client left deliberately (phone roamed/slept)
//    15  4WAY_HANDSHAKE_TIMEOUT - WPA2 handshake failed: the radio-contention
//                                 signature we are hunting
//    39  TIMEOUT
// A run of 15s points back at BLE/WiFi coexistence. A run of 8s or 4s means the
// phone is leaving on its own (power save, or Android abandoning a network with
// no internet) and the board is not at fault.

#pragma once

#include <esp_event.h>
#include <esp_wifi.h>

#include "esphome/core/log.h"

namespace van_diag {

static const char *const AP_DIAG_TAG = "ap_diag";

// -1 if the AP is not up yet; otherwise the number of associated stations.
inline int ap_client_count() {
  wifi_sta_list_t list{};
  if (esp_wifi_ap_get_sta_list(&list) != ESP_OK)
    return -1;
  return static_cast<int>(list.num);
}

// Captureless, so it converts to esp_event_handler_t. Registered alongside
// ESPHome's own handler - esp_event supports multiple handlers per event, so
// this observes without displacing anything.
inline void ap_event_handler(void *, esp_event_base_t base, int32_t id, void *data) {
  if (base != WIFI_EVENT)
    return;
  if (id == WIFI_EVENT_AP_STACONNECTED) {
    auto *e = static_cast<wifi_event_ap_staconnected_t *>(data);
    ESP_LOGW(AP_DIAG_TAG, "AP JOIN  aid=%d %02x:%02x:%02x:%02x:%02x:%02x  clients=%d", e->aid,
             e->mac[0], e->mac[1], e->mac[2], e->mac[3], e->mac[4], e->mac[5], ap_client_count());
  } else if (id == WIFI_EVENT_AP_STADISCONNECTED) {
    auto *e = static_cast<wifi_event_ap_stadisconnected_t *>(data);
    ESP_LOGE(AP_DIAG_TAG, "AP LEAVE aid=%d %02x:%02x:%02x:%02x:%02x:%02x  reason=%d  clients=%d",
             e->aid, e->mac[0], e->mac[1], e->mac[2], e->mac[3], e->mac[4], e->mac[5],
             static_cast<int>(e->reason), ap_client_count());
  }
}

inline void install_ap_logging() {
  esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID, &ap_event_handler, nullptr,
                                      nullptr);
  ESP_LOGW(AP_DIAG_TAG, "SoftAP join/leave logging installed");
}

}  // namespace van_diag

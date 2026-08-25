#include "van_ui.h"
#include "van_ui_html.h"

#include "esphome/core/log.h"

namespace esphome {
namespace van_ui {

static const char *const TAG = "van_ui";

// Cached, because they never change between OTAs and a phone that re-fetches
// the icon on every launch is wasting airtime on a link shared with BLE.
static const char *const CACHE_ASSET = "max-age=86400";
// Never cached: the page is a few tens of kB over a link with nothing else on
// it, and a stale UI after an OTA is a real debugging trap.
static const char *const CACHE_PAGE = "no-store";

// The URLs each OS fetches to decide whether a network has internet. Answering
// these at all is the whole "fail validation" decision documented in van_ui.h;
// answering them with a page rather than a 204 is what makes the phone offer a
// tap-through instead of silently swallowing the network.
static const char *const PROBE_PATHS[] = {
    "/generate_204",              // Android
    "/gen_204",                   // Android, older
    "/hotspot-detect.html",       // iOS, macOS
    "/library/test/success.html", // macOS
    "/connecttest.txt",           // Windows 10/11
    "/ncsi.txt",                  // Windows, legacy
    "/canonical.html",            // Firefox / NetworkManager
    "/success.txt",               // Firefox
    "/van-ui/portal",             // and by hand, to see what the phone sees
};

void VanUi::setup() {
  this->base_->init();
  this->base_->add_handler(this);
}

void VanUi::dump_config() {
  ESP_LOGCONFIG(TAG, "van-core UI:");
  ESP_LOGCONFIG(TAG, "  App:      %s (also /ui)", this->path_);
  ESP_LOGCONFIG(TAG, "  Manifest: /van-ui/manifest.webmanifest");
  ESP_LOGCONFIG(TAG, "  Icon:     /van-ui/icon.png");
  ESP_LOGCONFIG(TAG, "  Captive landing page: %s",
                this->captive_landing_ ? "YES (network reports no internet)" : "NO");
  ESP_LOGCONFIG(TAG, "  Flash: %u bytes",
                (unsigned) (VAN_UI_INDEX_LEN + VAN_UI_PORTAL_LEN +
                            VAN_UI_MANIFEST_LEN + VAN_UI_ICON_LEN));
}

const VanUi::Asset *VanUi::match_(const std::string &url) const {
  static const Asset INDEX{"text/html", VAN_UI_INDEX, VAN_UI_INDEX_LEN, true,
                           CACHE_PAGE};
  static const Asset PORTAL{"text/html", VAN_UI_PORTAL, VAN_UI_PORTAL_LEN, true,
                            CACHE_PAGE};
  static const Asset MANIFEST{"application/manifest+json", VAN_UI_MANIFEST,
                              VAN_UI_MANIFEST_LEN, true, CACHE_ASSET};
  static const Asset ICON{"image/png", VAN_UI_ICON, VAN_UI_ICON_LEN, false,
                          CACHE_ASSET};

  // Exact matches only. No prefix matching: a greedy handler registered ahead
  // of web_server would swallow /events and the REST endpoints the page itself
  // depends on, which is a failure that looks like "the UI is frozen".
  if (url == this->path_ || url == "/ui")
    return &INDEX;
  if (url == "/van-ui/manifest.webmanifest")
    return &MANIFEST;
  if (url == "/van-ui/icon.png")
    return &ICON;

  if (this->captive_landing_) {
    for (const char *p : PROBE_PATHS) {
      if (url == p)
        return &PORTAL;
    }
  }
  return nullptr;
}

bool VanUi::canHandle(AsyncWebServerRequest *request) const {
  if (request->method() != HTTP_GET)
    return false;
  return this->match_(request->url()) != nullptr;
}

void VanUi::handleRequest(AsyncWebServerRequest *request) {
  const Asset *a = this->match_(request->url());
  if (a == nullptr) {
    // Unreachable - canHandle() said yes using the same table. Answer rather
    // than leaving the request dangling: an unanswered request on this server
    // holds a socket until it times out.
    request->send(404);
    return;
  }

  // Served straight out of flash. No heap copy: this node runs a BLE client, a
  // SoftAP and a display in one cooperative loop (CLAUDE.md section 2), and a
  // ~30kB allocation on every page load is not something to hand it.
  AsyncWebServerResponse *r =
      request->beginResponse(200, a->content_type, a->data, a->len);
  if (a->gzip)
    r->addHeader("Content-Encoding", "gzip");
  r->addHeader("Cache-Control", a->cache_control);
  request->send(r);
}

}  // namespace van_ui
}  // namespace esphome

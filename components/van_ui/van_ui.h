// van_ui.h - serve the custom van-core web UI out of flash.
//
// A single AsyncWebHandler bolted onto the web server ESPHome already runs, so
// the page and the REST/SSE API it drives are the same origin, the same port
// and the same TCP stack. Nothing new listens, nothing new needs configuring.
//
// The pages are gzipped into van_ui_html.h by tools/pack_ui.py. They are served
// from flash with no copy into RAM: this node also runs a BLE client, a SoftAP
// and a display in one cooperative loop (CLAUDE.md section 2), and a 30kB heap
// allocation on every page load is not something to hand it.
//
// The default ESPHome UI stays where it was, at /. This one is at /ui by
// default, with `at_root: true` to swap them. Keeping the stock UI reachable is
// not politeness: it is the fallback that shows every entity when a custom
// page's assumptions turn out to be wrong, at 11pm, in a car park.
//
// Four things get served:
//
//   <path> and /ui                 the app. /ui answers unconditionally, even
//                                  under `at_root`, so the one absolute URL
//                                  baked into ui/portal.html is never wrong.
//   /van-ui/manifest.webmanifest   home-screen app metadata
//   /van-ui/icon.png               home-screen icon
//   the OS connectivity probes     the captive landing page - see below
//
// ---------------------------------------------------------------------------
// Captive portal behaviour: DELIBERATELY FAIL VALIDATION.
//
// Android, iOS and Windows each fetch a known URL on joining a network and
// decide from the answer whether the network has internet. With
// `captive_portal:` running a catch-all DNS server, those probes land here.
//
// We answer them with a small landing page, i.e. not the 204/"Success" the OS
// wants. The phone therefore concludes "captive portal", keeps mobile data as
// its default route so the rest of the phone still works, and offers a "Sign
// in to network" notification that lands the user straight on that page.
//
// The alternative - returning 204 and pretending the AP has internet - makes
// the phone auto-join silently and never nag, at the cost of routing all
// traffic to this node, i.e. no internet on the phone at all while parked at
// the van. Rejected. See docs/decisions.md.
//
// Set `captive_landing: false` to give the probe paths back to ESPHome's own
// captive_portal component.
// ---------------------------------------------------------------------------

#pragma once

#include <string>

#include "esphome/core/component.h"
#include "esphome/components/web_server_base/web_server_base.h"

namespace esphome {
namespace van_ui {

class VanUi : public Component, public AsyncWebHandler {
 public:
  void set_base(web_server_base::WebServerBase *base) { this->base_ = base; }
  void set_path(const char *path) { this->path_ = path; }
  void set_captive_landing(bool on) { this->captive_landing_ = on; }

  void setup() override;
  void dump_config() override;
  // BEFORE web_server (which is WIFI - 1). AsyncWebServer tries handlers in
  // registration order, so registering first is the only way `at_root` can
  // take "/" away from the stock UI. It costs nothing when serving "/ui".
  float get_setup_priority() const override { return setup_priority::WIFI + 1.0f; }

  bool canHandle(AsyncWebServerRequest *request) const override;
  void handleRequest(AsyncWebServerRequest *request) override;
  bool isRequestHandlerTrivial() const override { return true; }

 protected:
  // One row per servable thing. Everything the handler needs is here, so
  // canHandle() and handleRequest() share exactly one notion of what exists
  // and cannot drift apart.
  struct Asset {
    const char *content_type;
    const uint8_t *data;
    size_t len;
    bool gzip;
    const char *cache_control;
  };

  // Returns the asset for a URL, or nullptr if this handler does not own it.
  const Asset *match_(const std::string &url) const;

  web_server_base::WebServerBase *base_{nullptr};
  const char *path_{"/ui"};
  bool captive_landing_{true};
};

}  // namespace van_ui
}  // namespace esphome

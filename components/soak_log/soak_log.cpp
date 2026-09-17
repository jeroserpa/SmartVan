#include "soak_log.h"

#include <algorithm>
#include <cinttypes>
#include <cstdarg>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>

#include <esp_attr.h>
#include <esp_heap_caps.h>
#include <esp_http_client.h>
#include <esp_rom_crc.h>
#include <esp_system.h>
#include <esp_timer.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

#include "esphome/core/log.h"

namespace esphome::soak_log {

static const char *const TAG = "soak_log";
static constexpr uint32_t MAGIC = 0x4b414f53;  // "SOAK"
static constexpr uint32_t FORMAT_VERSION = 1;
static constexpr size_t HDR_BYTES = (sizeof(Header) + 3) & ~size_t{3};

// Not zeroed at boot. The only thing in this section.
EXT_RAM_NOINIT_ATTR static uint8_t s_store[SOAK_LOG_BUFFER_BYTES];

static int64_t now_ms() { return esp_timer_get_time() / 1000; }

static const char *reset_reason_str(uint8_t r) {
  switch (r) {
    case ESP_RST_POWERON: return "power-on";
    case ESP_RST_EXT: return "external";
    case ESP_RST_SW: return "software";
    case ESP_RST_PANIC: return "panic";
    case ESP_RST_INT_WDT: return "int-wdt";
    case ESP_RST_TASK_WDT: return "task-wdt";
    case ESP_RST_WDT: return "wdt";
    case ESP_RST_DEEPSLEEP: return "deepsleep";
    case ESP_RST_BROWNOUT: return "brownout";
    case ESP_RST_SDIO: return "sdio";
    default: return "unknown";
  }
}

// ---------------------------------------------------------------------------
// Storage
// ---------------------------------------------------------------------------

uint8_t *SoakLog::row_ptr_(uint32_t slot) const { return s_store + HDR_BYTES + slot * this->row_size_(); }

uint32_t SoakLog::layout_hash_() const {
  // FNV-1a over everything that changes the meaning of a stored byte. A
  // reflash that only changes code keeps the data; one that changes the
  // columns throws it away rather than mislabelling it.
  uint32_t h = 2166136261u;
  auto mix = [&h](const uint8_t *p, size_t n) {
    for (size_t i = 0; i < n; i++) {
      h ^= p[i];
      h *= 16777619u;
    }
  };
  const uint32_t fixed[] = {FORMAT_VERSION, (uint32_t) this->row_size_(), this->capacity_,
                            (uint32_t) SOAK_LOG_BUFFER_BYTES};
  mix((const uint8_t *) fixed, sizeof(fixed));
  for (const auto &c : this->columns_)
    mix((const uint8_t *) c.name, strlen(c.name) + 1);
  return h;
}

void SoakLog::seal_() {
  this->hdr_->crc = esp_rom_crc32_le(0, (const uint8_t *) this->hdr_, offsetof(Header, crc));
}

bool SoakLog::header_valid_() const {
  const Header *h = this->hdr_;
  return h->magic == MAGIC && h->layout == this->layout_hash_() && h->capacity == this->capacity_ &&
         h->head < h->capacity && h->count <= h->capacity &&
         h->crc == esp_rom_crc32_le(0, (const uint8_t *) h, offsetof(Header, crc));
}

void SoakLog::reset_buffer_() {
  memset(this->hdr_, 0, sizeof(Header));
  this->hdr_->magic = MAGIC;
  this->hdr_->layout = this->layout_hash_();
  this->hdr_->capacity = this->capacity_;
  this->seal_();
}

void SoakLog::setup() {
  this->hdr_ = reinterpret_cast<Header *>(s_store);
  this->capacity_ = (SOAK_LOG_BUFFER_BYTES - HDR_BYTES) / this->row_size_();
  this->reset_reason_ = (uint8_t) esp_reset_reason();

  // PSRAM loses its contents without power, and a garbage header could still
  // pass the magic check by luck; the CRC makes that vanishingly unlikely,
  // and a power-type reset skips the question entirely.
  bool power_reset = this->reset_reason_ == ESP_RST_POWERON || this->reset_reason_ == ESP_RST_BROWNOUT ||
                     this->reset_reason_ == ESP_RST_UNKNOWN;
  {
    LockGuard lock(this->mutex_);
    this->kept_ = !power_reset && this->header_valid_();
    if (!this->kept_)
      this->reset_buffer_();

    Header *h = this->hdr_;
    h->boot++;
    // Carry the clock across a software reset. The gap is at most one
    // interval plus the boot itself; rows say so with clock=2.
    if (this->kept_ && h->last_epoch != 0) {
      this->epoch_offset_ms_ = (int64_t) h->last_epoch * 1000 + this->interval_ms_ - now_ms();
      this->clock_state_ = 2;
    }
    BootRecord &b = h->boots[h->nboots % MAX_BOOTS];
    b.boot = h->boot;
    b.reason = this->reset_reason_;
    b.clock = this->clock_state_;
    b.epoch = this->now_epoch_();
    b.seq = h->seq;
    h->nboots++;
    this->seal_();
  }

  this->base_->init();
  this->base_->add_handler(this);
  this->set_interval("row", this->interval_ms_, [this]() { this->write_row_(); });

  if (!this->remotes_.empty()) {
    // Core 1 with the main loop, priority 1: it yields on every socket wait,
    // and it must never outrank the loop that feeds the BLE client (core 0
    // runs the controller and host).
    xTaskCreatePinnedToCore(SoakLog::remote_task_, "soak_remote", 4096, this, 1, nullptr, 1);
  }
}

uint32_t SoakLog::now_epoch_() const {
  if (this->clock_state_ == 0)
    return 0;
  return (uint32_t) ((now_ms() + this->epoch_offset_ms_) / 1000);
}

void SoakLog::write_row_() {
  // Sample outside the lock; the values are the main loop's own.
  const size_t n = this->columns_.size();
  float vals[MAX_COLUMNS];
  for (size_t i = 0; i < n; i++) {
    const Column &c = this->columns_[i];
    if (c.sensor != nullptr) {
      vals[i] = c.sensor->has_state() ? c.sensor->state : NAN;
    } else {
      vals[i] = c.binary->has_state() ? (c.binary->state ? 1.0f : 0.0f) : NAN;
    }
  }

  LockGuard lock(this->mutex_);
  Header *h = this->hdr_;
  uint32_t epoch = this->now_epoch_();
  uint32_t uptime = (uint32_t) (now_ms() / 1000);
  uint16_t flags = this->clock_state_;
  uint8_t *p = this->row_ptr_(h->head);
  memcpy(p, &epoch, 4);
  memcpy(p + 4, &uptime, 4);
  memcpy(p + 8, &h->boot, 2);
  memcpy(p + 10, &flags, 2);
  memcpy(p + 12, vals, 4 * n);
  h->head = (h->head + 1) % h->capacity;
  if (h->count < h->capacity)
    h->count++;
  h->seq++;
  if (epoch != 0)
    h->last_epoch = epoch;
  this->seal_();
}

void SoakLog::loop() {
  if (this->remotes_.empty())
    return;
  int64_t now = now_ms();
  LockGuard lock(this->mutex_);
  for (Remote *r : this->remotes_) {
    if (r->fresh) {
      r->fresh = false;
      r->stale_published = false;
      r->sensor->publish_state(r->value);
    } else if (!r->stale_published && now - r->last_ok_ms > (int64_t) this->stale_ms_) {
      // Absent is a state worth logging as such, not as the last good number.
      r->stale_published = true;
      r->sensor->publish_state(NAN);
    }
  }
}

void SoakLog::dump_config() {
  ESP_LOGCONFIG(TAG, "Soak log (PSRAM, .ext_ram_noinit):");
  ESP_LOGCONFIG(TAG, "  Buffer: %u bytes, %u rows of %u bytes, every %" PRIu32 " ms",
                (unsigned) SOAK_LOG_BUFFER_BYTES, (unsigned) this->capacity_, (unsigned) this->row_size_(),
                this->interval_ms_);
  ESP_LOGCONFIG(TAG, "  Holds: %.1f days", this->capacity_ * (this->interval_ms_ / 1000.0f) / 86400.0f);
  ESP_LOGCONFIG(TAG, "  Reset reason: %s -> buffer %s (%" PRIu32 " rows, boot %u)",
                reset_reason_str(this->reset_reason_), this->kept_ ? "KEPT" : "cleared", this->hdr_->count,
                this->hdr_->boot);
  ESP_LOGCONFIG(TAG, "  Columns: %u, remotes: %u. Page: /soak", (unsigned) this->columns_.size(),
                (unsigned) this->remotes_.size());
}

// ---------------------------------------------------------------------------
// Remote poller - runs on its own task
// ---------------------------------------------------------------------------

bool SoakLog::fetch_value_(const std::string &url, float *out) {
  esp_http_client_config_t cfg{};
  cfg.url = url.c_str();
  cfg.timeout_ms = 2000;
  cfg.buffer_size = 512;
  cfg.disable_auto_redirect = true;
  esp_http_client_handle_t c = esp_http_client_init(&cfg);
  if (c == nullptr)
    return false;
  bool ok = false;
  char body[384];
  if (esp_http_client_open(c, 0) == ESP_OK) {
    esp_http_client_fetch_headers(c);
    if (esp_http_client_get_status_code(c) == 200) {
      int len = 0;
      while (len < (int) sizeof(body) - 1) {
        int r = esp_http_client_read(c, body + len, sizeof(body) - 1 - len);
        if (r <= 0)
          break;
        len += r;
      }
      body[len] = '\0';
      // {"id":"sensor/Fridge power","value":24.3,"state":"24.3 W"}
      // A sensor with no state serialises value as null: that is a valid
      // answer (the peer is up, its meter is not), so it counts as ok.
      const char *p = strstr(body, "\"value\":");
      if (p != nullptr) {
        p += 8;
        char *end;
        float v = strtof(p, &end);
        *out = (end == p) ? NAN : v;
        ok = true;
      }
    }
  }
  esp_http_client_close(c);
  esp_http_client_cleanup(c);
  return ok;
}

void SoakLog::remote_task_(void *arg) {
  auto *self = static_cast<SoakLog *>(arg);
  for (;;) {
    for (Remote *r : self->remotes_) {
      float v = NAN;
      bool ok = fetch_value_(r->url, &v);
      LockGuard lock(self->mutex_);
      if (ok) {
        r->value = v;
        r->last_ok_ms = now_ms();
        r->fresh = true;
        r->ok++;
      } else {
        r->fail++;
      }
    }
    vTaskDelay(pdMS_TO_TICKS(self->remote_interval_ms_));
  }
}

// ---------------------------------------------------------------------------
// HTTP - runs on the httpd task
// ---------------------------------------------------------------------------

static const char PAGE[] = R"HTML(<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Soak log</title>
<style>body{font:15px system-ui,sans-serif;margin:0;padding:12px 16px;background:#111;color:#ddd}
h1{font-size:18px}button{font:inherit;padding:10px 14px;margin:4px 4px 4px 0;border-radius:6px;
border:1px solid #555;background:#222;color:#eee}button.d{border-color:#a33;color:#f88}
table{border-collapse:collapse}td{padding:2px 10px 2px 0;vertical-align:top}#m{color:#8c8;min-height:1.4em}
code{color:#adf}</style></head><body><h1>van-core soak log</h1>
<div id="m"></div><table id="t"></table>
<p><button onclick="dl(0)">Download all</button><button onclick="dl(8640)">Last 24 h</button>
<button onclick="dl(2160)">Last 6 h</button><button onclick="sync()">Sync clock</button></p>
<p><button class="d" onclick="clr()">Clear log</button></p>
<p>Plain link if the buttons fail: <a href="/soak/log.csv">/soak/log.csv</a>.
Turn <b>mobile data off</b> first, or Android routes this page over cellular.</p>
<p><a href="/">ESPHome entities</a></p>
<script>
const $=id=>document.getElementById(id);
function msg(s){$('m').textContent=s}
function fmtAge(s){return s>=86400?(s/86400).toFixed(1)+' d':s>=3600?(s/3600).toFixed(1)+' h':Math.round(s/60)+' min'}
async function post(u,b){return fetch(u,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b})}
async function sync(){await post('/soak/clock','ms='+Date.now()+'&tz='+(-new Date().getTimezoneOffset()));await load()}
async function load(){
 const s=await (await fetch('/soak/status')).json();
 const c=['unknown','phone','carried over reboot'][s.clock];
 const rows=[['Rows',s.rows+' / '+s.capacity+' ('+fmtAge(s.rows*s.interval_s)+' of '+fmtAge(s.capacity*s.interval_s)+')'],
  ['Interval',s.interval_s+' s'],['Boot',s.boot+' (last reset: '+s.reset+', buffer '+(s.kept?'kept':'cleared')+')'],
  ['Clock',c],['Free PSRAM',(s.psram_free/1e6).toFixed(2)+' MB']];
 for(const r of s.remotes)rows.push(['Remote',r.url+'<br>ok '+r.ok+', fail '+r.fail+', age '+(r.age_s<0?'never':r.age_s+' s')]);
 rows.push(['Reboots',s.boots.map(b=>'#'+b.boot+' '+b.reason+(b.epoch?' '+new Date(b.epoch*1000).toLocaleString():'')).join('<br>')]);
 $('t').innerHTML=rows.map(r=>'<tr><td>'+r[0]+'</td><td>'+r[1]+'</td></tr>').join('');
}
async function dl(n){
 msg('Downloading...');
 try{const r=await fetch('/soak/log.csv'+(n?'?last='+n:''));if(!r.ok)throw r.status;
  const cd=r.headers.get('Content-Disposition')||'';const f=(cd.match(/filename="([^"]+)"/)||[])[1]||'soak.csv';
  if(window.VanApp&&VanApp.saveFile){const t=await r.text();msg(VanApp.saveFile(f,t));return}
  if(/; wv\)/.test(navigator.userAgent)){msg('This app cannot save files yet. Use Chrome for downloads.');return}
  const b=await r.blob();const a=document.createElement('a');
  a.href=URL.createObjectURL(b);a.download=f;document.body.appendChild(a);a.click();a.remove();
  msg('Saved '+f+' ('+(b.size/1024).toFixed(0)+' kB)');}catch(e){msg('Download failed: '+e)}
}
async function clr(){if(!confirm('Erase every row in the soak log? Download first.'))return;await post('/soak/clear','confirm=1');await load();msg('Cleared')}
sync().then(()=>msg('Clock synced from this phone')).catch(e=>msg('Status failed: '+e));
</script></body></html>)HTML";

enum Route { NONE, PAGE_R, STATUS, CSV, CLOCK, CLEAR };

static Route route_of(AsyncWebServerRequest *request, bool serve_ui) {
  char buf[AsyncWebServerRequest::URL_BUF_SIZE];
  StringRef url = request->url_to(buf);
  bool get = request->method() == HTTP_GET;
  bool post = request->method() == HTTP_POST;
  if (get && (url == "/soak" || url == "/soak/"))
    return PAGE_R;
  // The Android wrapper (D-15) opens /ui and has no address bar. On a build
  // with no van_ui, answering /ui is the only way it can reach this page.
  if (get && serve_ui && url == "/ui")
    return PAGE_R;
  if (get && url == "/soak/status")
    return STATUS;
  if (get && url == "/soak/log.csv")
    return CSV;
  if (post && url == "/soak/clock")
    return CLOCK;
  if (post && url == "/soak/clear")
    return CLEAR;
  return NONE;
}

bool SoakLog::canHandle(AsyncWebServerRequest *request) const { return route_of(request, this->serve_ui_) != NONE; }

void SoakLog::handleRequest(AsyncWebServerRequest *request) {
  switch (route_of(request, this->serve_ui_)) {
    case PAGE_R:
      this->send_page_(request);
      return;
    case STATUS:
      this->send_status_(request);
      return;
    case CSV:
      this->send_csv_(request);
      return;
    case CLOCK: {
      std::string ms = request->arg("ms");
      std::string tz = request->arg("tz");
      int64_t v = strtoll(ms.c_str(), nullptr, 10);
      // Anything before 2026 is a phone with no idea what time it is.
      if (v < 1767225600000LL) {
        request->send(400, "text/plain", "bad ms");
        return;
      }
      LockGuard lock(this->mutex_);
      this->epoch_offset_ms_ = v - now_ms();
      this->clock_state_ = 1;
      if (!tz.empty())
        this->hdr_->tz_min = (int32_t) strtol(tz.c_str(), nullptr, 10);
      this->seal_();
      request->send(200, "text/plain", "ok");
      return;
    }
    case CLEAR: {
      if (request->arg("confirm") != "1") {
        request->send(400, "text/plain", "confirm=1 required");
        return;
      }
      LockGuard lock(this->mutex_);
      uint16_t boot = this->hdr_->boot;
      int32_t tz = this->hdr_->tz_min;
      this->reset_buffer_();
      this->hdr_->boot = boot;
      this->hdr_->tz_min = tz;
      this->seal_();
      ESP_LOGW(TAG, "Log cleared from the web page");
      request->send(200, "text/plain", "cleared");
      return;
    }
    default:
      request->send(404);
      return;
  }
}

void SoakLog::send_page_(AsyncWebServerRequest *request) {
  AsyncWebServerResponse *r =
      request->beginResponse(200, "text/html", (const uint8_t *) PAGE, sizeof(PAGE) - 1);
  r->addHeader("Cache-Control", "no-store");
  request->send(r);
}

void SoakLog::send_status_(AsyncWebServerRequest *request) {
  std::string s;
  s.reserve(2048);
  char b[256];
  int64_t now = now_ms();
  LockGuard lock(this->mutex_);
  const Header *h = this->hdr_;
  snprintf(b, sizeof(b),
           "{\"rows\":%" PRIu32 ",\"capacity\":%" PRIu32 ",\"seq\":%" PRIu32 ",\"interval_s\":%.1f,"
           "\"boot\":%u,\"reset\":\"%s\",\"kept\":%s,\"clock\":%u,\"epoch\":%" PRIu32 ",\"psram_free\":%u,",
           h->count, h->capacity, h->seq, this->interval_ms_ / 1000.0f, h->boot, reset_reason_str(this->reset_reason_),
           this->kept_ ? "true" : "false", this->clock_state_, this->now_epoch_(),
           (unsigned) heap_caps_get_free_size(MALLOC_CAP_SPIRAM));
  s += b;
  s += "\"remotes\":[";
  for (size_t i = 0; i < this->remotes_.size(); i++) {
    const Remote *r = this->remotes_[i];
    long age = r->last_ok_ms == 0 ? -1 : (long) ((now - r->last_ok_ms) / 1000);
    snprintf(b, sizeof(b), "%s{\"url\":\"%s\",\"ok\":%" PRIu32 ",\"fail\":%" PRIu32 ",\"age_s\":%ld}",
             i ? "," : "", r->url.c_str(), r->ok, r->fail, age);
    s += b;
  }
  s += "],\"boots\":[";
  uint16_t kept = h->nboots < MAX_BOOTS ? h->nboots : MAX_BOOTS;
  for (uint16_t i = 0; i < kept; i++) {
    // Newest first.
    const BootRecord &br = h->boots[(h->nboots - 1 - i) % MAX_BOOTS];
    snprintf(b, sizeof(b), "%s{\"boot\":%u,\"reason\":\"%s\",\"epoch\":%" PRIu32 ",\"seq\":%" PRIu32 "}",
             i ? "," : "", br.boot, reset_reason_str(br.reason), br.epoch, br.seq);
    s += b;
  }
  s += "]}";
  AsyncWebServerResponse *resp = request->beginResponse(200, "application/json", s);
  resp->addHeader("Cache-Control", "no-store");
  request->send(resp);
}

static void fmt_local(char *out, size_t n, uint32_t epoch, int32_t tz_min) {
  if (epoch == 0) {
    out[0] = '\0';
    return;
  }
  time_t t = (time_t) epoch + (time_t) tz_min * 60;
  struct tm tm;
  gmtime_r(&t, &tm);
  strftime(out, n, "%Y-%m-%d %H:%M:%S", &tm);
}

void SoakLog::send_csv_(AsyncWebServerRequest *request) {
  httpd_req_t *req = *request;
  const size_t ncol = this->columns_.size();
  const size_t rs = this->row_size_();

  uint32_t want = 0;
  std::string last = request->arg("last");
  if (!last.empty())
    want = (uint32_t) strtoul(last.c_str(), nullptr, 10);

  // Snapshot. Rows are then copied in batches under the lock; a row the
  // writer overwrote in the meantime (buffer full, slow phone) is skipped.
  uint32_t count, seq, cap, now_epoch;
  int32_t tz;
  uint16_t boot, nboots;
  BootRecord boots[MAX_BOOTS];
  {
    LockGuard lock(this->mutex_);
    count = this->hdr_->count;
    seq = this->hdr_->seq;
    cap = this->hdr_->capacity;
    tz = this->hdr_->tz_min;
    boot = this->hdr_->boot;
    nboots = this->hdr_->nboots;
    memcpy(boots, this->hdr_->boots, sizeof(boots));
    now_epoch = this->now_epoch_();
  }
  if (want != 0 && want < count)
    count = want;
  uint32_t first_seq = seq - count;

  char fname[64];
  if (now_epoch != 0) {
    char ts[24];
    time_t t = (time_t) now_epoch + (time_t) tz * 60;
    struct tm tm;
    gmtime_r(&t, &tm);
    strftime(ts, sizeof(ts), "%Y%m%d-%H%M", &tm);
    snprintf(fname, sizeof(fname), "attachment; filename=\"soak-%s.csv\"", ts);
  } else {
    snprintf(fname, sizeof(fname), "attachment; filename=\"soak-boot%u-%" PRIu32 ".csv\"", boot,
             (uint32_t) (now_ms() / 1000));
  }
  httpd_resp_set_status(req, HTTPD_200);
  httpd_resp_set_type(req, "text/csv");
  httpd_resp_set_hdr(req, "Content-Disposition", fname);
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");

  // Output buffer and row batch in PSRAM: this runs on the httpd task, whose
  // stack is small, and internal RAM belongs to the BLE stack.
  static constexpr size_t OUT = 8192;
  static constexpr uint32_t BATCH = 64;
  char *out = (char *) heap_caps_malloc(OUT, MALLOC_CAP_SPIRAM);
  uint8_t *batch = (uint8_t *) heap_caps_malloc(BATCH * rs, MALLOC_CAP_SPIRAM);
  if (out == nullptr || batch == nullptr) {
    free(out);
    free(batch);
    httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "no memory");
    return;
  }
  size_t len = 0;
  bool alive = true;
  auto flush = [&]() {
    if (alive && len > 0 && httpd_resp_send_chunk(req, out, len) != ESP_OK)
      alive = false;  // phone went away; stop formatting for nobody
    len = 0;
  };
  auto put = [&](const char *fmt, ...) {
    if (OUT - len < 512)
      flush();
    va_list ap;
    va_start(ap, fmt);
    int w = vsnprintf(out + len, OUT - len, fmt, ap);
    va_end(ap);
    if (w > 0)
      len += std::min((size_t) w, OUT - len - 1);
  };

  // Self-describing preamble: a CSV found on a laptop in three weeks must
  // still say what produced it and what the reboots were.
  put("# van-core soak log. rows=%" PRIu32 " interval_s=%.1f boot=%u tz_min=%d\n", count,
      this->interval_ms_ / 1000.0f, boot, (int) tz);
  put("# clock: 0=unknown 1=phone-synced 2=carried across reboot (error ~1 interval)\n");
  uint16_t kept = nboots < MAX_BOOTS ? nboots : MAX_BOOTS;
  for (uint16_t i = kept; i-- > 0;) {
    const BootRecord &br = boots[(nboots - 1 - i) % MAX_BOOTS];
    char lt[24];
    fmt_local(lt, sizeof(lt), br.epoch, tz);
    put("# boot %u reset=%s at_row=%" PRIu32 " time=%s\n", br.boot, reset_reason_str(br.reason), br.seq,
        lt[0] ? lt : "unknown");
  }
  put("local_time,epoch,uptime_s,boot,clock");
  for (const auto &c : this->columns_)
    put(",%s", c.name);
  put("\n");

  uint32_t done = 0;
  while (alive && done < count) {
    uint32_t n = std::min(BATCH, count - done);
    uint32_t got = 0;
    {
      LockGuard lock(this->mutex_);
      const Header *h = this->hdr_;
      uint32_t oldest_seq = h->seq - h->count;
      for (uint32_t k = 0; k < n; k++) {
        uint32_t s = first_seq + done + k;
        if (s < oldest_seq)
          continue;  // overwritten while we were sending
        // Slot of absolute row s: head is where row h->seq will go.
        uint32_t back = h->seq - s;  // 1..count
        uint32_t slot = (h->head + cap - back) % cap;
        memcpy(batch + got * rs, this->row_ptr_(slot), rs);
        got++;
      }
    }
    for (uint32_t k = 0; k < got && alive; k++) {
      const uint8_t *p = batch + k * rs;
      uint32_t epoch, uptime;
      uint16_t rboot, flags;
      memcpy(&epoch, p, 4);
      memcpy(&uptime, p + 4, 4);
      memcpy(&rboot, p + 8, 2);
      memcpy(&flags, p + 10, 2);
      char lt[24];
      fmt_local(lt, sizeof(lt), epoch, tz);
      put("%s,%" PRIu32 ",%" PRIu32 ",%u,%u", lt, epoch, uptime, rboot, flags);
      for (size_t i = 0; i < ncol; i++) {
        float v;
        memcpy(&v, p + 12 + 4 * i, 4);
        if (std::isnan(v)) {
          put(",");
        } else {
          put(",%.*f", this->columns_[i].decimals, v);
        }
      }
      put("\n");
    }
    done += n;
    // Let the writer and the radio in between batches.
    vTaskDelay(1);
  }
  flush();
  if (alive)
    httpd_resp_send_chunk(req, nullptr, 0);
  free(out);
  free(batch);
}

}  // namespace esphome::soak_log

#include "arbiter_core.h"

namespace van {

const char *ac_reason_str(AcReason r) {
  switch (r) {
    case AcReason::OFF: return "off";
    case AcReason::PARKED: return "parked";
    case AcReason::BOOT: return "boot";
    case AcReason::BLE_LOST: return "ble lost";
    case AcReason::LINK_STALE: return "link stale";
    case AcReason::TEMP_STALE: return "probe stale";
    case AcReason::WATCHDOG: return "watchdog";
    case AcReason::FRIDGE_HARD: return "fridge (hard)";
    case AcReason::FRIDGE: return "fridge";
    case AcReason::MANUAL: return "manual";
    case AcReason::SURPLUS: return "surplus";
  }
  return "?";
}

void ArbiterCore::begin(uint32_t now_ms) {
  started_ = true;
  boot_ms_ = now_ms;
  last_tick_ms_ = now_ms;
  // Back-date the anti-short-cycle timer: at boot there is no compressor to
  // protect, and a warm fridge must not wait out a lockout it never earned.
  fridge_since_ms_ = now_ms - cfg_.min_off_ms;
  // Pretend the probe was fresh and the compressor busy at t=0. Both bias
  // toward ON, which is the correct direction while nothing is known yet.
  temp_fresh_ms_ = now_ms;
  link_fresh_ms_ = now_ms;
  compressor_busy_ms_ = now_ms;
  manual_busy_ms_ = now_ms;
  surplus_since_ms_ = now_ms;
  out_ = ArbiterOutputs{};
}

const ArbiterOutputs &ArbiterCore::tick(uint32_t now_ms, const ArbiterInputs &in) {
  if (!started_)
    begin(now_ms);

  // Watchdog: did our own loop get starved? This is the SD-write / display
  // redraw failure mode called out in CLAUDE.md section 11, caught rather than
  // assumed absent.
  const bool watchdog_tripped = elapsed(now_ms, last_tick_ms_, cfg_.watchdog_ms);
  last_tick_ms_ = now_ms;

  last_in_ = in;

  // --- parked mode. The one place in this firmware where the section 5.2
  // fail-safe direction is deliberately inverted. ---
  //
  // "Fail toward powered" protects food. Parked at home the fridge is empty
  // and there is no food; what a forced-on inverter protects then is nothing,
  // at a measured ~48W of station overhead, which flattens a 3.9kWh pack in
  // about three days and then leaves it flat - the one outcome that actually
  // damages hardware. So while parked, OFF is the safe state, and BLE loss, a
  // dead probe and a starved loop must all resolve to OFF, not ON.
  //
  // Everything that makes this safe is at the entry gate (park_request's
  // interlock), not here. By this point the decision has been made.
  if (parked_) {
    out_.force_on = false;
    out_.fridge_req = false;
    out_.fridge_hard = false;
    out_.manual_req = false;
    out_.surplus_req = false;
    out_.inhibit_active = false;
    out_.manual_warning = false;
    out_.manual_remaining_s = 0;
    out_.ac_on = false;
    out_.parked = true;
    out_.park_pending = false;
    out_.reason = AcReason::PARKED;
    out_.parked_for_s = static_cast<uint32_t>(now_ms - parked_since_ms_) / 1000u;

    // Keep every timer rolling forward so that whatever hour of whatever week
    // the mode is left, the arbiter resumes with clean state instead of an
    // anti-short-cycle lockout or a stale-probe trip it inherited from storage.
    fridge_since_ms_ = now_ms - cfg_.min_off_ms;
    compressor_busy_ms_ = now_ms;
    manual_busy_ms_ = now_ms;
    surplus_since_ms_ = now_ms;
    temp_fresh_ms_ = now_ms;
  link_fresh_ms_ = now_ms;
    return out_;
  }
  out_.parked = false;
  out_.parked_for_s = 0;

  // An arm that is never confirmed lapses here rather than lingering as a
  // half-pressed button until some unrelated press hours later completes it.
  if (park_armed_ && elapsed(now_ms, park_armed_ms_, cfg_.park_confirm_ms))
    park_armed_ = false;
  out_.park_pending = park_armed_;

  if (in.temp_valid)
    temp_fresh_ms_ = now_ms;
  // Any station-sourced value arriving is proof the BLE link is carrying data.
  // The temperature probe is local and deliberately excluded - a live DS18B20
  // says nothing about whether the P310 is still listening.
  if (in.power_valid || in.soc_valid || in.input_power_valid)
    link_fresh_ms_ = now_ms;
  // An unreadable power sensor must never be mistaken for "compressor idle" -
  // that would release the inverter early. Unknown counts as busy.
  if (!in.power_valid || in.output_power_w >= cfg_.compressor_idle_w)
    compressor_busy_ms_ = now_ms;
  if (!in.power_valid || in.output_power_w >= cfg_.manual_release_w)
    manual_busy_ms_ = now_ms;

  // --- fail-safe override, evaluated first and winning over everything ---
  out_.force_on = true;
  if (!elapsed(now_ms, boot_ms_, cfg_.boot_force_ms)) {
    out_.reason = AcReason::BOOT;
  } else if (!in.ble_connected) {
    // Honest about what this can and cannot do: with the link down the switch
    // write goes nowhere, so this does NOT keep the fridge running. It holds
    // the request true so the reconnect restores AC immediately, and it is a
    // recovery behaviour, not a fail-safe. The fail-safe that can still act is
    // LINK_STALE below, which fires while there is a link left to carry it.
    out_.reason = AcReason::BLE_LOST;
  } else if (elapsed(now_ms, link_fresh_ms_, cfg_.link_stale_ms)) {
    // Connected by the stack's reckoning, but no station data has arrived.
    // A wedged-but-open link used to be invisible here: the local probe kept
    // the thermostat running and it went on commanding a switch nobody was
    // listening to. Commanding ON now is the last moment that can still work.
    out_.reason = AcReason::LINK_STALE;
  } else if (elapsed(now_ms, temp_fresh_ms_, cfg_.temp_stale_ms)) {
    out_.reason = AcReason::TEMP_STALE;
  } else if (watchdog_tripped) {
    out_.reason = AcReason::WATCHDOG;
  } else {
    out_.force_on = false;
  }

  update_fridge_(now_ms, in);
  update_manual_(now_ms, in);
  update_surplus_(now_ms, in);

  // --- drive inhibit (Phase 4). A suppressor, never a request, and it expires
  // on its own so a stuck-true engine signal cannot hold the fridge off. ---
  if (in.drive_inhibit) {
    if (!inhibit_seen_) {
      inhibit_seen_ = true;
      inhibit_since_ms_ = now_ms;
    }
    out_.inhibit_active = !elapsed(now_ms, inhibit_since_ms_, cfg_.inhibit_max_ms);
  } else {
    inhibit_seen_ = false;
    out_.inhibit_active = false;
  }

  // The 10 C override is not an ordinary request: like force_on it ignores the
  // drive inhibit. Silence and interlocks are preferences; food is not.
  const bool requested = out_.fridge_req || out_.manual_req || out_.surplus_req;
  out_.ac_on = out_.force_on || out_.fridge_hard || (requested && !out_.inhibit_active);

  if (!out_.force_on) {
    if (!out_.ac_on) {
      out_.reason = AcReason::OFF;
    } else if (out_.fridge_req) {
      out_.reason = out_.fridge_hard ? AcReason::FRIDGE_HARD : AcReason::FRIDGE;
    } else if (out_.manual_req) {
      out_.reason = AcReason::MANUAL;
    } else {
      out_.reason = AcReason::SURPLUS;
    }
  }
  return out_;
}

// Fridge block scheduler. `REWRITTEN 2026-08-25` per docs/PATCHES.md P2 and
// ANALYSIS section 4 - this was a compressor follower, and there is nothing to
// follow. The appliance has a variable-speed inverter compressor that modulates
// against accumulated heat for hours and at high ambient never stops, so the old
// release condition (output_power quiet for 90s) could never be satisfied and
// the inverter stayed on permanently, saving nothing.
//
// So the supervisor picks the cycles now. Temperature is an override ceiling
// rather than the primary input - the inversion is the point of the rewrite.
// Prefer fewer, longer blocks: the restart penalty scales with cycle count.
void ArbiterCore::update_fridge_(uint32_t now_ms, const ArbiterInputs &in) {
  if (!in.temp_valid)
    return;  // hold the previous request; force_on is already covering this

  // Hard override outranks the anti-short-cycle timer. Food beats compressor
  // wear, and it applies in sleep mode too - silence is a preference.
  if (in.fridge_temp_c >= cfg_.temp_hard_c) {
    if (!out_.fridge_req) {
      out_.fridge_req = true;
      fridge_since_ms_ = now_ms;
    }
    // Latched for the whole cycle, not just while the temperature is above the
    // threshold: once it has been let go this far, run it properly back down.
    out_.fridge_hard = true;
    return;
  }

  // Coasting (sleep mode, or drive inhibit) raises the ceiling and, when a
  // cycle does run, drives all the way down to maximise remaining coast.
  const bool coasting = in.sleep_mode || in.drive_inhibit;
  const float ceiling = coasting ? cfg_.sleep_ceiling_c : cfg_.temp_on_c;
  const float floor_c = coasting ? cfg_.sleep_target_c : cfg_.temp_off_c;

  if (!out_.fridge_req) {
    // Anti-short-cycle first: nothing starts a block before the rest floor,
    // except the hard override, which returned above.
    if (!elapsed(now_ms, fridge_since_ms_, cfg_.min_off_ms))
      return;

    // Two ways in. The ceiling is the safety net; the schedule is the normal
    // path and is suppressed while coasting, which is what makes sleep mode
    // "pre-cool, then nothing until the ceiling" rather than a cycle count.
    const bool too_warm = in.fridge_temp_c > ceiling;
    const bool scheduled = !coasting && elapsed(now_ms, fridge_since_ms_, cfg_.rest_block_ms) &&
                           in.fridge_temp_c > floor_c;
    // The floor test on `scheduled` matters: starting a block on a cabinet
    // already at target would burn min_on_ms of inverter for no cooling, every
    // rest period, forever.
    if (too_warm || scheduled) {
      out_.fridge_req = true;
      fridge_since_ms_ = now_ms;
    }
    return;
  }

  // --- running: when does the block end? ---
  const bool settled = elapsed(now_ms, fridge_since_ms_, cfg_.min_on_ms);
  if (!settled)
    return;

  const bool cold = in.fridge_temp_c < floor_c;

  // A run that started as the hard override runs properly back down to the
  // floor: once it has been let go that far, ending on a block timer would
  // leave it barely inside the safe band and straight back at the ceiling.
  if (out_.fridge_hard) {
    if (cold) {
      out_.fridge_req = false;
      out_.fridge_hard = false;
      fridge_since_ms_ = now_ms;
    }
    return;
  }

  // Coasting has no block schedule, so its only exit is reaching the target -
  // and the target is the deep one, to maximise the coast that follows.
  if (coasting) {
    if (cold) {
      out_.fridge_req = false;
      fridge_since_ms_ = now_ms;
    }
    return;
  }

  // Normal running. Any one of three ends the block - note OR, not AND. The
  // compressor-stopped term is the salvaged Strategy A: worth taking when this
  // fridge does stop (it does, at low ambient), but never required, because
  // requiring it is precisely the bug this rewrite exists to remove.
  const bool block_done = elapsed(now_ms, fridge_since_ms_, cfg_.run_block_ms);
  const bool compressor_stopped = elapsed(now_ms, compressor_busy_ms_, cfg_.compressor_idle_ms);
  if (cold || block_done || compressor_stopped) {
    out_.fridge_req = false;
    fridge_since_ms_ = now_ms;
  }
}

void ArbiterCore::update_manual_(uint32_t now_ms, const ArbiterInputs &in) {
  (void) in;
  out_.manual_warning = false;
  if (!out_.manual_req) {
    out_.manual_remaining_s = 0;
    return;
  }

  // Hard ceiling first - three hours of inverter is never an accident worth
  // honouring.
  if (elapsed(now_ms, manual_start_ms_, cfg_.manual_max_ms)) {
    out_.manual_req = false;
    out_.manual_remaining_s = 0;
    return;
  }

  // Auto-release: nothing above the release floor has drawn power for long
  // enough, so the cooking is over even if the timer disagrees.
  const bool past_grace = elapsed(now_ms, manual_start_ms_, cfg_.manual_grace_ms);
  if (past_grace && elapsed(now_ms, manual_busy_ms_, cfg_.manual_release_ms)) {
    out_.manual_req = false;
    out_.manual_remaining_s = 0;
    return;
  }

  const uint32_t to_deadline = static_cast<uint32_t>(manual_deadline_ms_ - now_ms);
  if (static_cast<int32_t>(to_deadline) <= 0) {
    out_.manual_req = false;
    out_.manual_remaining_s = 0;
    return;
  }

  // Report whichever release comes first, so the display and the buzzer are
  // telling the truth rather than reciting the timer.
  uint32_t remaining = to_deadline;
  if (past_grace) {
    const uint32_t idle_for = static_cast<uint32_t>(now_ms - manual_busy_ms_);
    const uint32_t to_auto =
        (idle_for >= cfg_.manual_release_ms) ? 0u : (cfg_.manual_release_ms - idle_for);
    if (to_auto < remaining)
      remaining = to_auto;
  }
  out_.manual_remaining_s = remaining / 1000u;
  out_.manual_warning = remaining <= cfg_.manual_warn_ms;
}

void ArbiterCore::update_surplus_(uint32_t now_ms, const ArbiterInputs &in) {
  // Suppressed entirely while coasting - the whole point of sleep mode is that
  // nothing opportunistic gets to start the inverter.
  if (in.sleep_mode || in.drive_inhibit || !in.input_power_valid || !in.power_valid ||
      !in.soc_valid) {
    if (out_.surplus_req) {
      out_.surplus_req = false;
      surplus_since_ms_ = now_ms;
    }
    return;
  }

  const float surplus = in.input_power_w - in.output_power_w;
  if (!out_.surplus_req) {
    if (surplus > cfg_.surplus_margin_w && in.soc_pct >= cfg_.surplus_soc_pct) {
      out_.surplus_req = true;
      surplus_since_ms_ = now_ms;
    }
    return;
  }
  // Wide release hysteresis plus a minimum on-time: a passing cloud must not
  // cost an inverter start.
  const bool gone =
      surplus < (cfg_.surplus_margin_w * 0.5f) || in.soc_pct < (cfg_.surplus_soc_pct - 5.0f);
  if (gone && elapsed(now_ms, surplus_since_ms_, cfg_.surplus_min_on_ms)) {
    out_.surplus_req = false;
    surplus_since_ms_ = now_ms;
  }
}

void ArbiterCore::manual_press(uint32_t now_ms) {
  // Pressing the cooking button in a parked van means someone is back in it
  // and wants 230V. That is a deliberate press of a dedicated button, so treat
  // it as the exit gesture rather than ignoring it and looking broken.
  if (parked_)
    park_exit(now_ms);

  if (!out_.manual_req) {
    out_.manual_req = true;
    manual_start_ms_ = now_ms;
    manual_deadline_ms_ = now_ms + cfg_.manual_initial_ms;
    manual_busy_ms_ = now_ms;  // grace starts now; do not release on stale idle
    return;
  }
  // Extend, but never past the hard ceiling measured from the first press.
  const uint32_t extended = manual_deadline_ms_ + cfg_.manual_extend_ms;
  const uint32_t ceiling = manual_start_ms_ + cfg_.manual_max_ms;
  manual_deadline_ms_ = (static_cast<int32_t>(extended - ceiling) > 0) ? ceiling : extended;
}

void ArbiterCore::manual_cancel(uint32_t now_ms) {
  (void) now_ms;
  out_.manual_req = false;
  out_.manual_remaining_s = 0;
  out_.manual_warning = false;
}

// --- parked mode -----------------------------------------------------------

bool ArbiterCore::park_request(uint32_t now_ms) {
  if (parked_)
    return true;

  // Second request inside the window: commit.
  if (park_armed_ && !elapsed(now_ms, park_armed_ms_, cfg_.park_confirm_ms)) {
    park_armed_ = false;
    out_.park_pending = false;
    parked_ = true;
    parked_since_ms_ = now_ms;
    // Publish immediately rather than waiting for the next tick: the caller is
    // a button handler, and the UI reads outputs() the instant it returns.
    out_.parked = true;
    return true;
  }

  // First request, or one arriving after a previous arm lapsed: arm and wait.
  // Re-arming rather than committing is the whole point - a stale arm from an
  // hour ago must never be completed by an unrelated press.
  park_armed_ = true;
  park_armed_ms_ = now_ms;
  out_.park_pending = true;
  return false;
}

void ArbiterCore::set_parked(uint32_t now_ms, bool on) {
  if (on) {
    if (!parked_) {
      parked_ = true;
      parked_since_ms_ = now_ms;
    }
    out_.parked = true;
    park_armed_ = false;
    out_.park_pending = false;
  } else {
    park_exit(now_ms);
  }
}

void ArbiterCore::park_exit(uint32_t now_ms) {
  if (!parked_)
    return;
  parked_ = false;
  out_.parked = false;
  out_.parked_for_s = 0;
  // A pending arm does not survive leaving the mode: the next single press
  // must arm afresh, not complete a confirmation from before the exit.
  park_armed_ = false;
  out_.park_pending = false;
  // Re-arm the boot force-on window. Coming out of parked mode is exactly the
  // moment food gets loaded, and the normal thermostat would sit idle at cabin
  // temperature waiting for a threshold it is already past. Power it now and
  // let the arbiter take over once the probes have had their say.
  boot_ms_ = now_ms;
  temp_fresh_ms_ = now_ms;
  link_fresh_ms_ = now_ms;
  compressor_busy_ms_ = now_ms;
  manual_busy_ms_ = now_ms;
  fridge_since_ms_ = now_ms - cfg_.min_off_ms;
}

}  // namespace van

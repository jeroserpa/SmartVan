#!/usr/bin/env python3
"""
soak_report.py - analyse a CSV downloaded from van-core-probes (/soak).

Standard library only. Usage:

    python tools/soak_report.py soak-20260917-0830.csv
    python tools/soak_report.py soak.csv --swap-probes      # probe roles reversed
    python tools/soak_report.py soak.csv --stop-w 5 --capacity 3900

Sections, in the order the design needs them:
  1. data quality   rows, gaps, reboots, clock, plug/probe dropouts
  2. cabinet        where the default thermostat holds it, and the swing
  3. compressor     does it ever stop; stop lengths; coast dT/dt per stop
  4. energy         fridge Wh/day, and fridge W against (cabin - fridge)
  5. overhead       station overhead from the SOC balance, fridge excluded
  6. doors          fast excursions on the fridge probe
  7. node health    BLE, internal heap trend, die temperature vs cabin

Every figure is printed with the number of samples behind it. A number from
40 minutes of data is not a number from four days.
"""

import argparse
import csv
import math
import statistics as st
import sys


def load(path):
    meta, rows = [], []
    with open(path, newline="", encoding="utf-8") as f:
        lines = [l for l in f]
    body = []
    for l in lines:
        if l.startswith("#"):
            meta.append(l.strip())
        else:
            body.append(l)
    for r in csv.DictReader(body):
        out = {}
        for k, v in r.items():
            if k == "local_time":
                out[k] = v
            elif v == "" or v is None:
                out[k] = None
            else:
                out[k] = float(v)
        rows.append(out)
    return meta, rows


def t_of(r):
    """Seconds on one monotonic axis: wall clock if known, else uptime."""
    return r["epoch"] if r["epoch"] else None


def vals(rows, k):
    return [r[k] for r in rows if r.get(k) is not None]


def linfit(xs, ys):
    n = len(xs)
    if n < 3:
        return None
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx == 0:
        return None
    b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx
    a = my - b * mx
    ss_res = sum((y - a - b * x) ** 2 for x, y in zip(xs, ys))
    ss_tot = sum((y - my) ** 2 for y in ys) or 1e-12
    return a, b, 1 - ss_res / ss_tot


def fmt_h(s):
    return f"{s / 3600:.1f} h" if s >= 3600 else f"{s / 60:.0f} min"


def section(title):
    print(f"\n== {title} " + "=" * max(0, 60 - len(title)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("--swap-probes", action="store_true",
                    help="probe 0 is the cabin, probe 1 the fridge")
    ap.add_argument("--stop-w", type=float, default=5.0,
                    help="plug power below this = compressor stopped (default 5 W)")
    ap.add_argument("--min-stop-min", type=float, default=10.0,
                    help="ignore stops shorter than this for coast rates")
    ap.add_argument("--capacity", type=float, default=3900.0,
                    help="usable Wh, for the SOC balance (M2: ~3900)")
    ap.add_argument("--door-kpm", type=float, default=0.5,
                    help="fridge probe rise faster than this K/min = door event")
    a = ap.parse_args()

    meta, rows = load(a.csv)
    if a.swap_probes:
        for r in rows:
            r["t_fridge"], r["t_cabin"] = r["t_cabin"], r["t_fridge"]
    if not rows:
        sys.exit("no rows")

    # ---------------------------------------------------------------- 1
    section("1. Data quality")
    for m in meta:
        print(m)
    timed = [r for r in rows if r["epoch"]]
    print(f"rows: {len(rows)}, with wall clock: {len(timed)}")
    if len(timed) < len(rows):
        print("  WARNING: rows without clock - the /soak page was not opened "
              "after boot. Time-of-day analysis ignores them.")
    if timed:
        span = timed[-1]["epoch"] - timed[0]["epoch"]
        print(f"span: {timed[0]['local_time']} -> {timed[-1]['local_time']} ({fmt_h(span)})")
        steps = [b["epoch"] - a_["epoch"] for a_, b in zip(timed, timed[1:])]
        interval = st.median(steps) if steps else 10
        gaps = [(timed[i]["local_time"], s) for i, s in enumerate(steps) if s > 3 * interval]
        print(f"interval: {interval:.0f} s, gaps > 3 intervals: {len(gaps)}")
        for t, s in gaps[:10]:
            print(f"  gap of {fmt_h(s)} after {t}")
    boots = sorted({int(r["boot"]) for r in rows})
    print(f"boots in data: {boots}" + ("  <- REBOOTS, see '# boot' lines" if len(boots) > 1 else ""))
    for k in ("t_fridge", "t_cabin", "fridge_w", "soc"):
        miss = sum(1 for r in rows if r.get(k) is None)
        print(f"  {k:9s} missing {miss} ({100 * miss / len(rows):.1f} %)")

    # ---------------------------------------------------------------- 2
    section("2. Cabinet on the default thermostat")
    tf = vals(rows, "t_fridge")
    tc = vals(rows, "t_cabin")
    if tf:
        q = st.quantiles(tf, n=20)
        print(f"fridge probe: mean {st.mean(tf):.2f} C, p5 {q[0]:.2f}, p95 {q[-1]:.2f}, "
              f"min {min(tf):.2f}, max {max(tf):.2f}  (n={len(tf)})")
        print("  The probe reads its mounting point, not the food. Apply the "
              "glass-of-water offset (docs/soak-probes.md) before comparing with 7-8 C.")
    if tc:
        print(f"cabin probe:  mean {st.mean(tc):.2f} C, min {min(tc):.2f}, max {max(tc):.2f}")
    if tf and tc and st.mean(tf) > st.mean(tc):
        print("  !! fridge probe reads warmer than cabin on average - probes are "
              "probably swapped. Re-run with --swap-probes.")

    # ---------------------------------------------------------------- 3
    section("3. Compressor: does it stop?")
    pw = [(r["epoch"], r["fridge_w"], r["t_fridge"]) for r in timed if r.get("fridge_w") is not None]
    stops = []
    if pw:
        running = [p for _, p, _ in pw if p >= a.stop_w]
        print(f"plug samples: {len(pw)}, running {100 * len(running) / len(pw):.1f} % "
              f"(threshold {a.stop_w} W)")
        if running:
            q = st.quantiles(running, n=10)
            print(f"running power: median {st.median(running):.1f} W, p10 {q[0]:.1f}, p90 {q[-1]:.1f}")
        cur = None
        for e, p, t in pw:
            if p < a.stop_w:
                if cur is None:
                    cur = [e, e, []]
                cur[1] = e
                if t is not None:
                    cur[2].append((e, t))
            elif cur is not None:
                stops.append(cur)
                cur = None
        if cur is not None:
            stops.append(cur)
        print(f"stop events: {len(stops)}")
        long_stops = [s for s in stops if s[1] - s[0] >= a.min_stop_min * 60]
        for s0, s1, pts in long_stops[:30]:
            fit = linfit([(e - s0) / 3600 for e, _ in pts], [t for _, t in pts]) if len(pts) > 5 else None
            rate = f"coast {fit[1]:+.2f} K/h (r2 {fit[2]:.2f})" if fit else "no probe data"
            print(f"  {fmt_h(s1 - s0):>8s}  {rate}")
        if not stops:
            print("  Never stopped: the M6 finding holds at the default setting too.\n"
                  "  Coast rate must come from an imposed OFF block (docs/soak-probes.md, test B).")

    # ---------------------------------------------------------------- 4
    section("4. Fridge energy")
    ek = [(r["epoch"], r["fridge_kwh"]) for r in timed if r.get("fridge_kwh") is not None]
    if len(ek) > 2:
        de = ek[-1][1] - ek[0][1]
        dt = ek[-1][0] - ek[0][0]
        if dt > 0 and de >= 0:
            print(f"plug energy: {de * 1000:.0f} Wh over {fmt_h(dt)} = {de * 1000 / dt * 3600:.1f} W avg")
            if dt >= 20 * 3600:
                print(f"  = {de * 1000 * 86400 / dt:.0f} Wh/day; rated 114 kWh/yr = 312 Wh/day (EU test, 25 C ambient)")
            else:
                print("  (no per-day figure: under 20 h, and the fridge's on/off pattern dominates)")
    # Hourly means: fridge W against the temperature difference driving the leak.
    buckets = {}
    for r in timed:
        if None in (r.get("fridge_w"), r.get("t_fridge"), r.get("t_cabin")):
            continue
        buckets.setdefault(int(r["epoch"] // 3600), []).append(r)
    xs, ys = [], []
    for b in buckets.values():
        if len(b) < 180:  # at least half an hour at 10 s
            continue
        xs.append(st.mean(r["t_cabin"] - r["t_fridge"] for r in b))
        ys.append(st.mean(r["fridge_w"] for r in b))
    fit = linfit(xs, ys)
    if fit:
        a0, b0, r2 = fit
        print(f"hourly fridge W vs (cabin - fridge): W = {a0:.1f} + {b0:.2f} * dT "
              f"(r2 {r2:.2f}, {len(xs)} hours, dT {min(xs):.1f}..{max(xs):.1f} K)")
        for amb in (25, 30, 35):
            d = amb - (st.mean(tf) if tf else 5)
            print(f"  extrapolated at cabin {amb} C: {a0 + b0 * d:.1f} W "
                  f"{'(outside measured range)' if d > max(xs) else ''}")
        print("  Hourly lag (compressor responds to heat hours later) weakens r2; "
              "trust the slope only with a wide dT range.")
    else:
        print("not enough complete hours for the W-vs-dT fit")

    # ---------------------------------------------------------------- 5
    section("5. Station overhead (SOC balance, fridge excluded)")
    # Windows of 2 h with no AC input: overhead = in - out_to_loads - d(E)/dt.
    # out_w already includes the fridge and the plug's own draw; what is left
    # after subtracting (in - out) from the battery slope is the station.
    quiet = [r for r in timed if r.get("soc") is not None and r.get("in_w") is not None
             and r.get("out_w") is not None and (r.get("ac_in_w") or 0) == 0]
    win = 7200
    results = []
    i = 0
    while i < len(quiet):
        j = i
        while j + 1 < len(quiet) and quiet[j + 1]["epoch"] - quiet[i]["epoch"] <= win \
                and quiet[j + 1]["epoch"] - quiet[j]["epoch"] < 120:
            j += 1
        span = quiet[j]["epoch"] - quiet[i]["epoch"]
        seg = quiet[i:j + 1]
        # A pack pinned at either end (full on solar, or at the cutoff) is not
        # integrating anything: the balance is meaningless there.
        pinned = any(r["soc"] >= 99.5 or r["soc"] <= 5.5 for r in seg)
        if span >= 0.9 * win and not pinned:
            batt_w = (seg[-1]["soc"] - seg[0]["soc"]) / 100 * a.capacity / (span / 3600)
            p_in = st.mean(r["in_w"] for r in seg)
            p_out = st.mean(r["out_w"] for r in seg)
            results.append((seg[0]["local_time"], p_in - p_out - batt_w, p_in, p_out, batt_w))
        i = j + 1
    if results:
        res_w = 0.1 / 100 * a.capacity / (win / 3600)
        print(f"{len(results)} windows of {fmt_h(win)} without AC input "
              f"(SOC resolution alone = +/-{res_w:.1f} W per window)")
        for t, ov, pi, po, bw in results[:12]:
            print(f"  {t}  overhead {ov:5.1f} W   (in {pi:.0f}, out {po:.0f}, batt {bw:+.0f})")
        print(f"  median overhead: {st.median(r[1] for r in results):.1f} W "
              "(M2 said ~48 W including everything; inverter idle still needs idle-test)")
    else:
        print("no 2 h windows without AC input")
    fw = [(r["out_w"], r["fridge_w"]) for r in timed
          if r.get("out_w") is not None and r.get("fridge_w") is not None]
    if fw:
        diff = [o - f for o, f in fw]
        print(f"out_w - fridge_w: median {st.median(diff):.1f} W  "
              "(= plug self-draw + other AC loads + meter disagreement)")

    # ---------------------------------------------------------------- 6
    section("6. Door openings (fast rises on the fridge probe)")
    events, last = [], None
    tp = [(r["epoch"], r["t_fridge"], r["local_time"]) for r in timed if r.get("t_fridge") is not None]
    # Compare across >= 60 s: at 10 s sampling a single 0.0625 K DS18B20 step
    # is already 0.375 K/min, so sample-to-sample slopes are pure quantisation.
    j = 0
    for i, (e1, t1, lt) in enumerate(tp):
        while j < i and e1 - tp[j][0] > 60:
            j += 1
        if j == 0 or e1 - tp[j - 1][0] > 90:
            continue
        e0, t0, _ = tp[j - 1]
        if (t1 - t0) / ((e1 - e0) / 60) > a.door_kpm:
            if last is None or e1 - last > 300:
                events.append((lt, e1))
            last = e1
    print(f"{len(events)} events above {a.door_kpm} K/min over 60 s (5 min merge)")
    for lt, _ in events[:15]:
        print(f"  {lt}")
    print("  Check a few against memory. None at all with the probe foam-covered "
          "on the wall is the intended result, and means the EMA can be shorter.")

    # ---------------------------------------------------------------- 7
    section("7. Node health")
    ble = vals(rows, "ble_up")
    if ble:
        print(f"BLE up {100 * sum(ble) / len(ble):.2f} % of rows, "
              f"drops (max of counter) {max(vals(rows, 'ble_drops') or [0]):.0f}")
    fc = vals(rows, "ble_first_s")
    if fc:
        print(f"first BLE connect after boot: {sorted(set(fc))} s")
    hi = [(r["uptime_s"], r["heap_int"]) for r in rows if r.get("heap_int") is not None
          and r["boot"] == rows[-1]["boot"]]
    if len(hi) > 10:
        fit = linfit([u / 3600 for u, _ in hi], [h for _, h in hi])
        print(f"internal heap: now {hi[-1][1]:.0f} B, min-ever {min(vals(rows, 'heap_int_min')):.0f} B, "
              f"trend {fit[1]:+.0f} B/h over {fmt_h(hi[-1][0] - hi[0][0])}" +
              ("  <- LEAK?" if fit[1] < -50 and hi[-1][0] - hi[0][0] >= 12 * 3600 else
               "  (too short to judge a leak; needs 12 h+)" if hi[-1][0] - hi[0][0] < 12 * 3600 else ""))
    dc = [(r["die_c"], r["t_cabin"], r.get("backlight")) for r in rows
          if r.get("die_c") is not None and r.get("t_cabin") is not None]
    for bl, label in ((0, "backlight off"), (1, "backlight on")):
        d = [x - c for x, c, b in dc if b == bl]
        if d:
            print(f"die - cabin, {label}: {st.mean(d):.1f} K (n={len(d)}) -> "
                  f"die at cabin 40 C ~ {40 + st.mean(d):.0f} C")
    print("  PSRAM is rated to 85 C ambient; the die sensor reads hotter than the "
          "package, so treat this as an upper bound.")


if __name__ == "__main__":
    main()

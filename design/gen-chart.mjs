// Generates the SVG geometry for the energy-balance chart from a plausible
// 24h simulation, so the mockup's shapes are consistent with each other
// (net line = in - out, SOC integrates net, duty % matches the ribbon).
// Not real data. Replaced by the SD log once Phase 1 logging exists.

const N = 288;               // 5 min samples
const H = 24 / N;            // hours per sample
const CAP_WH = 3840;

const hours = i => (i + 0.5) * H;

// --- generation ---------------------------------------------------------
function solar(t) {
  if (t < 6.7 || t > 20.0) return 0;
  const g = 640 * Math.exp(-Math.pow((t - 13.1) / 3.05, 2));
  // two cloud notches, because a clean bell curve is a lie
  const c1 = t > 10.4 && t < 11.1 ? 0.42 : 1;
  const c2 = t > 15.8 && t < 16.4 ? 0.55 : 1;
  return Math.max(0, g * c1 * c2);
}
// alternator charging: one drive, 09:00 -> 10:35, tapering per the voltage rule
function acIn(t) {
  if (t < 9.0 || t > 10.58) return 0;
  if (t < 9.15) return 300;
  if (t < 9.6) return 1100;
  if (t < 10.0) return 700;
  return 1100;
}

// inverter ON windows: fridge cycles (denser as the cabin warms) + cooking
const fridgeCycles = [];
{
  let t = 0.35;
  while (t < 24) {
    // afternoon heat shortens the off period
    const heat = Math.exp(-Math.pow((t - 15.5) / 6.5, 2));
    const off = 0.98 - 0.42 * heat;      // hours off
    const on = 0.30 + 0.10 * heat;       // hours on
    // sleep mode 23:00-06:00: one long cycle only
    if (t > 23.0 || t < 6.0) { t += 0.5; continue; }
    fridgeCycles.push([t, t + on]);
    t += on + off;
  }
  fridgeCycles.push([2.72, 3.05]);       // the single permitted night cycle
}
const manualWindows = [[8.05, 8.60], [13.30, 14.05], [19.80, 20.62]];
const cookLoads = [
  [8.10, 8.22, 1800],   // kettle
  [13.38, 13.92, 1450], // hob
  [19.88, 20.48, 1620], // hob
];

const inWin = (t, ws) => ws.some(([a, b]) => t >= a && t < b);

const rows = [];
for (let i = 0; i < N; i++) {
  const t = hours(i);
  const acOn = inWin(t, fridgeCycles) || inWin(t, manualWindows);
  const comp = inWin(t, fridgeCycles);
  let out = 12;                                  // nodes + 12V habitation bus
  if (acOn) out += 35;                           // inverter idle - the whole problem
  if (comp) out += 39;                           // compressor, 35W AC / 0.9
  for (const [a, b, w] of cookLoads) if (t >= a && t < b) out += w / 0.9;
  const pin = solar(t) + acIn(t);
  rows.push({ t, solar: solar(t), acIn: acIn(t), out, acOn, comp, net: pin - out });
}

// SOC, integrated from net
let wh = 0.62 * CAP_WH;
for (const r of rows) { wh = Math.min(CAP_WH, Math.max(0, wh + r.net * H)); r.soc = wh / CAP_WH * 100; }

// --- geometry -----------------------------------------------------------
const W = 720, CH = 300;
const YMAX = 1450, YMIN = -2150;   // must clear the generated peaks; asserted below
const x = i => (i / (N - 1)) * W;
const y = w => ((YMAX - w) / (YMAX - YMIN)) * CH;
const Y0 = y(0);
const r2 = n => Math.round(n * 10) / 10;

function areaBetween(lower, upper) {
  // upper: watts array (top edge), lower: watts array (bottom edge)
  let d = `M${r2(x(0))} ${r2(y(upper[0]))}`;
  for (let i = 1; i < N; i++) d += `L${r2(x(i))} ${r2(y(upper[i]))}`;
  for (let i = N - 1; i >= 0; i--) d += `L${r2(x(i))} ${r2(y(lower[i]))}`;
  return d + 'Z';
}
function line(vals) {
  let d = `M${r2(x(0))} ${r2(y(vals[0]))}`;
  for (let i = 1; i < N; i++) d += `L${r2(x(i))} ${r2(y(vals[i]))}`;
  return d;
}
function socLine(vals) { // 0-100% mapped over the full plot height
  let d = `M${r2(x(0))} ${r2(CH - vals[0] / 100 * CH)}`;
  for (let i = 1; i < N; i++) d += `L${r2(x(i))} ${r2(CH - vals[i] / 100 * CH)}`;
  return d;
}

const zeros = rows.map(() => 0);
const sol = rows.map(r => r.solar);
const solPlusAc = rows.map(r => r.solar + r.acIn);
const negOut = rows.map(r => -r.out);

// ribbons: merged ON blocks as x-ranges
function blocks(pred) {
  const out = [];
  let start = null;
  for (let i = 0; i < N; i++) {
    const on = pred(rows[i]);
    if (on && start === null) start = i;
    if (!on && start !== null) { out.push([start, i]); start = null; }
  }
  if (start !== null) out.push([start, N]);
  return out.map(([a, b]) => ({ x: r2(x(a)), w: r2(Math.max(2.5, x(b) - x(a))) }));
}

const dutyAc = rows.filter(r => r.acOn).length / N;
const dutyComp = rows.filter(r => r.comp).length / N;
const harvest = rows.reduce((s, r) => s + (r.solar + r.acIn) * H, 0);
const used = rows.reduce((s, r) => s + r.out * H, 0);
const idleWh = rows.filter(r => r.acOn).length * H * 35;
const idleAlways = 24 * 35;

// The SVG viewport clips: a series outside [YMIN, YMAX] renders as a flat cut
// edge while the axis label still claims that value is the extent. Fail loudly.
{
  const peakIn = Math.max(...solPlusAc), peakOut = Math.min(...negOut);
  const netHi = Math.max(...rows.map(r => r.net)), netLo = Math.min(...rows.map(r => r.net));
  if (peakIn > YMAX || peakOut < YMIN || netHi > YMAX || netLo < YMIN)
    throw new Error(`axis too tight: in ${peakIn.toFixed(0)}, out ${peakOut.toFixed(0)}, net ${netLo.toFixed(0)}..${netHi.toFixed(0)} vs [${YMIN}, ${YMAX}]`);
}

console.log(JSON.stringify({
  solarArea: areaBetween(zeros, sol),
  acInArea: areaBetween(sol, solPlusAc),
  outArea: areaBetween(zeros, negOut),
  netLine: line(rows.map(r => r.net)),
  socLine: socLine(rows.map(r => r.soc)),
  zeroY: r2(Y0),
  acBlocks: blocks(r => r.acOn),
  compBlocks: blocks(r => r.comp),
  stats: {
    dutyAc: Math.round(dutyAc * 100),
    dutyComp: Math.round(dutyComp * 100),
    harvestKwh: (harvest / 1000).toFixed(2),
    usedKwh: (used / 1000).toFixed(2),
    netKwh: ((harvest - used) / 1000).toFixed(2),
    socEnd: Math.round(rows[N - 1].soc),
    socMin: Math.round(Math.min(...rows.map(r => r.soc))),
    idleWh: Math.round(idleWh),
    idleAlwaysWh: Math.round(idleAlways),
    savedWh: Math.round(idleAlways - idleWh),
    cycles: blocks(r => r.comp).length,
    overheadPts: Math.round(dutyAc * 100) - Math.round(dutyComp * 100),
  },
}, null, 1));

// Substitutes the generated chart geometry into the .src.html sources and
// writes the .dc.html artboards the canvas is seeded from.
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';

const chart = JSON.parse(readFileSync(new URL('./chart.json', import.meta.url), 'utf8'));

const vals = {
  SOLAR_AREA: chart.solarArea,
  ACIN_AREA: chart.acInArea,
  OUT_AREA: chart.outArea,
  NET_LINE: chart.netLine,
  SOC_LINE: chart.socLine,
  ZERO_Y: String(chart.zeroY),
  AC_BLOCKS: chart.acBlocks
    .map(b => `<rect x="${b.x}" y="0" width="${b.w}" height="10" rx="1" fill="oklch(0.78 0.145 72)"></rect>`)
    .join(''),
  COMP_BLOCKS: chart.compBlocks
    .map(b => `<rect x="${b.x}" y="0" width="${b.w}" height="10" rx="1" fill="oklch(0.60 0.06 72)"></rect>`)
    .join(''),
};
for (const [k, v] of Object.entries(chart.stats)) vals['S_' + k.toUpperCase()] = String(v);

const dir = new URL('./', import.meta.url);
for (const f of readdirSync(dir).filter(f => f.endsWith('.src.html'))) {
  let s = readFileSync(new URL('./' + f, dir), 'utf8');
  s = s.replace(/@@([A-Z0-9_]+)@@/g, (m, k) => {
    if (!(k in vals)) throw new Error(`${f}: unknown placeholder ${m}`);
    return vals[k];
  });
  const out = f.replace(/\.src\.html$/, '.dc.html');
  writeFileSync(new URL('./' + out, dir), s);
  console.log(`${out}  ${(s.length / 1024).toFixed(1)} KiB`);
}

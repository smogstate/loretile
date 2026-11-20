// The map at scale (2026-11-20): past 150 nodes a physics tick writes the
// svg directly (ui/draw-live!) instead of re-rendering through React. What
// must hold at 400 nodes: every node is drawn; a dragged node moves and its
// links follow it (the direct writes keep nodes and edges together); after
// the layout settles React's render agrees with them; a tap still selects.
// Prints the frame rate (not asserted — the machine varies).
// Usage: serve public/ on :8291; node test/scale-check.js
const puppeteer = require('puppeteer-core');
const BASE = process.argv[2] || 'http://localhost:8291/';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const N = 400;
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  await page.setViewport({ width: 1280, height: 900 });
  // a binary tree: node i linked to node i/2
  const ops = [];
  for (let i = 0; i < N; i++) ops.push(`[:node-set "n${i}" {:title "Узел ${i}"}]`);
  for (let i = 1; i < N; i++) ops.push(`[:edge-set "n${i}" :see-also "n${Math.floor(i / 2)}" {}]`);
  const log = `[{:ev/id "e1" :ev/ns "demo" :ev/actor "t" :ev/ts 1 :ev/type :change :ops [${ops.join(' ')}]}]`;
  await page.evaluateOnNewDocument((log) => {
    if (sessionStorage.getItem('seeded')) return; sessionStorage.setItem('seeded', '1');
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
    localStorage.setItem('loretile-kg-log', log);
  }, log);
  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.waitForFunction((n) => document.querySelectorAll('.node').length >= n, { timeout: 60000 }, N).catch(() => {});
  check(await page.evaluate(() => document.querySelectorAll('.node').length) === N, `all ${N} nodes are drawn`);

  // where a node is drawn (its transform) and how far its links' ends are from it
  const probe = () => page.evaluate(() => {
    const g = [...document.querySelectorAll('.node')].find((e) => /Узел 0$/.test(e.querySelector('.node-label')?.textContent || e.textContent));
    const m = g.getAttribute('transform').match(/translate\(([-\d.e]+),([-\d.e]+)\)/);
    const x = +m[1], y = +m[2];
    // the line nearest to the node at one of its ends: within the rim when the links follow it
    const gap = Math.min(...[...document.querySelectorAll('line.edge')].map((l) => Math.min(
      Math.hypot(+l.getAttribute('x1') - x, +l.getAttribute('y1') - y), Math.hypot(+l.getAttribute('x2') - x, +l.getAttribute('y2') - y))));
    const r = g.getBoundingClientRect();
    return { x, y, gap, sx: r.x + r.width / 2, sy: r.y + r.height / 2 };
  });
  await sleep(1500);
  const a = await probe();
  await page.mouse.move(a.sx, a.sy); await page.mouse.down();
  for (let i = 1; i <= 10; i++) { await page.mouse.move(a.sx + i * 15, a.sy + i * 8); await sleep(40); }
  const mid = await probe();
  check(Math.hypot(mid.x - a.x, mid.y - a.y) > 40, `the dragged node moves (${Math.round(Math.hypot(mid.x - a.x, mid.y - a.y))} units)`);
  check(mid.gap < 45, `and its links follow it while dragged (nearest end ${Math.round(mid.gap)} from its centre)`);
  await page.mouse.up();

  const fps = await page.evaluate(() => new Promise((ok) => { let n = 0; const s = performance.now(); const f = () => { n++; if (performance.now() - s < 2000) requestAnimationFrame(f); else ok(Math.round(n / 2)); }; requestAnimationFrame(f); }));
  console.log(`(frame rate while settling: ${fps} fps)`);
  await sleep(12000);   // settled: React's own render takes over again
  const end = await probe();
  check(end.gap < 45, `after the layout settles nodes and links still agree (${Math.round(end.gap)})`);

  const t0 = Date.now();
  const c = await page.evaluate(() => { const r = [...document.querySelectorAll('.node')][20].getBoundingClientRect(); return [r.x + r.width / 2, r.y + r.height / 2]; });
  await page.mouse.click(c[0], c[1]);
  await page.waitForSelector('.node.selected', { timeout: 3000 }).catch(() => {});
  check(await page.$('.node.selected') !== null, `a tap selects (${Date.now() - t0} ms)`);

  await browser.close();
  console.log(failures ? 'SCALE CHECK FAILED' : 'SCALE CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

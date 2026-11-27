// The map at real size (graph review 2026-10-03): topics laid out by
// prerequisite — foundations left, what builds on them to the right — with
// a remembered switch to free physics; detail by zoom (counts only up
// close, on hover or selection); and a topic opened from search brought
// into view when it is off screen. On the SQL demo.
// Usage: serve public/ on :8291; node test/map-check.js
const puppeteer = require('puppeteer-core');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  await page.setViewport({ width: 1280, height: 820 });
  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => { localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1'); });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(300);
  await page.click('.load-demo'); await sleep(3500);

  // x of a node by its title (the <title> every node carries)
  const xOf = (title) => page.evaluate((t) => { const g = [...document.querySelectorAll('.node')].find((n) => n.querySelector(':scope > title')?.textContent.startsWith(t));
    return g ? g.getBoundingClientRect().x : null; }, title);
  const [sel, where, grp] = [await xOf('SELECT'), await xOf('Фильтрация WHERE'), await xOf('GROUP BY')];
  check(sel !== null && sel < where && where < grp, `prerequisites flow left → right: SELECT ${Math.round(sel)} < WHERE ${Math.round(where)} < GROUP BY ${Math.round(grp)}`);
  check(await page.$eval('.nav-layout', (b) => b.getAttribute('aria-pressed')) === 'true', 'the layered layout is the default');

  await page.click('.nav-layout'); await sleep(300);
  check(await page.evaluate(() => localStorage.getItem('loretile-layout')) === 'free' && await page.$eval('.nav-layout', (b) => b.getAttribute('aria-pressed')) === 'false',
    'the switch to free physics is remembered');
  await page.click('.nav-layout'); await sleep(2500);

  // detail by zoom
  const subShown = () => page.evaluate(() => { const s = [...document.querySelectorAll('.node:not(.selected) .node-sub')][0]; return s ? getComputedStyle(s).display !== 'none' : null; });
  check(await page.$eval('svg.graph', (s) => s.classList.contains('lod-mid')) && (await subShown()) === false, 'at the normal zoom the counts line is hidden');
  const box = await page.$eval('svg.graph', (s) => { const r = s.getBoundingClientRect(); return { x: r.x + 200, y: r.y + 200 }; });
  await page.mouse.move(box.x, box.y);
  for (let i = 0; i < 6; i++) { await page.mouse.wheel({ deltaY: -150 }); await sleep(60); }
  await sleep(200);
  check(await page.$eval('svg.graph', (s) => s.classList.contains('lod-near')) && (await subShown()) === true, 'zoomed in, every topic shows its counts');
  for (let i = 0; i < 16; i++) { await page.mouse.wheel({ deltaY: 200 }); await sleep(40); }
  await sleep(200);
  const far = await page.evaluate(() => ({ lod: document.querySelector('svg.graph').getAttribute('class'),
    hidden: [...document.querySelectorAll('.node:not(.hub) .node-label')].filter((l) => getComputedStyle(l).display === 'none').length,
    hubsShown: [...document.querySelectorAll('.node.hub .node-label')].filter((l) => getComputedStyle(l).display !== 'none').length }));
  check(/lod-far/.test(far.lod) && far.hidden > 0 && far.hubsShown > 0, `zoomed far out, only well-connected topics keep titles (${far.hubsShown} kept, ${far.hidden} hidden)`);

  // a topic opened from search is brought into view
  await page.mouse.move(640, 400); await page.mouse.down(); await page.mouse.move(640 + 2500, 400, { steps: 8 }); await page.mouse.up(); await sleep(200);
  const inView = (t) => page.evaluate((t) => { const g = [...document.querySelectorAll('.node')].find((n) => n.querySelector(':scope > title')?.textContent === t);
    if (!g) return false; const r = g.getBoundingClientRect(); const pane = document.querySelector('.gpopup, .split > .panel');
    const right = pane ? pane.getBoundingClientRect().left : innerWidth; return r.x > 0 && r.right < right && r.y > 54 && r.bottom < innerHeight; }, t);
  check(!(await inView('HAVING: фильтр по группам')), 'panned away, HAVING is off screen');
  await page.keyboard.down('Control'); await page.keyboard.press('k'); await page.keyboard.up('Control'); await sleep(250);
  await page.type('.cmd-input', 'HAVING: фильтр'); await sleep(250); await page.keyboard.press('Enter'); await sleep(900);
  check(await inView('HAVING: фильтр по группам'), 'opened from search, the map brings it into view beside its panel');

  // a SELECTED topic's orbit chips (link, delete) must not cover the topic: a tap
  // inside its circle near a chip opened… a removal (2026-11-27: «a course teaches
  // this topic» on a course target, a silent 6 s countdown on any other)
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(600);
  const target = await page.evaluate(() => { const kg = loretile.store.kg(); const c = cljs.core.first(cljs.core.vals(cljs.core.get(kg, cljs.core.keyword('courses'))));
    return cljs.core.first(cljs.core.first(cljs.core.get(c, cljs.core.keyword('targets')))); });
  await page.evaluate((id) => cljs.core.reset_BANG_(loretile.ui.graph_sel, id), target); await sleep(500);
  const hits = await page.evaluate(() => {
    const g = document.querySelector('.orbit-del').closest('.node');
    const r = g.querySelector('circle.node-body').getBoundingClientRect();
    const [x, y, rad] = [r.x + r.width / 2, r.y + r.height / 2, r.width / 2];
    // inside the circle (0.85 of its radius) toward each chip, and a ring of points on its rim
    const points = [[0.85, 35], [0.85, -35], ...Array.from({ length: 12 }, (_, i) => [0.95, i * 30])];
    return points.map(([k, deg]) => { const a = deg * Math.PI / 180; const e = document.elementFromPoint(x + k * rad * Math.cos(a), y + k * rad * Math.sin(a));
      return e && e.closest('.orbit') ? deg : null; }).filter((d) => d !== null);
  });
  check(hits.length === 0, `inside a selected topic, every point is the topic — no orbit chip covers it (${hits.length ? 'chips at ' + hits.join(', ') + '°' : 'none'})`);
  const chips = await page.evaluate(() => ['.orbit-del', '.orbit-link'].map((s) => { const r = document.querySelector(s + ' circle:not(.orbit-hit)').getBoundingClientRect();
    const e = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2); return !!(e && e.closest(s)); }));
  check(chips.every(Boolean), 'and each chip still answers where it is drawn');

  await browser.close();
  console.log(failures ? 'MAP CHECK FAILED' : 'MAP CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

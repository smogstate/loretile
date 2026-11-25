// The whole app on a big graph (wiki/Graph-Store.md): N synthetic topics
// (default 40 000) seeded into IndexedDB, then every main screen timed — boot,
// the map, a topic, the palette, an edit, undo, the graphs view, the assistant.
// Asserts every screen WORKS and answers under a ceiling (regression gates,
// not targets — the targets and the measured state are in wiki/Graph-Store.md).
// Not in `make checks`: it measures, ~10 minutes.
// Usage: serve public/ (after make engines), then: node test/scale40k-check.js [N] [baseUrl]
const puppeteer = require('puppeteer-core');
const { bigGraph, seedBigGraph } = require('./lib/big-graph');
const N = +(process.argv[2] || 40000);
const BASE = (process.argv[3] || 'http://localhost:8291').replace(/\/?$/, '/');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };
const times = {};
async function timed(label, budget, f) {
  const t = Date.now();
  try { await f(); } catch (e) { times[label] = 'TIMEOUT'; check(false, `${label} (${e.message.split('\n')[0]})`); return; }
  times[label] = Date.now() - t;
  check(times[label] <= budget, `${label}: ${times[label]} ms (budget ${budget})`);
}

(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new',
    args: ['--no-sandbox', '--disable-gpu', '--js-flags=--expose-gc'], protocolTimeout: 600000 });
  try {
    const page = await browser.newPage();
    page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
    await page.setViewport({ width: 1280, height: 900 });
    const events = bigGraph(N);
    await seedBigGraph(page, BASE, events);
    console.log(`seeded ${events.length} events (${N} topics)`);

    // first load: the log is read and folded, the projection rebuilt in the
    // background (long, once — after an import or on a new device)
    await timed('first load → the map drawn', 40000, async () => {
      await page.goto(BASE, { waitUntil: 'domcontentloaded' });
      await page.waitForFunction(() => document.querySelectorAll('svg.graph .node').length > 0, { timeout: 120000, polling: 250 });
    });
    await timed('first load → the graph store holds it (in the background)', 300000, async () => {
      await page.waitForFunction(() => window.loretileGraphStore && window.loretileGraphStore.current(), { timeout: 300000, polling: 1000 });
    });
    await sleep(30000);   // the log's one-time rewrite as JSON (20 s after boot)
    // every later boot: the steady state, where everything below is timed
    await timed('boot → the map drawn', 25000, async () => {
      await page.goto(BASE + 'icon.svg'); await page.goto(BASE, { waitUntil: 'domcontentloaded' });
      await page.waitForFunction(() => document.querySelectorAll('svg.graph .node').length > 0, { timeout: 120000, polling: 250 });
    });
    await page.waitForFunction(() => window.loretileGraphStore && window.loretileGraphStore.current(), { timeout: 300000, polling: 500 });
    const drawn = await page.evaluate(() => document.querySelectorAll('svg.graph .node').length);
    check(drawn <= 600, `the map draws a readable part of the graph, not all of it (${drawn} nodes)`);

    await timed('open a topic (its page)', 5000, async () => {
      await page.goto(BASE + '#/c/t7', { waitUntil: 'domcontentloaded' });
      await page.waitForSelector('.panel', { timeout: 30000 });
    });

    await timed('palette finds a topic and jumps there', 6000, async () => {
      await page.goto(BASE + '#/', { waitUntil: 'domcontentloaded' });
      await page.waitForSelector('svg.graph .node', { timeout: 30000 });
      await page.keyboard.down('Control'); await page.keyboard.press('k'); await page.keyboard.up('Control');
      await page.waitForSelector('.cmd-input', { timeout: 30000 });
      await page.type('.cmd-input', `Тема ${N - 1}`);
      // Enter takes the FIRST row: wait until it is the topic (mid-typing, «Тема 3999» lists ten)
      await page.waitForFunction((t) => (document.querySelector('.cmd-list > li') || {}).textContent?.includes(t), { timeout: 30000 }, `Тема ${N - 1} `);
      await page.keyboard.press('Enter');
      await page.waitForSelector('.panel', { timeout: 30000 });
    });
    const note = await page.$eval('.hood-note', (e) => e.textContent).catch(() => '');
    check(note.includes(`Тема ${N - 1}`), `the big map re-centres on the topic jumped to («${note.slice(0, 80)}»)`);

    await timed('an edit (a new topic)', 5000, async () => {
      await page.goto(BASE + '#/', { waitUntil: 'domcontentloaded' });
      await page.waitForFunction(() => [...document.querySelectorAll('.rail .rbtn')].some((e) => /Тема/.test(e.title)), { timeout: 30000 });
      await page.evaluate(() => [...document.querySelectorAll('.rail .rbtn')].find((e) => /Тема/.test(e.title)).click());
      await page.waitForSelector('.modal input', { timeout: 30000 });
      await page.type('.modal input', 'Новая тема масштаба');
      await page.click('.modal button.primary');
      // the form closes once the edit is stored (loretileLog prints the whole log: once, after)
      await page.waitForFunction(() => !document.querySelector('.modal input'), { timeout: 30000, polling: 100 });
    });
    // the projection takes the edit first: loretileLog prints the whole log on
    // the main thread (≈ 3 s at 40k), and a sync whose answer waits behind it
    // measured 3.5 s instead of ≈ 0.6 s (2026-11-25)
    const synced = () => page.waitForFunction(() => window.loretileGraphStore.current(), { timeout: 60000, polling: 100 });
    await synced();
    check(/Новая тема масштаба/.test(await page.evaluate(() => loretileLog().slice(-3000))), 'the new topic is in the log');

    await timed('undo', 5000, async () => {
      await page.keyboard.down('Control'); await page.keyboard.press('z'); await page.keyboard.up('Control');
      await page.waitForFunction(() => /Отменено|отменено/.test((document.querySelector('.snack') || {}).textContent || ''), { timeout: 30000, polling: 100 });
    });
    await synced();
    times.store = await page.evaluate(() => window.loretileGraphStore.lastSync());
    check(/:node-remove/.test(await page.evaluate(() => loretileLog().slice(-800))), 'undo reverses it in the log');

    await timed('the graphs view', 3000, async () => {
      await page.goto(BASE + '#/graphs', { waitUntil: 'domcontentloaded' });
      await page.waitForSelector('.entity-row', { timeout: 30000 });
    });

    await timed('the assistant opens', 3000, async () => {
      await page.goto(BASE + '#/chat', { waitUntil: 'domcontentloaded' });
      await page.waitForSelector('.composer', { timeout: 30000 });
    });

    // retained, not garbage waiting for a collection
    const heap = await page.evaluate(() => { if (window.gc) gc(); return performance.memory ? Math.round(performance.memory.usedJSHeapSize / 1e6) : null; });
    console.log('heap MB', heap, 'times', JSON.stringify(times));
  } catch (e) {
    console.log('CHECK CRASHED', e.message); failures++;
  } finally {
    await browser.close();
  }
  console.log(failures ? `scale40k: ${failures} FAILED` : 'scale40k: OK');
  process.exit(failures ? 1 : 0);
})();

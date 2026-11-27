// The browser graph store's SQLite pool (public/js/graphstore.js) after a
// reload cut its FIRST creation short: the pool holds fewer than its six slot
// files, and a reopened pool is never topped up by itself — the journal then
// found no slot («SAH pool is full»; the release smoke failed on CI with it,
// 2026-11-26). The state is made on purpose: a pool of ONE slot, then the app
// boots on it and must work (graphstore.js reserves the minimum capacity).
// Usage: serve public/ on :8291 (after make engines); node test/sah-pool-check.js
const puppeteer = require('puppeteer-core');
const BASE = (process.argv[2] || 'http://localhost:8291').replace(/\/$/, '');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  let failures = 0;
  const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };
  try {
    const page = await browser.newPage();
    const errors = [];
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
    page.on('pageerror', (e) => errors.push(e.message));
    await page.goto(BASE + '/icon.svg');
    const made = await page.evaluate(() => new Promise((ok) => {
      const src = `import init from '${location.origin}/js/sqlite/index.mjs';
        const s = await init(); const pool = await s.installOpfsSAHPoolVfs({ name: 'loretile', initialCapacity: 1 });
        postMessage(pool.getCapacity());`;
      const w = new Worker(URL.createObjectURL(new Blob([src], { type: 'text/javascript' })), { type: 'module' });
      w.onmessage = (e) => { w.terminate(); ok(e.data); };
      w.onerror = (e) => ok('error ' + e.message);
    }));
    check(made === 1, `a pool cut short at one slot file is made (${made})`);
    await sleep(500);   // the worker's handles go with it
    await page.evaluate(() => {
      localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
      for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
    });
    await page.goto(BASE + '/', { waitUntil: 'networkidle2' });
    // the store opens and takes the open graph (a write: the journal needs its slot)
    const current = await page.waitForFunction(() => window.loretileGraphStore && window.loretileGraphStore.current(), { timeout: 30000, polling: 250 })
      .then(() => true, () => false);
    const pool = errors.filter((e) => /SAH pool|pool is full/.test(e));
    check(current && pool.length === 0, `the app boots on it and its graph store works (${pool[0] ? pool[0].slice(0, 100) : 'no pool error'})`);
  } catch (e) {
    console.log('CHECK CRASHED', e.message); failures++;
  } finally {
    await browser.close();
  }
  console.log(failures ? 'SAH POOL CHECK FAILED' : 'SAH POOL CHECK OK');
  process.exit(failures ? 1 : 0);
})();

// The graph store's projection in the browser (wiki/Graph-Store.md milestone 3):
// SQLite in a Worker held by one tab, kept by the delta fold. A log seeded →
// a rebuild whose rows read back as exactly the fold's graph; the log grown →
// only the new events; reopened → nothing to do; a different log → a rebuild;
// FTS finds a word; a second tab waits and takes over when the first closes;
// the log is stored one record per event. (The search-nodes tool's FTS path runs in
// the tools and chat checks, which now find the projection current.)
// Usage: serve public/ (after `make engines`), then: node test/graphstore-check.js [baseUrl]

const fs = require('fs');
const puppeteer = require('puppeteer-core');
const BASE = process.argv[2] || 'http://localhost:8291';
const CHROME = process.env.CHROME_BIN || '/usr/bin/google-chrome';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

const SQL = fs.readFileSync(__dirname + '/../kg/demo/sql.ru.edn', 'utf8').split('\n').filter((l) => l.trim() && !l.startsWith(';'));
const seed = (lines) => '[' + lines.join(' ') + ']';
const extra = (n) => `{:ev/id "gs-extra-${n}" :ev/ns "demo" :ev/actor "t" :ev/ts ${1795000000000 + n} :ev/type :change :ops [[:node-set "join" {:definition "INNER JOIN склеивает строки; правка ${n} про квазистабильность"}]] :edit :material}`;

async function boot(page, log) {
  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate((log) => {
    localStorage.setItem('loretile-locale', 'ru'); localStorage.setItem('loretile-tour-done', '1');
    localStorage.setItem('loretile-chat-tour-done', '1'); localStorage.setItem('loretile-learn-tour-done', '1');
    if (log) localStorage.setItem('loretile-kg-log', log);
  }, log);
  await page.reload({ waitUntil: 'networkidle0' });
}

async function settled(page, timeout = 60000) {
  await page.waitForFunction(() => window.loretileGraphStore && window.loretileGraphStore.ready() && window.loretileGraphStore.current(),
                             { timeout, polling: 200 });
  return page.evaluate(() => window.loretileGraphStore.lastSync());
}

(async () => {
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--disable-gpu', '--lang=ru'] });
  try {
    const page = await browser.newPage();
    await page.setViewport({ width: 1280, height: 800 });
    const errs = [];
    page.on('console', (m) => m.type() === 'error' && errs.push(m.text()));
    await page.goto(BASE, { waitUntil: 'networkidle0' });
    await page.evaluate(() => { localStorage.clear(); return new Promise((r) => { const q = indexedDB.deleteDatabase('loretile'); q.onsuccess = q.onerror = q.onblocked = r; }); });

    // 1. a seeded log: the projection is rebuilt and reads back as the fold
    await boot(page, seed(SQL));
    let s = await settled(page);
    check(s.mode === 'rebuild' && s.events === SQL.length, `a new log is projected whole (${s.mode}, ${s.events} events, ${s.ms} ms)`);
    let eq = await page.evaluate(() => window.loretileGraphStore.equivalence());
    check(eq.equal, `the rows read back are exactly the fold's graph${eq.equal ? '' : ' — differs in ' + eq.differs.join(', ')}`);

    // 2. FTS over the projection
    const hits = await page.evaluate(() => window.loretileGraphStore.search('JOIN склеивает'));
    check(hits.some((h) => h.id === 'join' || h.parent === 'join'), `FTS finds «join» (${hits.length} hits, first ${hits[0] && hits[0].id})`);

    // 3. reopened: nothing to project
    await page.reload({ waitUntil: 'networkidle0' });
    s = await settled(page);
    check(s.mode === 'current', `a reopen projects nothing (${s.mode})`);

    // 4. the log grew (same prefix): only the new events
    await boot(page, seed([...SQL, extra(1), extra(2)]));
    s = await settled(page);
    check(s.mode === 'append' && s.events === 2, `a grown log projects only its new events (${s.mode}, ${s.events})`);
    eq = await page.evaluate(() => window.loretileGraphStore.equivalence());
    check(eq.equal, `…and still reads back as the fold${eq.equal ? '' : ' — ' + eq.differs.join(', ')}`);
    const edited = await page.evaluate(() => window.loretileGraphStore.search('квазистабильность'));
    check(edited.length > 0 && edited[0].id === 'join', 'FTS sees the edited definition, and not the old row');

    // 5. the log stored one record per event
    const recs = await page.evaluate(() => new Promise((ok) => {
      const r = indexedDB.open('loretile'); r.onsuccess = () => { const d = r.result;
        const q = d.transaction('events').objectStore('events').count(); q.onsuccess = () => ok(q.result); };
    }));
    check(recs === SQL.length + 2, `the log is stored one IndexedDB record per event (${recs})`);

    // 6. a different log (an import replaced it): a rebuild
    await boot(page, seed([...SQL.slice(0, 40), extra(3)]));
    s = await settled(page);
    check(s.mode === 'rebuild', `a replaced log is rebuilt (${s.mode}, ${s.events})`);
    eq = await page.evaluate(() => window.loretileGraphStore.equivalence());
    check(eq.equal, `…and reads back as the fold${eq.equal ? '' : ' — ' + eq.differs.join(', ')}`);

    // 7. a second tab waits for the store; it takes over when the first closes
    const page2 = await browser.newPage();
    await page2.goto(BASE, { waitUntil: 'networkidle0' });
    await sleep(1500);
    check(!(await page2.evaluate(() => window.loretileGraphStore && window.loretileGraphStore.ready())), 'a second tab does not open the store while the first holds it');
    await page.close();
    await page2.waitForFunction(() => window.loretileGraphStore.ready(), { timeout: 30000, polling: 200 });
    check(true, 'the second tab takes the store over when the first closes');
    s = await settled(page2);
    check(s.mode === 'current', `…and finds the rows current (${s.mode})`);

    check(errs.filter((e) => /graph store/i.test(e)).length === 0, `no graph-store errors in the console${errs.length ? ' (' + errs.slice(0, 2).join(' | ') + ')' : ''}`);
  } catch (e) {
    console.log('CHECK CRASHED', e); failures++;
  } finally {
    await browser.close();
  }
  console.log(failures ? `graphstore: ${failures} FAILED` : 'graphstore: OK');
  process.exit(failures ? 1 : 0);
})();

// THE GRAPH AS IT STOOD (wiki/Data-Model.md § Bitemporal): a clock in the map
// tools opens two datetime inputs — «as of» cuts by the changes' own clock
// (:ev/ts), «as known by» by when this log took them in (:ev/recorded). While
// a time is set every reader shows the past, the rail and the pencils are
// absent, a write is refused, and «Now» brings HEAD back. The SQL demo's whole
// history is one morning (2025-09-02, 08:00–10:35 UTC), so the cut lands
// inside it; a late event is given a foreign clock to prove the second axis.
// Usage: serve public/ first, then: node test/asof-check.js [baseUrl]
const puppeteer = require('puppeteer-core');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

// a datetime-local value is read in the browser's LOCAL zone: pin it so the
// cut is the same on every machine
const TZ = 'UTC';

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome',
    headless: 'new', args: ['--no-sandbox', '--disable-gpu'], env: { ...process.env, TZ },
  });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  await page.setViewport({ width: 1280, height: 900 });
  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done'].forEach((k) => localStorage.setItem(k, '1'));
  });
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(300);
  await page.click('.load-demo');
  await sleep(2500);

  const nodes = () => page.evaluate(() => document.querySelectorAll('.node:not(.node-ghost)').length);
  const count = (sel) => page.evaluate((s) => document.querySelectorAll(s).length, sel);
  const setInput = async (sel, v) => {
    await page.evaluate((s, val) => {
      // React listens to the native input event and tracks the value through
      // the prototype setter — a plain el.value = … is invisible to it
      const el = document.querySelector(s);
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, val);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }, sel, v);
    await sleep(600);
  };
  // one demo topic («window») given a foreign clock: made at 09:00, taken in at 10:30
  await page.evaluate(() => {
    // the log is one pr-str'd vector; an event's keys print in insertion order
    const log = loretileLog();
    const re = /:ev\/ts \d+(, :ev\/type :change, :ops \[\[:node-set "window" )/;
    if (!re.test(log)) throw new Error('the window topic was not found in the demo log');
    localStorage.setItem('loretile-kg-log', log.replace(re, ':ev/ts 1756803600000, :ev/recorded 1756809000000$1'));
  });
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(1500);
  const head = await nodes();
  check(head > 10, 'the demo graph at HEAD: ' + head);
  check(await page.$('.rail') !== null, 'the rail is there at HEAD');

  // a cut inside the morning: fewer topics, no rail, no pencils
  await page.click('.nav-asof');
  await sleep(200);
  check(await page.$('.asof-pop') !== null, 'the clock opens its popover');
  await setInput('.asof-valid', '2025-09-02T08:10');
  const past = await nodes();
  check(past > 0 && past < head, `as of 08:10 the graph is smaller: ${past} < ${head}`);
  check(await page.$('.rail') === null, 'the rail is absent in the past');
  check(await page.$('.asof-chip') !== null, 'a chip says which moment is shown');
  check(await count('.app.past') === 1, 'the app wears .past');
  await page.screenshot({ path: 'test/shot-asof.png' });

  // open a topic: the panel shows, its pencils and removals do not
  await page.evaluate(() => { location.hash = '#/c/select'; });
  await sleep(600);
  check(await page.$('.panel') !== null, 'a topic opens in the past');
  check(await page.evaluate(() => [...document.querySelectorAll('.panel .pt-edit, .panel .pt-rm')].every((e) => getComputedStyle(e).display === 'none')),
    'no pencil and no removal in the past panel');

  // a write is refused with the banner
  await page.evaluate(() => { location.hash = '#/'; });
  await sleep(400);
  const refused = await page.evaluate(() => {
    const s = window.loretile && window.loretile.store;
    if (!s) return 'no-dev';
    const r = s.append_BANG_(window.cljs.reader.read_string('{:ev/type :change :ops [[:node-set "q-asof" {:is :question :concept-id "select" :question "?"}]]}'));
    return r ? 'refused' : 'written';
  });
  check(refused !== 'written', 'a write in the past is refused (' + refused + ')');
  await sleep(300);
  const banner = await page.$eval('.error', (e) => e.textContent).catch(() => '');
  if (refused === 'refused') check(/прошлое состояние/.test(banner), 'and the banner says so: ' + banner);
  check(!/q-asof/.test(await page.evaluate(() => loretileLog())), 'nothing landed in the log');

  // the second clock: the late event was MADE at 09:00 but KNOWN only at 10:30
  // (the map remounted behind the topic popup, so the popover is closed again)
  await page.click('.nav-asof'); await sleep(200);
  await setInput('.asof-valid', '2025-09-02T10:00');
  const byMade = await nodes();
  await setInput('.asof-recorded', '2025-09-02T10:00');
  const byKnown = await nodes();
  check(byKnown === byMade - 1, `as known by 10:00 lacks exactly the topic taken in at 10:30: ${byKnown} = ${byMade} - 1`);
  await page.setViewport({ width: 390, height: 844 }); await sleep(400);
  await page.screenshot({ path: 'test/shot-asof-mobile.png' });
  await page.setViewport({ width: 1280, height: 900 }); await sleep(400);

  // Now: HEAD again, rail back
  await page.click('.asof-now');
  await sleep(800);
  check(await nodes() === head, 'Now brings HEAD back');
  check(await page.$('.rail') !== null, 'and the rail');
  check(await page.$('.app.past') === null, 'and the app is no longer .past');

  await browser.close();
  console.log(failures ? `\n${failures} FAILED` : '\nASOF CHECK PASSED');
  process.exit(failures ? 1 : 0);
})();

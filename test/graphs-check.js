// The fourth use case (wiki/Continuous-Learning.md §9): several graphs side
// by side, so a person can bring their own material into a graph of their
// own. Plus publish-and-pin (wiki/Data-Model.md): a learner in flight keeps
// folding at the publication they started under, while the author edits on.
// Usage: serve public/ first, then: node test/graphs-check.js [baseUrl]
const puppeteer = require('puppeteer-core');
const G = require('./lib/graphs');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome',
    headless: 'new', args: ['--no-sandbox', '--disable-gpu'],
  });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  // one handler for every dialog: prompts answer with the graph name,
  // confirms just accept
  page.on('dialog', (d) => d.accept(d.type() === 'prompt' ? 'Мой учебник' : undefined));
  await page.setViewport({ width: 1280, height: 900 });
  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    localStorage.setItem('loretile-tour-done', '1');
    localStorage.setItem('loretile-chat-tour-done', '1');
    localStorage.setItem('loretile-learn-tour-done', '1');
  });
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(300);

  const nodes = () => page.evaluate(() => document.querySelectorAll('.node').length);

  // a fresh install has one graph, and it is the personal one
  const rows0 = await G.listRows(page, BASE);
  check(rows0.length === 1, 'a fresh install has exactly one graph');
  check(/Мой граф/.test(rows0[0]), 'and it is the personal graph, where bring-your-own-material starts');
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(300);

  // load a demo: it lands in a graph of its OWN, the personal one is untouched
  await page.click('.load-demo');
  await sleep(2200);
  const demoNodes = await nodes();
  check(demoNodes > 10, 'the demo graph loaded');
  check((await G.listRows(page, BASE)).length === 2, 'the demo did not land in the personal graph');
  await G.openGraph(page, BASE, /Мой граф/);
  await sleep(600);
  check(await nodes() === 0, 'switching back, the personal graph is still empty');
  check(await page.$('.load-demo') !== null, 'and it offers the empty-map start again');

  // a graph of your own: create, author in it, and it stays separate
  await G.newGraph(page, BASE, 'Мой учебник');
  const rows3 = await G.listRows(page, BASE);
  check(rows3.length === 3, 'a new graph was created');
  check(rows3.some((r) => /^\*Мой учебник/.test(r)), 'and it is the one now open');
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(300);
  await page.evaluate(() => [...document.querySelectorAll('.rail .rbtn')].find((e) => /Тема/.test(e.title)).click());
  await page.waitForSelector('.modal input');
  await page.type('.modal input', 'Моя тема из PDF');
  await page.type('.modal textarea', 'Определение, которое я принял сам.');
  await page.click('.modal button.primary');
  await sleep(400);
  check(await nodes() === 1, 'a topic authored in my own graph');
  const ns = await page.evaluate(() => (loretileLog().match(/:ev\/ns "([^"]+)"/) || [])[1]);
  check(/^g-/.test(ns), `the event carries the real namespace, not the "demo" stub (${ns})`);
  await G.openGraph(page, BASE, /SQL|аналитик/);
  await sleep(700);
  check(await nodes() === demoNodes, 'switching away and the demo graph is intact');
  await G.openGraph(page, BASE, /Мой учебник/);
  await sleep(700);
  check(await nodes() === 1, 'and my own graph kept its topic');

  // deep link #/g/{graph}/c/{node}: opens that graph and the node in it
  const sqlId = await page.evaluate(() => {
    const row = (localStorage.getItem('loretile-graphs').match(/\{[^}]*\}/g) || []).find((e) => /SQL|аналитик/.test(e));
    return ((row || '').match(/:id "([^"]+)"/) || [])[1];
  });
  check(!!sqlId, `the SQL demo has a graph id (${sqlId})`);
  await page.evaluate((g) => { location.hash = '#/g/' + encodeURIComponent(g) + '/c/group-by'; }, sqlId);
  await sleep(900);
  check(await nodes() === demoNodes, 'the deep link switched to its graph');
  check(/GROUP BY/.test(await page.$eval('.panel-head h2', (e) => e.textContent).catch(() => '')),
    'and opened the node');
  check(await page.evaluate(() => location.hash) === '#/c/group-by', 'the address became the plain node route');
  await page.evaluate(() => { location.hash = '#/g/no-such-graph/c/group-by'; });
  await sleep(500);
  check(await nodes() === demoNodes, 'an unknown graph switches nothing');
  check(await page.$eval('.panel', (e) => e.textContent).then((t) => !/GROUP BY/.test(t)).catch(() => true),
    'and never shows a same-slug node of the open graph');
  await page.evaluate(() => { location.hash = '#/'; });
  await G.openGraph(page, BASE, /Мой учебник/);
  await sleep(700);

  // --- publish and pin -------------------------------------------------------
  // start a run on the demo course, then edit that course's graph at HEAD and
  // prove the run in flight does not see the material change
  await page.goto(BASE + '#/learn/sql-analyst', { waitUntil: 'networkidle0' });
  await sleep(600);
  await page.click('.learn .start');
  await sleep(600);
  const firstTopic = await page.$eval('.qcard:not(.done) .qtopic', (e) => e.textContent);
  check(firstTopic.length > 0, `a run is in flight on «${firstTopic}»`);
  const pinned = await page.evaluate(() => {
    const s = localStorage.getItem('loretile-learn-sessions') || '';
    return /:pin "/.test(s) && /:graph "demo-sql"/.test(s);
  });
  check(pinned, 'the run recorded its pin and its graph');

  // the author renames that very topic at HEAD (a MATERIAL change). The
  // author edits ONE graph at a time, so switch to the course's graph first
  // — the learner needed no such switch, which is the two hats working.
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' });
  await sleep(500);
  await G.openGraph(page, BASE, /SQL|аналитик/);
  await sleep(700);
  await page.goto(BASE + '#/c/' + encodeURIComponent('group-by'), { waitUntil: 'networkidle0' });
  await sleep(800);
  check(/GROUP BY/.test(await page.$eval('.panel-head h2', (e) => e.textContent)), 'author opens the topic at HEAD');
  await page.click('.def-row .pt-edit');
  await sleep(250);
  await page.$eval('.def-row .form.edit input', (el) => { el.value = ''; });
  await page.type('.def-row .form.edit input', 'ПЕРЕИМЕНОВАНО АВТОРОМ');
  await page.click('.def-row .form.edit label.check input');
  await page.click('.def-row .form.edit button.primary');
  await sleep(400);
  check(/ПЕРЕИМЕНОВАНО/.test(await page.$eval('.panel-head h2', (e) => e.textContent)),
    'the material rename landed at HEAD');

  // back into the run and walk the diagnostic until the renamed topic is
  // dealt: its card must still carry the title as PUBLISHED, not as edited
  await page.goto(BASE + '#/learn/sql-analyst', { waitUntil: 'networkidle0' });
  await sleep(700);
  const seen = [];
  for (let i = 0; i < 14; i++) {
    const card = await page.$('.qcard:not(.done)');
    if (!card) break;
    seen.push(await page.$eval('.qcard:not(.done) .qtopic', (e) => e.textContent));
    if (await page.$('.qcard:not(.done) .opt')) {
      await page.evaluate(() => document.querySelector('.qcard:not(.done) .opt').click());
    } else {
      await page.type('.qcard:not(.done) input', 'x');
      await page.click('.qcard:not(.done) button.primary');
    }
    await sleep(160);
  }
  const feed = await page.$eval('.feed', (e) => e.innerText);
  check(!/ПЕРЕИМЕНОВАНО/.test(feed), "the run in flight does NOT see the author's material change");
  check(seen.some((tpc) => /GROUP BY/.test(tpc)),
    `the renamed topic is still dealt under its published title (${seen.filter((x) => /GROUP|ПЕРЕ/.test(x)).join(', ') || 'none seen'})`);

  // --- the release cycle must CLOSE: republish, then start over -------------
  // (before the review this was a one-way valve: no republish action existed
  // and «start over» carried the old pin forward, so edits reached nobody)
  await page.goto(BASE + '#/course/sql-analyst', { waitUntil: 'networkidle0' });
  await sleep(800);
  check(await page.$('.republish') !== null, 'the author has a publish-update action');
  check(await page.$eval('.republish', (e) => !e.disabled), 'it is enabled once there are unreleased edits');
  check(/\d/.test(await page.$eval('.republish', (e) => e.textContent)), 'and it says how many');
  await page.click('.republish');
  await sleep(500);
  check(await page.$eval('.republish', (e) => e.disabled), 'after releasing there is nothing left to release');

  // start over: the NEW run must see the released rename
  await page.goto(BASE + '#/learn/sql-analyst', { waitUntil: 'networkidle0' });
  await sleep(700);
  await page.click('.learn-head .restart');
  await sleep(900);
  const seen2 = [];
  for (let i = 0; i < 14; i++) {
    if (!(await page.$('.qcard:not(.done)'))) break;
    seen2.push(await page.$eval('.qcard:not(.done) .qtopic', (e) => e.textContent));
    if (await page.$('.qcard:not(.done) .opt')) {
      await page.evaluate(() => document.querySelector('.qcard:not(.done) .opt').click());
    } else {
      await page.type('.qcard:not(.done) input', 'x');
      await page.click('.qcard:not(.done) button.primary');
    }
    await sleep(160);
  }
  check(seen2.some((tpc) => /ПЕРЕИМЕНОВАНО/.test(tpc)),
    `after republishing, a fresh run DOES see the change (${seen2.filter((x) => /GROUP|ПЕРЕ/.test(x)).join(', ') || 'none'})`);

  // a run whose graph/pin cannot be resolved must offer a way out
  await page.evaluate(() => localStorage.setItem('loretile-learn-sessions',
    '{:current {:id "old" :course "sql-analyst" :round 1 :stage :diagnostic :queue [] :answered [] :gaps [] :learn-at 0 :feed [] :at 1} :archived []}'));
  await page.goto(BASE + '#/learn/sql-analyst', { waitUntil: 'networkidle0' });
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(900);
  const stranded = await page.$eval('.learn', (e) => e.innerText);
  check(!/не найдена/.test(stranded) || await page.$('.learn button.primary') !== null,
    'a run written by an older version is recoverable, not a dead end');


  // --- storage safety: a switch that cannot shelve must NOT proceed ---------
  // (silently losing the open graph used to be the cost of navigating)
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' });
  await sleep(600);
  const beforeNodes = await nodes();
  await page.evaluate(() => {
    // logs live in IndexedDB: a store that refuses every save
    window.__put = IDBObjectStore.prototype.put;
    IDBObjectStore.prototype.put = function () { throw new DOMException('quota', 'QuotaExceededError'); };
  });
  await G.openGraph(page, BASE, /Мой граф/);
  await sleep(700);
  check(await nodes() === beforeNodes, 'a switch that cannot shelve leaves the open graph untouched');
  check(await page.$('.error') !== null, 'and says so instead of failing silently');
  const survived = await page.evaluate(() => (loretileLog() || '').length);
  check(survived > 1000, `the open graph's log is still there (${survived} chars)`);
  await page.evaluate(() => { IDBObjectStore.prototype.put = window.__put; });
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(700);
  check(await nodes() === beforeNodes, 'and it is still intact after a reload');

  // security review 2026-10-03: a log from another device is untrusted, so
  // import validates every event; this calls store internals, only on a dev build
  if (await page.evaluate(() => typeof loretile !== 'undefined' && loretile.store && loretile.store.import_edn_BANG_)) {
  // import validates every event against the schema — a crafted file (the way
  // a hostile course's exercise code would arrive) is refused whole, not
  // partly written. Append a malformed event and a good one; neither lands.
  const before = await nodes();
  const err = await page.evaluate(() => {
    loretile.store.import_edn_BANG_('{:ev/type :concept-accepted :concept {:id "evil" :title 42}}\n{:ev/id "g" :ev/ns "x" :ev/actor "a" :ev/ts 0 :ev/type :concept-accepted :concept {:id "ok" :title "OK" :definition "d" :kind :definition}}', true);
    const e = cljs.core.deref(loretile.store.last_error);
    return e ? String(cljs.core.get(e, cljs.core.keyword('error'))) : 'none';
  });
  await sleep(200);
  check(/bad-import/.test(err), 'an import with a malformed event is refused (' + err + ')');
  check(await nodes() === before, 'and nothing from that file was written, not even its valid events');
  } else console.log('SKIP import validation: release build');

  await browser.close();
  console.log(failures ? 'GRAPHS CHECK FAILED' : 'GRAPHS CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

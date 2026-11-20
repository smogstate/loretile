// The integrity block of review 2026-10-16, as a person meets it:
//  A3  an imported log is replayed through the guard: a file whose graph
//      takes an ontology copy that declares a learning relation id is
//      refused, the graph unchanged
//  A5  the topic editor belongs to its topic: going to another topic with
//      the form open does not carry the first topic's text along
//  A6  a new course never takes an existing course's id
//  A10 the sitting is not swapped while a call runs in it
//  A14 a course whose PREREQUISITE was deprecated cannot be republished
// Usage: serve public/ on :8291; node test/review-check.js
const puppeteer = require('puppeteer-core');
const fs = require('fs');
const os = require('os');
const path = require('path');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'review-'));
const CRAFTED = path.join(dir, 'log.edn');
// a well-formed `change` (the schema takes it): only the guard can refuse it
fs.writeFileSync(CRAFTED, '{:ev/id "x1" :ev/ns "g" :ev/actor "a" :ev/ts 1 :ev/type :change :ops [[:graph-set {:ontology "evil" :ontology-copy {:id "evil" :title "Evil" :node-types [{:id :thing :label "thing"}] :relations [{:id :requires :label "requires" :from #{:thing} :to #{:thing} :ordering? true}]}}]]}\n');

(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  page.on('dialog', (d) => d.accept());
  await page.setViewport({ width: 1280, height: 900 });
  await page.evaluateOnNewDocument(() => {
    const orig = window.fetch.bind(window);
    window.fetch = (url, opts) => {
      if (!String(url).includes('api.anthropic.com')) return orig(url, opts);
      // a slow model: the sitting stays busy for a while
      return new Promise((res) => setTimeout(() => res(new Response(JSON.stringify({ content: [{ type: 'text', text: '{"concepts":[],"note":"ок"}' }] }), { status: 200 })), 2500));
    };
  });
  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');
  const ls = (k) => page.evaluate((k) => localStorage.getItem(k), k);

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
    localStorage.setItem('loretile-llm-config', '{:preset :anthropic :shape :anthropic :url "https://api.anthropic.com/v1/messages" :model "test" :key "sk-test"}');
  });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(400);
  await page.click('.load-demo'); await sleep(1500);
  const log0 = await page.evaluate(() => loretileLog());

  // --- A3 -------------------------------------------------------------------
  // import lives in the Graphs view and makes a graph of its own (2026-11-19)
  await page.goto(BASE + '#/graphs', { waitUntil: 'networkidle0' }); await sleep(300);
  await (await page.$('.import-graph input[type=file]')).uploadFile(CRAFTED); await sleep(800);
  const a3 = await text('div.error');
  check(/не импортирован/.test(a3), 'A3: a log whose ontology copy takes «requires» is refused, in words');
  check(/:refused :bad-profile/.test(a3), 'A3: refused by the guard (an unsound vocabulary), not by the schema');
  check((await page.evaluate(() => loretileLog())) === log0, 'A3: and the open graph is unchanged');
  await page.evaluate(() => { const b = document.querySelector('div.error button'); if (b) b.click(); });

  // --- A5 -------------------------------------------------------------------
  const ids = [...new Set([...log0.matchAll(/\[:node-set "([^"]+)" \{:title "[^"]*", :definition/g)].map((m) => m[1]))];
  await page.goto(BASE + '#/c/' + encodeURIComponent(ids[0]), { waitUntil: 'networkidle0' }); await page.reload({ waitUntil: 'networkidle0' }); await sleep(600);
  await page.click('.def-row .pt-edit'); await sleep(200);
  const firstDef = await page.$eval('.def-row .form.edit textarea', (e) => e.value);
  await page.evaluate((id) => { location.hash = '#/c/' + encodeURIComponent(id); }, ids[1]); await sleep(500);
  const open = await page.$('.def-row .form.edit textarea');
  const shown = open ? await open.evaluate((e) => e.value) : null;
  check(shown === null || shown !== firstDef, 'A5: the next topic does not open with the first topic\'s text');

  // --- A6 -------------------------------------------------------------------
  const publish = async (title) => {
    await page.goto(BASE + '#/course/new', { waitUntil: 'networkidle0' }); await page.reload({ waitUntil: 'networkidle0' }); await sleep(500);
    await page.type('.panel input[placeholder="Название курса"]', title); await sleep(150);
    // tick the first topic the form can publish
    const rows = await page.$$('.crow');
    for (const r of rows) {
      await (await r.$('input[type=checkbox]')).click(); await sleep(150);
      if (await page.$eval('.course-summary button.primary', (b) => !b.disabled).catch(() => false)) break;
      await (await r.$('input[type=checkbox]')).click(); await sleep(100);
    }
    await page.click('.course-summary button.primary'); await sleep(500);
    return page.evaluate(() => decodeURIComponent(location.hash));
  };
  const h1 = await publish('Основы запросов');
  const h2 = await publish('Основы: запросов');
  check(/#\/course\/основы-запросов$/.test(h1) && /#\/course\/основы-запросов-2$/.test(h2),
    'A6: two titles with the same slug get two courses (' + h1 + ', ' + h2 + ')');
  check(((await page.evaluate(() => loretileLog())).match(/\[:node-set "основы-запросов" /g) || []).length === 1, 'A6: the first course was not overwritten');

  // --- A14 ------------------------------------------------------------------
  // a prerequisite of the demo course (a requires-edge target that is not a course target)
  const log1 = await page.evaluate(() => loretileLog());
  const course = log1.match(/\[:node-set "([^"]+)" \{:title "[^"]*", :targets \[([^\]]*\])*\]/);
  const cid = course[1];
  const targets = new Set([...course[0].matchAll(/\["([^"]+)" :/g)].map((m) => m[1]));
  const edges = [...log1.matchAll(/\[:edge-set "([^"]+)" :requires "([^"]+)"/g)].map((m) => [m[1], m[2]]);
  const reach = new Set(targets); let grew = true;
  while (grew) { grew = false; for (const [a, b] of edges) if (reach.has(a) && !reach.has(b)) { reach.add(b); grew = true; } }
  const prereq = [...reach].find((x) => !targets.has(x));
  await page.evaluate((id) => {
    const log = loretileLog();
    localStorage.setItem('loretile-kg-log', log.replace(/\]\s*$/, ` {:ev/id "dep-1" :ev/ns "t" :ev/actor "t" :ev/ts 9999999999 :ev/type :change :ops [[:node-set "${id}" {:status :deprecated}]]}]`));
  }, prereq);
  await page.goto(BASE + '#/course/' + encodeURIComponent(cid), { waitUntil: 'networkidle0' }); await page.reload({ waitUntil: 'networkidle0' }); await sleep(600);
  check(prereq && await page.$eval('.republish', (b) => b.disabled).catch(() => false),
    'A14: a deprecated prerequisite (' + prereq + ') blocks the republish of ' + cid);

  // --- A10 ------------------------------------------------------------------
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' }); await sleep(400);
  await page.type('.chat .composer textarea', 'Что есть в базе?'); await page.keyboard.press('Enter'); await sleep(400);
  await page.click('.chat-head .pt-new'); await sleep(300);
  const keep = await page.$('.keep');
  if (keep) { await keep.click(); await sleep(300); }
  check(/Что есть в базе\?/.test(await text('.chat .msgs')) && /Подождите, пока закончится текущий шаг/.test(await text('.chat .msgs')),
    'A10: «new conversation» while a call runs keeps the sitting and says why');
  await sleep(3000);
  const after = await text('.chat .msgs');
  check(/ок$/.test(after.trim()) && /Что есть в базе\?/.test(after), 'A10: the reply lands in the sitting it was asked in: …' + after.slice(-80));

  // --- A18: two tabs answer, the attempt log keeps both -------------------------
  const tab = async () => {
    const p = await browser.newPage(); await p.setViewport({ width: 1280, height: 900 });
    p.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
    await p.goto(BASE + '#/learn/sql-analyst', { waitUntil: 'networkidle0' }); await sleep(500);
    return p;
  };
  const answerOne = async (p) => {
    await p.bringToFront(); await sleep(200);   // a background tab is throttled
    await p.click('.learn .start'); await sleep(400);
    const live = '.qcard:not(.done)';
    if (await p.$(live + ' .opt')) await p.evaluate((l) => document.querySelector(l + ' .opt').click(), live);
    else if (await p.$(live + ' input')) { await p.type(live + ' input', 'x'); await p.click(live + ' button.primary'); }
    else { await p.type(live + ' textarea.ex-code', 'select 1'); await p.click(live + ' .ex-submit');
           for (let i = 0; i < 300 && !(await p.$('.qcard.done')); i++) await sleep(100); }
    await sleep(500);
  };
  await page.evaluate(() => localStorage.removeItem('loretile-attempts'));
  const t1 = await tab(); const t2 = await tab();   // both loaded before either answers
  await answerOne(t1); await answerOne(t2);
  const stored = await t2.evaluate(() => localStorage.getItem('loretile-attempts') || '');
  check((stored.match(/:item /g) || []).length === 2, 'A18: two tabs each answer once — the stored log keeps both (' + (stored.match(/:item /g) || []).length + ')');
  const runs = await t2.evaluate(() => localStorage.getItem('loretile-learn-sessions') || '');
  check((runs.match(/:id "[0-9a-f-]{36}"/g) || []).length >= 2, 'A18: and the other tab\'s run is shelved, not dropped');

  // --- SQL sequences: a rolled-back insert must not move the next id -----------
  const serials = await page.evaluate(async () => {
    const w = new Worker('js/pgrunner.js', { type: 'module' });
    const ask = (id, sql) => new Promise((res) => {
      const on = ({ data }) => { if (data.id === id && (data.rows || data.error)) { w.removeEventListener('message', on); res(data.rows ? data.rows[0][0] : data.error); } };
      w.addEventListener('message', on);
      w.postMessage({ id, setup: 'create table t (id serial primary key, v int); insert into t (v) values (1);', sql });
    });
    const a = await ask(1, 'insert into t (v) values (2) returning id');
    const b = await ask(2, 'insert into t (v) values (2) returning id');
    w.terminate();
    return [a, b];
  });
  check(serials[0] === 2 && serials[1] === 2, 'a reference and an identical answer get the same serial id (' + serials + ')');

  await browser.close();
  fs.rmSync(dir, { recursive: true, force: true });
  console.log(failures ? 'REVIEW CHECK FAILED' : 'REVIEW CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

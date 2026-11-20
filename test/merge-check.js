// MERGE FILES (loretile.merge): a log of events dropped on S3 under
// merges/{graph-id}/ shows up as a badge on the map and a row in the drawer;
// picking it opens a sitting whose cards are the file's events — new topics
// and links as ghosts on the map, edits and removals marked on their topics —
// and accepting appends the file's events verbatim. A merged file is a
// source of the graph, so it never shows as pending again, reload included.
// Usage: serve public/ first, then: node test/merge-check.js [baseUrl]
const puppeteer = require('puppeteer-core');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

const BUCKET = 'https://merge-bucket.test/b';
const ev = (id, type, rest) => `{:ev/id "${id}" :ev/ns "demo-sql" :ev/actor "workflow-7" :ev/ts ${1700000000000 + +id.slice(2)} :ev/type ${type} ${rest}}`;
const FILE_A = [
  ev('m-1', ':concept-proposed', ':provenance :llm-drafted :concept {:id "merge-topic" :title "Тема из слияния" :definition "Определение из файла"}'),
  ev('m-2', ':edge-proposed', ':edge {:from "merge-topic" :to "select" :type :requires :provenance :llm-drafted :justification "из файла"}'),
  ev('m-3', ':object-added', ':object {:id "o-merge-1" :concept-id "merge-topic" :kind :explanation :level :novice :locale "ru" :origin :authored :text "Материал новой темы"}'),
  ev('m-4', ':object-added', ':object {:id "o-merge-2" :concept-id "where" :kind :explanation :level :novice :locale "ru" :origin :authored :text "Материал к WHERE из файла"}'),
  ev('m-5', ':version-created', ':entity :concept :id "limit" :change :patch :payload {:definition "Новое определение LIMIT из файла"}'),
  ev('m-6', ':concept-discarded', ':id "distinct"'),
  ev('m-7', ':item-added', ':item {:id "i-merge-1" :primary-concept-id "where" :level :novice :kind :single-choice :locale "ru" :spec {:stem "Вопрос из файла" :options ["да" "нет"] :key 0}}'),
  // a duplicate of a topic already in the graph: the guard drops it, no card
  ev('m-8', ':concept-proposed', ':provenance :llm-drafted :concept {:id "select" :title "SELECT again" :definition "dup"}'),
].join('\n');
const FILE_B = ev('m-9', ':version-created', ':entity :concept :id "join" :change :material :payload {:definition "Определение JOIN, которое отклонят"}');
const LISTING = (keys) => `<?xml version="1.0" encoding="UTF-8"?><ListBucketResult><Name>b</Name>${keys.map((k) => `<Contents><Key>${k}</Key></Contents>`).join('')}</ListBucketResult>`;

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome',
    headless: 'new', args: ['--no-sandbox', '--disable-gpu'],
  });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  await page.setViewport({ width: 1280, height: 900 });

  // the bucket: one listing per graph prefix, two files for the SQL demo
  await page.evaluateOnNewDocument((bucket, listing, a, b) => {
    const orig = window.fetch.bind(window);
    window.__listed = [];
    window.fetch = (url, opts) => {
      const u = String(url);
      if (!u.startsWith(bucket)) return orig(url, opts);
      const text = (body, type) => Promise.resolve(new Response(body, { status: 200, headers: { 'Content-Type': type } }));
      if (u.includes('list-type=2')) {
        const prefix = decodeURIComponent(u.split('prefix=')[1]);
        window.__listed.push(prefix);
        return text(prefix === 'merges/demo-sql/' ? listing : listing.replace(/<Contents>.*<\/Contents>/, ''), 'application/xml');
      }
      if (u.endsWith('/merges/demo-sql/run-7.edn')) return text(a, 'application/edn');
      if (u.endsWith('/merges/demo-sql/run-8.edn')) return text(b, 'application/edn');
      return Promise.resolve(new Response('', { status: 404 }));
    };
  }, BUCKET, LISTING(['merges/demo-sql/run-7.edn', 'merges/demo-sql/run-8.edn']), FILE_A, FILE_B);

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate((bucket) => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done'].forEach((k) => localStorage.setItem(k, '1'));
    localStorage.setItem('loretile-merge-url', bucket);
  }, BUCKET);
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(300);

  const nodes = () => page.evaluate(() => document.querySelectorAll('.node:not(.node-ghost)').length);
  const count = (sel) => page.evaluate((s) => document.querySelectorAll(s).length, sel);
  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');
  const log = () => page.evaluate(() => loretileLog() || '');

  // the empty personal graph has nothing waiting
  check(await page.$('.merge-badge') === null, 'no badge on a graph with no merge files');

  // opening the SQL demo re-lists under its prefix and finds two files
  await page.click('.load-demo');
  await sleep(2500);
  const before = await nodes();
  check(before > 10, 'the demo graph loaded');
  check((await page.evaluate(() => window.__listed)).includes('merges/demo-sql/'), 'the bucket was listed under merges/{graph id}/');
  check(/К слиянию: 2/.test(await text('.merge-badge')), 'the map badge counts the waiting files: ' + await text('.merge-badge'));
  await page.screenshot({ path: 'test/shot-merge-badge.png' });

  // the drawer lists them; picking one opens the chat over it
  await page.click('.merge-badge');
  await sleep(350);
  check(await count('.drawer .merge-row') === 2, 'the drawer lists both files');
  check(/run-7\.edn/.test(await text('.drawer .merge-row')), 'a file is named by its key under the prefix');
  await page.click('.drawer .merge-row');
  await sleep(1200);
  check(/Файл «run-7\.edn»: карточек — 6/.test(await text('.msgs')), 'the sitting opens with the file\'s summary: ' + (await text('.msgs')).slice(0, 80));
  check(/Пропущено[^0-9]*1/.test(await text('.msgs')), 'the duplicate topic proposal is reported dropped');
  check(/Разобрано 0 из 6/.test(await text('.cards h3')), 'six cards: topic (its material riding on it), link, material, question, edit, removal — minus the dropped duplicate');
  check(await count('.card-change.chg-edit') === 1 && await count('.card-change.chg-remove') === 1, 'an edit card and a removal card');
  check(/Новое определение LIMIT/.test(await text('.card-change.chg-edit')), 'the edit card shows the new text');
  check(await count('.card-item') === 1, 'the item has a card of its own');

  // the file is visible ON THE MAP before anything is accepted
  check(await count('.node-ghost') === 1, 'the proposed topic is a ghost on the map');
  check(await count('.edge-ghost') === 1, 'the proposed link is a dashed ghost edge');
  check(await count('.node.pending-edit') === 1, 'the topic with an edit waiting wears its mark');
  check(await count('.node.pending-remove') === 1, 'the topic with a removal waiting wears its mark');
  await page.screenshot({ path: 'test/shot-merge-cards.png' });
  await page.setViewport({ width: 390, height: 844 }); await sleep(400);
  await page.screenshot({ path: 'test/shot-merge-mobile.png' });
  await page.setViewport({ width: 1280, height: 900 }); await sleep(400);

  // the file became a source: no longer pending, even though still listed
  check(/К слиянию: 1/.test(await text('.merge-badge')), 'picked up: the badge drops to the other file');

  // accept everything; the link card waits for its topic and slides in after
  for (let i = 0; i < 12 && await page.$('.cards .card button.primary'); i++) {
    await page.click('.cards .card button.primary');
    await sleep(250);
  }
  check(await page.$('.cards .card') === null, 'every card decided');
  const l = await log();
  check(/:ev\/id "m-3"/.test(l) && /:ev\/actor "workflow-7"/.test(l), 'the file\'s events landed verbatim — ids and actor kept');
  check(/Новое определение LIMIT/.test(l), 'the edit applied');
  check(/:ev\/recorded \d{13}/.test(l) && !/:ev\/recorded/.test(l.split(':ev/id "m-1"')[0]), 'file events are stamped with when this log took them in (:ev/recorded); local events are not');
  check(/"o-merge-2"/.test(l) && /"i-merge-1"/.test(l) && /:item-accepted/.test(l), 'material and question landed, the question active');
  check(/:source-added[^}]*"merges\/demo-sql\/run-7\.edn"/.test(l), 'the merge file is a source of the graph');
  await sleep(400);
  check(await nodes() === before, 'one topic added, one removed: the map count is unchanged');
  check(await count('.node-ghost') === 0 && await count('.edge-ghost') === 0, 'no ghosts left');
  check(await count('.node.pending-edit') === 0 && await count('.node.pending-remove') === 0, 'no marks left');

  // discard: the second file's edit is dropped without a trace
  await page.click('.burger'); await sleep(350);
  check(await count('.drawer .merge-row') === 1, 'one file left in the drawer');
  await page.click('.drawer .merge-row');
  await sleep(1000);
  check(await count('.node.pending-edit') === 1, 'the second file marks its topic');
  await page.click('.cards .card button:not(.primary)');
  await sleep(250);
  check(!/которое отклонят/.test(await log()), 'a discarded edit writes nothing');
  check(await count('.node.pending-edit') === 0, 'and its mark is gone');
  check(await page.$('.merge-badge') === null, 'nothing waits any more');

  // a reload re-lists the same two keys and still finds nothing pending
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(800);
  check(await page.$('.merge-badge') === null, 'merged files stay merged after a reload — the source is in the log');

  await browser.close();
  console.log(failures ? `\n${failures} FAILED` : '\nMERGE CHECK PASSED');
  process.exit(failures ? 1 : 0);
})();

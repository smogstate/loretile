// Storage that refuses a write (review 2026-10-16, A1 A2 A7 A11 A15). The
// browser's quota is stood in for by a setItem that throws for the keys
// listed in window.__fail ('idb': an IndexedDB put, where the logs live). What must hold:
//  A1  a refused save is not reported as saved: the banner says storage is
//      full, the topic is not on the map, the form keeps what was typed
//  A15 a graph switch whose write is refused stays on the open graph — the
//      working copy and `current` never name different graphs
//  A2  an attachment's File is never saved into the sittings; a store
//      broken by one (#object[File …]) is mended, the shelf survives
//  A7  a removal still in its ring is dropped when the author switches
//      graphs — it never lands in the graph open when it fires
//  A11 when spending cannot be recorded, the next model call is refused
// Usage: serve public/ on :8291; node test/storage-check.js
const puppeteer = require('puppeteer-core');
const G = require('./lib/graphs');
const fs = require('fs');
const os = require('os');
const path = require('path');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'storage-'));
const DOC = path.join(dir, 'notes.md');
fs.writeFileSync(DOC, '# Notes\nA short note about joins.\n');

(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  page.on('dialog', (d) => d.accept(d.type() === 'prompt' ? 'Второй граф' : undefined));
  await page.setViewport({ width: 1280, height: 900 });
  await page.evaluateOnNewDocument(() => {
    window.__fail = [];
    const set = Storage.prototype.setItem;
    Storage.prototype.setItem = function (k, v) {
      if (window.__fail.includes(k)) throw new DOMException('quota', 'QuotaExceededError');
      return set.call(this, k, v);
    };
    // the logs live in IndexedDB: 'idb' in __fail refuses their saves
    const put = IDBObjectStore.prototype.put;
    IDBObjectStore.prototype.put = function (v, k) {
      if (window.__fail.includes('idb')) throw new DOMException('quota', 'QuotaExceededError');
      return put.call(this, v, k);
    };
    window.__calls = 0;
    const orig = window.fetch.bind(window);
    window.fetch = (url, opts) => {
      if (!String(url).includes('api.anthropic.com')) return orig(url, opts);
      window.__calls++;
      return Promise.resolve(new Response(JSON.stringify({ content: [{ type: 'text', text: '{"concepts":[],"note":"ок"}' }],
        usage: { input_tokens: 100, output_tokens: 20 } }), { status: 200 }));
    };
  });
  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');
  const ls = (k) => page.evaluate((k) => localStorage.getItem(k), k);
  const fail = (keys) => page.evaluate((keys) => { window.__fail = keys; }, keys);
  const nodes = () => page.$$('.node:not(.node-ghost)');

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
    localStorage.setItem('loretile-llm-config', '{:preset :anthropic :shape :anthropic :url "https://api.anthropic.com/v1/messages" :model "test" :key "sk-test"}');
    // a sittings store broken by an attachment's File, with a shelved sitting
    localStorage.setItem('loretile-sessions', '{:current {:msgs [{:who :user :text "текущая"}] :cards [] :doc {:name "a.md" :text "x" :file #object[File [object File]]} :graph nil :decided 0 :onto nil :at "2026-10-15T10:00:00.000Z"} :archived [{:msgs [{:who :user :text "СТАРАЯ БЕСЕДА"}] :cards [] :at "2026-10-14T10:00:00.000Z" :title "СТАРАЯ БЕСЕДА"}]}');
  });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(500);
  await page.click('.load-demo'); await sleep(1500);
  const sqlNodes = (await nodes()).length;
  check(sqlNodes > 3, 'the SQL demo is open (' + sqlNodes + ' topics)');
  // B2 (wiki/Graph-Sync.md): logs live in IndexedDB — a reload brings them back
  // from there, and an old localStorage copy (a seed) has moved over and gone
  await page.evaluate(() => localStorage.setItem('loretile-kg-log:old-shelf', '[{:ev/id "o1" :ev/ns "old-shelf" :ev/actor "t" :ev/ts 1 :ev/type :change :ops [[:graph-set {:title "ОТЛОЖЕННЫЙ"}]]}]'));
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(800);
  check((await nodes()).length === sqlNodes, 'B2: after a reload the open graph comes back from IndexedDB');
  check(await page.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('loretile-kg-log')).length) === 0,
    'B2: no log is left in localStorage');
  check(/ОТЛОЖЕННЫЙ/.test(await page.evaluate(() => loretileLog('old-shelf'))), 'B2: a shelved localStorage log moved into IndexedDB');

  // --- A1 -------------------------------------------------------------------
  await fail(['idb']);
  await page.evaluate(() => [...document.querySelectorAll('.rail .rbtn')].find((e) => /Тема/.test(e.title)).click());
  await sleep(300);
  await page.type('.modal input', 'Тема, которой нет');
  await page.click('.modal button.primary'); await sleep(400);
  check(/переполнено/.test(await text('div.error')), 'A1: the banner says storage is full');
  check((await nodes()).length === sqlNodes, 'A1: the refused topic is not on the map');
  check(await page.$('.modal input') !== null, 'A1: the form stays open with what was typed');
  check(!/Тема, которой нет/.test(await page.evaluate(() => loretileLog())), 'A1: and nothing half-written reached storage');
  await page.keyboard.press('Escape'); await page.evaluate(() => { const b = [...document.querySelectorAll('.modal button')].find((x) => /Отмена/.test(x.textContent)); if (b) b.click(); });
  await sleep(200);

  // --- A15 ------------------------------------------------------------------
  const curBefore = await ls('loretile-graph');
  const rows = await G.listRows(page, BASE);
  await page.evaluate(() => [...document.querySelectorAll('.entity-row')].find((e) => !e.classList.contains('on')).querySelector('.entity-title').click());
  await sleep(500);
  check((await ls('loretile-graph')) === curBefore, 'A15: a refused switch leaves `current` on the open graph');
  check((await nodes()).length === sqlNodes, 'A15: and the open graph still shows (' + rows.length + ' graphs)');
  await fail([]);

  // --- A7: arm a removal, switch graphs inside the ring -----------------------
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await page.reload({ waitUntil: 'networkidle0' }); await sleep(800);
  const sqlLog = await page.evaluate(() => loretileLog());
  // a course target refuses removal at once — arm the first topic that is not one
  for (let i = 0; i < sqlNodes && !(await page.$('.orbit-del.pending')); i++) {
    await (await nodes())[i].click(); await sleep(250);
    if (await page.$('.orbit-del')) { await page.click('.orbit-del'); await sleep(150); }
  }
  check(await page.$('.orbit-del.pending') !== null, 'A7: the removal ring is armed');
  await page.goto(BASE + '#/graphs', { waitUntil: 'networkidle0' }); await sleep(250);
  await page.evaluate(() => [...document.querySelectorAll('.entity-row')].find((e) => !e.classList.contains('on')).querySelector('.entity-title').click());
  await sleep(500);
  const otherBefore = await page.evaluate(() => loretileLog());
  await sleep(6800);
  check((await page.evaluate(() => loretileLog())) === otherBefore && !/concept-discarded/.test(otherBefore), 'A7: the graph open when the ring closed is untouched');
  const sqlShelf = await page.evaluate(() => loretileLog('demo-sql') || '');
  check(sqlShelf.length > 0 && !/concept-discarded/.test(sqlShelf) && sqlShelf.length === sqlLog.length, 'A7: and the removal never landed in the graph it was armed in either');

  // --- A2 -------------------------------------------------------------------
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' }); await sleep(400);
  await (await page.$('.composer input[type=file]')).uploadFile(DOC); await sleep(1500);
  const saved = await ls('loretile-sessions');
  check(!/#object/.test(saved), 'A2: the attachment is saved without the browser File');
  check(/СТАРАЯ БЕСЕДА/.test(saved), 'A2: a store broken by a File was mended — the shelved sitting survived the save');
  check((await ls('loretile-sessions-unreadable')) === null, 'A2: nothing had to be set aside as unreadable');

  // --- A11 ------------------------------------------------------------------
  await page.click('.composer .chat-sendbtn');
  for (let i = 0; i < 40 && (await page.evaluate(() => window.__calls)) < 1; i++) await sleep(150);
  await sleep(600);
  await fail(['loretile-llm-usage']);
  await page.type('.chat .composer textarea', 'Что есть в базе?'); await page.keyboard.press('Enter');
  for (let i = 0; i < 40 && (await page.evaluate(() => window.__calls)) < 2; i++) await sleep(150);
  await sleep(600);
  const calls2 = await page.evaluate(() => window.__calls);
  await page.type('.chat .composer textarea', 'И ещё вопрос?'); await page.keyboard.press('Enter');
  await sleep(1200);
  check((await page.evaluate(() => window.__calls)) === calls2, 'A11: with spending unrecorded, the next call is not sent');
  check(/Расход не удаётся записать/.test(await text('.chat .msgs')), 'A11: and the chat says why');
  await fail([]);
  await page.type('.chat .composer textarea', 'Теперь можно?'); await page.keyboard.press('Enter');
  for (let i = 0; i < 40 && (await page.evaluate(() => window.__calls)) === calls2; i++) await sleep(150);
  check((await page.evaluate(() => window.__calls)) === calls2 + 1, 'A11: once storage takes the record again, calls resume');
  const usage = await ls('loretile-llm-usage');
  check(/:tokens (\d+)/.test(usage) && +usage.match(/:tokens (\d+)/)[1] >= 3 * 40, 'A11: and the unrecorded spending was counted: ' + usage);

  await browser.close();
  fs.rmSync(dir, { recursive: true, force: true });
  console.log(failures ? 'STORAGE CHECK FAILED' : 'STORAGE CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

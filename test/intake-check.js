// A LONG document through the intake: read in parts with a progress bar
// and a stop button, reviewed five topic cards at a time with drafts riding
// inside, links held back until their topics exist; the sitting remembers
// its graph (cards from another graph cannot be accepted), «new
// conversation» asks before shelving undecided cards, and a ghost on the
// map reopens the shelved sitting that proposed it.
// Usage: serve public/, then: node test/intake-check.js [baseUrl]

const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');
const G = require('./lib/graphs');
const BASE = process.argv[2] || 'http://localhost:8291';
const CHROME = process.env.CHROME_BIN || '/usr/bin/google-chrome';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

// 12 page-marked pages × ~2.5k chars ≈ 30k → two parts at the 20k cap.
// Plain text on purpose: chunking does not care where the markers came
// from, and a one-line PDF page clips at its media box
const FIXTURE = path.join(__dirname, 'fixture-long.txt');
fs.writeFileSync(FIXTURE, Array.from({ length: 12 }, (_, i) =>
  `[стр. ${i + 1}] Topic ${i + 1} ` + 'lorem ipsum dolor sit amet '.repeat(92)).join('\n'));

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--disable-gpu'],
  });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  page.on('dialog', (d) => d.accept('Второй граф'));
  await page.setViewport({ width: 1280, height: 800 });

  // the mock answers per call: one topic per page it was sent (with one
  // draft + one question each), and «requires» for every pair it is asked
  await page.evaluateOnNewDocument(() => {
    const orig = window.fetch.bind(window);
    window.__calls = [];
    window.fetch = (url, opts) => {
      if (!String(url).includes('api.anthropic.com')) return orig(url, opts);
      const body = JSON.parse(opts.body);
      const user = body.messages[0].content;
      window.__calls.push(body.system.includes('Разбей') ? 'topics' : 'links');
      let reply;
      if (body.system.includes('Разбей')) {
        const pages = [...user.matchAll(/\[стр\. (\d+)\]/g)].map((m) => m[1]);
        reply = {
          concepts: pages.map((p) => ({ id: 't-' + p, title: 'Тема ' + p, definition: 'Определение ' + p,
            evidence: '[стр. ' + p + '] Topic ' + p })),
          objects: pages.map((p) => ({ concept: 't-' + p, kind: 'explanation', level: 'novice', text: 'Черновик ' + p })),
          questions: pages.map((p) => ({ concept: 't-' + p, question: 'Вопрос ' + p })),
          note: 'ok',
        };
      } else {
        const ids = [...body.system.matchAll(/^- (\S+) \(«/gm)].map((m) => m[1]);
        const second = [...body.system.matchAll(/^ {2}(\S+) \(«/gm)].map((m) => m[1]);
        reply = { edges: ids.slice(0, 3).map((a, i) => ({ from: second[i], to: a, type: 'requires', why: 'порядок' })) };
      }
      return new Promise((res) => setTimeout(() => res(new Response(JSON.stringify({
        content: [{ type: 'text', text: JSON.stringify(reply) }],
      }), { status: 200 })), 700));
    };
  });

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done'].forEach((k) => localStorage.setItem(k, '1'));
    localStorage.setItem('loretile-llm-config',
      '{:preset :anthropic :shape :anthropic :url "https://api.anthropic.com/v1/messages" :model "test" :key "sk-test"}');
  });
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' });
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(300);

  await (await page.$('.composer input[type=file]')).uploadFile(FIXTURE);
  let chip = null;
  for (let i = 0; i < 40 && !chip; i++) { await sleep(250); chip = await page.$('.doc-chip'); }
  check(chip !== null, 'long document attached');
  await page.click('.composer button.primary');
  await G.passTriage(page);   // nothing open yet: the one door asks first
  await sleep(300);

  const prog = await page.$eval('.intake-progress', (e) => e.textContent).catch(() => '');
  check(/стр\. 1–\d+ · ищу темы · 1\/2/.test(prog), 'progress names the pages, the step and the part: ' + prog);
  check(await page.$('.intake-progress .bar') !== null && await page.$('.intake-progress .stop') !== null,
    'progress bar and stop button while reading');
  await page.screenshot({ path: 'test/shot-intake-progress.png' });

  for (let i = 0; i < 40 && await page.$('.intake-progress'); i++) await sleep(250);
  const calls = await page.evaluate(() => window.__calls);
  check(calls.filter((c) => c === 'topics').length === 2, 'the document went in 2 parts, not one call');
  check(/частей: 2/.test(await page.$eval('.msgs', (e) => e.textContent)), 'the run reports its parts');

  check((await page.$$('.card')).length === 5, 'only 5 cards on screen');
  const head = await page.$eval('.cards h3', (e) => e.textContent);
  check(/Разобрано 0 из (\d+)/.test(head) && +head.match(/из (\d+)/)[1] > 12, 'counter covers topics + links: ' + head);
  check(/ещё 7/.test(await page.$eval('.cards', (e) => e.textContent)), 'the rest announced, not shown');
  // the first card (by order — child indexes shift with the pile's header rows)
  check((await page.$$eval('.cards .card', (cs) => cs[0] ? cs[0].querySelectorAll('.draft').length : 0)) === 2, 'draft + question ride on the topic card');
  await page.screenshot({ path: 'test/shot-intake-cards.png' });
  await page.setViewport({ width: 390, height: 844 });
  await sleep(300);
  await page.screenshot({ path: 'test/shot-intake-mobile.png' });
  await page.setViewport({ width: 1280, height: 800 });
  await sleep(300);

  // untick the question on the first topic, accept it
  await page.click('.cards .card .draft:nth-of-type(2) input');
  await sleep(100);
  await page.click('.cards .card button.primary');
  await sleep(250);
  let log = await page.evaluate(() => loretileLog());
  check(/:concept-id "t-1"[^}]*:text "Черновик 1"|:text "Черновик 1"/.test(log), 'ticked draft landed');
  check(!/:question "Вопрос 1"/.test(log), 'unticked question did not');
  check(/Разобрано 1 из/.test(await page.$eval('.cards h3', (e) => e.textContent)), 'counter moved');
  check((await page.$$('.card')).length === 5, 'the next card slid in');

  // «new conversation» with cards: asked, kept for later
  await page.click('.chat-head .pt-new');
  await sleep(150);
  check(await page.$('.confirm-new') !== null, 'new conversation asks about undecided cards');
  await page.click('.confirm-new .keep');
  await sleep(1000);
  check((await page.$$('.card')).length === 0, 'kept for later: the panel starts empty');

  // a ghost on the map reopens that sitting
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' });
  await sleep(1200);
  const ghosts = await page.$$('.node-ghost');
  check(ghosts.length === 11, '11 undecided topics wait on the map as ghosts');
  await ghosts[0].click();
  await sleep(600);
  check((await page.$$('.card')).length === 5, 'a ghost reopens the shelved sitting with its cards');

  // accept every topic → the held-back links become reviewable
  for (let i = 0; i < 11; i++) {
    const topic = await page.$$eval('.cards .card', (cs) => cs.findIndex((c) => /концепт/.test(c.textContent)));
    if (topic < 0) break;
    await (await page.$$('.cards .card'))[topic].$('button.primary').then((b) => b.click());
    await sleep(150);
  }
  const txt = await page.$eval('.cards', (e) => e.textContent).catch(() => '');
  check(/связь/.test(txt) && !/ждут/.test(txt), 'links appear once their topics exist');

  // another graph: the cards stay, but cannot be accepted there
  await G.newGraph(page, BASE, 'Второй граф');
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' });
  await sleep(300);
  check(await page.$('.card-foreign') !== null, 'cards from another graph are flagged');
  const before = (await page.$$('.card')).length;
  check(await page.$eval('.cards .card button.primary', (b) => b.disabled), 'and cannot be accepted here');
  check((await page.$$('.card')).length === before, 'the pile is untouched');
  await page.screenshot({ path: 'test/shot-intake-foreign.png' });
  await page.click('.card-foreign button');
  await sleep(400);
  check(await page.$('.card-foreign') === null, '«open graph» switches back');

  // discard all: the rest is rejected in the log, then shelved
  await page.click('.chat-head .pt-new');
  await sleep(150);
  await page.click('.confirm-new .drop-rest');
  await sleep(400);
  log = await page.evaluate(() => loretileLog());
  check(/\[:edge-remove "/.test(log) && (await page.$$('.card')).length === 0, 'discard all rejects the rest');

  fs.unlinkSync(FIXTURE);
  await browser.close();
  console.log(failures === 0 ? 'INTAKE CHECK OK' : 'INTAKE CHECK FAILED: ' + failures);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(2); });

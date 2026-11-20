// A reply cut off at the provider's output cap is no JSON. The intake must
// read that part again in halves instead of losing it (business dogfood
// 2026-10-15: a 48k-char process document answered 7.5–9.3k tokens per
// part against the 4096 cap — every part failed and the run reported
// «topics proposed: 0»). The mock cuts its reply in half whenever the part
// it was sent holds more than 4 pages, the way a capped API would.
// Also: a link card the guard refuses (it would close a loop) stays in the
// pile with its accept disabled until discarded — before, it stayed
// clickable for ever at the top of the pile.
// Usage: serve public/ first, then: node test/split-check.js [baseUrl]
const puppeteer = require('puppeteer-core');
const G = require('./lib/graphs');
const fs = require('fs');
const path = require('path');
const BASE = process.argv[2] || 'http://localhost:8291';
const CHROME = process.env.CHROME_BIN || '/usr/bin/google-chrome';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

// 12 page-marked pages × ~2.5k chars → two parts of 7 and 5 pages; the
// first is cut off and read again in halves, and the second is cut to the
// same size BEFORE it is sent — one wasted call, not one per part
const FIXTURE = path.join(__dirname, 'fixture-split.txt');
fs.writeFileSync(FIXTURE, Array.from({ length: 12 }, (_, i) =>
  `[стр. ${i + 1}] Topic ${i + 1} ` + 'lorem ipsum dolor sit amet '.repeat(92)).join('\n'));

(async () => {
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  await page.setViewport({ width: 1280, height: 800 });
  await page.evaluateOnNewDocument(() => {
    const orig = window.fetch.bind(window);
    window.__topicCalls = [];
    window.fetch = (url, opts) => {
      if (!String(url).includes('api.anthropic.com')) return orig(url, opts);
      const body = JSON.parse(opts.body);
      const user = body.messages[0].content;
      let text;
      if (body.system.includes('Разбей')) {
        const pages = [...user.matchAll(/\[стр\. (\d+)\]/g)].map((m) => m[1]);
        window.__topicCalls.push(pages.length);
        window.__caps = (window.__caps || []).concat([['topics', body.max_tokens]]);
        text = JSON.stringify({
          concepts: pages.map((p) => ({ id: 't-' + p, title: 'Тема ' + p, definition: 'Определение ' + p, evidence: '[стр. ' + p + '] Topic ' + p })),
          note: 'ok',
        });
        if (pages.length > 4) text = text.slice(0, Math.floor(text.length / 2));   // the output cap
      } else {
        // the first pair asked, in BOTH directions: accepting the second
        // would close a loop, and the guard must refuse it
        window.__caps = (window.__caps || []).concat([['links', body.max_tokens]]);
        const a = (body.system.match(/^- (\S+) \(«/m) || [])[1];
        const b = (body.system.match(/^ {2}(\S+) \(«/m) || [])[1];
        text = JSON.stringify({ edges: a && b && !window.__looped ? [
          { from: a, to: b, type: 'requires', why: 'один' }, { from: b, to: a, type: 'requires', why: 'другой' }] : [] });
        if (a && b) window.__looped = true;
      }
      return Promise.resolve(new Response(JSON.stringify({ content: [{ type: 'text', text }] }), { status: 200 }));
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
  await sleep(500);
  for (let i = 0; i < 80 && await page.$('.intake-progress'); i++) await sleep(250);

  const calls = await page.evaluate(() => window.__topicCalls);
  // halves are cut between pages, so the exact sizes follow the text; what
  // must hold: every cut-off part was read again, nothing was read twice
  const cut = calls.filter((n) => n > 4), whole = calls.filter((n) => n <= 4);
  check(cut.length === 1 && whole.reduce((a, b) => a + b, 0) === 12,
    `one cut-off part, then the rest cut smaller up front; every page read once (pages per call: ${calls})`);
  // the topics call may answer long (the preset's :max-output); links keep the cap
  const caps = await page.evaluate(() => window.__caps);
  check(caps.filter((c) => c[0] === 'topics').every((c) => c[1] === 16000) && caps.filter((c) => c[0] === 'links').every((c) => c[1] === 4096),
    'a topics call asks for the provider\'s long reply (16000), a links call keeps 4096');
  const said = await page.$eval('.msgs', (e) => e.textContent);
  check(/ответ не уместился — читаю эту часть двумя половинами/.test(said), 'the chat says why it reads the part again');
  check(!/Unterminated|JSON/.test(said), 'no parse error reaches the author');
  // a node's props hold no nested map here: [:node-set "t-1" {… :status :proposed …}]
  const nodesWith = (status) => page.evaluate((st) => new Set([...loretileLog().matchAll(new RegExp('\\[:node-set "([^"]+)" \\{[^{}]*:status :' + st + '\\b', 'g'))].map((m) => m[1])).size, status);
  const proposed = await nodesWith('proposed');
  check(proposed === 12, `every page's topic was proposed (${proposed} of 12)`);
  check(new RegExp('частей: ' + whole.length).test(said), `the run reports the parts it actually read (${whole.length})`);

  // the author's one tap per kind: every topic, then every link; each still
  // passes the guard — the second direction of the pair closes a loop and is
  // refused: the banner says why, the card stays with its accept disabled
  // (it could only be refused again), discard removes it
  const allTopics = await page.$('.accept-all-topics');
  check(allTopics !== null && /Принять все темы \(12\)/.test(await allTopics.evaluate((e) => e.textContent)), 'one button accepts all 12 topics');
  await allTopics.click(); await sleep(400);
  const accepted = await nodesWith('accepted');
  check(accepted === 12, `all topics accepted in one tap (${accepted})`);
  const allLinks = await page.$('.accept-all-links');
  check(allLinks !== null, 'then the links, also in one tap');
  if (allLinks) await allLinks.click();
  await sleep(300);
  const banner = await page.$eval('div.error', (e) => e.textContent).catch(() => '');
  check(/замкнула бы цикл/.test(banner) && !/Цикл в requires/.test(banner), 'the refusal names the loop: ' + banner.slice(0, 90));
  const stuck = await page.$$eval('.cards .card', (cs) => cs.map((c) => ({ t: c.textContent, off: c.querySelector('button.primary') && c.querySelector('button.primary').disabled })));
  check(stuck.length === 1 && stuck[0].off, 'the refused card stays, its accept disabled (' + stuck.length + ' left)');
  const drop = await page.$('.cards .card button:not(.primary)');
  if (drop) { await drop.click(); await sleep(250); }
  check((await page.$$('.cards .card')).length === 0, 'discard removes it');
  const log = await page.evaluate(() => loretileLog());
  check((log.match(/\[:edge-set "[^"]+" :requires "[^"]+" \{[^{}]*:status :accepted/g) || []).length === 1, 'one direction accepted, the loop never written');

  await browser.close();
  try { fs.unlinkSync(FIXTURE); } catch (_) {}
  console.log(failures ? `\n${failures} FAILED` : '\nALL PASSED');
  process.exit(failures ? 1 : 0);
})();

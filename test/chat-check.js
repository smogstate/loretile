// M0.3 end-to-end check of the intake chat: mock the LLM at fetch level
// (kintale chat-check pattern) → configure BYOK → paste material → send →
// proposal cards render → accept concept/edge/object/question → events
// land, tile appears on the map, question queued. Screenshots 1280/390.
// Usage: serve public/, then: node test/chat-check.js [baseUrl]

const puppeteer = require('puppeteer-core');
const BASE = process.argv[2] || 'http://localhost:8291';
const CHROME = process.env.CHROME_BIN || '/usr/bin/google-chrome';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

const MOCK_OPS = {
  concepts: [
    { id: 'group-by', title: 'GROUP BY', definition: 'Складывает строки в корзины по ключу' },
    { id: 'having', title: 'HAVING', definition: 'Фильтр уже по корзинам, не по строкам' },
  ],
  edges: [{ from: 'having', to: 'group-by', type: 'requires', why: 'HAVING без GROUP BY не имеет смысла' }],
  objects: [{ concept: 'group-by', kind: 'explanation', level: 'apprentice', text: 'Представьте корзины…' }],
  questions: [{ concept: 'having', question: 'Какие типичные ошибки с HAVING у ваших аналитиков?' }],
  note: 'Разбил материал на 2 концепта.',
};

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--disable-gpu', '--lang=ru'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });

  await page.evaluateOnNewDocument((mock) => {
    const orig = window.fetch.bind(window);
    window.__llmCalls = [];
    window.fetch = (url, opts) => {
      if (String(url).includes('api.anthropic.com')) {
        window.__llmCalls.push(JSON.parse(opts.body));
        return Promise.resolve(new Response(JSON.stringify({
          content: [{ type: 'text', text: JSON.stringify(mock) }],
        }), { status: 200 }));
      }
      return orig(url, opts);
    };
  }, MOCK_OPS);

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => { localStorage.clear(); localStorage.setItem('loretile-locale', 'ru'); localStorage.setItem('loretile-tour-done','1'); localStorage.setItem('loretile-chat-tour-done','1'); localStorage.setItem('loretile-learn-tour-done','1'); });
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' });
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(200);

  check(await page.$('.chat') !== null, 'chat view opens');
  check(/ключ не настроен/.test(await page.$eval('.chat-head', (e) => e.textContent)),
    'unconfigured state visible');

  // configure BYOK (anthropic preset)
  await page.click('.chat-head .pt-model');   // → #/settings
  await sleep(200);
  const inputs = await page.$$('.settings-page input');
  await inputs[2].type('sk-test-123');
  await page.click('.settings-page button.primary');
  await sleep(150);
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' });
  await sleep(200);
  check(/claude/.test(await page.$eval('.chat-head', (e) => e.textContent)), 'model shown after config');
  check(/прикрепите PDF/.test(await page.$eval('.chat .msgs', (e) => e.textContent).catch(() => '')), 'an empty sitting says what it is for');

  // send material
  await page.type('.composer textarea', 'GROUP BY складывает строки… HAVING фильтрует корзины…');
  await page.click('.composer button.primary');
  await sleep(400);
  // one card per topic: drafts ride inside; the link waits for both topics
  check((await page.$$('.card')).length === 2, '2 topic cards (link hidden until its topics exist)');
  check((await page.$$('.card .draft')).length === 2, 'object + question ride inside their topic cards');
  check(/Разобрано 0 из 3/.test(await page.$eval('.cards h3', (e) => e.textContent)), 'progress counts the waiting link');
  check(/ждут/.test(await page.$eval('.cards', (e) => e.textContent)), 'waiting link is announced');

  const sys = await page.evaluate(() => window.__llmCalls[0].system);
  check(/База пуста/.test(sys), 'dedup summary sent (empty base)');
  const typed = await page.evaluate(() => window.__llmCalls[0].messages[0].content);
  check(typed === 'GROUP BY складывает строки… HAVING фильтрует корзины…', 'fast typing loses no characters');
  check((await page.$eval('.composer textarea', (e) => e.value)) === '', 'composer cleared after send');

  // accept all cards (buttons re-queried each round as list shrinks)
  for (let i = 0; i < 5; i++) {
    const btn = await page.$('.cards .card button.primary');
    if (!btn) break;
    await btn.click();
    await sleep(150);
  }
  check((await page.$$('.card')).length === 0, 'all cards resolved');
  const logC = await page.evaluate(() => loretileLog());
  check(/:object-added/.test(logC) && /:question-raised/.test(logC), 'ticked drafts went in with their topics');

  // nodes landed on the map (split view shows map left)
  await sleep(800); // physics settle
  const tiles = await page.$$('.node:not(.node-ghost)');
  check(tiles.length === 2, 'accepted concepts appear as nodes');
  check((await page.$$('.edge-requires')).length === 1, 'accepted edge drawn');

  // second send includes existing concepts in dedup summary
  await page.type('.composer textarea', 'ещё материал');
  await page.click('.composer button.primary');
  await sleep(400);
  const sys2 = await page.evaluate(() => window.__llmCalls[window.__llmCalls.length - 1].system);
  check(/group-by/.test(sys2), 'dedup summary now lists existing concepts');

  await page.screenshot({ path: 'test/shot-chat.png' });
  await page.setViewport({ width: 390, height: 844 });
  await sleep(200);
  await page.screenshot({ path: 'test/shot-chat-mobile.png' });

  await browser.close();
  console.log(failures === 0 ? 'CHAT CHECK OK' : 'CHAT CHECK FAILED: ' + failures);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(2); });

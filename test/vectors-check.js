// Concept vectors end-to-end: a namespace larger than the candidate cap,
// an embeddings endpoint configured (mocked) → the course intake embeds the
// concepts once (batched, cached in localStorage), embeds the goal, and the
// model sees the nearest concepts, not a dump. Second run re-embeds nothing.
// Usage: serve public/, then: node test/vectors-check.js [baseUrl]

const puppeteer = require('puppeteer-core');
const BASE = process.argv[2] || 'http://localhost:8291';
const CHROME = process.env.CHROME_BIN || '/usr/bin/google-chrome';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

// 70 concepts (> cap 60): 69 filler + group-by; one requires edge so 1-hop shows
const ev = (n, body) => `{:ev/id "e${n}" :ev/ns "demo" :ev/actor "t" :ev/ts ${n} ${body}}`;
const events = [];
for (let i = 1; i <= 69; i++) events.push(ev(i, `:ev/type :change :ops [[:node-set "c${i}" {:title "Тема ${i}" :definition "наполнитель ${i}" :status :accepted}]]`));
events.push(ev(70, ':ev/type :change :ops [[:node-set "group-by" {:title "GROUP BY" :definition "складывает строки в корзины" :status :accepted}]]'));
events.push(ev(71, ':ev/type :change :ops [[:edge-set "group-by" :requires "c1" {:provenance :authored :status :accepted}]]'));
const SEED = '[' + events.join(' ') + ']';

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--disable-gpu', '--lang=ru'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });

  await page.evaluateOnNewDocument(() => {
    const orig = window.fetch.bind(window);
    window.__embedCalls = [];
    window.__llmCalls = [];
    window.fetch = (url, opts) => {
      const u = String(url);
      if (u.endsWith('/embeddings')) {
        const body = JSON.parse(opts.body);
        window.__embedCalls.push(body.input.length);
        // anything about grouping → [1,0]; everything else → [0,1]
        const data = body.input.map((t, index) => ({ index, embedding: /group|группиров/i.test(t) ? [1, 0] : [0, 1] }));
        return Promise.resolve(new Response(JSON.stringify({ data }), { status: 200 }));
      }
      if (u.endsWith('/chat/completions')) {
        window.__llmCalls.push(JSON.parse(opts.body));
        return Promise.resolve(new Response(JSON.stringify({
          choices: [{ message: { content: JSON.stringify({ targets: [{ id: 'group-by', level: 'apprentice', why: 'ok' }], unmatched: [], note: 'ok' }) } }],
        }), { status: 200 }));
      }
      return orig(url, opts);
    };
  });

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate((seed) => {
    localStorage.clear();
    localStorage.setItem('loretile-locale', 'ru'); localStorage.setItem('loretile-tour-done','1'); localStorage.setItem('loretile-chat-tour-done','1'); localStorage.setItem('loretile-learn-tour-done','1');
    localStorage.setItem('loretile-kg-log', seed);
    localStorage.setItem('loretile-llm-config',
      '{:preset :custom :shape :openai-compat :url "https://x.test/v1/chat/completions" :model "m" :key "k" :embed-url "https://x.test/v1/embeddings" :embed-model "emb"}');
  }, SEED);
  await page.goto(BASE + '#/course/new', { waitUntil: 'networkidle0' });
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(300);

  await page.type('.course-intake textarea', 'группировка строк');
  await page.click('.course-intake button.primary');
  await sleep(600);

  const calls = await page.evaluate(() => window.__embedCalls);
  check(calls.length === 3 && calls[0] + calls[1] === 70 && calls[2] === 1,
    'concepts embedded once in two batches (50+20), then the goal once [' + calls + ']');
  const sys = await page.evaluate(() => window.__llmCalls[0].messages[0].content);
  const m = /Темы базы \((\d+) из 70\)/.exec(sys);
  check(m && +m[1] <= 60 && +m[1] < 70, 'candidates capped (≤60 of 70), not a dump [' + (m && m[1]) + ']');
  check(/group-by — GROUP BY/.test(sys), 'nearest concept (group-by) in the candidates');
  check(/c1 — Тема 1/.test(sys), 'its requires-neighbour (c1) pulled in by 1-hop');
  check((await page.$$('.course-intake .card')).length === 1, 'target card proposed');
  const cached = await page.evaluate(() => JSON.parse(localStorage.getItem('loretile-vectors') || '{}'));
  check(cached.model === 'emb' && Object.keys(cached.vecs || {}).length === 70, 'vectors cached in localStorage (70, model emb)');

  // reload: nothing re-embedded, only the new goal
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(300);
  await page.type('.course-intake textarea', 'ещё раз');
  await page.click('.course-intake button.primary');
  await sleep(500);
  const calls2 = await page.evaluate(() => window.__embedCalls);
  check(calls2.length === 1 && calls2[0] === 1, 'after reload only the goal is embedded (cache hit) [' + calls2 + ']');

  // two graphs sharing a topic id with DIFFERENT text: the cache is keyed by
  // content, so neither overwrites the other and switching back costs nothing
  // (keyed by id, «c5» re-embedded on every switch)
  const intake = async (goal) => {
    await page.reload({ waitUntil: 'networkidle0' }); await sleep(300);
    await page.type('.course-intake textarea', goal);
    await page.click('.course-intake button.primary');
    await sleep(500);
    return page.evaluate(() => window.__embedCalls);
  };
  const SEED2 = SEED.replace(':definition "наполнитель 5"', ':definition "совсем другой текст"');
  await page.evaluate((x) => localStorage.setItem('loretile-kg-log', x), SEED2);
  const calls3 = await intake('третий');
  check(calls3.join(',') === '1,1', 'a graph whose «c5» says something else embeds only that text, then the goal [' + calls3 + ']');
  await page.evaluate((x) => localStorage.setItem('loretile-kg-log', x), SEED);
  const calls4 = await intake('четвёртый');
  check(calls4.join(',') === '1', 'switching back re-embeds nothing — the first «c5» was never overwritten [' + calls4 + ']');
  const cached2 = await page.evaluate(() => JSON.parse(localStorage.getItem('loretile-vectors') || '{}'));
  check(Object.keys(cached2.vecs || {}).length === 71, 'both texts stay cached (71)');

  await browser.close();
  console.log(failures === 0 ? 'VECTORS CHECK OK' : 'VECTORS CHECK FAILED: ' + failures);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(2); });

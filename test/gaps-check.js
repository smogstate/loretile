// Gaps the competency questions leave (wiki/Profiles.md §CQ, slice c): the
// graph counts them, the assistant's gaps card lists them, and «find the
// answer» asks the model to look — among existing nodes here (the nodes
// have no source) — and proposes the answer as cards; an accepted edge is
// what closes the gap. The model is a scripted mock.
// Usage: serve public/ first, then: node test/gaps-check.js [baseUrl]
const path = require('path');
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
  page.on('dialog', (d) => d.accept(d.type() === 'prompt' ? 'Магазин' : undefined));
  await page.setViewport({ width: 1280, height: 900 });
  await page.evaluateOnNewDocument(() => {
    const orig = window.fetch.bind(window);
    window.__calls = []; window.__script = [];
    window.fetch = (url, opts) => {
      if (String(url).includes('api.anthropic.com')) {
        const body = JSON.parse(opts.body);
        window.__calls.push({ system: body.system, user: body.messages[0].content });
        const reply = window.__script.length > 1 ? window.__script.shift() : window.__script[0];
        const text = typeof reply === 'string' ? reply : JSON.stringify(reply);
        return Promise.resolve(new Response(JSON.stringify({ content: [{ type: 'text', text }] }), { status: 200 }));
      }
      return orig(url, opts);
    };
  });
  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
    localStorage.setItem('loretile-llm-config', '{:preset :anthropic :shape :anthropic :url "https://api.anthropic.com/v1/messages" :model "test" :key "sk-test"}');
  });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(400);

  // a graph from the ontology file, two processes and a role, one question
  if (await G.graphFromOntologyFile(page, path.join(__dirname, '..', 'kg', 'ontologies', 'business.edn'), 'Магазин') === null) { console.log('SKIP: release build — the ontology-file door is internal until slice 3 (wiki/Ontology-Graph.md)'); await browser.close(); process.exit(0); }
  await sleep(800);
  const addNode = async (title, type) => {
    await page.evaluate(() => [...document.querySelectorAll('.rail .rbtn')].find((e) => /Тема/.test(e.title)).click());
    await page.waitForSelector('.modal input');
    await page.select('.modal select.node-type', type);
    await page.type('.modal input', title);
    await page.type('.modal textarea', title + ' — определение.');
    await page.click('.modal button.primary');
    await sleep(400);
  };
  await addNode('Оформление заказа', 'process');
  await addNode('Выставление счёта', 'process');
  await addNode('Бухгалтер', 'role');
  await page.goto(BASE + '#/ontology', { waitUntil: 'networkidle0' }); await sleep(300);
  await page.type('.cq-form input', 'Кто выполняет процесс?');
  await page.select('.cq-form select.cq-rel', 'performs'); await sleep(100);
  await page.select('.cq-form select.cq-dir', 'in');
  await page.select('.cq-form select.cq-from', 'process');
  await page.click('.cq-form .cq-add'); await sleep(400);
  check(/отвечено 0 из 2/.test(await text('.cq-row')), 'two processes, no performer: two gaps');

  // the chat shows the gaps card, computed from the graph
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' }); await sleep(500);
  check(await page.$('.card-gaps') !== null, 'the gaps card is pinned in the chat');
  check(/Узлов без ответа: 2, вопросов: 1/.test(await text('.card-gaps')), `it counts nodes and questions (${await text('.card-gaps')})`);
  check((await page.$$('.card-gaps .gap-row')).length === 2, 'one row per node and question');

  // «find the answer»: the model picks an existing node; the edge is a card
  const ids = await page.evaluate(() => {
    const log = loretileLog();
    return { role: (log.match(/:id "([^"]+)",? :title "Бухгалтер"/) || [])[1], invoice: (log.match(/:id "([^"]+)",? :title "Выставление счёта"/) || [])[1] };
  });
  await page.evaluate((s) => { window.__script = s; window.__calls = []; }, [{ edges: [{ from: ids.role, to: ids.invoice, why: 'счета выставляет бухгалтерия' }], concepts: [], note: 'Нашёл среди существующих узлов.' }]);
  await page.evaluate(() => [...document.querySelectorAll('.card-gaps .gap-row')].find((r) => /Выставление/.test(r.textContent)).querySelector('.gap-find').click());
  await sleep(1500);
  const call = await page.evaluate(() => window.__calls[0]);
  check(/не отвечает на вопрос компетенции/.test(call.system) && /Источника нет/.test(call.system), 'the model is asked for this node and told there is no source');
  check(/Бухгалтер/.test(call.system), 'and given the existing candidates');
  check(await page.$('.card .card-tag') !== null && /связь/.test(await page.$$eval('.card .card-tag', (es) => es.map((e) => e.textContent).join(' '))), 'the answer arrives as a link card');
  check(/выполняет/.test(await page.$$eval('.card', (es) => es.map((e) => e.textContent).join(' '))), 'in the ontology\'s words');
  await page.evaluate(() => [...document.querySelectorAll('.card')].find((c) => /выполняет/.test(c.textContent) && !/Пробелы/.test(c.textContent)).querySelector('button.primary').click());
  await sleep(500);
  check(/:type :performs/.test(await page.evaluate(() => loretileLog())), 'accepting writes the edge');
  check(/Узлов без ответа: 1/.test(await text('.card-gaps')), 'and the graph now counts one gap fewer');

  // the second gap: the model answers with an INFERENCE (stated: false) and a
  // topic that repeats an existing one — both cards say so, and neither is
  // ever accepted in bulk (business dogfood 2026-10-15)
  const checkout = await page.evaluate(() => (loretileLog().match(/:id "([^"]+)",? :title "Оформление заказа"/) || [])[1]);
  await page.evaluate((s) => { window.__script = s; window.__calls = []; }, [{
    edges: [{ from: ids.role, to: checkout, why: 'бухгалтер, видимо, и оформляет', stated: false }],
    concepts: [{ id: 'kassir', type: 'role', title: 'Кассир', definition: 'на кассе', evidence: '', stated: false },
               { id: 'buhgalter-2', type: 'role', title: 'Бухгалтер', definition: 'ведёт учёт', evidence: 'бухгалтер', stated: true }],
    note: 'Отчасти вывод.' }]);
  await page.evaluate(() => [...document.querySelectorAll('.card-gaps .gap-row')].find((r) => /Оформление/.test(r.textContent)).querySelector('.gap-find').click());
  await sleep(1200);
  check(/stated: true — источник говорит это прямо/.test(await page.evaluate(() => window.__calls.map((c) => c.system).join(' '))),
    'the model is asked to say whether the source states it');
  check((await page.$$('.card .tag-inferred')).length === 2, 'the inferred link and the inferred topic each wear «вывод»');
  check(/Похоже на «Бухгалтер»/.test(await page.$$eval('.card .card-dup', (es) => es.map((e) => e.textContent).join(' '))), 'the repeated topic says what it looks like');
  check(await page.$('.accept-all-topics') === null && await page.$('.accept-all-links') === null, 'nothing flagged is offered in bulk');
  check(/^3 — посмотрите сами/.test(await text('.accept-all-held')), `the pile says how many to check by hand (${await text('.accept-all-held')})`);

  // accepted by hand, the inference stays one in the graph: an agent reading
  // it is told the source does not say so (2026-10-21)
  await page.evaluate(() => [...document.querySelectorAll('.card')].find((c) => c.querySelector('.tag-inferred') && /выполняет/.test(c.textContent)).querySelector('button.primary').click());
  await sleep(500);
  const log = await page.evaluate(() => loretileLog());
  const accepted = [...log.matchAll(/:edge-accepted, :edge (\{[^}]*\})/g)].map((m) => m[1]);
  check(accepted.length === 2 && accepted.filter((x) => /:inferred\? true/.test(x)).length === 1 && accepted.some((x) => x.includes(checkout) && /:inferred\? true/.test(x)),
    `the accepted inference is stored as one, the stated link is not (${accepted.length} links)`);

  await page.screenshot({ path: 'test/shot-gaps.png' });
  await browser.close();
  console.log(failures ? `\n${failures} FAILED` : '\nALL PASSED');
  process.exit(failures ? 1 : 0);
})();

// Adversarial competency questions (wiki/Profiles.md §CQ, slice d): the
// ontology page shows the pitfalls the fold sees on its own; one tap asks
// the model for the questions the vocabulary cannot answer yet, which come
// back as cards — accepting one that needs a new relation grows the
// ontology additively through the guard and records the question. Model
// mocked.
// Usage: serve public/ first, then: node test/probe-check.js [baseUrl]
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
        const reply = window.__script[0];
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

  // a graph from the ontology file with one lonely process
  if (await G.graphFromOntologyFile(page, path.join(__dirname, '..', 'kg', 'ontologies', 'business.edn'), 'Магазин') === null) { console.log('SKIP: release build — the ontology-file door is internal until slice 3 (wiki/Ontology-Graph.md)'); await browser.close(); process.exit(0); }
  await sleep(800);
  await page.evaluate(() => [...document.querySelectorAll('.rail .rbtn')].find((e) => /Тема/.test(e.title)).click());
  await page.waitForSelector('.modal input');
  await page.select('.modal select.node-type', 'process');
  await page.type('.modal input', 'Оформление заказа');
  await page.type('.modal textarea', 'Оформление заказа — определение.');
  await page.click('.modal button.primary'); await sleep(400);

  // the fold's own checks on the ontology page
  await page.goto(BASE + '#/ontology', { waitUntil: 'networkidle0' }); await sleep(300);
  const pits = await page.$$eval('.pit-row', (es) => es.map((e) => e.textContent));
  check(pits.some((p) => /Узел без единой связи/.test(p) && /Оформление заказа/.test(p)), 'the lonely node is a pitfall');
  check(pits.some((p) => /Тип «роль» не используется/.test(p)), 'an unused type is a pitfall');
  check(!pits.some((p) => /Связь «/.test(p)), 'relations are not flagged while the graph has no edges at all (the lonely node says it)');

  // one tap: the model is asked for the questions the vocabulary cannot answer
  await page.evaluate((s) => { window.__script = s; }, [{
    cqs: [
      { question: 'Какая система обслуживает процесс X?', relation: { id: 'runs-in', label: 'идёт в системе', inverse: 'обслуживает', from: 'process', to: 'system' }, asks_from: 'process', new: false },
      { question: 'Какой срок у процесса X?', relation: { id: 'due-by', label: 'срок', inverse: 'срок для', from: 'process', to: 'rule' }, asks_from: 'process', new: true },
    ],
    pitfalls: [{ what: 'Тип «решение» дублирует «правило»', fix: 'оставить правило' }],
    note: 'Два вопроса и одна оговорка.',
  }]);
  await page.click('.probe-ontology'); await sleep(1500);
  check(/#\/chat/.test(await page.evaluate(() => location.hash)), 'the chat opens');
  const call = await page.evaluate(() => window.__calls[0]);
  check(/проверяешь онтологию/.test(call.system) && /Оформление заказа/.test(call.system) && /isolated/.test(call.system),
    'the model gets the vocabulary, the nodes and the fold\'s pitfalls');
  check((await page.$$('.card-cq-draft')).length === 2, 'two question cards');
  check(/новая связь/.test(await page.$$eval('.card-cq-draft', (es) => es.map((e) => e.textContent).join(' '))), 'the one needing a new relation says so');
  check(/дублирует «правило» → оставить правило/.test(await text('.chat .msgs')), 'the pitfalls are reported in prose, nothing changed by itself');

  // accept both: the existing relation's question is recorded; the new relation joins first
  for (let i = 0; i < 2; i++) { await page.click('.card-cq-draft .cq-draft-accept'); await sleep(300); }
  const log = await page.evaluate(() => loretileLog());
  const logs = await page.evaluate(() => loretileLogs());
  check((log.match(/:is :cq/g) || []).length === 2, 'two questions recorded');
  // the vocabulary lives in the ontology graph (an edge there), the bound graph takes the copy
  check(/:edge-set "process" :due-by "rule"/.test(logs) && /:id :due-by/.test(log), 'the new relation joined the ontology');
  check((logs.match(/:edge-set "process" :runs-in "system"/g) || []).length === 1, 'the existing one was not added again');
  check(await page.$('.error') === null, 'no guard error');
  await page.goto(BASE + '#/ontology', { waitUntil: 'networkidle0' }); await sleep(300);
  const onto = await text('.ontology-page');
  check(/due-by/.test(onto) && /срок/.test(onto), 'the ontology page shows the new relation');
  check((await page.$$('.cq-row')).length === 2, 'and both questions, checked over the graph');

  await page.screenshot({ path: 'test/shot-probe.png' });
  await browser.close();
  console.log(failures ? `\n${failures} FAILED` : '\nALL PASSED');
  process.exit(failures ? 1 : 0);
})();

// The ontology interview (wiki/Profiles.md §Competency questions): the
// assistant asks, the author answers, competency questions arrive as cards,
// and the accepted ones become the graph's ontology — every relation born
// justified by a question; the fold then checks the questions over the
// graph. The model is a scripted mock.
// Usage: serve public/ first, then: node test/interview-check.js [baseUrl]
const puppeteer = require('puppeteer-core');
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
  page.on('dialog', (d) => d.accept(d.type() === 'prompt' ? 'Как работает магазин' : undefined));
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
  const msgs = () => page.$$eval('.chat .msgs .msg', (els) => els.map((e) => e.textContent));
  const say = async (script, q) => {
    await page.evaluate((s) => { window.__script = s; window.__calls = []; }, script);
    await page.type('.chat .composer textarea', q);
    await page.keyboard.press('Enter');
    await sleep(1200);
  };

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
    localStorage.setItem('loretile-llm-config', '{:preset :anthropic :shape :anthropic :url "https://api.anthropic.com/v1/messages" :model "test" :key "sk-test"}');
  });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(400);

  // a new graph with the assistant's interview. Its drawer door is gone (2026-11-19);
  // slice 3 of wiki/Ontology-Graph.md folds the interview into the assistant's one
  // door — until then the store and chat path is driven directly (dev build only)
  const started = await page.evaluate(() => {
    if (typeof loretile === 'undefined' || !loretile.chat || !loretile.chat.start_ontology_interview_BANG_) return null;
    const id = loretile.store.new_graph_BANG_('Как работает магазин');
    loretile.chat.start_ontology_interview_BANG_(id);
    location.hash = '#/chat';
    return true;
  });
  if (started === null) { console.log('SKIP: release build — the interview door is internal until slice 3'); await browser.close(); process.exit(0); }
  await sleep(800);
  check(/#\/chat/.test(await page.evaluate(() => location.hash)), 'the interview opens in the chat');
  check(/режим онтологии/.test(await text('.chat-head')), 'the composer wears the ontology badge');
  check(/Для чего он/.test((await msgs()).join(' ')), 'the assistant opens with the first question');

  // the model asks one question first (participatory prompting), then proposes
  await say([{ ask: 'Какие 2–3 ситуации должен покрывать граф?' }], 'Для агента-программиста, чтобы он знал, как работает магазин');
  const m1 = await msgs();
  check(/Какие 2–3 ситуации/.test(m1[m1.length - 1]), 'the assistant asks the next question');
  check(await page.$('.card-cq') === null, 'no cards yet');
  const call = await page.evaluate(() => window.__calls[0]);
  check(/вопросы компетенции/.test(call.system) && /user: Для агента/.test(call.user), 'the model gets the method and the transcript');

  await say([{
    title: 'Как работает магазин',
    story: 'Агент-программист читает граф, чтобы понять процессы магазина, их исполнителей и правила.',
    node_types: [{ id: 'process', label: 'процесс' }, { id: 'role', label: 'роль' }, { id: 'rule', label: 'правило' }],
    cqs: [
      { question: 'Кто выполняет процесс X?', relation: { id: 'performs', label: 'выполняет', inverse: 'выполняется', from: 'role', to: 'process' }, asks_from: 'process' },
      { question: 'Какие правила регулируют процесс X?', relation: { id: 'governed-by', label: 'регулируется', inverse: 'регулирует', from: 'process', to: 'rule' }, asks_from: 'process' },
      { question: 'Что запускается после процесса X?', relation: { id: 'triggers', label: 'запускает', inverse: 'запускается после', from: 'process', to: 'process' }, asks_from: 'process' },
      { question: 'Сколько стоит процесс X?', relation: { id: 'costs', label: 'стоит', inverse: 'цена', from: 'process', to: 'rule' }, asks_from: 'process' },
    ],
    ordering: 'triggers',
    note: 'Четыре вопроса на старт.',
  }], 'Онбординг, поддержка, инциденты');
  check((await page.$$('.card-cq')).length === 4, 'four competency-question cards');
  check(await page.$('.card-profile-next') !== null, 'the ontology step is pinned');
  check(/выполняет \/ выполняется · роль → процесс · спрашивают о: процесс/.test(await text('.card-cq')),
    `a card shows its relation, its types and what it asks about (${await text('.card-cq')})`);
  check(await page.$('.compose-profile') === null, 'nothing to write before a question is accepted');

  // accept three, discard the fourth: the ontology is built from the accepted ones
  for (let i = 0; i < 3; i++) { await page.click('.card-cq .cq-accept'); await sleep(200); }
  await page.evaluate(() => [...document.querySelectorAll('.card-cq .row button')].find((b) => /Отклонить/.test(b.textContent)).click());
  await sleep(200);
  check(await page.$('.card-cq') === null, 'all four decided');
  check(/Принято вопросов: 3/.test(await text('.card-profile-next')), 'the step counts the accepted ones');
  await page.click('.compose-profile'); await sleep(600);
  const log = await page.evaluate(() => loretileLog());
  check(/:profile-set/.test(log), 'the ontology is written');
  check((log.match(/:cq-raised/g) || []).length === 3, 'with one competency question per accepted card');
  check(!/:costs/.test(log), 'the discarded question left no relation behind');
  check(/:inverse\? true/.test(log), 'a question asked from the receiving end is checked from there');
  check(/Онтология записана: типов узлов — 3, связей — 3/.test((await msgs()).join(' ')), 'the sitting reports what it wrote');
  check(!/режим онтологии/.test(await text('.chat-head')), 'and returns to the author mode');

  // the ontology page shows what the interview produced; nothing is answered yet
  await page.goto(BASE + '#/ontology', { waitUntil: 'networkidle0' }); await sleep(300);
  const onto = await text('.ontology-page');
  check(/процесс/.test(onto) && /performs/.test(onto) && /triggers/.test(onto), 'the ontology page lists the vocabulary');
  check((await page.$$('.cq-row')).length === 3, 'and the three questions');
  check(!/ни один вопрос не требует/.test(onto), 'every relation is justified by a question');

  await page.screenshot({ path: 'test/shot-interview.png' });
  await browser.close();
  console.log(failures ? `\n${failures} FAILED` : '\nALL PASSED');
  process.exit(failures ? 1 : 0);
})();

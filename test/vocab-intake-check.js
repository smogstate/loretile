// Documents into an ontology graph (wiki/Ontology-Graph.md slice 3): with a
// graph on the types ontology open, a dropped document becomes TYPE and
// RELATION cards (the model mocked); accepting writes the property-graph ops
// — a relation brings its two types along, so the order of accepts does not
// matter; reserved ids never become cards; a Turtle/edn file is read by code
// (no model call); an ontology file dropped into a data graph is refused in words.
// Usage: serve public/ first, then: node test/vocab-intake-check.js [baseUrl]
const fs = require('fs');
const os = require('os');
const path = require('path');
const puppeteer = require('puppeteer-core');
const G = require('./lib/graphs');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vocab-'));
const DOC = path.join(dir, 'процессы.md');
fs.writeFileSync(DOC, '# Как работает магазин\n\nБухгалтер выполняет оплату счетов. Оплата идёт в 1С.\n');

const REPLY = {
  types: [{ id: 'process', label: 'процесс', description: 'Шаг работы.', evidence: 'оплату счетов' },
          { id: 'role', label: 'роль', description: 'Кто делает.', evidence: 'Бухгалтер' },
          { id: 'system', label: 'система', description: 'Программа.', evidence: '1С' }],
  relations: [{ id: 'performs', label: 'выполняет', inverse: 'выполняется', from: 'role', to: 'process', evidence: 'Бухгалтер выполняет' },
              { id: 'runs-in', label: 'идёт в', from: 'process', to: 'system', evidence: 'Оплата идёт в 1С' },
              { id: 'requires', label: 'требует', from: 'process', to: 'process' }],
  note: 'Описание процессов магазина.',
};

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome',
    headless: 'new', args: ['--no-sandbox', '--disable-gpu'],
  });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  page.on('dialog', (d) => d.accept());
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
  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
    localStorage.setItem('loretile-llm-config', '{:preset :anthropic :shape :anthropic :url "https://api.anthropic.com/v1/messages" :model "test" :key "sk-test"}');
  });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(300);

  const log = () => page.evaluate(() => loretileLog() || '');
  const msgs = () => page.$$eval('.chat .msgs .msg', (els) => els.map((e) => e.textContent).join(' | '));
  const drop = async (file, script) => {
    await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' }); await sleep(400);
    await page.evaluate((s) => { window.__script = s; window.__calls = []; }, script);
    await (await page.$('.composer input[type=file]')).uploadFile(file); await sleep(700);
    await page.click('.composer .chat-sendbtn'); await sleep(1200);
  };

  // an ontology graph, then a document dropped into its assistant
  await G.newGraph(page, BASE, 'Магазин: онтология', 'ontology');
  await drop(DOC, [REPLY]);
  const calls = await page.evaluate(() => window.__calls);
  check(calls.length === 1 && /ВИДЫ вещей/.test(calls[0].system) && /Бухгалтер выполняет/.test(calls[0].user),
    'the model is asked for kinds of things and how they relate, with the document');
  check((await page.$$('.card-type')).length === 3 && (await page.$$('.card-relation')).length === 2,
    'three type cards and two relation cards');
  check(!(await page.$$eval('.card-relation', (cs) => cs.some((c) => /требует/.test(c.textContent)))), 'a reserved id never becomes a card');
  check(/Предложено типов: 3, связей: 2/.test(await msgs()), 'the assistant says what it proposed');

  // a relation first: it brings its two types along
  await page.evaluate(() => [...document.querySelectorAll('.card-relation')].find((c) => /выполняет/.test(c.textContent)).querySelector('.relation-accept').click());
  await sleep(500);
  const l1 = await log();
  check(/:node-set "role" \{:title "роль"\}/.test(l1) && /:node-set "process" \{:title "процесс"\}/.test(l1)
        && /:edge-set "role" :performs "process" \{:label "выполняет", :inverse "выполняется"\}/.test(l1),
    'accepting a relation writes its missing types and the edge with its words, in one change');
  // the rest in one tap each
  await page.evaluate(() => { const b = document.querySelector('.accept-all-topics'); if (b) b.click(); });
  await sleep(500);
  await page.evaluate(() => { [...document.querySelectorAll('.relation-accept')].forEach((b) => b.click()); });
  await sleep(500);
  check(/:definition "Шаг работы\."/.test(await log()), 'a type card accepted later fills in its words (merge)');
  await page.goto(BASE + '#/ontology', { waitUntil: 'networkidle0' }); await sleep(300);
  const page1 = await page.$eval('.ontology-page', (e) => e.textContent);
  check(/процесс/.test(page1) && /роль/.test(page1) && /система/.test(page1) && /выполняет/.test(page1) && /идёт в/.test(page1),
    'the ontology now has the three types and two relations');

  // an ontology FILE is read by code: no model call
  await drop(path.join(__dirname, '..', 'kg', 'ontologies', 'business.edn'), [REPLY]);
  check((await page.evaluate(() => window.__calls.length)) === 0, 'an edn ontology file is read without the model');
  check(/Предложено типов: 7, связей: 15/.test(await msgs()), 'and becomes type and relation cards (7 types, 15 relations)');
  check(await page.$('.card-type .card-dup') !== null, 'a type already there says accepting updates it');

  // THE ONE DOOR: nothing open (an empty graph on no ontology) — the document is asked about first
  const fresh = async (title) => {
    await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' }); await sleep(300);
    if (await page.$('.chat-head .pt-new')) { await page.click('.chat-head .pt-new'); await sleep(200);
      await page.evaluate(() => { const b = document.querySelector('.keep'); if (b) b.click(); }); await sleep(200); }
    await G.newGraph(page, BASE, title);
  };
  await fresh('Пустой');
  await drop(DOC, [{ concepts: [{ id: 'buh', title: 'Бухгалтер', type: 'role', definition: 'Ведёт счета.' }] }]);
  check(await page.$('.card-triage') !== null && (await page.evaluate(() => window.__calls.length)) === 0,
    'a document dropped while nothing is open is asked about first — no model call yet');
  check(await page.$eval('.card-triage .triage-content', (e) => e.checked), 'its guess for a text document: graph content');
  await page.click('.card-triage .triage-content');
  const oid = (await page.evaluate(() => localStorage.getItem('loretile-graphs')).then((r) => r.match(/:id "([^"]+)", :title "Магазин: онтология"/)) || [])[1];
  await page.select('.card-triage .triage-which', oid);
  await page.click('.card-triage .triage-go'); await sleep(1500);
  check(new RegExp(':title "Пустой", :ontology "' + oid + '"').test(await page.evaluate(() => localStorage.getItem('loretile-graphs'))),
    'content on a chosen ontology: the graph takes that ontology');
  check((await page.evaluate(() => window.__calls.length)) >= 1 && await page.$('.card') !== null && /Бухгалтер/.test(await page.$eval('.cards', (e) => e.textContent).catch(() => '')),
    'and the document is read as its content — topic cards typed by the ontology');

  // an ontology file dropped into a data graph that has its ontology is refused in words
  await drop(path.join(__dirname, '..', 'kg', 'ontologies', 'business.edn'), [REPLY]);
  check(/файл онтологии/.test(await msgs()) && await page.$('.card-triage') === null,
    'an ontology file in a data graph that has its ontology is refused in words');

  // SLICE 4: a kind the ontology lacks — its nodes wait, a card offers to add the type to the ontology graph
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' }); await sleep(300);
  await page.click('.chat-head .pt-new'); await sleep(200);
  await page.evaluate(() => { const b = document.querySelector('.keep'); if (b) b.click(); }); await sleep(200);
  await drop(DOC, [{ concepts: [{ id: 'vat', title: 'НДС на услуги', type: 'rule', type_label: 'правило', definition: 'Ставка 20 % на каждую Оплата поставщику.' },
                                { id: 'pay', title: 'Оплата поставщику', type: 'process', definition: 'Перевод денег.' }] },
                   { edges: [{ from: 'pay', to: 'vat', type: 'governed-by', label: 'регулируется', inverse: 'регулирует', why: 'ставка на оплату' }] }]);
  check(/подходит НИ К ОДНОМУ/.test((await page.evaluate(() => window.__calls))[0].system), 'with its ontology on this device, the intake may name a missing kind');
  const pile = () => page.$eval('.cards', (e) => e.textContent).catch(() => '');
  check(await page.$('.card-type-draft') !== null && /правило/.test(await pile()) && /Оплата поставщику/.test(await pile()) && !/НДС на услуги<|Ставка 20/.test(await pile()),
    'a type-draft card for «правило»; the fitting node is a card, the waiting one is held back');
  check(/Ждут этого типа: 1/.test(await pile()), 'the draft says how many nodes wait for it');
  await page.click('.card-type-draft .type-draft-accept'); await sleep(600);
  const ologMatch = await page.evaluate((id) => loretileLog(id) || '', oid);
  check(/:node-set "rule" \{:title "правило"\}/.test(ologMatch), 'accepting writes the type into the ONTOLOGY graph');
  check(/Ставка 20/.test(await pile()), 'its copy comes back here and the waiting node is now a card');
  check(/:concept-proposed[^}]*:id "vat"/.test(await log()), 'the node that waited is proposed now (a ghost on the map)');
  const pairCall = (await page.evaluate(() => window.__calls)).find((c) => /НДС на услуги/.test(c.user) && /Оплата поставщику/.test(c.user) && c.user !== (window.__calls[0] || {}).user);
  check(!!pairCall || (await page.evaluate(() => window.__calls.length)) >= 2, 'and the pair search runs over it — its links are asked');
  check(await page.$('.card-relation-draft') !== null && /регулируется/.test(await pile()),
    'a link it needs that the ontology lacks comes as a relation draft');
  await page.evaluate(() => [...document.querySelectorAll('.card')].find((c) => /НДС на услуги/.test(c.textContent)).querySelector('button.primary').click());
  await sleep(500);
  check(/:id "vat"[^}]*:kind :rule/.test(await log()), 'and is accepted with the new type');

  // SLICE 4 — a relation the ontology lacks: the link waits, a card adds the relation to the ontology graph
  await page.click('.chat-head .pt-new'); await sleep(200);
  await page.evaluate(() => { const b = document.querySelector('.keep'); if (b) b.click(); }); await sleep(200);
  const DOC2 = path.join(dir, 'налоги.md');
  fs.writeFileSync(DOC2, '# Налоги\n\nГлавный бухгалтер проверяет налог на прибыль.\n');
  await drop(DOC2, [{ concepts: [{ id: 'acc2', title: 'Главный бухгалтер', type: 'role', definition: 'Ведёт учёт.' },
                                 { id: 'tax2', title: 'Налог на прибыль', type: 'rule', definition: 'Его проверяет Главный бухгалтер.' }] },
                    { edges: [{ from: 'acc2', to: 'tax2', type: 'checks', label: 'проверяет', inverse: 'проверяется', why: 'так сказано' }] }]);
  const sys2 = (await page.evaluate(() => window.__calls)).map((c) => c.system).join('\n');
  check(/НЕТ в списке/.test(sys2), 'the link step may name a relation the ontology lacks');
  check(/ни одна связь из списка эти типы не соединяет/.test(sys2) && !/вероятно (acc2|tax2)/.test(sys2),
    'and the hint for a pair no relation fits names none — it proposed «role requires rule» (real-model run 2026-11-20)');
  check(await page.$('.card-relation-draft') !== null && /проверяет/.test(await pile()), 'a relation-draft card for «проверяет»');
  await page.click('.card-relation-draft .relation-draft-accept'); await sleep(600);
  check(/:edge-set "role" :checks "rule" \{:label "проверяет", :inverse "проверяется"\}/.test(await page.evaluate((id) => loretileLog(id) || '', oid)),
    'accepting writes the relation into the ONTOLOGY graph, between the two types');
  await page.evaluate(() => { const b = document.querySelector('.accept-all-topics'); if (b) b.click(); }); await sleep(500);
  await page.evaluate(() => { [...document.querySelectorAll('.card')].filter((c) => /проверяет/.test(c.textContent) && c.querySelector('button.primary')).forEach((c) => c.querySelector('button.primary').click()); });
  await sleep(500);
  check(/:edge-accepted[^}]*:from "acc2", :to "tax2", :type :checks/.test(await log()), 'the waiting link is accepted once its relation and both nodes exist');

  // DERIVE: the document makes an ontology first, then is read as this graph's content
  await fresh('Пустой 3');
  await drop(DOC, [REPLY, { concepts: [{ id: 'buh2', title: 'Бухгалтер', type: 'role', definition: 'Ведёт счета.' }] }]);
  await page.click('.card-triage .triage-content');
  await page.select('.card-triage .triage-which', 'derive');
  await page.click('.card-triage .triage-go'); await sleep(1500);
  const reg3 = await page.evaluate(() => localStorage.getItem('loretile-graphs'));
  check(/:title "Онтология: Как работает магазин", :ontology "ontology"/.test(reg3), 'derive: an ontology graph is made from the document, named after its heading');
  check((await page.$$('.card-type')).length >= 1 && await page.$('.card-derive-next .derive-go[disabled]') !== null,
    'its types are cards, and the next step waits until they are decided');
  await page.evaluate(() => { const b = document.querySelector('.accept-all-topics'); if (b) b.click(); }); await sleep(400);
  await page.evaluate(() => { const b = document.querySelector('.accept-all-links'); if (b) b.click(); }); await sleep(400);
  await page.evaluate(() => { [...document.querySelectorAll('.relation-accept, .type-accept')].forEach((b) => b.click()); }); await sleep(500);
  check(await page.$('.card-derive-next .derive-go:not([disabled])') !== null, 'decided: the next step can go');
  await page.click('.card-derive-next .derive-go'); await sleep(1500);
  const reg4 = await page.evaluate(() => localStorage.getItem('loretile-graphs'));
  check(/:title "Пустой 3", :ontology "o-/.test(reg4), 'the graph the document was dropped in takes the derived ontology');
  check(/Бухгалтер/.test(await pile()), 'and the document is read as its content');

  await fresh('Пустой 2');
  await drop(path.join(__dirname, '..', 'kg', 'ontologies', 'business.edn'), [REPLY]);
  check(await page.$eval('.card-triage .triage-ontology', (e) => e.checked).catch(() => false), 'an ontology file\'s guess: an ontology');
  await page.click('.card-triage .triage-go'); await sleep(800);
  check(/:title "Пустой 2", :ontology "ontology"/.test(await page.evaluate(() => localStorage.getItem('loretile-graphs')))
        && /Предложено типов: 7, связей: 15/.test(await msgs()) && (await page.evaluate(() => window.__calls.length)) === 0,
    '«it is an ontology»: the empty graph describes types now, and the file becomes cards without the model');

  await browser.close();
  fs.rmSync(dir, { recursive: true, force: true });
  console.log(failures ? 'VOCAB INTAKE CHECK FAILED' : 'VOCAB INTAKE CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

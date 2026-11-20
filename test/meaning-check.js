// What a model reads off a graph (2026-10-21, after Netflix's UDA): one
// sentence per node type and relation saying what it MEANS, who owns the
// graph, and the graph as RDF for an organisation's own tools.
//  - the sample ontology's descriptions arrive with the file
//  - #/ontology: set the owner (a graph-set op)
//  - the agent export (.md) and the RDF export (.ttl) carry both
//  - a learning graph shows no description cells (its words are built in)
// Usage: serve public/ on :8291; node test/meaning-check.js
const path = require('path');
const puppeteer = require('puppeteer-core');
const G = require('./lib/graphs');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  page.on('dialog', (d) => d.accept(d.type() === 'prompt' ? 'Магазин' : undefined));
  await page.setViewport({ width: 1280, height: 900 });
  // every download is a Blob through URL.createObjectURL: keep the last one
  await page.evaluateOnNewDocument(() => {
    URL.createObjectURL = (b) => { window.__blob = b; return 'about:blank'; };
    // and the download link's click goes nowhere (it would leave the page)
    const click = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () { if (!this.download) click.call(this); };
  });
  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');
  const clear = async (sel) => {
    await page.focus(sel);
    await page.keyboard.down('Control'); await page.keyboard.press('KeyA'); await page.keyboard.up('Control');
    await page.keyboard.press('Backspace');
  };
  const log = () => page.evaluate(() => loretileLog() || '');
  const download = async (sel) => {
    await page.evaluate(() => { window.__blob = null; });
    // exports live on each graph's row in the Graphs view (2026-11-19)
    await page.goto(BASE + '#/graphs', { waitUntil: 'networkidle0' }); await sleep(300);
    await page.evaluate((sel) => document.querySelector(sel).click(), sel); await sleep(300);
    return page.evaluate(() => (window.__blob ? window.__blob.text() : ''));
  };

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
  });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(400);

  // --- a learning graph: built-in words, no description cells -------------------
  await page.click('.load-demo'); await sleep(1500);
  await page.goto(BASE + '#/ontology', { waitUntil: 'networkidle0' }); await sleep(300);
  check(await page.$('.ontology-page .owner-form') !== null, 'every graph can name its owner, a learning one too');
  check(await page.$('.ontology-page .desc-cell') === null, 'a learning graph shows no description cells');

  // --- a business graph from the sample ontology --------------------------------
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(300);
  if (await G.graphFromOntologyFile(page, path.join(__dirname, '..', 'kg', 'ontologies', 'business.edn'), 'Магазин') === null) { console.log('SKIP: release build — the ontology-file door is internal until slice 3 (wiki/Ontology-Graph.md)'); await browser.close(); process.exit(0); }
  await sleep(800);
  check(/:description "Роль → процесс: роль делает этот процесс/.test(await log()), 'the sample ontology\'s descriptions arrive with the file');
  await page.goto(BASE + '#/ontology', { waitUntil: 'networkidle0' }); await sleep(300);
  // what each type and relation means, read-only on its own row (words are
  // edited in the ontology graph since 2026-11-20)
  const tables = await page.$$eval('table.onto', (ts) => ts.map((t) => t.textContent));
  check(/роль делает этот процесс/.test(tables[1]), 'the relations table shows what each relation means');
  check(/Число, по которому судят о процессе/.test(tables[0]), 'and the node types table what each type is');
  // the stored log is EDN; an event's keys come in any order
  const ev = (l, ...parts) => l.split(/(?=\{:ev\/)/).some((x) => x.includes(':ev/type :change') && parts.every((p) => x.includes(p)));
  // describing a relation from this page is gone with the graph-local vocabulary
  // forms (2026-11-20): a vocabulary is edited in its ontology graph

  // the owner
  await page.type('.owner-team', 'Финансы');
  await page.type('.owner-contact', 'fin@example.com');
  await page.click('.owner-save'); await sleep(400);
  check(ev(await log(), ':graph-set {:owner {', ':team "Финансы"', ':contact "fin@example.com"'), 'the owner is an event in the log');
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(400);
  check((await page.$eval('.owner-team', (e) => e.value)) === 'Финансы', 'and the form shows it after a reload');

  // --- both exports carry the meaning and the owner ------------------------------
  const md = await download('.entity-row.on .export-agent');
  check(/Owner: Финансы \(fin@example.com\)/.test(md), 'the agent export names the owner');
  check(/`measures` измеряет \/ измеряется: Метрика → процесс: метрика говорит/.test(md), 'and each relation with what it means');
  const ttl = await download('.entity-row.on .export-ttl');
  check(/^@prefix rdf: /.test(ttl), 'the RDF export is Turtle');
  check(/v:measures a owl:ObjectProperty ;[^.]*rdfs:comment "Метрика → процесс: метрика говорит[^"]*"@ru/.test(ttl), 'a relation is an OWL property with its description');
  check(/lt:team "Финансы" ; lt:contact "fin@example.com"/.test(ttl), 'the owner is in the RDF');
  check(/v:process a owl:Class/.test(ttl) && /rdfs:domain v:metric/.test(ttl), 'types are classes, endpoints domain and range');

  await browser.close();
  console.log(failures ? 'MEANING CHECK FAILED' : 'MEANING CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

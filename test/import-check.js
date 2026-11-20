// An ontology from an ontology editor (Protégé, TopBraid, VocBench), saved
// as Turtle, starts a graph (2026-10-23 — the reverse of the RDF export):
// classes become node types, object properties relations, and the
// ontology page says what came in and what Loretile does not model.
// Usage: serve public/ on :8291; node test/import-check.js
const puppeteer = require('puppeteer-core');
const G = require('./lib/graphs');
const fs = require('fs');
const os = require('os');
const path = require('path');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'import-'));
const TTL = path.join(dir, 'shop.ttl');
fs.writeFileSync(TTL, `@prefix : <http://example.org/shop#> .
@prefix owl: <http://www.w3.org/2002/07/owl#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
<http://example.org/shop> a owl:Ontology ; rdfs:label "Shop"@en , "Магазин"@ru .
:Process a owl:Class ; rdfs:label "process"@en , "процесс"@ru ; rdfs:comment "Последовательность работы."@ru .
:Role a owl:Class ; rdfs:label "роль"@ru .
:performs a owl:ObjectProperty ; rdfs:label "выполняет"@ru ; rdfs:domain :Role ; rdfs:range :Process ; owl:inverseOf :performedBy .
:performedBy a owl:ObjectProperty ; rdfs:label "выполняется"@ru .
:partOf a owl:ObjectProperty ; rdfs:domain :Process ; rdfs:range :Process .
:amount a owl:DatatypeProperty ; rdfs:range xsd:decimal .
`);
const BAD = path.join(dir, 'broken.ttl');
fs.writeFileSync(BAD, '@prefix : <http://x#> .\n:a :b "unclosed .\n');

(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  page.on('dialog', (d) => d.accept(d.type() === 'prompt' ? d.defaultValue() : undefined));
  await page.setViewport({ width: 1280, height: 900 });
  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');
  // the drawer's file door is gone (2026-11-19); slice 3 of wiki/Ontology-Graph.md
  // brings files back as the assistant's cards — until then the store path is driven directly
  const upload = async (f) => {
    await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(300);
    if (await G.graphFromOntologyFile(page, f, path.basename(f, path.extname(f))) === null) {
      console.log('SKIP: release build — the ontology-file door is internal until slice 3'); await browser.close(); process.exit(0);
    }
    await sleep(900);
  };

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
  });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(400);


  // a broken file: refused in words, no graph made
  const graphsBefore = await page.evaluate(() => localStorage.getItem('loretile-graphs') || '');
  await upload(BAD);
  check(/Онтология не прочитана: unclosed string/.test(await text('div.error')), 'a broken file is refused in words');
  check((await page.evaluate(() => localStorage.getItem('loretile-graphs') || '')) === graphsBefore, 'and no graph is made for it');
  await page.evaluate(() => { const b = document.querySelector('div.error button'); if (b) b.click(); });

  // the Protégé-style file: a graph in its vocabulary, the page says what came in
  await upload(TTL);
  await page.goto(BASE + '#/ontology', { waitUntil: 'networkidle0' }); await sleep(300);
  const tables = await page.$$eval('table.onto', (ts) => ts.map((t) => t.textContent));
  check(/процесс/.test(tables[0]) && /Последовательность работы\./.test(tables[0]) && /роль/.test(tables[0]), 'node types in the graph\'s language, with their descriptions');
  check(/выполняет/.test(tables[1]) && /выполняется/.test(tables[1]) && /role → process/.test(tables[1]), 'a relation with its inverse and endpoints — one, not two');
  const log = await page.evaluate(() => loretileLog());
  // the bound graph's log carries its ontology as a copy (2026-11-20): the
  // inverse folded into one relation, not imported as a second
  check(/\[:graph-set \{:ontology "[^"]+".*:ontology-copy \{/.test(log) && /:id :performs/.test(log) && !/:id :performed-by/.test(log), 'the bound graph\'s log carries the ontology copy');

  await browser.close();
  fs.rmSync(dir, { recursive: true, force: true });
  console.log(failures ? 'IMPORT CHECK FAILED' : 'IMPORT CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

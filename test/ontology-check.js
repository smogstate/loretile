// Ontology profiles (wiki/Profiles.md): a graph created from an ontology
// file speaks its own vocabulary — typed nodes, the chooser offers only the
// relations that fit two endpoints, the ontology page lists the vocabulary,
// the learning chrome (course button, hats, questions) is ABSENT, and the
// markdown export for an agent carries typed relations both ways.
// Usage: serve public/ first, then: node test/ontology-check.js [baseUrl]
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
  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    localStorage.setItem('loretile-tour-done', '1');
    localStorage.setItem('loretile-chat-tour-done', '1');
    localStorage.setItem('loretile-learn-tour-done', '1');
  });
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(300);

  const nodes = () => page.evaluate(() => document.querySelectorAll('.node').length);
  const openDrawer = async () => { await page.click('.burger'); await sleep(350); };
  const railTitles = () => page.evaluate(() => [...document.querySelectorAll('.rail .rbtn')].map((b) => b.title));
  const addNode = async (title, type) => {
    await page.evaluate(() => [...document.querySelectorAll('.rail .rbtn')].find((e) => /Тема/.test(e.title)).click());
    await page.waitForSelector('.modal input');
    if (type) await page.select('.modal select.node-type', type);
    await page.type('.modal input', title);
    await page.type('.modal textarea', title + ' — определение.');
    await page.click('.modal button.primary');
    await sleep(400);
  };

  // a learning graph shows the learning chrome
  await page.click('.load-demo');
  await sleep(2200);
  check((await railTitles()).includes('Курс'), 'learning graph: the course button is on the rail');
  check(await page.$('.hats') !== null, 'learning graph: the two hats are shown');

  // create a graph from the ontology file: the file is read FIRST, the title asked after
  if (await G.graphFromOntologyFile(page, path.join(__dirname, '..', 'kg', 'ontologies', 'business.edn'), 'Магазин') === null) { console.log('SKIP: release build — the ontology-file door is internal until slice 3 (wiki/Ontology-Graph.md)'); await browser.close(); process.exit(0); }
  await sleep(800);
  check(await nodes() === 0, 'a graph from the ontology opened empty');
  check(await page.$('.error') === null, 'and the file was accepted');
  const log = await page.evaluate(() => loretileLog());
  check(/:profile-set/.test(log), 'the profile is the first event of its log');
  check(await page.$('.hats') === null, 'no learner hat on a graph with nothing to learn');

  // typed nodes
  await addNode('Оформление заказа', 'process');
  await addNode('Выставление счёта', 'process');
  await addNode('Бухгалтер', 'role');
  check(await nodes() === 3, 'three typed nodes');
  check(!(await railTitles()).includes('Курс'), 'the course button is absent (never disabled)');
  check(/:kind :role/.test(await page.evaluate(() => loretileLog())),
    'the node carries its type');

  // link mode: the chooser offers only relations whose types fit
  const nodeByTitle = async (re) => {
    for (const h of await page.$$('.node:not(.node-ghost)')) {
      if (re.test(await h.$eval(':scope > title', (t) => t.textContent))) return h;
    }
    console.log('no node for', re, await page.$$eval('.node', (ns) => ns.map((n) => n.className.baseVal + ':' + (n.querySelector(':scope > title') || {}).textContent)));
    return null;
  };
  await page.evaluate(() => [...document.querySelectorAll('.rail .rbtn')].find((e) => /Связь/.test(e.title)).click());
  await (await nodeByTitle(/Бухгалтер/)).click(); await sleep(150);
  await (await nodeByTitle(/Выставление/)).click(); await sleep(300);
  const offered = await page.evaluate(() => [...document.querySelectorAll('.modal .edge-btn')].map((b) => b.textContent));
  check(offered.length === 2 && offered.includes('выполняет') && offered.includes('владеет'),
    `role → process offers performs/owns only (${offered.join(', ')})`);
  await page.evaluate(() => [...document.querySelectorAll('.modal .edge-btn')].find((b) => /выполняет/.test(b.textContent)).click());
  await sleep(400);
  check(/:type :performs/.test(await page.evaluate(() => loretileLog())), 'the edge was written in the ontology\'s relation');

  // the popup reads the relation back with its inverse label, no questions section
  await (await nodeByTitle(/Выставление/)).click(); await sleep(250);
  await (await nodeByTitle(/Выставление/)).click(); await sleep(600);
  const panel = await page.$eval('.gpopup .panel', (e) => e.textContent);
  check(/выполняется ← Бухгалтер/.test(panel), 'the receiving end reads the inverse label');
  check(!/Вопросы/.test(panel) && !/вопрос/.test(panel), 'no questions section on a node that is not learnable');
  check(/процесс/.test(await page.$eval('.gpopup .node-type-badge', (e) => e.textContent)), 'the node type is on the panel head');

  // the ontology page
  await page.goto(BASE + '#/ontology', { waitUntil: 'networkidle0' });
  await sleep(300);
  const onto = await page.$eval('.ontology-page', (e) => e.textContent);
  check(/Типы узлов/.test(onto) && /процесс/.test(onto) && /роль/.test(onto), 'the ontology page lists node types');
  check(/triggers/.test(onto) && /запускает/.test(onto) && /порядок слоёв/.test(onto), 'and the relations with the ordering one marked');
  check(!/[a-z]+-[a-z]+ \{/.test(onto), 'no raw EDN on the page');

  // competency questions: raised here, CHECKED by the fold — the process
  // without a performer is the gap; the relation no question needs is marked
  await page.type('.cq-form input', 'Кто выполняет процесс?');
  await page.select('.cq-form select.cq-rel', 'performs');
  await sleep(100);
  await page.select('.cq-form select.cq-dir', 'in');
  await page.select('.cq-form select.cq-from', 'process');
  await page.click('.cq-form .cq-add');
  await sleep(400);
  const cq = await page.$eval('.cq-row', (e) => e.textContent);
  check(/отвечено 1 из 2/.test(cq), `the question is checked over the graph (${cq})`);
  check(/пробелы: .*Оформление заказа/.test(cq), 'and names the process with no performer as the gap');
  check(/:cq-raised/.test(await page.evaluate(() => loretileLog())), 'the question is in the log');
  const relRows = await page.$$eval('table.onto tr', (rows) => rows.map((r) => r.textContent));
  check(relRows.some((r) => /triggers/.test(r) && /ни один вопрос не требует/.test(r)), 'a relation no question needs is marked');
  check(!relRows.some((r) => /performs/.test(r) && /ни один вопрос не требует/.test(r)), 'the one a question needs is not');

  // the ontology grows additively: a node type, then a relation using it
  const ntInputs = await page.$$('.node-type-form input:not([type=color])');
  // (metric and measures already exist in business.edn — the guard refuses duplicates)
  await ntInputs[0].type('team'); await ntInputs[1].type('команда');
  await page.click('.node-type-form .node-type-add');
  await sleep(300);
  check(/команда/.test(await page.$eval('.ontology-page', (e) => e.textContent)), 'a node type joined the ontology');
  const relInputs = await page.$$('.relation-form input:not([type=checkbox])');
  await relInputs[0].type('staffs'); await relInputs[1].type('укомплектовывает'); await relInputs[2].type('укомплектована');
  const relSelects = await page.$$('.relation-form select');
  await relSelects[0].select('team'); await relSelects[1].select('process');
  await page.click('.relation-form .relation-add');
  await sleep(300);
  check(/staffs/.test(await page.$eval('.ontology-page', (e) => e.textContent)), 'a relation joined the ontology');
  check(await page.$('.error') === null, `without a guard error (${await page.$eval('.error', (e) => e.textContent).catch(() => '')})`);

  // the gap link opens the node, whose panel names its unanswered question
  // (by script: the «relation added» snack can sit over the link)
  await page.evaluate(() => document.querySelector('.cq-row a').click());
  await sleep(700);
  check(/Кто выполняет процесс/.test(await page.$eval('.gpopup .node-gaps', (e) => e.textContent).catch(() => '')),
    'the node panel names the question it leaves unanswered');

  // the learning graph is untouched by all of this
  await G.openGraph(page, BASE, /SQL|аналитик/);
  check((await railTitles()).includes('Курс'), 'back in the learning graph the course button returns');

  await page.screenshot({ path: 'test/shot-ontology.png' });
  await browser.close();
  console.log(failures ? `\n${failures} FAILED` : '\nALL PASSED');
  process.exit(failures ? 1 : 0);
})();

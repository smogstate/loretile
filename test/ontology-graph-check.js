// The ontology as an ordinary graph (wiki/Ontology-Graph.md, wiki/Property-Graph.md): an
// ontology file makes an ontology graph and a data graph bound to it by a
// copy (a graph property, in a change event); the ontology graph's
// map draws types as nodes and relations as edges (a loop for type → same
// type); a change made in the ontology graph flows into the bound graph; a
// fresh ontology must be whole before a graph can use it; the deep link
// reaches a graph's ontology page.
// Usage: serve public/ first, then: node test/ontology-graph-check.js [baseUrl]
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
  let answer = 'Магазин';
  page.on('dialog', (d) => d.accept(d.type() === 'prompt' ? answer : undefined));
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
  const registry = () => page.evaluate(() => localStorage.getItem('loretile-graphs'));

  // an ontology file makes an ontology graph «Онтология: Магазин» and a graph
  // «Магазин» bound to it (the load-time move of a graph carrying its own
  // profile is gone with the profile event, 2026-11-20)
  if (await G.graphFromOntologyFile(page, path.join(__dirname, '..', 'kg', 'ontologies', 'business.edn'), 'Магазин') === null) {
    console.log('SKIP: release build — the ontology-file door is internal until slice 3'); await browser.close(); process.exit(0);
  }
  await sleep(800);
  // and both survive a reload
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(500);
  const reg = await registry();
  const log = () => page.evaluate(() => loretileLog());
  check(/:ontology "ontology"/.test(reg), 'an ontology graph was made from the file (a graph on the types ontology)');
  check(/:ontology "o-/.test(reg), 'and the data graph is bound to it in the registry');
  check(/:ontology-copy/.test(await log()), 'the data graph carries a copy of its ontology, as a graph property');
  await page.goto(BASE + '#/ontology', { waitUntil: 'networkidle0' });
  await sleep(300);
  check(await page.$('.bound-note a') !== null, 'its ontology page names the ontology graph');
  check(await page.$('.node-type-form') === null && await page.$('.relation-form') === null,
    'and offers no vocabulary forms there: they live in the ontology graph');

  // the ontology graph is an ordinary graph: types are nodes, relations edges
  await page.click('.bound-note a');
  await sleep(900);
  check(/#\/ontology$/.test(await page.evaluate(() => location.hash)), 'the link opened the ontology graph on its ontology page');
  check(await page.$('.ontology-users li') !== null, 'which lists the graph that uses it');
  check(await page.$('.edit-on-map') !== null && await page.$('.node-type-form') === null, 'and sends editing to its map');
  check(/:ev\/type :change/.test(await log()) && !/ontology-graph-started|ontology-synced/.test(await log()),
    'its log holds ordinary change events, no special ones');
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' });
  await sleep(1500);
  const typeCount = await nodes();
  check(typeCount >= 6, `the ontology graph's map shows its node types (${typeCount})`);
  check(await page.$$eval('.edge', (es) => es.length).then((n) => n >= 8), 'and its relations as edges');
  check(await page.$('.edge-loop') !== null, 'a relation from a type to itself draws a loop');
  const rail = await page.evaluate(() => [...document.querySelectorAll('.rail .rbtn')].map((b) => b.title));
  check(rail.includes('Тема') && rail.includes('Связь'), 'its rail adds and links like any graph');

  // add a type on its map, then draw a relation and name it as it is drawn
  const addNode = async (title, type) => {
    await page.evaluate(() => [...document.querySelectorAll('.rail .rbtn')].find((e) => /Тема/.test(e.title)).click());
    await page.waitForSelector('.modal input');
    if (type) await page.select('.modal select.node-type', type);
    await page.type('.modal input', title);
    await page.type('.modal textarea', title + ' — определение.');
    await page.click('.modal button.primary');
    await sleep(500);
  };
  const tapNode = async (title) => {
    const i = await page.evaluate((t) => [...document.querySelectorAll('.node')].findIndex((n) => n.textContent.includes(t)), title);
    const ns = await page.$$('.node');
    await ns[i].click(); await sleep(150);
  };
  const idOf = async (title) => ((await log()).match(new RegExp(':node-set "([^"]+)" \\{[^}]*:title "' + title + '"')) || [])[1];
  await addNode('Поставщик');
  await sleep(800);
  check(await page.$('.error') === null && await nodes() === typeCount + 1, 'a type is added on the ontology map');
  const procTitle = await page.evaluate(() => [...document.querySelectorAll('.node')].map((n) => n.textContent).find((t) => /процесс/i.test(t)) || '');
  await page.evaluate(() => [...document.querySelectorAll('.rail .rbtn')].find((e) => /Связь/.test(e.title)).click());
  await tapNode('Поставщик'); await tapNode(procTitle.trim().slice(0, 7));
  check(await page.$('.modal .new-relation') !== null, 'drawing between two types offers a new relation');
  await page.type('.new-relation-label', 'поставляет для');
  await page.type('.new-relation-inverse', 'получает от');
  await page.click('.new-relation-add'); await sleep(600);
  check(/:edge-set "[^"]+" :поставляет-для/.test(await log()) && /:label "поставляет для"/.test(await log()), 'the relation is an edge whose words are its properties');

  // properties of a type, in its popup
  const sup = await idOf('Поставщик');
  await page.goto(BASE + '#/c/' + encodeURIComponent(sup), { waitUntil: 'networkidle0' }); await sleep(600);
  check(await page.$('.node-props .prop-color') !== null && await page.$('.node-props .prop-learnable') !== null,
    'a type\'s popup edits its colour and learnable flag');
  // a colour chosen: the picker commits with the browser's change event
  await page.evaluate(() => {
    const i = document.querySelector('.prop-color');
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(i, '#ff8800');
    i.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await sleep(300);
  await page.click('.prop-learnable'); await sleep(300);
  await page.type('.prop-new-key', 'пример');
  await page.type('.prop-new-value', 'ООО Ромашка');
  await page.click('.prop-add-btn'); await sleep(400);
  const l1 = await log();
  check(/:color "#ff8800"/.test(l1) && /:learnable\? true/.test(l1) && /:пример "ООО Ромашка"/.test(l1),
    'colour, flag and a free property are node-set ops in the log');
  check(await page.$eval('.prop-row .prop-value', (e) => e.value).catch(() => '') === 'ООО Ромашка', 'the free property shows as a row');
  await page.click('.node-props .icon-current'); await sleep(200);
  await page.click('.icon-picker .icon-pick[title="person"]'); await sleep(400);
  check(/:node-set "[^"]+" \{:icon "person"\}/.test(await log()), 'an icon is chosen for the type from the app\'s set');
  check(await page.$('.node.selected g path, .node g path') !== null, 'and draws in the node');

  // a relation's words and flags, edited from a type's popup — every edge of it at once
  check(await page.$('.relation-props .relation-row') !== null, 'the type\'s popup lists its relations');
  await page.evaluate(() => {
    const r = [...document.querySelectorAll('.relation-row')].find((x) => x.querySelector('.rel-id').value === 'поставляет-для');
    const i = r.querySelector('.rel-label'); i.focus(); i.select();
  });
  await page.keyboard.type('снабжает'); await page.keyboard.press('Enter'); await sleep(400);
  check(/:поставляет-для "[^"]+" \{:label "снабжает"\}/.test(await log()), 'renaming a relation writes its edges');
  await page.evaluate(() => [...document.querySelectorAll('.relation-row')].find((x) => x.querySelector('.rel-id').value === 'поставляет-для').querySelector('.rel-ordering').click());
  await sleep(400);
  check(/порядк|ordering|bad-profile|Онтология/i.test(await page.$eval('.error', (e) => e.textContent).catch(() => '')),
    'a second ordering relation is refused, in words');
  await page.evaluate(() => { const b = document.querySelector('div.error button'); if (b) b.click(); });
  // an edge's own properties: from its chip in the popup, or by tapping the relation's name on the map
  await page.goto(BASE + '#/c/' + encodeURIComponent(sup), { waitUntil: 'networkidle0' }); await sleep(500);
  await page.evaluate(() => [...document.querySelectorAll('.links .chip')].find((c) => /снабжает/.test(c.textContent)).querySelector('.chip-props').click());
  await sleep(300);
  check(await page.$('.edge-props .prop-new-key') !== null, 'a link chip opens the edge\'s own properties');
  await page.type('.edge-props .prop-new-key', 'срок');
  await page.type('.edge-props .prop-new-value', '5 дней');
  await page.keyboard.press('Enter'); await sleep(400);
  check(/:edge-set "[^"]+" :поставляет-для "[^"]+" \{:срок "5 дней"\}/.test(await log()), 'an edge property is an edge-set op');
  check(await page.$eval('.edge-props .prop-row .prop-value', (e) => e.value).catch(() => '') === '5 дней'
        && /1/.test(await page.$eval('.links .chip.on .chip-n', (e) => e.textContent).catch(() => '')), 'the row shows, and the chip counts it');
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(900);
  await page.evaluate(() => { const t = [...document.querySelectorAll('.edge-text')].find((x) => /снабжает/.test(x.textContent)); t.dispatchEvent(new MouseEvent('click', { bubbles: true })); });
  await sleep(700);
  check(decodeURIComponent(await page.evaluate(() => location.hash)) === '#/c/' + sup && await page.$('.edge-props .prop-row') !== null,
    'tapping a relation\'s name on the map opens that edge in its source\'s popup');

  // a relation can go entirely (the dialog is accepted): every edge of it in one change
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(600);
  await page.evaluate(() => [...document.querySelectorAll('.rail .rbtn')].find((e) => /Связь/.test(e.title)).click());
  await tapNode('Поставщик'); await tapNode(procTitle.trim().slice(0, 7));
  await page.type('.new-relation-label', 'временная'); await page.click('.new-relation-add'); await sleep(500);
  await page.goto(BASE + '#/c/' + encodeURIComponent(sup), { waitUntil: 'networkidle0' }); await sleep(500);
  await page.evaluate(() => [...document.querySelectorAll('.relation-row')].find((x) => x.querySelector('.rel-id').value === 'временная').querySelector('.rel-delete').click());
  await sleep(400);
  check(/:edge-remove "[^"]+" :временная/.test(await log()) && !(await page.$$eval('.relation-row', (rs) => rs.some((r) => r.querySelector('.rel-id').value === 'временная'))),
    'a whole relation is deleted from the type\'s popup');

  // a competency question is raised in the ontology graph and checked in the graphs using it
  await page.goto(BASE + '#/ontology', { waitUntil: 'networkidle0' }); await sleep(300);
  await page.type('.cq-form input', 'Кто выполняет процесс?');
  await page.select('.cq-form select.cq-rel', 'performs'); await sleep(100);
  await page.select('.cq-form select.cq-dir', 'in');
  await page.select('.cq-form select.cq-from', 'process');
  await page.click('.cq-form .cq-add'); await sleep(400);
  check(/проверяется в графах/.test(await page.$eval('.cq-row', (e) => e.textContent).catch(() => '')),
    'the ontology graph raises a question, to be checked in the graphs using it');

  // the bound graph took the new copy
  await G.openGraph(page, BASE, /^Магазин$/);
  await page.goto(BASE + '#/ontology', { waitUntil: 'networkidle0' });
  await sleep(300);
  const tables = await page.$eval('.ontology-page', (e) => e.textContent);
  check(/Поставщик/.test(tables) && /снабжает/.test(tables), 'the bound graph took the new copy: the type and the renamed relation');
  const cqRow = await page.$eval('.cq-row', (e) => e.textContent).catch(() => '');
  check(/Кто выполняет процесс/.test(cqRow) && /из онтологии/.test(cqRow) && /отвечено/.test(cqRow),
    `the graph using it inherits the question and checks it over its data (${cqRow.slice(0, 80)})`);
  check(/:ontology-copy \{[^}]*:icon "person"/.test(await log()) || /:icon "person"/.test((await log()).split(':ontology-copy').pop()),
    'the type\'s icon travels in the copy to the graph using it');
  check(await page.$('.type-styles .type-style-row') !== null, 'the graph\'s own page restyles its types (graph meta)');
  await page.evaluate(() => {
    const r = [...document.querySelectorAll('.type-style-row')].find((x) => /Поставщик/.test(x.textContent));
    const i = r.querySelector('.prop-color');
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(i, '#112233');
    i.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await sleep(400);
  check(/:type-style \{:[^ ]*поставщик \{:color "#112233"\}\}/.test(await log()), 'a type\'s colour set in the graph\'s meta, over the ontology\'s');

  // properties of a data node, in its popup — they survive a reload
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(500);
  await addNode('Оплата счёта', 'process');
  const pay = await idOf('Оплата счёта');
  await page.goto(BASE + '#/c/' + encodeURIComponent(pay), { waitUntil: 'networkidle0' }); await sleep(600);
  check(await page.$('.node-props .prop-color') !== null && await page.$('.node-props .prop-learnable') === null,
    'a data node has its own colour and icon, but no type flag');
  await page.type('.prop-new-key', 'владелец');
  await page.type('.prop-new-value', 'финансы');
  await page.click('.prop-add-btn'); await sleep(400);
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(800);
  check(await page.$eval('.prop-row .prop-value', (e) => e.value).catch(() => '') === 'финансы', 'a data node\'s property is set and survives a reload');
  await page.focus('.prop-row .prop-value');
  await page.evaluate(() => document.querySelector('.prop-row .prop-value').select());
  await page.keyboard.type('бухгалтерия'); await page.keyboard.press('Enter'); await sleep(400);
  check(/:владелец "бухгалтерия"/.test(await log()), 'editing the value writes it');
  await page.focus('.prop-row .prop-key');
  await page.evaluate(() => document.querySelector('.prop-row .prop-key').select());
  await page.keyboard.type('ответственный'); await page.keyboard.press('Enter'); await sleep(400);
  check(/:ответственный "бухгалтерия"/.test(await log()) && await page.$eval('.prop-row .prop-key', (e) => e.value).catch(() => '') === 'ответственный',
    'renaming a key moves the value under the new name');
  await page.click('.prop-row .prop-x'); await sleep(400);
  check(await page.$('.prop-row') === null && /:ответственный nil/.test(await log()), 'and × removes it');

  // a graph held on an old ontology version is unstuck by a migration (2026-11-20)
  await G.openGraph(page, BASE, /^Онтология: Магазин$/);
  const removed = await page.evaluate(() => loretile.store.append_BANG_(cljs.reader.read_string('{:ev/type :change :ops [[:node-remove "process"]]}')));
  check(removed === null, 'the ontology drops the type «процесс» (no usage checks there)');
  await G.openGraph(page, BASE, /^Магазин$/);
  await page.goto(BASE + '#/ontology', { waitUntil: 'networkidle0' }); await sleep(400);
  check(/процесс/.test(await page.$eval('.migration', (e) => e.textContent).catch(() => '')),
    'the graph using it is held on the old version, and its page says what the new one drops');
  await page.select('.migration .migrate-type', 'role');
  await page.click('.migration .migrate-go'); await sleep(600);
  const lm = await log();
  check(/:ontology-copy/.test(lm.split(':node-set').slice(-2).join('')) || /:kind :role/.test(lm), 'one change: the new copy and the node retyped');
  check(await page.$('.migration') === null && /:node-set "[^"]+" \{:kind :role\}/.test(lm), 'the graph is on the new version; its process node is a role now');

  // a relation's ID renamed in the ontology: the graphs using it move their links (2026-11-20)
  const app = (edn) => page.evaluate((x) => { const r = loretile.store.append_BANG_(cljs.reader.read_string(x)); return r ? cljs.core.pr_str(r) : null; }, edn);
  await G.openGraph(page, BASE, /^Онтология: Магазин$/);
  check(await app('{:ev/type :change :ops [[:edge-set "role" :курирует "role" {:label "курирует"}]]}') === null, 'the ontology gains a relation «курирует»');
  await G.openGraph(page, BASE, /^Магазин$/);
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(400);
  await addNode('Кассир', 'role');
  const cashier = await idOf('Кассир');
  check(await app(`{:ev/type :change :ops [[:edge-set "${pay}" :курирует "${cashier}" {:с "понедельника"}]]}`) === null, 'and the graph using it links two roles with it');
  await G.openGraph(page, BASE, /^Онтология: Магазин$/);
  await page.goto(BASE + '#/c/role', { waitUntil: 'networkidle0' }); await sleep(500);
  await page.evaluate(() => {
    const i = [...document.querySelectorAll('.relation-row .rel-id')].find((x) => x.value === 'курирует'); i.focus(); i.select();
  });
  await page.keyboard.type('наблюдает'); await page.keyboard.press('Enter'); await sleep(500);
  await page.screenshot({ path: path.join(__dirname, 'shot-relation-id.png') });
  const lo = await log();
  check(/:edge-set "role" :наблюдает "role" \{[^}]*:label "курирует"/.test(lo) && /:relation-renames \{:курирует :наблюдает\}/.test(lo),
    'renaming its id moves its edges, words kept, and records the rename');
  await G.openGraph(page, BASE, /^Магазин$/);
  const lr = await log();
  check(new RegExp(`:edge-set "${pay}" :наблюдает "${cashier}" \\{:с "понедельника"\\}`).test(lr), 'the graph using it moved its link to the new id, its property kept');
  await page.goto(BASE + '#/ontology', { waitUntil: 'networkidle0' }); await sleep(400);
  check(await page.$('.migration') === null, 'and is not held back — nothing to migrate');

  // properties seen and searched (2026-11-20): the palette, a property under titles, the filter
  await page.goto(BASE + '#/c/' + encodeURIComponent(pay), { waitUntil: 'networkidle0' }); await sleep(500);
  await page.type('.node-props .prop-new-key', 'отдел');
  await page.type('.node-props .prop-new-value', 'финансы');
  await page.click('.node-props .prop-add-btn'); await sleep(400);
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(500);
  await addNode('Кладовщик', 'role');
  await page.keyboard.down('Control'); await page.keyboard.press('k'); await page.keyboard.up('Control'); await sleep(300);
  await page.type('.cmd-input', 'финансы'); await sleep(300);
  const row = await page.$eval('.cmd-list li', (e) => e.textContent).catch(() => '');
  check(/Оплата сч/.test(row) && /отдел: финансы/.test(row), `the palette finds a node by a property value, and says which (${row})`);
  await page.keyboard.press('Enter'); await sleep(500);
  check(decodeURIComponent(await page.evaluate(() => location.hash)) === '#/c/' + pay, 'and opens it');
  await page.goto(BASE + '#/ontology', { waitUntil: 'networkidle0' }); await sleep(300);
  await page.select('.graph-props .map-prop', 'отдел'); await sleep(300);
  check(/:map-prop :отдел/.test(await log()), 'the property shown on the map is a graph property');
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(900);
  check(await page.$$eval('.node-prop', (ts) => ts.some((x) => x.textContent === 'финансы')), 'and shows under the node\'s title');
  await page.click('.nav-filter'); await sleep(200);
  await page.select('.filter-card .filter-key', 'отдел');
  await page.select('.filter-card .filter-value', 'финансы'); await sleep(300);
  const dimmed = await page.$$eval('.node', (ns) => ns.map((n) => [n.textContent.includes('Кладовщик'), n.classList.contains('filtered-out')]));
  check(dimmed.some(([k, f]) => k && f) && dimmed.some(([k, f]) => !k && !f), 'the filter dims the nodes without that value, and only those');
  await page.click('.filter-card .filter-clear'); await sleep(200);
  check(!(await page.$('.node.filtered-out')), 'and clearing it brings them back');

  // the one form makes any graph: a title and its ontology (Denis 2026-11-19: no kind of graph is special)
  const formCreate = async (title, ontology) => {
    await page.waitForSelector('.new-graph-form');
    if (ontology) await page.select('.new-graph-ontology', ontology);
    await page.type('.new-graph-title', title);
    await page.click('.new-graph-create'); await sleep(800);
  };
  await page.goto(BASE + '#/graphs', { waitUntil: 'networkidle0' }); await sleep(300);
  await page.click('.graphs-page .new-graph');
  await page.waitForSelector('.new-graph-form');
  const opts = await page.$$eval('.new-graph-ontology option', (os) => os.map((o) => o.value + '=' + o.textContent));
  check(opts.some((o) => /^learning=Обучение/.test(o)) && opts.some((o) => /^ontology=Типы и связи/.test(o)) && opts.some((o) => /^o-/.test(o)),
    `the form offers the two built-in ontologies and the graphs that describe types (${opts.length})`);
  // a fresh ontology needs a type before a graph can use it
  await formCreate('Склад', 'ontology');
  check(/#\/ontology$/.test(await page.evaluate(() => location.hash)), 'a graph on the types ontology opens on its page');
  check(/Склад", :ontology "ontology"/.test(await registry()), 'its ontology is the built-in types one — a property, not a kind');
  check(await nodes() === 0, 'and it is empty');
  await page.click('.new-graph-from-ontology');
  await sleep(500);
  check(/не готова/.test(await page.$eval('.error', (e) => e.textContent).catch(() => '')) && await page.$('.new-graph-form') === null,
    'an empty ontology cannot be used yet, says why, and opens no form');
  await page.evaluate(() => { const b = document.querySelector('div.error button'); if (b) b.click(); });
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(400);
  await addNode('Товар');
  await page.goto(BASE + '#/ontology', { waitUntil: 'networkidle0' }); await sleep(300);
  await page.click('.new-graph-from-ontology');
  await page.waitForSelector('.new-graph-ontology', { timeout: 5000 }).catch(() => {});
  check(await page.$eval('.new-graph-ontology', (e) => e.value).catch(() => '') !== 'learning', 'with a type, the form opens with this ontology chosen');
  await formCreate('Склад: данные');
  check(/Склад: данные", :ontology "o-/.test(await registry()), 'and the new graph takes it');

  // the deep link reaches a graph's ontology page
  const oid = (reg.match(/:id "(o-[^"]+)"/) || [])[1];
  await page.evaluate((g) => { location.hash = '#/g/' + g + '/ontology'; }, oid);
  await sleep(700);
  check(await page.evaluate(() => location.hash) === '#/ontology' && await page.$('.ontology-users') !== null,
    'the deep link #/g/{ontology}/ontology opened that ontology graph\'s page');

  // the colour scheme is a graph property
  check(await page.$eval('.color-scheme', (e) => e.value).catch(() => '') === 'blueprint', 'a graph on the types ontology draws in the blueprint scheme by default');
  await page.select('.color-scheme', 'warm'); await sleep(300);
  check(/:color-scheme "warm"/.test(await log()), 'choosing a scheme writes a graph property');
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(400);
  check(await page.$('.map-wrap.scheme-warm') !== null, 'and the map draws in it');

  // ONE list of graphs (2026-11-19): the drawer only navigates
  await page.click('.burger'); await sleep(350);
  const drawerText = await page.$eval('.drawer nav', (e) => e.textContent);
  check(await page.$('.drawer .nav-graphs') !== null && await page.$('.drawer .nav-ontologies') === null,
    'the drawer links to the one Graphs view');
  check(!/Новый граф|Экспорт|Импорт|Демо-графы/.test(drawerText) && await page.$('.drawer .graph-row') === null,
    'and no longer lists graphs, makes them, imports or exports');
  await page.keyboard.press('Escape'); await sleep(200);
  const rows = await G.listRows(page, BASE);
  check(['Магазин', 'Онтология: Магазин', 'Склад', 'Склад: данные'].every((t) => rows.some((r) => r.replace('*', '') === t)),
    `the Graphs view lists every graph, ontologies included (${rows.join(' | ')})`);
  check(await page.$$eval('.graphs-page .entity-row', (es) => es.every((e) => e.querySelector('.export-log') && e.querySelector('.export-agent') && e.querySelector('.export-ttl'))),
    'each row exports (edn, md, ttl)');
  const rowText = (t) => page.evaluate((t) => { const r = [...document.querySelectorAll('.entity-row')].find((e) => e.querySelector('.entity-title').textContent === t); return r ? r.textContent + (r.querySelector('.delete') ? ' [delete]' : '') : ''; }, t);
  check(/Онтология: Обучение \(встроенная\)/.test(await rowText('Мой граф')), 'each row names its ontology — learning by default');
  const used = await rowText('Онтология: Магазин');
  check(/графов с ней: 1/.test(used) && !/\[delete\]/.test(used), 'a graph others use as their ontology says how many, and has no delete');
  // rename and delete an unused one (the dialog is accepted)
  await page.click('.graphs-page .new-graph'); await formCreate('Черновик', 'ontology');
  await page.goto(BASE + '#/graphs', { waitUntil: 'networkidle0' }); await sleep(300);
  answer = 'Черновик (переименован)';
  await page.evaluate(() => [...document.querySelectorAll('.entity-row')].find((e) => /^Черновик$/.test(e.querySelector('.entity-title').textContent)).querySelector('.rename').click());
  await sleep(300);
  check(/Черновик \(переименован\)/.test(await registry()), 'rename changes the title');
  const nRows = (await G.listRows(page, BASE)).length;
  await page.evaluate(() => [...document.querySelectorAll('.entity-row')].find((e) => /переименован/.test(e.querySelector('.entity-title').textContent)).querySelector('.delete').click());
  await sleep(400);
  check(!/переименован/.test(await registry()) && (await G.listRows(page, BASE)).length === nRows - 1,
    'an unused graph is deleted, its row and log gone');

  // both are in the command palette too — they open the form
  const fromPalette = async (cmd, title) => {
    await page.goto(BASE + '#/', { waitUntil: 'networkidle0' });
    await sleep(400);
    await page.keyboard.down('Control'); await page.keyboard.press('k'); await page.keyboard.up('Control');
    await sleep(300);
    await page.type('.cmd-input', cmd); await sleep(250);
    await page.keyboard.press('Enter'); await sleep(400);
    await formCreate(title);
  };
  const before = (await registry()).match(/:id "/g).length;
  await fromPalette('Новая онтология', 'Из палитры: онтология');
  check(/Из палитры: онтология",? :ontology "ontology"/.test(await registry()) && /#\/ontology$/.test(await page.evaluate(() => location.hash)),
    'the palette\'s «new ontology» opens the form with the types ontology chosen');
  await fromPalette('Новый граф', 'Из палитры: граф');
  const reg2 = await registry();
  check(/Из палитры: граф"/.test(reg2) && reg2.match(/:id "/g).length === before + 2 && await nodes() === 0,
    'the palette starts a new empty graph');

  await browser.close();
  console.log(failures ? 'ONTOLOGY GRAPH CHECK FAILED' : 'ONTOLOGY GRAPH CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

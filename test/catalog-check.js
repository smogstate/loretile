// The two hats (Denis 2026-09-11): the map is the author's home, the
// catalog the learner's. Checks the switch, search over courses AND their
// topics, per-course progress derived from the attempt log, run history on
// the card, and that the palette groups the two session kinds apart.
// Usage: serve public/ first, then: node test/catalog-check.js [baseUrl]
const puppeteer = require('puppeteer-core');
const G = require('./lib/graphs');
const fs = require('fs');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

const KEY = {};
for (const line of fs.readFileSync(__dirname + '/../kg/demo/sql.ru.edn', 'utf8').split('\n')) {
  const sc = line.match(/:stem "([^"]+)" :options \[((?:"[^"]*" ?)+)\] :key (\d+)/);
  if (sc) { const opts = [...sc[2].matchAll(/"([^"]*)"/g)].map((m) => m[1]); KEY[sc[1]] = opts[+sc[3]]; continue; }
  const cz = line.match(/:text "([^"]+)" :key "([^"]+)"/);
  if (cz) KEY[cz[1]] = cz[2];
}

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome',
    headless: 'new', args: ['--no-sandbox', '--disable-gpu'],
  });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  page.on('dialog', (d) => d.accept());
  await page.setViewport({ width: 1280, height: 900 });
  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    localStorage.setItem('loretile-tour-done', '1');
    localStorage.setItem('loretile-chat-tour-done', '1');
    localStorage.setItem('loretile-learn-tour-done', '1');
    localStorage.setItem('loretile-llm-config', '{:preset :anthropic :shape :anthropic :url "http://localhost:1/none" :model "test" :key "sk-test"}');
  });
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(300);
  await page.click('.load-demo');
  await sleep(2200);
  // each demo is its OWN graph: the map shows one at a time, the catalog spans all
  const sqlNodes = await page.evaluate(() => document.querySelectorAll('.node').length);
  for (const name of ['Информационная', 'виноградар']) {
    await page.goto(BASE + '#/graphs', { waitUntil: 'networkidle0' }); await sleep(300);
    await page.click('.graphs-page .add-demo');
    await sleep(300);
    await page.evaluate((n) => [...document.querySelectorAll('.modal .demo-row')].find((b) => b.textContent.includes(n)).click(), name);
    await sleep(2200);
  }
  const lastNodes = await page.evaluate(() => document.querySelectorAll('.node').length);
  check(lastNodes > 10 && lastNodes < sqlNodes + 10,
    `the map shows ONE graph at a time, not all three merged (${sqlNodes} then ${lastNodes})`);
  // the personal starting graph plus one per demo — «Мой граф» is where
  // bring-your-own-PDF begins, so it stays
  const rows = await G.listRows(page, BASE);
  check(rows.length === 4, 'the Graphs view lists the personal graph and all three demos');
  check(rows.filter((r) => r.startsWith('*')).length === 1, 'exactly one graph is open');
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(600);

  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');
  const LIVE = '.qcard:not(.done)';
  const answer = async (right) => {
    const stem = await text(LIVE + ' .stem');
    const key = KEY[stem];
    if (await page.$(LIVE + ' .options')) {
      const opts = await page.$$eval(LIVE + ' .opt', (els) => els.map((e) => e.textContent));
      const i = right ? opts.indexOf(key) : opts.findIndex((o) => o !== key);
      await page.evaluate((i, sel) => document.querySelectorAll(sel + ' .opt')[i].click(), i, LIVE);
    } else {
      await page.type(LIVE + ' input', right ? key : 'нет');
      await page.click(LIVE + ' button.primary');
    }
    await sleep(120);
  };

  // the switch
  check((await page.$$('.hats .hat')).length === 2, 'header offers both hats');
  check(await page.$eval('.hats .hat.on', (e) => /Автор/.test(e.textContent)), 'the map is the author hat');
  await page.click('.hats .hat:last-child');
  await sleep(500);
  check(await page.$('.catalog') !== null, 'the learning hat opens the catalog');
  check(await page.$eval('.hats .hat.on', (e) => /Обучение/.test(e.textContent)), 'the learning hat is marked active');
  check(await page.$('.tracker') === null, "the author's readiness tracker is absent on the learner side");
  check((await page.$$('.course-card')).length === 4, 'every course of every graph has a card (the SQL graph has two)');
  check(/не начат/.test(await text('.course-card .badge')), 'a fresh course reads as not started');
  check(await page.$('.course-card .cc-edit') === null, "the learner's catalog carries no author's edit button");
  check(/русский/.test(await text('.course-card .cc-meta')) && /порог \d+%/.test(await text('.course-card .cc-meta')), 'its chips say what they are: language by name, the pass mark labelled');
  await page.screenshot({ path: 'test/shot-catalog.png' });

  // search reaches through to target topics
  await page.type('.cat-search', 'GROUP BY');
  await sleep(300);
  check((await page.$$('.course-card')).length === 1, 'searching a TOPIC name finds exactly its course');
  await page.$eval('.cat-search', (el) => { el.value = ''; });
  await page.type('.cat-search', 'zzzz');
  await sleep(300);
  check((await page.$$('.course-card')).length === 0, 'a miss shows nothing');
  await page.$eval('.cat-search', (el) => { el.value = ''; });
  await page.type('.cat-search', 'SQL');
  await sleep(300);
  check((await page.$$('.course-card')).length === 2, 'searching a title finds every course that matches');
  await page.$eval('.cat-search', (el) => { el.value = ''; });
  await page.type('.cat-search', 'аналитиков');
  await sleep(300);
  check((await page.$$('.course-card')).length === 1 && /аналитиков/.test(await text('.course-card h3')),
    'a more specific title narrows to one');

  // start from the card, answer a couple, come back: progress is live
  await page.click('.course-card .cc-go');
  await sleep(500);
  check(await page.$('.learn .start') !== null, 'the card leads into the learner loop');
  await page.click('.learn .start');
  await sleep(400);
  await answer(true); await answer(true);
  check(await page.$eval('.learn .back', (e) => e.getAttribute('href')) === '#/courses',
    'the learner back-link points at the catalog, not the author panel');
  await page.click('.learn .back');
  await sleep(500);
  check(await page.$('.catalog') !== null, 'and it lands there');
  // the search box keeps its text across navigation — set it, do not append
  await page.$eval('.cat-search', (el) => { el.value = ''; });
  await page.type('.cat-search', 'SQL'); await sleep(300);
  check(/идёт/.test(await text('.course-card .badge')), 'the card now reads as in progress');
  check(/закрыто \d+ из \d+ тем/.test(await text('.course-card')), 'the card shows closed-topic progress');
  check((await page.$$('.cc-runs .cc-run')).length === 1, 'the run is listed on its own course card');

  // reopening from the card restores the feed
  await page.click('.cc-runs .cc-run');
  await sleep(600);
  check(await page.$('.learn .feed') !== null && (await page.$$('.qcard')).length >= 2,
    'reopening a run from the card restores its feed');

  // CROSS-COURSE CREDIT: ace one course's diagnostic, and the sibling course
  // in the same graph must open pre-shrunk, saying so (the whole thesis)
  await page.goto(BASE + '#/learn/sql-analyst', { waitUntil: 'networkidle0' });
  await sleep(600);
  await page.click('.learn-head .restart'); await page.waitForSelector('.restart-yes'); await page.click('.restart-yes');
  await sleep(700);
  const stage = () => page.$eval('.learn', (e) => e.dataset.stage).catch(() => '');
  let m = 0;
  while ((await stage()) === 'diagnostic' && m++ < 25) await answer(true);
  await page.goto(BASE + '#/courses', { waitUntil: 'networkidle0' });
  await sleep(800);
  await page.$eval('.cat-search', (el) => { el.value = ''; });
  await page.type('.cat-search', 'посложнее');
  await sleep(300);
  const sibling = await text('.course-card');
  check(/Короче на \d+ тем/.test(sibling),
    'the sibling course says how much shorter it is' + (/Короче/.test(sibling) ? '' : ' — got ' + sibling.slice(0, 120)));
  // counted right: «на 1 тему», «на 4 темы», «на 5 тем» (learning review 2026-11-20 B3)
  const cm = sibling.match(/Короче на (\d+) (\S+?):/);
  const ruForm = (n) => (n % 10 === 1 && n % 100 !== 11) ? 'тему' : (n % 10 >= 2 && n % 10 <= 4 && !(n % 100 >= 12 && n % 100 <= 14)) ? 'темы' : 'тем';
  check(!!cm && cm[2] === ruForm(+cm[1]), `and the count agrees with its noun («${cm ? cm[1] + ' ' + cm[2] : '—'}»)`);
  check(/Осталось \d+ из \d+/.test(sibling), 'and how much is left');

  // re-picking a loaded demo SWITCHES to its graph, never duplicates it
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' });
  await sleep(600);
  await page.goto(BASE + '#/graphs', { waitUntil: 'networkidle0' }); await sleep(300);
  await page.click('.graphs-page .add-demo');
  await sleep(300);
  await page.evaluate(() => [...document.querySelectorAll('.modal .demo-row')].find((b) => b.textContent.includes('SQL')).click());
  await sleep(1500);
  check(await page.evaluate(() => document.querySelectorAll('.node').length) === sqlNodes,
    're-picking a demo switches to its graph rather than duplicating it');
  check((await G.listRows(page, BASE)).length === 4, 'and no extra graph appeared');
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(600);

  // the palette keeps the two kinds apart
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' });
  await sleep(600);
  await page.keyboard.down('Control'); await page.keyboard.press('k'); await page.keyboard.up('Control');
  await sleep(300);
  await page.type('.cmd-input', 'SQL');
  await sleep(300);
  const groups = await page.$$eval('.cmd-list li', (els) => els.map((e) => {
    const g = e.querySelector('.cmd-group');
    return g ? g.textContent : null;
  }));
  check(groups.some((g) => g === 'Обучение'), 'learning sessions are tagged as learning in the palette');
  await page.keyboard.press('Escape');
  await sleep(200);
  // an assistant sitting must carry the other tag — make one first
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' });
  await sleep(400);
  await page.type('.composer textarea', 'Беседа автора про индексы');
  await page.click('.composer .chat-sendbtn');
  await sleep(1200);
  await page.keyboard.down('Control'); await page.keyboard.press('k'); await page.keyboard.up('Control');
  await sleep(300);
  await page.type('.cmd-input', 'Беседа автора');
  await sleep(300);
  const authorRows = await page.$$eval('.cmd-list li', (els) => els.map((e) => {
    const g = e.querySelector('.cmd-group');
    return g ? g.textContent : null;
  }));
  check(authorRows.some((g) => g === 'Автор'), 'assistant sittings are tagged as author work');
  await page.keyboard.press('Escape');

  // an address that names no screen says so (learning review 2026-11-20 B4: #/catalog
  // silently opened the author's map)
  await page.goto(BASE + '#/catalog', { waitUntil: 'networkidle0' }); await sleep(400);
  check(/Нет такой страницы: #\/catalog/.test(await page.$eval('div.error', (e) => e.textContent).catch(() => '')),
    'an unknown address says there is no such page');
  await browser.close();
  console.log(failures ? 'CATALOG CHECK FAILED' : 'CATALOG CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

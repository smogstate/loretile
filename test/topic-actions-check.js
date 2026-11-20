// The four writes nothing made until 2026-10-14 (the loretile graph's
// check-cqs found them): answer an expert question (the question's :answer),
// restore removed material (an object's :status :active), split a topic (the
// old node removed and its parts set in one change) and mark one deprecated
// (:status :deprecated) — through the topic panel, on the SQL demo.
// Usage: serve public/ first, then: node test/topic-actions-check.js [baseUrl]
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
  await page.setViewport({ width: 1280, height: 900 });
  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');
  const log = () => page.evaluate(() => loretileLog());
  const open = async (id) => { await page.goto(BASE + '#/c/' + id, { waitUntil: 'networkidle0' }); await sleep(600); };

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
  });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(300);
  await page.click('.load-demo'); await sleep(2000);

  // 1. question-answered — the demo's open question on LIMIT
  await open('limit');
  check(/Вопросы к эксперту \(открыто: 1\)/.test(await text('.expert-questions')), 'the open expert question is shown on its topic');
  const objsBefore = (await page.$$('.gpopup .lo:not(.lo-removed)')).length;
  await page.type('.answer-form textarea', 'Keyset-пагинация; OFFSET — только для отчётов.');
  await page.click('.answer-form input[type=checkbox]');
  await page.click('.answer-form .answer-save'); await sleep(400);
  check(/:node-set "[^"]+" \{:answer "Keyset-пагинация/.test(await log()), 'the answer is written');
  check(/→ Keyset-пагинация/.test(await text('.expert-questions')) && /открыто: 0/.test(await text('.expert-questions')), 'and shown as answered');
  check((await page.$$('.gpopup .lo:not(.lo-removed)')).length === objsBefore + 1, 'and, ticked, became material on the topic');

  // 2. an object demoted → active again
  await open('where');
  const before = (await page.$$('.gpopup .lo:not(.lo-removed)')).length;
  await page.click('.gpopup .lo .pt-rm'); await sleep(6600);
  check((await page.$$('.gpopup .lo:not(.lo-removed)')).length === before - 1, 'removing material takes it out of use');
  check(/Убранные материалы \(1\)/.test(await text('.removed-material summary')), 'and lists it under removed material');
  await page.click('.removed-material summary'); await sleep(150);
  const promoted = async () => ((await log()).match(/:node-set "[^"]+" \{:status :active\}/g) || []).length;
  const promotedBefore = await promoted();
  await page.click('.removed-material .restore'); await sleep(400);
  check(await promoted() === promotedBefore + 1, 'restoring writes the object :status :active');
  check((await page.$$('.gpopup .lo:not(.lo-removed)')).length === before && !(await page.$('.removed-material')), 'and the material is back');

  // 3. a split — refused on a course target, done on a free topic
  await open('group-by');
  await page.click('.topic-actions .split-open'); await sleep(200);
  await page.type('.split-form .split-row:nth-of-type(2) input', 'HAVING после группировки');
  await page.click('.split-form .split-save'); await sleep(400);
  check(/курс/i.test(await text('.error')) && !/:node-remove "group-by"/.test(await log()), 'a course target cannot be split (refused in words)');
  await page.click('.error button').catch(() => {});
  await page.click('.split-form button:not(.primary):not(.split-add)').catch(() => {});

  await open('order-by');
  const nodesBefore = (await page.$$('.node:not(.node-ghost)')).length;
  await page.click('.topic-actions .split-open'); await sleep(200);
  check(await page.$('.split-form .split-save[disabled]') !== null, 'the split waits for a second title');
  await page.type('.split-form .split-row:nth-of-type(2) input', 'Сортировка по нескольким столбцам');
  await page.type('.split-form .split-row:nth-of-type(2) textarea', 'ORDER BY a, b: второй ключ решает ничьи первого.');
  const selects = await page.$$('.split-form select.split-to');
  if (selects.length) await selects[selects.length - 1].select('1');
  await page.click('.split-form .split-save'); await sleep(900);
  const l = await log();
  check(/\[:node-remove "order-by"\] \[:node-set "order-by" \{/.test(l) && /:node-set "[^"]+" \{:title "Сортировка по нескольким столбцам"[^}]*:status :accepted/.test(l), 'the split is written');
  const hash = await page.evaluate(() => decodeURIComponent(location.hash));
  check(hash === '#/c/order-by', `the first part keeps the topic's id (${hash})`);
  check((await page.$$('.node:not(.node-ghost)')).length === nodesBefore + 1, 'one topic became two on the map');
  check(/Сортировка по нескольким столбцам/.test(await page.evaluate(() => [...document.querySelectorAll('.node title')].map((t) => t.textContent).join('|'))), 'the new topic is on the map');

  // 4. deprecated — one-way, behind the ring
  await open('tables');
  await page.click('.topic-actions .deprecate'); await sleep(6600);
  check(/:node-set "tables" \{:status :deprecated\}/.test(await log()), 'deprecating is written after the ring');
  check(/Устарела/.test(await text('.topic-actions')) && !(await page.$('.topic-actions .deprecate')), 'the panel says so, and offers no second deprecate');
  check(await page.$('.node.tile-deprecated') !== null, 'the map greys it');

  await page.screenshot({ path: 'test/shot-topic-actions.png' });
  await browser.close();
  console.log(failures ? `\n${failures} FAILED` : '\nALL PASSED');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

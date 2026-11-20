// Links between learning objects (2026-10-07): an author links material on
// one topic — «example of», «simpler version of», «read after» — and the
// learner's topic card reads in that order: the example under what it
// illustrates, a simpler version behind «Объяснить проще». A link is a
// structural change, so it reaches learners with the next publication.
// Usage: serve public/ on :8291; node test/links-check.js
const puppeteer = require('puppeteer-core');
const fs = require('fs');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

// item stem → right answer, from the demo, to answer WRONG on purpose
const KEY = {};
const unq = (x) => JSON.parse('"' + x + '"');
for (const line of fs.readFileSync(__dirname + '/../kg/demo/sql.ru.edn', 'utf8').split('\n')) {
  const ex = line.match(/:kind :exercise .*:stem "((?:[^"\\]|\\.)*)" .*:solution "((?:[^"\\]|\\.)*)"/);
  if (ex) { KEY[unq(ex[1])] = { solution: unq(ex[2]) }; continue; }
  const sc = line.match(/:stem "([^"]+)" :options \[((?:"[^"]*" ?)+)\] :key (\d+)/);
  if (sc) { const o = [...sc[2].matchAll(/"([^"]*)"/g)].map((m) => m[1]); KEY[sc[1]] = o[+sc[3]]; }
  const cz = line.match(/:text "([^"]+)" :key "([^"]+)"/);
  if (cz) KEY[cz[1]] = cz[2];
}

(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  page.on('dialog', (d) => d.accept());
  await page.setViewport({ width: 1280, height: 900 });
  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');
  const log = () => page.evaluate(() => loretileLog() || '');
  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => { localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1'); });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(300);
  await page.click('.load-demo'); await sleep(2500);

  // --- author: link the worked example to the explanation it illustrates --------
  await page.goto(BASE + '#/c/select', { waitUntil: 'networkidle0' }); await sleep(800);
  const los = await page.$$('.panel .lo');
  check(los.length === 2, 'the SELECT topic has its two learning objects');
  const exRow = (await page.$$('.panel .lo'))[1];          // o-select-1, the worked example (ids sort)
  await page.evaluate(() => document.querySelectorAll('.panel .lo')[1].querySelector('.lo-link-add').click()); await sleep(200);
  await page.select('.panel .lo-link-form .lo-link-type', 'example-of');
  check(/Запрос читают/.test(await text('.panel .lo-link-form .lo-link-target')), 'the target list offers the other material on this topic');
  await page.evaluate(() => document.querySelector('.panel .lo-link-form button.primary').click()); await sleep(300);
  check(/пример к: объяснение · Запрос читают/.test(await text('.panel .lo-link')), 'the link shows as a chip on the example');
  check(/\[:edge-set "o-select-1" :example-of "o-select-0"/.test(await log()), 'and is an event in the log');

  await page.evaluate(() => document.querySelector('.panel .lo-link .chip-x').click()); await sleep(300);
  check(await page.$('.panel .lo-link') === null && /\[:edge-remove "o-select-1" :example-of "o-select-0"\]/.test(await log()), 'the × takes it back, also as an event');
  await page.evaluate(() => document.querySelectorAll('.panel .lo')[1].querySelector('.lo-link-add').click()); await sleep(200);
  await page.select('.panel .lo-link-form .lo-link-type', 'example-of');
  await page.evaluate(() => document.querySelector('.panel .lo-link-form button.primary').click()); await sleep(300);

  // a simpler version of the explanation
  const adds = await page.$$('.panel .addbtn');
  await adds[0].click(); await sleep(250);
  await page.type('.panel .form textarea', 'Проще говоря: SELECT называет столбцы, которые нужны.');
  await page.click('.panel .form button.primary'); await sleep(400);
  const rows = await page.$$('.panel .lo');
  check(rows.length === 3, 'a third, simpler object is added');
  // the new object sorts by its random id: find its row by text
  const simpleIdx = await page.evaluate(() => [...document.querySelectorAll('.panel .lo')].findIndex((r) => /Проще говоря/.test(r.textContent)));
  await page.evaluate((i) => document.querySelectorAll('.panel .lo')[i].querySelector('.lo-link-add').click(), simpleIdx); await sleep(200);
  await page.select('.panel .lo-link-form .lo-link-type', 'simpler-of');
  const target0 = await page.evaluate(() => [...document.querySelectorAll('.panel .lo-link-form .lo-link-target option')].find((o) => /Запрос читают/.test(o.textContent)).value);
  await page.select('.panel .lo-link-form .lo-link-target', target0);
  await page.evaluate(() => document.querySelector('.panel .lo-link-form button.primary').click()); await sleep(300);
  check(/проще, чем: объяснение · Запрос читают/.test(await page.evaluate(() => [...document.querySelectorAll('.panel .lo-link')].map((c) => c.textContent).join(' | '))),
    'and linked as a simpler version of the explanation');

  // --- a structural change reaches learners with the next publication -----------
  await page.goto(BASE + '#/course/sql-analyst', { waitUntil: 'networkidle0' }); await sleep(600);
  const rep = await page.$('.republish');
  check(rep !== null && !(await page.$eval('.republish', (b) => b.disabled)), 'the course offers to publish the change');
  await rep.click(); await sleep(500);

  // --- learner: miss SELECT in the diagnostic, then read its topic card ----------
  await page.goto(BASE + '#/learn/sql-analyst', { waitUntil: 'networkidle0' }); await sleep(600);
  await page.click('.learn .start'); await sleep(400);
  const stage = () => page.$eval('.learn', (e) => e.dataset.stage).catch(() => '');
  for (let n = 0; n < 25 && (await stage()) === 'diagnostic'; n++) {
    const LIVE = '.qcard:not(.done)'; const stem = await text(LIVE + ' .stem'); const key = KEY[stem];
    // miss only SELECT: it becomes the one gap, so its card comes first
    const right = !/SELECT/.test(await text(LIVE + ' .qtopic'));
    if (await page.$(LIVE + '.exercise')) {
      await page.type(LIVE + ' textarea.ex-code', right && key ? key.solution : 'SELECT 1'); const b = (await page.$$('.qcard.done')).length;
      await page.click(LIVE + ' .ex-submit'); for (let i = 0; i < 300 && (await page.$$('.qcard.done')).length === b; i++) await sleep(100);
    } else if (await page.$(LIVE + ' .options')) {
      const opts = await page.$$eval(LIVE + ' .opt', (els) => els.map((e) => e.textContent));
      const i = Math.max(0, right ? opts.indexOf(key) : opts.findIndex((o) => o !== key));
      await page.evaluate((i, sel) => document.querySelectorAll(sel + ' .opt')[i].click(), i, LIVE);
    } else { await page.type(LIVE + ' input', right && key ? key : 'нет'); await page.click(LIVE + ' button.primary'); }
    await sleep(200);
  }
  const btns = await page.$$('.feed button.primary'); await btns[btns.length - 1].click(); await sleep(500);
  const card = await page.evaluate(() => { const c = [...document.querySelectorAll('.topic-card')].pop(); return c ? c.innerHTML : ''; });
  check(/SELECT/.test(card), 'the first topic card is SELECT');
  const order = await page.evaluate(() => { const c = [...document.querySelectorAll('.topic-card')].pop();
    return [...c.querySelectorAll('.lo')].map((d) => (d.classList.contains('lo-example') ? 'EX:' : d.classList.contains('lo-simple') ? 'SIMPLE:' : 'MAIN:') + d.textContent.slice(0, 40)); });
  check(order.length === 2 && /^MAIN:.*Запрос читают/.test(order[0]) && /^EX:.*пример к материалу выше/.test(order[1]),
    'the example reads right under the explanation it illustrates (' + order.join(' || ') + ')');
  check(/Объяснить проще/.test(card) && !/Проще говоря/.test(card), 'the simpler version waits behind «Объяснить проще», not forced on the learner');
  await page.evaluate(() => [...document.querySelectorAll('.topic-card')].pop().querySelector('.lo-simpler').click()); await sleep(200);
  check(/Проще говоря/.test(await page.evaluate(() => [...document.querySelectorAll('.topic-card')].pop().textContent)), 'one tap shows it');

  await browser.close();
  console.log(failures ? 'LINKS CHECK FAILED' : 'LINKS CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

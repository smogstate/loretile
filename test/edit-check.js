// In-place editing (kintale's pencil): topic, learning object and question
// each edit through a change event carrying `:edit :patch`; the change survives a reload.
// Usage: serve public/ first, then: node test/edit-check.js [baseUrl]
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
  await page.setViewport({ width: 1280, height: 800 });
  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => { localStorage.clear(); localStorage.setItem('loretile-locale', 'ru'); localStorage.setItem('loretile-tour-done','1'); localStorage.setItem('loretile-chat-tour-done','1'); localStorage.setItem('loretile-learn-tour-done','1'); });
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(300);
  await page.click('.load-demo');
  await sleep(2000);
  await page.goto(BASE + '#/c/group-by', { waitUntil: 'networkidle0' });
  await sleep(800);

  const text = (sel) => page.$eval(sel, (e) => e.textContent);
  const setValue = async (sel, value) => {
    await page.$eval(sel, (el) => { el.value = ''; });
    await page.type(sel, value);
  };

  const editsBefore = await page.evaluate(() => (loretileLog().match(/:edit :patch/g) || []).length);
  // topic: title + definition, as a plain patch
  await page.click('.def-row .pt-edit');
  await sleep(150);
  check(await page.$('.def-row .form.edit') !== null, 'topic pencil opens the edit form');
  await setValue('.def-row .form.edit input', 'GROUP BY: корзины');
  await setValue('.def-row .form.edit textarea', 'Каждая корзина даёт одну строку.');
  await page.click('.def-row .form.edit button.primary');
  await sleep(300);
  check(/GROUP BY: корзины/.test(await text('.panel-head h2')), 'title edited in place');
  check(/одну строку/.test(await text('.def-row .def')), 'definition edited in place');
  check(!/уже есть|error/i.test(await page.evaluate(() => document.querySelector('.error')?.textContent || '')), 'no guard error (an edit is a version, not a re-accept)');

  // learning object
  const loBefore = await text('.lo p');
  await page.click('.lo .pt-edit');
  await sleep(150);
  await setValue('.lo .form.edit textarea', 'Отредактированный объект.');
  await page.select('.lo .form.edit select', 'worked-example');
  await page.click('.lo .form.edit button.primary');
  await sleep(300);
  check(/Отредактированный объект/.test(await text('.lo p')) && loBefore !== (await text('.lo p')), 'learning object text edited');
  check(/разбор примера/.test(await text('.lo .lo-kind')), 'learning object kind edited (localized label)');

  // question (single-choice): stem + options + key
  await page.click('.item .pt-edit');
  await sleep(150);
  const rows = await page.$$('.item .form.edit .opt-row');
  check(rows.length >= 2 && await page.$('.item .form.edit .opt-right:checked') !== null,
    'single-choice edit shows the options as rows, the right one marked');
  await setValue('.item .form.edit input:nth-of-type(1)', 'Новый вопрос?');
  await page.click('.item .form.edit button.primary');
  await sleep(300);
  check(/Новый вопрос\?/.test(await text('.item p')), 'question stem edited');

  // and everything survives a reload — it is in the log, not in the DOM
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(800);
  check(/GROUP BY: корзины/.test(await text('.panel-head h2')) && /Отредактированный объект/.test(await text('.lo p'))
    && /Новый вопрос/.test(await text('.item p')), 'edits survive a reload');
  const versions = await page.evaluate(() => (loretileLog().match(/:edit :patch/g) || []).length) - editsBefore;
  check(versions === 3, `three edit (:edit :patch) events in the log (got ${versions})`);

  await browser.close();
  console.log(failures ? 'EDIT CHECK FAILED' : 'EDIT CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

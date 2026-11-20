// Undo / redo (2026-11-20): an edit is never erased — Ctrl+Z appends a change
// that reverses this session's last edit of the open graph, Ctrl+Shift+Z (or
// Ctrl+Y) reverses the undo; inside a text field the browser's own text undo
// stays; the palette offers both.
// Usage: serve public/ first, then: node test/undo-check.js [baseUrl]
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
  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
  });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(300);

  const nodes = () => page.evaluate(() => document.querySelectorAll('.node').length);
  const log = () => page.evaluate(() => loretileLog() || '');
  const key = async (code, shift) => {
    await page.evaluate(() => document.activeElement && document.activeElement.blur());
    await page.keyboard.down('Control'); if (shift) await page.keyboard.down('Shift');
    await page.keyboard.press(code);
    if (shift) await page.keyboard.up('Shift'); await page.keyboard.up('Control');
    await sleep(400);
  };
  const addNode = async (title) => {
    await page.evaluate(() => [...document.querySelectorAll('.rail .rbtn')].find((e) => /Тема/.test(e.title)).click());
    await page.waitForSelector('.modal input');
    await page.type('.modal input', title);
    await page.click('.modal button.primary');
    await sleep(500);
  };

  await addNode('Первая');
  await addNode('Вторая');
  check(await nodes() === 2, 'two nodes added');
  const id = (await log()).match(/:id "([^"]+)", :title "Вторая"/)[1];

  // a property, then undo it in the popup's absence (the map has focus)
  await page.goto(BASE + '#/c/' + encodeURIComponent(id), { waitUntil: 'networkidle0' }); await sleep(400);
  await page.type('.prop-new-key', 'срок');
  await page.type('.prop-new-value', '3 дня');
  await page.keyboard.press('Enter'); await sleep(400);
  check(/:срок "3 дня"/.test(await log()), 'a property is set');
  // inside a text field Ctrl+Z is the browser's — the graph is untouched
  await page.focus('.prop-new-key'); await page.keyboard.type('x');
  await page.keyboard.down('Control'); await page.keyboard.press('KeyZ'); await page.keyboard.up('Control'); await sleep(300);
  check(await page.$('.prop-row') !== null, 'Ctrl+Z inside a text field leaves the graph alone');
  await key('KeyZ');
  check(await page.$('.prop-row') === null && /:срок nil/.test(await log()), 'Ctrl+Z reverses the property — a new change, nothing erased');
  check(/Отменено/.test(await page.$eval('.snack', (e) => e.textContent).catch(() => '')), 'and says so');

  // undo the node itself, then redo it
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(500);
  await key('KeyZ');
  check(await nodes() === 1, 'the next Ctrl+Z takes the second node back out');
  await key('KeyZ', true);
  check(await nodes() === 2, 'Ctrl+Shift+Z puts it back');
  // two undos, two redos: Ctrl+Y brings the property back too
  await key('KeyY');
  check(((await log()).match(/:срок "3 дня"/g) || []).length === 2, 'Ctrl+Y redoes the earlier undo — the property is back');
  await key('KeyY');
  check(/Нечего возвращать/.test(await page.$eval('.snack', (e) => e.textContent).catch(() => '')), 'nothing more to redo, and it says so');

  // the palette offers both
  await page.keyboard.down('Control'); await page.keyboard.press('k'); await page.keyboard.up('Control'); await sleep(300);
  await page.type('.cmd-input', 'Отменить последнее'); await sleep(250);
  await page.keyboard.press('Enter'); await sleep(500);
  check(((await log()).match(/:срок nil/g) || []).length === 2, 'the palette\'s «undo» works too (the property again)');

  // a fresh edit forgets what was undone
  await addNode('Третья');
  await key('KeyY');
  check(await nodes() === 3 && /Нечего возвращать/.test(await page.$eval('.snack', (e) => e.textContent).catch(() => '')),
    'after a fresh edit there is nothing to redo');
  // the log only grew: every undo is an appended change
  check(((await log()).match(/:ev\/type :concept-accepted/g) || []).length === 3, 'the log only grew — no edit was erased');

  await browser.close();
  console.log(failures ? 'UNDO CHECK FAILED' : 'UNDO CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

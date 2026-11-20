// Who changed what (2026-10-21: every event said «denis»). A device has one
// author id; the name set in Settings is written into each graph the person
// changes (a `[:graph-set {:authors {id name}}]` change), and the topic panel and the exports say who
// added and last changed a topic.
// Usage: serve public/ on :8291; node test/author-check.js
const puppeteer = require('puppeteer-core');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  await page.setViewport({ width: 1280, height: 900 });
  await page.evaluateOnNewDocument(() => {
    URL.createObjectURL = (b) => { window.__blob = b; return 'about:blank'; };
    const click = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () { if (!this.download) click.call(this); };
  });
  const log = () => page.evaluate(() => loretileLog() || '');
  const evs = async () => (await log()).split(/(?=\{:ev\/)/);
  // the change that names an author: [:graph-set {:authors {"a-…" "Name"}}]
  const namings = async () => (await evs()).filter((x) => /\[:graph-set \{:authors \{/.test(x));
  const addTopic = async (title) => {
    await page.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(300);
    await page.evaluate(() => [...document.querySelectorAll('.rail .rbtn')].find((e) => /Тема/.test(e.title)).click());
    await page.waitForSelector('.modal input');
    await page.type('.modal input', title);
    await page.type('.modal textarea', title + ' — определение.');
    await page.click('.modal button.primary'); await sleep(400);
  };

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
  });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(400);
  await page.click('.load-demo'); await sleep(1500);
  check((await page.evaluate(() => localStorage.getItem('loretile-author'))) === null, 'only reading writes no author record');

  // no name yet: changes carry the device id, nothing names it, the panel stays quiet
  await addTopic('Без имени');
  const id = (await page.evaluate(() => localStorage.getItem('loretile-author'))).match(/:id "(a-[0-9a-f]{8})"/)[1];
  const mine = (await evs()).filter((x) => x.includes(':ev/actor "' + id + '"'));
  check(mine.length === 1 && mine[0].includes('Без имени'), `the change carries this device's author id (${id})`);
  check((await namings()).length === 0, 'with no name set, nothing names it');
  const tid = (await log()).match(/:node-set "([^"]+)" \{:title "Без имени"/)[1];
  await page.goto(BASE + '#/c/' + encodeURIComponent(tid), { waitUntil: 'networkidle0' }); await sleep(500);
  check(await page.$('.authorship') === null, 'and the panel shows no bare id');

  // a name in Settings: the next change names its author first
  await page.goto(BASE + '#/settings', { waitUntil: 'networkidle0' }); await sleep(300);
  await page.type('.author-name', 'Анна');
  await page.click('.author-save'); await sleep(300);
  await addTopic('С именем');
  const named = await namings();
  check(named.length === 1 && named[0].includes(':authors {"' + id + '" "Анна"}') && named[0].includes(':ev/actor "' + id + '"'), 'the next change first writes the name for this id');
  const tid2 = (await log()).match(/:node-set "([^"]+)" \{:title "С именем"/)[1];
  await page.goto(BASE + '#/c/' + encodeURIComponent(tid2), { waitUntil: 'networkidle0' }); await sleep(500);
  check(/Добавлено: Анна · изменено: Анна, \d{4}-\d\d-\d\d/.test(await page.$eval('.authorship', (e) => e.textContent).catch(() => '')), 'the panel says who added and changed it');
  await page.goto(BASE + '#/c/' + encodeURIComponent(tid), { waitUntil: 'networkidle0' }); await sleep(500);
  check(/Добавлено: Анна/.test(await page.$eval('.authorship', (e) => e.textContent).catch(() => '')), 'the earlier unnamed change now reads as hers too — same id');

  // the same name again writes nothing; a new name writes once more
  await addTopic('Ещё одна');
  check((await namings()).length === 1, 'the graph already knows the name: no second naming');
  await page.goto(BASE + '#/settings', { waitUntil: 'networkidle0' }); await sleep(300);
  await page.click('.author-name', { clickCount: 3 }); await page.keyboard.press('Backspace');
  await page.focus('.author-name'); await page.keyboard.down('Control'); await page.keyboard.press('KeyA'); await page.keyboard.up('Control'); await page.keyboard.press('Backspace');
  await page.type('.author-name', 'Анна К.');
  await page.click('.author-save'); await sleep(300);
  await addTopic('После переименования');
  check((await namings()).length === 2 && (await namings())[1].includes(':authors {"' + id + '" "Анна К."}'), 'a new name is written once more, under the same id');

  // the agent export says it
  await page.goto(BASE + '#/graphs', { waitUntil: 'networkidle0' }); await sleep(300);
  await page.evaluate(() => document.querySelector('.entity-row.on .export-agent').click()); await sleep(300);
  const md = await page.evaluate(() => (window.__blob ? window.__blob.text() : ''));
  check(/## Ещё одна `[^`]+`\n- type: concept\n- added by Анна К\.; last changed by Анна К\. on \d{4}-\d\d-\d\d/.test(md), 'the agent export names the author of each topic');

  await browser.close();
  console.log(failures ? 'AUTHOR CHECK FAILED' : 'AUTHOR CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

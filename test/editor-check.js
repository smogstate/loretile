// M0.2 end-to-end check of the manual editor: boot → create two concepts →
// link them (requires) → cycle attempt is rejected → open popup (deep link) →
// add 1 LO + 3 items → concept flips to «готова к публикации» → screenshots
// at 1280 and 390 (review-new-UI rule).
// Usage: serve public/ first, then: node test/editor-check.js [baseUrl]

const puppeteer = require('puppeteer-core');

const BASE = process.argv[2] || 'http://localhost:8291';
const CHROME = process.env.CHROME_BIN || '/usr/bin/google-chrome';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let failures = 0;
const check = (ok, name) => {
  console.log((ok ? 'PASS' : 'FAIL') + ' ' + name);
  if (!ok) failures++;
};

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-gpu', '--lang=ru'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });
  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => { localStorage.clear(); localStorage.setItem('loretile-locale', 'ru'); localStorage.setItem('loretile-tour-done','1'); localStorage.setItem('loretile-chat-tour-done','1'); localStorage.setItem('loretile-learn-tour-done','1'); });
  await page.reload({ waitUntil: 'networkidle0' });

  check(await page.$('header h1') !== null, 'boot: header renders');

  const clickTool = async (label) => {
    await page.evaluate((lbl) => {
      const b = [...document.querySelectorAll('.rail .rbtn')]
        .find((el) => el.title.includes(lbl));
      b.click();
    }, label);
    await sleep(200);
  };
  const nodes = () => page.$$('.node:not(.node-ghost)');

  // -- create concept via form
  const addConcept = async (title, def) => {
    await clickTool('Тема');
    await page.waitForSelector('.modal input', { timeout: 3000 });
    await page.type('.modal input', title);
    await page.type('.modal textarea', def);
    await page.click('.modal button.primary');
    await sleep(120);
  };
  await addConcept('SELECT: выборка', 'Как достать строки из таблицы');
  await addConcept('Фильтры WHERE', 'Как отобрать нужные строки');
  await sleep(1200); // physics settle
  const tiles = await nodes();
  check(tiles.length === 2, 'two concept nodes on the map');

  // -- link WHERE -> requires -> SELECT
  await clickTool('Связь');
  const tiles2 = await nodes();
  await tiles2[1].click();
  await sleep(120);
  await tiles2[0].click();
  await sleep(160);
  const hasChooser = await page.$('.modal');
  check(hasChooser !== null, 'edge type chooser opens');
  await page.click('.modal .edge-btn'); // first = requires
  await sleep(120);
  await sleep(400);
  check((await page.$$('.edge-requires')).length === 1, 'requires edge drawn');

  // -- cycle attempt: SELECT -> requires -> WHERE must be rejected
  await clickTool('Связь');
  const t3 = await nodes();
  await t3[0].click(); await sleep(120); await t3[1].click(); await sleep(140);
  await page.click('.modal .edge-btn');
  await sleep(150);
  const errText = await page.$eval('.error', (el) => el.textContent).catch(() => '');
  check(/цикл/i.test(errText), 'cycle rejected with visible error');
  await page.click('.error button').catch(() => {});

  // -- deep link into a concept popup
  const whereId = await page.evaluate(() => {
    const evs = loretileLog();
    const m = evs.match(/:id "([^"]*where[^"]*)"/) || evs.match(/:id "(файл[^"]*)"/);
    return m ? m[1] : null;
  });
  await page.goto(BASE + '#/c/' + encodeURIComponent(whereId), { waitUntil: 'networkidle0' });
  await sleep(200);
  check(await page.$('.panel') !== null, 'deep link opens concept panel');

  // -- add 1 LO (form collapsed behind dashed addbtn)
  await page.click('.panel .addbtn');
  await sleep(150);
  await page.type('.panel .form textarea', 'WHERE отбирает строки до группировки…');
  await page.click('.panel .form button.primary');
  await sleep(150);
  check((await page.$$('.panel .lo')).length === 1, 'learning object added');

  // -- add 3 single-choice items (collapsed form each time)
  for (let i = 1; i <= 3; i++) {
    const add = await page.$$('.panel .addbtn');
    await add[add.length - 1].click();
    await sleep(150);
    const forms = await page.$$('.panel .form');
    const itemForm = forms[forms.length - 1];
    const inputs = await itemForm.$$('input');
    await inputs[0].type('Вопрос про WHERE №' + i);
    // options are rows: a text field each, a radio marks the right one
    const opts = await itemForm.$$('.opt-text');
    await opts[0].type('вариант А'); await opts[1].type('вариант Б');
    if (i === 1) check(await itemForm.$eval('button.primary', (b) => b.disabled) && /отметьте верный/.test(await itemForm.$eval('.opt-hint', (e) => e.textContent)),
      'a choice question cannot be saved until its right answer is marked');
    const radios = await itemForm.$$('.opt-right');
    await radios[i === 1 ? 1 : 0].click(); await sleep(100);
    if (i === 1) check(!(await itemForm.$('.opt-hint')), 'marked: the hint goes and it can be saved');
    await (await itemForm.$('button.primary')).click();
    await sleep(120);
  }
  check((await page.$$('.panel .item')).length === 3, 'three items added');
  const first = await page.evaluate(() => loretileLog().split('{:ev/id').find((e) => e.includes('Вопрос про WHERE №1')) || '');
  check(/:options \["вариант А" "вариант Б"\]/.test(first) && /:key 1/.test(first) && !/""/.test(first.match(/:options \[[^\]]*\]/)[0]),
    'the marked option is the key; the empty third row is dropped');

  // the tracker opens on what is not ready; the map's «?» says what colours and glyphs mean
  await page.click('button.tracker'); await sleep(200);
  check(await page.$('.tracker-list') !== null && /Ещё не готовы/.test(await page.$eval('.tracker-list', (e) => e.textContent)), 'the readiness tracker lists what is not ready');
  await page.click('button.tracker'); await sleep(150);
  await page.click('.nav-legend'); await sleep(200);
  check(/Цвета и значки/.test(await page.$eval('.legend-card', (e) => e.textContent).catch(() => '')), 'the map legend is one tap away, not only in the tour');
  await page.click('.nav-legend'); await sleep(150);
  const badge = await page.$eval('.panel .badge', (el) => el.textContent);
  check(/готова к публикации/.test(badge), 'concept reached minimum-viable: ' + badge);

  const tracker = await page.$eval('.tracker', (el) => el.textContent);
  check(/1 \/ 2/.test(tracker), 'tracker shows 1/2: ' + tracker);

  // -- reload: persistence survives
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(200);
  check(await page.$('.panel') !== null, 'reload keeps deep link + data');

  // -- removal by hand (no assistant needed): question with delayed ring,
  // cancel restores, link × goes at once
  const rms = await page.$$('.panel .item .pt-rm');
  await rms[0].click(); await sleep(150);
  check(await page.$('.panel .item .pt-rm.pending') !== null, 'item removal armed (ring)');
  await sleep(2000); await page.screenshot({ path: 'test/shot-rm-pending.png' });
  await page.click('.panel .item .pt-rm.pending'); await sleep(150);
  check(await page.$('.panel .item .pt-rm.pending') === null, 'revert cancels removal');
  await (await page.$$('.panel .item .pt-rm'))[0].click();
  await sleep(6500);
  check((await page.$$('.panel .item')).length === 2, 'question retired after the ring closes');
  await page.click('.panel .chip .chip-x'); await sleep(200);
  check((await page.$$('.edge-requires')).length === 0, 'link removed from the topic panel');

  // -- screenshots (1280 + 390)
  await page.screenshot({ path: 'test/shot-desktop.png' });
  await page.setViewport({ width: 390, height: 844 });
  await sleep(200);
  await page.screenshot({ path: 'test/shot-mobile.png' });
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' });
  await page.setViewport({ width: 1280, height: 800 });
  await sleep(200);
  await page.screenshot({ path: 'test/shot-map.png' });

  // -- orbit chips on the selected node: link and delete
  const sel = (await nodes())[0];
  await sel.click(); await sleep(250);
  check((await page.$$('.orbit')).length === 2, 'selected node wears link + delete chips');
  await page.screenshot({ path: 'test/shot-orbit.png' });
  await page.click('.orbit-link'); await sleep(150);
  await (await nodes())[1].click(); await sleep(200);
  check(await page.$('.modal .edge-btn') !== null, 'link chip starts a link from the node');
  await page.click('.modal .edge-btn'); await sleep(300);
  check((await page.$$('.edge-requires')).length === 1, 'link redrawn via orbit chip');
  await (await nodes())[0].click(); await sleep(250);
  await page.click('.orbit-del'); await sleep(150);
  check(await page.$('.orbit-del.pending') !== null, 'delete chip arms the ring');
  await sleep(2000); await page.screenshot({ path: 'test/shot-orbit-pending.png' });
  await sleep(6500);
  check((await nodes()).length === 1, 'topic removed after the ring closes');
  check((await page.$$('.edge-requires')).length === 0, 'its links went with it');

  await browser.close();
  console.log(failures === 0 ? 'EDITOR CHECK OK' : 'EDITOR CHECK FAILED: ' + failures);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(2); });

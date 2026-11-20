// The re-upload card (decided 2026-10-02, #7): a new version of a document
// already in the graph is recognised, and each topic written from the old
// version gets a «patch or material?» card. «Material» stales only that
// topic's material; «patch» stales nothing; «separate document» asks nothing.
// Usage: make engines; serve public/ on :8291; node test/reupload-check.js
const puppeteer = require('puppeteer-core');
const G = require('./lib/graphs');
const fs = require('fs');
const os = require('os');
const path = require('path');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'reupload-'));
const v2 = path.join(dir, 'Учебник SQL для аналитиков v2.md');
fs.writeFileSync(v2, '# Учебник SQL, второе издание\n\nGROUP BY теперь объясняется иначе: сначала WHERE, потом группы.\n');
const pg2 = path.join(dir, 'Документация PostgreSQL 16 — Queries (2).md');
fs.writeFileSync(pg2, '# Другой документ с похожим именем\n\nНичего общего с первым.\n');
// German version words (and a bare «final») mark a version too — up front,
// where no prefix match can hide them
const de3 = path.join(dir, 'Fassung 3 Учебник SQL для аналитиков.md');
fs.writeFileSync(de3, '# Dritte Fassung\n\nWHERE vor GROUP BY.\n');
const fin = path.join(dir, 'final_Учебник SQL для аналитиков.md');
fs.writeFileSync(fin, '# Final\n\nEndgültig.\n');
// a longer name that only STARTS like one here is another document (corpus
// test 2026-10-21: «procurement-consultants.md» was asked «a new version of
// procurement.md?»); one more word is still a version («… обновлённый»)
const longer = path.join(dir, 'Учебник SQL для аналитиков — приложение про оконные функции.md');
fs.writeFileSync(longer, '# Приложение\n\nОконные функции.\n');
const other = path.join(dir, 'Посторонний файл.md');
fs.writeFileSync(other, '# Посторонний\n\nТекст.\n');

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome',
    headless: 'new', args: ['--no-sandbox', '--disable-gpu'],
    // non-ASCII fixture paths need a UTF-8 locale (cloud sessions set no LANG)
    env: { ...process.env, LANG: process.env.LANG || 'C.UTF-8' },
  });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  page.on('dialog', (d) => d.accept(d.type() === 'prompt' ? 'Чужой граф' : undefined));
  await page.setViewport({ width: 1280, height: 900 });
  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');
  const log = () => page.evaluate(() => loretileLogs());
  const upload = async (file) => {
    const input = await page.$('.chat-attach input[type=file]');
    await input.uploadFile(file);
    await sleep(1200);
  };
  const clickCard = (sel, titleRe, btn) => page.evaluate((sel, src, btn) => {
    const c = [...document.querySelectorAll(sel)].find((x) => new RegExp(src).test(x.textContent));
    if (!c) return false;
    c.querySelector(btn).click(); return true;
  }, sel, titleRe, btn);

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
  });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(400);
  await page.click('.load-demo'); await sleep(1500);
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' }); await sleep(400);

  await upload(v2);
  const q = await text('.card-version');
  check(/Учебник SQL для аналитиков v2\.md/.test(q) && /новая версия «Учебник SQL для аналитиков»/.test(q),
    'a document named like one already here is asked about: is it a new version?');
  await page.click('.card-version .version-yes'); await sleep(400);
  const n = await page.$$eval('.card-reversion', (els) => els.length);
  check(n > 0, 'yes: topics written from the old version each get a «patch or material?» card (' + n + ' shown)');
  check((await text('.chat .msgs')).match(/По старой версии «Учебник SQL для аналитиков» написано тем: (\d+)/),
    'the chat says how many topics were written from the old version');

  // by topic id: «GROUP BY» also appears in other topics' material snippets
  const gbCard = await page.$('.card-reversion[data-concept="group-by"]');
  check(gbCard !== null, 'GROUP BY has a card');
  await page.click('.card-reversion[data-concept="group-by"] .reversion-material');
  await sleep(300);
  check(/«GROUP BY[^»]*» устарела\. Дальше: примите материал из новой версии, затем снимите старый материал/.test(await text('.chat .msgs')),
    '«the meaning changed» says what to do next, or the topic stays stale');
  const patched = await page.$eval('.card-reversion b', (e) => e.textContent).catch(() => '');
  const patchedId = await page.$eval('.card-reversion', (e) => e.dataset.concept).catch(() => '');
  await page.click('.card-reversion[data-concept="' + patchedId + '"] .reversion-patch');
  check(!!patchedId, patched + ': «just corrections»');
  await sleep(300);

  // events are maps: match each as a set of fields, whatever their order
  const evs = (await log()).split('{:ev/id').filter((e) => e.includes(':source-updated'));
  check(evs.some((e) => /"src-sql-book"/.test(e) && /:change :material/.test(e) && /:concepts \["group-by"\]/.test(e)),
    'material is recorded for that one topic, against the OLD source');
  check(evs.some((e) => /"src-sql-book"/.test(e) && /:change :patch/.test(e) && e.includes('"' + patchedId + '"')), 'patch is recorded too — the decision is kept');

  await page.goto(BASE + '#/c/group-by', { waitUntil: 'networkidle0' }); await sleep(700);
  // the topic's own panel, not the map behind it (other nodes carry labels)
  check(/устарел/.test(await text('.gpopup')), 'GROUP BY now shows stale');
  await page.goto(BASE + '#/c/' + patchedId, { waitUntil: 'networkidle0' }); await sleep(700);
  check(patchedId && !/устарел/.test(await text('.gpopup')), 'the topic answered «patch» stays current (' + patchedId + ')');

  // a separate document with a similar name: asked, answered no, nothing more
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' }); await sleep(400);
  const before = await page.$$eval('.card-reversion', (els) => els.length);
  await upload(pg2);
  check(/Документация PostgreSQL 16/.test(await text('.card-version')), 'a similarly named document is asked about too');
  await page.click('.card-version .version-no'); await sleep(400);
  check((await page.$$eval('.card-reversion', (els) => els.length)) === before, '«separate document» lays out no cards');
  check(!(await log()).split('{:ev/id').some((e) => e.includes(':source-updated') && e.includes('"src-pg-docs"')), 'and records no change to the other source');

  for (const [f, name] of [[de3, 'Fassung 3 '], [fin, 'final_']]) {
    await upload(f);
    check((await text('.card-version')).includes(name + 'Учебник SQL для аналитиков.md'), 'a German/bare version word is recognised: «' + name + '»');
    await page.click('.card-version .version-no'); await sleep(300);
  }

  const versionCards = async () => page.$$eval('.card-version', (els) => els.length);
  await upload(longer);
  check((await versionCards()) === 0, 'a longer name that only starts like one here is NOT asked about (another document)');

  // undecided cards of THIS graph: another graph must not take the sitting over
  const left = await page.$$eval('.card-reversion', (els) => els.length);
  check(left > 0, 'undecided cards remain in the sitting (' + left + ')');
  await G.newGraph(page, BASE, 'Чужой граф');
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' }); await sleep(400);
  check(await page.$('.card-foreign') !== null, 'in another graph the cards are flagged as foreign');
  await upload(other);
  check(/В этой беседе есть неразобранные предложения для графа «[^»]+»/.test(await text('.chat .msgs')), 'attaching a file there is refused, pointing at «Открыть граф» or a new conversation');
  check(await page.$('.card-foreign') !== null && await page.$eval('.card-reversion .reversion-material', (b) => b.disabled),
    'and the other graph\'s cards stay unacceptable here');
  check(!/:source-added/.test(await page.evaluate(() => loretileLog())), 'nothing was written into the new graph');

  await browser.close();
  fs.rmSync(dir, { recursive: true, force: true });
  console.log(failures ? 'REUPLOAD CHECK FAILED' : 'REUPLOAD CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

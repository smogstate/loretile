// «I want to learn X» with no course (Assistant design #4): the catalog
// assistant offers to build one from the learner's own material — a file
// becomes a private graph, the intake drafts topics, choice questions are
// drafted (malformed ones dropped), the course is assembled from ready topics
// and opens. What the learner accepts there is `generated` (ruling 2026-10-02).
// Usage: make engines; serve public/ on :8291; node test/build-check.js
const puppeteer = require('puppeteer-core');
const fs = require('fs');
const os = require('os');
const path = require('path');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'build-'));
const FILE = path.join(dir, 'Заметки об обжарке.md');
fs.writeFileSync(FILE, '# Обжарка кофе\n\nСветлая обжарка кислее, тёмная горче. Первый крэк — около 196 °C.\n');
const EMPTY = path.join(dir, 'Пусто.md');
fs.writeFileSync(EMPTY, '  \n\t\n');
const DOCX = path.join(dir, 'Заметки.docx');
fs.writeFileSync(DOCX, 'PK\u0003\u0004 not text');
// nine topics: questions are drafted four topics per reply (a reply is capped)
const EXTRA = ['t1', 't2', 't3', 't4', 't5', 't6', 't7'];

const REPLIES = {
  guide: [
    { tools: [{ name: 'search-catalog', args: { query: 'обжарка кофе' } }] },
    { note: 'Такого курса пока нет — соберём его из ваших материалов.', actions: [{ name: 'build-from-material', args: { goal: 'Обжарка кофе' } }] },
  ],
  intake: [{
    concepts: [
      { id: 'roast-levels', title: 'Степени обжарки', definition: 'Светлая, средняя и тёмная обжарка различаются временем и вкусом.' },
      { id: 'first-crack', title: 'Первый крэк', definition: 'Щелчок зёрен около 196 °C — граница светлой обжарки.' }],
    objects: [
      { concept: 'roast-levels', kind: 'explanation', level: 'novice', text: '==Чем дольше обжарка, тем меньше кислотности и больше горечи.==' },
      { concept: 'first-crack', kind: 'explanation', level: 'novice', text: '==Первый крэк — сигнал, что светлая обжарка готова.==' }],
    questions: [], note: 'Темы из ваших заметок.' }],
};
for (const t of EXTRA) {
  REPLIES.intake[0].concepts.push({ id: t, title: 'Тема ' + t, definition: 'Определение темы ' + t + '.' });
  REPLIES.intake[0].objects.push({ concept: t, kind: 'explanation', level: 'novice', text: '==Суть темы ' + t + '.==' });
}
// the questions mock answers per batch: three per asked topic; the batch
// holding t3 answers garbage; the first batch adds one malformed question and
// one for a topic it was NOT asked about (both dropped)
const questionsFor = (user) => {
  const Q = (concept, stem, options, key) => ({ concept, stem, options, key });
  const ids = [...user.matchAll(/Тема (\S+):/g)].map((m) => m[1]);
  if (ids.includes('t3')) return 'not json at all';
  const qs = [];
  for (const id of ids) {
    if (id === 'roast-levels') qs.push(Q(id, 'Какая обжарка самая кислая?', ['Светлая', 'Средняя', 'Тёмная'], 0),
      Q(id, 'Что растёт при более долгой обжарке?', ['Кислотность', 'Горечь', 'Влажность'], 1),
      Q(id, 'Чем различаются степени обжарки?', ['Сортом зерна', 'Временем и вкусом', 'Помолом'], 1));
    else if (id === 'first-crack') qs.push(Q(id, 'При какой температуре примерно наступает первый крэк?', ['100 °C', '196 °C', '300 °C'], 1),
      Q(id, 'Что означает первый крэк?', ['Зерно сгорело', 'Светлая обжарка готова', 'Пора молоть'], 1),
      Q(id, 'Первый крэк — это…', ['Звук', 'Цвет', 'Запах'], 0));
    else for (let k = 1; k <= 3; k++) qs.push(Q(id, 'Вопрос ' + k + ' по ' + id, ['да ' + k, 'нет ' + k, 'не знаю ' + k], 0));
  }
  if (ids.includes('first-crack')) qs.push(Q('first-crack', 'Сломанный вопрос', ['Только один вариант'], 0), Q('t7', 'Не спрашивали', ['а', 'б', 'в'], 0));
  return JSON.stringify({ questions: qs });
};

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome',
    headless: 'new', args: ['--no-sandbox', '--disable-gpu'],
    // non-ASCII fixture paths need a UTF-8 locale (cloud sessions set no LANG)
    env: { ...process.env, LANG: process.env.LANG || 'C.UTF-8' },
  });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  page.on('dialog', (d) => d.accept());
  await page.setViewport({ width: 1280, height: 900 });
  await page.evaluateOnNewDocument((R, F) => {
    const orig = window.fetch.bind(window);
    window.fetch = (url, opts) => {
      if (String(url).includes('api.anthropic.com')) {
        const body = JSON.parse(opts.body);
        const sys = body.system || '';
        if (/проверочных вопроса/.test(sys)) {
          window.__qcalls = (window.__qcalls || 0) + 1;
          return Promise.resolve(new Response(JSON.stringify({ content: [{ type: 'text', text: (0, eval)('(' + F + ')')(body.messages[0].content) }] }), { status: 200 }));
        }
        const q = /помощник ученика/.test(sys) ? R.guide : /учебные концепты/.test(sys) ? R.intake : null;
        const reply = q ? (q.length > 1 ? q.shift() : q[0]) : { edges: [] };
        return Promise.resolve(new Response(JSON.stringify({ content: [{ type: 'text', text: JSON.stringify(reply) }] }), { status: 200 }));
      }
      return orig(url, opts);
    };
  }, REPLIES, questionsFor.toString());
  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');
  const log = () => page.evaluate(() => loretileLogs());
  // one segment per [:node-set "id" {props}] op; `cls` keeps those whose :is is that class
  const nodeOps = (l, cls) => l.split('[:node-set ').slice(1).filter((o) => o.includes(':is :' + cls));

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
    localStorage.setItem('loretile-llm-config', '{:preset :anthropic :shape :anthropic :url "https://api.anthropic.com/v1/messages" :model "test" :key "sk-test"}');
  });
  await page.goto(BASE + '#/courses', { waitUntil: 'networkidle0' }); await page.reload({ waitUntil: 'networkidle0' }); await sleep(500);

  const ask = async () => {
    await page.type('.guide .composer textarea', 'хочу научиться обжаривать кофе');
    await page.keyboard.press('Enter'); await sleep(1400);
  };
  const pick = async (file) => {
    const [chooser] = await Promise.all([page.waitForFileChooser({ timeout: 5000 }), page.click('.guide .card-action .action-do')]);
    await chooser.accept([file]); await sleep(800);
  };
  const graphs = () => page.evaluate(() => localStorage.getItem('loretile-graphs') || '');

  await ask();
  check(/Собрать курс «Обжарка кофе» из своего материала/.test(await text('.guide .card-action')),
    'no course fits: the assistant offers to build one from the learner\'s own material');
  // the file is read FIRST: an empty file, or one that is not text, creates nothing
  await pick(EMPTY);
  check(/#\/courses/.test(page.url()) && /В файле «Пусто\.md» нет текста/.test(await text('.guide .msgs')),
    'an empty file: the guide says so and stays put');
  check(!/:personal\? true/.test(await graphs()), 'and no graph was created for it');
  await ask(); await pick(DOCX);
  check(/Не смог прочитать «Заметки\.docx»: файл Word повреждён/.test(await text('.guide .msgs')) && !/:personal\? true/.test(await graphs()),
    'a damaged .docx: refused in words, nothing created');

  await ask(); await pick(FILE);
  for (let i = 0; i < 60 && (await page.$$('.cards .card')).length < 2; i++) await sleep(200);
  await sleep(500);
  check(/#\/chat/.test(page.url()), 'the tap opened a file picker; the file goes into the assistant');
  const reg = await graphs();
  check(/:title "Обжарка кофе"/.test(reg) && /:personal\? true/.test(reg), 'it became a private graph of its own');
  check(/личный граф «Обжарка кофе»/.test(await text('.chat .msgs')), 'and the chat says so');
  check(await page.$('.chat .chat-head .mode-badge.learner') !== null && await page.$('.chat .chat-head .mode-badge.author') === null,
    'the learner is not put in the author\'s chair: the chat wears the learner badge');
  check(/Сначала примите хотя бы одну тему/.test(await text('.card-build')) && await page.$eval('.cards .card', (e) => e.classList.contains('card-build')),
    'the «Your course» step is pinned above the pile and says topics come first');
  check(await page.$('.card-drafts mark.mk-essence') !== null && !/==/.test(await text('.card-drafts')),
    'material drafted inside a topic card shows its marks as highlights, not raw ==');

  // accept the topics (their material rides along), five on show at a time
  for (let i = 0; i < 20; i++) {
    const done = await page.evaluate(() => {
      const c = [...document.querySelectorAll('.cards .card')].find((x) => /Степени обжарки|Первый крэк|Тема t\d/.test(x.querySelector('b')?.textContent || ''));
      if (!c) return false; c.querySelector('button.primary').click(); return true;
    });
    if (!done) break; await sleep(250);
  }
  const l1 = await log();
  const objs = nodeOps(l1, 'object');
  check(objs.length === 9 && objs.every((e) => /:origin :generated/.test(e)), 'material accepted in a learner\'s own graph is «generated» (no curator vetted it) (' + objs.length + ')');

  check(/вопросы для тем: 9/.test(await text('.card-build')), 'the «Your course» step asks for check questions for all nine topics');
  await page.click('.card-build .build-questions');
  for (let i = 0; i < 60 && !/Составлено вопросов/.test(await text('.chat .msgs')); i++) await sleep(200);
  await sleep(300);
  check(await page.evaluate(() => window.__qcalls) === 3, 'drafted in batches of four topics: three replies');
  const cm = await text('.chat .msgs');
  check(/часть 2 из 3 — Ошибка:/.test(cm), 'the batch that answered garbage is reported, and costs only its own topics');
  check(/Составлено вопросов: 15 \(отброшено с ошибкой формы: 2\)/.test(cm),
    'the other batches\' questions arrive; a malformed one and one for a topic not asked about are dropped');
  check(await page.$eval('.cards .card', (e) => e.classList.contains('card-build')) && (await page.$$('.card-item-draft')).length === 5,
    'with fifteen drafts in the pile, the «Your course» card stays pinned on top');
  for (let i = 0; i < 20; i++) {
    const c = await page.$('.card-item-draft button.primary');
    if (!c) break; await c.click(); await sleep(200);
  }
  const items = nodeOps(await log(), 'item');
  check(items.length === 15 && items.every((e) => /:origin :generated/.test(e) && /:level :novice/.test(e) && /:kind :single-choice/.test(e)),
    'accepted questions are «generated», at the level of the topic\'s material (' + items.length + ')');

  const bt = await text('.card-build');
  check(/Собрать курс из готовых тем \(5\) и начать/.test(bt) && /вопросы для тем: 4/.test(bt),
    'five topics are ready: the course can be built now, alongside questions for the other four');
  await page.click('.card-build .build-course'); await sleep(800);
  check(/#\/learn\/c-/.test(page.url()) && /Обжарка кофе/.test(await text('.learn')), 'the course is assembled and opens');
  const course = nodeOps(await log(), 'course')[0] || '';
  check(/\["first-crack" :novice\]/.test(course) && /\["roast-levels" :novice\]/.test(course) && /\["t7" :novice\]/.test(course) && !/"t3"/.test(course),
    'its targets are the ready topics at their level');
  await page.click('.learn .start'); await sleep(400);
  check(/Какая обжарка|температуре|первый крэк|Что растёт|Чем различаются|это…|Вопрос \d по/i.test(await text('.qcard:not(.done) .stem')), 'and the learner\'s own questions run in the diagnostic');

  await browser.close();
  fs.rmSync(dir, { recursive: true, force: true });
  console.log(failures ? 'BUILD CHECK FAILED' : 'BUILD CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

// Course composition end-to-end: seed a small KG into localStorage, open
// «Новый курс», pick two targets with levels, publish → course-published
// lands in the log, the course panel shows per-target readiness (level-
// aware) and the out-of-course prerequisite, the map dims everything else.
// Usage: serve public/, then: node test/course-check.js [baseUrl]

const puppeteer = require('puppeteer-core');
const BASE = process.argv[2] || 'http://localhost:8291';
const CHROME = process.env.CHROME_BIN || '/usr/bin/google-chrome';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

// select ← where ← group-by (requires); group-by has 1 LO + 3 items at apprentice
const ev = (n, body) => `{:ev/id "e${n}" :ev/ns "demo" :ev/actor "t" :ev/ts ${n} ${body}}`;
const concept = (id, t) => `:ev/type :concept-accepted :concept {:id "${id}" :title "${t}" :definition "${t} — определение"}`;
const item = (id) => `:ev/type :item-added :item {:id "${id}" :primary-concept-id "group-by" :level :apprentice :kind :cloze :locale "ru" :spec {:text "___" :key "x"}}`;
const SEED = '[' + [
  ev(1, concept('select', 'SELECT')),
  ev(2, concept('where', 'WHERE')),
  ev(3, concept('group-by', 'GROUP BY')),
  ev(4, ':ev/type :edge-accepted :edge {:from "where" :to "select" :type :requires :provenance :authored}'),
  ev(5, ':ev/type :edge-accepted :edge {:from "group-by" :to "where" :type :requires :provenance :authored}'),
  ev(6, ':ev/type :object-added :object {:id "o1" :concept-id "group-by" :kind :explanation :level :apprentice :locale "ru" :origin :authored :text "..."}'),
  ev(7, item('i1')), ev(8, ':ev/type :item-accepted :id "i1"'),
  ev(9, item('i2')), ev(10, ':ev/type :item-accepted :id "i2"'),
  ev(11, item('i3')), ev(12, ':ev/type :item-accepted :id "i3"'),
].join(' ') + ']';

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--disable-gpu', '--lang=ru'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });

  // mock the LLM: the course intake asks for targets; answer with two of the seeded concepts
  const MOCK = { targets: [
    { id: 'group-by', level: 'apprentice', why: 'ядро аналитики' },
    { id: 'where', level: 'apprentice', why: 'нужен для фильтрации' },
    { id: 'nope', level: 'novice', why: 'не существует — должен быть отброшен' },
  ], unmatched: ['оконные функции'], note: 'Подобрал 2 темы.' };
  await page.evaluateOnNewDocument((mock) => {
    const orig = window.fetch.bind(window);
    window.__llmCalls = [];
    window.fetch = (url, opts) => {
      if (String(url).includes('api.anthropic.com')) {
        window.__llmCalls.push(JSON.parse(opts.body));
        return Promise.resolve(new Response(JSON.stringify({
          content: [{ type: 'text', text: JSON.stringify(mock) }] }), { status: 200 }));
      }
      return orig(url, opts);
    };
  }, MOCK);

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate((seed) => {
    localStorage.clear();
    localStorage.setItem('loretile-locale', 'ru'); localStorage.setItem('loretile-tour-done','1'); localStorage.setItem('loretile-chat-tour-done','1'); localStorage.setItem('loretile-learn-tour-done','1');
    localStorage.setItem('loretile-kg-log', seed);
    localStorage.setItem('loretile-llm-config',
      '{:preset :anthropic :shape :anthropic :url "https://api.anthropic.com/v1/messages" :model "test" :key "sk-test"}');
  }, SEED);
  await page.goto(BASE + '#/course/new', { waitUntil: 'networkidle0' });
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(300);

  check((await page.$$('.crow')).length === 3, 'course form lists the 3 concepts');
  check(await page.$('.rail .rbtn[title="Курс"]') !== null, 'rail has the Курс button');

  // course intake: describe the course → target cards → accept
  await page.type('.course-intake textarea', 'Курс SQL для аналитиков: группировка и фильтрация, 2 часа');
  await page.click('.course-intake button.primary');
  await sleep(400);
  const sys = await page.evaluate(() => window.__llmCalls[0].system);
  check(/Темы базы \(3 из 3\)/.test(sys) && /group-by — GROUP BY/.test(sys), 'candidates block sent, not a dump');
  const typed = await page.evaluate(() => window.__llmCalls[0].messages[0].content);
  check(typed === 'Курс SQL для аналитиков: группировка и фильтрация, 2 часа', 'fast typing loses no characters');
  check((await page.$$('.course-intake .card')).length === 2, 'unknown target dropped: 2 target cards');
  check(/Нет в базе: оконные функции/.test(await page.$eval('.course-intake .msgs', (e) => e.textContent)),
    'unmatched aspects reported');
  const accepts = await page.$$('.course-intake .card button.primary');
  for (const b of accepts) { await b.click(); await sleep(80); }
  await sleep(100);
  check((await page.$$('.course-intake .card')).length === 0, 'target cards resolved');
  const ticked = await page.$$eval('.crow input[type=checkbox]', (els) => els.filter((e) => e.checked).length);
  check(ticked === 2, 'accepted targets ticked in the form');
  await page.screenshot({ path: 'test/shot-course-intake.png' });

  await page.type('.panel input[placeholder="Название курса"]', 'SQL для аналитиков');
  await sleep(100);
  const badges = await page.$$eval('.crow .badge', (els) => els.map((e) => e.textContent));
  check(badges.length === 2 && badges.includes('готова') && badges.includes('не готова'),
    'per-target readiness at chosen level (group-by ready, where not)');
  check(/2 целей, готовы 1/.test(await page.$eval('.course-summary', (e) => e.textContent)),
    'summary counts targets and readiness');
  check(/пререквизит/.test(await page.$eval('.course-summary', (e) => e.textContent)),
    'summary warns about the out-of-course prerequisite (select)');
  await page.screenshot({ path: 'test/shot-course-form.png' });

  // the gate: `where` is not ready at the chosen level, so this course cannot
  // be published — a course that cannot examine a target can only ever issue
  // an incomplete verdict
  check(await page.$eval('.course-summary button.primary', (e) => e.disabled),
    'a course with an unexaminable target cannot be published');
  check(/Нельзя издать/.test(await page.$eval('.course-summary', (e) => e.textContent)),
    'and the form says which target blocks it');

  // drop the unready target and publish what can actually be examined
  await page.evaluate(() => {
    const row = [...document.querySelectorAll('.crow')].find((x) => /WHERE/.test(x.textContent));
    row.querySelector('input[type=checkbox]').click();
  });
  await sleep(300);
  check(await page.$eval('.course-summary button.primary', (e) => !e.disabled), 'dropping it unblocks publishing');
  await page.click('.course-summary button.primary');
  await sleep(300);
  { const h = await page.evaluate(() => location.hash); check(/#\/course\/sql-/.test(h), 'publish navigates to the course [' + h + ']'); }
  check(/course-published/.test(await page.evaluate(() => loretileLog())),
    'course-published event in the log');
  check(/1 \/ 1 готовы/.test(await page.$eval('.panel-head', (e) => e.textContent)), 'course panel: every target ready');
  check(/порог сдачи 80%/.test(await page.$eval('.panel', (e) => e.textContent)), 'threshold shown');
  check(/Пререквизиты вне курса \(2\)/.test(await page.$eval('.panel', (e) => e.textContent)),
    'closure lists WHERE and SELECT as prerequisites outside the course');
  check((await page.$$('.panel .crow .badge')).length === 3, 'prerequisite row has its own readiness badge');
  check(/язык русский/.test(await page.$eval('.panel', (e) => e.textContent)), 'course locale shown');

  await sleep(600); // physics settle
  check((await page.$$('.node.in-target')).length === 1, 'map: the single target is ringed');
  check((await page.$$('.node.in-closure')).length === 2, 'map: its two prerequisites are dashed');
  check((await page.$$('.node.dim')).length === 0, 'map: nothing dimmed (all 3 in scope)');
  await page.screenshot({ path: 'test/shot-course.png' });
  await page.setViewport({ width: 390, height: 844 });
  await sleep(200);
  await page.screenshot({ path: 'test/shot-course-mobile.png' });

  await browser.close();
  console.log(failures === 0 ? 'COURSE CHECK OK' : 'COURSE CHECK FAILED: ' + failures);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(2); });

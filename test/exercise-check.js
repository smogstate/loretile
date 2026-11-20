// Exercises (wiki/Exercises.md): an answer that is EXECUTED in real Postgres
// (PGlite) in the browser. Walks the demo SQL course to the WHERE exercise
// twice: once answering wrong (explained, solution shown), once right with a
// query shaped differently from the reference (closes the topic).
// Usage: make pglite; serve public/ on :8291; node test/exercise-check.js
const puppeteer = require('puppeteer-core');
const fs = require('fs');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

// stem → correct option text / cloze key / exercise solution, from the demo log
const KEY = {};
const unq = (s) => JSON.parse('"' + s + '"');
const logs = ['log.edn', 'vineyard.ru.edn'].map((f) => fs.readFileSync(__dirname + '/../kg/demo/' + f, 'utf8')).join('\n');
for (const line of logs.split('\n')) {
  const ex = line.match(/:kind :exercise .*:stem "((?:[^"\\]|\\.)*)" .*:solution "((?:[^"\\]|\\.)*)"/);
  if (ex) { KEY[unq(ex[1])] = { solution: unq(ex[2]) }; continue; }
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
  // no LLM key: a miss must still explain itself (the fallback shows the solution)
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
  });
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(300);
  await page.click('.load-demo');
  await sleep(1500);

  const LIVE = '.qcard:not(.done)';
  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');
  const stage = () => page.$eval('.learn', (e) => e.dataset.stage).catch(() => '');
  const liveTopic = () => text(LIVE + ' .qtopic');
  const feedText = () => text('.feed');
  const isExercise = async () => (await page.$(LIVE + '.exercise')) !== null;
  // Run, then wait for the output to change (the first run may wait for the engine to boot)
  const outHtml = () => page.$eval(LIVE + ' .ex-out', (e) => e.outerHTML).catch(() => '');
  const runCode = async (code) => {
    await typeCode(code);
    const before = await outHtml();
    await page.click(LIVE + ' .ex-run');
    for (let i = 0; i < 600 && (await outHtml()) === before; i++) await sleep(100);
  };
  const typeCode = async (code) => {
    await page.$eval(LIVE + ' textarea.ex-code', (e) => { e.value = ''; });
    await page.type(LIVE + ' textarea.ex-code', code);
  };
  const submitCode = async (code) => {
    await typeCode(code);
    const before = (await page.$$('.qcard.done')).length;
    await page.click(LIVE + ' .ex-submit');
    // the first execution boots Postgres in the page: seconds, not millis
    for (let i = 0; i < 600 && (await page.$$('.qcard.done')).length === before; i++) await sleep(100);
    await sleep(300);
  };
  const answer = async (right) => {
    const stem = await text(LIVE + ' .stem');
    const key = KEY[stem];
    if (await isExercise()) return submitCode(right ? key.solution : 'SELECT 1');
    if (await page.$(LIVE + ' .options')) {
      const opts = await page.$$eval(LIVE + ' .opt', (els) => els.map((e) => e.textContent));
      const i = right ? opts.indexOf(key) : opts.findIndex((o) => o !== key);
      await page.evaluate((i, sel) => document.querySelectorAll(sel + ' .opt')[i].click(), i, LIVE);
    } else {
      await page.type(LIVE + ' input', right ? key : 'нет');
      await page.click(LIVE + ' button.primary');
    }
    await sleep(150);
  };
  const readyUp = async () => {
    for (const sel of ['.check-me', '.continue']) {
      const els = await page.$$(sel);
      if (els.length) { await els[els.length - 1].click(); await sleep(250); return true; }
    }
    return false;
  };
  const clickLive = async (sel) => { const els = await page.$$(sel); await els[els.length - 1].click(); await sleep(300); };
  // diagnostic: everything right except WHERE, so WHERE is the only gap to learn
  const toWhereTopic = async () => {
    await page.click('.learn .start'); await sleep(300);
    let n = 0;
    while ((await stage()) === 'diagnostic' && n++ < 20) await answer(!/WHERE/.test(await liveTopic()));
    await clickLive('.plan-card button.primary');
    // the scratchpad: the course's own tables, no check
    await page.click('.scratch-toggle'); await sleep(200);
    await page.type('.scratch textarea.ex-code', 'SELECT count(*) FROM orders');
    await page.click('.scratch .ex-run');
    for (let i = 0; i < 300 && !(await text('.scratch .ex-out')); i++) await sleep(100);
    check(/7/.test(await text('.scratch .ex-out td')), 'SQL scratchpad: queries run against the course\'s own tables');
    await readyUp();
  };

  await page.goto(BASE + '#/learn/sql-analyst', { waitUntil: 'networkidle0' });
  await sleep(500);
  check(await page.$('.learn .start') !== null, 'the course opens');

  // --- run A: answer the exercise WRONG -------------------------------------
  await toWhereTopic();
  check((await stage()) === 'learn' && /WHERE/.test(await liveTopic()), 'learning WHERE, a check is live');
  await answer(true);                       // i-where-2
  if (await page.$(LIVE)) await answer(false); else { await readyUp(); await answer(false); }   // i-where-3, explained
  await sleep(300);
  await readyUp();                          // «next» after the explanation
  check(await isExercise(), 'the WHERE exercise is dealt as a check');
  check(/customers\(id, name, city\)/.test(await text(LIVE + ' .ex-schema')), 'it shows the tables it runs against');

  // Run is the exercise's own button: shows rows, records nothing
  const attemptsBefore = await page.evaluate(() => (localStorage.getItem('loretile-attempts') || '').length);
  await runCode('SELECT * FROM customers');
  const rows = await page.$$eval(LIVE + ' .ex-out tbody tr', (els) => els.length).catch(() => 0);
  const head = await page.$$eval(LIVE + ' .ex-out th', (els) => els.map((e) => e.textContent)).catch(() => []);
  check(rows === 5 && head.join(',') === 'id,name,city', 'Run executes in Postgres and shows the rows (' + rows + ' rows, ' + head + ')');
  check(await page.evaluate(() => (localStorage.getItem('loretile-attempts') || '').length) === attemptsBefore,
    'Run is not an attempt: the log is untouched');
  // the learner cannot end the transaction the run lives in
  await runCode('commit');
  check(/транзакцией/.test(await text(LIVE + ' .ex-out')), 'transaction control is refused with a reason');
  // a destructive run is rolled back: the seed survives
  await runCode('DELETE FROM customers');
  await runCode('SELECT count(*) FROM customers');
  check(/5/.test(await text(LIVE + ' .ex-out td')), 'a DELETE in a run is rolled back, the seed survives');
  // a syntax error is the learner's, shown as Postgres reports it
  await runCode('SELEC name FROM customers');
  check(/syntax error/.test(await text(LIVE + ' .ex-out')), 'a syntax error comes back as Postgres words it');
  // the leading-comment trick is still transaction control
  await runCode('/* x /* nested */ */ commit');
  check(/транзакцией/.test(await text(LIVE + ' .ex-out')), 'a COMMIT behind comments is refused too');
  // ATTACK (review 2026-10-02 #3): a query that never ends froze the tab —
  // PGlite ran on the page's thread. Now it is a worker, killed at 10 s; a
  // timer in the page keeps firing meanwhile
  await page.evaluate(() => { window.__ticks = 0; window.__tick = setInterval(() => window.__ticks++, 100); });
  const tCte = Date.now();
  await runCode('WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r) SELECT count(*) FROM r');
  const cteMs = Date.now() - tCte;
  const ticks = await page.evaluate(() => { clearInterval(window.__tick); return window.__ticks; });
  check(/дольше 10 секунд/.test(await text(LIVE + ' .ex-out')) && cteMs < 30000,
    'a looping recursive CTE comes back as a timeout, in Python\'s words (' + cteMs + ' ms)');
  check(ticks > 50, 'and the page stayed responsive meanwhile (' + ticks + ' timer ticks)');
  await runCode('SELECT count(*) FROM customers');
  check(/5/.test(await text(LIVE + ' .ex-out td')), 'the next SQL run works on a rebuilt worker');

  await submitCode('SELECT name FROM customers');
  check(/Неверно/.test(await feedText()), 'a wrong query is graded wrong');
  check(/SELECT name FROM customers WHERE city = 'Moscow'/.test(await feedText()),
    'without a tutor key the miss still explains itself with the reference solution');
  check(/SELECT name FROM customers/.test(await text('.qcard.exercise.done pre.ex-code')),
    'the answered card keeps the query that was submitted');
  const logA = await page.evaluate(() => localStorage.getItem('loretile-attempts'));
  check(logA.split('{').some((m) => m.includes(':item "i-where-4"') && m.includes(':correct? false')), 'the attempt is logged against the exercise, incorrect');

  // --- run B: answer it RIGHT, shaped differently from the reference ---------
  // forget run A's attempts (the app holds the log in memory: reload after clearing)
  await page.evaluate(() => localStorage.removeItem('loretile-attempts'));
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(600);
  await page.click('.learn-head .restart'); await sleep(400);
  // the restart began a fresh diagnostic; walk it the same way
  let n = 0;
  while ((await stage()) === 'diagnostic' && n++ < 20) await answer(!/WHERE/.test(await liveTopic()));
  await clickLive('.plan-card button.primary');
  await readyUp();
  await answer(false);                      // i-where-2, explained
  await sleep(300);
  await readyUp();
  await answer(true);                       // i-where-3
  if (!(await page.$(LIVE))) await readyUp();
  check(await isExercise(), 'the exercise is dealt again in a fresh run');
  await submitCode("select c.name from customers as c where c.city in ('Moscow')");
  check(/закрыта/.test(await feedText()), 'a right query of a different shape passes, and two in a row close the topic');
  const logB = await page.evaluate(() => localStorage.getItem('loretile-attempts'));
  check(logB.split('{').some((m) => m.includes(':item "i-where-4"') && m.includes(':correct? true')), 'logged correct');

  // --- AUTHORING: the runtime judges the author's solution before saving ------
  await page.goto(BASE + '#/c/having', { waitUntil: 'networkidle0' });
  await sleep(700);
  const nItems = () => page.$$eval('.item', (els) => els.length);
  const before = await nItems();
  const addBtns = await page.$$('.addbtn');
  await addBtns[addBtns.length - 1].click();          // «+ question», the last add button
  await sleep(200);
  await page.select('.form select', 'exercise');
  await sleep(200);
  check(await page.$eval('.form select.ex-runtime', (e) => e.value) === 'python', 'a new exercise defaults to Python, not SQL');
  await page.select('.form select.ex-runtime', 'pglite');
  await sleep(200);
  const prefilled = await page.$eval('.form textarea.ex-setup', (e) => e.value);
  check(/create table customers/.test(prefilled), 'a new exercise starts from the seed this graph already uses');
  await page.type('.form input', 'Города, где сумма заказов больше 200');
  const setSol = async (code) => {
    await page.$eval('.form textarea.ex-solution', (e) => { e.value = ''; });
    await page.type('.form textarea.ex-solution', code);
  };
  // wait for the OUTCOME (a new message, or a new item), not for a button label to flip
  const settle = async (outSel, click) => {
    const before = [await text(outSel), await nItems()];
    await page.click(click);
    for (let i = 0; i < 300; i++) {
      await sleep(100);
      const now = await text(outSel);   // clicking clears the old message first: wait for a NEW one
      if ((now && now !== before[0]) || (await nItems()) !== before[1]) break;
    }
    await sleep(150);
  };
  const add = () => settle('.form .ex-out', '.form button.primary');
  await setSol('SELECT city FROM nope');
  await add();
  check(/Решение не выполняется/.test(await text('.form .ex-out')) && /nope/.test(await text('.form .ex-out')),
    'a solution that fails is refused with Postgres\'s reason');
  check((await nItems()) === before, 'and nothing is saved');
  await setSol("SELECT city FROM customers WHERE city = 'Atlantis'");
  await add();
  check(/пустой результат/.test(await text('.form .ex-out')), 'an empty expected result is refused (it would accept any empty answer)');
  // a broken setup is named as the setup's fault
  const goodSetup = prefilled;
  await page.$eval('.form textarea.ex-setup', (e) => { e.value = ''; });
  await page.type('.form textarea.ex-setup', 'create tabel x (a int);');
  await setSol('SELECT 1');
  await add();
  check(/Подготовка не выполняется/.test(await text('.form .ex-out')), 'a setup that does not build is named as such');
  await page.$eval('.form textarea.ex-setup', (e, v) => { e.value = ''; }, goodSetup);
  await page.evaluate((v) => {   // restore the seed through the input event, as typing would
    const ta = document.querySelector('.form textarea.ex-setup');
    const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
    set.call(ta, v); ta.dispatchEvent(new Event('input', { bubbles: true }));
  }, goodSetup);
  await setSol('SELECT c.city FROM customers c JOIN orders o ON o.customer_id = c.id GROUP BY c.city HAVING SUM(o.amount) > 200');
  await add();
  check((await nItems()) === before + 1, 'a solution that runs and returns rows is saved');
  const log = await page.evaluate(() => loretileLogs());
  const added = log.split('{:ev/id').find((e) => e.includes('Города, где сумма заказов больше 200')) || '';
  check(/:kind :exercise/.test(added) && /:schema "customers\(id, name, city\)\\norders\(id, customer_id, amount, placed\)"/.test(added),
    'it is stored as an exercise, with the table list read from Postgres' + (/:schema/.test(added) ? '' : ' — got ' + added.slice(0, 300)));

  // the pencil holds the same gate
  // items are listed by id; find the new one by its text
  await page.evaluate(() => [...document.querySelectorAll('.item')].find((e) => /больше 200/.test(e.textContent)).querySelector('.pt-edit').click());
  await sleep(300);
  await page.$eval('.item .form.edit textarea.ex-code', (e) => { e.value = ''; });
  await page.type('.item .form.edit textarea.ex-code', 'SELEC city FROM customers');
  await settle('.item .form.edit .ex-out', '.item .form.edit button.primary');
  check(/Решение не выполняется/.test(await text('.item .form.edit .ex-out')), 'editing a solution into a broken one is refused');
  check(await page.$('.item .form.edit') !== null, 'and the form stays open with the reason');

  // --- PYTHON: the same card, another runtime, on a non-IT subject -----------
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
  });
  await page.goto(BASE + '#/', { waitUntil: 'networkidle0' });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(400);
  await page.evaluate(() => [...document.querySelectorAll('button.demo-row')].find((b) => /виноград/i.test(b.textContent)).click());
  await sleep(1500);
  await page.goto(BASE + '#/learn/vineyard-season', { waitUntil: 'networkidle0' }); await sleep(500);
  await page.click('.learn .start'); await sleep(300);
  check(await page.$('.scratch-toggle') === null && await page.$('.scratch') === null, 'no scratchpad during the diagnostic');
  const pyGap = /Зрелость|Сбор урожая/;
  n = 0;
  while ((await stage()) === 'diagnostic' && n++ < 20) await answer(!pyGap.test(await liveTopic()));
  await clickLive('.plan-card button.primary');
  // --- scratchpad: try anything, nothing is recorded -------------------------
  // (the SQL walk left it open; a new graph's course starts it closed only after a reload)
  if (!(await page.$('.scratch'))) await page.click('.scratch-toggle');
  await sleep(200);
  check(/Python/.test(await text('.scratch-head')), 'the scratchpad offers the course\'s runtime: Python');
  const attemptsBeforeScratch = await page.evaluate(() => localStorage.getItem('loretile-attempts') || '');
  await page.$eval('.scratch textarea.ex-code', (e) => { e.value = ''; });
  await page.type('.scratch textarea.ex-code', 'print(sum([18, 20, 22]) / 3)');
  await page.click('.scratch .ex-run');
  for (let i = 0; i < 300 && !(await text('.scratch .ex-out')); i++) await sleep(100);
  check((await text('.scratch .ex-out')).trim() === '20.0', 'scratchpad runs Python');
  check((await page.evaluate(() => localStorage.getItem('loretile-attempts') || '')) === attemptsBeforeScratch,
    'a scratchpad run is never an attempt');
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(600);
  await page.click('.scratch-toggle'); await sleep(200);
  check(/sum\(\[18, 20, 22\]\)/.test(await page.$eval('.scratch textarea.ex-code', (e) => e.value)),
    'the learner\'s scratch code survives a reload, on this device');
  await readyUp();
  check(/Зрелость/.test(await liveTopic()), 'vineyard: learning ripeness');
  check(await page.$('.scratch') === null && await page.$('.scratch-toggle') === null,
    'the scratchpad is gone while a graded check is live');
  await answer(false); await sleep(300);                     // explained, burned
  check(await page.$('.scratch') !== null || await page.$('.scratch-toggle') !== null, 'and back once the answer is in');
  await readyUp();
  await answer(true);
  if (!(await page.$(LIVE))) await readyUp();
  check(await isExercise(), 'a Python exercise is dealt in a craft course');
  check(/print/.test(await page.$eval(LIVE + ' textarea.ex-code', (e) => e.placeholder)), 'the editor speaks Python, not SQL');
  await runCode('print(2 + 2)');
  check((await text(LIVE + ' .ex-out')).trim() === '4', 'Run executes Python and shows its output');
  await runCode('brix');
  check(/NameError/.test(await text(LIVE + ' .ex-out')), "every run starts fresh: an earlier run's names do not leak");
  const t0 = Date.now();
  await runCode('while True:\n    pass');
  check(/дольше 10 секунд/.test(await text(LIVE + ' .ex-out')) && Date.now() - t0 < 30000,
    'an endless loop is stopped, and the tab stays alive');
  // this run pays a FRESH Python boot (the loop killed the shared worker): the
  // yardstick the spare is measured against below, on this machine, under its load
  const tFresh = Date.now();
  await runCode('print("alive")');
  const freshMs = Date.now() - tFresh;
  check(/alive/.test(await text(LIVE + ' .ex-out')), 'the next run works on a fresh worker');
  // ATTACK (review 2026-10-02 #1): a Run silences print in the shared worker.
  // The verdict runs in a clean worker, so the reference still prints 13.0
  // and a right answer still passes — on the old code the reference printed
  // nothing and the comparison was poisoned.
  await runCode('import builtins\nbuiltins.print = lambda *a, **k: None');
  // a bare expression counts like a print: what the code SHOWS is compared.
  // Timed: the verdict's clean worker is the SPARE booted when the course
  // opened, so no Python boot (~1.3 s) is paid on the Answer
  const tAns = Date.now();
  await submitCode('round(22 * 0.59, 1)');
  const ansMs = Date.now() - tAns;
  // relative, not absolute: a fixed 1000 ms failed at ~1080 ms whenever the
  // machine was busy (2026-11-19); a judged answer must clearly beat a boot
  check(ansMs < freshMs * 0.8, 'a Python answer is judged without a fresh boot — the spare was ready (' + ansMs + ' ms vs a fresh boot ' + freshMs + ' ms)');
  check(/закрыта/.test(await feedText()), 'a right answer of another shape passes and closes the topic');
  check(await page.evaluate(() => (localStorage.getItem('loretile-attempts') || '').split('{').some((m) => m.includes(':item "i-vin-ripeness-4"') && m.includes(':correct? true'))),
    'logged correct against the Python exercise');

  // hidden checks: the learner never sees them, not even when failing them
  await readyUp();
  check(/Сбор урожая/.test(await liveTopic()), 'next topic: harvest');
  await answer(false); await sleep(300); await readyUp();
  await answer(true);
  if (!(await page.$(LIVE))) await readyUp();
  check(await isExercise(), 'the hidden-check exercise is dealt');
  // ATTACK (review 2026-10-02 #1, proved on the old code): a Run plants
  // `ready` in builtins, then an empty answer is submitted
  await runCode('import builtins\nbuiltins.ready = lambda brix, acid: brix >= 21 and acid <= 7');
  await submitCode('pass');
  check(/Неверно/.test(await feedText()), 'a Run that plants the function in builtins cannot make an empty answer pass');
  check(!/assert/.test(await text('.qcard.exercise.done')), 'and the hidden checks never reach the screen');

  // --- authoring in Python: the gate catches checks that cannot fail -------
  await page.goto(BASE + '#/c/harvest', { waitUntil: 'networkidle0' }); await sleep(700);
  const pyBefore = await nItems();
  const pyAdd = await page.$$('.addbtn');
  await pyAdd[pyAdd.length - 1].click(); await sleep(200);
  await page.select('.form select', 'exercise'); await sleep(200);
  await page.select('.form select.ex-check', 'assert'); await sleep(200);
  await page.type('.form input', 'Сколько ящиков по 18 кг нужно на урожай: функция crates(kg)');
  await setSol('import math\ndef crates(kg):\n    return math.ceil(kg / 18)');
  await page.type('.form textarea.ex-test', 'assert True');
  await add();
  check(/проходят и без ответа/.test(await text('.form .ex-out')), 'hidden checks that pass with no answer are refused');
  await page.$eval('.form textarea.ex-test', (e) => { e.value = ''; });
  await page.type('.form textarea.ex-test', 'assert crates(36) == 3');
  await add();
  check(/Решение не выполняется/.test(await text('.form .ex-out')) && /AssertionError/.test(await text('.form .ex-out')),
    'a reference solution that fails its own checks is refused');
  await page.$eval('.form textarea.ex-test', (e) => { e.value = ''; });
  await page.type('.form textarea.ex-test', 'assert crates(36) == 2\nassert crates(37) == 3\nassert crates(1) == 1');
  await add();
  check((await nItems()) === pyBefore + 1, 'a Python exercise whose solution passes real checks is saved');
  // a solution that never ends is refused after ONE timeout: the dead worker
  // answers the gate's later runs at once instead of a second wait
  const loopAdd = await page.$$('.addbtn');
  await loopAdd[loopAdd.length - 1].click(); await sleep(200);
  await page.select('.form select', 'exercise'); await sleep(200);
  await page.select('.form select.ex-check', 'assert'); await sleep(200);
  await page.type('.form input', 'Бесконечный цикл');
  await setSol('def f():\n    pass\nwhile True:\n    pass');
  await page.type('.form textarea.ex-test', 'assert f() is None');
  const tLoop = Date.now();
  await add();
  const loopMs = Date.now() - tLoop;
  check(/Решение не выполняется: timeout/.test(await text('.form .ex-out')) && loopMs < 16000,
    'a solution that never ends is refused after one timeout, not several (' + loopMs + ' ms)');
  await page.click('.form button:not(.primary)'); await sleep(200);   // cancel
  // GATE (review 2026-10-02 #2): an output exercise whose setup already prints the answer
  const pyAdd2 = await page.$$('.addbtn');
  await pyAdd2[pyAdd2.length - 1].click(); await sleep(200);
  await page.select('.form select', 'exercise'); await sleep(200);
  await page.select('.form select.ex-check', 'output'); await sleep(200);
  await page.type('.form input', 'Напечатайте 42');
  await page.$eval('.form textarea.ex-setup', (e) => { e.value = ''; });
  await page.type('.form textarea.ex-setup', 'print(42)');
  await setSol('x = 42');   // prints nothing itself: the expected output is the setup's alone
  await add();
  check(/Пустой ответ уже даёт ожидаемый вывод/.test(await text('.form .ex-out')),
    'an output exercise an empty answer would pass (the setup prints it) is refused');

  // the attacks call namespaces only a dev build exposes; a release build skips them
  if (await page.evaluate(() => typeof cljs === 'undefined')) console.log('SKIP verdict attacks: release build (run them against a dev build)');
  else {
  // --- ATTACKS on the verdict itself (review 2026-10-02 b), straight through
  // ex/grade! — the function the exercise card's Answer calls (dev build) ---
  const grade = (spec, code) => page.evaluate(async (spec, code) => {
    const c = cljs.core, kw = c.keyword;
    const m = c.PersistentArrayMap.fromArray(Object.entries(spec).flatMap(([k, v]) =>
      [kw(k), k === 'runtime' || k === 'check' ? kw(v) : v]), true);
    const r = await loretile.exercise.grade_BANG_(m, code);
    return c.get(r, kw('correct?'));
  }, spec, code);
  const ready = { runtime: 'python', check: 'assert', setup: '',
    solution: 'def ready(brix, acid):\n    return brix >= 21 and acid <= 7',
    test: 'assert ready(22, 6.5)\nassert ready(21, 7)' };
  check(await grade(ready, ready.solution) === true, 'control: a real answer still passes the rewritten checks');
  check(await grade(ready, 'import __main__\n__main__.eval_code = lambda *a, **k: None') === false,
    'an answer that rebinds the runner\'s eval_code cannot switch the hidden checks off');
  check(await grade({ ...ready, test: 'assert harvest(3) == 42' },
    'class A:\n    __eq__ = lambda s, o: True\ndef harvest(*a):\n    return A()') === false,
    'an object whose __eq__ always says True does not pass a comparison');
  check(await grade(ready, 'class T:\n    __bool__ = lambda s: True\ndef ready(*a):\n    return T()') === false,
    'an always-truthy object does not pass a bare assert');
  check(await grade(ready, 'def ready(*a):\n    globals()["_lt_plain"] = lambda x: x\n    return True') === true
    && await grade({ ...ready, test: 'assert ready(22, 6.5)\nassert harvest(3) == 42' },
      'class A:\n    __eq__ = lambda s, o: True\ndef harvest(*a):\n    return A()\n' +
      'def ready(*a):\n    globals()["_lt_plain"] = lambda x: x\n    return True') === false,
    'and the learner cannot swap the checker out from inside a checked call');
  const ripe = { runtime: 'python', check: 'output', setup: '',
    solution: 'def ripe(b):\n    return b >= 22\nprint(sum(ripe(x) for x in [20, 23, 25]))' };
  check(await grade(ripe, 'import gc\nfor d in gc.get_objects():\n' +
    '    if type(d) is dict and d.get("__name__") == "__main__" and "ripe" in d:\n' +
    '        print(sum(d["ripe"](x) for x in [20, 23, 25]))\n        break') === false,
    'an output answer cannot find the reference solution through gc');

  // ATTACK (security review 2026-10-03): the worker shares the app origin, so
  // exercise code could read the uploaded documents (OPFS) and send them out
  // (proved). The runner locks storage and the nested-worker escape away
  // before any learner code — via navigator.storage, directly and through the
  // prototype getter, and js.Worker.
  // reload first: earlier Runs patched print in the SHARED worker, which would
  // swallow this probe's output (the lockdown reads a value, not stdout, but a
  // clean worker keeps the probe honest)
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(400);
  await page.evaluate(() => loretile.blobs.put_BANG_('src-secret.md', 'PRIVATE-DATA'));
  const reach = { runtime: 'python', check: 'output', setup: '',
    solution: 'def ok(b):\n    return b\nprint(ok(1))' };
  const seesStorage = await page.evaluate((spec) => new Promise((res) => {
    const c = cljs.core, kw = c.keyword;
    const m = c.PersistentArrayMap.fromArray(Object.entries(spec).flatMap(([k, v]) => [kw(k), k === 'runtime' || k === 'check' ? kw(v) : v]), true);
    loretile.exercise.try_run_BANG_(m, 'import js\nproto = js.Object.getPrototypeOf(js.navigator)\nd = js.Object.getOwnPropertyDescriptor(proto, "storage")\nviaproto = None\ntry:\n    viaproto = d.get.call(js.navigator)\nexcept Exception:\n    pass\n"direct=" + str(js.navigator.storage) + " proto=" + str(viaproto)')
      .then((r) => res(String(c.get(r, kw('result')) || c.get(r, kw('error')) || '')), () => res('ERR'));
  }), reach);
  check(/direct=None/.test(seesStorage) && /proto=None/.test(seesStorage),
    'exercise code cannot reach the app\'s file system (OPFS), directly or via the prototype getter (' + seesStorage.replace(/\n/g, ' ') + ')');
  const makesWorker = await page.evaluate((spec) => new Promise((res) => {
    const c = cljs.core, kw = c.keyword;
    const m = c.PersistentArrayMap.fromArray(Object.entries(spec).flatMap(([k, v]) => [kw(k), k === 'runtime' || k === 'check' ? kw(v) : v]), true);
    loretile.exercise.try_run_BANG_(m, 'import js\nhasw = js.Worker is not None and hasattr(js.Worker, "new")\n"worker=" + str(hasw)')
      .then((r) => res(String(c.get(r, kw('result')) || c.get(r, kw('error')) || '')), () => res('ERR'));
  }), reach);
  check(/worker=false/i.test(makesWorker), 'and cannot spawn an unlocked nested worker to escape the lockdown (' + makesWorker + ')');

  // ATTACK (review 2026-10-02 #5): a Python worker that cannot load stalled
  // every exercise, SQL included. Block its script, then ask both runtimes
  // (Fetch with a pattern pauses only that script; puppeteer's interception
  // would also pause the SQL worker's own fetches, which it never sees)
  const cdp = await page.createCDPSession();
  await cdp.send('Fetch.enable', { patterns: [{ urlPattern: '*pyrunner.js*' }] });
  cdp.on('Fetch.requestPaused', (e) => cdp.send('Fetch.failRequest', { requestId: e.requestId, errorReason: 'Failed' }));
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(400);
  const boot = await page.evaluate(async () => {
    const c = cljs.core, kw = c.keyword, ex = loretile.exercise;
    const spec = (rt, chk, sol, setup) => c.PersistentArrayMap.fromArray(
      [kw('runtime'), kw(rt), kw('check'), kw(chk), kw('setup'), setup, kw('solution'), sol], true);
    const late = (ms) => new Promise((r) => setTimeout(() => r('stalled'), ms));
    const t0 = Date.now();
    const py = Promise.race([ex.check_solution_BANG_(spec('python', 'output', 'print(1)', '')), late(20000)]);
    const sql = Promise.race([ex.try_run_BANG_(spec('pglite', 'rows', 'select 1', 'create table t(a int); insert into t values (7);'),
      'select a from t'), late(20000)]);
    const [p, s] = await Promise.all([py, sql]);
    return { py: p === 'stalled' ? p : ex.error_text(p), sql: s === 'stalled' ? s : String(c.get(s, kw('rows'))), ms: Date.now() - t0 };
  });
  check(/не запустилась/.test(boot.py), 'a Python worker that cannot load fails with a translated message (' + boot.py + ', ' + boot.ms + ' ms)');
  check(/7/.test(boot.sql), 'and SQL still runs meanwhile (' + boot.sql + ')');

  }
  await browser.close();
  console.log(failures ? 'EXERCISE CHECK FAILED' : 'EXERCISE CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

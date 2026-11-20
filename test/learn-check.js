// The gap loop on the demo course (wiki/MVP.md): diagnostic → plan of gaps
// only → learn with the two-in-a-row check → final → fail → shorter round 2
// → pass. Answers are chosen by reading the demo log's keys, so the walk is
// deterministic. Usage: serve public/ first, then: node test/learn-check.js
const puppeteer = require('puppeteer-core');
const fs = require('fs');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

// item stem → correct option text / cloze key / exercise solution, from the demo log
const KEY = {};
const unq = (s) => JSON.parse('"' + s + '"');
for (const line of fs.readFileSync(__dirname + '/../kg/demo/log.edn', 'utf8').split('\n')) {
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
  await page.setViewport({ width: 1280, height: 800 });
  // a canned tutor: records what it was asked, answers in one line
  await page.evaluateOnNewDocument(() => {
    const orig = window.fetch.bind(window);
    window.__llmCalls = [];
    window.fetch = (url, opts) => {
      if (String(url).includes('api.anthropic.com')) {
        window.__llmCalls.push(JSON.parse(opts.body));
        const body = JSON.parse(opts.body);
        const sys = body.system || '';
        const text = /НЕВЕРНО/.test(sys)
          ? 'Ваш вариант описывает первичный ключ, а вопрос про ссылку на другую таблицу.'
          : 'Внешний ключ — это ссылка на строку другой таблицы.';
        return Promise.resolve(new Response(JSON.stringify({ content: [{ type: 'text', text }] }), { status: 200 }));
      }
      return orig(url, opts);
    };
  });
  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru'); localStorage.setItem('loretile-tour-done','1'); localStorage.setItem('loretile-chat-tour-done','1'); localStorage.setItem('loretile-learn-tour-done','1');
    localStorage.setItem('loretile-llm-config', '{:preset :anthropic :shape :anthropic :url "https://api.anthropic.com/v1/messages" :model "test" :key "sk-test"}');
  });
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(300);
  await page.click('.load-demo');
  await sleep(2000);

  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');
  const stage = () => page.$eval('.learn', (e) => e.dataset.stage).catch(() => '');
  const LIVE = '.qcard:not(.done)';
  const liveCard = () => page.$(LIVE);
  const feedText = () => text('.feed');
  // answer the live card: right, or deliberately wrong
  const answer = async (right) => {
    const stem = await text(LIVE + ' .stem');
    const key = KEY[stem];
    if (await page.$(LIVE + '.exercise')) {
      // an executed answer (test/exercise-check.js covers it in depth): submit and wait for the verdict
      await page.type(LIVE + ' textarea.ex-code', right ? key.solution : 'SELECT 1');
      const before = (await page.$$('.qcard.done')).length;
      await page.click(LIVE + ' .ex-submit');
      for (let i = 0; i < 600 && (await page.$$('.qcard.done')).length === before; i++) await sleep(100);
      await sleep(120);
      return;
    }
    if (await page.$(LIVE + ' .options')) {
      const opts = await page.$$eval(LIVE + ' .opt', (els) => els.map((e) => e.textContent));
      const i = right ? opts.indexOf(key) : opts.findIndex((o) => o !== key);
      await page.evaluate((i, sel) => document.querySelectorAll(sel + ' .opt')[i].click(), i, LIVE);
    } else {
      await page.type(LIVE + ' input', right ? key : 'нет');
      await page.click(LIVE + ' button.primary');
    }
    await sleep(120);
  };
  const liveTopic = () => text(LIVE + ' .qtopic');
  // learn mode now asks to be checked first: the material and the tutor come
  // before the card that credits mastery
  const readyUp = async () => {
    for (const sel of ['.check-me', '.continue']) {
      const els = await page.$$(sel);
      if (els.length) { await els[els.length - 1].click(); await sleep(250); return true; }
    }
    return false;
  };
  const lastText = (sel) => page.$$eval(sel, (els) => els.length ? els[els.length - 1].textContent : '');
  const lastItems = (sel, inner) => page.$$eval(sel, (els, inner) => els.length ? [...els[els.length - 1].querySelectorAll(inner)].map((e) => e.textContent) : [], inner);
  const clickLive = async (sel) => { const els = await page.$$(sel); await els[els.length - 1].click(); await sleep(300); };

  await page.goto(BASE + '#/course/sql-analyst', { waitUntil: 'networkidle0' });
  await sleep(500);
  check(await page.$('.take-course') !== null, 'course panel offers «take the course»');
  await page.click('.take-course');
  await sleep(400);
  await page.click('.learn .start');
  await sleep(300);
  check((await stage()) === 'diagnostic', 'diagnostic starts');
  check(/Ассистент выключен/.test(await text('.learn .locked')), 'composer is locked during the diagnostic');

  // diagnostic: know GROUP BY / HAVING / agg topics, miss the rest
  let n = 0;
  while ((await stage()) === 'diagnostic' && n++ < 20) await answer(/GROUP BY|HAVING|Агрегатные/.test(await liveTopic()));
  check((await stage()) === 'plan', 'plan card follows the diagnostic');
  // HONESTY: the SQL course's closure contains «Таблицы, строки и столбцы»,
  // which carries no question. It must be named as untested, never folded
  // into a claim that the learner demonstrated it.
  const planText = await text('.plan-card');
  check(!/уже показали/.test(planText), 'the plan no longer claims the rest was demonstrated');
  check(/Пропущено, вопросов пока нет/.test(planText) && /Таблицы/.test(planText),
    'and names the topic nobody could test' + (/Пропущено/.test(planText) ? '' : ' — got ' + planText.slice(0, 160)));
  const doneCards = (await page.$$('.qcard.done')).length;
  check(doneCards >= 8, 'answered cards stay in the feed, greyed (' + doneCards + ')');
  const planItems = await page.$$eval('.plan-card ol.plan li', (els) => els.map((e) => e.textContent));
  check(planItems.length > 0 && !planItems.some((p) => /GROUP BY|HAVING|Агрегатные/.test(p)), 'plan holds only the gaps');
  const idx = (re) => planItems.findIndex((p) => re.test(p));
  check(idx(/Таблицы/) === -1 && idx(/SELECT/) < idx(/WHERE/) && idx(/Ключи/) < idx(/JOIN/), 'gaps in prerequisite order (untested topics skipped)');

  await clickLive('.plan-card button.primary');
  check((await stage()) === 'learn' && (await page.$$('.topic-card')).length === 1, 'learning starts: topic card in the feed');
  check((await page.$$('.topic-card .lo')).length >= 1, 'the topic card shows its learning objects');
  check(await page.$('.check-me') !== null, 'the learner asks to be checked; the question is not forced on them');
  check(await page.$('.learn .composer textarea') !== null, 'the tutor is open on the material');

  // the tutor: grounded in THIS topic, told not to answer the check
  await page.type('.learn .composer textarea', 'что такое внешний ключ?');
  await page.keyboard.press('Enter');
  await sleep(400);
  check(/Внешний ключ — это ссылка/.test(await feedText()), 'tutor answers inline in the feed');
  const call = await page.evaluate(() => window.__llmCalls[0]);
  check(/Ключи и связи таблиц/.test(call.system) && /orders\.customer_id/.test(call.system) && /НИКОГДА не называй ответ/.test(call.system),
    'tutor prompt carries the topic, its objects and the no-answers rule');
  // the tutor is a COURSE tutor: the boundary is stated as data, and the
  // course's own topic titles are what define it
  check(/ГРАНИЦА ТЕМЫ/.test(call.system) && /ОТКЛОНИ/.test(call.system),
    'tutor prompt fences it to the course and orders a refusal outside it');
  check(/GROUP BY|JOIN|HAVING/.test(call.system.split('ГРАНИЦА ТЕМЫ')[1] || ''),
    'the fence names the course topics, so the model is not guessing the edge');

  // INTEGRITY: while the question is live the tutor must be gone
  await readyUp();
  check(await page.$(LIVE) !== null, 'the check card appears on request');
  check(await page.$('.learn .composer textarea') === null, 'the tutor is CLOSED while the question is live');
  check(/выключен/.test(await text('.learn .locked')), 'and says why');

  // a WRONG answer explains itself, unprompted
  await answer(false);
  await sleep(500);
  check(/описывает первичный ключ/.test(await feedText()),
    'a miss is explained by the tutor without being asked');
  const askCall = await page.evaluate(() => window.__llmCalls[window.__llmCalls.length - 1]);
  check(/НЕВЕРНО/.test(askCall.system) && /Верный ответ/.test(JSON.stringify(askCall.messages)),
    'the explanation prompt carries the learner\'s choice and the right answer');
  check(await page.$('.learn .composer textarea') !== null, 'the tutor is open again for follow-ups');
  check(await page.$('.continue') !== null, 'and the next question waits for a tap');
  check(/Неверно/.test(await feedText()), 'wrong answer gets a feedback line in learn mode');
  // a debriefed question never returns (review 2026-10-02 #3). «Ключи» has
  // ONE question and its answer was just shown: the old loop re-asked it and
  // closed the topic on it. Now the topic says so and the loop moves on.
  await readyUp();
  check(!(await page.$(LIVE)) && /не осталось вопросов/.test(await feedText()) && (await page.$$('.topic-card')).length === 2,
    'a one-question topic whose answer was shown is not re-asked; it stays a gap and the next topic opens');
  // a CORRECT answer keeps the rhythm: never a needless tap. Either the next
  // question is already live, or the topic closed — never a pause card.
  await readyUp(); await answer(true);
  check(await page.$('.continue') === null,
    'a correct answer never stops at a pause card');
  if (!(await page.$(LIVE))) await readyUp();
  if (await page.$(LIVE)) await answer(true);
  check(/закрыта/.test(await feedText()) && (await page.$$('.topic-card')).length === 3, 'two in a row (two different questions) close the topic, the next topic card appears');
  n = 0;
  const feedLen = () => page.$$eval('.feed > *', (els) => els.length);
  while ((await stage()) === 'learn' && n++ < 60) {
    const before = await feedLen();
    if (!(await page.$(LIVE))) { if (!(await readyUp())) break; }
    if (await page.$(LIVE)) await answer(true);
    await sleep(250);  // an explained miss appends asynchronously
    if ((await feedLen()) === before && (await stage()) === 'learn') { console.log('STUCK, feed did not grow'); break; }
  }
  check((await stage()) === 'final-intro', 'after the last gap the final is offered');
  await clickLive('.feed .card button.primary');
  check((await stage()) === 'final' && await page.$('.learn .composer') === null && /Ассистент выключен/.test(await text('.learn .locked')),
    'final: composer gone, lock shown');

  // final round 1: miss exactly two targets → fail (4/6 = 67% < 80%)
  n = 0; let missed = 0;
  while (await liveCard() && n++ < 12) {
    const miss = missed < 2 && /JOIN|WHERE/.test(await liveTopic());
    if (miss) missed++;
    await answer(!miss);
  }
  check((await stage()) === 'result' && /Пока не сдано/.test(await feedText()), 'two misses of six fail an 80% threshold');
  check((await page.$$('.result-card .lo')).length === 2, 'debrief walks through both wrong answers');
  await clickLive('.result-card button.primary');
  const plan2 = await lastItems('.plan-card', 'ol.plan li');
  check((await stage()) === 'plan' && plan2.length === 2, 'round 2 plan holds exactly the two missed topics' + (plan2.length === 2 ? '' : ' — got ' + JSON.stringify(plan2)));
  check(/раунд 2/.test(await text('.learn-head')), 'round counter advanced');
  await clickLive('.plan-card button.primary');
  n = 0;
  while ((await stage()) === 'learn' && n++ < 40) {
    if (!(await page.$(LIVE))) { if (!(await readyUp())) break; }
    if (await page.$(LIVE)) await answer(true);
    await sleep(200);
  }
  await clickLive('.feed .card button.primary');
  n = 0;
  while (await liveCard() && n++ < 12) await answer(true);
  check((await stage()) === 'result' && /^Сдано/.test(await lastText('.result-card h3')), 'round 2 passes');
  check(/Теперь вы умеете: .*GROUP BY/.test(await lastText('.result-card .recap')), 'a pass recaps what the learner can now do');
  check(await page.$('.scratch, .scratch-toggle') === null, 'and the scratchpad is gone once the course is done');
  const log = await page.evaluate(() => localStorage.getItem('loretile-attempts'));
  check((log.match(/:ctx :final/g) || []).length === 12 && /:round 2/.test(log), 'attempt log holds both finals with rounds');

  // sessions: start over shelves this run; the palette lists both; reopening restores the passed feed
  await page.click('.learn-head .restart');
  await sleep(400);
  check((await stage()) === 'diagnostic' && (await page.$$('.qcard')).length === 1, 'start over begins a fresh run');
  await page.keyboard.down('Control'); await page.keyboard.press('k'); await page.keyboard.up('Control');
  await sleep(200);
  await page.type('.cmd-input', 'SQL');
  await sleep(200);
  const rows = await page.$$eval('.cmd-list li', (els) => els.map((e) => e.textContent));
  check(rows.some((r) => /раунд 2/.test(r) && /сдано/.test(r)) && rows.some((r) => /раунд 1/.test(r) && /сейчас/.test(r)), 'palette lists the passed session and the open one');
  await page.evaluate(() => [...document.querySelectorAll('.cmd-list li')].find((e) => /раунд 2/.test(e.textContent)).click());
  await sleep(400);
  check((await stage()) === 'result' && /^Сдано/.test(await lastText('.result-card h3')), 'reopening a past session restores its feed');

  // an ACED diagnostic (no gaps) must still have a tutor: course-scoped,
  // open at final-intro (the bug: «I still have no ai in learning»)
  await page.click('.learn-head .restart');
  await sleep(400);
  n = 0;
  while ((await stage()) === 'diagnostic' && n++ < 20) await answer(true);
  check((await stage()) === 'final-intro', 'clean diagnostic skips straight to the final offer');
  check(await page.$('.learn .composer textarea') !== null, 'tutor composer is OPEN at the final offer');
  await page.type('.learn .composer textarea', 'зачем нужен HAVING?');
  await page.keyboard.press('Enter');
  await sleep(400);
  const callN = await page.evaluate(() => window.__llmCalls.length);
  const courseCall = await page.evaluate(() => window.__llmCalls[window.__llmCalls.length - 1]);
  check(callN >= 2 && /репетитор курса/.test(courseCall.system) && /SQL для аналитиков/.test(courseCall.system)
    && /НИКОГДА не называй ответ/.test(courseCall.system), 'course-scoped tutor prompt with the no-answers rule');
  await clickLive('.feed .card button.primary');
  n = 0;
  while (await liveCard() && n++ < 12) await answer(true);
  check((await stage()) === 'result' && await page.$('.learn .composer textarea') !== null,
    'tutor stays open on the result screen');

  // progress survives a reload
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(500);
  check((await stage()) === 'result' && (await page.$$('.qcard')).length > 10, 'the run survives a reload');

  // --- HONESTY: a final cannot quietly skip a target it has no question for --
  // Publishing such a course is now blocked at the door, so the case that
  // remains real is a course that arrived some other way: an imported log, or
  // one published before the gate existed. Inject exactly that.
  await page.evaluate(() => {
    const log = loretileLog();
    const ev = '{:ev/id "legacy-course" :ev/ns "demo-sql" :ev/actor "import" :ev/ts 1 '
      + ':ev/type :course-published :course {:id "legacy" :title "Ввезённый курс" '
      + ':targets [["tables" :apprentice] ["select" :apprentice]] :locale "ru" :pass-threshold 80}}';
    localStorage.setItem('loretile-kg-log', log.replace(/\]\s*$/, ' ' + ev + ']'));
  });
  await page.goto(BASE + '#/learn/legacy', { waitUntil: 'networkidle0' });
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(900);
  check(await page.$('.learn .start') !== null, 'an imported course with an unready target still opens');
  await page.click('.learn .start');
  await sleep(600);
  let k = 0;
  while ((await stage()) === 'diagnostic' && k++ < 12) await answer(true);
  if ((await stage()) === 'plan') {
    await clickLive('.plan-card button.primary');
    k = 0;
    while ((await stage()) === 'learn' && k++ < 30) {
      if (!(await page.$(LIVE))) { if (!(await readyUp())) break; }
      if (await page.$(LIVE)) await answer(true);
      await sleep(200);
    }
  }
  if ((await stage()) === 'final-intro') await clickLive('.feed .card button.primary');
  check(/проверить нечем/.test(await feedText()),
    'the final says up front that a target cannot be checked');
  k = 0;
  while (await liveCard() && k++ < 8) await answer(true);
  const verdict = await lastText('.result-card');
  check(/Не проверено целей/.test(verdict) && /Таблицы/.test(verdict),
    'and the verdict carries the caveat, naming the target' + (/Не проверено/.test(verdict) ? '' : ' — got ' + verdict.slice(0, 160)));
  check(/^Пока не сдано/.test(await lastText('.result-card h3')), 'and an unexamined target blocks the pass');

  await browser.close();
  console.log(failures ? 'LEARN CHECK FAILED' : 'LEARN CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

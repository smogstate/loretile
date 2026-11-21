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
  // what the course covers, before it starts (learning review 2026-11-20 D5)
  const starts = await page.$$eval('.start-targets li', (ls) => ls.map((l) => l.textContent));
  check(starts.length === 6 && starts.some((x) => /GROUP BY/.test(x)) && starts.every((x) => /практик|новичок|профи/.test(x)),
    `the start page lists the course's targets with their levels (${starts.length})`);
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
  // the finished diagnostic folds into one line (learning review 2026-11-20 D1);
  // its answered cards are still there, greyed, a tap away
  const diagLine = await text('.feed-seg.diagnostic .feed-fold');
  check(/Диагностика · верно \d+ из \d+/.test(diagLine), 'the finished diagnostic folds into one line: ' + diagLine);
  await page.click('.feed-seg.diagnostic .feed-fold'); await sleep(200);
  const doneCards = (await page.$$('.qcard.done')).length;
  check(doneCards >= 8, 'and opens on tap: its answered cards stay in the feed, greyed (' + doneCards + ')');
  // opening it does not throw the page back to the end (every redraw scrolled there)
  const inView = await page.$eval('.feed-seg.diagnostic .feed-fold', (b) => { const r = b.getBoundingClientRect(); return r.bottom > 0 && r.top < window.innerHeight; });
  check(inView, 'and the opened part stays in view — the page does not jump back to the end');
  await page.click('.feed-seg.diagnostic .feed-fold'); await sleep(200);
  const planItems = await page.$$eval('.plan-card ol.plan li', (els) => els.map((e) => e.textContent));
  check(planItems.length > 0 && !planItems.some((p) => /GROUP BY|HAVING|Агрегатные/.test(p)), 'plan holds only the gaps');
  const idx = (re) => planItems.findIndex((p) => re.test(p));
  check(idx(/Таблицы/) === -1 && idx(/SELECT/) < idx(/WHERE/) && idx(/Ключи/) < idx(/JOIN/), 'gaps in prerequisite order (untested topics skipped)');

  await clickLive('.plan-card button.primary');
  check((await stage()) === 'learn' && (await page.$$('.feed-seg.topic')).length === 1, 'learning starts: topic card in the feed');
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
  check(!(await page.$(LIVE)) && /не осталось вопросов/.test(await feedText()) && (await page.$$('.feed-seg.topic')).length === 2,
    'a one-question topic whose answer was shown is not re-asked; it stays a gap and the next topic opens');
  // a CORRECT answer keeps the rhythm: never a needless tap. Either the next
  // question is already live, or the topic closed — never a pause card.
  await readyUp(); await answer(true);
  check(await page.$('.continue') === null,
    'a correct answer never stops at a pause card');
  if (!(await page.$(LIVE))) await readyUp();
  if (await page.$(LIVE)) await answer(true);
  check(/закрыта/.test(await feedText()) && (await page.$$('.feed-seg.topic')).length === 3, 'two in a row (two different questions) close the topic, the next topic card appears');
  n = 0;
  // grows with every entry, folded or not: segments, then the live one's entries
  const feedLen = () => page.$$eval('.feed .feed-seg', (els) => els.length * 1000 + (els.length ? els[els.length - 1].children.length : 0));
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

  // the catalog after a pass (learning review 2026-11-20 D4)
  await page.goto(BASE + '#/courses', { waitUntil: 'networkidle0' }); await sleep(400);
  const passedCard = await page.$$eval('.course-card.passed', (cs) => cs.map((c) => c.textContent));
  check(passedCard.some((x) => /Открыть результат/.test(x) && /Пройти заново/.test(x) && !/Продолжить/.test(x)),
    'a passed course opens its result and can be taken again — not «Продолжить»');
  await page.goto(BASE + '#/learn/sql-analyst', { waitUntil: 'networkidle0' }); await sleep(400);

  // sessions: start over shelves this run; the palette lists both; reopening restores the passed feed
  await page.waitForSelector('.learn-head .restart'); await page.click('.learn-head .restart');
  await page.waitForSelector('.restart-confirm', { timeout: 3000 }).catch(() => {});
  // restart asks in place, not with the browser's dialog (learning review 2026-11-20 D7)
  check(/Начать курс заново\?/.test(await text('.restart-confirm')) && (await stage()) === 'result',
    'restart asks in place first; nothing restarts until confirmed');
  await page.waitForSelector('.restart-yes'); await page.click('.restart-yes');
  await sleep(400);
  check((await stage()) === 'diagnostic' && (await page.$$('.qcard')).length === 1, 'start over begins a fresh run');
  await page.keyboard.down('Control'); await page.keyboard.press('k'); await page.keyboard.up('Control');
  await sleep(200);
  // the learner's palette (learning review 2026-11-20 D2): courses and runs, no author commands
  const all = await page.$$eval('.cmd-list li', (els) => els.map((e) => e.textContent));
  check(!all.some((r) => /Новая тема|Новая связь|Новый граф|Новая онтология|Отменить/.test(r)) && all.some((r) => /SQL для аналитиков/.test(r)),
    'inside a course the palette is the learner\'s: courses and runs, no author commands');
  await page.type('.cmd-input', 'SQL');
  await sleep(200);
  const rows = await page.$$eval('.cmd-list li', (els) => els.map((e) => e.textContent));
  check(rows.some((r) => /раунд 2/.test(r) && /сдано/.test(r)) && rows.some((r) => /раунд 1/.test(r) && /сейчас/.test(r)), 'palette lists the passed session and the open one');
  await page.evaluate(() => [...document.querySelectorAll('.cmd-list li')].find((e) => /раунд 2/.test(e.textContent)).click());
  await sleep(400);
  check((await stage()) === 'result' && /^Сдано/.test(await lastText('.result-card h3')), 'reopening a past session restores its feed');

  // an ACED diagnostic (no gaps) must still have a tutor: course-scoped,
  // open at final-intro (the bug: «I still have no ai in learning»)
  await page.click('.learn-head .restart'); await page.waitForSelector('.restart-yes'); await page.click('.restart-yes');
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

  // progress survives a reload: the same parts of the run come back (folded)
  const segsBefore = await page.$$eval('.feed-seg', (els) => els.map((e) => e.className + '|' + (e.querySelector('.feed-fold')?.textContent || '')));
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(500);
  const segsAfter = await page.$$eval('.feed-seg', (els) => els.map((e) => e.className + '|' + (e.querySelector('.feed-fold')?.textContent || '')));
  check((await stage()) === 'result' && segsBefore.length >= 2 && JSON.stringify(segsAfter) === JSON.stringify(segsBefore), 'the run survives a reload' + (JSON.stringify(segsAfter) === JSON.stringify(segsBefore) ? '' : ' — ' + JSON.stringify([segsBefore, segsAfter])));

  // --- HONESTY: a final cannot quietly skip a target it has no question for --
  // Publishing such a course is now blocked at the door, so the case that
  // remains real is a course that arrived some other way: an imported log, or
  // one published before the gate existed. Inject exactly that.
  await page.evaluate(() => {
    const log = loretileLog();
    const ev = '{:ev/id "legacy-course" :ev/ns "demo-sql" :ev/actor "import" :ev/ts 1 '
      + ':ev/type :change :ops [[:node-set "legacy" {:is :course :title "Ввезённый курс" '
      + ':targets [["tables" :apprentice] ["select" :apprentice]] :locale "ru" :pass-threshold 80}]]}';
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

  // --- on a phone: a tap leaves no answer looking chosen (learning review 2026-11-20 B2:
  // :hover stuck to the first option under the finger after tapping «Начать») ---
  {
    const ctx = await browser.createBrowserContext();
    const ph = await ctx.newPage();
    await ph.setViewport({ width: 390, height: 844, isMobile: true, hasTouch: true });
    await ph.goto(BASE, { waitUntil: 'networkidle0' });
    await ph.evaluate(() => { localStorage.clear(); localStorage.setItem('loretile-locale', 'ru'); for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1'); });
    await ph.reload({ waitUntil: 'networkidle0' }); await sleep(1000);
    await ph.tap('.load-demo'); await sleep(2000);
    await ph.goto(BASE + '#/learn/sql-analyst', { waitUntil: 'networkidle0' }); await sleep(600);
    await ph.tap('.learn .start'); await sleep(600);
    // what a finger leaves behind: the pointer resting on the first answer
    const box = await (await ph.$('.qcard:not(.done) .opt')).boundingBox();
    await ph.mouse.move(box.x + box.width / 2, box.y + box.height / 2); await sleep(200);
    const bgs = await ph.$$eval('.qcard:not(.done) .opt', (os) => os.map((o) => getComputedStyle(o).backgroundColor));
    check(bgs.length >= 2 && new Set(bgs).size === 1, `on a phone no option looks chosen before a tap (${[...new Set(bgs)].join(' / ')})`);
    // the header's progress never pushes the page wider than the phone (D1, 2026-11-21)
    // measured with the LONGEST progress the header shows (the final's), on the course's long title
    const widths = await ph.evaluate(() => {
      const pr = document.querySelector('.learn-head .learn-progress');
      if (pr) pr.textContent = 'итоговая проверка · раунд 12';
      return [document.documentElement.scrollWidth, document.documentElement.clientWidth];
    });
    check(widths[0] <= widths[1], `on a phone the course page is no wider than the screen (${widths.join(' / ')})`);
    // the assistant from the top of the catalog (learning review 2026-11-20 D3)
    await ph.goto(BASE + '#/courses', { waitUntil: 'networkidle0' }); await sleep(400);
    const askBox = await (await ph.$('.ask-guide'))?.boundingBox();
    check(!!askBox && askBox.y < 200, 'on a phone «Спросить помощника» is at the top of the catalog');
    const guideTop = () => ph.evaluate(() => document.querySelector('.guide').getBoundingClientRect().top);
    const before = await guideTop();
    if (askBox) { await ph.tap('.ask-guide'); await sleep(500); }
    const after = await guideTop();
    const vh = await ph.evaluate(() => window.innerHeight);
    check(before > vh && after >= -1 && after < vh, `and it brings the assistant into view (top ${Math.round(before)} → ${Math.round(after)} of ${vh})`);
    await ctx.close();
  }
  await browser.close();
  console.log(failures ? 'LEARN CHECK FAILED' : 'LEARN CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

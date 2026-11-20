// Learner-data integrity (review 2026-10-02 #3–#6): each of these wrote
// wrong data into an append-only log that can never be corrected.
//  #3 a debriefed question never comes back, and two-in-a-row needs two
//     different questions; a topic that runs out says so and stays a gap
//  #4 mastery respects level: a novice pass does not credit professional
//  #5 an item/object edit can be declared material
//  #6 «assisted» = the learner asked the tutor since the last check, learn only
// Usage: make engines; serve public/ on :8291; node test/integrity-check.js
const puppeteer = require('puppeteer-core');
const fs = require('fs');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

const KEY = {};
for (const line of fs.readFileSync(__dirname + '/../kg/demo/vineyard.ru.edn', 'utf8').split('\n')) {
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
  await page.evaluateOnNewDocument(() => {
    const orig = window.fetch.bind(window);
    window.fetch = (url, opts) => {
      if (String(url).includes('api.anthropic.com')) {
        const text = 'Короткий ответ репетитора.';
        return Promise.resolve(new Response(JSON.stringify({ content: [{ type: 'text', text }] }), { status: 200 }));
      }
      return orig(url, opts);
    };
  });

  const LIVE = '.qcard:not(.done)';
  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');
  const stage = () => page.$eval('.learn', (e) => e.dataset.stage).catch(() => '');
  const liveTopic = () => text(LIVE + ' .qtopic');
  const feedText = () => text('.feed');
  const dealt = [];   // every stem that became a live card, in order
  const answer = async (right) => {
    const stem = await text(LIVE + ' .stem');
    dealt.push(stem);
    const key = KEY[stem];
    if (await page.$(LIVE + '.exercise')) {   // only ever answered wrong here
      await page.type(LIVE + ' textarea.ex-code', 'pass');
      const before = (await page.$$('.qcard.done')).length;
      await page.click(LIVE + ' .ex-submit');
      for (let i = 0; i < 300 && (await page.$$('.qcard.done')).length === before; i++) await sleep(100);
      await sleep(200);
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
    await sleep(250);
  };
  const readyUp = async () => {
    for (const sel of ['.check-me', '.continue']) {
      const els = await page.$$(sel);
      if (els.length) { await els[els.length - 1].click(); await sleep(250); return true; }
    }
    return false;
  };
  const clickLive = async (sel) => { const els = await page.$$(sel); await els[els.length - 1].click(); await sleep(300); };
  const ask = async (q) => {
    await page.type('.learn .composer textarea', q);
    await page.keyboard.press('Enter');
    await sleep(400);
  };
  const lastAttempt = () => page.evaluate(() => {
    const s = localStorage.getItem('loretile-attempts') || '';
    const maps = s.split('{').slice(1);
    return maps[maps.length - 1] || '';
  });
  const fresh = async () => {
    await page.goto(BASE + '#/', { waitUntil: 'networkidle0' });
    await page.evaluate(() => {
      localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
      for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
      localStorage.setItem('loretile-llm-config', '{:preset :anthropic :shape :anthropic :url "https://api.anthropic.com/v1/messages" :model "test" :key "sk-test"}');
    });
    await page.reload({ waitUntil: 'networkidle0' }); await sleep(400);
  };

  // --- #3 + #6 on a three-question topic -------------------------------------
  await fresh();
  await page.evaluate(() => [...document.querySelectorAll('button.demo-row')].find((b) => /виноград/i.test(b.textContent)).click());
  await sleep(1500);
  await page.goto(BASE + '#/learn/vineyard-season', { waitUntil: 'networkidle0' }); await sleep(500);
  await page.click('.learn .start'); await sleep(300);
  let n = 0;
  while ((await stage()) === 'diagnostic' && n++ < 25) await answer(!/Строение куста/.test(await liveTopic()));
  await clickLive('.plan-card button.primary');
  check(/Строение куста/.test(await text('.topic-card h2')), 'learning the one gap: «Строение куста» (three questions)');

  // review 2026-10-02 (b): wrong (burned), right, wrong (burned), right on
  // the same item. The pool used to count burned items, so «two different
  // in a row» could never happen and the topic stuck as a gap forever.
  await ask('из чего растёт урожай?');                 // the learner asks the tutor…
  await readyUp();
  const firstStem = await text(LIVE + ' .stem');
  await answer(false);                                // …then answers: assisted
  check(/:assisted\? true/.test(await lastAttempt()), '#6 a check answered after asking the tutor is marked assisted');
  await sleep(300); await readyUp();
  const secondStem = await text(LIVE + ' .stem');
  await answer(true);                                 // no question since the last check
  check(/:assisted\? false/.test(await lastAttempt()), '#6 the next check, with no new question, is not');
  check(!/Тема «Строение куста» закрыта/.test(await feedText()), 'one right answer with two honest questions left does not close');
  await sleep(300); await readyUp();
  const thirdStem = await text(LIVE + ' .stem');
  await answer(false);                                // burns the third
  await sleep(300); await readyUp();
  const fourthStem = await text(LIVE + ' .stem');
  check(![firstStem, thirdStem].includes(fourthStem),
    '#3 a question whose answer was shown is never dealt again (dealt: ' + dealt.slice(-4).map((s) => s.slice(0, 20)).join(' | ') + ')');
  check(fourthStem === secondStem, 'the one honest question left is re-asked');
  await answer(true);
  await sleep(400);
  check(/Тема «Строение куста» закрыта/.test(await feedText()),
    'one honest question left: one right answer closes the topic — it no longer sticks as a gap');
  check((await stage()) === 'final-intro', 'the loop moves on — no hang');

  // #6: chatting at the final offer never marks exam answers assisted
  await ask('что будет на итоговой?');
  await clickLive('.feed .card button.primary');
  n = 0;
  while (await page.$(LIVE) && n++ < 12) await answer(true);
  const finals = await page.evaluate(() => (localStorage.getItem('loretile-attempts') || '').split('{').filter((m) => m.includes(':ctx :final')));
  check(finals.length > 0 && finals.every((m) => /:assisted\? false/.test(m)),
    '#6 final answers are never assisted, even after chatting at the final offer (' + finals.length + ')');

  // --- a target whose every question was burned blocks the pass ---------------
  await fresh();
  await page.evaluate(() => [...document.querySelectorAll('button.demo-row')].find((b) => /виноград/i.test(b.textContent)).click());
  await sleep(1500);
  await page.evaluate(() => {
    const gid = (localStorage.getItem('loretile-graph') || '').replace(/"/g, '');
    const a = [1, 2, 3].map((i) => `{:item "i-vin-vine-anatomy-${i}" :concept "vine-anatomy" :correct? false :ctx :final :debriefed? true :level :apprentice :ts ${i} :graph "${gid}" :course "vineyard-season" :run "planted" :round 1}`);
    localStorage.setItem('loretile-attempts', '[' + a.join(' ') + ']');
  });
  await page.goto(BASE + '#/learn/vineyard-season', { waitUntil: 'networkidle0' });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(500);
  await page.click('.learn .start'); await sleep(300);
  n = 0;
  while ((await stage()) === 'diagnostic' && n++ < 25) await answer(true);
  if ((await stage()) === 'plan') {   // only if some topic stayed a gap
    await clickLive('.plan-card button.primary');
    n = 0;
    while ((await stage()) === 'learn' && n++ < 30) { if (!(await page.$(LIVE)) && !(await readyUp())) break; if (await page.$(LIVE)) await answer(true); }
  }
  await clickLive('.feed .card button.primary');       // start the final
  n = 0;
  while (await page.$(LIVE) && n++ < 12) await answer(true);
  const res = await text('.result-card');
  check((await stage()) === 'result' && /Пока не сдано/.test(res),
    '#2 every answer right, yet a target whose questions were all burned blocks the pass');
  check(/Не проверено целей: 1 — Строение куста/.test(res) && /не осталось вопросов, которых вы ещё не видели/.test(res),
    '#2 the result says honestly why it was not examined (' + res.slice(0, 120) + ')');

  // --- #4: a novice pass does not credit a professional target -----------------
  await fresh();
  await page.click('.load-demo'); await sleep(1500);
  const plant = (level) => page.evaluate((lvl) => {
    const gid = (localStorage.getItem('loretile-graph') || '').replace(/"/g, '');   // stored as an EDN string
    const a = `{:item "i-window-1" :concept "window" :correct? true :ctx :final :level :${lvl} :ts 1 :graph "${gid}" :course "sql-reporting" :run "planted" :round 1}`;
    localStorage.setItem('loretile-attempts', '[' + a + ']');
  }, level);
  const card = () => page.evaluate(() => [...document.querySelectorAll('.course-card')].find((c) => /отчёты посложнее/.test(c.textContent))?.textContent || '');
  await plant('novice');
  await page.goto(BASE + '#/courses', { waitUntil: 'networkidle0' }); await page.reload({ waitUntil: 'networkidle0' }); await sleep(600);
  check(!/Короче на/.test(await card()), '#4 a novice pass on «window» does not shorten a course that needs it at professional');
  await plant('professional');
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(600);
  check(/Короче на 1/.test(await card()), '#4 a professional pass does');

  // --- #5: an item edit can be declared material ------------------------------
  await page.goto(BASE + '#/c/where', { waitUntil: 'networkidle0' }); await sleep(700);
  await page.evaluate(() => document.querySelector('.item .pt-edit').click()); await sleep(300);
  check(await page.$('.item .form.edit input.material') !== null, '#5 a question\'s edit form offers «material change»');
  await page.click('.item .form.edit input.material');
  await page.$eval('.item .form.edit input', (e) => { e.value = ''; });
  await page.type('.item .form.edit input', 'Переписанный вопрос по смыслу');
  await page.click('.item .form.edit button.primary'); await sleep(400);
  const log = await page.evaluate(() => loretileLogs());
  const v = log.split('{:ev/id').find((e) => e.includes('Переписанный вопрос по смыслу')) || '';
  check(/:version-created/.test(v) && /:change :material/.test(v), '#5 it is recorded as a material version');
  await page.goto(BASE + '#/c/where', { waitUntil: 'networkidle0' }); await sleep(500);
  await page.evaluate(() => document.querySelector('.lo .pt-edit').click()); await sleep(300);
  check(await page.$('.lo .form.edit input.material') !== null, '#5 so does a learning object\'s');

  // --- #8 #9 #11: what a learner reads, and which publication a retake gets ---
  await fresh();
  await page.evaluate(() => [...document.querySelectorAll('button.demo-row')].find((b) => /виноград/i.test(b.textContent)).click());
  await sleep(1500);
  const inject = (evs) => page.evaluate((evs) => {
    const log = loretileLog();
    localStorage.setItem('loretile-kg-log', log.replace(/\]\s*$/, ' ' + evs.join(' ') + ']'));
  }, evs);
  const ev = (id, body) => `{:ev/id "${id}" :ev/ns "vineyard" :ev/actor "t" :ev/ts 9999999999 ${body}}`;
  await inject([
    ev('t-o1', ':ev/type :object-added :object {:id "o-drafted" :concept-id "ripeness" :kind :explanation :level :apprentice :locale "ru" :origin :drafted :text "Черновик: сахар растёт, кислота падает."}'),
    ev('t-o2', ':ev/type :object-added :object {:id "o-en" :concept-id "ripeness" :kind :explanation :level :apprentice :locale "en" :origin :authored :text "ENGLISH ONLY MATERIAL"}'),
    ev('t-o3', ':ev/type :object-added :object {:id "o-pro" :concept-id "ripeness" :kind :explanation :level :professional :locale "ru" :origin :authored :text "ТОЛЬКО ДЛЯ ПРОФИ"}'),
  ]);
  const republish = async () => {
    await page.goto(BASE + '#/course/vineyard-season', { waitUntil: 'networkidle0' });
    await page.reload({ waitUntil: 'networkidle0' }); await sleep(700);
    await page.click('.republish'); await sleep(500);
  };
  await republish();
  await page.goto(BASE + '#/learn/vineyard-season', { waitUntil: 'networkidle0' }); await sleep(500);
  await page.click('.learn .start'); await sleep(300);
  n = 0;
  while ((await stage()) === 'diagnostic' && n++ < 25) await answer(!/Зрелость/.test(await liveTopic()));
  await clickLive('.plan-card button.primary');
  const topicCard = await text('.topic-card');
  check(/Черновик: сахар растёт/.test(topicCard) && /черновик ИИ, принят куратором/.test(topicCard),
    '#8 the learner sees that the AI-drafted material was accepted by a curator');
  check(!/ENGLISH ONLY MATERIAL/.test(topicCard), '#9 material in another language is not shown in this course');
  check(!/ТОЛЬКО ДЛЯ ПРОФИ/.test(topicCard), '#9 material deeper than the course\'s level is not shown');
  await page.evaluate(() => { window.__sys = null; const f = window.fetch; window.fetch = (u, o) => { if (String(u).includes('anthropic')) window.__sys = JSON.parse(o.body).system; return f(u, o); }; });
  await ask('что тут главное?');
  const sys = await page.evaluate(() => window.__sys || '');
  check(/Черновик: сахар растёт/.test(sys) && !/ENGLISH ONLY/.test(sys) && !/ТОЛЬКО ДЛЯ ПРОФИ/.test(sys),
    '#9 the tutor\'s prompt carries the same filtered material');

  // #11: fail the final, the author republishes a material rewrite, the retake gets it
  await readyUp(); await answer(true);
  if (!(await page.$(LIVE))) await readyUp();
  await answer(true);
  await sleep(300);
  await clickLive('.feed .card button.primary');            // start the final
  n = 0;
  while (await page.$(LIVE) && n++ < 12) await answer(!/Зрелость|Сбор урожая/.test(await liveTopic()));
  check(/Пока не сдано/.test(await feedText()), 'round 1: two misses fail the final');
  await inject([ev('t-v1', ':ev/type :version-created :entity :object :id "o-drafted" :change :material :payload {:text "ПЕРЕПИСАНО В НОВОЙ ВЕРСИИ."}')]);
  await republish();
  await page.goto(BASE + '#/learn/vineyard-season', { waitUntil: 'networkidle0' }); await sleep(600);
  await clickLive('.result-card button.primary');           // next round
  await clickLive('.plan-card button.primary');
  const card2 = await page.$$eval('.topic-card', (els) => els[els.length - 1].textContent);
  check(/ПЕРЕПИСАНО В НОВОЙ ВЕРСИИ/.test(card2) && !/Черновик: сахар растёт/.test(card2),
    '#11 the retake round reads the newest publication — the author\'s material rewrite reaches it');

  // --- a pinned run whose topic the author has since deprecated -------------
  // deprecation is one-way and blocks republishing, so a learner pinned to
  // the old cut kept learning a retired topic with no word about it
  await fresh();
  await page.evaluate(() => [...document.querySelectorAll('button.demo-row')].find((b) => /виноград/i.test(b.textContent)).click());
  await sleep(1500);
  await page.goto(BASE + '#/learn/vineyard-season', { waitUntil: 'networkidle0' }); await sleep(500);
  await page.click('.learn .start'); await sleep(300);
  check(await page.$('.retired-note') === null, 'a run on the current publication carries no retired note');
  await inject([ev('t-d1', ':ev/type :concept-deprecated :id "ripeness"')]);
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(600);
  const note1 = await text('.retired-note');
  check(/вывел из обращения: Зрелость: сахар и кислота/.test(note1) && /когда выйдет новая/.test(note1),
    'the pinned run names the topic the author retired since: ' + note1.slice(0, 80));
  // a newer publication that still holds the retired topic promises nothing
  // (review 2026-10-16 A14) …
  const republishAs = (id, retarget) => page.evaluate((id, retarget) => {
    const log = loretileLog();
    const pub = log.split('{:ev/id').filter((e) => /:course-published/.test(e) && /:id "vineyard-season"/.test(e)).pop();
    let again = '{:ev/id' + pub.replace(/^ "[^"]+"/, ' "' + id + '"').replace(/\s*\]\s*$/, '');
    if (retarget) again = again.replace(/:targets \[[^]*?\]\]/, ':targets [["vine-anatomy" :apprentice]]');
    localStorage.setItem('loretile-kg-log', log.replace(/\]\s*$/, ' ' + again + ']'));
  }, id, retarget);
  await republishAs('t-p2', false);
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(600);
  check(/когда выйдет новая/.test(await text('.retired-note')), 'a newer publication that still holds the topic is no «new version» for it');
  // … one without it says the next round runs on it
  await republishAs('t-p3', true);
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(600);
  check(/опубликовал новую версию курса/.test(await text('.retired-note')), 'with a newer publication without the topic out, the note says the next round runs on it');

  // --- generated questions count only in the learner's OWN graph (ruling) -----
  const GEN = 'СГЕН: сгенерированный вопрос о зрелости?';
  KEY[GEN] = 'да';
  const genRun = async (personal) => {
    await fresh();
    await page.evaluate(() => [...document.querySelectorAll('button.demo-row')].find((b) => /виноград/i.test(b.textContent)).click());
    await sleep(1500);
    if (personal) await page.evaluate(() => localStorage.setItem('loretile-graphs',
      localStorage.getItem('loretile-graphs').replace('{:id "demo-vineyard"', '{:personal? true :id "demo-vineyard"')));
    await inject([
      ev('t-g1', `:ev/type :item-added :item {:id "i-aaa-gen" :primary-concept-id "ripeness" :level :apprentice :kind :single-choice :origin :generated :locale "ru" :spec {:stem "${GEN}" :options ["да" "нет"] :key 0}}`),
      ev('t-g2', ':ev/type :item-accepted :id "i-aaa-gen"'),
    ]);
    await republish();
    await page.goto(BASE + '#/learn/vineyard-season', { waitUntil: 'networkidle0' }); await sleep(500);
    await page.click('.learn .start'); await sleep(300);
    dealt.length = 0;
    let label = '';
    n = 0;
    while ((await stage()) === 'diagnostic' && n++ < 25) {
      if ((await text(LIVE + ' .stem')) === GEN) label = await text(LIVE + ' .qtopic');
      await answer(true);
    }
    return { seen: dealt.includes(GEN), label };
  };
  const shared = await genRun(false);
  check(!shared.seen, '#8 a shared graph never deals a generated question (diagnostic: ' + dealt.length + ' dealt)');
  const own = await genRun(true);
  check(own.seen, '#8 the learner\'s own graph deals it — their acceptance counts for them');
  check(/сгенерировано, не проверено/.test(own.label), '#8 its check card carries the AI label');
  if ((await stage()) === 'final-intro') await clickLive('.feed .card button.primary');
  n = 0;
  while (await page.$(LIVE) && n++ < 12) await answer(true);
  check(/Самопроверка/.test(await text('.result-card')), '#8 the result in a personal graph says «self-checked»');
  await page.goto(BASE + '#/courses', { waitUntil: 'networkidle0' }); await sleep(600);
  const vcard = await page.evaluate(() => [...document.querySelectorAll('.course-card')].find((c) => /Сезон на винограднике/.test(c.textContent))?.textContent || '');
  check(/самопроверка/.test(vcard), '#8 so does its catalog card');

  await browser.close();
  console.log(failures ? 'INTEGRITY CHECK FAILED' : 'INTEGRITY CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

// New content is stamped with the GRAPH's language, not a hardcoded "ru"
// and not the interface's: a course deals only items in its own language, so
// an exercise added to the English graph used to never reach its learners.
// Usage: make engines; serve public/ on :8291; node test/locale-check.js
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
  page.on('dialog', (d) => d.accept());
  await page.setViewport({ width: 1280, height: 900 });
  // one canned model reply carrying every step's shape: intake, links, bench
  await page.evaluateOnNewDocument(() => {
    const orig = window.fetch.bind(window);
    window.__llmCalls = [];
    const reply = {
      concepts: [{ id: 'bud-load', title: 'Bud load', definition: 'How many buds a vine keeps after pruning.' }],
      objects: [{ concept: 'bud-load', kind: 'explanation', level: 'apprentice', text: 'Keep about eight to twelve buds per square metre of canopy.' }],
      questions: [], edges: [],
      exercises: [{ stem: 'Print two plus two.', check: 'output', setup: '', solution: 'print(2 + 2)', wrong: ['print(5)'] }],
    };
    window.fetch = (url, opts) => {
      if (String(url).includes('api.anthropic.com')) {
        window.__llmCalls.push(JSON.parse(opts.body));
        return Promise.resolve(new Response(JSON.stringify({ content: [{ type: 'text', text: JSON.stringify(reply) }] }), { status: 200 }));
      }
      return orig(url, opts);
    };
  });
  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');
  const logText = () => page.evaluate(() => loretileLogs());

  // the English vineyard graph…
  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'en');
    localStorage.setItem('loretile-llm-config', '{:preset :anthropic :shape :anthropic :url "https://api.anthropic.com/v1/messages" :model "test" :key "sk-test"}');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
  });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(400);
  await page.evaluate(() => [...document.querySelectorAll('button.demo-row')].find((b) => /vineyard/i.test(b.textContent)).click());
  await sleep(1500);
  // …authored by someone whose interface is Russian: the graph's language must win
  await page.evaluate(() => localStorage.setItem('loretile-locale', 'ru'));
  await page.goto(BASE + '#/c/ripeness', { waitUntil: 'networkidle0' });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(700);

  const STEM = 'Print six times seven.';
  const adds = await page.$$('.addbtn');
  await adds[adds.length - 1].click(); await sleep(200);
  await page.select('.form select', 'exercise'); await sleep(200);
  await page.type('.form input', STEM);
  await page.type('.form textarea.ex-solution', 'print(6 * 7)');
  const before = await page.$$eval('.item', (els) => els.length);
  await page.click('.form button.primary');
  for (let i = 0; i < 300 && (await page.$$eval('.item', (els) => els.length)) === before; i++) await sleep(100);
  const added = (await logText()).split('{:ev/id').find((e) => e.includes(STEM)) || '';
  check(/:locale "en"/.test(added), 'an exercise added to the English graph is stamped "en", even from a Russian interface'
    + (/:locale "en"/.test(added) ? '' : ' — got ' + (added.match(/:locale "[a-z]+"/) || ['none'])[0]));

  // a choice question takes the same path
  // (on another topic: the diagnostic deals one question per topic, the exercise must keep ripeness's)
  await page.goto(BASE + '#/c/harvest', { waitUntil: 'networkidle0' });
  for (let i = 0; i < 100 && !/Harvest/.test(await text('.panel-head h2')); i++) await sleep(100);   // a hash change: wait for the panel to switch
  await sleep(300);
  const adds2 = await page.$$('.addbtn');
  await adds2[adds2.length - 1].click(); await sleep(200);
  await page.select('.form select', 'single-choice'); await sleep(200);   // the form keeps the last kind used
  await page.type('.form input', 'Which number is sweeter?');
  const opts = await page.$$('.form .opt-text');
  await opts[0].type('18 Brix'); await opts[1].type('24 Brix');
  await (await page.$$('.form .opt-right'))[1].click();
  await page.click('.form button.primary'); await sleep(400);
  const q = (await logText()).split('{:ev/id').find((e) => e.includes('Which number is sweeter?')) || '';
  check(/:locale "en"/.test(q), 'so is a choice question');
  check(/:primary-concept-id "harvest"/.test(q), 'and it lands on the topic the author is looking at, not the one opened first');

  // republish, then an English learner is actually dealt the new exercise
  await page.goto(BASE + '#/course/vineyard-season', { waitUntil: 'networkidle0' }); await sleep(700);
  const rep = await page.$('.republish');
  check(rep !== null, 'the course offers to republish the change');
  if (rep) { await rep.click(); await sleep(500); }
  await page.goto(BASE + '#/learn/vineyard-season', { waitUntil: 'networkidle0' }); await sleep(600);
  await page.click('.learn .start'); await sleep(400);
  let seen = false;
  for (let n = 0; n < 20 && !seen; n++) {
    const live = await page.$('.qcard:not(.done)');
    if (!live) break;
    if ((await text('.qcard:not(.done) .stem')) === STEM) { seen = true; break; }
    // move on: any answer will do
    if (await page.$('.qcard:not(.done) .opt')) await page.click('.qcard:not(.done) .opt');
    else if (await page.$('.qcard:not(.done) input')) { await page.type('.qcard:not(.done) input', 'x'); await page.click('.qcard:not(.done) button.primary'); }
    else break;
    await sleep(200);
  }
  check(seen, 'the English course deals the new exercise to its learners');

  // --- the rest of the authoring surface: same English graph, Russian interface ---
  await page.goto(BASE + '#/course/new', { waitUntil: 'networkidle0' }); await sleep(600);
  const courseLang = await page.evaluate(() => {
    const sel = [...document.querySelectorAll('select')].find((x) => ['ru', 'de', 'en'].every((v) => [...x.options].some((o) => o.value === v)));
    return sel && sel.value;
  });
  check(courseLang === 'en', 'a new course in the English graph defaults to English (got ' + courseLang + ')');

  await page.goto(BASE + '#/c/harvest', { waitUntil: 'networkidle0' });
  for (let i = 0; i < 100 && !/Harvest/.test(await text('.panel-head h2')); i++) await sleep(100);
  const nCalls = await page.evaluate(() => window.__llmCalls.length);
  await page.click('.draft-ex-btn');
  for (let i = 0; i < 100 && (await page.evaluate(() => window.__llmCalls.length)) === nCalls; i++) await sleep(100);
  const benchSys = await page.evaluate(() => window.__llmCalls[window.__llmCalls.length - 1].system);
  check(/на языке: en/.test(benchSys), 'the bench asks for exercises in the graph\'s language');
  for (let i = 0; i < 300 && !(await page.$('.card-ex')); i++) await sleep(100);
  await page.click('.card-ex button.primary'); await sleep(400);
  const drafted = (await logText()).split('{:ev/id').find((e) => e.includes('Print two plus two.')) || '';
  check(/:locale "en"/.test(drafted), 'an accepted drafted exercise is stamped "en"');

  // the intake chat: a learning-object draft riding on a topic card
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' }); await sleep(500);
  await page.type('.chat .composer textarea', 'Bud load basics');
  await page.keyboard.press('Enter');
  for (let i = 0; i < 100 && !(await page.$('.cards .card')); i++) await sleep(100);
  const intakeSys = await page.evaluate(() => window.__llmCalls.find((c) => /учебные концепты/.test(c.system || '')).system);
  check(/черновики объектов и вопросы пиши на языке: en/.test(intakeSys) && /evidence приводи дословно/.test(intakeSys),
    'the intake asks for drafts in the graph\'s language, evidence verbatim');
  await page.evaluate(() => [...document.querySelectorAll('.cards .card')].find((c) => /Bud load/.test(c.textContent)).querySelector('button.primary').click());
  await sleep(400);
  const lo = (await logText()).split('{:ev/id').find((e) => e.includes('eight to twelve buds')) || '';
  check(/:is :object/.test(lo) && /:locale "en"/.test(lo), 'a learning object drafted in the chat is stamped "en"');

  await browser.close();
  console.log(failures ? 'LOCALE CHECK FAILED' : 'LOCALE CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

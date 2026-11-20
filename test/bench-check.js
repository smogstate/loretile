// The drafting bench (wiki/Exercises.md): the model drafts exercises for a
// topic, the RUNTIME judges every draft — solution must pass its own check,
// every plausible wrong answer must be rejected — and only survivors reach
// the author as cards. The model is mocked; the runtimes are real.
// Usage: make engines; serve public/ on :8291; node test/bench-check.js
const puppeteer = require('puppeteer-core');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

const PY_DRAFTS = { exercises: [
  { stem: 'Напечатайте средний сахар трёх проб: 18, 20 и 22 °Bx.', check: 'output', setup: '',
    solution: 'print(sum([18, 20, 22]) / 3)', wrong: ['print(sum([18, 20, 22]) // 3)'] },
  { stem: 'BROKEN: напечатайте сахар.', check: 'output', setup: '',
    solution: 'print(undefined_brix)', wrong: ['print(0)'] },
  { stem: 'WEAK: функция ok(brix) — пора ли собирать (от 21 °Bx).', check: 'assert', setup: '',
    solution: 'def ok(brix):\n    return brix >= 21', test: 'assert ok(22)',
    wrong: ['def ok(brix):\n    return True'] },
  { stem: 'Функция ok(brix): True, когда сахар не ниже 21 °Bx.', check: 'assert', setup: '',
    solution: 'def ok(brix):\n    return brix >= 21', test: 'assert ok(22)\nassert ok(21)\nassert not ok(20)',
    wrong: ['def ok(brix):\n    return True'] },
] };
const SQL_DRAFTS = { exercises: [
  { stem: 'Города, где больше одного клиента.', check: 'rows', ordered: false,
    setup: 'create table junk (x int);',            // must be ignored: the graph's seed wins
    solution: 'SELECT city FROM customers GROUP BY city HAVING count(*) > 1',
    wrong: ['SELECT city FROM customers'] },
] };

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome',
    headless: 'new', args: ['--no-sandbox', '--disable-gpu'],
  });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  page.on('dialog', (d) => d.accept());
  await page.setViewport({ width: 1280, height: 900 });
  await page.evaluateOnNewDocument((py, sql) => {
    const orig = window.fetch.bind(window);
    window.__llmCalls = [];
    window.fetch = (url, opts) => {
      if (String(url).includes('api.anthropic.com')) {
        const body = JSON.parse(opts.body);
        window.__llmCalls.push(body);
        const text = JSON.stringify(/Язык: Python/.test(body.system) ? py : sql);
        return Promise.resolve(new Response(JSON.stringify({ content: [{ type: 'text', text }] }), { status: 200 }));
      }
      return orig(url, opts);
    };
  }, PY_DRAFTS, SQL_DRAFTS);

  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');
  const fresh = async () => {
    await page.goto(BASE + '#/', { waitUntil: 'networkidle0' });
    await page.evaluate(() => {
      localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
      for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
      localStorage.setItem('loretile-llm-config', '{:preset :anthropic :shape :anthropic :url "https://api.anthropic.com/v1/messages" :model "test" :key "sk-test"}');
    });
    await page.reload({ waitUntil: 'networkidle0' }); await sleep(400);
  };
  const waitCards = async (n) => {
    for (let i = 0; i < 600 && (await page.$$('.card-ex')).length < n; i++) await sleep(100);
    await sleep(300);
  };
  const nItems = () => page.$$eval('.item', (els) => els.length);

  // --- Python, on a craft topic -----------------------------------------------
  await fresh();
  await page.evaluate(() => [...document.querySelectorAll('button.demo-row')].find((b) => /виноград/i.test(b.textContent)).click());
  await sleep(1500);
  await page.goto(BASE + '#/c/ripeness', { waitUntil: 'networkidle0' }); await sleep(700);
  const before = await nItems();
  check(await page.$eval('.draft-ex select.ex-runtime', (e) => e.value) === 'python',
    'the bench defaults to the runtime this graph already uses (Python here)');
  await page.click('.draft-ex-btn');
  await waitCards(2);
  check(/#\/chat/.test(page.url()), 'it opens the assistant, where the cards land');
  const call = await page.evaluate(() => window.__llmCalls[window.__llmCalls.length - 1]);
  check(/Язык: Python/.test(call.system) && /НЕВЕРНЫХ/.test(call.system) && /Зрелость/.test(call.messages[0].content),
    'the prompt asks for Python exercises with wrong answers, on this topic and its material');
  const stems = await page.$$eval('.card-ex', (els) => els.map((e) => e.querySelector('p').textContent));
  check(stems.length === 2 && stems.some((s) => /средний сахар/.test(s)) && stems.some((s) => /^Функция ok/.test(s)),
    'only the two drafts that survive the runtime become cards (' + stems.length + ')');
  const chatText = await text('.chat .msgs');
  check(/Предложено 4, прошли проверку запуском 2/.test(chatText), 'the chat says how many were drafted and how many passed');
  check(/Отброшено «BROKEN[^»]*»: Решение не выполняется: NameError/.test(chatText), 'a draft whose solution fails is dropped, with the reason');
  check(/Отброшено «WEAK[^»]*»: Принимает неверный ответ/.test(chatText), 'a draft whose check accepts a wrong answer is dropped, with the reason');
  check(/неверных ответов отвергнуто — 1/.test(await text('.card-ex')), 'a card says it was checked by running it');

  // accept one, discard the other
  const accept = async (re) => page.evaluate((src) => {
    const card = [...document.querySelectorAll('.card-ex')].find((c) => new RegExp(src).test(c.textContent));
    card.querySelector('button.primary').click();
  }, re);
  await accept('средний сахар'); await sleep(300);
  await page.evaluate(() => document.querySelector('.card-ex button:not(.primary)').click()); await sleep(300);
  check((await page.$$('.card-ex')).length === 0, 'the cards resolve');
  await page.goto(BASE + '#/c/ripeness', { waitUntil: 'networkidle0' }); await sleep(600);
  check((await nItems()) === before + 1, 'the accepted draft is an exercise on the topic');
  const log = await page.evaluate(() => loretileLogs());
  const added = log.split('{:ev/id').find((e) => e.includes('средний сахар')) || '';
  check(/:kind :exercise/.test(added) && /:origin :drafted/.test(added) && /:runtime :python/.test(added),
    'stored as a drafted Python exercise — a human accepted it, the model wrote it');

  // --- SQL: the graph's seed is the engine's to keep -------------------------
  await fresh();
  await page.click('.load-demo'); await sleep(1500);
  await page.goto(BASE + '#/c/having', { waitUntil: 'networkidle0' }); await sleep(700);
  check(await page.$eval('.draft-ex select.ex-runtime', (e) => e.value) === 'pglite', 'in the SQL graph it defaults to SQL');
  await page.click('.draft-ex-btn');
  await waitCards(1);
  const sqlCall = await page.evaluate(() => window.__llmCalls[window.__llmCalls.length - 1]);
  check(/PostgreSQL/.test(sqlCall.system) && /create table customers/.test(sqlCall.system), 'the SQL prompt carries the graph\'s seed');
  check((await page.$$('.card-ex')).length === 1, 'the SQL draft survives: it rejected the wrong query');
  await page.click('.card-ex button.primary'); await sleep(300);
  const log2 = await page.evaluate(() => loretileLogs());
  const sqlAdded = log2.split('{:ev/id').find((e) => e.includes('больше одного клиента')) || '';
  check(/create table customers/.test(sqlAdded) && !/junk/.test(sqlAdded), 'the model\'s own setup was replaced by the graph\'s seed');

  // without a key the door is not shown at all
  await page.evaluate(() => localStorage.removeItem('loretile-llm-config'));
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(500);
  check(await page.$('.draft-ex') === null, 'without a model key the bench button is absent, not disabled');

  await browser.close();
  console.log(failures ? 'BENCH CHECK FAILED' : 'BENCH CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

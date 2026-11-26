// The assistant's read tools (wiki/Assistant.md, the kintale registry shape):
// a reply asking for {"tools":[…]} gets the results appended and is asked
// again — at most two rounds — and writes still arrive only as cards.
// The model is a scripted mock; the tools run against the real demo graph.
// Usage: make engines; serve public/ on :8291; node test/tools-check.js
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
  await page.setViewport({ width: 1280, height: 900 });
  await page.evaluateOnNewDocument(() => {
    const orig = window.fetch.bind(window);
    window.__calls = []; window.__script = [];
    window.fetch = (url, opts) => {
      if (String(url).includes('api.anthropic.com')) {
        const body = JSON.parse(opts.body);
        window.__calls.push({ system: body.system, user: body.messages[0].content });
        // the link-judging step gets nothing to judge; everything else follows the script
        const reply = /Для КАЖДОЙ пары/.test(body.system) ? { edges: [] }
          : (window.__script.length > 1 ? window.__script.shift() : window.__script[0]);
        // a string is a plain-text answer (the tutor's), an object is JSON
        const text = typeof reply === 'string' ? reply : JSON.stringify(reply);
        return Promise.resolve(new Response(JSON.stringify({ content: [{ type: 'text', text }] }), { status: 200 }));
      }
      return orig(url, opts);
    };
  });
  const text = (sel) => page.$eval(sel, (e) => e.textContent).catch(() => '');
  const msgs = () => page.$$eval('.chat .msgs .msg', (els) => els.map((e) => e.textContent));
  const intakeCalls = () => page.evaluate(() => window.__calls.filter((c) => /учебные концепты/.test(c.system)));
  const say = async (script, q) => {
    await page.evaluate((s) => { window.__script = s; window.__calls = []; }, script);
    await page.type('.chat .composer textarea', q);
    await page.keyboard.press('Enter');
    for (let i = 0; i < 100 && (await page.$('.chat .msg-sys')) && (await text('.chat .msgs')).includes('…'); i++) await sleep(100);
    await sleep(1200);
  };

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
    localStorage.setItem('loretile-llm-config', '{:preset :anthropic :shape :anthropic :url "https://api.anthropic.com/v1/messages" :model "test" :key "sk-test"}');
  });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(400);
  await page.click('.load-demo'); await sleep(1500);
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' }); await sleep(400);

  // --- search, then open, then answer ----------------------------------------
  await say([
    { tools: [{ name: 'search-nodes', args: { query: 'GROUP BY корзины' } }] },
    { tools: [{ name: 'open-node', args: { id: 'group-by' } }] },
    { concepts: [], note: 'Да: тема group-by уже есть, опирается на WHERE.' },
  ], 'есть ли у нас что-то про группировку строк?');
  let calls = await intakeCalls();
  check(calls.length === 3, 'two rounds of lookups, then the answer: three model calls (' + calls.length + ')');
  check(/ИНСТРУМЕНТЫ/.test(calls[0].system) && ['search-nodes', 'open-node', 'list-courses', 'learner-gaps'].every((n) => calls[0].system.includes(n)),
    'the prompt lists the tools — derived from the registry');
  check(/РЕЗУЛЬТАТЫ ИНСТРУМЕНТОВ/.test(calls[1].user) && /group-by — GROUP BY/.test(calls[1].user),
    'the search result goes back to the model: it found group-by');
  check(/definition:/.test(calls[2].user) && /requires: .*WHERE/.test(calls[2].user) && /material \(\d+\)/.test(calls[2].user),
    'open-node hands over the definition, the links and the material');
  let m = await msgs();
  check(m.filter((x) => /🔎 смотрю: (search-nodes|open-node)/.test(x)).length === 2, 'the chat shows what was looked up');
  check(m.some((x) => /тема group-by уже есть/.test(x)), 'and the answer arrives in the chat');
  check((await page.$$('.cards .card')).length === 0, 'a question makes no cards — reads never write');

  // --- the learner's gaps ------------------------------------------------------
  await say([
    { tools: [{ name: 'list-courses', args: {} }, { name: 'learner-gaps', args: { course: 'sql-analyst' } }] },
    { concepts: [], note: 'Пробелы видны.' },
  ], 'где у ученика пробелы в курсе SQL?');
  calls = await intakeCalls();
  check(/sql-analyst — SQL для аналитиков[^\n]*\[ru\]: [^\n]*@apprentice/.test(calls[1].user), 'list-courses names the courses with their targets');
  check(/course sql-analyst/.test(calls[1].user) && /known \(0\)/.test(calls[1].user) && /gaps \(\d+\): /.test(calls[1].user),
    'learner-gaps reads this learner\'s standing from the attempt log (none yet: all gaps)');

  // --- unknown tools are refused in words; the loop is bounded -----------------
  await say([{ tools: [{ name: 'delete-everything', args: {} }] }, { concepts: [], note: 'ок' }], 'удали всё');
  calls = await intakeCalls();
  check(/unknown tool: delete-everything/.test(calls[1].user), 'a tool that does not exist is refused, and the model is told so');
  await say([{ tools: [{ name: 'search-nodes', args: { query: 'x' } }] }], 'ищи без конца');
  calls = await intakeCalls();
  check(calls.length === 3, 'a model that never stops asking is cut off after two rounds (' + calls.length + ' calls)');
  check((await msgs()).some((x) => /два круга поиска — предел/.test(x)), 'and the chat says so');

  // --- actions: proposed as one-tap cards, never run by the model -------------
  await say([{ concepts: [], note: 'Вот куда стоит пойти.',
               actions: [{ name: 'go-to-node', args: { id: 'group-by' } },
                         { name: 'start-course', args: { course: 'sql-analyst' } },
                         { name: 'go-to-node', args: { id: 'no-such-topic' } },
                         { name: 'build-from-material', args: { goal: 'чужое действие' } }] }],
            'куда мне дальше?');
  calls = await intakeCalls();
  check(!calls[0].system.includes('build-from-material') && !calls[0].system.includes('search-catalog'),
    'the author intake is offered only its own set: no guide action, no catalog search');
  check(/ДЕЙСТВИЯ/.test(calls[0].system) && ['go-to-node', 'start-course', 'draft-exercises'].every((n) => calls[0].system.includes(n)),
    'the prompt lists the actions apart from the reads — derived from the registry');
  const actionCards = await page.$$eval('.card-action', (els) => els.map((e) => e.textContent));
  check(actionCards.length === 2 && actionCards.some((x) => /Открыть тему «GROUP BY/.test(x)) && actionCards.some((x) => /Открыть курс «SQL для аналитиков/.test(x)),
    'two valid actions become cards; one pointing at nothing and one outside the intake\'s set are dropped (' + actionCards.length + ')');
  check(/#\/chat/.test(page.url()), 'nothing has moved yet — the model cannot run an action');
  await page.evaluate(() => [...document.querySelectorAll('.card-action')].find((c) => /GROUP BY/.test(c.textContent)).querySelector('.action-do').click());
  await sleep(500);
  check(/#\/c\/group-by/.test(page.url()), 'one tap, and the action runs: the topic opens');
  // an action requested as a read is refused with the way to propose it
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' }); await sleep(300);
  await say([{ tools: [{ name: 'start-course', args: { course: 'sql-analyst' } }] }, { concepts: [], note: 'ок' }], 'запусти курс');
  calls = await intakeCalls();
  check(/start-course is an action, not a read: propose it in "actions"/.test(calls[1].user), 'an action asked for as a read is refused, with the way to propose it');
  check(/#\/chat/.test(page.url()), 'and nothing ran');

  // --- graphs: the assistant lists, creates and opens them (2026-11-26) --------
  const firstGraph = await page.evaluate(() => cljs.core.deref(loretile.store.current));
  await say([{ tools: [{ name: 'list-graphs', args: {} }] }, { concepts: [], note: 'Вот ваши графы.' }], 'какие у меня есть графы?');
  calls = await intakeCalls();
  check(calls[0].system.includes('list-graphs') && calls[0].system.includes('create-graph') && calls[0].system.includes('open-graph'),
    'the author assistant is offered list-graphs, create-graph and open-graph');
  check(/on this device:/.test(calls[1].user) && new RegExp(firstGraph + ' — «[^»]+» \\[learning\\] \\(open now\\)').test(calls[1].user),
    'list-graphs names the graphs on this device, their ontology, and which is open');
  await say([{ concepts: [], note: 'Создам граф.',
               actions: [{ name: 'create-graph', args: { title: 'Процессы склада', ontology: 'learning' } },
                         { name: 'create-graph', args: { title: 'Чужой', ontology: 'no-such-ontology' } },
                         { name: 'open-graph', args: { id: firstGraph } }] }], 'создай граф «Процессы склада»');
  // (a course card from the actions above is still on the pile: graph cards are told by their words)
  let graphCards = await page.$$eval('.card-action', (els) => els.map((e) => e.textContent).filter((x) => /граф/.test(x)));
  check(graphCards.length === 1 && /Создать граф «Процессы склада» · Обучение/.test(graphCards[0]),
    `create-graph becomes one card; an unknown ontology and opening the graph already open are dropped (${graphCards.length})`);
  check(await page.evaluate(() => cljs.core.deref(loretile.store.current)) === firstGraph, 'nothing is created before the tap');
  const tap = (re) => page.evaluate((src) => [...document.querySelectorAll('.card-action')].find((c) => new RegExp(src).test(c.textContent)).querySelector('.action-do').click(), re);
  await tap('Создать граф'); await sleep(500);
  const made = await page.evaluate(() => ({ id: cljs.core.deref(loretile.store.current), title: loretile.store.graph_title(cljs.core.deref(loretile.store.current)) }));
  check(made.id !== firstGraph && made.title === 'Процессы склада', 'one tap: the graph is created and open (' + made.title + ')');
  check((await msgs()).some((x) => /Открыт граф «Процессы склада»/.test(x)), 'the assistant says which graph is open now');
  await say([{ concepts: [], note: 'Открываю.', actions: [{ name: 'open-graph', args: { id: firstGraph } }] }], 'вернись к прежнему графу');
  graphCards = await page.$$eval('.card-action', (els) => els.map((e) => e.textContent).filter((x) => /граф/.test(x)));
  check(graphCards.length === 1 && /Открыть граф «/.test(graphCards[0]), 'open-graph becomes a card naming the graph');
  await tap('Открыть граф'); await sleep(500);
  check(await page.evaluate(() => cljs.core.deref(loretile.store.current)) === firstGraph, 'one tap: the first graph is open again');

  // --- course mode: the course form's composer is the assistant too ----------
  await page.goto(BASE + '#/course/new', { waitUntil: 'networkidle0' }); await sleep(500);
  check(/режим курса/.test(await text('.course-intake .mode-badge')), 'the course form\'s composer wears the course-mode badge');
  await page.evaluate(() => { window.__script = [
    { tools: [{ name: 'search-nodes', args: { query: 'оконные функции' } }, { name: 'learner-gaps', args: { course: 'sql-analyst' } }] },
    { targets: [{ id: 'window', level: 'professional', why: 'отчёты с накопительным итогом' }], unmatched: [], note: 'Вот цель для курса.' }];
    window.__calls = []; });
  await page.type('.course-intake textarea', 'курс про отчёты с накопительным итогом');
  await page.click('.course-intake button.primary'); await sleep(1300);
  const ccalls = await page.evaluate(() => window.__calls.filter((c) => /По описанию курса/.test(c.system)));
  check(ccalls.length === 2 && /window — /.test(ccalls[1].user), 'it searches the graph before choosing targets');
  check(/learner-gaps is not available here/.test(ccalls[1].user), 'and gets only the reads course mode was given');
  check(/Оконные функции/.test(await text('.course-intake .cards')), 'the found topic arrives as a target card');

  // --- the tutor reads too: two lookups, fenced to its course, questions hidden ---
  // before the course's last publication: material in another language and above
  // the course's level, a German question, and a topic outside the course
  // that requires WHERE — none of it is the tutor's to read
  await page.evaluate(() => {
    // just before the course's LAST publication, so the pinned learner sees it all
    const k = 'loretile-kg-log', s = loretileLog(), at = s.lastIndexOf('{:ev/id', s.lastIndexOf(':course {:id "sql-analyst"'));
    const e = (n, rest) => '{:ev/id "x-' + n + '" :ev/ns "demo" :ev/actor "demo-author" :ev/ts 1756808000000 ' + rest + '} ';
    const extra = e(1, ':ev/type :object-added :object {:id "o-where-de" :concept-id "where" :kind :explanation :level :apprentice :locale "de" :origin :authored :text "DEUTSCHES MATERIAL"}')
      + e(2, ':ev/type :object-added :object {:id "o-where-pro" :concept-id "where" :kind :explanation :level :professional :locale "ru" :origin :authored :text "ПРОФИ-МАТЕРИАЛ"}')
      + e(3, ':ev/type :edge-accepted :edge {:from "window" :to "where" :type :requires :provenance :authored}')
      // a German question on EVERY topic, whichever one the tutor is on
      + [...new Set([...s.matchAll(/:concept-accepted,? :concept \{:id "([^"]+)"/g)].map((m) => m[1]))].map((c, i) =>
        e('q' + i, ':ev/type :item-added :item {:id "i-de-' + c + '" :primary-concept-id "' + c + '" :level :apprentice :kind :single-choice :locale "de" :spec {:stem "DEUTSCHER STAMM" :options ["a" "b" "c"] :key 0}}')
        + e('a' + i, ':ev/type :item-accepted :id "i-de-' + c + '"')).join('');
    if (at > 0) localStorage.setItem(k, s.slice(0, at) + extra + s.slice(at));
  });
  await page.reload({ waitUntil: 'networkidle0' }); await sleep(300);
  await page.goto(BASE + '#/learn/sql-analyst', { waitUntil: 'networkidle0' }); await sleep(500);
  await page.click('.learn .start'); await sleep(300);
  for (let i = 0; i < 25 && await page.$('.qcard:not(.done)'); i++) {   // any answers: we only need the plan
    if (await page.$('.qcard:not(.done) .opt')) await page.click('.qcard:not(.done) .opt');
    else { await page.type('.qcard:not(.done) input', 'x'); await page.click('.qcard:not(.done) button.primary'); }
    await sleep(120);
  }
  const planBtns = await page.$$('.plan-card button.primary'); await planBtns[planBtns.length - 1].click(); await sleep(400);
  const whereStem = await page.evaluate(() => {
    const s = loretileLogs();
    const m = s.match(/:primary-concept-id "where"[^\n]*?:stem "([^"]+)"/);
    return m ? m[1] : '';
  });
  await page.evaluate(() => { window.__script = [
    { tools: [{ name: 'open-node', args: { id: 'where' } }, { name: 'open-node', args: { id: 'window' } },
              { name: 'learner-gaps', args: { course: 'sql-analyst' } }] },
    'WHERE отбирает строки до группировки.']; window.__calls = []; });
  await page.type('.learn .composer textarea', 'чем WHERE отличается от HAVING?');
  await page.keyboard.press('Enter'); await sleep(1200);
  const tcalls = await page.evaluate(() => window.__calls.filter((c) => /репетитор/.test(c.system)));
  check(tcalls.length === 2, 'the tutor looks once, then answers: two calls (' + tcalls.length + ')');
  check(/ИНСТРУМЕНТЫ/.test(tcalls[0].system) && /- search-nodes/.test(tcalls[0].system) && /- open-node/.test(tcalls[0].system)
    && !/- learner-gaps/.test(tcalls[0].system) && !/ДЕЙСТВИЯ/.test(tcalls[0].system),
    'the tutor\'s prompt offers two lookups and no actions');
  check(/definition:/.test(tcalls[1].user) && /requires:/.test(tcalls[1].user), 'it can open a topic of its course: definition and links');
  check(whereStem && !tcalls[1].user.includes(whereStem), 'but never the text of its questions (counted, not shown)');
  check(/window is outside this course/.test(tcalls[1].user), 'a topic outside the course is refused');
  check(/material \(2\)/.test(tcalls[1].user) && !tcalls[1].user.includes('DEUTSCHES MATERIAL') && !tcalls[1].user.includes('ПРОФИ-МАТЕРИАЛ'),
    'open-node lists the material a learner of this course reads: its language, its level or below');
  check(!/required by: [^\n]*Оконные функции/.test(tcalls[1].user), 'and names no topic outside the course');
  check(!tcalls[0].system.includes('DEUTSCHER STAMM'), 'the tutor\'s prompt carries question stems only in the course\'s language');
  check(/learner-gaps is not available here/.test(tcalls[1].user), 'a tool the tutor was not given is refused');
  const feed = await text('.feed');
  check(/🔎 репетитор смотрит: open-node/.test(feed) && /WHERE отбирает строки до группировки/.test(feed),
    'the learner sees what the tutor looked up, then its answer');
  // a request for no lookups at all is not «the limit»
  await page.evaluate(() => { window.__script = [{ tools: [] }]; window.__calls = []; });
  await page.type('.learn .composer textarea', 'а ещё?');
  await page.keyboard.press('Enter'); await sleep(1000);
  const feed2 = await text('.feed');
  check(!/долго искал/.test(feed2) && /ничего не ответил/.test(feed2), 'a tutor reply of {"tools":[]} shows no «limit reached» line');

  // --- the guide's action label: model text on a button is capped ------------
  await page.goto(BASE + '#/courses', { waitUntil: 'networkidle0' }); await sleep(500);
  await page.evaluate(() => { window.__script = [{ note: 'нет', actions: [{ name: 'build-from-material', args: { goal: 'О'.repeat(300) } }] }]; });
  await page.type('.guide .composer textarea', 'хочу всё');
  await page.keyboard.press('Enter'); await sleep(1000);
  const lbl = await text('.guide .card-action .action-do');
  check(lbl && !lbl.includes('О'.repeat(61)) && lbl.includes('О'.repeat(60) + '…'), 'the build-from-material label caps the model\'s goal at 60 characters');

  await browser.close();
  console.log(failures ? 'TOOLS CHECK FAILED' : 'TOOLS CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

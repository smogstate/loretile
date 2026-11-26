// Changesets (wiki/Changesets.md): TEXT that changes a graph, posted to the
// server with the graph's id in its header; the graph lists its pending ones;
// picking one makes it a source of the graph and the assistant reads it against
// the graph — new topics, EDITS and REMOVALS of existing ones, as cards. A
// server on a temp dir, the model scripted (the page's api/llm, OpenAI shape).
// Usage: a built bundle in public/ (npm run dev / make release); node test/changeset-check.js
const { spawn, execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const puppeteer = require('puppeteer-core');
const ROOT = path.resolve(__dirname, '..');
const PORT = 8298;
const BASE = `http://localhost:${PORT}/`;
const TOKEN = 'cs-token-1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loretile-cs-'));
  fs.writeFileSync(path.join(dir, 'config.edn'), `{:server {:port ${PORT}} :app {:demos false} :llm {:url "http://127.0.0.1:9/none"}}`);
  const cp = execFileSync('clojure', ['-Spath'], { cwd: path.join(ROOT, 'server') }).toString().trim();
  const server = spawn('java', ['-cp', `${path.join(ROOT, 'server/src')}:${cp}`, 'clojure.main', '-m', 'loretile.server.main'], {
    env: { ...process.env, LORETILE_CONFIG: 'file://' + path.join(dir, 'config.edn'), CHANGESETS_TOKEN: TOKEN,
           SERVER_PUBLIC_DIR: path.join(ROOT, 'public'), GRAPHS_URL: 'file://' + path.join(dir, 'graphs') },
    stdio: ['ignore', 'ignore', 'inherit'],
  });
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  try {
    for (let i = 0; i < 60; i++) { if (await fetch(BASE + 'api/health').then((r) => r.ok, () => false)) break; await sleep(500); }
    const page = await browser.newPage();
    page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
    await page.setViewport({ width: 1280, height: 900 });
    await page.evaluateOnNewDocument(() => {
      if (!sessionStorage.getItem('seeded')) {
        localStorage.clear(); sessionStorage.setItem('seeded', '1'); localStorage.setItem('loretile-locale', 'ru');
        for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
      }
      // the model, scripted: the server's api/llm in its OpenAI shape
      const orig = window.fetch.bind(window);
      window.__calls = []; window.__script = [];
      window.fetch = (u, o) => {
        if (String(u).includes('api/llm')) {
          const body = JSON.parse(o.body); const sys = (body.messages.find((m) => m.role === 'system') || {}).content || '';
          window.__calls.push({ system: sys });
          const reply = /Для КАЖДОЙ пары/.test(sys) ? { edges: [] } : (window.__script.length > 1 ? window.__script.shift() : window.__script[0]);
          return Promise.resolve(new Response(JSON.stringify({ choices: [{ message: { content: JSON.stringify(reply) } }],
                                                               usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 } }), { status: 200 }));
        }
        return orig(u, o);
      };
    });
    await page.goto(BASE, { waitUntil: 'networkidle0' });
    const append = (edn) => page.evaluate(async (s) => { const r = await loretile.store.append_BANG_(cljs.reader.read_string(s)); return r ? cljs.core.pr_str(r) : null; }, edn);
    // a graph on the server with two topics the story will change
    const gid = await page.evaluate(() => loretile.store.new_graph_BANG_('Возвраты'));
    await sleep(800);
    await append('{:ev/type :change :ops [[:node-set "refund-window" {:title "Окно возврата" :definition "Возврат возможен в течение 14 дней."}]]}');
    await append('{:ev/type :change :ops [[:node-set "manual-approval" {:title "Ручное одобрение" :definition "Каждый возврат одобряет менеджер."}]]}');
    await sleep(500);

    // --- posting: the header's graph id is mandatory ---------------------------
    const post = (text, token = TOKEN) => fetch(BASE + 'api/changesets', { method: 'POST', body: text,
      headers: token ? { authorization: 'Bearer ' + token, 'content-type': 'text/markdown' } : { 'content-type': 'text/markdown' } })
      .then(async (r) => ({ status: r.status, text: await r.text() }));
    const story = `---\ngraph: ${gid}\nid: STORY-1\ntitle: Возвраты за 30 дней\nsource: https://example.test/pull/1\n---\n# Возвраты за 30 дней\n\nОкно возврата теперь 30 дней. Ручное одобрение больше не нужно: возврат до 5000 рублей проходит автоматически (автовозврат).\n`;
    check((await post(story, null)).status === 401, 'no token: refused (401)');
    check((await post('Окно возврата теперь 30 дней.')).status === 400, 'no front matter: refused (400)');
    check((await post('---\ntitle: Без графа\n---\nтекст')).status === 400, 'no graph in the header: refused (400)');
    check((await post('---\ngraph: no-such-graph\n---\nтекст')).status === 400, 'a graph the server does not hold: refused (400)');
    const ok = await post(story);
    check(ok.status === 201 && /STORY-1/.test(ok.text), 'a changeset naming its graph is stored (201)');
    check((await post(story)).status === 409, 'the same id again: refused (409)');
    const pending = await fetch(BASE + `api/graphs/${gid}/changesets`).then((r) => r.text());
    check(/STORY-1/.test(pending) && /Возвраты за 30 дней/.test(pending), 'the graph lists it as pending');

    // --- the graph shows it; picked, the assistant reads it against the graph ----
    await page.reload({ waitUntil: 'networkidle0' }); await sleep(800);
    check(/Изменений: 1/.test(await page.$eval('.changeset-badge', (e) => e.textContent).catch(() => '')), 'the map says one changeset waits');
    await page.evaluate(() => { window.__script = [{
      concepts: [{ id: 'auto-refund', title: 'Автовозврат', definition: 'Возврат до 5000 рублей проходит без одобрения.', evidence: 'возврат до 5000 рублей проходит автоматически' }],
      changes: [{ id: 'refund-window', definition: 'Возврат возможен в течение 30 дней.', why: 'Окно возврата теперь 30 дней.' }],
      removals: [{ id: 'manual-approval', why: 'Ручное одобрение больше не нужно' }, { id: 'no-such-node', why: 'выдумка' }],
      note: 'Окно возврата длиннее, одобрение ушло.' }]; window.__calls = []; });
    await page.click('.burger'); await sleep(300);
    await page.evaluate(() => [...document.querySelectorAll('.changeset-row')].find((r) => /Возвраты за 30 дней/.test(r.textContent)).click());
    for (let i = 0; i < 60 && (await page.$$('.cards .card')).length < 3; i++) await sleep(200);
    const calls = await page.evaluate(() => window.__calls);
    const intake = calls.find((c) => /ЭТО ИЗМЕНЕНИЕ/.test(c.system));
    check(!!intake && /refund-window «Окно возврата»: Возврат возможен в течение 14 дней/.test(intake.system),
      'the model reads the changeset with the existing topics it may touch, as they stand');
    const cards = await page.$$eval('.cards .card', (cs) => cs.map((c) => c.textContent));
    check(cards.some((c) => /Автовозврат/.test(c)), 'a new topic arrives as a card');
    check(cards.some((c) => /Окно возврата/.test(c) && /14 дней/.test(c) && /30 дней/.test(c) && /Окно возврата теперь 30 дней/.test(c)),
      'an EDIT of an existing topic: old → new, with the sentence that asks for it');
    check(cards.some((c) => /Ручное одобрение/.test(c) && /больше не нужно/.test(c)) && !cards.some((c) => /выдумка/.test(c)),
      'a REMOVAL of an existing topic; one naming nothing is dropped');
    check(!/STORY-1/.test(await fetch(BASE + `api/graphs/${gid}/changesets`).then((r) => r.text())), 'picked up, it is no longer pending (a source of the graph)');

    // --- applied: each card through the server ---------------------------------
    const accept = (re) => page.evaluate((src) => { const c = [...document.querySelectorAll('.cards .card')].find((x) => new RegExp(src).test(x.textContent));
      c.querySelector('button.primary').click(); }, re);
    await accept('Окно возврата'); await sleep(900);
    await accept('Ручное одобрение'); await sleep(900);
    await accept('Автовозврат'); await sleep(1200);
    const log = await fetch(BASE + 'api/graphs/' + gid).then((r) => r.text());
    check(/:node-set "refund-window" \{:definition "Возврат возможен в течение 30 дней\."\}/.test(log), 'the edit is on the server');
    check(/:node-set "manual-approval" \{:status :deprecated\}/.test(log), 'the removal deprecates the topic on the server (never deleted)');
    check(/Автовозврат/.test(log) && /:source-id "changeset:STORY-1"|changeset:STORY-1/.test(log), 'the new topic is on the server, grounded in the changeset');

    // --- the assistant lists and takes up changesets ---------------------------
    await post(`---\ngraph: ${gid}\nid: STORY-2\n---\n# Скидки для постоянных клиентов\n\nПостоянным клиентам скидка 5%.\n`);
    await page.evaluate(() => { window.__script = [{ tools: [{ name: 'list-changesets', args: {} }] },
      { concepts: [], note: 'Ждёт одно изменение.', actions: [{ name: 'apply-changeset', args: { id: 'STORY-2' } }, { name: 'apply-changeset', args: { id: 'NO-SUCH' } }] }]; window.__calls = []; });
    await page.type('.chat .composer textarea', 'что ждёт применения?'); await page.keyboard.press('Enter');
    for (let i = 0; i < 40 && !(await page.$('.card-action')); i++) await sleep(200);
    const looked = await page.evaluate(() => window.__calls.map((c) => c.system).join('\n'));
    const acts = await page.$$eval('.card-action', (cs) => cs.map((c) => c.textContent));
    check(/list-changesets/.test(looked) && /apply-changeset/.test(looked), 'the assistant is offered list-changesets and apply-changeset');
    check(acts.length === 1 && /Взять изменение «Скидки для постоянных клиентов»/.test(acts[0]), 'a pending changeset becomes a one-tap card; an unknown id is dropped');
  } catch (e) {
    console.log('CHECK CRASHED', e.message); failures++;
  } finally {
    await browser.close(); server.kill();
  }
  console.log(failures ? `CHANGESET CHECK FAILED: ${failures}` : 'CHANGESET CHECK OK');
  process.exit(failures ? 1 : 0);
})();

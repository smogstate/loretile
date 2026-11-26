// Graphs kept by the server, SERVER-FIRST (wiki/Graph-Store.md): two browsers
// (separate profiles) on one server. A makes a graph — it reaches the server
// at once; B opens it from «На сервере»; an edit in either reaches the other;
// the same property set two ways: the later one stands, no card; B edits a
// node A removed: the server refuses it, B's edit goes back and the banner
// says why; a server out of reach takes the edit back; an edit never
// confirmed is gone after a reload; an ontology changed on one device
// reaches the graphs bound to it on the other.
// Usage (dev or release bundle in public/): node test/sync-check.js
const { spawn, execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const puppeteer = require('puppeteer-core');
const G = require('./lib/graphs');
const ROOT = path.resolve(__dirname, '..');
const PORT = 8296;
const BASE = `http://localhost:${PORT}/`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loretile-sync-'));
  fs.writeFileSync(path.join(dir, 'config.edn'), `{:server {:port ${PORT}} :app {:demos false}}`);
  const cp = execFileSync('clojure', ['-Spath'], { cwd: path.join(ROOT, 'server') }).toString().trim();
  const server = spawn('java', ['-cp', `${path.join(ROOT, 'server/src')}:${cp}`, 'clojure.main', '-m', 'loretile.server.main'], {
    env: { ...process.env, LORETILE_CONFIG: 'file://' + path.join(dir, 'config.edn'),
           SERVER_PUBLIC_DIR: path.join(ROOT, 'public'), GRAPHS_URL: 'file://' + path.join(dir, 'graphs') },
    stdio: ['ignore', 'ignore', 'inherit'],
  });
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  try {
    for (let i = 0; i < 60; i++) {
      if (await fetch(BASE + 'api/health').then((r) => r.ok, () => false)) break;
      await sleep(500);
    }
    const open = async () => {
      const ctx = await browser.createBrowserContext();
      const page = await ctx.newPage();
      page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
      page.on('dialog', (d) => d.accept());
      await page.setViewport({ width: 1280, height: 900 });
      await page.evaluateOnNewDocument(() => {
        if (!sessionStorage.getItem('seeded')) {
          localStorage.clear(); sessionStorage.setItem('seeded', '1');
          localStorage.setItem('loretile-locale', 'ru');
          for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
        }
      });
      await page.goto(BASE, { waitUntil: 'networkidle0' });
      return page;
    };
    // a graph's log in backend mode (wiki/Graph-Store.md § Backend mode): what the
    // server holds, read from here, plus what is still unconfirmed in that page —
    // the page keeps no log of its own
    const log = async (p, g) => {
      const gid = g || await p.evaluate(() => cljs.core.deref(loretile.store.current));
      const srv = await fetch(BASE + 'api/graphs/' + gid).then((r) => r.text(), () => '');
      return srv + '\n' + await p.evaluate((x) => cljs.core.pr_str(loretile.store.graph_events(x)), gid);
    };
    // the guard's refusal, printed — or null when the event was stored
    // backend mode (wiki/Graph-Store.md § Backend mode, step 3): an edit's answer is
    // the server's — append waits for it (nil = stored and in the graph);
    // appendNoWait sends and does not wait (the server out of reach on purpose)
    const append = (p, edn) => p.evaluate(async (s) => {
      const r = await Promise.race([loretile.store.append_BANG_(cljs.reader.read_string(s)), new Promise((ok) => setTimeout(() => ok('TIMEOUT'), 8000))]);
      return r ? (r === 'TIMEOUT' ? r : cljs.core.pr_str(r)) : null; }, edn);
    const appendNoWait = (p, edn) => p.evaluate((s) => { loretile.store.append_BANG_(cljs.reader.read_string(s)); }, edn);
    // one whole sync cycle of the open graph, waited for (retried while one is already running)
    const syncNow = async (p) => {
      for (let i = 0; i < 30; i++) {
        const done = await p.evaluate(() => loretile.sync.sync_BANG_(cljs.core.deref(loretile.store.current)).then((r) => r !== null && r !== undefined));
        if (done) return;
        await sleep(300);
      }
    };
    const onServer = async (gid) => (await fetch(BASE + 'api/graphs/' + gid).then((r) => r.text()));

    // A makes a graph and a node: it reaches the server
    const A = await open();
    await G.newGraph(A, BASE, 'Общий граф');
    await A.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(300);
    await A.evaluate(() => [...document.querySelectorAll('.rail .rbtn')].find((e) => /Тема/.test(e.title)).click());
    await A.waitForSelector('.modal input'); await A.type('.modal input', 'Икс'); await A.click('.modal button.primary');
    await sleep(1500);   // the edit goes to the server at once
    await syncNow(A);
    const gid = await A.evaluate(() => cljs.core.deref(loretile.store.current));
    const nid = (await log(A)).match(/:node-set "([^"]+)" \{:title "Икс"/)[1];
    check(/Икс/.test(await onServer(gid)), 'A\'s new graph and node reached the server a moment after the edit');
    const list = await fetch(BASE + 'api/graphs').then((r) => r.text());
    check(new RegExp(`:id "${gid}"`).test(list) && /Общий граф/.test(list), 'the server lists it under its title');

    // B opens it from «На сервере»
    const B = await open();
    await B.goto(BASE + '#/graphs', { waitUntil: 'networkidle0' }); await sleep(800);
    const offered = await B.$$eval('.server-graphs .server-row', (rs) => rs.map((r) => r.textContent));
    check(offered.some((t) => /Общий граф/.test(t)), 'B sees it under «На сервере»');
    await B.click('.server-graphs .open-server'); await sleep(1500);
    check(await B.evaluate(() => document.querySelectorAll('.node').length) === 1 && /Икс/.test(await log(B)), 'B opens it and has A\'s node');
    check(await B.evaluate(() => window.loretileAppGraphIsServers()), 'B\'s graph is exactly the server\'s (its rows), and B keeps no log');

    // backend mode, the clean way (wiki/Graph-Store.md): a boot builds the graph
    // from the server's rows at ANY size, never a refold here
    await B.reload({ waitUntil: 'networkidle0' }); await sleep(800);
    check(await B.evaluate(() => window.loretileBootedFrom()) === 'rows' && await B.evaluate(() => window.loretileAppGraphIsServers()),
      'B\'s reload builds its graph from the server\'s rows: the server\'s graph exactly');
    const kept = await B.evaluate(() => new Promise((ok) => {
      const r = indexedDB.open('loretile');
      r.onsuccess = () => { const d = r.result;
        if (!d.objectStoreNames.contains('events')) { d.close(); return ok(0); }
        const q = d.transaction('events', 'readonly').objectStore('events').count();
        q.onsuccess = () => { d.close(); ok(q.result); }; q.onerror = () => { d.close(); ok(-1); }; };
      r.onerror = () => ok(0);
    }).then((n) => ({ idb: n, suffix: cljs.core.count(loretile.store.graph_events(cljs.core.deref(loretile.store.current))) })));
    check(kept.idb === 0 && kept.suffix === 0, `B keeps no log: no event in IndexedDB, nothing in memory past the server's rows (${JSON.stringify(kept)})`);

    // an edit in B reaches A — in ONE request: the POST, no pull before it
    await B.evaluate(() => { window.__api = []; const f = window.fetch;
      window.fetch = (u, o) => { if (String(u).includes('api/graphs')) window.__api.push((o && o.method) || 'GET'); return f(u, o); }; });
    await append(B, `{:ev/type :change :ops [[:node-set "${nid}" {:owner "Борис"}]]}`);
    await sleep(800);
    const calls = await B.evaluate(() => window.__api);
    check(calls.length === 1 && calls[0] === 'POST', `an edit makes exactly one request, a POST (${calls.join(', ') || 'none'})`);
    // a stored push used to throw in the browser (a local `seq` shadowed
    // clojure.core/seq): the edit was taken back as «unreachable» until a pull
    check(/:owner "Борис"/.test(await log(B)) && await B.evaluate(() => cljs.core.deref(loretile.store.last_error)) === null,
      'the stored edit stays, and no error banner');
    await syncNow(B); await syncNow(A);
    check(/:owner "Борис"/.test(await log(A)), 'B\'s property reaches A');
    // phase C: what the other did, as pills
    const pills = async (p) => { await sleep(400); return p.$$eval('.peer-pill', (ps) => ps.map((x) => x.textContent)).catch(() => []); };
    check((await pills(A)).some((t) => /1\s*свойство изменено/.test(t)), 'A is shown what B did: «1 свойство изменено»');
    const refused = await append(B, `{:ev/type :change :ops [[:node-set "n-2" {:title "Второй"}] [:node-set "n-3" {:title "Третий"}] [:edge-set "n-2" :see-also "${nid}" {}]]}`);
    check(!refused, 'B adds two nodes and a link' + (refused ? ': ' + refused : ''));
    await syncNow(B); await syncNow(A);
    const more = await pills(A);
    check(more.some((t) => /2\s*новых узла/.test(t)) && more.some((t) => /1\s*новая связь/.test(t)), 'and on the next sync «2 новых узла», «1 новая связь»: ' + more.join(' | '));
    await A.screenshot({ path: path.join(__dirname, 'shot-sync-pills.png') });
    await A.setViewport({ width: 390, height: 800 }); await sleep(200);
    await A.screenshot({ path: path.join(__dirname, 'shot-sync-pills-phone.png') });
    await A.setViewport({ width: 1280, height: 900 });
    await sleep(8500);
    check((await pills(A)).length === 0, 'the pills fade after 8 s');

    // the same property set two ways: the server takes both in order — the
    // later one stands; nobody is asked (server-first has no conflict cards)
    await append(A, `{:ev/type :change :ops [[:node-set "${nid}" {:stage "черновик"}]]}`);
    await syncNow(A);
    await append(B, `{:ev/type :change :ops [[:node-set "${nid}" {:stage "готово"}]]}`);
    await syncNow(B); await syncNow(A);
    const aLog = await log(A);
    check(aLog.lastIndexOf(':stage "готово"') > aLog.lastIndexOf(':stage "черновик"'), 'the same property set by both: the later value stands for both');
    await B.goto(BASE + '#/chat', { waitUntil: 'networkidle0' }); await sleep(400);
    check(await B.$('.card-conflict') === null && await B.$('.conflict-badge') === null, 'and nobody gets a conflict card');

    // A removes the node; B, not yet knowing, edits it: the server refuses B's edit.
    // «Not yet knowing» is made sure: B pulls every 15 s, and under load that pull
    // landed in between (make checks, 2026-11-26) — for this step B's pulls answer
    // «nothing new»; its edit still goes to the server
    await B.evaluate((g) => { window.__pullFetch = window.fetch;
      window.fetch = (u, o) => {
        if (!(o && o.method === 'POST') && new RegExp('api/graphs/' + g + '\\?after=').test(String(u))) {
          const known = cljs.core.get(cljs.core.get(cljs.core.deref(loretile.sync.sync_state), g), cljs.core.keyword('seq'));
          return Promise.resolve(new Response(`{:seq ${known} :events []}`, { status: 200 }));
        }
        return window.__pullFetch(u, o);
      }; }, gid);
    await append(A, `{:ev/type :change :ops [[:node-remove "${nid}"]]}`);
    await syncNow(A);
    await B.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(300);
    // no guard here in backend mode: the edit goes to the server, whose guard
    // judges it on the graph as it stands there — and its answer is the refusal
    const answer = await append(B, `{:ev/type :change :ops [[:node-set "${nid}" {:priority "высокий"}]]}`);
    check(/server-refused/.test(answer || ''), 'B does not judge it here: the edit goes to the server, and the answer is its refusal (' + answer + ')');
    await syncNow(B); await sleep(300);
    check(/Сервер не принял/.test(await B.$eval('div.error', (e) => e.textContent).catch(() => '')), 'the server refuses it, and B\'s banner says so');
    check(!/:priority "высокий"/.test(await log(B)) && !/:priority "высокий"/.test(await onServer(gid)), 'the refused edit is gone from B, and never reached the server');
    await B.evaluate(() => { window.fetch = window.__pullFetch; cljs.core.reset_BANG_(loretile.store.last_error, null); });

    // the server out of reach: no offline — the edit goes back
    await B.evaluate(() => { window.__fetch = window.fetch;
      window.fetch = (u, o) => String(u).includes('api/graphs') ? Promise.reject(new TypeError('offline')) : window.__fetch(u, o); });
    await appendNoWait(B, `{:ev/type :change :ops [[:node-set "n-2" {:note "без сети"}]]}`);
    await sleep(800);
    check(/Сервер недоступен/.test(await B.$eval('div.error', (e) => e.textContent).catch(() => '')), 'out of reach: the banner says the edit was not saved');
    check(!/без сети/.test(await log(B)), 'and the edit is taken back');
    await B.evaluate(() => { window.fetch = window.__fetch; cljs.core.reset_BANG_(loretile.store.last_error, null); });

    // an edit the server never confirmed does not survive a reload
    await B.evaluate(() => { window.__fetch = window.fetch;
      window.fetch = (u, o) => (o && o.method === 'POST' && String(u).includes('api/graphs')) ? new Promise(() => {}) : window.__fetch(u, o); });
    await appendNoWait(B, `{:ev/type :change :ops [[:node-set "n-2" {:note "не подтверждено"}]]}`);
    await sleep(300);
    check(/не подтверждено/.test(await log(B)), 'an edit waiting for the server shows at once');
    await B.reload({ waitUntil: 'networkidle0' }); await sleep(800);
    check(!/не подтверждено/.test(await log(B)) && !/не подтверждено/.test(await onServer(gid)), 'after a reload the unconfirmed edit is gone — unconfirmed is unsaved');

    // a graph that never synced is local work, not a provisional edit: its first
    // push failing must not empty it (code review 2026-11-25: it erased the log)
    await B.evaluate(() => { window.__fetch = window.fetch;
      window.fetch = (u, o) => String(u).includes('api/graphs') ? Promise.reject(new TypeError('offline')) : window.__fetch(u, o); });
    const local = await B.evaluate(() => loretile.store.new_graph_BANG_('Местный граф'));
    await appendNoWait(B, `{:ev/type :change :ops [[:node-set "loc-1" {:title "Своё"}]]}`);
    await sleep(800);
    check(/Своё/.test(await log(B)), 'a never-synced graph keeps its log when its first push cannot reach the server');
    await B.evaluate(() => { window.fetch = window.__fetch; cljs.core.reset_BANG_(loretile.store.last_error, null); });
    await syncNow(B);
    check(/Своё/.test(await onServer(local)), 'and the next sync uploads it');
    await B.evaluate((g) => loretile.store.switch_graph_BANG_(g), gid);

    // an ONTOLOGY changed on one device reaches the graphs bound to it on another
    // (2026-11-20): B pulls the ontology graph, and its own bound graph takes the copy
    const made = await A.evaluate(() => {
      const S = loretile.store, rd = cljs.reader.read_string;
      const oid = S.new_ontology_graph_BANG_('Общая онтология', null);
      S.append_BANG_(rd('{:ev/type :change :ops [[:node-set "process" {:title "процесс"}]]}'));
      return oid;
    });
    await syncNow(A);
    const dgid = await A.evaluate(async (oid) => {
      const S = loretile.store;
      const gid = S.new_graph_BANG_('Данные по онтологии');
      const bad = await S.bind_BANG_(gid, oid);   // the server's answer, in backend mode
      return bad ? null : gid;
    }, made);
    await syncNow(A);
    check(!!dgid, 'A binds a data graph to its ontology graph; both reach the server');
    const openOnB = async (title) => {
      await B.goto(BASE + '#/graphs', { waitUntil: 'networkidle0' });
      await B.waitForSelector('.server-graphs .server-row', { timeout: 8000 }).catch(() => {});
      await B.evaluate((t) => { const r = [...document.querySelectorAll('.server-graphs .server-row')].find((x) => x.textContent.includes(t)); if (r) r.querySelector('.open-server').click(); }, title);
      await sleep(1200);
    };
    await openOnB('Общая онтология');
    await openOnB('Данные по онтологии');
    check(/:label "процесс"|:title "процесс"/.test(await log(B)) && (await B.evaluate(() => localStorage.getItem('loretile-graphs'))).includes(`:ontology "${made}"`),
      'B opens both; its copy of the data graph is bound to the ontology graph');
    await A.evaluate((oid) => {
      loretile.store.switch_graph_BANG_(oid);
      loretile.store.append_BANG_(cljs.reader.read_string('{:ev/type :change :ops [[:node-set "role" {:title "роль"}]]}'));
    }, made);
    await syncNow(A);
    await B.evaluate((oid) => loretile.store.switch_graph_BANG_(oid), made);
    await syncNow(B);
    const bData = await log(B, dgid);
    const copied = /:ontology-copy \{[^]*:label "роль"/.test(bData);
    check(copied, 'A adds a type to the ontology; when B syncs the ontology graph, B\'s bound data graph takes the new copy'
      + (copied ? '' : ` (B's last error ${await B.evaluate(() => cljs.core.pr_str(cljs.core.deref(loretile.store.last_error)))}; on the server: ${/роль/.test(await onServer(dgid))}; B's data graph ends …${bData.slice(-400)})`));

    // --- B keeps no log: what needed one is answered from the seed or by the server
    await B.evaluate((g) => loretile.store.switch_graph_BANG_(g), gid);
    await sleep(800);
    const has = (p, id) => p.evaluate((x) => cljs.core.contains_QMARK_(cljs.core.get(loretile.store.kg(), cljs.core.keyword('concepts')), x), id);
    // undo: this session's edit is reversed from the seed and the suffix
    await append(B, `{:ev/type :change :ops [[:node-set "u-1" {:title "Отменить"}]]}`);
    await sleep(800);
    const undone = await B.evaluate(() => loretile.store.undo_BANG_());
    await sleep(1000);
    check(undone && !(await has(B, 'u-1')) && /:node-remove "u-1"/.test(await onServer(gid)), 'undo works with no log here: the node goes, and the server holds the reversal');
    // the past view: the server folds the log as it stood
    const t0 = Date.now(); await sleep(50);
    await append(B, `{:ev/type :change :ops [[:node-set "p-1" {:title "Позже"}]]}`);
    await sleep(800);
    await B.evaluate((t) => cljs.core.reset_BANG_(loretile.store.as_of, cljs.reader.read_string(`{:valid ${t}}`)), t0);
    for (let i = 0; i < 40 && await B.evaluate(() => loretile.store.view_pending_QMARK_()); i++) await sleep(100);
    const past = !(await has(B, 'p-1'));
    await B.evaluate(() => cljs.core.reset_BANG_(loretile.store.as_of, null));
    check(past && await has(B, 'p-1'), 'the past view is the server\'s fold of the log as it stood: before the edit the node is not there; back at HEAD it is');
    // export: the server's log, downloaded
    await B.evaluate(() => { window.__blob = null; HTMLAnchorElement.prototype.click = function () {};
      URL.createObjectURL = (b) => { window.__blob = b; return 'blob:none'; }; loretile.store.export_edn_BANG_(); });
    for (let i = 0; i < 40 && !(await B.evaluate(() => !!window.__blob)); i++) await sleep(100);
    const exported = await B.evaluate(() => window.__blob ? window.__blob.text() : '');
    check(/Икс/.test(exported) && /Позже/.test(exported), 'export downloads the log the server holds (A\'s first node is in it, and B\'s latest)');
    // a learner opens a published course: the server folds what a learner sees
    await append(B, `{:ev/type :change :ops [[:node-set "c-topic" {:title "Тема курса"}]]}`);
    await B.evaluate(() => loretile.store.append_BANG_(loretile.ops.publish_course(cljs.reader.read_string('{:id "c-sync" :title "Курс синхронизации" :targets [["c-topic" :novice]] :locale "ru"}'))));
    await sleep(1000);
    await B.goto(BASE + '#/learn/c-sync', { waitUntil: 'networkidle0' });
    await B.waitForSelector('.learn h2', { timeout: 8000 }).catch(() => {});
    const intro = await B.$eval('.learn', (e) => e.textContent).catch(() => '');
    check(/Курс синхронизации/.test(intro) && /Тема курса/.test(intro), 'a learner opens the course: the server folds its publication, the intro names its target');
    // step 3: the browser does not fold — no fold/delta, no guard here; the edit
    // comes into B's graph as the rows the server sent back
    const folds = await B.evaluate(async (n) => {
      const F = loretile.fold, d = F.delta, g = F.guard; let calls = 0;
      F.delta = function () { calls++; return d.apply(this, arguments); };
      F.guard = function () { calls++; return g.apply(this, arguments); };
      const r = await loretile.store.append_BANG_(cljs.reader.read_string(`{:ev/type :change :ops [[:node-set "${n}" {:checked "да"}]]}`));
      F.delta = d; F.guard = g;
      const c = cljs.core.get_in(loretile.store.kg(), cljs.core.vector(cljs.core.keyword('concepts'), n));
      return { r: r === null, calls, checked: cljs.core.get(c, cljs.core.keyword('checked')) };
    }, "c-topic");
    check(folds.r && folds.calls === 0 && folds.checked === 'да', `an edit reaches B's graph with no fold here: the server's rows (${JSON.stringify(folds)})`);

  } finally {
    await browser.close();
    server.kill();
  }
  console.log(failures ? 'SYNC CHECK FAILED' : 'SYNC CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

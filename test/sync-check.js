// Graphs kept by the server, local-first sync (wiki/Graph-Sync.md, phase B):
// two browsers (separate profiles) on one server. A makes a graph — it
// reaches the server; B opens it from «На сервере»; an edit in either reaches
// the other; the same property set two ways becomes a CONFLICT CARD for a
// person, and «оставить моё» wins for both; a node edited by B but removed
// by A comes back on «вернуть и оставить моё».
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
    const log = (p) => p.evaluate(() => loretileLog() || '');
    // the guard's refusal, printed — or null when the event was stored
    const append = (p, edn) => p.evaluate((s) => { const r = loretile.store.append_BANG_(cljs.reader.read_string(s)); return r ? cljs.core.pr_str(r) : null; }, edn);
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
    await sleep(3000);   // the push follows an edit after ~2 s
    await syncNow(A);
    const gid = await A.evaluate(() => cljs.core.deref(loretile.store.current));
    const nid = (await log(A)).match(/:id "([^"]+)", :title "Икс"/)[1];
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

    // an edit in B reaches A
    await append(B, `{:ev/type :change :ops [[:node-set "${nid}" {:owner "Борис"}]]}`);
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

    // the same property set two ways: a conflict card for the one who syncs second
    await append(A, `{:ev/type :change :ops [[:node-set "${nid}" {:stage "черновик"}]]}`);
    await append(B, `{:ev/type :change :ops [[:node-set "${nid}" {:stage "готово"}]]}`);
    await syncNow(A); await syncNow(B);
    await B.goto(BASE + '#/chat', { waitUntil: 'networkidle0' }); await sleep(600);
    const card = await B.$eval('.card-conflict', (e) => e.textContent).catch(() => '');
    check(/stage/.test(card) && /черновик/.test(card) && /готово/.test(card), 'B gets a conflict card: theirs «черновик», mine «готово»');
    check(await B.$('.nav-chat .conflict-badge') !== null, 'and a badge on the assistant');
    check(await B.$('.peer-pill.hot.conflicts') !== null, 'and a red pill: a conflict waits');
    await B.screenshot({ path: path.join(__dirname, 'shot-sync-conflict.png') });
    check(!/:stage "готово"/.test(await onServer(gid)), 'B\'s clashing value was held back, not sent');
    await B.click('.card-conflict .conflict-mine'); await sleep(500); await syncNow(B);
    check(await B.$('.card-conflict') === null, '«оставить моё» resolves it');
    await syncNow(A);
    const aLog = await log(A);
    check(aLog.lastIndexOf(':stage "готово"') > aLog.lastIndexOf(':stage "черновик"'), 'and A ends with B\'s value');

    // A removes the node B is editing: B decides to bring it back
    await append(A, `{:ev/type :change :ops [[:node-remove "${nid}"]]}`);
    await append(B, `{:ev/type :change :ops [[:node-set "${nid}" {:priority "высокий"}]]}`);
    await syncNow(A); await syncNow(B);
    await B.goto(BASE + '#/chat', { waitUntil: 'networkidle0' }); await sleep(600);
    check(/удалил/.test(await B.$eval('.card-conflict', (e) => e.textContent).catch(() => '')), 'B is told the node it changed was removed');
    await B.click('.card-conflict .conflict-mine'); await sleep(500); await syncNow(B);
    await syncNow(A);
    await A.goto(BASE + '#/', { waitUntil: 'networkidle0' }); await sleep(800);
    check(await A.evaluate(() => document.querySelectorAll('.node').length) === 3 && /:priority "высокий"/.test(await log(A)),
      '«вернуть и оставить моё» brings it back for both, with B\'s edit');
  } finally {
    await browser.close();
    server.kill();
  }
  console.log(failures ? 'SYNC CHECK FAILED' : 'SYNC CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

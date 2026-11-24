// The Loretile server (server/) in front of the RELEASE bundle (`make release`
// first): a fake gateway that accepts only its second token, the server with a
// token provider file and env overrides, the app in backend mode, and the
// server-kept graphs API (list, load after a seq, store with resends skipped),
// and the server's projection (wiki/Graph-Store.md milestone 4): a demo log
// judged and projected — structure counts equal the fold's, ETag/304, node
// detail, FTS, a cycle refused with nothing stored, changes-since after an
// edit, embeddings under a daily cap that fails closed, a restart with no
// rebuild, a lost projection rebuilt.
// Usage: node test/server-check.js
const { spawn, execFileSync } = require('child_process');
const fs = require('fs');
const http = require('http');
const os = require('os');
const path = require('path');
const puppeteer = require('puppeteer-core');

const ROOT = path.resolve(__dirname, '..');
const CHROME = process.env.CHROME_BIN || '/usr/bin/google-chrome';
const PORT = 8297;
const GW = 8298;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const fail = (m) => { console.error('SERVER CHECK FAILED: ' + m); process.exitCode = 1; };

(async () => {
  const seen = [];
  const embedded = []; // every input the fake /embeddings received
  // a fake vector: letter counts folded into 16 dimensions
  const vec = (t) => { const v = new Array(16).fill(0); for (const ch of t.toLowerCase()) v[ch.codePointAt(0) % 16]++; return v; };
  const gateway = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      seen.push({ url: req.url, auth: req.headers.authorization, body });
      const ok = req.headers.authorization === 'Bearer tok-2';
      res.writeHead(ok ? 200 : 401, { 'content-type': 'application/json' });
      if (ok && req.url === '/v1/embeddings') {
        const input = JSON.parse(body).input;
        embedded.push(...input);
        return res.end(JSON.stringify({ data: input.map((t, i) => ({ index: i, embedding: vec(t) })) }));
      }
      res.end(JSON.stringify({ choices: [{ message: { content: 'ok' } }], usage: { total_tokens: 3 } }));
    });
  }).listen(GW);

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loretile-server-'));
  fs.writeFileSync(path.join(dir, 'token.clj'),
    '(def n (atom 0))\n(fn [] {:token (str "tok-" (swap! n inc)) :expires-in-ms 600000})\n');
  fs.writeFileSync(path.join(dir, 'config.edn'),
    `{:server {:port ${PORT}} :llm {:url "http://127.0.0.1:${GW}/v1/chat" :model "from-file"` +
    ` :token-provider-file "${path.join(dir, 'token.clj')}"}}`);

  // the classpath is relative to server/ (src, ../src), so java runs there
  const SERVER = path.join(ROOT, 'server');
  const cp = execFileSync('clojure', ['-Spath'], { cwd: SERVER }).toString().trim();
  const java = (args, opts) => spawn('java', ['--enable-native-access=ALL-UNNAMED', '-cp', cp, ...args], { cwd: SERVER, ...opts });
  const CAP = 2000; // tokens a day: the SQL demo's texts (≈1 400) fit, a second demo's do not
  let log = '';
  const start = () => {
    const p = java(['clojure.main', '-m', 'loretile.server.main'], {
      env: { ...process.env, LORETILE_CONFIG: 'file://' + path.join(dir, 'config.edn'),
             LLM_MODEL: 'from-env', SERVER_PUBLIC_DIR: path.join(ROOT, 'public'),
             GRAPHS_URL: 'file://' + path.join(dir, 'graphs'),
             LLM_EMBED_URL: `http://127.0.0.1:${GW}/v1/embeddings`, LLM_EMBED_MODEL: 'fake-emb',
             LLM_EMBED_DAILY_TOKENS: String(CAP) },
      stdio: ['ignore', 'inherit', 'pipe'],
    });
    p.stderr.on('data', (d) => { log += d; process.stderr.write(d); });
    return p;
  };
  const healthy = async () => {
    for (let i = 0; i < 120; i++) {
      if (await fetch(`http://localhost:${PORT}/api/health`).then((r) => r.ok, () => false)) return;
      await sleep(500);
    }
    throw new Error('the server did not start');
  };
  let server = start();

  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  try {
    await healthy();
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await page.evaluateOnNewDocument(() => {
      window.requestAnimationFrame = (cb) => setTimeout(() => cb(performance.now()), 16);
      localStorage.setItem('loretile-locale', 'ru');
      for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
    });
    await page.goto(`http://localhost:${PORT}/#/settings`, { waitUntil: 'networkidle2', timeout: 30000 });
    await page.waitForSelector('.settings-page', { timeout: 15000 });

    const meta = await page.$eval('meta[name=loretile-backend]', (m) => m.content).catch(() => null);
    if (!meta || !meta.includes('"from-env"')) fail('backend meta missing or env override not applied: ' + meta);
    const s = await page.evaluate(() => ({
      text: document.querySelector('.settings-page').innerText,
      keyInputs: document.querySelectorAll('.settings-page input[type=password]').length,
      mergeUrl: !!document.querySelector('.merge-url'),
    }));
    if (s.keyInputs) fail('the key field is rendered in backend mode');
    if (s.mergeUrl) fail('the bucket field is rendered in backend mode');
    if (!s.text.includes('from-env')) fail('the settings do not name the model');

    const reply = await page.evaluate(() =>
      fetch('api/llm', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{"model":"from-env"}' })
        .then((r) => r.status));
    if (reply !== 200) fail('api/llm answered ' + reply);
    const chat = seen.filter((x) => x.url === '/v1/chat');
    if (chat.map((x) => x.auth).join(',') !== 'Bearer tok-1,Bearer tok-2') fail('token refresh on 401: ' + JSON.stringify(chat.map((x) => x.auth)));
    if (errors.length) fail('page errors: ' + errors.join('; '));

    // graphs kept by the server (wiki/Graph-Sync.md): list, load, store
    if (!meta.includes(':graphs? true')) fail('the backend meta does not say graphs are kept: ' + meta);
    const api = (p, opts) => fetch(`http://localhost:${PORT}/api/graphs${p}`, opts).then(async (r) => ({ status: r.status, text: await r.text() }));
    const post = (id, body) => api(`/${id}/events`, { method: 'POST', headers: { 'content-type': 'application/edn' }, body });
    let r = await api('');
    if (r.status !== 200 || r.text.trim() !== '[]') fail('an empty store lists no graphs: ' + r.status + ' ' + r.text);
    const ev = (id, ts, ops) => `{:ev/id "${id}" :ev/ns "g-1" :ev/actor "a" :ev/ts ${ts} :ev/type :change :ops ${ops}}`;
    r = await post('g-1', `{:after 0 :events [${ev('e1', 1, '[[:graph-set {:title "Магазин"}]]')} ${ev('e2', 2, '[[:node-set "x" {:title "Икс"}]]')}]}`);
    if (!/:seq 1/.test(r.text) || !/:stored 2/.test(r.text) || !/:events \[\]/.test(r.text)) fail('a first batch is stored as seq 1: ' + r.text);
    r = await post('g-1', `{:after 1 :events [${ev('e2', 2, '[[:node-set "x" {:title "Икс"}]]')}]}`);
    if (!/:seq 1/.test(r.text) || !/:stored 0/.test(r.text)) fail('a resent event is skipped, no new batch: ' + r.text);
    // a second browser, behind, sends its own and gets the first one's
    r = await post('g-1', `{:after 0 :events [${ev('b1', 3, '[[:node-set "y" {:title "Игрек"}]]')}]}`);
    if (!/:seq 2/.test(r.text) || !/"e1"/.test(r.text) || !/"e2"/.test(r.text) || /"b1"/.test(r.text))
      fail('a second client gets what it lacked, not its own: ' + r.text);
    r = await api('/g-1?after=1');
    if (!/:seq 2/.test(r.text) || !/"b1"/.test(r.text) || /"e1"/.test(r.text)) fail('load after a seq returns only later batches: ' + r.text);
    r = await api('/g-1');
    if (!/"e1"/.test(r.text) || !/"b1"/.test(r.text)) fail('load without after returns the whole graph: ' + r.text);
    r = await api('');
    if (!/:id "g-1"/.test(r.text) || !/:title "Магазин"/.test(r.text) || !/:events 3/.test(r.text)) fail('the list names the graph, its title and size: ' + r.text);
    r = await api('/..%2Fetc');
    if (r.status !== 400 && r.status !== 404) fail('a bad graph id is refused: ' + r.status);
    if (!fs.existsSync(path.join(dir, 'graphs', 'g-1', '000000000002.edn'))) fail('batches are files on disk');

    // --- the projection: a demo log judged, projected, read back ---------------------
    const get = (p, headers) => fetch(`http://localhost:${PORT}/api/graphs${p}`, { headers })
      .then(async (r) => ({ status: r.status, etag: r.headers.get('etag'), type: r.headers.get('content-type'), text: await r.text() }));
    const demo = fs.readFileSync(path.join(ROOT, 'kg/demo/sql.ru.edn'), 'utf8');
    const nDemo = (demo.match(/^\{:ev\/id /gm) || []).length;
    r = await post('sql', `{:after 0 :events [\n${demo}\n]}`);
    if (r.status !== 200 || !r.text.includes(`:stored ${nDemo}`) || !r.text.includes(`:head ${nDemo}`)) fail(`the demo log is stored, head ${nDemo}: ` + r.status + ' ' + r.text.slice(0, 300));
    // the oracle: the fold's own structure of the same log, on the JVM
    const expected = JSON.parse(execFileSync('java', ['--enable-native-access=ALL-UNNAMED', '-cp', cp, 'clojure.main', '-e',
      `(require '[loretile.fold :as f] '[loretile.graph-store :as gs] '[clojure.edn :as edn])
       (let [kg (f/kg (edn/read-string (str "[" (slurp "../kg/demo/sql.ru.edn") "]")))
             s (gs/kg-structure kg)]
         (println (str "[" (count (:nodes s)) "," (+ (count (:edges s)) (count (:object-links kg))) "]")))`],
      { cwd: SERVER, stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim().split('\n').pop());
    let st = await get('/sql/structure');
    const S = JSON.parse(st.text);
    if (st.status !== 200 || !/json/.test(st.type)) fail('structure is JSON: ' + st.status + ' ' + st.type);
    if (S.nodes.length !== expected[0] || S.edges.length !== expected[1]) fail(`structure counts ${S.nodes.length}/${S.edges.length}, the fold's ${expected}`);
    if (st.etag !== `"${nDemo}"`) fail('the structure ETag is the head: ' + st.etag);
    r = await get('/sql/structure', { 'If-None-Match': st.etag });
    if (r.status !== 304) fail('If-None-Match with the head answers 304: ' + r.status);
    const [from, , to] = S.edges.find((e) => e[1] === 'requires');
    r = await get('/sql/nodes/' + encodeURIComponent(from));
    const N = r.status === 200 && JSON.parse(r.text);
    if (!N || N.node[0] !== 'concepts' || !N.out.some((d) => JSON.parse(d).to === to)) fail('node detail with its edges: ' + r.status + ' ' + r.text.slice(0, 300));
    if ((await get('/sql/nodes/no-such-node')).status !== 404) fail('an unknown node is 404');
    const word = JSON.parse(N.node[1]).definition.split(/[^\p{L}]+/u).sort((a, b) => b.length - a.length)[0];
    r = await get('/sql/search?q=' + encodeURIComponent(word));
    if (r.status !== 200 || !JSON.parse(r.text).some((h) => h[0] === from)) fail(`FTS finds "${word}" in ${from}'s definition: ` + r.text.slice(0, 300));
    // a cycle: the reverse of a requires edge — refused, nothing stored
    const ev2 = (id, ops) => `{:ev/id "${id}" :ev/ns "sql" :ev/actor "a" :ev/ts 9 :ev/type :change :ops ${ops}}`;
    r = await post('sql', `{:after 1 :events [${ev2('x-ok', `[[:node-set "${from}" {:title "Пропадёт"}]]`)} ${ev2('x-cycle', `[[:edge-set "${to}" :requires "${from}" {}]]`)}]}`);
    if (r.status !== 422 || !r.text.includes(':error') || !r.text.includes('"x-cycle"')) fail('a cycle is refused with 422 naming the event: ' + r.status + ' ' + r.text);
    if (fs.existsSync(path.join(dir, 'graphs', 'sql', '000000000002.edn'))) fail('a refused batch writes no file');
    if ((await get('/sql/structure')).etag !== `"${nDemo}"`) fail('a refused batch moves no head');
    r = await get('/sql/search?q=' + encodeURIComponent('Пропадёт'));
    if (JSON.parse(r.text).some((h) => h[2] === 'Пропадёт')) fail('a refused batch projects nothing: ' + r.text);
    // an edit → changes since the head before it
    r = await post('sql', `{:after 1 :events [${ev2('x-edit', `[[:node-set "${from}" {:definition "Совершенно новое определение"}]]`)}]}`);
    if (r.status !== 200 || !r.text.includes(`:head ${nDemo + 1}`)) fail('an edit is stored: ' + r.text.slice(0, 200));
    r = await get(`/sql/changes?since=${nDemo}`);
    const C = JSON.parse(r.text);
    const rows = C.nodes.filter((n) => n[0] === from);
    if (C.head.seq !== nDemo + 1 || rows.length !== 2 || !rows.some((n) => n[6] === nDemo + 1) || !rows.some((n) => n[6] === null))
      fail('changes-since: the closed row and the new one: ' + r.text);
    if (!JSON.parse((await get('/sql/search?q=' + encodeURIComponent('совершенно'))).text).some((h) => h[0] === from)) fail('FTS finds the edited definition');

    // --- embeddings under the daily cap -------------------------------------------------
    await sleep(11000); // two embedder rounds: every pending text so far
    const sqlTexts = embedded.length;
    if (sqlTexts < 43) fail('the embedder embeds the demo\'s texts: ' + sqlTexts);
    r = await get('/sql/search?q=' + encodeURIComponent(word));
    if (!embedded.includes(word) || !JSON.parse(r.text).some((h) => h[0] === from)) fail('search embeds the query and still finds the node: ' + r.text.slice(0, 200));
    const vineyard = fs.readFileSync(path.join(ROOT, 'kg/demo/vineyard.ru.edn'), 'utf8');
    r = await post('vineyard', `{:after 0 :events [\n${vineyard}\n]}`);
    if (r.status !== 200) fail('a second demo is stored: ' + r.text.slice(0, 200));
    await sleep(11000); // two embedder rounds
    const tokens = embedded.reduce((n, t) => n + Math.ceil(t.length / 4), 0);
    if (embedded.length !== sqlTexts + 1 || tokens > CAP) fail(`past the cap nothing is embedded: ${embedded.length} texts, ${tokens} tokens`);
    if (!log.includes('embedding cap reached')) fail('the cap is logged');

    // --- a restart: no rebuild, the same head; a lost projection is rebuilt ---------------
    const restart = async () => { server.kill(); await new Promise((ok) => server.once('exit', ok)); log = ''; server = start(); await healthy(); };
    const before = (await get('/sql/structure')).text;
    await restart();
    st = await get('/sql/structure');
    if (st.etag !== `"${nDemo + 1}"` || st.text !== before) fail('after a restart the same head and structure: ' + st.etag);
    if (/rebuilt/.test(log)) fail('a restart rebuilds nothing: ' + log);
    server.kill(); await new Promise((ok) => server.once('exit', ok));
    for (const f of fs.readdirSync(path.join(dir, 'graphs')).filter((f) => f.startsWith('projection.sqlite3'))) fs.unlinkSync(path.join(dir, 'graphs', f));
    log = ''; server = start(); await healthy();
    st = await get('/sql/structure');
    if (st.etag !== `"${nDemo + 1}"` || st.text !== before || !/projection of sql rebuilt/.test(log)) fail('a lost projection is rebuilt from the batches: ' + st.etag + ' ' + log);
  } finally {
    await browser.close();
    server.kill();
    gateway.close();
  }
  if (!process.exitCode) console.log('server check OK');
})().catch((e) => { fail(e.message); process.exit(1); });

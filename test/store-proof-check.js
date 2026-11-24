// Graph-store proof (wiki/Graph-Store.md milestone 1): SQLite WASM in a
// Worker on opfs-sahpool, the design's tables, a synthetic graph of N topics.
// Serves test/store-proof + node_modules itself — no app build needed.
// Usage: node test/store-proof-check.js [N=40000]   → timings + out/store-proof.json
// Asserts correctness (counts, cycle check, FTS, history, reopen), not speed.

const http = require('http');
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');

const N = +(process.argv[2] || 40000);
const CACHE = +(process.env.CACHE_MB || 0);   // SQLite page cache in MB (0 = its default, 2 MB; 64 measured: no gain)
const CHROME = process.env.CHROME_BIN || '/usr/bin/google-chrome';
const ROOT = path.join(__dirname, '..');
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm' };
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

const server = http.createServer((req, res) => {
  const url = decodeURIComponent(req.url.split('?')[0]);
  if (url === '/favicon.ico') { res.writeHead(204); return res.end(); }
  const file = url.startsWith('/node_modules/@sqlite.org/')
    ? path.join(ROOT, url)
    : path.join(__dirname, 'store-proof', url === '/' ? 'index.html' : url);
  if (!file.startsWith(ROOT) || !fs.existsSync(file)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'content-type': TYPES[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});

async function run(page, phase, timeout) {
  await page.goto(`http://localhost:${server.address().port}/index.html?phase=${phase}&n=${N}&cache=${CACHE}`);
  await page.waitForFunction(() => window.__proof, { timeout, polling: 500 });
  return page.evaluate(() => window.__proof);
}

(async () => {
  await new Promise((r) => server.listen(0, r));
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--disable-gpu', '--enable-precise-memory-info'] });
  const results = { n: N, date: new Date().toISOString() };
  try {
    const page = await browser.newPage();
    page.on('console', (m) => m.type() === 'error' && console.log('console:', m.text()));

    const full = results.full = await run(page, 'full', 30 * 60 * 1000);
    if (full.error) throw new Error(full.error);
    const concepts = N, objects = 2 * Math.ceil(N / 4);
    check(full.fts5, 'FTS5 is compiled into the WASM build');
    check(full.structure.nodes === concepts + objects, `structure has every current node (${full.structure.nodes})`);
    check(full.structure.edges === full.cycle.relation_edges, `structure has every current edge (${full.structure.edges})`);
    check(full.node_open_found === 1000, 'node open finds every topic it asks for');
    check(full.changes_since.rows === full.changes_since.expected, `changes-since after 1000 edits = 1000 new rows + one closed row per edited topic (${full.changes_since.rows} of ${full.changes_since.expected})`);
    check(full.cycle.correct, 'cycle check: both ways agree, a closing edge is caught, a downward one is not');
    check(full.fts.edited_found, 'FTS finds the edited text (history rows replaced in the index)');
    check(full.vectors.self_first, 'cosine: a vector is its own nearest');
    check(full.rebuild_rows.nodes === concepts + objects, `rebuild restores every node (${full.rebuild_rows.nodes})`);

    // tabs: the full-phase tab is CLOSED (navigating away is not enough: the
    // back/forward cache can keep the page, its Worker and its handles alive)
    await page.close();
    const holder = await browser.newPage();
    const held = results.held = await run(holder, 'boot', 90000);
    check(!held.error, `a tab takes the pool after the holding tab was closed (${held.error ? held.error.slice(0, 120) : held.vfs_ms + ' ms, ' + held.vfs_attempts + ' attempts'})`);
    const page2 = await browser.newPage();
    const second = results.second = await run(page2, 'second', 30000);
    check(!second.opened, `a second tab cannot open the pool while another holds it (${second.error ? 'locked' : 'opened!'})`);
    // graceful handover: the holder pauses the VFS, the other tab takes it
    await holder.evaluate(() => window.__pause());
    const taken = results.taken = await run(page2, 'take', 90000);
    check(taken.opened, `after the holder hands over (pauseVfs), the other tab opens it (${taken.ms} ms, ${taken.attempts} attempts)`);
    await page2.close(); await holder.close();

    // reopen: what a boot costs once the database exists
    const page3 = await browser.newPage();
    const boot = results.boot = await run(page3, 'boot', 60000);
    check(!boot.error && boot.head > 0, `reopen reads head ${boot.head} and the structure (${boot.nodes} nodes)`);
  } catch (e) {
    console.log('FAIL', e.message); failures++;
  } finally {
    await browser.close(); server.close();
  }

  fs.mkdirSync(path.join(ROOT, 'out'), { recursive: true });
  fs.writeFileSync(path.join(ROOT, 'out', 'store-proof.json'), JSON.stringify(results, null, 1));
  const f = results.full || {};
  if (f.structure) {
    console.log(`\ncache ${f.cache_mb} MB · N=${N} topics, ${f.events} events, ${f.rows.nodes} node rows, ${f.rows.edges} edge rows, db ${f.db_mb} MB`);
    console.table({
      'init wasm / vfs (ms)': `${f.init_ms} / ${f.vfs_ms}`,
      'bulk load, all events (ms)': f.load_ms,
      'structure query / stringify (ms)': `${f.structure.query_ms} / ${f.structure.stringify_ms}`,
      'structure JSON (MB) / parse in page (ms)': `${f.structure.json_mb} / ${f.main && f.main.parse_ms}`,
      'page heap after structure (MB)': f.main && f.main.heap_mb,
      'node open, avg (ms)': f.node_open_ms,
      'edit through reader, avg per tx (ms)': f.edit_ms,
      'changes-since (ms)': f.changes_since.ms,
      'cycle: load relation / JS walk (ms)': `${f.cycle.load_relation_ms} / ${f.cycle.js_walk_ms}`,
      'cycle: recursive SQL hit / miss (ms)': `${f.cycle.sql_hit_ms} / ${f.cycle.sql_miss_ms}`,
      'FTS query, avg (ms)': f.fts.ms,
      'vectors: insert / load / cosine top20 (ms)': `${f.vectors_insert_ms} / ${f.vectors.load_ms} / ${f.vectors.cosine_top20_ms}`,
      'rebuild, all events (ms)': f.rebuild_ms,
      'reopen → structure (ms)': results.boot && results.boot.total_to_structure_ms,
      'tab takeover after the holder closed (ms / attempts)': results.held && `${results.held.vfs_ms} / ${results.held.vfs_attempts}`,
      'tab handover via pauseVfs (ms / attempts)': results.taken && `${results.taken.ms} / ${results.taken.attempts}`,
    });
  }
  console.log(failures ? `store-proof: ${failures} FAILED` : 'store-proof: OK');
  process.exit(failures ? 1 : 0);
})();

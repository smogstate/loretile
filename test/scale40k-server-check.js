// A big graph kept by the server (wiki/Graph-Store.md): N synthetic topics
// (default 40 000) stored as one batch, a server on a temp dir, then timed in a
// browser: opening it from «На сервере» (the log as JSON + the graph built from
// the server's rows) and a later boot (the graph from the server's rows again).
// Asserts the browser's graph is exactly the server's (it keeps no log). Measures —
// not in `make checks`. Usage (a built bundle in public/): node test/scale40k-server-check.js [N]
const { spawn, execFileSync } = require('child_process');
const fs = require('fs'); const os = require('os'); const path = require('path');
const puppeteer = require('puppeteer-core');
const { bigGraph } = require('./lib/big-graph');
const N = +(process.argv[2] || 40000);
const ROOT = path.resolve(__dirname, '..');
const PORT = 8297; const BASE = `http://localhost:${PORT}/`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loretile-big-'));
  fs.mkdirSync(path.join(dir, 'graphs', 'big'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'graphs', 'big', '000000000001.edn'), bigGraph(N).join('\n') + '\n');
  fs.writeFileSync(path.join(dir, 'config.edn'), `{:server {:port ${PORT}} :app {:demos false}}`);
  const cp = execFileSync('clojure', ['-Spath'], { cwd: path.join(ROOT, 'server'), env: { ...process.env, CLJ_CONFIG: fs.mkdtempSync(path.join(os.tmpdir(), 'cljcfg-')) } }).toString().trim();
  const server = spawn('java', ['-Xmx4g', '-cp', cp, 'clojure.main', '-m', 'loretile.server.main'], {
    cwd: path.join(ROOT, 'server'),
    env: { ...process.env, LORETILE_CONFIG: 'file://' + path.join(dir, 'config.edn'), SERVER_PUBLIC_DIR: path.join(ROOT, 'public'), GRAPHS_URL: 'file://' + path.join(dir, 'graphs') },
    stdio: ['ignore', 'ignore', 'inherit'],
  });
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox', '--disable-gpu', '--js-flags=--expose-gc'], protocolTimeout: 900000 });
  try {
    for (let i = 0; i < 120; i++) { if (await fetch(BASE + 'api/health').then((r) => r.ok, () => false)) break; await sleep(500); }
    let t = Date.now();
    await fetch(BASE + 'api/graphs/big/structure');
    console.log(`server: projection of ${N} topics built on first access in ${Date.now() - t} ms`);
    const page = await browser.newPage();
    page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
    await page.setViewport({ width: 1280, height: 900 });
    await page.evaluateOnNewDocument(() => { if (!sessionStorage.getItem('s')) { sessionStorage.setItem('s', '1'); localStorage.clear();
      localStorage.setItem('loretile-locale', 'ru'); for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1'); } });
    await page.goto(BASE + '#/graphs', { waitUntil: 'networkidle0' });
    await page.waitForSelector('.server-graphs .open-server', { timeout: 60000 });
    t = Date.now();
    await page.click('.server-graphs .open-server');
    await page.waitForFunction(() => document.querySelectorAll('svg.graph .node').length > 0, { timeout: 600000, polling: 250 });
    const open = Date.now() - t;
    check(open < 30000, `opening it from «На сервере» → the map: ${open} ms`);
    // backend mode keeps no log in the browser (wiki/Graph-Store.md § Backend mode):
    // the graph must be the server's exactly
    check(await page.evaluate(() => window.loretileAppGraphIsServers()), 'the browser\'s graph is exactly the server\'s rows (no log kept here)');
    await sleep(3000);
    t = Date.now();
    await page.goto(BASE + 'icon.svg'); await page.goto(BASE, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => document.querySelectorAll('svg.graph .node').length > 0, { timeout: 600000, polling: 250 });
    const boot = Date.now() - t;
    check(boot < 25000, `a later boot → the map: ${boot} ms`);
    const heap = await page.evaluate(() => { if (window.gc) gc(); return Math.round(performance.memory.usedJSHeapSize / 1e6); });
    console.log('heap MB', heap);
  } catch (e) { console.log('CHECK CRASHED', e.message); failures++; }
  finally { await browser.close(); server.kill(); }
  console.log(failures ? `scale40k-server: ${failures} FAILED` : 'scale40k-server: OK');
  process.exit(failures ? 1 : 0);
})();

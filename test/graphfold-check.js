// The fold and the guard inside the graph-store Worker (wiki/Graph-Store.md §
// Next, step 1): every log in kg/ — plus events the guard must refuse — is
// judged and projected in the Worker against SQLite and in the page in memory:
// the same verdicts and the same graph.
// Usage: serve public/ (after make engines), then: node test/graphfold-check.js [baseUrl]
const fs = require('fs'); const path = require('path');
const puppeteer = require('puppeteer-core');
const BASE = process.argv[2] || 'http://localhost:8291';
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };
const logs = [];
(function walk(d) { for (const f of fs.readdirSync(d)) { const p = path.join(d, f); fs.statSync(p).isDirectory() ? walk(p) : p.endsWith('.edn') && logs.push(p); } })(path.join(__dirname, '..', 'kg'));
const ev = (n, ops) => `{:ev/id "probe-${n}" :ev/ns "g" :ev/actor "t" :ev/ts ${n} :ev/type :change :ops ${ops}}`;

(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  try {
    const page = await browser.newPage();
    await page.goto(BASE, { waitUntil: 'networkidle0' });
    await page.evaluate(() => { localStorage.setItem('loretile-tour-done', '1'); });
    await page.waitForFunction(() => window.loretileGraphStore && window.loretileGraphStore.ready(), { timeout: 60000, polling: 250 });
    for (const file of logs) {
      const lines = fs.readFileSync(file, 'utf8').split('\n').filter((l) => l.trim() && !l.startsWith(';'));
      // events the guard must refuse, built from the log: a cycle, a duplicate proposal, a missing endpoint, a missing node
      const req = lines.join(' ').match(/\[:edge-set "([^"]+)" :requires "([^"]+)"/);
      const someNode = (lines.join(' ').match(/\[:node-set "([^"]+)" \{:title/) || [])[1];
      const probes = [
        req && ev(1, `[[:edge-set "${req[2]}" :requires "${req[1]}" {}]]`),
        someNode && ev(2, `[[:node-set "${someNode}" {:status :proposed :title "dup"}]]`),
        someNode && ev(3, `[[:edge-set "${someNode}" :see-also "nowhere" {}]]`),
        ev(4, `[[:node-remove "nowhere"]]`),
        someNode && ev(5, `[[:node-set "${someNode}" {:definition "правка из проверки"}]]`),
      ].filter(Boolean);
      const r = await page.evaluate((log) => window.loretileGraphStore.verifyInWorker(log), '[' + [...lines, ...probes].join(' ') + ']');
      const name = path.relative(path.join(__dirname, '..'), file);
      check(r.verdictsEqual && r.graphEqual, `${name}: the Worker judges and folds like the page (${lines.length + probes.length} events, ${r.refused} refused)${r.firstDiff ? ' — ' + r.firstDiff : ''}${r.graphEqual ? '' : ' — graphs differ'}`);
    }
  } catch (e) { console.log('CHECK CRASHED', e); failures++; }
  finally { await browser.close(); }
  console.log(failures ? `graphfold: ${failures} FAILED` : 'graphfold: OK');
  process.exit(failures ? 1 : 0);
})();

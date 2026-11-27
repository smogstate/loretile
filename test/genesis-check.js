// The genesis tool end to end (server/src/loretile/server/genesis.clj): a graph
// that exists only as a projection gets its events and reaches a server. A
// server on a temp dir takes the SQL demo (so its projection.sqlite3 holds a
// real graph), the tool reads that file read-only, proves its log, posts it as
// a NEW graph — and the server's graph built from those events is the
// original, row for row. Not in the browser: no bundle needed.
// Usage: node test/genesis-check.js
const { spawn, execFileSync, spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const ROOT = path.resolve(__dirname, '..');
const SERVER = path.join(ROOT, 'server');
const PORT = 8299;
const BASE = `http://localhost:${PORT}/`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loretile-genesis-'));
  const graphs = path.join(dir, 'graphs');
  fs.writeFileSync(path.join(dir, 'config.edn'), `{:server {:port ${PORT}} :app {:demos false}}`);
  const cp = execFileSync('clojure', ['-Spath'], { cwd: SERVER }).toString().trim();
  const server = spawn('java', ['-cp', `${path.join(SERVER, 'src')}:${cp}`, 'clojure.main', '-m', 'loretile.server.main'], {
    env: { ...process.env, LORETILE_CONFIG: 'file://' + path.join(dir, 'config.edn'), SERVER_PUBLIC_DIR: path.join(ROOT, 'public'), GRAPHS_URL: 'file://' + graphs },
    stdio: ['ignore', 'ignore', 'inherit'],
  });
  const genesis = (...args) => {
    const r = spawnSync('clojure', ['-M:genesis', ...args], { cwd: SERVER, encoding: 'utf8', env: { ...process.env, CLJ_CONFIG: fs.mkdtempSync(path.join(os.tmpdir(), 'cljc-')) } });
    return { code: r.status, out: (r.stdout || '') + (r.stderr || '').split('\n').filter((l) => !/^WARNING/.test(l)).join('\n') };
  };
  try {
    for (let i = 0; i < 60; i++) { if (await fetch(BASE + 'api/health').then((r) => r.ok, () => false)) break; await sleep(500); }
    // the server keeps the SQL demo: its projection now holds a real graph
    const demo = fs.readFileSync(path.join(ROOT, 'kg/demo/sql.ru.edn'), 'utf8');
    let r = await fetch(BASE + 'api/graphs/src/events', { method: 'POST', headers: { 'content-type': 'application/edn' }, body: `{:after 0 :events [\n${demo}\n]}` });
    check(r.status === 200, 'the server keeps the SQL demo as graph src');
    await fetch(BASE + 'api/graphs/src/rows').then((x) => x.text());   // its projection is built
    const db = path.join(graphs, 'projection.sqlite3');

    // the tool: read-only on the file, the log proven, posted as a new graph
    const out = path.join(dir, 'copy.log.edn');
    const g = genesis('--db', db, '--from', 'src', '--graph', 'copy', '--out', out, '--post', BASE);
    check(g.code === 0 && /proven/.test(g.out), 'the log is proven offline (replayed through the guard, it folds to the database\'s graph)' + (g.code ? ': ' + g.out.slice(-400) : ''));
    check(fs.existsSync(out) && fs.readFileSync(out, 'utf8').split('\n').filter(Boolean).length > 0, 'the log is written as a file too');
    check(/stored on the server: \d+/.test(g.out), 'and posted to the server, batch by batch');
    const rows = async (id) => JSON.parse(await fetch(BASE + `api/graphs/${id}/rows`).then((x) => x.text()));
    const [a, b] = [await rows('src'), await rows('copy')];
    const set = (xs) => new Set(xs.map((x) => JSON.stringify(x)));
    const same = (x, y) => x.size === y.size && [...x].every((v) => y.has(v));
    check(same(set(a.nodes.map(([id, c, d]) => [id, c, d])), set(b.nodes.map(([id, c, d]) => [id, c, d]))),
      `the server's graph from those events has the original's nodes, row for row (${a.nodes.length})`);
    check(same(set(a.edges), set(b.edges)) && JSON.stringify(a.discarded.sort()) === JSON.stringify(b.discarded.sort()) && a.heads === b.heads,
      `…its edges, discards and graph properties too (${a.edges.length} edges)`);

    // refusals: a graph id the server holds; a database of several graphs with no --from
    const again = genesis('--db', db, '--from', 'src', '--graph', 'copy', '--post', BASE);
    check(again.code !== 0 && /already holds a graph copy/.test(again.out), 'posting onto a graph the server already holds is refused');
    const which = genesis('--db', db);
    check(which.code !== 0 && /holds 2 graphs: name one with --from/.test(which.out), 'a database of several graphs asks which one (--from)');
  } catch (e) {
    console.log('CHECK CRASHED', e.message); failures++;
  } finally {
    server.kill();
  }
  console.log(failures ? `GENESIS CHECK FAILED: ${failures}` : 'GENESIS CHECK OK');
  process.exit(failures ? 1 : 0);
})();

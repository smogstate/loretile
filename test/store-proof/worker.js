// Graph-store proof (wiki/Graph-Store.md milestone 1): SQLite WASM on
// opfs-sahpool in a Worker, the design's tables, a synthetic graph of N
// topics, every operation the design leans on — timed. No app code.
// Driven by index.html; phases: full (build + measure), boot (reopen), second
// (a second tab tries to open the same pool).
import sqlite3InitModule from '/node_modules/@sqlite.org/sqlite-wasm/dist/index.mjs';

const G = 'proof';
const DB = '/proof.sqlite3';
const DIMS = 384;
const CACHE_MB = +(new URLSearchParams(self.location.search).get('cache') || 0);
const out = {};
const now = () => performance.now();
const ms = (t) => Math.round((now() - t) * 10) / 10;
const post = (m) => self.postMessage(m);
const step = (label) => post({ progress: label });

// deterministic words, so a run is repeatable
function rng(seed) { return () => { seed |= 0; seed = (seed + 0x6d2b79f5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
const rand = rng(42);
const VOCAB = Array.from({ length: 3000 }, (_, i) => 'w' + i.toString(36) + ['ion', 'er', 'al', 'ing', 'ty'][i % 5]);
const words = (n) => Array.from({ length: n }, () => VOCAB[Math.floor(rand() * VOCAB.length)]).join(' ');
const hash = (s) => { let h = 5381; for (let i = 0; i < s.length; i++) h = (h * 33 + s.charCodeAt(i)) >>> 0; return h.toString(16); };

const SCHEMA = `
CREATE TABLE nodes (graph TEXT, id TEXT, from_seq INTEGER, to_seq INTEGER,
  class TEXT, status TEXT, title TEXT, short TEXT, parent TEXT, text TEXT, text_hash TEXT, data TEXT,
  PRIMARY KEY (graph, id, from_seq));
CREATE UNIQUE INDEX nodes_now    ON nodes (graph, id)     WHERE to_seq IS NULL;
CREATE INDEX        nodes_parent ON nodes (graph, parent) WHERE to_seq IS NULL;
CREATE INDEX        nodes_from   ON nodes (graph, from_seq);
-- closed rows only: a plain (graph, to_seq) index also matches \`to_seq IS NULL\`
-- and the planner then picks it over the right one (node open 75 ms → see wiki)
CREATE INDEX        nodes_closed ON nodes (graph, to_seq) WHERE to_seq IS NOT NULL;
CREATE TABLE edges (graph TEXT, "from" TEXT, type TEXT, "to" TEXT, from_seq INTEGER, to_seq INTEGER,
  status TEXT, data TEXT, PRIMARY KEY (graph, "from", type, "to", from_seq));
CREATE UNIQUE INDEX edges_now  ON edges (graph, "from", type, "to") WHERE to_seq IS NULL;
CREATE INDEX        edges_to   ON edges (graph, "to")              WHERE to_seq IS NULL;
CREATE INDEX        edges_type ON edges (graph, type)              WHERE to_seq IS NULL;
CREATE INDEX        edges_fseq   ON edges (graph, from_seq);
CREATE INDEX        edges_closed ON edges (graph, to_seq) WHERE to_seq IS NOT NULL;
CREATE TABLE discarded (graph TEXT, key TEXT, seq INTEGER, PRIMARY KEY (graph, key));
CREATE TABLE events  (graph TEXT, seq INTEGER, id TEXT, recorded INTEGER, ev TEXT, PRIMARY KEY (graph, seq));
CREATE TABLE heads   (graph TEXT PRIMARY KEY, seq INTEGER, fold TEXT, props TEXT);
CREATE TABLE vectors (hash TEXT, model TEXT, vec BLOB, PRIMARY KEY (hash, model));
CREATE VIRTUAL TABLE nodes_fts USING fts5(text, content='', contentless_delete=1);
`;

// --- the synthetic graph: N topics, material on every 4th (2 objects),
// 2 `requires` edges per topic to earlier topics (acyclic by construction) ---
function generate(n) {
  const ops = []; // [kind, ...] in log order — what the decoded events would say
  for (let i = 0; i < n; i++) {
    const def = words(45);
    ops.push(['node', { id: 'n' + i, class: 'concept', title: 'Topic ' + i + ' ' + words(2), definition: def, parent: null }]);
    if (i % 4 === 0) for (const s of ['a', 'b'])
      ops.push(['node', { id: 'o' + i + s, class: 'object', title: null, definition: words(80), parent: 'n' + i }]);
  }
  for (let i = 1; i < n; i++) {
    const to = new Set();
    while (to.size < Math.min(2, i)) to.add(Math.floor(rand() * i));
    for (const j of to) ops.push(['edge', { from: 'n' + i, type: 'requires', to: 'n' + j }]);
  }
  return ops;
}

const nodeRow = (o) => {
  const text = (o.title ? o.title + '. ' : '') + o.definition;
  return { text, hash: hash(text), short: o.definition.slice(0, 120),
           data: JSON.stringify({ definition: o.definition, version: 1, 'changed-by': 'proof', kind: 'process' }) };
};
const evEdn = (seq, op) => op[0] === 'node'
  ? `{:ev/id "e${seq}" :ev/ns "${G}" :ev/actor "proof" :ev/ts ${seq} :ev/type :change :ops [[:node-set "${op[1].id}" {:definition "${op[1].definition}"}]]}`
  : `{:ev/id "e${seq}" :ev/ns "${G}" :ev/actor "proof" :ev/ts ${seq} :ev/type :change :ops [[:edge-set "${op[1].from}" :requires "${op[1].to}" {:provenance :authored}]]}`;

// --- applying one op through a reader: what the delta fold + apply-changes! do to SQLite ---
function applier(db) {
  const st = {
    nodeNow: db.prepare(`SELECT rowid, data FROM nodes WHERE graph=? AND id=? AND to_seq IS NULL`),
    close:   db.prepare(`UPDATE nodes SET to_seq=? WHERE rowid=?`),
    ins:     db.prepare(`INSERT INTO nodes VALUES (?,?,?,NULL,?,?,?,?,?,?,?,?) RETURNING rowid`),
    ftsDel:  db.prepare(`DELETE FROM nodes_fts WHERE rowid=?`),
    ftsIns:  db.prepare(`INSERT INTO nodes_fts (rowid, text) VALUES (?,?)`),
    edgeNow: db.prepare(`SELECT rowid FROM edges WHERE graph=? AND "from"=? AND type=? AND "to"=? AND to_seq IS NULL`),
    edgeIns: db.prepare(`INSERT INTO edges VALUES (?,?,?,?,?,NULL,'accepted',?)`),
    ev:      db.prepare(`INSERT INTO events VALUES (?,?,?,?,?)`),
    head:    db.prepare(`INSERT INTO heads (graph, seq) VALUES (?,?) ON CONFLICT(graph) DO UPDATE SET seq=excluded.seq`),
  };
  const one = (s, bind) => { s.bind(bind); const r = s.step() ? s.get([]) : null; s.reset(); return r; };
  const run = (s, bind) => { s.bind(bind).stepReset(); };
  return {
    st,
    node(seq, o, withEvent) {
      const old = one(st.nodeNow, [G, o.id]);                       // reader.node
      if (old) { run(st.close, [seq, old[0]]); run(st.ftsDel, [old[0]]); }
      const r = nodeRow(o);
      const [rowid] = one(st.ins, [G, o.id, seq, o.class, 'accepted', o.title, o.title ? r.short : null, o.parent, r.text, r.hash, r.data]);
      run(st.ftsIns, [rowid, r.text]);
      if (withEvent) run(st.ev, [G, seq, 'e' + seq, seq, withEvent]);
    },
    edge(seq, e, withEvent) {
      if (one(st.edgeNow, [G, e.from, e.type, e.to])) return;        // reader.edge
      run(st.edgeIns, [G, e.from, e.type, e.to, seq, '{"provenance":"authored"}']);
      if (withEvent) run(st.ev, [G, seq, 'e' + seq, seq, withEvent]);
    },
    head(seq) { run(st.head, [G, seq]); },
    finalize() { Object.values(st).forEach((s) => s.finalize()); },
  };
}

// A Worker that ends releases its access handles asynchronously, so a tab
// taking over right after can still find them locked: retry (`tries`), and
// report how long the handover took.
async function openPool(sqlite3, tries = 1) {
  const t = now();
  for (let i = 1; ; i++) {
    try {
      const pool = await sqlite3.installOpfsSAHPoolVfs({ name: 'loretile-proof' });
      return { pool, ms: ms(t), attempts: i };
    } catch (e) {
      if (i >= tries) throw e;
      await new Promise((r) => setTimeout(r, 100));
    }
  }
}

async function init() {
  const t = now();
  const sqlite3 = await sqlite3InitModule();
  return { sqlite3, ms: ms(t) };
}

// SQLite builds the JSON itself: one string crosses into JS instead of 140k
// rows converted cell by cell (that conversion was ~1 s of a 1.4 s query)
function structure(db) {
  const t = now();
  const nodes = db.selectValue(`SELECT json_group_array(json_array(id, class, status, title, short, parent))
                                FROM nodes WHERE graph=? AND to_seq IS NULL`, [G]);
  const edges = db.selectValue(`SELECT json_group_array(json_array("from", type, "to", status))
                                FROM edges WHERE graph=? AND to_seq IS NULL`, [G]);
  const q = ms(t);
  const t2 = now();
  const json = `{"nodes":${nodes},"edges":${edges}}`;
  return { query: q, stringify: ms(t2),
           nodes: db.selectValue(`SELECT count(*) FROM nodes WHERE graph=? AND to_seq IS NULL`, [G]),
           edges: db.selectValue(`SELECT count(*) FROM edges WHERE graph=? AND to_seq IS NULL`, [G]), json };
}

async function full(n) {
  const { sqlite3, ms: tInit } = await init();
  out.init_ms = tInit;
  out.fts5 = sqlite3.capi.sqlite3_compileoption_used('ENABLE_FTS5') === 1;
  const { pool, ms: tPool } = await openPool(sqlite3);
  out.vfs_ms = tPool;
  pool.unlink(DB);
  const db = new pool.OpfsSAHPoolDb(DB);
  // the default 2 MB page cache sends every index update of a bulk load to disk
  if (CACHE_MB) db.exec(`PRAGMA cache_size = -${CACHE_MB * 1024}`);
  out.cache_mb = CACHE_MB || 2;
  db.exec(SCHEMA);

  step('generate');
  let t = now();
  const ops = generate(n);
  out.generate_ms = ms(t);
  out.events = ops.length;

  // bulk load: every op through the applier, as a first import would
  step('load');
  t = now();
  const a = applier(db);
  for (let i = 0; i < ops.length; i += 5000) {
    db.transaction(() => {
      for (let k = i; k < Math.min(i + 5000, ops.length); k++) {
        const seq = k + 1, op = ops[k];
        op[0] === 'node' ? a.node(seq, op[1], evEdn(seq, op)) : a.edge(seq, op[1], evEdn(seq, op));
      }
      a.head(Math.min(i + 5000, ops.length));
    });
  }
  out.load_ms = ms(t);
  out.db_mb = Math.round(db.selectValue('PRAGMA page_count') * db.selectValue('PRAGMA page_size') / 1e5) / 10;
  out.rows = { nodes: db.selectValue('SELECT count(*) FROM nodes'), edges: db.selectValue('SELECT count(*) FROM edges') };

  // structure
  step('structure');
  const s = structure(db);
  out.structure = { query_ms: s.query, stringify_ms: s.stringify, nodes: s.nodes, edges: s.edges, json_mb: Math.round(s.json.length / 1e5) / 10 };
  post({ structureJson: s.json });

  // where the structure's time goes: SQLite stepping vs building JS rows
  {
    const tq = (label, sql) => { const st = db.prepare(sql); st.bind([G]); let k = 0, t0 = now(); while (st.step()) k++; const step = ms(t0); st.reset();
      st.bind([G]); t0 = now(); const rows = []; while (st.step()) rows.push(st.get([])); const get = ms(t0); st.finalize(); return { step_ms: step, step_and_get_ms: get, rows: k }; };
    out.structure_split = {
      nodes: tq('nodes', `SELECT id, class, status, title, short, parent FROM nodes WHERE graph=? AND to_seq IS NULL`),
      nodes_noindex: tq('nodes', `SELECT id, class, status, title, short, parent FROM nodes NOT INDEXED WHERE graph=? AND to_seq IS NULL`),
      edges: tq('edges', `SELECT "from", type, "to", status FROM edges WHERE graph=? AND to_seq IS NULL`),
    };
  }

  // which index each hot query uses (a wrong pick is a 50× slowdown)
  const plan = (sql) => db.selectArrays('EXPLAIN QUERY PLAN ' + sql, [G, 'x', 'y'].slice(0, (sql.match(/\?/g) || []).length)).map((r) => r[3]).join(' | ');
  out.plans = {
    node: plan(`SELECT id FROM nodes WHERE graph=? AND id=? AND to_seq IS NULL`),
    kids: plan(`SELECT id FROM nodes WHERE graph=? AND parent=? AND to_seq IS NULL`),
    out: plan(`SELECT type FROM edges WHERE graph=? AND "from"=? AND to_seq IS NULL`),
    in: plan(`SELECT type FROM edges WHERE graph=? AND "to"=? AND to_seq IS NULL`),
    structure: plan(`SELECT id FROM nodes WHERE graph=? AND to_seq IS NULL`),
    changes: plan(`SELECT id FROM nodes WHERE graph=? AND (from_seq > ? OR to_seq > ?)`),
    cte_step: plan(`SELECT e."to" FROM edges e WHERE e."from"=? AND e.graph=? AND e.type='requires' AND e.to_seq IS NULL`),
  };

  // node open: row + material + edges both ways
  step('node open');
  const qNode = db.prepare(`SELECT id, class, title, data FROM nodes WHERE graph=? AND id=? AND to_seq IS NULL`);
  const qKids = db.prepare(`SELECT id, class, data FROM nodes WHERE graph=? AND parent=? AND to_seq IS NULL`);
  const qOut = db.prepare(`SELECT type, "to", data FROM edges WHERE graph=? AND "from"=? AND to_seq IS NULL`);
  const qIn = db.prepare(`SELECT "from", type, data FROM edges WHERE graph=? AND "to"=? AND to_seq IS NULL`);
  const all = (s, b) => { const r = []; s.bind(b); while (s.step()) r.push(s.get([])); s.reset(); return r; };
  t = now();
  let found = 0;
  for (let i = 0; i < 1000; i++) {
    const id = 'n' + Math.floor(rand() * n);
    found += all(qNode, [G, id]).length; all(qKids, [G, id]); all(qOut, [G, id]); all(qIn, [G, id]);
  }
  out.node_open_ms = Math.round(ms(t) / 1000 * 1000) / 1000;
  out.node_open_found = found;
  [qNode, qKids, qOut, qIn].forEach((x) => x.finalize());

  // edits: one node-set per transaction, through the reader, with history + FTS + event + head
  step('edits');
  let seq = ops.length;
  const before = seq;
  const edited = new Set();
  t = now();
  for (let i = 0; i < 1000; i++) {
    const k = Math.floor(rand() * n);
    edited.add(k);
    seq++;
    db.transaction(() => {
      a.node(seq, { id: 'n' + k, class: 'concept', title: 'Topic ' + k, definition: 'edited ' + i + ' ' + words(40), parent: null },
             `{:ev/id "e${seq}" :ev/type :change :ops [[:node-set "n${k}" {:definition "…"}]]}`);
      a.head(seq);
    });
  }
  out.edit_ms = Math.round(ms(t) / 1000 * 1000) / 1000;

  // changes-since: what the UI patches after those edits
  t = now();
  const changed = db.selectValue(`SELECT count(*) FROM nodes WHERE graph=? AND (from_seq > ? OR to_seq > ?)`, [G, before, before]);
  // every edit opens a row; each topic edited closes its pre-edit row once (a row opened AND closed inside counts once)
  out.changes_since = { ms: ms(t), rows: changed, expected: 1000 + edited.size };

  // cycle check for an acyclic relation: (a) load the relation's edges + walk in JS, (b) recursive SQL
  step('cycle');
  const reaches = (adj, start, goal) => { const seen = new Set([start]); const stack = [start];
    while (stack.length) { const x = stack.pop(); if (x === goal) return true; for (const y of adj.get(x) || []) if (!seen.has(y)) { seen.add(y); stack.push(y); } } return false; };
  const anEdge = db.selectArray(`SELECT "from", "to" FROM edges WHERE graph=? AND type='requires' AND to_seq IS NULL LIMIT 1 OFFSET ?`, [G, Math.floor(n / 2)]);
  t = now();
  const rel = db.selectArrays(`SELECT "from", "to" FROM edges WHERE graph=? AND type='requires' AND to_seq IS NULL`, [G]);
  const adj = new Map(); for (const [f, to] of rel) (adj.get(f) || adj.set(f, []).get(f)).push(to);
  const loadMs = ms(t);
  t = now();
  const cycJs = reaches(adj, anEdge[0], anEdge[1]);       // adding to→from would close a cycle
  const noCycJs = reaches(adj, 'n0', 'n' + (n - 1));      // adding (n-1)→0 is downward: no cycle
  const walkMs = ms(t);
  const cte = db.prepare(`WITH RECURSIVE r(x) AS (SELECT ? UNION SELECT e."to" FROM edges e JOIN r ON e."from" = r.x
                            WHERE e.graph=? AND e.type='requires' AND e.to_seq IS NULL)
                          SELECT count(*) FROM r WHERE x = ?`);
  const viaSql = (start, goal) => { cte.bind([start, G, goal]); cte.step(); const v = cte.get(0); cte.reset(); return v > 0; };
  t = now();
  const cycSql = viaSql(anEdge[0], anEdge[1]);
  const cteHitMs = ms(t);
  t = now();
  const noCycSql = viaSql('n0', 'n' + (n - 1));
  const cteMissMs = ms(t);
  cte.finalize();
  out.cycle = { load_relation_ms: loadMs, relation_edges: rel.length, js_walk_ms: walkMs, sql_hit_ms: cteHitMs, sql_miss_ms: cteMissMs,
                correct: cycJs && !noCycJs && cycSql && !noCycSql };

  // FTS: 100 one/two-word queries, ranked, top 20, joined back to current rows
  step('fts');
  const fts = db.prepare(`SELECT n.id FROM nodes_fts f JOIN nodes n ON n.rowid = f.rowid
                          WHERE nodes_fts MATCH ? AND n.to_seq IS NULL ORDER BY f.rank LIMIT 20`);
  t = now();
  let hits = 0;
  for (let i = 0; i < 100; i++) {
    const q = i % 2 ? VOCAB[Math.floor(rand() * VOCAB.length)] : VOCAB[Math.floor(rand() * VOCAB.length)] + ' ' + VOCAB[Math.floor(rand() * VOCAB.length)];
    hits += all(fts, [q]).length;
  }
  out.fts = { ms: Math.round(ms(t) / 100 * 100) / 100, avg_hits: hits / 100,
              edited_found: all(fts, ['edited']).length > 0 };
  fts.finalize();

  // vectors: N × 384 float32 as blobs; load all, brute-force cosine top 20
  step('vectors');
  t = now();
  const vIns = db.prepare(`INSERT INTO vectors VALUES (?, 'proof-model', ?)`);
  db.transaction(() => {
    const v = new Float32Array(DIMS);
    for (let i = 0; i < n; i++) {
      let norm = 0; for (let d = 0; d < DIMS; d++) { v[d] = rand() - 0.5; norm += v[d] * v[d]; }
      norm = Math.sqrt(norm); for (let d = 0; d < DIMS; d++) v[d] /= norm;
      vIns.bind([hash('n' + i), new Uint8Array(v.buffer.slice(0))]).stepReset();
    }
  });
  vIns.finalize();
  out.vectors_insert_ms = ms(t);
  t = now();
  const blobs = db.selectValues(`SELECT vec FROM vectors WHERE model='proof-model'`);
  const M = new Float32Array(blobs.length * DIMS);
  blobs.forEach((b, i) => M.set(new Float32Array(b.buffer, b.byteOffset, DIMS), i * DIMS));
  const loadVec = ms(t);
  t = now();
  const qv = M.subarray(0, DIMS);
  const scores = new Float32Array(blobs.length);
  for (let i = 0; i < blobs.length; i++) { let s = 0; const o = i * DIMS; for (let d = 0; d < DIMS; d++) s += M[o + d] * qv[d]; scores[i] = s; }
  const top = Array.from(scores.keys()).sort((x, y) => scores[y] - scores[x]).slice(0, 20);
  out.vectors = { load_ms: loadVec, cosine_top20_ms: ms(t), count: blobs.length, self_first: top[0] === 0 };

  // rebuild: wipe the projection, replay every op through the reader in batches
  step('rebuild');
  t = now();
  db.exec(`DELETE FROM nodes WHERE graph='${G}'; DELETE FROM edges WHERE graph='${G}'; INSERT INTO nodes_fts(nodes_fts) VALUES('delete-all');`);
  for (let i = 0; i < ops.length; i += 5000) {
    db.transaction(() => {
      for (let k = i; k < Math.min(i + 5000, ops.length); k++) {
        const op = ops[k];
        op[0] === 'node' ? a.node(k + 1, op[1], null) : a.edge(k + 1, op[1], null);
      }
      a.head(Math.min(i + 5000, ops.length));
    });
  }
  out.rebuild_ms = ms(t);
  out.rebuild_rows = { nodes: db.selectValue('SELECT count(*) FROM nodes'), edges: db.selectValue('SELECT count(*) FROM edges') };
  a.finalize();
  db.close();
  return out;
}

let heldPool = null;   // the pool a booted tab holds, until it hands it over

async function boot(tries) {
  const t = now();
  const { sqlite3, ms: tInit } = await init();
  const { pool, ms: tPool, attempts } = await openPool(sqlite3, tries);
  heldPool = pool;
  const db = new pool.OpfsSAHPoolDb(DB);
  const head = db.selectValue('SELECT seq FROM heads WHERE graph=?', [G]);
  const s = structure(db);
  post({ structureJson: s.json });
  db.close();
  return { init_ms: tInit, vfs_ms: tPool, vfs_attempts: attempts, head, structure_query_ms: s.query, total_to_structure_ms: ms(t), nodes: s.nodes };
}

// a tab that wants the pool: one try (`second`), or retrying until it is free (`take`)
async function take(tries) {
  const t = now();
  const { sqlite3 } = await init();
  try {
    const { attempts } = await openPool(sqlite3, tries);
    return { opened: true, ms: ms(t), attempts };
  } catch (e) {
    return { opened: false, ms: ms(t), error: String(e.message || e).slice(0, 200) };
  }
}

self.onmessage = async ({ data: { phase, n } }) => {
  try {
    const r = phase === 'boot' ? await boot(600)          // ≤ 60 s: after an abrupt end the handles free late
            : phase === 'second' ? await take(1)
            : phase === 'take' ? await take(600)
            : phase === 'pause' ? (heldPool.pauseVfs(), { paused: true })   // graceful handover: handles released now
            : await full(n);
    post({ done: r });
  } catch (e) {
    post({ error: String(e && e.stack || e) });
  }
};

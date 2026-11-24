// The graph store's projection in the browser (wiki/Graph-Store.md): SQLite
// (WASM, opfs-sahpool) in a module Worker, held by ONE tab (Web Locks, in
// graph_local.cljs). It stores rows the fold computed — nodes and edges with
// their history (from_seq/to_seq), FTS, vectors — and answers reads. No
// Loretile rule lives here: `apply` writes row changes, everything else reads.
// Messages: {id, op, ...args} → {id, ok: result} | {id, error}.
import sqlite3InitModule from './sqlite/index.mjs';

const DB = '/loretile.sqlite3';
let sqlite3, pool, db;
const st = {};

const SCHEMA = `
CREATE TABLE IF NOT EXISTS nodes (graph TEXT, id TEXT, from_seq INTEGER, to_seq INTEGER,
  class TEXT, status TEXT, title TEXT, short TEXT, parent TEXT, text TEXT, text_hash TEXT, data TEXT,
  PRIMARY KEY (graph, id, from_seq));
CREATE UNIQUE INDEX IF NOT EXISTS nodes_now    ON nodes (graph, id)     WHERE to_seq IS NULL;
CREATE INDEX        IF NOT EXISTS nodes_parent ON nodes (graph, parent) WHERE to_seq IS NULL;
CREATE INDEX        IF NOT EXISTS nodes_from   ON nodes (graph, from_seq);
-- closed rows only: a plain (graph, to_seq) index also matches "to_seq IS NULL"
-- and the planner then prefers it to the right one (proof: node open 75 ms)
CREATE INDEX        IF NOT EXISTS nodes_closed ON nodes (graph, to_seq) WHERE to_seq IS NOT NULL;
CREATE TABLE IF NOT EXISTS edges (graph TEXT, "from" TEXT, type TEXT, "to" TEXT, from_seq INTEGER, to_seq INTEGER,
  status TEXT, data TEXT, PRIMARY KEY (graph, "from", type, "to", from_seq));
CREATE UNIQUE INDEX IF NOT EXISTS edges_now    ON edges (graph, "from", type, "to") WHERE to_seq IS NULL;
CREATE INDEX        IF NOT EXISTS edges_to     ON edges (graph, "to")              WHERE to_seq IS NULL;
CREATE INDEX        IF NOT EXISTS edges_type   ON edges (graph, type)              WHERE to_seq IS NULL;
CREATE INDEX        IF NOT EXISTS edges_fseq   ON edges (graph, from_seq);
CREATE INDEX        IF NOT EXISTS edges_closed ON edges (graph, to_seq) WHERE to_seq IS NOT NULL;
CREATE TABLE IF NOT EXISTS discarded (graph TEXT, key TEXT, seq INTEGER, PRIMARY KEY (graph, key));
CREATE TABLE IF NOT EXISTS heads (graph TEXT PRIMARY KEY, seq INTEGER, last_id TEXT, fold TEXT, props TEXT);
CREATE TABLE IF NOT EXISTS vectors (hash TEXT, model TEXT, vec BLOB, PRIMARY KEY (hash, model));
CREATE VIRTUAL TABLE IF NOT EXISTS nodes_fts USING fts5(text, content='', contentless_delete=1);
`;

const prep = () => {
  const P = (k, sql) => (st[k] = db.prepare(sql));
  P('nodeNow', `SELECT rowid FROM nodes WHERE graph=? AND id=? AND to_seq IS NULL`);
  P('closeNode', `UPDATE nodes SET to_seq=? WHERE rowid=?`);
  P('insNode', `INSERT INTO nodes VALUES (?,?,?,NULL,?,?,?,?,?,?,?,?) RETURNING rowid`);
  P('ftsDel', `DELETE FROM nodes_fts WHERE rowid=?`);
  P('ftsIns', `INSERT INTO nodes_fts (rowid, text) VALUES (?,?)`);
  P('edgeNow', `SELECT rowid FROM edges WHERE graph=? AND "from"=? AND type=? AND "to"=? AND to_seq IS NULL`);
  P('closeEdge', `UPDATE edges SET to_seq=? WHERE rowid=?`);
  P('insEdge', `INSERT INTO edges VALUES (?,?,?,?,?,NULL,?,?)`);
  P('discard', `INSERT OR IGNORE INTO discarded VALUES (?,?,?)`);
  P('head', `INSERT INTO heads (graph, seq, last_id, fold) VALUES (?,?,?,?)
             ON CONFLICT(graph) DO UPDATE SET seq=excluded.seq, last_id=excluded.last_id, fold=excluded.fold`);
  P('props', `UPDATE heads SET props=? WHERE graph=?`);
};

const one = (s, b) => { s.bind(b); const r = s.step() ? s.get([]) : null; s.reset(); return r; };
const run = (s, b) => { s.bind(b).stepReset(); };
const all = (sql, b) => db.selectArrays(sql, b);

// A Worker that just ended releases its handles a moment later: retry the open.
async function open() {
  const t = performance.now();
  sqlite3 = await sqlite3InitModule();
  for (let i = 1; ; i++) {
    try { pool = await sqlite3.installOpfsSAHPoolVfs({ name: 'loretile' }); break; }
    catch (e) { if (i >= 100) throw e; await new Promise((r) => setTimeout(r, 100)); }
  }
  db = new pool.OpfsSAHPoolDb(DB);
  db.exec(SCHEMA);
  prep();
  return { ms: Math.round(performance.now() - t) };
}

// row changes of consecutive events, one transaction: close what changes, insert the new rows
function apply({ graph, changes, fold }) {
  db.transaction(() => {
    for (const c of changes) {
      const seq = c.seq;
      for (const id of c.removedNodes) {
        const old = one(st.nodeNow, [graph, id]);
        if (old) { run(st.closeNode, [seq, old[0]]); run(st.ftsDel, [old[0]]); }
      }
      for (const n of c.nodes) {
        const old = one(st.nodeNow, [graph, n.id]);
        if (old) { run(st.closeNode, [seq, old[0]]); run(st.ftsDel, [old[0]]); }
        const [rowid] = one(st.insNode, [graph, n.id, seq, n.class, n.status, n.title, n.short, n.parent, n.text, n.text_hash, n.data]);
        if (n.text) run(st.ftsIns, [rowid, n.text]);
      }
      for (const [from, type, to] of c.removedEdges) {
        const old = one(st.edgeNow, [graph, from, type, to]);
        if (old) run(st.closeEdge, [seq, old[0]]);
      }
      for (const e of c.edges) {
        const old = one(st.edgeNow, [graph, e.from, e.type, e.to]);
        if (old) run(st.closeEdge, [seq, old[0]]);
        run(st.insEdge, [graph, e.from, e.type, e.to, seq, e.status, e.data]);
      }
      for (const k of c.discarded) run(st.discard, [graph, k, seq]);
      run(st.head, [graph, seq, c.lastId, fold]);
      if (c.heads != null) run(st.props, [c.heads, graph]);
    }
  });
  return true;
}

function head({ graph }) {
  const r = db.selectArray(`SELECT seq, last_id, fold FROM heads WHERE graph=?`, [graph]);
  return r ? { seq: r[0], lastId: r[1], fold: r[2] } : null;
}

// a rebuild: the projection of one graph goes, the vectors (keyed by text) stay
function clear({ graph }) {
  db.transaction(() => {
    for (const [rowid] of all(`SELECT rowid FROM nodes WHERE graph=? AND to_seq IS NULL`, [graph])) run(st.ftsDel, [rowid]);
    for (const t of ['nodes', 'edges', 'discarded', 'heads']) db.exec({ sql: `DELETE FROM ${t} WHERE graph=?`, bind: [graph] });
  });
  return true;
}

// the structure: JSON built by SQLite — one string, not 140k rows converted in JS
function structure({ graph }) {
  const nodes = db.selectValue(`SELECT json_group_array(json_array(id, class, status, title, short, parent))
                                FROM nodes WHERE graph=? AND to_seq IS NULL`, [graph]);
  const edges = db.selectValue(`SELECT json_group_array(json_array("from", type, "to", status))
                                FROM edges WHERE graph=? AND to_seq IS NULL`, [graph]);
  return `{"nodes":${nodes},"edges":${edges}}`;
}

function node({ graph, id }) {
  const n = db.selectArray(`SELECT class, data FROM nodes WHERE graph=? AND id=? AND to_seq IS NULL`, [graph, id]);
  if (!n) return null;
  return {
    node: n,
    children: all(`SELECT class, id, data FROM nodes WHERE graph=? AND parent=? AND to_seq IS NULL`, [graph, id]),
    out: all(`SELECT data FROM edges WHERE graph=? AND "from"=? AND status='accepted' AND to_seq IS NULL`, [graph, id]).map((r) => r[0]),
    in: all(`SELECT data FROM edges WHERE graph=? AND "to"=? AND status='accepted' AND to_seq IS NULL`, [graph, id]).map((r) => r[0]),
  };
}

// words → an FTS5 query: every word as a prefix, any of them; bm25 ranks
const ftsQuery = (text) => (text || '').toLowerCase().split(/[^\p{L}\p{N}_]+/u).filter((w) => w.length > 1)
  .map((w) => `"${w.replace(/"/g, '')}"*`).join(' OR ');

function search({ graph, text, limit, classes }) {
  const q = ftsQuery(text);
  if (!q) return [];
  const cls = classes && classes.length ? ` AND n.class IN (${classes.map(() => '?').join(',')})` : '';
  return all(`SELECT n.id, n.class, n.title, n.short, n.parent, -bm25(nodes_fts) AS score
              FROM nodes_fts f JOIN nodes n ON n.rowid = f.rowid
              WHERE nodes_fts MATCH ? AND n.graph=? AND n.to_seq IS NULL${cls}
              ORDER BY bm25(nodes_fts) LIMIT ?`, [q, graph, ...(classes || []), limit || 20]);
}

function changesSince({ graph, seq }) {
  return {
    nodes: all(`SELECT id, class, status, title, short, parent, to_seq FROM nodes WHERE graph=? AND (from_seq > ? OR to_seq > ?)`, [graph, seq, seq]),
    edges: all(`SELECT "from", type, "to", status, to_seq FROM edges WHERE graph=? AND (from_seq > ? OR to_seq > ?)`, [graph, seq, seq]),
    head: head({ graph }),
  };
}

// every current row — the equivalence check rebuilds the kg from it
function dump({ graph }) {
  return {
    nodes: all(`SELECT id, class, data FROM nodes WHERE graph=? AND to_seq IS NULL`, [graph]),
    edges: all(`SELECT status, data FROM edges WHERE graph=? AND to_seq IS NULL`, [graph]),
    discarded: all(`SELECT key FROM discarded WHERE graph=?`, [graph]).map((r) => r[0]),
    heads: db.selectValue(`SELECT props FROM heads WHERE graph=?`, [graph]),
  };
}

// every current row as ONE JSON string built by SQLite — the page builds its
// graph from it at boot instead of refolding the log (wiki/Graph-Store.md)
function dumpJson({ graph }) {
  const nodes = db.selectValue(`SELECT json_group_array(json_array(id, class, json(data)))
                                FROM nodes WHERE graph=? AND to_seq IS NULL`, [graph]);
  const edges = db.selectValue(`SELECT json_group_array(json_array(status, json(data)))
                                FROM edges WHERE graph=? AND to_seq IS NULL`, [graph]);
  const discarded = db.selectValue(`SELECT json_group_array(key) FROM discarded WHERE graph=?`, [graph]);
  const heads = db.selectValue(`SELECT props FROM heads WHERE graph=?`, [graph]);
  return `{"nodes":${nodes},"edges":${edges},"discarded":${discarded},"heads":${JSON.stringify(heads)}}`;
}

function pending({ graph, model, limit }) {
  return all(`SELECT DISTINCT n.text_hash, n.text FROM nodes n
              WHERE n.graph=? AND n.to_seq IS NULL AND n.text <> ''
                AND NOT EXISTS (SELECT 1 FROM vectors v WHERE v.hash = n.text_hash AND v.model = ?)
              LIMIT ?`, [graph, model, limit || 100]);
}

function putVectors({ model, vecs }) {
  const s = db.prepare(`INSERT OR REPLACE INTO vectors VALUES (?,?,?)`);
  db.transaction(() => { for (const [hash, v] of vecs) s.bind([hash, model, new Uint8Array(new Float32Array(v).buffer)]).stepReset(); });
  s.finalize();
  return true;
}

// vectors of a graph's current nodes, loaded once per model and kept here (proof: 368 ms for 40k)
let vcache = null;
function vectorSearch({ graph, model, vec, limit }) {
  if (!vcache || vcache.graph !== graph || vcache.model !== model || vcache.stale) {
    const rows = all(`SELECT n.id, v.vec FROM nodes n JOIN vectors v ON v.hash = n.text_hash AND v.model = ?
                      WHERE n.graph=? AND n.to_seq IS NULL`, [model, graph]);
    vcache = { graph, model, ids: rows.map((r) => r[0]), vecs: rows.map((r) => new Float32Array(r[1].buffer, r[1].byteOffset, r[1].byteLength / 4)) };
  }
  const q = Float32Array.from(vec);
  let qn = 0; for (let i = 0; i < q.length; i++) qn += q[i] * q[i];
  qn = Math.sqrt(qn) || 1;
  const scored = vcache.vecs.map((v, i) => {
    let s = 0, n = 0; for (let d = 0; d < v.length; d++) { s += v[d] * q[d]; n += v[d] * v[d]; }
    return [vcache.ids[i], s / (Math.sqrt(n) * qn || 1)];
  });
  return scored.sort((a, b) => b[1] - a[1]).slice(0, limit || 20);
}

const ops = { open, apply, head, clear, structure, node, search, changesSince, dump, dumpJson, pending, putVectors, vectorSearch,
  // the handover to another tab: release the access handles now
  pause: () => { pool && pool.pauseVfs(); return true; } };

self.onmessage = async ({ data }) => {
  try {
    if (data.op === 'apply' || data.op === 'clear' || data.op === 'putVectors') vcache && (vcache.stale = true);
    self.postMessage({ id: data.id, ok: await ops[data.op](data) });
  } catch (e) {
    self.postMessage({ id: data.id, error: String((e && e.message) || e) });
  }
};

// The MCP server over a log.edn (wiki/Profiles.md): a coding agent's view of
// a curated graph. Spawns the built server on the SQL demo, speaks JSON-RPC
// over stdio, and checks the handshake, the tool list and the reads.
// Usage: node test/mcp-check.js [out/mcp.js] [kg/demo/sql.ru.edn]
const { spawn } = require('child_process');
const path = require('path');
const SERVER = process.argv[2] || path.join(__dirname, '..', 'out', 'mcp.js');
const LOG = process.argv[3] || path.join(__dirname, '..', 'kg', 'demo', 'sql.ru.edn');
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

const srv = spawn('node', [SERVER, LOG]);
let buf = '';
const pending = new Map();
srv.stdout.on('data', (d) => {
  buf += d;
  const lines = buf.split('\n'); buf = lines.pop();
  for (const l of lines) {
    if (!l.trim()) continue;
    const m = JSON.parse(l);
    if (pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  }
});
srv.stderr.on('data', (d) => console.log('stderr:', String(d).trim()));
let n = 0;
const call = (method, params) => new Promise((resolve) => {
  const id = ++n;
  pending.set(id, resolve);
  srv.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
});
const notify = (method) => srv.stdin.write(JSON.stringify({ jsonrpc: '2.0', method }) + '\n');
const text = (r) => (r.result && r.result.content && r.result.content[0].text) || '';

(async () => {
  const init = await call('initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'check', version: '0' } });
  check(init.result && init.result.serverInfo.name === 'loretile' && init.result.capabilities.tools, 'initialize answers with the server and its tools capability');
  check(/accepted by a person/.test(init.result.instructions), 'and tells the agent what kind of graph this is');
  notify('notifications/initialized');
  check((await call('ping', {})).result !== undefined, 'ping');

  const list = await call('tools/list', {});
  const names = list.result.tools.map((t) => t.name);
  check(['describe-ontology', 'list-nodes', 'search-nodes', 'open-node', 'list-edges', 'check-cqs'].every((t) => names.includes(t)), `the six read tools are listed (${names.join(', ')})`);
  check(list.result.tools.every((t) => t.inputSchema && t.inputSchema.type === 'object'), 'each with a JSON schema');

  const onto = await call('tools/call', { name: 'describe-ontology', arguments: {} });
  check(/ontology: Learning/.test(text(onto)) && /requires/.test(text(onto)), 'describe-ontology names the learning vocabulary');

  const found = await call('tools/call', { name: 'search-nodes', arguments: { query: 'GROUP BY' } });
  check(/group-by — /.test(text(found)), `search-nodes finds GROUP BY (${text(found).split('\n')[0]})`);

  const node = await call('tools/call', { name: 'open-node', arguments: { id: 'group-by' } });
  check(/definition: /.test(text(node)) && /requires: /.test(text(node)) && /material \(/.test(text(node)), 'open-node gives definition, relations and material');
  check(!/options|key:/.test(text(node)), 'and never a question stem or key');

  const all = await call('tools/call', { name: 'list-nodes', arguments: {} });
  check(text(all).split('\n').length > 10 && text(all).indexOf('select') < text(all).indexOf('group-by'), 'list-nodes puts foundations first');

  const req = await call('tools/call', { name: 'list-edges', arguments: { relation: 'requires', node: 'group-by' } });
  check(/GROUP BY[^\n]*—requires→ /.test(text(req)) && !/grounded/.test(text(req)), `list-edges lists one relation around one node (${text(req).split('\n')[0]})`);
  const nope = await call('tools/call', { name: 'list-edges', arguments: { relation: 'triggers' } });
  check(/no relation triggers/.test(text(nope)), 'an unknown relation is said in words');
  const cqs = await call('tools/call', { name: 'check-cqs', arguments: {} });
  check(/no competency questions/.test(text(cqs)), 'check-cqs on a learning demo: none');

  const bad = await call('tools/call', { name: 'open-node', arguments: { id: 'nope' } });
  check(/no node with id nope/.test(text(bad)), 'a missing node is said in words');
  const unknown = await call('tools/call', { name: 'write-node', arguments: {} });
  check(unknown.result.isError && /unknown tool/.test(text(unknown)), 'no write tool exists');
  const nomethod = await call('resources/list', {});
  check(nomethod.error && nomethod.error.code === -32601, 'an unsupported method is a JSON-RPC error, not a crash');
  check(!names.includes('read-source') && !names.includes('search-sources'), 'without --sources the source tools are not offered');
  srv.stdin.end();

  // --- with --sources: the document behind a node (2026-10-21) -------------------
  const fs = require('fs'); const os = require('os');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcp-src-'));
  const docs = path.join(dir, 'docs'); fs.mkdirSync(docs);
  fs.writeFileSync(path.join(docs, 'ap.md'), '# AP\n\nVendors send invoices to ap@example.com.\n\nPayment runs happen every Thursday; approve by Wednesday EOD.\n');
  fs.writeFileSync(path.join(dir, 'secret.md'), 'TOP SECRET outside the folder\n');
  fs.writeFileSync(path.join(docs, 'blob.md'), 'binary\u0000data');
  fs.symlinkSync(path.join(dir, 'secret.md'), path.join(docs, 'link.md'));
  const ev = (id, rest) => `{:ev/id "${id}" :ev/ns "t" :ev/actor "t" :ev/ts 1 ${rest}}`;
  fs.writeFileSync(path.join(dir, 'log.edn'), [
    ev('1', ':ev/type :change :ops [[:node-set "s-ap" {:is :source :title "ap.md" :ref "x.md"}]]'),
    ev('2', ':ev/type :change :ops [[:node-set "s-out" {:is :source :title "../secret.md" :ref "../secret.md"}]]'),
    ev('3', ':ev/type :change :ops [[:node-set "s-bin" {:is :source :title "blob.md" :ref "blob.md"}]]'),
    ev('4', ':ev/type :change :ops [[:node-set "pay-run" {:title "Payment run" :definition "weekly" :source-id "s-ap" :evidence "Payment runs happen every Thursday" :status :accepted}]]'),
    ev('5', ':ev/type :change :ops [[:node-set "leak" {:title "Leak" :definition "x" :source-id "s-out" :evidence "TOP SECRET" :status :accepted}]]'),
    ev('6', ':ev/type :change :ops [[:node-set "bin" {:title "Bin" :definition "x" :source-id "s-bin" :evidence "binary" :status :accepted}]]'),
    ev('7', ':ev/type :change :ops [[:node-set "s-link" {:is :source :title "link.md" :ref "link.md"}]]'),
    ev('8', ':ev/type :change :ops [[:node-set "via-link" {:title "Via link" :definition "x" :source-id "s-link" :evidence "TOP SECRET" :status :accepted}]]'),
  ].join('\n'));
  const srv2 = spawn('node', [SERVER, path.join(dir, 'log.edn'), '--sources', docs]);
  let buf2 = ''; const pend2 = new Map(); let n2 = 0;
  srv2.stdout.on('data', (d) => {
    buf2 += d; const lines = buf2.split('\n'); buf2 = lines.pop();
    for (const l of lines) { if (!l.trim()) continue; const m = JSON.parse(l); if (pend2.has(m.id)) { pend2.get(m.id)(m); pend2.delete(m.id); } }
  });
  srv2.stderr.on('data', (d) => console.log('stderr:', String(d).trim()));
  const call2 = (method, params) => new Promise((resolve) => { const id = ++n2; pend2.set(id, resolve); srv2.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n'); });
  const init2 = await call2('initialize', {});
  check(/read-source opens the passage/.test(init2.result.instructions), 'with --sources the server tells the agent the documents are there');
  const names2 = (await call2('tools/list', {})).result.tools.map((t) => t.name);
  check(names2.includes('read-source') && names2.includes('search-sources'), 'and offers read-source and search-sources');
  const rs = text(await call2('tools/call', { name: 'read-source', arguments: { id: 'pay-run' } }));
  check(/source «ap.md»/.test(rs) && /every Thursday; approve by Wednesday EOD/.test(rs), `read-source opens the node's passage (${rs.split('\n')[0]})`);
  const ss = text(await call2('tools/call', { name: 'search-sources', arguments: { query: 'Wednesday' } }));
  check(/«ap.md» @ /.test(ss) && /nodes written from here: pay-run/.test(ss), 'search-sources finds the passage and the node written from it');
  const leak = text(await call2('tools/call', { name: 'read-source', arguments: { id: 'leak' } }));
  check(!/TOP SECRET outside/.test(leak) && /not available/.test(leak), 'a source named outside the folder is never read');
  const vl = text(await call2('tools/call', { name: 'read-source', arguments: { id: 'via-link' } }));
  check(!/TOP SECRET outside/.test(vl) && /not available/.test(vl), 'nor one a symlink in the folder points out to');
  const bin = text(await call2('tools/call', { name: 'read-source', arguments: { id: 'bin' } }));
  check(/not available/.test(bin), 'a file that is not text is not read');
  srv2.stdin.end();
  fs.rmSync(dir, { recursive: true, force: true });
  console.log(failures ? `\n${failures} FAILED` : '\nALL PASSED');
  process.exit(failures ? 1 : 0);
})();

#!/usr/bin/env python3
"""The loretile codebase as a graph in its own tool (wiki/Profiles.md, the
second consumer): a `software` ontology, then the system itself.

GENERATED — rerun after changing the code: `python3 kg/loretile/build.py`
→ kg/loretile/loretile.edn (open it in the app via Импорт, or serve it to a
coding agent: `node out/mcp.js kg/loretile/loretile.edn`).

Two kinds of knowledge, kept apart on purpose:
  MECHANICAL — read from the source every run, never typed by hand:
    modules (every namespace, its docstring as material), `depends-on`
    (the :require graph), `emits` (who builds which kind of `change` —
    the constructors of ops.cljc, and raw changes), `stores-in` (which
    storage keys a module names), gates (every *-check.js, its header
    comment as definition), writes (ops.cljc's constructors).
    Edges here carry provenance :inferred-from-data.
  CURATED — written here from CLAUDE.md (rules) and the wiki (features in
    wiki/Code.md, decisions in their pages), :authored:
    features, rules, decisions, and the edges between them and the
    mechanical layer (implements, verifies, constrains, decided-in).
Competency questions make the curated layer's holes visible: run
check-cqs (MCP) or open the Ontology page.
"""
import os, re, sys, json

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SRC = os.path.join(ROOT, 'src', 'loretile')
TEST = os.path.join(ROOT, 'test')
OUT = os.path.join(os.path.dirname(__file__), 'loretile.edn')
NS = 'loretile-code'

# ---------------------------------------------------------------- EDN out
class Kw(str):
    pass

def edn(v):
    if isinstance(v, Kw):
        return ':' + v
    if isinstance(v, bool):
        return 'true' if v else 'false'
    if isinstance(v, int):
        return str(v)
    if isinstance(v, str):
        return json.dumps(v, ensure_ascii=False)
    if isinstance(v, (set, frozenset)):
        return '#{' + ' '.join(edn(x) for x in sorted(v, key=str)) + '}'
    if isinstance(v, (list, tuple)):
        return '[' + ' '.join(edn(x) for x in v) + ']'
    if isinstance(v, dict):
        return '{' + ' '.join(edn(k) + ' ' + edn(x) for k, x in v.items()) + '}'
    raise TypeError(v)

events = []

def K(s): return Kw(s)

def _rest(m, *drop):
    return {k: v for k, v in m.items() if k not in (K('id'),) + tuple(K(d) for d in drop)}

def ops_of(t, p):
    """Every write is one `change` (wiki/Property-Graph.md, the learning
    slice 2026-11-20): the ops for one described step, as ops.cljc builds them."""
    if t == 'profile-set':
        return [[K('graph-set'), {K('ontology'): 'software', K('ontology-copy'): p[K('profile')]}]]
    if t == 'graph-owner-set':
        return [[K('graph-set'), {K('owner'): p[K('owner')]}]]
    if t == 'cq-raised':
        q = p[K('cq')]
        return [[K('node-set'), q[K('id')], {**_rest(q), K('is'): K('cq')}]]
    if t == 'source-added':
        x = p[K('source')]
        return [[K('node-set'), x[K('id')], {**_rest(x), K('is'): K('source')}]]
    if t == 'concept-accepted':
        c = p[K('concept')]
        return [[K('node-set'), c[K('id')], {**_rest(c), K('status'): K('accepted')}]]
    if t == 'edge-accepted':
        x = p[K('edge')]
        return [[K('edge-set'), x[K('from')], x[K('type')], x[K('to')], _rest(x, 'from', 'to', 'type')]]
    if t == 'object-added':
        o = p[K('object')]
        return [[K('node-set'), o[K('id')], {**_rest(o), K('is'): K('object')}]]
    raise ValueError(t)

def ev(t, **kv):
    payload = {Kw(k.replace('_', '-')): v for k, v in kv.items()}
    events.append({Kw('ev/id'): f'lc-{len(events) + 1:04d}', Kw('ev/ns'): NS, Kw('ev/actor'): 'build.py',
                   Kw('ev/ts'): 1792000000000 + len(events), Kw('ev/type'): Kw('change'),
                   Kw('ops'): ops_of(t, payload)})

# ---------------------------------------------------------------- ontology
PROFILE = {
    K('id'): 'software', K('title'): 'How loretile is built',
    K('brief'): 'The loretile codebase: modules and what they depend on, the features they implement, '
                'the events they write, the storage they keep, the gates that verify them, and the rules '
                'and decisions that constrain them.',
    K('node-types'): [
        {K('id'): K('module'), K('label'): 'module', K('color'): '#4f7cff', K('description'): 'A ClojureScript namespace under src/loretile (or a JS worker under public/js).'},
        {K('id'): K('feature'), K('label'): 'feature', K('color'): '#2fa36b', K('description'): 'Something a person can do in the app, or a property it keeps; named in CLAUDE.md and wiki/Code.md.'},
        {K('id'): K('event'), K('label'): 'write', K('color'): '#8a63d2', K('description'): 'One kind of write: every write is a `change` event; ops.cljc builds the kinds (a node accepted, an item added…), and a module may write raw changes.'},
        {K('id'): K('store'), K('label'): 'store', K('color'): '#6b7f99', K('description'): 'A localStorage key (or a family of keys) the app reads and writes.'},
        {K('id'): K('gate'), K('label'): 'gate', K('color'): '#2aa7b8', K('description'): 'A test: fold_test, a browser check test/<name>-check.js, or a Makefile target.'},
        {K('id'): K('rule'), K('label'): 'rule', K('color'): '#d9822b', K('description'): 'A rule an edit can break, from CLAUDE.md «Rules an edit can break».'},
        {K('id'): K('decision'), K('label'): 'decision', K('color'): '#c94f7c', K('description'): 'A decision page or section of the wiki that says why something is so.'},
    ],
    K('relations'): [
        {K('id'): K('depends-on'), K('label'): 'depends on', K('inverse'): 'used by',
         K('from'): {K('module')}, K('to'): {K('module')}, K('ordering?'): True,
         K('description'): 'A → B: module A requires module B (its ns :require). B is built first.'},
        {K('id'): K('implements'), K('label'): 'implements', K('inverse'): 'implemented in',
         K('from'): {K('module')}, K('to'): {K('feature')},
         K('description'): 'module → feature: the module holds the code of the feature.'},
        {K('id'): K('emits'), K('label'): 'emits', K('inverse'): 'emitted by',
         K('from'): {K('module')}, K('to'): {K('event')},
         K('description'): 'module → write: the module appends changes of this kind to the log.'},
        {K('id'): K('stores-in'), K('label'): 'keeps state in', K('inverse'): 'state of',
         K('from'): {K('module')}, K('to'): {K('store')},
         K('description'): 'module → store: the module reads or writes this storage key.'},
        {K('id'): K('verifies'), K('label'): 'verifies', K('inverse'): 'verified by',
         K('from'): {K('gate')}, K('to'): {K('feature')},
         K('description'): 'gate → feature: the test fails when the feature breaks.'},
        {K('id'): K('constrains'), K('label'): 'constrains', K('inverse'): 'constrained by',
         K('from'): {K('rule')}, K('to'): {K('feature'), K('module')},
         K('description'): 'rule → feature or module: an edit there must keep the rule.'},
        {K('id'): K('decided-in'), K('label'): 'decided in', K('inverse'): 'decides',
         K('from'): {K('feature'), K('rule')}, K('to'): {K('decision')},
         K('description'): 'feature or rule → decision: the wiki page that says why.'},
        {K('id'): K('within'), K('label'): 'is within', K('inverse'): 'includes',
         K('from'): {K('feature')}, K('to'): {K('feature')}, K('acyclic?'): True,
         K('description'): 'A → B: feature A is part of the larger feature B.'},
    ],
}
ev('profile-set', profile=PROFILE)
# who answers for the graph (graph-owner-set, 2026-10-21)
ev('graph-owner-set', owner={K('team'): 'Loretile', K('contact'): 'github.com/smogstate/loretile'})

CQS = [
    ('cq-impl', 'Which module implements this feature?', {K('relation'): K('implements'), K('from-type'): K('feature'), K('inverse?'): True}),
    ('cq-gate', 'Which gate verifies this feature?', {K('relation'): K('verifies'), K('from-type'): K('feature'), K('inverse?'): True}),
    ('cq-emit', 'Which module makes this kind of write?', {K('relation'): K('emits'), K('from-type'): K('event'), K('inverse?'): True}),
    ('cq-store', 'Which module keeps this store?', {K('relation'): K('stores-in'), K('from-type'): K('store'), K('inverse?'): True}),
    ('cq-rule-scope', 'What does this rule constrain?', {K('relation'): K('constrains'), K('from-type'): K('rule')}),
    ('cq-rule-why', 'Where was this rule decided?', {K('relation'): K('decided-in'), K('from-type'): K('rule')}),
]
for cid, q, pat in CQS:
    ev('cq-raised', cq={K('id'): cid, K('question'): q, K('pattern'): pat})

# ---------------------------------------------------------------- sources
sources = {}
def source(sid, title, ref):
    if sid not in sources:
        sources[sid] = True
        ev('source-added', source={K('id'): sid, K('title'): title, K('ref'): ref})
    return sid

CLAUDE_MD = source('src-claude-md', 'CLAUDE.md', 'CLAUDE.md')
CODE_MD = source('src-wiki-code', 'wiki/Code.md', 'wiki/Code.md')

nodes = {}
def node(nid, title, definition, kind, src=None):
    assert nid not in nodes, nid
    nodes[nid] = kind
    c = {K('id'): nid, K('title'): title, K('definition'): definition.strip(), K('kind'): K(kind)}
    if src:
        c[K('source-id')] = src
    ev('concept-accepted', concept=c)

edges = set()
def edge(a, b, t, prov='authored', why=None):
    assert a in nodes, ('missing', a, t, b)
    assert b in nodes, ('missing', b, t, a)
    if (a, b, t) in edges:
        return
    edges.add((a, b, t))
    e = {K('from'): a, K('to'): b, K('type'): K(t), K('provenance'): K(prov)}
    if why:
        e[K('justification')] = why
    ev('edge-accepted', edge=e)

def material(nid, text, src):
    ev('object-added', object={K('id'): 'o-' + nid, K('concept-id'): nid, K('kind'): K('explanation'),
                               K('level'): K('apprentice'), K('locale'): 'en', K('origin'): K('authored'),
                               K('text'): text, K('grounded-in'): src})

def first_para(s, n=700):
    s = re.sub(r'\s+', ' ', s).strip()
    return s if len(s) <= n else s[:n].rsplit(' ', 1)[0] + '…'

# ---------------------------------------------------------------- MECHANICAL: modules
modules = {}
for f in sorted(os.listdir(SRC)):
    if not re.search(r'\.clj[cs]?$', f):
        continue
    text = open(os.path.join(SRC, f), encoding='utf-8').read()
    m = re.search(r'\(ns ([\w.\-]+)', text)
    if not m:
        continue
    name = m.group(1)
    if name.endswith('-test'):
        continue
    short = name.replace('loretile.', '')
    doc = re.search(r'\(ns [\w.\-]+\s+"((?:[^"\\]|\\.)*)"', text, re.S)
    requires = sorted(set(re.findall(r'\[loretile\.([\w\-]+)', text)) - {short})
    # what it writes: the ops.cljc constructors it calls, and raw changes
    emits = sorted(set(re.findall(r'\(ops/([\w\-!?]+)', text))
                   | ({'change'} if re.search(r'\{:ev/type :change', text) else set()))
    keys = sorted(set(re.findall(r'"(loretile-[a-z\-]+)', text)))
    modules[short] = dict(file='src/loretile/' + f, doc=doc.group(1).replace('\\"', '"') if doc else None,
                          requires=requires, emits=emits, keys=keys, lines=text.count('\n'))

for short, m in modules.items():
    sid = source('src-' + short, m['file'], m['file'])
    lead = first_para(m['doc'], 220) if m['doc'] else f"Namespace loretile.{short}."
    # no line count: the freshness check (make test) fires on STRUCTURE —
    # requires, writers, keys, docstrings, checks — not on every edit
    node('m-' + short, 'loretile.' + short, f"{lead} ({m['file']})", 'module', sid)
    if m['doc']:
        material('m-' + short, first_para(m['doc'], 1200), sid)

# ---------------------------------------------------------------- MECHANICAL: writes
# every write is a `change`; its KINDS are ops.cljc's constructors (their
# docstrings say what each does) plus a raw change
ops_text = open(os.path.join(SRC, 'ops.cljc'), encoding='utf-8').read()
OPS_SRC = source('src-ops', 'src/loretile/ops.cljc', 'src/loretile/ops.cljc')
node('e-change', 'change', 'A raw `change` event: a list of ops (graph-set, node-set, node-remove, edge-set, edge-remove) applied atomically and guarded together.', 'event', OPS_SRC)
for m in re.finditer(r'\(defn ([\w\-!?]+)\s+(?:"((?:[^"\\]|\\.)*)"\s+)?\[', ops_text):
    name, doc = m.group(1), m.group(2)
    if name == 'change':
        continue
    node('e-' + name, 'ops/' + name, first_para(doc, 300) if doc else 'ops/' + name + '.', 'event', OPS_SRC)

# ---------------------------------------------------------------- MECHANICAL: stores
STORE_DEFS = {
    'loretile-kg-log': 'The OPEN graph\'s event log (localStorage). Other graphs are shelved under loretile-kg-log:{id}.',
    'loretile-graphs': 'The registry of graphs on this device [{:id :title :personal?}].',
    'loretile-graph': 'The id of the graph that is open.',
    'loretile-sessions': 'Assistant sittings {:current :archived}: whole conversations, autosaved.',
    'loretile-learn-sessions': 'Learning runs {:current :archived}.',
    'loretile-attempts': 'The append-only attempt log mastery is projected from (keyed by {graph, concept}).',
    'loretile-scratch': "The learner's scratchpad code, per course, on this device.",
    'loretile-marks': 'The learner option to hide reader marks ("off").',
    'loretile-llm-config': 'The BYOK model configuration (preset, url, model, key).',
    'loretile-llm-usage': "Today's token usage on this device (UTC day); unreadable counts as spent.",
    'loretile-llm-budget': 'The daily token budget (default 400 000).',
    'loretile-vectors': 'Concept embedding cache, keyed by content hash and model.',
    'loretile-locale': 'The UI language, shared with the landing.',
    'loretile-theme': 'Light/dark theme, shared with the landing.',
    'loretile-layout': 'The map layout: layers or free.',
    'loretile-merge-url': 'The S3 bucket address merge files are listed from (merges/{graph-id}/).',
    'loretile-tour-done': 'Seen-flag of the map tour.',
    'loretile-chat-tour-done': 'Seen-flag of the chat tour.',
    'loretile-learn-tour-done': 'Seen-flag of the learn tour.',
}
all_keys = sorted({k for m in modules.values() for k in m['keys']})
for k in all_keys:
    node('s-' + k, k, STORE_DEFS.get(k, 'localStorage key ' + k + '.'), 'store')
node('s-opfs', 'OPFS blobs', 'The browser\'s origin-private file system: uploaded documents and their extracted text, content-addressed. Never in git; the log references their hash.', 'store', source('src-blobs', 'src/loretile/blobs.cljs', 'src/loretile/blobs.cljs'))

for short, m in modules.items():
    for r in m['requires']:
        if r in modules:
            edge('m-' + short, 'm-' + r, 'depends-on', 'inferred-from-data', 'its ns :require')
    for e in m['emits']:
        if 'e-' + e in nodes:
            edge('m-' + short, 'e-' + e, 'emits', 'inferred-from-data',
                 'writes raw {:ev/type :change}' if e == 'change' else 'calls ops/' + e)
    for k in m['keys']:
        edge('m-' + short, 's-' + k, 'stores-in', 'inferred-from-data', 'names the key "' + k + '"')
edge('m-blobs', 's-opfs', 'stores-in', 'inferred-from-data', 'navigator.storage.getDirectory')

# ---------------------------------------------------------------- MECHANICAL: gates
gates = {}
for f in sorted(os.listdir(TEST)):
    if not (f.endswith('-check.js') or f in ('smoke.js',)):
        continue
    lines = open(os.path.join(TEST, f), encoding='utf-8').read().split('\n')
    head = []
    for l in lines:
        if l.startswith('//'):
            t = l[2:].strip()
            if t.lower().startswith('usage'):
                break
            head.append(t)
        elif head:
            break
    gid = 'g-' + f.replace('.js', '')
    gates[f.replace('.js', '')] = gid
    node(gid, 'test/' + f, first_para(' '.join(head), 600) or 'Browser check ' + f + '.', 'gate',
         source('src-test-' + f, 'test/' + f, 'test/' + f))
node('g-fold-tests', 'fold tests (npm test)',
     'The unit tests of the pure layer (src/loretile/fold_test.cljc): the paper simulations, every guard, publish-and-pin, staleness, reader marks, object links, profiles, competency questions, the read tools, the agent export, and a replay of every demo log through schema + guard.',
     'gate', source('src-fold-test', 'src/loretile/fold_test.cljc', 'src/loretile/fold_test.cljc'))

# ---------------------------------------------------------------- CURATED: decisions
DECISIONS = [
    ('d-data-model', 'Data Model', 'wiki/Data-Model.md', 'Concepts, edges, objects, items; attempts belong to items; mastery is a projection; the small closed learning ontology; publish = a cut in the log.'),
    ('d-architecture', 'Architecture', 'wiki/Architecture.md', 'CLJS SPA, thin backend, S3-only, event streams; git is the store.'),
    ('d-authoring', 'Authoring', 'wiki/Authoring.md', 'The intake chat: documents → proposal cards → a person accepts; two steps, concepts then engine-proposed pairs.'),
    ('d-ux', 'UX', 'wiki/UX.md', 'The feed and the map; kintale chrome; absence over disabled controls.'),
    ('d-exercises', 'Exercises', 'wiki/Exercises.md', 'Items whose answer is executed; browser kernels first; grading stays ours.'),
    ('d-continuous', 'Continuous Learning', 'wiki/Continuous-Learning.md', 'The offering stack; §9 bring your own material: several graphs, personal graphs, generated items count only for the learner.'),
    ('d-2026-10-02', 'Decisions 2026-10-02', 'wiki/Decisions-2026-10-02.md', 'Every parked question decided: staleness on material, re-upload cards, intake in the graph language, self-serve first.'),
    ('d-ethics', 'Ethics', 'wiki/Ethics.md', 'The lines not crossed: caps from day one, fail closed; manager sees attestation status only.'),
    ('d-security', 'Security review 2026-10-03', 'wiki/Security-Review-2026-10-03.md', 'Exercise code could read uploaded documents: worker lockdown, CSP, import validation, frame-buster.'),
    ('d-assistant', 'Assistant design 2026-10-02', 'wiki/Assistant-Design-2026-10-02.md', 'One assistant, mode by screen; look-don\'t-touch tools; actions only on tap; the tutor\'s fence.'),
    ('d-review-b', 'Review 2026-10-02 (b)', 'wiki/Review-2026-10-02-b.md', 'Verdict hardening for Python asserts, honest items, generated questions ruling, batched question drafting.'),
    ('d-profiles', 'Profiles', 'wiki/Profiles.md', 'One graph tool, several ontologies; competency questions checked by the fold; the coding agent as the second consumer.'),
]
for did, title, ref, d in DECISIONS:
    node(did, title, d, 'decision', source('src-' + did, ref, ref))

# ---------------------------------------------------------------- CURATED: features
F = [
    # id, title, definition, modules, gates, decision, within
    ('f-event-log', 'The graph is a fold over events',
     'Every change is an event in an append-only log; the graph is reduce(apply-event) over it; guards run at the door, before append. The log is exported to git as log.edn.',
     ['fold', 'schema', 'store'], ['g-fold-tests'], 'd-architecture', None),
    ('f-map', 'Author\'s map',
     'The d3-force map with the kintale rail and drawer: layers layout along the ordering relation, level of detail, ego, tidy, popups, orbit chips for link and delete.',
     ['ui'], ['map-check', 'editor-check'], 'd-ux', None),
    ('f-editing', 'Editing in place',
     'Pencils on title, definition, kind, objects and items (every edit writes version-created, never a re-accept); removal behind a 6 s ring, removed material restorable; a topic can be split (items, objects and learners\' attempts follow) or marked deprecated.',
     ['ui'], ['edit-check', 'topic-actions-check'], 'd-data-model', 'f-map'),
    ('f-intake', 'Document intake',
     'Documents (md/txt/pdf/docx/html) read in parts (a part whose reply is cut at the output cap is read again in halves); the model proposes nodes, then judges engine-proposed pairs (typed by the ontology in a custom graph); everything arrives as cards a person accepts.',
     ['chat', 'fold', 'llm', 'blobs'], ['intake-check', 'split-check', 'steps-check', 'chat-check', 'pdf-check', 'formats-check', 'topic-actions-check'], 'd-authoring', None),
    ('f-sittings', 'Assistant sittings',
     'Whole conversations autosaved and restored; ✎ shelves a sitting, never deletes; past sittings reopen from the palette.',
     ['session', 'chat'], ['session-check'], 'd-assistant', 'f-intake'),
    ('f-reupload', 'Re-upload and staleness',
     'A document named like an existing source is asked about as a new version; per-topic patch/material answers write source-updated; staleness lives on material.',
     ['chat', 'fold'], ['reupload-check', 'version-check'], 'd-2026-10-02', 'f-intake'),
    ('f-asof', 'The graph as it stood',
     'A bitemporal cut over the log: :ev/ts is when a change was made, :ev/recorded when this log took it in; fold/events-as-of filters by both, store/as-of makes every reader show the past, and editing is absent until «Now».',
     ['fold', 'store', 'ui'], ['asof-check', 'g-fold-tests'], 'd-data-model', None),
    ('f-merge', 'Merge files',
     'A log of events dropped on S3 under merges/{graph-id}/ is listed for the open graph (badge on the map, rows in the drawer); picking one opens a sitting whose cards are its events — new topics and links as ghosts on the map, edits and removals marked on their topic — and accept appends the file\'s event verbatim; a merged file is a source of the graph.',
     ['merge', 'chat', 'ui'], ['merge-check'], 'd-architecture', 'f-intake'),
    ('f-graphs', 'Several graphs',
     'The open graph under loretile-kg-log, others shelved per id; switching, new and demo graphs abort when the shelf write fails; other tabs are re-read.',
     ['store'], ['graphs-check'], 'd-continuous', None),
    ('f-demos', 'Demo graphs',
     'Three trilingual demo graphs (SQL, infosec, vineyard), generated by Python builders, each loading into a graph of its own.',
     ['store', 'ui'], ['g-fold-tests'], 'd-continuous', 'f-graphs'),
    ('f-publish', 'Publish and pin',
     'A published version is a cut in the log; pinned learners fold up to it plus patch-level corrections after it.',
     ['fold', 'learn'], ['g-fold-tests', 'learn-check'], 'd-data-model', None),
    ('f-course', 'Course composition',
     'A course = named (topic, level) targets; the course panel with level-aware readiness and the closure highlighted on the map; course intake from a goal.',
     ['ui', 'chat'], ['course-check'], 'd-data-model', None),
    ('f-catalog', 'Catalog and two hats',
     "The learner's catalog across every graph, progress projected from attempts, past runs; the header switches author and learner.",
     ['ui', 'learn'], ['catalog-check'], 'd-continuous', None),
    ('f-learner-loop', 'Learner loop',
     'Diagnostic → personal plan → learn with the tutor → unassisted final → result → next round, as a feed; mastery keyed by {graph, concept}.',
     ['learn'], ['learn-check', 'integrity-check'], 'd-data-model', None),
    ('f-reader-marks', 'Reader marks',
     'Material may carry ==essence== and ++example++; learners can hide them; prompts always get plain text.',
     ['fold', 'learn', 'ui'], ['marks-check'], 'd-2026-10-02', 'f-learner-loop'),
    ('f-object-links', 'Links between learning objects',
     'example-of, simpler-of, follows within one topic; they set the learner\'s reading order.',
     ['fold', 'ui', 'learn'], ['links-check'], 'd-data-model', 'f-learner-loop'),
    ('f-exercises', 'Executed exercises',
     'Items whose answer runs: PGlite and Pyodide in locked-down workers; rows/output/assert checks; every verdict in a clean worker.',
     ['exercise', 'learn', 'ui'], ['exercise-check'], 'd-exercises', 'f-learner-loop'),
    ('f-bench', 'Drafting bench',
     'The model drafts exercises with wrong answers; each draft is judged in the runtime and only survivors become cards.',
     ['chat', 'exercise'], ['bench-check'], 'd-exercises', 'f-exercises'),
    ('f-tools', 'Assistant tools',
     'One registry of look-don\'t-touch reads and tap-only actions, shared by the intake chat, the tutor and the guide; bounded hops and calls.',
     ['tools', 'tool-defs', 'graph-tools'], ['tools-check'], 'd-assistant', None),
    ('f-guide', 'Learner-mode assistant',
     "The catalog's assistant: searches every graph's courses and starts one; can build a course from the learner's own material.",
     ['guide', 'chat'], ['guide-check'], 'd-assistant', 'f-tools'),
    ('f-build-from-material', 'Build from my material',
     'A file becomes a personal graph, question drafting in batches, and a course — the cold-start path.',
     ['guide', 'chat', 'store'], ['build-check'], 'd-continuous', 'f-guide'),
    ('f-budget', 'Token budget',
     'Every model call is admitted against a daily budget on the device; fails closed; key onboarding in Settings.',
     ['llm', 'ui'], ['budget-check'], 'd-ethics', None),
    ('f-i18n', 'Three languages',
     'Every UI string in ru, de and en; prompts stay Russian; closed vocabularies through enum-label; new content stamped with the graph\'s language.',
     ['i18n', 'i18n-ui', 'i18n-chat'], ['i18n-check', 'lang-check', 'locale-check'], 'd-2026-10-02', None),
    ('f-tours', 'Walkthroughs',
     'driver.js tours of the map, the chat and the learner feed, each shown once.',
     ['tour'], ['tour-check'], 'd-ux', None),
    ('f-vectors', 'Concept vectors',
     'Embeddings cached per content hash and model; lexical search when no embeddings endpoint is configured.',
     ['vectors'], ['vectors-check'], 'd-2026-10-02', 'f-intake'),
    ('f-profiles', 'Ontology profiles',
     'A graph = a profile + its log; learning is one profile with reserved relations; a custom ontology comes from an EDN file or the interview, and grows additively.',
     ['profile', 'fold', 'ui', 'chat', 'store'], ['ontology-check'], 'd-profiles', None),
    ('f-cqs', 'Competency questions',
     'What a graph must answer, each with a pattern the fold checks; gaps are computed, never a model\'s opinion; the interview, the gaps card and adversarial questions.',
     ['fold', 'ui', 'chat'], ['ontology-check', 'interview-check', 'gaps-check', 'probe-check'], 'd-profiles', 'f-profiles'),
    ('f-agent-export', 'Export for an agent',
     'The graph as one markdown file: vocabulary, nodes in ordering order, relations both ways, sources, stale flags.',
     ['agent-md', 'store'], ['g-fold-tests'], 'd-profiles', 'f-profiles'),
    ('f-mcp', 'MCP server',
     'A read-only stdio MCP server over one log.edn: describe-ontology, list-nodes, search-nodes, open-node, list-edges, check-cqs; with --sources <folder>, read-source and search-sources open the documents behind the nodes.',
     ['mcp', 'graph-tools'], ['mcp-check'], 'd-profiles', 'f-profiles'),
    ('f-model-reads', 'What a model reads',
     'A description on every node type and relation (vocabulary-described), the graph owner (graph-owner-set), inferred links marked with their reason, search over material, and the graph as RDF (Turtle) for an organisation\'s own tools.',
     ['profile', 'fold', 'graph-tools', 'agent-md', 'rdf', 'ui', 'chat'], ['meaning-check', 'gaps-check', 'g-fold-tests'], 'd-profiles', 'f-profiles'),
    ('f-authors', 'Authors',
     'Every change carries a stable per-device author id; the name set in Settings is written into each graph as author-named; the panel, open-node, the markdown and RDF exports say who added and last changed each topic.',
     ['store', 'fold', 'ui', 'graph-tools'], ['author-check', 'g-fold-tests'], 'd-profiles', None),
    ('f-onto-import', 'Ontology import from Turtle',
     'A Turtle file from an ontology editor (Protégé, TopBraid, VocBench) starts a graph: classes become node types, object properties relations; the order only when stated; what Loretile does not model is counted and shown.',
     ['turtle', 'store', 'ui'], ['import-check', 'g-fold-tests'], 'd-profiles', 'f-profiles'),
    ('f-settings', 'Settings page',
     '#/settings (ui/settings-page): kintale-shaped sections — Общие (language, theme), Ассистент (the BYOK form, chat/config-fields), the token budget (ui/budget-section).',
     ['ui', 'chat', 'llm'], ['budget-check', 'lang-check'], 'd-ux', None),
    ('f-deploy', 'Release and deploy',
     'make deploy: test + smoke, release bundle cache-busted by the git hash, landing + app to the bucket.',
     ['core'], ['smoke', 'landing-check'], 'd-architecture', None),
]
for fid, title, d, mods, gs, dec, within in F:
    node(fid, title, d, 'feature', CODE_MD)
for fid, title, d, mods, gs, dec, within in F:
    for m in mods:
        edge('m-' + m, fid, 'implements')
    for g in gs:
        gid = g if g.startswith('g-') else gates.get(g)
        if gid:
            edge(gid, fid, 'verifies')
    edge(fid, dec, 'decided-in')
    if within:
        edge(fid, within, 'within')

# ---------------------------------------------------------------- CURATED: rules
R = [
    ('r-guard-at-door', 'Guards run before append',
     'Every event passes the schema and the guard before it is appended; the fold trusts what is in the log. Imported logs are validated event by event.',
     ['f-event-log', 'm-fold', 'm-store'], 'd-architecture'),
    ('r-write-reports', 'Every storage write reports',
     'Every localStorage write goes through store/write!, which puts a failure into last-error; graph switching aborts when the shelf write fails — a swallowed quota error once destroyed the open graph.',
     ['f-graphs', 'm-store'], 'd-continuous'),
    ('r-absence', 'Absence, never disabled',
     'A control that cannot act is not rendered (empty graph rail, learning chrome on a graph with nothing learnable) rather than shown disabled.',
     ['f-map', 'f-profiles'], 'd-ux'),
    ('r-semi-controlled', 'Semi-controlled inputs on the map',
     'Inputs that live under the per-tick map redraw are semi-controlled (default-value + on-change); controlled ones drop keystrokes.',
     ['f-map', 'f-editing', 'm-ui'], None),
    ('r-no-unicode-regex', 'No \\p{L} in CLJS regexes',
     'CLJS regex literals have no u flag, so \\p{L} matches nothing; split on spaces and punctuation, use explicit classes.',
     ['m-graph-tools', 'm-chat'], None),
    ('r-i18n-complete', 'Every string in three languages',
     'Every UI entry carries ru, de and en; LLM prompts stay Russian and are marked i18n-ignore; closed vocabularies render through enum-label, never (name kind).',
     ['f-i18n'], 'd-2026-10-02'),
    ('r-content-locale', 'New content in the graph\'s language',
     'Every authoring path stamps :locale (store/locale-for-new); never hardcode "ru" — a course deals only items in its own locale.',
     ['f-i18n', 'f-intake'], 'd-2026-10-02'),
    ('r-two-step-intake', 'Two-step intake',
     'Nodes first, then the model judges engine-proposed pairs; never free-form edges over a document.',
     ['f-intake', 'f-cqs'], 'd-authoring'),
    ('r-gaps-computed', 'Gaps are computed',
     'Whether a competency question is answered is a fold query; the model elicits and drafts, never declares a gap closed.',
     ['f-cqs'], 'd-profiles'),
    ('r-reserved-relations', 'Learning relations are reserved',
     'requires, generalizes, is-confused-with, part-of and see-also cannot appear in a custom profile, so no custom relation reaches the scheduler, closure or mastery.',
     ['f-profiles', 'm-profile'], 'd-profiles'),
    ('r-generated-private', 'Generated questions stay private',
     'Items with origin :generated are dealt only in a personal graph and count only for that learner, shown as self-checked.',
     ['f-learner-loop', 'f-build-from-material'], 'd-continuous'),
    ('r-clean-verdict', 'Every verdict in a clean worker',
     'Grading runs in a fresh locked-down worker, trusted code first, the learner\'s last; storage, IndexedDB, caches and nested workers are removed before any untrusted code.',
     ['f-exercises', 'm-exercise'], 'd-security'),
    ('r-budget-fails-closed', 'The budget fails closed',
     'A call whose worst case would cross today\'s budget is refused before fetch; an unreadable usage record counts as spent.',
     ['f-budget', 'm-llm'], 'd-ethics'),
    ('r-publication-cut', 'Material waits, patches reach',
     'A pinned learner sees material changes only at the next publication; patch-level corrections reach them at once.',
     ['f-publish', 'f-editing'], 'd-data-model'),
    ('r-mastery-key', 'Mastery is keyed by graph and concept',
     'Attempts credit {graph, concept}: ids are slugs, unique inside a graph and meaningless between graphs.',
     ['f-learner-loop'], 'd-continuous'),
    ('r-staleness-on-material', 'Staleness lives on material',
     'A material source update stales the objects written from it, and a concept only when its own definition came from it.',
     ['f-reupload'], 'd-2026-10-02'),
    ('r-tour-flags', 'Browser checks pin the tour flags',
     'Every browser check sets the three tour seen-flags in its localStorage setup, or the tour overlay eats its clicks.',
     ['f-tours'], None),
    ('r-settings-sections', 'New settings are sections, never modals',
     'A new user-facing setting is a new section of #/settings (ui/settings-page), never a modal; its labels in ru, de and en; its value under a loretile-… key written through store/write!.',
     ['f-settings', 'm-ui'], 'd-ux'),
    ('r-commit-before-deploy', 'Commit before deploying',
     'main.js is cache-busted by the git short hash; make deploy refuses a dirty tree.',
     ['f-deploy'], 'd-architecture'),
    ('r-mcp-read-only', 'The MCP server only reads',
     'No write tool exists on the MCP server; every event of the served log is schema-checked.',
     ['f-mcp', 'm-mcp'], 'd-profiles'),
    ('r-questions-counted', 'Questions are counted, never shown',
     'Tools give a model question counts per kind and level, never a stem, option or key — the tutor must not give a check away.',
     ['f-tools', 'm-graph-tools'], 'd-assistant'),
]
# rules an edit elsewhere can break live in CLAUDE.md; the rest in wiki/Code.md
IN_CODE_MD = {'r-generated-private', 'r-publication-cut', 'r-mastery-key', 'r-staleness-on-material'}
for rid, title, d, scope, dec in R:
    node(rid, title, d, 'rule', CODE_MD if rid in IN_CODE_MD else CLAUDE_MD)
    for s in scope:
        edge(rid, s, 'constrains')
    if dec:
        edge(rid, dec, 'decided-in')

# ---------------------------------------------------------------- MECHANICAL: who reads the learning relations by name
# r-reserved-relations exists because these modules match the literal ids;
# a rename must touch each of them (and every stored log.edn, see f-demos)
RESERVED = ['requires', 'generalizes', 'is-confused-with', 'part-of', 'see-also']
for short, m in modules.items():
    if short in ('profile', 'schema'):
        continue
    text = open(os.path.join(ROOT, m['file']), encoding='utf-8').read()
    used = [r for r in RESERVED if re.search(r':' + re.escape(r) + r'\b', text)]
    if used:
        edge('r-reserved-relations', 'm-' + short, 'constrains', 'inferred-from-data',
             'matches ' + ', '.join(':' + u for u in used) + ' by name')
edge('r-reserved-relations', 'f-demos', 'constrains', why='every shipped log.edn carries these ids')

# ---------------------------------------------------------------- write
# `--check` (make test): rebuild in memory and fail when the committed file
# differs — the graph is exactly as current as the code, or the build says so
text = ''.join(edn(e) + '\n' for e in events)
if '--check' in sys.argv:
    old = open(OUT, encoding='utf-8').read() if os.path.exists(OUT) else ''
    if old != text:
        a, b = old.splitlines(), text.splitlines()
        first = next((i for i in range(min(len(a), len(b))) if a[i] != b[i]), min(len(a), len(b)))
        print(f'kg/loretile/loretile.edn is STALE: it no longer matches the code '
              f'(first difference at event {first + 1}; {len(a)} → {len(b)} events).\n'
              f'Run: python3 kg/loretile/build.py   and commit the result.', file=sys.stderr)
        sys.exit(1)
    print(f'kg/loretile/loretile.edn is current ({len(events)} events)')
    sys.exit(0)
with open(OUT, 'w', encoding='utf-8') as f:
    f.write(text)
kinds = {}
for k in nodes.values():
    kinds[k] = kinds.get(k, 0) + 1
print(f'{OUT}: {len(events)} events, {len(nodes)} nodes {kinds}, {len(edges)} edges')

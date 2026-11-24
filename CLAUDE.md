# Loretile

Learning platform: knowledge graph of small learning objects, per-learner mastery model, AI tutor on top of the graph. Since 2026-10-14 also a graph tool for ANY ontology (a graph = a profile + its log; `learning` is one profile). Named 2026-09-03 (the earlier working name Tessella failed the trademark check). Repo: github.com/smogstate/loretile-private (working repo, has the wiki). Public AGPL copy without `wiki/`: github.com/smogstate/loretile, updated by `make publish` (one squashed snapshot commit per run).

## Where things are

- **Wiki** `wiki/` (start at `wiki/Home.md`) — every decision. Data model: `wiki/Data-Model.md`. Ontologies: `wiki/Profiles.md`; the ontology as its own graph (2026-11-19, in progress): `wiki/Ontology-Graph.md`; one fold for every graph, a property graph: `wiki/Property-Graph.md`; graphs kept by the server, local-first sync (2026-11-20, in progress): `wiki/Graph-Sync.md`; graphs of 40 000 nodes — event store + SQLite projection, local OR backend (design 2026-11-23; browser proof DONE 2026-11-24, `node test/store-proof-check.js`; the rest not built): `wiki/Graph-Store.md`.
- **How the code is built, area by area** → `wiki/Code.md` (read the section before changing an area; the design page says why; run the area's checks after):

| area | how it is built | decided in | checks (`test/<name>-check.js`) |
|---|---|---|---|
| Toolchain, schema, fold, store | `wiki/Code.md#toolchain-and-the-core` | `wiki/Architecture.md` · `wiki/Data-Model.md` | `npm test` (fold_test), storage, review, then every check |
| Map, editor, layout, removal · the map at scale (ticks write the svg directly) | `wiki/Code.md#the-map-and-the-editor-uicljs` | `wiki/UX.md` | map, editor, edit, scale |
| Intake chat, attachments, intake review | `wiki/Code.md#the-intake-chat-chatcljs-llmcljs` | `wiki/Authoring.md` | intake, split, steps, chat, formats, pdf, tools, locale, marks (+ ontology, interview, gaps, probe: the prompts are profile-aware) |
| Assistant sittings | `wiki/Code.md#assistant-sittings-sessioncljs` | `wiki/Assistant-Design-2026-10-02.md` | session |
| Demo graphs | `wiki/Code.md#demo-graphs` | `wiki/Continuous-Learning.md` | fold_test `demo-log-is-sound`; every browser check loads the SQL demo |
| Several graphs · ONE Graphs view (open, rename, export, delete; one «new graph» form with an ontology choice; the drawer only navigates) | `wiki/Code.md#several-graphs` | `wiki/Continuous-Learning.md` §9 | graphs, catalog, ontology-graph |
| Publish and pin | `wiki/Code.md#publish-and-pin` | `wiki/Data-Model.md` | course, integrity, fold_test |
| Two hats, catalog | `wiki/Code.md#two-hats` | `wiki/Continuous-Learning.md` | catalog, guide |
| Learner loop | `wiki/Code.md#the-learner-loop-learncljs` | `wiki/MVP.md` M1–M4 | learn, integrity, catalog, exercise, marks, links |
| Tours | `wiki/Code.md#walkthroughs-tourcljs` | `wiki/UX.md` | tour |
| Exercises, sandboxing, bench | `wiki/Code.md#exercises-exercisecljs` | `wiki/Exercises.md` · `wiki/Security-Review-2026-10-03.md` | exercise, bench, learn |
| Ontology profiles | `wiki/Code.md#ontology-profiles` | `wiki/Profiles.md` | ontology, fold_test |
| Ontology import from Turtle (Protégé, TopBraid, VocBench → a profile) | `wiki/Code.md#ontology-import-turtle` | `wiki/Profiles.md` § Ontology import | import, fold_test |
| Undo / redo: a reversing change appended, this session's stacks per graph, Ctrl+Z / Ctrl+Shift+Z | `wiki/Code.md#undo-and-redo` | — | undo, fold_test `an-edit-is-reversed-not-erased` |
| The one door: documents → type/relation cards in an ontology graph; a triage card when nothing is open | `wiki/Code.md#the-one-door-documents-into-graphs` | `wiki/Ontology-Graph.md` slice 3 | vocab-intake (+ intake, formats, pdf, split via `passTriage`) |
| The property-graph core (`change` event with ops) · the ontology as an ordinary graph (types = nodes, relations = edges, vocabulary read out of it), node properties and relation words in the popup, data graphs bound by a copy, never-break sync, migration at load | `wiki/Code.md#the-ontology-as-its-own-graph` | `wiki/Ontology-Graph.md` | ontology-graph, fold_test `an-ontology-is-an-ordinary-graph` (+ ontology, interview, probe, graphs) |
| Graphs kept by the server: local-first sync, pending edits rebased on others', conflicts as assistant cards, «На сервере» in the Graphs view | `wiki/Code.md#the-server-optional-backend` | `wiki/Graph-Sync.md` | sync, server, `sync_merge_test` |
| What a model reads: descriptions, owner, inferred links, RDF export | `wiki/Code.md#what-a-model-reads` | `wiki/Profiles.md` § What a model reads | meaning, gaps, fold_test |
| Authors: who added and changed what (`:ev/actor`, `author-named`, Settings name) | `wiki/Code.md#authors` | `wiki/Profiles.md` § What a model reads | author, fold_test |
| Competency questions · interview (the interview door: build by talking → an ontology graph + content cards) · gaps card · probe | `wiki/Code.md#competency-questions` | `wiki/Profiles.md` § Competency questions | ontology, interview, gaps, probe |
| MCP server · the loretile graph | `wiki/Code.md#the-mcp-server` | `wiki/Profiles.md` § Dogfood | `make mcp`, `build.py --check`, fold_test `loretile-graph-is-sound` |
| The four events (answer, promote, split, deprecate) | `wiki/Code.md#the-four-events-nothing-wrote` | `wiki/Data-Model.md` | topic-actions, fold_test `the-four-events-have-guards` |
| Tools, one assistant by screen, guide, actions | `wiki/Code.md#tools--one-assistant-mode-by-screen` | `wiki/Assistant.md` · `wiki/Assistant-Design-2026-10-02.md` | tools, guide, build |
| Links between learning objects | `wiki/Code.md#links-between-learning-objects` | `wiki/Data-Model.md` | links |
| Reader marks | `wiki/Code.md#reader-marks` | — | marks |
| Token budget, key onboarding | `wiki/Code.md#token-budget` | `wiki/Ethics.md` | budget |
| The graph as it stood: a bitemporal cut over the log (`:ev/ts` made, `:ev/recorded` taken in), a clock on the map, editing absent in the past | `wiki/Code.md#the-graph-as-it-stood` | `wiki/Data-Model.md` § Bitemporal | asof, fold_test `the-graph-as-it-stood` |
| Merge files: a log of events on S3 under `merges/{graph-id}/`, reviewed as cards with the changes on the map | `wiki/Code.md#merge-files` | — | merge |
| Re-upload card (sorted by quote) · staleness on material | `wiki/Code.md#re-upload-card` | `wiki/Decisions-2026-10-02.md` #7 · `wiki/Profiles.md` § Where the graph wins | reupload, version, fold_test `staleness-lives-on-material` `a-new-version-flags-only-what-changed` |
| i18n · locale of new content | `wiki/Code.md#i18n` | `wiki/Decisions-2026-10-02.md` #12 | i18n (in `make test`), lang, locale |
| Concept vectors | `wiki/Code.md#concept-vectors-vectorscljs` | `wiki/Vectors.md` | vectors |
| Tests · release zip · deploy | `wiki/Code.md#tests` | — | `make smoke`, landing |
| Building without shadow-cljs (no Clojars) | `wiki/Code.md#building-without-shadow-cljs` | — | — |

- **The codebase as a graph** — `.mcp.json` serves `kg/loretile/loretile.edn` as the `loretile` MCP server with `--sources .` (run `make mcp` once; `out/` is gitignored). `describe-ontology`, then `search-nodes` / `open-node` / `list-edges` (modules, features, events, stores, gates, rules, decisions and who depends on, writes or verifies what); `read-source` / `search-sources` open the file behind a node. Ask it before grepping for "who writes this event / keeps this key / checks this feature".

## Code map

CLJS SPA, kintale toolchain: shadow-cljs + reagent + malli. `npm run dev` → http://localhost:8291 (`make engines` once first; `npm run dev` alone does not copy PGlite/Pyodide).

- `schema.cljc` — the ONE event (`change`) and the class shapes, the one contract · `ops.cljc` — every kind of write, as a `change` · `fold.cljc` — the KG IS a fold over changes, nodes filed by `:is`; guards run before append · `store.cljs` — event logs in memory, saved to IndexedDB (registry + settings in localStorage), several graphs, export/import log.edn
- `profile.cljc` — ontology profiles (`learning` built in) · `graph_tools.cljc` — the read tools shared by the in-app assistant and `mcp.cljs` (read-only stdio MCP server, `make mcp`) · `agent_md.cljc` — markdown export for an agent
- `ui.cljs` — map, panels, course panel, catalog, `#/ontology`, `#/settings` · `chat.cljs` + `llm.cljs` — the assistant (intake, interview, gaps, probe, build-from-material), BYOK · `session.cljs` — assistant sittings
- `server/` — the optional Loretile server (Clojure, JDK `HttpServer` on virtual threads): serves `public/`, holds the LLM gateway token and the S3 merge bucket behind `api/`; `config.clj` is the ONE list of settings (EDN file at `LORETILE_CONFIG`, file:// or s3://, re-read every 10s; every key overridable from env, `[:llm :token-ttl-ms]` → `LLM_TOKEN_TTL_MS`). It writes `<meta name="loretile-backend">` into index.html; `llm/backend` reads it — absent (every zip/tar.gz), the app stays browser-only with BYOK.
- `learn.cljs` — learner loop as a feed · `exercise.cljs` — executed exercises (PGlite, Pyodide workers; `public/js/pyrunner.js`, `pgrunner.js`) · `tools.cljs` / `tool_defs.cljs` / `guide.cljs` — tool registry, tools, learner-mode assistant · `tour.cljs` · `vectors.cljs` · `i18n*.cljs`

## Rules an edit can break

- **Every event is a `change`, built by `ops.cljc`, and passes schema + guard before append**; imported logs are validated event by event. A new kind of write → an `ops` constructor, its rule in `fold/op-problem` (or `change-problem` for the whole change), a class shape in `schema/class-schemas` if it is a new class, a writer in the UI (the loretile graph's `check-cqs` asks «which module makes this kind of write?»). Never write a raw old-style event (`:concept-accepted` …): the schema refuses it, and a seeded log containing one is set aside at load.
- **Every storage write goes through `store/write!`** (localStorage) **or `store/save-log!`** (a graph's log, IndexedDB) — both report failure into `last-error`; graph switches abort on a failed shelf write — a swallowed quota error once destroyed the open graph. Checks read logs through `window.loretileLog(id?)` / `loretileLogs()`, never raw storage.
- **i18n**: every UI string via `(t :ui/…)` / `(t :chat/…)` with ru+de+en; LLM prompts stay Russian, marked `;; i18n-ignore`; closed vocabularies via `i18n/enum-label`, NEVER `(name kind)` in the DOM. Gate `node test/i18n-check.js` (in `make test`).
- **New content in the graph's language**: stamp `:locale (store/locale-for-new)`; never hardcode "ru" (a course deals only items in its own locale).
- **Absence, never disabled**: a control that cannot act is not rendered (empty-graph rail, learning chrome on a non-learnable profile).
- **New settings are sections of `#/settings`** (`ui/settings-page`), never modals.
- **Learning relation ids are reserved** (`requires`, `generalizes`, `is-confused-with`, `part-of`, `see-also`) — a custom profile cannot use them. Gaps of competency questions are COMPUTED by the fold, never a model's opinion.
- **Intake is two-step**: concepts first, then the model judges engine-proposed pairs (`fold/edge-candidates`) — never free-form edges over a document.
- **Exercise verdicts run in a clean, locked-down worker** (`ex/judged`); never weaken the lockdown in `pyrunner.js` / `pgrunner.js` (wiki/Security-Review-2026-10-03.md).
- **Tools show a model question counts, never stems, options or keys.** The MCP server has no write tool.
- **Two modes, one bundle**: backend mode is ONLY the `loretile-backend` meta the server injects — never write it into `public/index.html` or a zip; in that mode key and bucket settings are absent (`llm/backend`). The server's settings live only in `server/src/loretile/server/config.clj` `spec`.
- **Every model call goes through `llm/admit`** (daily token budget, fails closed).
- **Generated files — edit the generator, rerun, commit the output**: demo graphs `kg/demo/build*.py` → `kg/demo/*.edn` (served as `public/demo/`) (translations inline, same ids across locales); the codebase graph `python3 kg/loretile/build.py` → `kg/loretile/loretile.edn` — `make test` runs `--check` and FAILS when it no longer matches the code (requires, event writers, storage keys, docstrings, checks). Rerun it after changing any of those.
- **Keep `wiki/Code.md` current**: a new feature or a changed decision gets its paragraph there (and a rule here only if an edit elsewhere can break it).

## Gotchas

- CLJS regex literals have no `u` flag — `\p{L}` matches nothing; use explicit classes / split on spaces and punctuation.
- A controlled input whose value flows through parent props drops fast keystrokes (the map redraws every physics tick) — `grow-textarea`, the palette's `.cmd-input`, the pencil edit forms and the add forms are semi-controlled for that reason.
- Never deref an atom inside a lazy `for` in a render — the component never re-renders; deref once at the top.
- A render reads a node's spot with `ui/pos` (live, off the sim's node), never `(get @positions id)` — past 150 nodes `positions` is only republished when the layout settles.
- The map sim measures `svg.graph`, never the first svg (rail icons are svgs). A ghost/button inside the map needs `:on-pointer-down` stopPropagation or the svg's pointer capture eats the click.
- `loretile.export` is a reserved word in JS output — hence `agent_md.cljc`.

## Tests

- `make test` — fold tests (`npm test`), i18n check, the loretile graph's `--check`. `make mcp` builds + checks the MCP server. `make smoke` boots the release bundle headless.
- Browser e2e: serve `public/` on :8291 (after `make engines`), then `make checks` runs EVERY check 8 at a time (`J=…`; ~5 min, ~20 serial; logs in `out/checks/`) or `node test/<name>-check.js` one (needs /usr/bin/google-chrome; the list is in `wiki/Code.md` § Tests). Every browser check pins `loretile-locale=ru` and the three tour seen-flags in its localStorage setup, or the tour overlay eats its clicks. A check that uploads a non-ASCII-named fixture launches Chromium with `env: {...process.env, LANG: process.env.LANG || 'C.UTF-8'}` — with no LANG (cloud sessions) the upload silently does nothing.
- No Clojars (cloud sessions)? `wiki/Code.md` § Building without shadow-cljs.

## Release and deploy

- Publishing a GitHub release runs `.github/workflows/release.yml` → `make test smoke dist dist-enterprise smoke-enterprise` → `dist/loretile-app-<tag>.zip` and `dist/loretile-app-<tag>-enterprise.zip` (no demo graphs: `<meta name="loretile-demos" content="off">` in index.html makes every demo control absent) attached to the release.
- `.github/workflows/build.yml` (public repo only): every push to master there — i.e. every `make publish` — tags `0.{commits}.{tags already on the commit}` (first build `.0`), runs the same gates, and releases both bundles as tar.gz; a failed build drops its tag.
- `Dockerfile` → the app with its server, `docker run -p 8080:8080 -e LORETILE_CONFIG=… ghcr.io/smogstate/loretile:<version>`; `build.yml` pushes it beside the tar.gz. Settings: `server/config.example.edn`. `make server CONFIG=…` runs it locally; `node test/server-check.js` (after `make release`) checks backend mode, the token refresh and the env overrides.
- `make deploy` → landing at https://loretile.com + app at /app/. COMMIT BEFORE DEPLOYING — main.js is cache-busted by the git short hash and deploy refuses a dirty tree. App asset paths are document-relative. No new deps without need.

**This project does not use Loom** — ignore the workspace-level Loom instructions here.

// Shared steps for browser checks since the drawer only navigates (Denis
// 2026-11-19): graphs and ontologies are opened, made, exported and deleted
// in the Graphs view, #/graphs — one set of graphs, each with an ontology.
const fs = require('fs');
const path = require('path');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// open the Graphs view and click the row whose title matches `re`
async function openGraph(page, base, re) {
  await page.goto(base + '#/graphs', { waitUntil: 'networkidle0' });
  await sleep(250);
  const ok = await page.evaluate((src) => {
    const a = [...document.querySelectorAll('.entity-row .entity-title')].find((e) => new RegExp(src).test(e.textContent));
    if (a) a.click();
    return !!a;
  }, re.source);
  await sleep(700);
  return ok;
}

// titles of the rows in the Graphs view; the open one is marked with «*»
async function listRows(page, base) {
  await page.goto(base + '#/graphs', { waitUntil: 'networkidle0' });
  await sleep(250);
  return page.$$eval('.entity-row', (es) => es.map((e) => (e.classList.contains('on') ? '*' : '') + e.querySelector('.entity-title').textContent));
}

// «Новый граф» in the Graphs view: the form takes a title and an ontology —
// "learning" (default), "ontology" (the graph describes types) or a graph id
async function newGraph(page, base, title, ontology = 'learning') {
  await page.goto(base + '#/graphs', { waitUntil: 'networkidle0' });
  await sleep(250);
  await page.click('.graphs-page .new-graph');
  await page.waitForSelector('.new-graph-form');
  await page.type('.new-graph-title', title);
  await page.select('.new-graph-ontology', ontology);
  await page.click('.new-graph-create');
  await sleep(700);
}

// THE ONE DOOR (2026-11-19): a document dropped while nothing is open (an
// empty graph on no ontology) waits in a triage card. Answer it — graph
// content on the built-in learning ontology unless told otherwise — so a
// check about the intake itself goes on as before. No card: nothing to do.
async function passTriage(page, what = 'content', ontology = 'learning') {
  for (let i = 0; i < 10 && !(await page.$('.card-triage')); i++) await sleep(100);
  if (!(await page.$('.card-triage'))) return false;
  await page.click(what === 'ontology' ? '.card-triage .triage-ontology' : '.card-triage .triage-content');
  if (what !== 'ontology') await page.select('.card-triage .triage-which', ontology).catch(() => {});
  await page.click('.card-triage .triage-go');
  await sleep(300);
  return true;
}

// THE DROPPED DOOR, kept for checks until slice 3 of wiki/Ontology-Graph.md
// replaces it with the assistant's cards: an ontology file (edn or Turtle)
// → an ontology graph «Онтология: {title}» made from it, and a graph
// {title} bound to it (every graph's vocabulary lives in an ontology graph
// since the learning slice, 2026-11-20). Drives store internals: DEV BUILD
// ONLY — returns null on a release build so the check can skip.
async function graphFromOntologyFile(page, file, title) {
  const text = fs.readFileSync(file, 'utf8');
  return page.evaluate((text, fname, title) => {
    if (typeof loretile === 'undefined' || !loretile.store || !loretile.store.read_profile) return null;
    const kw = cljs.core.keyword, get = cljs.core.get;
    const r = loretile.store.read_profile(text, fname);
    const err = get(r, kw('error'));
    if (err) { cljs.core.reset_BANG_(loretile.store.last_error, err); return false; }
    const oid = loretile.store.new_ontology_graph_BANG_('Онтология: ' + title, get(r, kw('profile')));
    const gid = loretile.store.new_graph_BANG_(title);
    const bad = loretile.store.bind_BANG_(gid, oid);
    if (bad) { cljs.core.reset_BANG_(loretile.store.last_error, bad); return false; }
    location.hash = '#/';
    return true;
  }, text, path.basename(file), title);
}

module.exports = { openGraph, listRows, newGraph, graphFromOntologyFile, passTriage, sleep };

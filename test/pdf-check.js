// PDF ingestion end-to-end: generate a minimal 2-page PDF fixture, upload it
// in the intake chat, extraction runs in-browser (pdf.js, lazy import), the
// page-marked text goes to the (mocked) LLM, evidence shows on concept cards,
// and a duplicate proposal in the response is dropped by the fold guard.
// Usage: serve public/, then: node test/pdf-check.js [baseUrl]

const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');
const G = require('./lib/graphs');
const BASE = process.argv[2] || 'http://localhost:8291';
const CHROME = process.env.CHROME_BIN || '/usr/bin/google-chrome';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

// --- fixture: hand-assembled PDF, ASCII only so offsets are just lengths ----
function makePdf(pageTexts) {
  const objs = [];
  const n = pageTexts.length;
  const kids = pageTexts.map((_, i) => `${4 + i * 2} 0 R`).join(' ');
  objs.push(`1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n`);
  objs.push(`2 0 obj\n<< /Type /Pages /Kids [${kids}] /Count ${n} >>\nendobj\n`);
  objs.push(`3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n`);
  pageTexts.forEach((t, i) => {
    const po = 4 + i * 2;
    const stream = `BT /F1 12 Tf 50 700 Td (${t}) Tj ET`;
    objs.push(`${po} 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] ` +
      `/Resources << /Font << /F1 3 0 R >> >> /Contents ${po + 1} 0 R >>\nendobj\n`);
    objs.push(`${po + 1} 0 obj\n<< /Length ${stream.length} >>\nstream\n${stream}\nendstream\nendobj\n`);
  });
  let out = '%PDF-1.4\n';
  const offsets = [];
  for (const o of objs) { offsets.push(out.length); out += o; }
  const xref = out.length;
  out += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n` +
    offsets.map((o) => String(o).padStart(10, '0') + ' 00000 n \n').join('');
  out += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
  return out;
}

const FIXTURE = path.join(__dirname, 'fixture.pdf');
fs.writeFileSync(FIXTURE, makePdf(['GROUP BY puts rows into buckets', 'HAVING filters whole buckets']));

const MOCK_OPS = {
  concepts: [
    { id: 'group-by', title: 'GROUP BY', definition: 'Складывает строки в корзины',
      evidence: '[стр. 1] GROUP BY puts rows into buckets' },
    { id: 'having', title: 'HAVING', definition: 'Фильтр по корзинам' },
    // duplicate id in the same response — the guard must drop it
    { id: 'group-by', title: 'GROUP BY (дубль)', definition: 'Дубль' },
  ],
  edges: [{ from: 'having', to: 'group-by', type: 'requires', why: 'HAVING без GROUP BY не имеет смысла' }],
  objects: [], questions: [],
  note: 'Разобрал PDF.',
};

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--disable-gpu', '--lang=ru'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });

  await page.evaluateOnNewDocument((mock) => {
    const orig = window.fetch.bind(window);
    window.__llmCalls = [];
    window.fetch = (url, opts) => {
      if (String(url).includes('api.anthropic.com')) {
        window.__llmCalls.push(JSON.parse(opts.body));
        return Promise.resolve(new Response(JSON.stringify({
          content: [{ type: 'text', text: JSON.stringify(mock) }],
        }), { status: 200 }));
      }
      return orig(url, opts);
    };
  }, MOCK_OPS);

  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(() => { localStorage.clear(); localStorage.setItem('loretile-locale', 'ru'); localStorage.setItem('loretile-tour-done','1'); localStorage.setItem('loretile-chat-tour-done','1'); localStorage.setItem('loretile-learn-tour-done','1'); });
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' });
  await page.reload({ waitUntil: 'networkidle0' });
  await sleep(200);

  // configure BYOK (anthropic preset)
  await page.click('.chat-head .pt-model');   // → #/settings
  await sleep(200);
  const inputs = await page.$$('.settings-page input');
  await inputs[2].type('sk-test-123');
  await page.click('.settings-page button.primary');
  await sleep(150);
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' });
  await sleep(200);

  // upload the PDF (hidden input)
  const file = await page.$('.composer input[type=file]');
  await file.uploadFile(FIXTURE);
  // extraction is async (worker module loads lazily) — poll for the chip
  let chip = null;
  for (let i = 0; i < 40 && !chip; i++) { await sleep(250); chip = await page.$('.doc-chip'); }
  check(chip !== null, 'doc chip appears after extraction');
  if (chip) {
    const chipText = await page.$eval('.doc-chip', (e) => e.textContent);
    check(/fixture\.pdf/.test(chipText) && /2 стр/.test(chipText), 'chip shows name and page count');
  }
  check(/Прочитан «fixture\.pdf»: 2 стр/.test(await page.$eval('.msgs', (e) => e.textContent)),
    'system message reports the read');
  check(/:source-added/.test(await page.evaluate(() => loretileLog())),
    'attaching a document adds a source node');
  await page.screenshot({ path: 'test/shot-pdf-attached.png' });

  // send with the doc only (no typed text)
  await page.click('.composer button.primary');
  await G.passTriage(page);   // nothing open yet: the one door asks first
  await sleep(500);
  const sent = await page.evaluate(() => window.__llmCalls[0].messages[0].content);
  check(/\[стр\. 1\] GROUP BY puts rows into buckets/.test(sent), 'page-1 text with marker sent to LLM');
  check(/\[стр\. 2\] HAVING filters whole buckets/.test(sent), 'page-2 text with marker sent to LLM');

  // duplicate concept dropped: 2 concepts + 1 edge = 3 cards, and a notice
  check((await page.$$('.card')).length === 2, 'duplicate proposal dropped (2 topic cards, not 3)');
  check(/Пропущено/.test(await page.$eval('.msgs', (e) => e.textContent)), 'drop notice shown');
  check(await page.$('.doc-chip') === null, 'chip cleared after send');
  const evid = await page.evaluate(() =>
    Array.from(document.querySelectorAll('.card p')).some((p) => /стр\. 1/.test(p.textContent)));
  check(evid, 'evidence quote rendered on the concept card');

  await page.screenshot({ path: 'test/shot-pdf-cards.png' });

  // accepting a concept proposed from the document grounds it in the source
  await page.click('.cards .card button.primary');
  await sleep(200);
  const log = await page.evaluate(() => loretileLog());
  check(/:source-id "src-/.test(log), 'accepted concept carries the source id');
  check((await page.$$('.card')).length === 1, 'card resolved after accept');

  // the document itself and its extraction live in the blob store (OPFS)
  const srcId = (/:source-id "(src-[0-9a-f]+)"/.exec(log) || [])[1];
  const blobs = await page.evaluate(async () => {
    const d = await navigator.storage.getDirectory(); const names = [];
    for await (const k of d.keys()) names.push(k); return names;
  });
  check(blobs.includes(srcId + '.pdf') && blobs.includes(srcId + '.md'), 'bytes + extraction stored under the hash');

  // source viewer: page-marked text, download link, who cites it; anchor highlights a page
  await page.goto(BASE + '#/src/' + srcId + '?a=p2', { waitUntil: 'networkidle0' });
  await sleep(400);
  check((await page.$$('.srcpage')).length === 2, 'viewer splits the extraction into 2 pages');
  check(await page.$('.srcpage.hit#src-p2') !== null, 'anchor p2 highlighted');
  check(await page.$('a[download]') !== null, 'original downloadable');
  check(/Темы на этом источнике/.test(await page.$eval('.panel', (e) => e.textContent)), 'viewer lists grounded concepts');
  await page.screenshot({ path: 'test/shot-source.png' });

  // concept page links back to the source
  await page.goto(BASE + '#/c/group-by', { waitUntil: 'networkidle0' });
  await sleep(300);
  check(/Источники/.test(await page.$eval('.panel', (e) => e.textContent)), 'concept panel has «Источники»');
  check((await page.$eval('.panel a.chip', (e) => e.getAttribute('href'))).startsWith('#/src/' + srcId), 'source chip links to the viewer');
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' });
  await sleep(200);

  // markdown attaches through the same chip (no page count), and a second
  // send of the same material is fully deduped by the fold guard
  const MD = path.join(__dirname, 'fixture.md');
  fs.writeFileSync(MD, '# SQL\n\nGROUP BY puts rows into buckets. HAVING filters whole buckets.');
  const file2 = await page.$('.composer input[type=file]');
  await file2.uploadFile(MD);
  let chip2 = null;
  for (let i = 0; i < 20 && !chip2; i++) { await sleep(150); chip2 = await page.$('.doc-chip'); }
  check(chip2 !== null, 'md attaches as a chip');
  if (chip2) {
    const t = await page.$eval('.doc-chip', (e) => e.textContent);
    check(/fixture\.md/.test(t) && !/стр/.test(t), 'md chip: name, no page count');
  }
  // same name as the PDF in another format: the chat asks whether it is a new
  // version (the re-upload card); here it is a separate document
  check(await page.$('.card-version') !== null, 'a same-named upload is asked about: a new version?');
  if (await page.$('.card-version')) { await page.click('.card-version .version-no'); await sleep(200); }
  await page.click('.composer button.primary');
  await sleep(500);
  const sent2 = await page.evaluate(() => window.__llmCalls[window.__llmCalls.length - 1].messages[0].content);
  check(/GROUP BY puts rows into buckets\. HAVING/.test(sent2), 'md content sent to LLM');
  check((await page.$$('.card')).length === 1, 're-sent material adds no cards (all deduped)');
  fs.unlinkSync(MD);
  await page.setViewport({ width: 390, height: 844 });
  await sleep(200);
  await page.screenshot({ path: 'test/shot-pdf-mobile.png' });

  await browser.close();
  console.log(failures === 0 ? 'PDF CHECK OK' : 'PDF CHECK FAILED: ' + failures);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(2); });

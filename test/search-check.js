// Search modes (wiki/Graph-Store.md): verbatim — the text as written (a phrase);
// semantic — by meaning (the query's vector, made by the provider, against the
// vectors the background embedder made); hybrid (default) — words fused with
// meaning. Embeddings are mocked: the join topic's wording («склеивает») and the
// query «соединение данных» → [1,0], everything else → [0,1].
// Usage: serve public/ (after make engines), then: node test/search-check.js [baseUrl]
const fs = require('fs');
const puppeteer = require('puppeteer-core');
const BASE = process.argv[2] || 'http://localhost:8291';
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };
const SQL = fs.readFileSync(__dirname + '/../kg/demo/sql.ru.edn', 'utf8').split('\n').filter((l) => l.trim() && !l.startsWith(';'));

(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  try {
    const page = await browser.newPage();
    await page.evaluateOnNewDocument(() => {
      window.__embedded = 0;
      const orig = window.fetch.bind(window);
      window.fetch = (url, opts) => {
        if (!String(url).endsWith('/embeddings')) return orig(url, opts);
        const body = JSON.parse(opts.body);
        window.__embedded += body.input.length;
        const data = body.input.map((t, index) => ({ index, embedding: /склеива|соединение данных/i.test(t) ? [1, 0] : [0, 1] }));
        return Promise.resolve(new Response(JSON.stringify({ data, usage: { total_tokens: 10 } }), { status: 200 }));
      };
    });
    await page.goto(BASE, { waitUntil: 'networkidle0' });
    await page.evaluate(() => new Promise((ok) => { localStorage.clear(); const q = indexedDB.deleteDatabase('loretile'); q.onsuccess = q.onerror = q.onblocked = ok; }));
    await page.evaluate((log) => {
      localStorage.setItem('loretile-locale', 'ru');
      for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
      localStorage.setItem('loretile-kg-log', log);
      localStorage.setItem('loretile-llm-config', '{:preset :custom :shape :openai-compat :url "https://x.test/v1/chat/completions" :model "m" :key "k" :embed-url "https://x.test/v1/embeddings" :embed-model "emb"}');
    }, '[' + SQL.join(' ') + ']');
    await page.reload({ waitUntil: 'networkidle0' });
    await page.waitForFunction(() => window.loretileGraphStore && window.loretileGraphStore.ready() && window.loretileGraphStore.current(), { timeout: 60000, polling: 250 });
    // the background embedder fills the vectors (a rebuild right after boot waits ~5 s)
    await page.waitForFunction(() => window.__embedded > 20, { timeout: 60000, polling: 250 });
    await new Promise((r) => setTimeout(r, 1500));
    const s = (q, mode) => page.evaluate((q, mode) => window.loretileGraphStore.search(q, mode), q, mode);

    const v1 = await s('склеивает строки двух таблиц', 'verbatim');
    check(v1.length > 0 && v1[0].id === 'join', `verbatim: the phrase as written finds «join» (${v1.map((h) => h.id).join(', ')})`);
    const v2 = await s('таблиц двух строки склеивает', 'verbatim');
    check(!v2.some((h) => h.id === 'join'), `verbatim: the same words out of order are not the phrase (${v2.map((h) => h.id).join(', ') || 'none'})`);
    const sem = await s('соединение данных', 'semantic');
    check(sem.length > 0 && (sem[0].id === 'join' || sem[0].parent === 'join'), `semantic: by meaning — no shared word with the definition — finds a JOIN topic first (${sem.slice(0, 3).map((h) => h.id).join(', ')})`);
    const wordsOnly = await s('соединение данных', 'verbatim');
    check(!wordsOnly.some((h) => h.id === 'join'), 'verbatim does not find what only the meaning holds');
    const hy = await s('соединение данных');
    check(hy.length > 0 && (hy[0].id === 'join' || hy[0].parent === 'join'), `hybrid (the default) finds by meaning too (${hy.slice(0, 3).map((h) => h.id).join(', ')})`);
    const hy2 = await s('GROUP BY');
    check(hy2.some((h) => h.id === 'group-by' || h.parent === 'group-by'), `hybrid finds by words too (${hy2.slice(0, 3).map((h) => h.id).join(', ')})`);
    const calls = await page.evaluate(() => window.__embedded);
    await s('соединение данных', 'semantic');
    check(await page.evaluate(() => window.__embedded) === calls, 'a query asked again is not embedded again');
  } catch (e) {
    console.log('CHECK CRASHED', e); failures++;
  } finally {
    await browser.close();
  }
  console.log(failures ? `search: ${failures} FAILED` : 'search: OK');
  process.exit(failures ? 1 : 0);
})();

// A new version of a source flags what changed, not the whole page (change
// test 2026-10-23: three updated handbook pages, 207 topics flagged where 5
// facts had changed). The topics written from the old version are sorted
// by the passage each quotes: gone from the new text → its own card, «the
// meaning changed» suggested; still there → one card for all, «just
// corrections»; nothing to check → its own card as before.
// Usage: serve public/ on :8291; node test/version-check.js
const puppeteer = require('puppeteer-core');
const fs = require('fs'); const os = require('os'); const path = require('path');
const BASE = process.argv[2] || 'http://localhost:8291';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
const check = (ok, name) => { console.log((ok ? 'PASS' : 'FAIL') + ' ' + name); if (!ok) failures++; };

const OLD = '# AP\n\nAll invoices are subject to a 10% PO tolerance.\n\nPayment Runs are completed on Thursdays.\n\nVendors email ap@example.com.\n';
const NEW = '# AP\n\nAll invoices are subject to a 5% PO tolerance.\n\nPayment Runs are completed on Thursdays.\n\nVendors email ap@example.com.\n';
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'version-'));
fs.writeFileSync(path.join(dir, 'accounts-payable.md'), NEW);
const ev = (id, rest) => `{:ev/id "${id}" :ev/ns "g" :ev/actor "t" :ev/ts 1 ${rest}}`;
const LOG = '[' + [
  ev('1', ':ev/type :source-added :source {:id "src-ap-old" :title "accounts-payable.md" :ref "src-ap-old.md"}'),
  ev('2', ':ev/type :concept-accepted :concept {:id "tolerance" :title "Допуск 10%" :definition "Счёт может превысить заказ на 10%." :source-id "src-ap-old" :evidence "All invoices are subject to a 10% PO tolerance."}'),
  ev('3', ':ev/type :concept-accepted :concept {:id "run" :title "Платёжный день" :definition "Платежи уходят по четвергам." :source-id "src-ap-old" :evidence "Payment Runs are completed on Thursdays."}'),
  ev('4', ':ev/type :concept-accepted :concept {:id "inbox" :title "Почта AP" :definition "Счета присылают на почту." :source-id "src-ap-old"}'),
].join(' ') + ']';

(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
  await page.setViewport({ width: 1280, height: 900 });
  const log = () => page.evaluate(() => loretileLog());
  const evs = async () => (await log()).split(/(?=\{:ev\/)/);
  await page.goto(BASE, { waitUntil: 'networkidle0' });
  await page.evaluate(async (l, old) => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
    localStorage.setItem('loretile-kg-log', l);
    // the old version's text, as the blob store kept it since its upload
    const d = await navigator.storage.getDirectory();
    const fh = await d.getFileHandle('src-ap-old.md', { create: true }); const w = await fh.createWritable(); await w.write(old); await w.close();
  }, LOG, OLD);
  await page.goto(BASE + '#/chat', { waitUntil: 'networkidle0' }); await page.reload({ waitUntil: 'networkidle0' }); await sleep(600);
  await (await page.$('.composer input[type=file]')).uploadFile(path.join(dir, 'accounts-payable.md')); await sleep(1200);
  await page.click('.card-version .version-yes'); await sleep(1000);

  const msg = await page.$$eval('.chat .msgs .msg', (m) => m.map((x) => x.textContent).pop());
  check(/написано тем: 3\. Цитата изменилась у 1, осталась прежней у 1, проверить нечем у 1/.test(msg), `the chat sorts the topics by their quote (${msg.slice(0, 140)})`);
  const changed = await page.$$eval('.card-reversion.quote-changed', (cs) => cs.map((c) => c.textContent));
  check(changed.length === 1 && /Допуск 10%/.test(changed[0]) && /Этого места в новой версии нет: «All invoices are subject to a 10% PO tolerance\.»/.test(changed[0]),
    'the topic whose quote is gone gets its own card, with the old quote');
  check(/1/.test(await page.$eval('.card-reversion-kept', (e) => e.textContent).catch(() => '')) && /Платёжный день/.test(await page.$eval('.card-reversion-kept', (e) => e.textContent).catch(() => '')),
    'the topic whose quote is still there shares one card');
  check((await page.$$('.card-reversion:not(.quote-changed)')).length === 1, 'the topic with no quote to check gets its own card, as before');

  await page.click('.card-reversion.quote-changed .reversion-material'); await sleep(300);
  await page.click('.reversion-kept-patch'); await sleep(300);
  const ups = (await evs()).filter((x) => x.includes(':source-updated'));
  check(ups.some((x) => x.includes(':change :material') && x.includes('"tolerance"')), 'the changed topic is marked «the meaning changed»');
  check(ups.some((x) => x.includes(':change :patch') && x.includes('"run"')), 'the unchanged ones «just corrections», in one event');
  check(!ups.some((x) => x.includes('"inbox"')), 'and the unchecked one waits for the author');
  await page.goto(BASE + '#/c/tolerance', { waitUntil: 'networkidle0' }); await sleep(500);
  check(/устарел/i.test(await page.$eval('.panel', (e) => e.textContent).catch(() => '')), 'the changed topic reads as stale');
  await page.goto(BASE + '#/c/run', { waitUntil: 'networkidle0' }); await sleep(500);
  check(!/устарел/i.test(await page.$eval('.panel', (e) => e.textContent).catch(() => '')), 'the unchanged one does not');

  await browser.close();
  fs.rmSync(dir, { recursive: true, force: true });
  console.log(failures ? 'VERSION CHECK FAILED' : 'VERSION CHECK OK');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error('CHECK CRASHED', e); process.exit(1); });

// The Loretile server (server/) in front of the RELEASE bundle (`make release`
// first): a fake gateway that accepts only its second token, the server with a
// token provider file and env overrides, and the app in backend mode.
// Usage: node test/server-check.js
const { spawn, execFileSync } = require('child_process');
const fs = require('fs');
const http = require('http');
const os = require('os');
const path = require('path');
const puppeteer = require('puppeteer-core');

const ROOT = path.resolve(__dirname, '..');
const CHROME = process.env.CHROME_BIN || '/usr/bin/google-chrome';
const PORT = 8297;
const GW = 8298;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const fail = (m) => { console.error('SERVER CHECK FAILED: ' + m); process.exitCode = 1; };

(async () => {
  const seen = [];
  const gateway = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      seen.push({ url: req.url, auth: req.headers.authorization, body });
      const ok = req.headers.authorization === 'Bearer tok-2';
      res.writeHead(ok ? 200 : 401, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ choices: [{ message: { content: 'ok' } }], usage: { total_tokens: 3 } }));
    });
  }).listen(GW);

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'loretile-server-'));
  fs.writeFileSync(path.join(dir, 'token.clj'),
    '(def n (atom 0))\n(fn [] {:token (str "tok-" (swap! n inc)) :expires-in-ms 600000})\n');
  fs.writeFileSync(path.join(dir, 'config.edn'),
    `{:server {:port ${PORT}} :llm {:url "http://127.0.0.1:${GW}/v1/chat" :model "from-file"` +
    ` :token-provider-file "${path.join(dir, 'token.clj')}"}}`);

  const cp = execFileSync('clojure', ['-Spath'], { cwd: path.join(ROOT, 'server') }).toString().trim();
  const server = spawn('java', ['-cp', `${path.join(ROOT, 'server/src')}:${cp}`, 'clojure.main', '-m', 'loretile.server.main'], {
    env: { ...process.env, LORETILE_CONFIG: 'file://' + path.join(dir, 'config.edn'),
           LLM_MODEL: 'from-env', SERVER_PUBLIC_DIR: path.join(ROOT, 'public') },
    stdio: ['ignore', 'inherit', 'inherit'],
  });

  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--disable-gpu'] });
  try {
    for (let i = 0; i < 60; i++) {
      if (await fetch(`http://localhost:${PORT}/api/health`).then((r) => r.ok, () => false)) break;
      await sleep(500);
    }
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await page.evaluateOnNewDocument(() => {
      window.requestAnimationFrame = (cb) => setTimeout(() => cb(performance.now()), 16);
      localStorage.setItem('loretile-locale', 'ru');
      for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
    });
    await page.goto(`http://localhost:${PORT}/#/settings`, { waitUntil: 'networkidle2', timeout: 30000 });
    await page.waitForSelector('.settings-page', { timeout: 15000 });

    const meta = await page.$eval('meta[name=loretile-backend]', (m) => m.content).catch(() => null);
    if (!meta || !meta.includes('"from-env"')) fail('backend meta missing or env override not applied: ' + meta);
    const s = await page.evaluate(() => ({
      text: document.querySelector('.settings-page').innerText,
      keyInputs: document.querySelectorAll('.settings-page input[type=password]').length,
      mergeUrl: !!document.querySelector('.merge-url'),
    }));
    if (s.keyInputs) fail('the key field is rendered in backend mode');
    if (s.mergeUrl) fail('the bucket field is rendered in backend mode');
    if (!s.text.includes('from-env')) fail('the settings do not name the model');

    const reply = await page.evaluate(() =>
      fetch('api/llm', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{"model":"from-env"}' })
        .then((r) => r.status));
    if (reply !== 200) fail('api/llm answered ' + reply);
    if (seen.map((x) => x.auth).join(',') !== 'Bearer tok-1,Bearer tok-2') fail('token refresh on 401: ' + JSON.stringify(seen.map((x) => x.auth)));
    if (errors.length) fail('page errors: ' + errors.join('; '));
  } finally {
    await browser.close();
    server.kill();
    gateway.close();
  }
  if (!process.exitCode) console.log('server check OK');
})().catch((e) => { fail(e.message); process.exit(1); });

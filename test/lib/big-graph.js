// A synthetic graph of N topics as log.edn events (wiki/Graph-Store.md): one
// source, topics drafted from it (≈ 30-word definitions), an explanation on
// every 4th topic, 2 `requires` links per topic to earlier ones (acyclic), a
// course on the first six. Deterministic.
const WORDS = 'анализ данные модель процесс правило система роль метрика решение запрос таблица отчёт поле ключ индекс связь группа фильтр порядок итог счёт сумма среднее период клиент заказ товар склад поставка договор риск контроль аудит доступ политика версия журнал событие источник цель шаг этап проверка ошибка'.split(' ');
function rng(seed) { return () => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }; }
function bigGraph(n, gid = 'big') {
  const r = rng(7);
  const words = (k) => Array.from({ length: k }, () => WORDS[Math.floor(r() * WORDS.length)]).join(' ');
  const ev = [];
  let seq = 0;
  const push = (ops) => { seq++; ev.push(`{:ev/id "b${seq}" :ev/ns "${gid}" :ev/actor "gen" :ev/ts ${1790000000000 + seq} :ev/type :change :ops [${ops}]}`); };
  push(`[:graph-set {:title "Большой граф"}]`);
  push(`[:node-set "src" {:title "Справочник" :is :source}]`);
  for (let i = 0; i < n; i++) {
    push(`[:node-set "t${i}" {:title "Тема ${i} ${words(2)}" :definition "${words(30)}" :kind :definition :source-id "src"}]`);
    if (i % 4 === 0)
      push(`[:node-set "o${i}" {:concept-id "t${i}" :kind :explanation :level :novice :locale "ru" :origin :authored :text "${words(60)}" :grounded-in "src" :is :object}]`);
  }
  for (let i = 1; i < n; i++) {
    const to = new Set(); while (to.size < Math.min(2, i)) to.add(Math.floor(r() * i));
    for (const j of to) push(`[:edge-set "t${i}" :requires "t${j}" {:provenance :authored}]`);
  }
  push(`[:node-set "course-big" {:title "Курс по большому графу" :targets [${[0, 1, 2, 3, 4, 5].map((i) => `["t${i}" :novice]`).join(' ')}] :locale "ru" :is :course}]`);
  return ev;
}
// Seed it into the app's IndexedDB (one record per event) and registry, in
// chunks — from a same-origin page that does not run the app (`base` + icon.svg):
// the app holds the database open, and a delete would wait for it forever
async function seedBigGraph(page, base, events, gid = 'big') {
  await page.goto(base.replace(/\/?$/, '/') + 'icon.svg');
  await page.evaluate(() => new Promise((ok) => { const q = indexedDB.deleteDatabase('loretile'); q.onsuccess = q.onerror = q.onblocked = ok; }));
  await page.evaluate((gid) => {
    localStorage.clear(); localStorage.setItem('loretile-locale', 'ru');
    for (const k of ['loretile-tour-done', 'loretile-chat-tour-done', 'loretile-learn-tour-done']) localStorage.setItem(k, '1');
    localStorage.setItem('loretile-graphs', `[{:id "${gid}" :title "Большой граф"}]`);
    localStorage.setItem('loretile-graph', `"${gid}"`);
  }, gid);
  for (let i = 0; i < events.length; i += 20000) {
    await page.evaluate((chunk, from, gid) => new Promise((ok, no) => {
      const r = indexedDB.open('loretile', 3);
      r.onupgradeneeded = () => { for (const s of ['logs', 'aside', 'events']) if (!r.result.objectStoreNames.contains(s)) r.result.createObjectStore(s); };
      r.onsuccess = () => { const t = r.result.transaction('events', 'readwrite'); const st = t.objectStore('events');
        chunk.forEach((e, k) => st.put(e, [gid, from + k])); t.oncomplete = () => { r.result.close(); ok(); }; t.onerror = () => no(t.error); };
    }), events.slice(i, i + 20000), i, gid);
  }
}
module.exports = { bigGraph, seedBigGraph };

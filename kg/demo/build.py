#!/usr/bin/env python3
"""Builds kg/demo/log.edn, log.ru.edn, log.de.edn, log.en.edn — the demo
knowledge graph the app offers on an empty map («Загрузить демо-граф»).
SQL for analysts: 17 topics, a prerequisite spine, the classic confusions,
two sources, objects and items dense enough that six topics are ready to
publish, one published course — the same graph shape, ids and timestamps
in Russian, German and English. Regenerate after editing:
python3 kg/demo/build.py

Python rather than hand-written EDN so ids/timestamps stay consistent and
the fixture stays readable. Every translatable string lives next to its
Russian original in a small {"ru":.., "en":.., "de":..} dict; build()
looks the locale up and blows up loudly if a translation is missing.
log.edn (ru) is what ships; log.ru.edn is byte-identical to it."""
import json
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from changes import as_change  # noqa: E402

T0 = 1756800000000  # 2025-09-02, one minute apart


def make_ev(events):
    def ev(type_, **kv):
        n = len(events)
        e = {"ev/id": f"demo-{n:03d}", "ev/ns": "demo", "ev/actor": "demo-author",
             "ev/ts": T0 + n * 60000, "ev/type": ":" + type_}
        as_change(e, type_, kv)
        events.append(e)
    return ev


def edn(v, indent=0):
    if isinstance(v, dict):
        return "{" + " ".join(f":{k} {edn(x)}" for k, x in v.items()) + "}"
    if isinstance(v, list):
        return "[" + " ".join(edn(x) for x in v) + "]"
    if isinstance(v, set):
        return "#{" + " ".join(edn(x) for x in sorted(v)) + "}"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, str):
        if v.startswith(":"):
            return v
        return json.dumps(v, ensure_ascii=False)
    raise TypeError(v)


def pick(translations, locale, what):
    assert translations is not None and locale in translations, \
        f"missing {locale!r} translation for {what}"
    return translations[locale]


# --- sources ----------------------------------------------------------------
SOURCES = [
    ("src-sql-book", "sql-analyst-book.pdf",
     {"ru": "Учебник SQL для аналитиков", "en": "SQL for Analysts Textbook",
      "de": "Lehrbuch SQL für Analysten"}),
    ("src-pg-docs", "https://www.postgresql.org/docs/16/queries.html",
     {"ru": "Документация PostgreSQL 16 — Queries",
      "en": "PostgreSQL 16 Documentation — Queries",
      "de": "PostgreSQL-16-Dokumentation — Queries"}),
]

# --- concepts -----------------------------------------------------------------
C = [
    ("tables", ":definition", "src-sql-book", {
        "ru": ("Таблицы, строки и столбцы",
               "Реляционная таблица: именованные столбцы с типами, строки без порядка. Запрос описывает, ЧТО нужно получить, а не как."),
        "en": ("Tables, rows and columns",
               "A relational table: named, typed columns and rows with no inherent order. A query describes WHAT you want, not how to get it."),
        "de": ("Tabellen, Zeilen und Spalten",
               "Eine relationale Tabelle: benannte, typisierte Spalten und Zeilen ohne feste Reihenfolge. Eine Abfrage beschreibt, WAS man haben will, nicht wie."),
    }),
    ("select", ":procedure", "src-sql-book", {
        "ru": ("SELECT: выборка столбцов",
               "SELECT перечисляет столбцы результата; FROM называет таблицу. Звёздочка берёт все столбцы — удобно в консоли, плохо в отчёте."),
        "en": ("SELECT: choosing columns",
               "SELECT lists the columns of the result; FROM names the table. The star grabs every column — handy at the console, bad in a report."),
        "de": ("SELECT: Spalten auswählen",
               "SELECT listet die Spalten des Ergebnisses auf; FROM nennt die Tabelle. Der Stern nimmt alle Spalten — praktisch in der Konsole, schlecht im Bericht."),
    }),
    ("operators", ":principle", "src-sql-book", {
        "ru": ("Операторы сравнения и логика AND/OR",
               "=, <>, <, >, BETWEEN, IN, LIKE и их сочетание через AND, OR, NOT. AND сильнее OR — без скобок условие читается не так, как написано."),
        "en": ("Comparison operators and AND/OR logic",
               "=, <>, <, >, BETWEEN, IN, LIKE, combined with AND, OR, NOT. AND binds tighter than OR — without parentheses a condition reads differently than it was written."),
        "de": ("Vergleichsoperatoren und AND/OR-Logik",
               "=, <>, <, >, BETWEEN, IN, LIKE, kombiniert mit AND, OR, NOT. AND bindet stärker als OR — ohne Klammern liest sich die Bedingung anders, als sie geschrieben wurde."),
    }),
    ("where", ":procedure", "src-sql-book", {
        "ru": ("Фильтрация WHERE",
               "WHERE оставляет строки, для которых условие истинно. Работает ДО группировки — агрегатов в нём быть не может."),
        "en": ("Filtering with WHERE",
               "WHERE keeps the rows for which the condition is true. It runs BEFORE grouping — aggregates can't appear in it."),
        "de": ("Filtern mit WHERE",
               "WHERE behält die Zeilen, für die die Bedingung wahr ist. Es läuft VOR der Gruppierung — Aggregate dürfen darin nicht vorkommen."),
    }),
    ("order-by", ":procedure", "src-sql-book", {
        "ru": ("Сортировка ORDER BY",
               "ORDER BY задаёт порядок строк результата; без него порядок не гарантирован. Несколько ключей, ASC/DESC для каждого."),
        "en": ("Sorting with ORDER BY",
               "ORDER BY sets the order of the result rows; without it the order isn't guaranteed. Multiple keys, ASC/DESC for each."),
        "de": ("Sortieren mit ORDER BY",
               "ORDER BY legt die Reihenfolge der Ergebniszeilen fest; ohne ORDER BY ist die Reihenfolge nicht garantiert. Mehrere Schlüssel, ASC/DESC für jeden."),
    }),
    ("limit", ":procedure", "src-pg-docs", {
        "ru": ("LIMIT и постраничный вывод",
               "LIMIT n OFFSET m отрезает окно из отсортированного результата. Без ORDER BY окно случайное."),
        "en": ("LIMIT and pagination",
               "LIMIT n OFFSET m cuts a window out of the sorted result. Without ORDER BY, the window is arbitrary."),
        "de": ("LIMIT und seitenweise Ausgabe",
               "LIMIT n OFFSET m schneidet ein Fenster aus dem sortierten Ergebnis. Ohne ORDER BY ist das Fenster beliebig."),
    }),
    ("distinct", ":procedure", "src-sql-book", {
        "ru": ("DISTINCT: уникальные значения",
               "DISTINCT убирает повторяющиеся строки результата целиком — по всем перечисленным столбцам, а не по первому."),
        "en": ("DISTINCT: unique values",
               "DISTINCT removes duplicate result rows as a whole — across all listed columns, not just the first one."),
        "de": ("DISTINCT: eindeutige Werte",
               "DISTINCT entfernt doppelte Ergebniszeilen als Ganzes — über alle aufgeführten Spalten, nicht nur die erste."),
    }),
    ("null", ":principle", "src-sql-book", {
        "ru": ("NULL и трёхзначная логика",
               "NULL — не значение, а его отсутствие. Любое сравнение с NULL даёт UNKNOWN; проверяют через IS NULL, а не = NULL."),
        "en": ("NULL and three-valued logic",
               "NULL isn't a value — it's the absence of one. Any comparison with NULL yields UNKNOWN; you test it with IS NULL, not = NULL."),
        "de": ("NULL und dreiwertige Logik",
               "NULL ist kein Wert, sondern dessen Fehlen. Jeder Vergleich mit NULL ergibt UNKNOWN; man prüft mit IS NULL, nicht mit = NULL."),
    }),
    ("agg", ":definition", "src-sql-book", {
        "ru": ("Агрегатные функции COUNT, SUM, AVG",
               "Агрегат сворачивает много строк в одно значение. COUNT(*) считает строки, COUNT(col) — не-NULL значения; AVG игнорирует NULL."),
        "en": ("Aggregate functions: COUNT, SUM, AVG",
               "An aggregate collapses many rows into one value. COUNT(*) counts rows, COUNT(col) counts non-NULL values; AVG ignores NULL."),
        "de": ("Aggregatfunktionen COUNT, SUM, AVG",
               "Ein Aggregat verdichtet viele Zeilen zu einem Wert. COUNT(*) zählt Zeilen, COUNT(col) zählt Nicht-NULL-Werte; AVG ignoriert NULL."),
    }),
    ("group-by", ":procedure", "src-sql-book", {
        "ru": ("GROUP BY: группировка",
               "GROUP BY складывает строки в корзины по значениям столбцов; каждая корзина даёт одну строку результата. Всё, что не в GROUP BY, должно быть под агрегатом."),
        "en": ("GROUP BY: grouping",
               "GROUP BY sorts rows into buckets by column values; each bucket produces one result row. Anything not in GROUP BY has to sit under an aggregate."),
        "de": ("GROUP BY: Gruppierung",
               "GROUP BY verteilt Zeilen anhand von Spaltenwerten auf Körbe; jeder Korb ergibt eine Ergebniszeile. Alles, was nicht in GROUP BY steht, muss unter einem Aggregat stehen."),
    }),
    ("having", ":procedure", "src-sql-book", {
        "ru": ("HAVING: фильтр по группам",
               "HAVING отбирает корзины после группировки — в нём можно использовать агрегаты. Условие по строкам ставят в WHERE, по группам — в HAVING."),
        "en": ("HAVING: filtering groups",
               "HAVING picks buckets after grouping — aggregates are allowed in it. A condition on rows goes in WHERE, a condition on groups goes in HAVING."),
        "de": ("HAVING: Filtern nach Gruppen",
               "HAVING wählt Körbe nach der Gruppierung aus — darin dürfen Aggregate stehen. Eine Bedingung für Zeilen gehört in WHERE, eine Bedingung für Gruppen in HAVING."),
    }),
    ("keys", ":definition", "src-sql-book", {
        "ru": ("Ключи и связи таблиц",
               "Первичный ключ однозначно определяет строку; внешний ключ ссылается на первичный ключ другой таблицы. По этим парам таблицы соединяют."),
        "en": ("Keys and table relationships",
               "A primary key identifies a row uniquely; a foreign key points to the primary key of another table. Tables are joined on these pairs."),
        "de": ("Schlüssel und Tabellenbeziehungen",
               "Ein Primärschlüssel identifiziert eine Zeile eindeutig; ein Fremdschlüssel verweist auf den Primärschlüssel einer anderen Tabelle. Über diese Paare werden Tabellen verbunden."),
    }),
    ("join", ":procedure", "src-sql-book", {
        "ru": ("JOIN: соединение таблиц",
               "INNER JOIN склеивает строки двух таблиц по условию ON и оставляет только пары, где условие выполнилось. Без условия — декартово произведение."),
        "en": ("JOIN: joining tables",
               "INNER JOIN glues rows of two tables together on the ON condition and keeps only the pairs where it holds. Without a condition you get a Cartesian product."),
        "de": ("JOIN: Tabellen verbinden",
               "INNER JOIN verbindet Zeilen zweier Tabellen über die ON-Bedingung und behält nur die Paare, für die sie zutrifft. Ohne Bedingung entsteht ein kartesisches Produkt."),
    }),
    ("left-join", ":procedure", "src-pg-docs", {
        "ru": ("LEFT JOIN и пропущенные строки",
               "LEFT JOIN сохраняет все строки левой таблицы; где пары нет, столбцы правой заполняются NULL. Фильтр по правой таблице в WHERE превращает LEFT в INNER."),
        "en": ("LEFT JOIN and missing rows",
               "LEFT JOIN keeps every row of the left table; where there's no match, the right table's columns come back NULL. A filter on the right table in WHERE turns LEFT into INNER."),
        "de": ("LEFT JOIN und fehlende Zeilen",
               "LEFT JOIN behält jede Zeile der linken Tabelle; wo es keine Übereinstimmung gibt, werden die Spalten der rechten Tabelle NULL. Ein Filter auf die rechte Tabelle in WHERE macht aus LEFT ein INNER."),
    }),
    ("subquery", ":procedure", "src-sql-book", {
        "ru": ("Подзапросы",
               "Запрос внутри запроса: в WHERE (IN, EXISTS), в FROM (производная таблица) или в SELECT (скалярный). Коррелированный подзапрос выполняется для каждой строки."),
        "en": ("Subqueries",
               "A query inside a query: in WHERE (IN, EXISTS), in FROM (a derived table) or in SELECT (a scalar subquery). A correlated subquery runs once per outer row."),
        "de": ("Unterabfragen",
               "Eine Abfrage innerhalb einer Abfrage: in WHERE (IN, EXISTS), in FROM (abgeleitete Tabelle) oder in SELECT (skalar). Eine korrelierte Unterabfrage läuft für jede äußere Zeile einmal."),
    }),
    ("cte", ":procedure", "src-pg-docs", {
        "ru": ("CTE: конструкция WITH",
               "WITH даёт подзапросу имя и выносит его наверх; запрос читается сверху вниз, как рецепт. Рекурсивные CTE обходят иерархии."),
        "en": ("CTEs: the WITH clause",
               "WITH names a subquery and pulls it up to the top; the query then reads top to bottom, like a recipe. Recursive CTEs walk hierarchies."),
        "de": ("CTEs: die WITH-Klausel",
               "WITH gibt einer Unterabfrage einen Namen und zieht sie nach oben; die Abfrage liest sich dann von oben nach unten wie ein Rezept. Rekursive CTEs durchlaufen Hierarchien."),
    }),
    ("window", ":principle", "src-pg-docs", {
        "ru": ("Оконные функции",
               "Оконная функция считает агрегат по «окну» строк, не сворачивая их: ROW_NUMBER, RANK, SUM OVER (PARTITION BY … ORDER BY …). Строк остаётся столько же."),
        "en": ("Window functions",
               "A window function computes an aggregate over a \"window\" of rows without collapsing them: ROW_NUMBER, RANK, SUM OVER (PARTITION BY … ORDER BY …). The row count stays the same."),
        "de": ("Fensterfunktionen",
               "Eine Fensterfunktion berechnet ein Aggregat über ein „Fenster“ von Zeilen, ohne sie zu verdichten: ROW_NUMBER, RANK, SUM OVER (PARTITION BY … ORDER BY …). Die Zeilenzahl bleibt gleich."),
    }),
]

# --- edges: the prerequisite spine (from REQUIRES to) ----------------------
REQ = [
    ("select", "tables"), ("where", "select"), ("where", "operators"), ("order-by", "select"),
    ("limit", "order-by"), ("distinct", "select"), ("null", "operators"), ("agg", "select"),
    ("group-by", "agg"), ("group-by", "where"), ("having", "group-by"), ("join", "keys"),
    ("join", "select"), ("left-join", "join"), ("left-join", "null"), ("subquery", "where"),
    ("cte", "subquery"), ("window", "agg"), ("window", "order-by"),
]
# the classic confusions and a sibling pointer (from, to, type, justification-or-None)
EDGES_EXTRA = [
    ("having", "where", ":is-confused-with", {
        "ru": "Оба фильтруют, но WHERE — строки до группировки, HAVING — группы после.",
        "en": "Both filter, but WHERE works on rows before grouping and HAVING on groups after.",
        "de": "Beide filtern, aber WHERE wirkt auf Zeilen vor der Gruppierung, HAVING auf Gruppen danach.",
    }),
    ("left-join", "join", ":is-confused-with", {
        "ru": "Фильтр по правой таблице в WHERE незаметно превращает LEFT JOIN в INNER.",
        "en": "A filter on the right table in WHERE quietly turns a LEFT JOIN into an INNER JOIN.",
        "de": "Ein Filter auf die rechte Tabelle in WHERE macht aus einem LEFT JOIN unbemerkt ein INNER JOIN.",
    }),
    ("cte", "subquery", ":see-also", None),
    ("window", "group-by", ":see-also", {
        "ru": "Одна и та же сумма: GROUP BY сворачивает строки, OVER — оставляет.",
        "en": "The same sum, two ways: GROUP BY collapses the rows, OVER leaves them in place.",
        "de": "Dieselbe Summe, zwei Wege: GROUP BY verdichtet die Zeilen, OVER lässt sie stehen.",
    }),
]

# --- learning objects --------------------------------------------------------
OBJ = [
    ("select", ":explanation", ":novice", "p12", {
        "ru": "Запрос читают с FROM: сначала откуда, потом что. SELECT name, city FROM customers — две колонки из таблицы клиентов.",
        "en": "Read a query starting with FROM: first where the data comes from, then what to take. SELECT name, city FROM customers — two columns from the customers table.",
        "de": "Eine Abfrage liest man ab FROM: zuerst woher, dann was. SELECT name, city FROM customers — zwei Spalten aus der Tabelle customers.",
    }),
    ("select", ":worked-example", ":apprentice", "p14", {
        "ru": "Задача: список email клиентов из Москвы. Решение: SELECT email FROM customers WHERE city = 'Москва'. Ошибка новичка: SELECT * и фильтр в Excel.",
        "en": "Task: a list of email addresses of customers from Moscow. Solution: SELECT email FROM customers WHERE city = 'Moscow'. Beginner's mistake: SELECT * and filtering in Excel afterward.",
        "de": "Aufgabe: Liste der E-Mail-Adressen von Kunden aus Moskau. Lösung: SELECT email FROM customers WHERE city = 'Moskau'. Anfängerfehler: SELECT * und danach in Excel filtern.",
    }),
    ("where", ":explanation", ":apprentice", "p21", {
        "ru": "WHERE — сито по строкам. Условие вычисляется для каждой строки; строка проходит, если условие истинно. NULL в условии — строка не проходит.",
        "en": "WHERE is a sieve over rows. The condition is evaluated for every row; a row passes if the condition is true. NULL in the condition — the row doesn't pass.",
        "de": "WHERE ist ein Sieb für Zeilen. Die Bedingung wird für jede Zeile ausgewertet; eine Zeile kommt durch, wenn die Bedingung wahr ist. NULL in der Bedingung — die Zeile kommt nicht durch.",
    }),
    ("where", ":worked-example", ":apprentice", "p23", {
        "ru": "Заказы за март дороже 1000: WHERE order_date BETWEEN '2025-03-01' AND '2025-03-31' AND amount > 1000. Скобки не нужны — только AND.",
        "en": "Orders from March over 1000: WHERE order_date BETWEEN '2025-03-01' AND '2025-03-31' AND amount > 1000. No parentheses needed — it's just AND.",
        "de": "Bestellungen aus dem März über 1000: WHERE order_date BETWEEN '2025-03-01' AND '2025-03-31' AND amount > 1000. Klammern sind hier nicht nötig — nur AND.",
    }),
    ("operators", ":explanation", ":apprentice", "p18", {
        "ru": "AND связывает сильнее OR: a OR b AND c читается как a OR (b AND c). Ставьте скобки всегда, когда есть OR.",
        "en": "AND binds tighter than OR: a OR b AND c reads as a OR (b AND c). Always add parentheses when OR is involved.",
        "de": "AND bindet stärker als OR: a OR b AND c liest sich als a OR (b AND c). Setzen Sie immer Klammern, sobald OR im Spiel ist.",
    }),
    ("order-by", ":explanation", ":novice", "p30", {
        "ru": "ORDER BY amount DESC, created_at — сначала дорогие, при равной сумме — более ранние. Сортировка — последний шаг перед LIMIT.",
        "en": "ORDER BY amount DESC, created_at — the priciest first, and for equal amounts, the earlier ones. Sorting is the last step before LIMIT.",
        "de": "ORDER BY amount DESC, created_at — zuerst die teuersten, bei gleichem Betrag die früheren. Sortieren ist der letzte Schritt vor LIMIT.",
    }),
    ("null", ":explanation", ":apprentice", "p26", {
        "ru": "WHERE discount = NULL не вернёт ничего и никогда. Правильно: WHERE discount IS NULL. COUNT(discount) не считает пустые.",
        "en": "WHERE discount = NULL never returns anything, ever. Correct form: WHERE discount IS NULL. COUNT(discount) doesn't count the empty ones.",
        "de": "WHERE discount = NULL liefert nie irgendein Ergebnis. Richtig: WHERE discount IS NULL. COUNT(discount) zählt die leeren nicht mit.",
    }),
    ("agg", ":explanation", ":apprentice", "p34", {
        "ru": "COUNT(*) — сколько строк. SUM(amount) — сумма. AVG(amount) — среднее по не-NULL. Без GROUP BY агрегат сворачивает всю таблицу в одну строку.",
        "en": "COUNT(*) — how many rows. SUM(amount) — the total. AVG(amount) — the average over non-NULL values. Without GROUP BY, an aggregate collapses the whole table into one row.",
        "de": "COUNT(*) — wie viele Zeilen. SUM(amount) — die Summe. AVG(amount) — der Durchschnitt über die Nicht-NULL-Werte. Ohne GROUP BY verdichtet ein Aggregat die ganze Tabelle zu einer Zeile.",
    }),
    ("group-by", ":explanation", ":apprentice", "p36", {
        "ru": "GROUP BY city складывает заказы по городам в корзины; SELECT city, SUM(amount) даёт по строке на город. Столбец без агрегата и не в GROUP BY — ошибка.",
        "en": "GROUP BY city sorts orders into buckets by city; SELECT city, SUM(amount) then gives one row per city. A column that's neither aggregated nor in GROUP BY is an error.",
        "de": "GROUP BY city verteilt Bestellungen nach Stadt auf Körbe; SELECT city, SUM(amount) ergibt dann eine Zeile pro Stadt. Eine Spalte, die weder aggregiert noch in GROUP BY steht, ist ein Fehler.",
    }),
    ("group-by", ":worked-example", ":apprentice", "p38", {
        "ru": "Выручка по месяцам: SELECT date_trunc('month', order_date) AS m, SUM(amount) FROM orders GROUP BY m ORDER BY m.",
        "en": "Revenue by month: SELECT date_trunc('month', order_date) AS m, SUM(amount) FROM orders GROUP BY m ORDER BY m.",
        "de": "Umsatz nach Monat: SELECT date_trunc('month', order_date) AS m, SUM(amount) FROM orders GROUP BY m ORDER BY m.",
    }),
    ("group-by", ":diagram", ":apprentice", "p37", {
        "ru": "Схема: 9 строк заказов → три корзины (Москва, Казань, Пермь) → три строки с суммами. Стрелки показывают, куда упала каждая строка.",
        "en": "Diagram: 9 order rows → three buckets (Moscow, Kazan, Perm) → three rows with totals. Arrows show which bucket each row landed in.",
        "de": "Diagramm: 9 Bestellzeilen → drei Körbe (Moskau, Kasan, Perm) → drei Zeilen mit Summen. Pfeile zeigen, in welchen Korb jede Zeile fällt.",
    }),
    ("having", ":explanation", ":apprentice", "p40", {
        "ru": "HAVING COUNT(*) > 10 оставляет только корзины, где больше десяти заказов. То же условие в WHERE — синтаксическая ошибка: строк ещё не сгруппировали.",
        "en": "HAVING COUNT(*) > 10 keeps only the buckets with more than ten orders. The same condition in WHERE is a syntax error: the rows haven't been grouped yet.",
        "de": "HAVING COUNT(*) > 10 behält nur die Körbe mit mehr als zehn Bestellungen. Dieselbe Bedingung in WHERE ist ein Syntaxfehler: Die Zeilen sind noch nicht gruppiert.",
    }),
    ("having", ":worked-example", ":apprentice", "p41", {
        "ru": "Города с выручкой выше миллиона за 2025 год: WHERE по дате (строки), GROUP BY city, HAVING SUM(amount) > 1000000 (группы).",
        "en": "Cities with revenue over a million in 2025: WHERE on the date (rows), GROUP BY city, HAVING SUM(amount) > 1000000 (groups).",
        "de": "Städte mit einem Umsatz über einer Million im Jahr 2025: WHERE auf das Datum (Zeilen), GROUP BY city, HAVING SUM(amount) > 1000000 (Gruppen).",
    }),
    ("keys", ":explanation", ":novice", "p46", {
        "ru": "orders.customer_id → customers.id: у заказа записан номер клиента. По этой паре и соединяют. Ключ — не «важный столбец», а обещание уникальности.",
        "en": "orders.customer_id → customers.id: an order carries its customer's id. Tables get joined on exactly this kind of pair. A key isn't \"an important column\" — it's a promise of uniqueness.",
        "de": "orders.customer_id → customers.id: Eine Bestellung trägt die id ihres Kunden. Genau über solche Paare werden Tabellen verbunden. Ein Schlüssel ist keine „wichtige Spalte“, sondern ein Eindeutigkeitsversprechen.",
    }),
    ("join", ":explanation", ":apprentice", "p48", {
        "ru": "FROM orders JOIN customers ON orders.customer_id = customers.id — каждому заказу приклеивается его клиент. Заказы без клиента и клиенты без заказов выпадают.",
        "en": "FROM orders JOIN customers ON orders.customer_id = customers.id — each order gets its customer attached. Orders without a customer and customers without orders drop out.",
        "de": "FROM orders JOIN customers ON orders.customer_id = customers.id — jeder Bestellung wird ihr Kunde angehängt. Bestellungen ohne Kunden und Kunden ohne Bestellungen fallen weg.",
    }),
    ("join", ":worked-example", ":apprentice", "p50", {
        "ru": "Имя клиента рядом с суммой заказа: SELECT c.name, o.amount FROM orders o JOIN customers c ON o.customer_id = c.id.",
        "en": "Customer name next to the order amount: SELECT c.name, o.amount FROM orders o JOIN customers c ON o.customer_id = c.id.",
        "de": "Kundenname neben dem Bestellbetrag: SELECT c.name, o.amount FROM orders o JOIN customers c ON o.customer_id = c.id.",
    }),
    ("join", ":diagram", ":apprentice", "p49", {
        "ru": "Две таблицы как два столбика карточек, линии между совпадающими id; результат — только соединённые пары.",
        "en": "Two tables as two columns of cards, lines between matching ids; the result is only the joined pairs.",
        "de": "Zwei Tabellen als zwei Spalten von Karten, Linien zwischen übereinstimmenden ids; das Ergebnis sind nur die verbundenen Paare.",
    }),
    ("left-join", ":explanation", ":apprentice", "p52", {
        "ru": "Клиенты без заказов: LEFT JOIN orders … WHERE orders.id IS NULL. Если написать WHERE orders.amount > 0 — пустые строки отсеются, и LEFT станет INNER.",
        "en": "Customers with no orders: LEFT JOIN orders … WHERE orders.id IS NULL. Write WHERE orders.amount > 0 instead, and the empty rows get filtered out — LEFT quietly becomes INNER.",
        "de": "Kunden ohne Bestellungen: LEFT JOIN orders … WHERE orders.id IS NULL. Schreibt man stattdessen WHERE orders.amount > 0, werden die leeren Zeilen herausgefiltert — aus LEFT wird unbemerkt INNER.",
    }),
    ("subquery", ":explanation", ":apprentice", "p56", {
        "ru": "WHERE customer_id IN (SELECT id FROM customers WHERE city = 'Казань') — внутренний запрос отдаёт список, внешний по нему фильтрует.",
        "en": "WHERE customer_id IN (SELECT id FROM customers WHERE city = 'Kazan') — the inner query hands back a list, and the outer query filters against it.",
        "de": "WHERE customer_id IN (SELECT id FROM customers WHERE city = 'Kasan') — die innere Abfrage liefert eine Liste, die äußere filtert damit.",
    }),
    ("cte", ":explanation", ":apprentice", "p60", {
        "ru": "WITH monthly AS (…) SELECT … FROM monthly — сначала именуем промежуточный результат, потом используем как таблицу. Длинный запрос перестаёт быть матрёшкой.",
        "en": "WITH monthly AS (…) SELECT … FROM monthly — first you name the intermediate result, then use it like a table. A long query stops being a matryoshka doll.",
        "de": "WITH monthly AS (…) SELECT … FROM monthly — zuerst benennt man das Zwischenergebnis, dann nutzt man es wie eine Tabelle. Eine lange Abfrage ist dann keine Matroschka mehr.",
    }),
    ("window", ":explanation", ":professional", "p66", {
        "ru": "SUM(amount) OVER (PARTITION BY city ORDER BY order_date) — накопительный итог по городу, при этом каждая строка заказа остаётся на месте.",
        "en": "SUM(amount) OVER (PARTITION BY city ORDER BY order_date) — a running total per city, while every order row stays right where it was.",
        "de": "SUM(amount) OVER (PARTITION BY city ORDER BY order_date) — eine laufende Summe pro Stadt, wobei jede Bestellzeile an ihrem Platz bleibt.",
    }),
]

# --- items: [concept, kind, {locale: spec}] ----------------------------------
ITEMS = [
    ("select", ":single-choice", {
        "ru": {"stem": "Какой запрос вернёт только имена клиентов?",
               "options": ["SELECT * FROM customers", "SELECT name FROM customers", "SELECT customers FROM name"], "key": 1},
        "en": {"stem": "Which query returns only customer names?",
               "options": ["SELECT * FROM customers", "SELECT name FROM customers", "SELECT customers FROM name"], "key": 1},
        "de": {"stem": "Welche Abfrage liefert nur die Namen der Kunden?",
               "options": ["SELECT * FROM customers", "SELECT name FROM customers", "SELECT customers FROM name"], "key": 1},
    }),
    ("select", ":cloze", {
        "ru": {"text": "Таблицу, из которой берутся строки, называют после слова ___", "key": "FROM"},
        "en": {"text": "The table rows are taken from is named after the keyword ___", "key": "FROM"},
        "de": {"text": "Die Tabelle, aus der die Zeilen stammen, wird nach dem Schlüsselwort ___ genannt", "key": "FROM"},
    }),
    ("select", ":single-choice", {
        "ru": {"stem": "Что вернёт SELECT name, city FROM customers?",
               "options": ["Одну колонку", "Две колонки на каждую строку", "Только уникальные города"], "key": 1},
        "en": {"stem": "What does SELECT name, city FROM customers return?",
               "options": ["One column", "Two columns for every row", "Only unique cities"], "key": 1},
        "de": {"stem": "Was liefert SELECT name, city FROM customers?",
               "options": ["Eine Spalte", "Zwei Spalten für jede Zeile", "Nur eindeutige Städte"], "key": 1},
    }),
    ("where", ":single-choice", {
        "ru": {"stem": "Заказы дороже 1000 за март. Где условие по дате?",
               "options": ["В SELECT", "В WHERE вместе с суммой через AND", "В ORDER BY"], "key": 1},
        "en": {"stem": "Orders over 1000 from March. Where does the date condition go?",
               "options": ["In SELECT", "In WHERE, combined with the amount via AND", "In ORDER BY"], "key": 1},
        "de": {"stem": "Bestellungen über 1000 aus dem März. Wo steht die Bedingung fürs Datum?",
               "options": ["In SELECT", "In WHERE, zusammen mit dem Betrag über AND", "In ORDER BY"], "key": 1},
    }),
    ("where", ":cloze", {
        "ru": {"text": "Строка попадает в результат, если условие в WHERE ___", "key": "истинно"},
        "en": {"text": "A row makes it into the result if the WHERE condition is ___", "key": "true"},
        "de": {"text": "Eine Zeile landet im Ergebnis, wenn die WHERE-Bedingung ___ ist", "key": "wahr"},
    }),
    ("where", ":single-choice", {
        "ru": {"stem": "WHERE amount > 1000 OR city = 'Пермь' AND amount < 10 — что отберётся?",
               "options": ["Дороже 1000, либо дешёвые пермские", "Только дорогие пермские", "Ошибка синтаксиса"], "key": 0},
        "en": {"stem": "WHERE amount > 1000 OR city = 'Perm' AND amount < 10 — what gets selected?",
               "options": ["Anything over 1000, plus cheap orders from Perm", "Only expensive orders from Perm", "A syntax error"], "key": 0},
        "de": {"stem": "WHERE amount > 1000 OR city = 'Perm' AND amount < 10 — was wird ausgewählt?",
               "options": ["Alles über 1000, plus billige Bestellungen aus Perm", "Nur teure Bestellungen aus Perm", "Ein Syntaxfehler"], "key": 0},
    }),
    ("operators", ":single-choice", {
        "ru": {"stem": "Как читается a OR b AND c?",
               "options": ["(a OR b) AND c", "a OR (b AND c)", "Зависит от СУБД"], "key": 1},
        "en": {"stem": "How does a OR b AND c parse?",
               "options": ["(a OR b) AND c", "a OR (b AND c)", "Depends on the database"], "key": 1},
        "de": {"stem": "Wie wird a OR b AND c ausgewertet?",
               "options": ["(a OR b) AND c", "a OR (b AND c)", "Kommt auf das Datenbanksystem an"], "key": 1},
    }),
    ("operators", ":cloze", {
        "ru": {"text": "Проверить вхождение в список значений можно оператором ___", "key": "IN"},
        "en": {"text": "Membership in a list of values is checked with the operator ___", "key": "IN"},
        "de": {"text": "Die Zugehörigkeit zu einer Werteliste prüft man mit dem Operator ___", "key": "IN"},
    }),
    ("null", ":single-choice", {
        "ru": {"stem": "Сколько строк вернёт WHERE discount = NULL?",
               "options": ["Все с пустой скидкой", "Ни одной", "Ошибка"], "key": 1},
        "en": {"stem": "How many rows does WHERE discount = NULL return?",
               "options": ["All the ones with an empty discount", "None", "An error"], "key": 1},
        "de": {"stem": "Wie viele Zeilen liefert WHERE discount = NULL?",
               "options": ["Alle mit leerem Rabatt", "Keine", "Einen Fehler"], "key": 1},
    }),
    ("null", ":cloze", {
        "ru": {"text": "Отсутствие значения проверяют через IS ___", "key": "NULL"},
        "en": {"text": "The absence of a value is checked with IS ___", "key": "NULL"},
        "de": {"text": "Das Fehlen eines Werts prüft man mit IS ___", "key": "NULL"},
    }),
    ("null", ":single-choice", {
        "ru": {"stem": "COUNT(discount) для 10 строк, где 3 скидки пустые, даст…",
               "options": ["10", "7", "3"], "key": 1},
        "en": {"stem": "COUNT(discount) over 10 rows where 3 discounts are empty gives…",
               "options": ["10", "7", "3"], "key": 1},
        "de": {"stem": "COUNT(discount) über 10 Zeilen, bei denen 3 Rabatte leer sind, ergibt…",
               "options": ["10", "7", "3"], "key": 1},
    }),
    ("agg", ":single-choice", {
        "ru": {"stem": "Чем COUNT(*) отличается от COUNT(col)?",
               "options": ["Ничем", "COUNT(col) не считает NULL", "COUNT(*) быстрее только в MySQL"], "key": 1},
        "en": {"stem": "How does COUNT(*) differ from COUNT(col)?",
               "options": ["It doesn't", "COUNT(col) doesn't count NULL", "COUNT(*) is only faster in MySQL"], "key": 1},
        "de": {"stem": "Worin unterscheidet sich COUNT(*) von COUNT(col)?",
               "options": ["Gar nicht", "COUNT(col) zählt NULL nicht mit", "COUNT(*) ist nur in MySQL schneller"], "key": 1},
    }),
    ("agg", ":cloze", {
        "ru": {"text": "Среднее по столбцу считает функция ___", "key": "AVG"},
        "en": {"text": "The average over a column is computed by the function ___", "key": "AVG"},
        "de": {"text": "Den Durchschnitt einer Spalte berechnet die Funktion ___", "key": "AVG"},
    }),
    ("agg", ":single-choice", {
        "ru": {"stem": "SELECT SUM(amount) FROM orders без GROUP BY вернёт…",
               "options": ["Строку на каждый заказ", "Одну строку", "Ошибку"], "key": 1},
        "en": {"stem": "SELECT SUM(amount) FROM orders without GROUP BY returns…",
               "options": ["A row for every order", "One row", "An error"], "key": 1},
        "de": {"stem": "SELECT SUM(amount) FROM orders ohne GROUP BY liefert…",
               "options": ["Eine Zeile pro Bestellung", "Eine Zeile", "Einen Fehler"], "key": 1},
    }),
    ("group-by", ":single-choice", {
        "ru": {"stem": "Что вернёт SELECT city, SUM(amount) FROM orders GROUP BY city?",
               "options": ["По строке на заказ", "По строке на город", "Одну строку"], "key": 1},
        "en": {"stem": "What does SELECT city, SUM(amount) FROM orders GROUP BY city return?",
               "options": ["A row per order", "A row per city", "A single row"], "key": 1},
        "de": {"stem": "Was liefert SELECT city, SUM(amount) FROM orders GROUP BY city?",
               "options": ["Eine Zeile pro Bestellung", "Eine Zeile pro Stadt", "Eine einzige Zeile"], "key": 1},
    }),
    ("group-by", ":cloze", {
        "ru": {"text": "Столбец без агрегата обязан быть перечислен в ___", "key": "GROUP BY"},
        "en": {"text": "A column with no aggregate has to be listed in ___", "key": "GROUP BY"},
        "de": {"text": "Eine Spalte ohne Aggregat muss in ___ aufgeführt sein", "key": "GROUP BY"},
    }),
    ("group-by", ":single-choice", {
        "ru": {"stem": "SELECT city, name, SUM(amount) … GROUP BY city — что не так?",
               "options": ["Ничего", "name не в GROUP BY и не под агрегатом", "SUM нельзя с GROUP BY"], "key": 1},
        "en": {"stem": "SELECT city, name, SUM(amount) … GROUP BY city — what's wrong?",
               "options": ["Nothing", "name is neither in GROUP BY nor under an aggregate", "SUM can't be used with GROUP BY"], "key": 1},
        "de": {"stem": "SELECT city, name, SUM(amount) … GROUP BY city — was stimmt hier nicht?",
               "options": ["Nichts", "name steht weder in GROUP BY noch unter einem Aggregat", "SUM ist mit GROUP BY nicht erlaubt"], "key": 1},
    }),
    ("having", ":single-choice", {
        "ru": {"stem": "Города с более чем 10 заказами. Где условие?",
               "options": ["WHERE COUNT(*) > 10", "HAVING COUNT(*) > 10", "ORDER BY COUNT(*) > 10"], "key": 1},
        "en": {"stem": "Cities with more than 10 orders. Where does the condition go?",
               "options": ["WHERE COUNT(*) > 10", "HAVING COUNT(*) > 10", "ORDER BY COUNT(*) > 10"], "key": 1},
        "de": {"stem": "Städte mit mehr als 10 Bestellungen. Wo steht die Bedingung?",
               "options": ["WHERE COUNT(*) > 10", "HAVING COUNT(*) > 10", "ORDER BY COUNT(*) > 10"], "key": 1},
    }),
    ("having", ":cloze", {
        "ru": {"text": "Условие по группам ставят после слова ___", "key": "HAVING"},
        "en": {"text": "A condition on groups goes after the keyword ___", "key": "HAVING"},
        "de": {"text": "Eine Bedingung für Gruppen steht nach dem Schlüsselwort ___", "key": "HAVING"},
    }),
    ("having", ":single-choice", {
        "ru": {"stem": "Почему WHERE SUM(amount) > 100 — ошибка?",
               "options": ["SUM нельзя в запросах", "Строки ещё не сгруппированы", "Нужны скобки"], "key": 1},
        "en": {"stem": "Why is WHERE SUM(amount) > 100 an error?",
               "options": ["SUM isn't allowed in queries", "The rows haven't been grouped yet", "It needs parentheses"], "key": 1},
        "de": {"stem": "Warum ist WHERE SUM(amount) > 100 ein Fehler?",
               "options": ["SUM ist in Abfragen nicht erlaubt", "Die Zeilen sind noch nicht gruppiert", "Es fehlen Klammern"], "key": 1},
    }),
    ("keys", ":single-choice", {
        "ru": {"stem": "orders.customer_id ссылается на customers.id. Что это?",
               "options": ["Первичный ключ orders", "Внешний ключ", "Индекс"], "key": 1},
        "en": {"stem": "orders.customer_id references customers.id. What is this?",
               "options": ["A primary key of orders", "A foreign key", "An index"], "key": 1},
        "de": {"stem": "orders.customer_id verweist auf customers.id. Was ist das?",
               "options": ["Ein Primärschlüssel von orders", "Ein Fremdschlüssel", "Ein Index"], "key": 1},
    }),
    ("join", ":single-choice", {
        "ru": {"stem": "INNER JOIN без совпадений для клиента — клиент…",
               "options": ["Появится с NULL", "Не появится", "Появится дважды"], "key": 1},
        "en": {"stem": "INNER JOIN with no match for a customer — the customer…",
               "options": ["Shows up with NULL", "Doesn't show up", "Shows up twice"], "key": 1},
        "de": {"stem": "INNER JOIN ohne Treffer für einen Kunden — der Kunde…",
               "options": ["Erscheint mit NULL", "Erscheint nicht", "Erscheint doppelt"], "key": 1},
    }),
    ("join", ":cloze", {
        "ru": {"text": "Условие соединения пишут после слова ___", "key": "ON"},
        "en": {"text": "The join condition is written after the keyword ___", "key": "ON"},
        "de": {"text": "Die Verknüpfungsbedingung schreibt man nach dem Schlüsselwort ___", "key": "ON"},
    }),
    ("join", ":single-choice", {
        "ru": {"stem": "JOIN без ON вернёт…",
               "options": ["Ошибку", "Декартово произведение", "Только первую пару"], "key": 1},
        "en": {"stem": "JOIN without ON returns…",
               "options": ["An error", "A Cartesian product", "Only the first pair"], "key": 1},
        "de": {"stem": "JOIN ohne ON liefert…",
               "options": ["Einen Fehler", "Ein kartesisches Produkt", "Nur das erste Paar"], "key": 1},
    }),
    ("left-join", ":single-choice", {
        "ru": {"stem": "Клиенты без заказов: LEFT JOIN orders … и условие?",
               "options": ["WHERE orders.id IS NULL", "WHERE orders.amount = 0", "HAVING orders.id IS NULL"], "key": 0},
        "en": {"stem": "Customers with no orders: LEFT JOIN orders … and which condition?",
               "options": ["WHERE orders.id IS NULL", "WHERE orders.amount = 0", "HAVING orders.id IS NULL"], "key": 0},
        "de": {"stem": "Kunden ohne Bestellungen: LEFT JOIN orders … und welche Bedingung?",
               "options": ["WHERE orders.id IS NULL", "WHERE orders.amount = 0", "HAVING orders.id IS NULL"], "key": 0},
    }),
    ("left-join", ":single-choice", {
        "ru": {"stem": "LEFT JOIN orders … WHERE orders.amount > 0 — что получится?",
               "options": ["Все клиенты", "Только клиенты с заказами", "Ошибка"], "key": 1},
        "en": {"stem": "LEFT JOIN orders … WHERE orders.amount > 0 — what do you get?",
               "options": ["Every customer", "Only customers with orders", "An error"], "key": 1},
        "de": {"stem": "LEFT JOIN orders … WHERE orders.amount > 0 — was kommt dabei heraus?",
               "options": ["Alle Kunden", "Nur Kunden mit Bestellungen", "Ein Fehler"], "key": 1},
    }),
    ("subquery", ":single-choice", {
        "ru": {"stem": "WHERE id IN (SELECT customer_id FROM orders) отбирает…",
               "options": ["Клиентов с заказами", "Заказы", "Ничего — так нельзя"], "key": 0},
        "en": {"stem": "WHERE id IN (SELECT customer_id FROM orders) selects…",
               "options": ["Customers who have orders", "Orders", "Nothing — this isn't allowed"], "key": 0},
        "de": {"stem": "WHERE id IN (SELECT customer_id FROM orders) wählt aus…",
               "options": ["Kunden mit Bestellungen", "Bestellungen", "Nichts — das ist nicht erlaubt"], "key": 0},
    }),
    ("cte", ":cloze", {
        "ru": {"text": "Именованный промежуточный результат объявляют словом ___", "key": "WITH"},
        "en": {"text": "A named intermediate result is declared with the keyword ___", "key": "WITH"},
        "de": {"text": "Ein benanntes Zwischenergebnis deklariert man mit dem Schlüsselwort ___", "key": "WITH"},
    }),
    ("window", ":single-choice", {
        "ru": {"stem": "SUM(amount) OVER (PARTITION BY city) — сколько строк в результате?",
               "options": ["По одной на город", "Столько же, сколько заказов", "Одна"], "key": 1},
        "en": {"stem": "SUM(amount) OVER (PARTITION BY city) — how many rows in the result?",
               "options": ["One per city", "As many as there are orders", "One"], "key": 1},
        "de": {"stem": "SUM(amount) OVER (PARTITION BY city) — wie viele Zeilen im Ergebnis?",
               "options": ["Eine pro Stadt", "So viele wie Bestellungen", "Eine"], "key": 1},
    }),
]

# --- an open question the intake left for the expert ------------------------
QUESTION = ("q-limit-1", "limit", {
    "ru": "Нужен ли аналитикам OFFSET, или на практике всегда keyset-пагинация? Уровень темы зависит от ответа.",
    "en": "Do analysts actually need OFFSET, or is keyset pagination always the practical choice? The topic's level depends on the answer.",
    "de": "Brauchen Analysten OFFSET wirklich, oder ist in der Praxis immer Keyset-Pagination die bessere Wahl? Das Niveau des Themas hängt von der Antwort ab.",
})

# --- the course ---------------------------------------------------------------
# --- extra items so the SECOND course's targets are publishable ---------------
# (cross-course credit only shows when two courses share a graph, and a target
#  must carry 1 object + 3 items at its level to be examinable)
EXTRA_ITEMS = [
    ("order-by", ":single-choice", {
        "ru": {"stem": "Без ORDER BY порядок строк в результате…",
               "options": ["Всегда как в таблице", "Не гарантирован", "Всегда по первичному ключу"], "key": 1},
        "en": {"stem": "Without ORDER BY, the row order in a result is…",
               "options": ["Always the table's order", "Not guaranteed", "Always by primary key"], "key": 1},
        "de": {"stem": "Ohne ORDER BY ist die Zeilenreihenfolge im Ergebnis…",
               "options": ["Immer die der Tabelle", "Nicht garantiert", "Immer nach Primärschlüssel"], "key": 1}}),
    ("order-by", ":single-choice", {
        "ru": {"stem": "ORDER BY amount DESC, created_at — что это значит?",
               "options": ["Сначала дорогие, при равной сумме — более ранние",
                           "Сначала ранние, потом дорогие", "Сортировка по сумме плюс дате как по строке"], "key": 0},
        "en": {"stem": "ORDER BY amount DESC, created_at means…",
               "options": ["Expensive first; ties broken by the earlier date",
                           "Earliest first, then by amount", "Sort by amount and date concatenated"], "key": 0},
        "de": {"stem": "ORDER BY amount DESC, created_at bedeutet…",
               "options": ["Teure zuerst; bei Gleichstand das frühere Datum",
                           "Zuerst die frühesten, dann nach Betrag", "Sortierung nach Betrag und Datum als Text"], "key": 0}}),
    ("order-by", ":cloze", {
        "ru": {"text": "Сортировка — последний шаг перед ___", "key": "LIMIT"},
        "en": {"text": "Sorting is the last step before ___", "key": "LIMIT"},
        "de": {"text": "Das Sortieren ist der letzte Schritt vor ___", "key": "LIMIT"}}),
    ("operators", ":single-choice", {
        "ru": {"stem": "Какой оператор проверяет попадание в диапазон?",
               "options": ["IN", "BETWEEN", "LIKE"], "key": 1},
        "en": {"stem": "Which operator tests a range?", "options": ["IN", "BETWEEN", "LIKE"], "key": 1},
        "de": {"stem": "Welcher Operator prüft einen Bereich?", "options": ["IN", "BETWEEN", "LIKE"], "key": 1}}),
    ("subquery", ":single-choice", {
        "ru": {"stem": "Подзапрос в FROM — это…",
               "options": ["Производная таблица", "Скалярное значение", "Индекс"], "key": 0},
        "en": {"stem": "A subquery in FROM is…", "options": ["A derived table", "A scalar value", "An index"], "key": 0},
        "de": {"stem": "Eine Unterabfrage im FROM ist…", "options": ["Eine abgeleitete Tabelle", "Ein Skalarwert", "Ein Index"], "key": 0}}),
    ("subquery", ":cloze", {
        "ru": {"text": "Подзапрос, который выполняется для каждой строки внешнего запроса, называют ___",
               "key": "коррелированным"},
        "en": {"text": "A subquery evaluated once per outer row is called ___", "key": "correlated"},
        "de": {"text": "Eine Unterabfrage, die je äußerer Zeile läuft, heißt ___", "key": "korreliert"}}),
    ("cte", ":single-choice", {
        "ru": {"stem": "Чем WITH удобнее вложенного подзапроса?",
               "options": ["Работает быстрее всегда", "Запрос читается сверху вниз, шаг за шагом",
                           "Позволяет обойтись без индексов"], "key": 1},
        "en": {"stem": "Why is WITH easier than a nested subquery?",
               "options": ["It is always faster", "The query reads top to bottom, step by step",
                           "It removes the need for indexes"], "key": 1},
        "de": {"stem": "Warum ist WITH bequemer als eine verschachtelte Unterabfrage?",
               "options": ["Es ist immer schneller", "Die Abfrage liest sich von oben nach unten, Schritt für Schritt",
                           "Man braucht keine Indizes mehr"], "key": 1}}),
    ("cte", ":single-choice", {
        "ru": {"stem": "Какие CTE обходят иерархии?",
               "options": ["Рекурсивные", "Материализованные", "Временные"], "key": 0},
        "en": {"stem": "Which CTEs walk hierarchies?", "options": ["Recursive", "Materialised", "Temporary"], "key": 0},
        "de": {"stem": "Welche CTEs durchlaufen Hierarchien?", "options": ["Rekursive", "Materialisierte", "Temporäre"], "key": 0}}),
    ("window", ":single-choice", {
        "ru": {"stem": "Что делает PARTITION BY в оконной функции?",
               "options": ["Удаляет дубликаты", "Делит строки на группы, внутри которых считается окно",
                           "Сортирует результат"], "key": 1},
        "en": {"stem": "What does PARTITION BY do in a window function?",
               "options": ["Removes duplicates", "Splits rows into groups, each with its own window",
                           "Sorts the result"], "key": 1},
        "de": {"stem": "Was macht PARTITION BY in einer Fensterfunktion?",
               "options": ["Entfernt Duplikate", "Teilt Zeilen in Gruppen mit je eigenem Fenster",
                           "Sortiert das Ergebnis"], "key": 1}}),
    ("window", ":cloze", {
        "ru": {"text": "Оконная функция, в отличие от GROUP BY, строки не ___", "key": "сворачивает"},
        "en": {"text": "Unlike GROUP BY, a window function does not ___ the rows", "key": "collapse"},
        "de": {"text": "Anders als GROUP BY werden bei einer Fensterfunktion die Zeilen nicht ___", "key": "zusammengefasst"}}),
    ("left-join", ":cloze", {
        "ru": {"text": "Там, где пары нет, столбцы правой таблицы заполняются ___", "key": "NULL"},
        "en": {"text": "Where no match exists, the right table's columns are filled with ___", "key": "NULL"},
        "de": {"text": "Wo es keine Entsprechung gibt, werden die Spalten der rechten Tabelle mit ___ gefüllt", "key": "NULL"}}),
]
ITEMS = ITEMS + EXTRA_ITEMS

# --- exercises: answers that are EXECUTED (wiki/Exercises.md) -----------------
# One shared seed, so the engine boots once per course. Data stays in English
# in every locale: it is the database, not the copy. Grading compares the
# learner's rows with the solution's rows, unordered unless "ordered?".
SETUP = """
create table customers (id int primary key, name text, city text);
create table orders (id int primary key, customer_id int references customers, amount numeric, placed date);
insert into customers values (1,'Anna','Moscow'),(2,'Boris','Kazan'),(3,'Vera','Moscow'),(4,'Gleb','Sochi'),(5,'Dina','Kazan');
insert into orders values (1,1,120,'2026-01-03'),(2,1,80,'2026-02-11'),(3,2,300,'2026-01-20'),
  (4,3,50,'2026-03-01'),(5,3,70,'2026-03-15'),(6,3,90,'2026-04-02'),(7,4,10,'2026-02-02');
""".strip()
SCHEMA = "customers(id, name, city)\norders(id, customer_id, amount, placed)"

EXERCISES = [
    ("where", "SELECT name FROM customers WHERE city = 'Moscow'", False, {
        "ru": "Выберите имена клиентов из Москвы (city = 'Moscow').",
        "en": "Select the names of customers from Moscow (city = 'Moscow').",
        "de": "Wählen Sie die Namen der Kunden aus Moskau (city = 'Moscow')."}),
    ("group-by", "SELECT customer_id, SUM(amount) FROM orders GROUP BY customer_id", False, {
        "ru": "Посчитайте сумму заказов каждого клиента: customer_id и сумма.",
        "en": "Total the orders of each customer: customer_id and the sum.",
        "de": "Summieren Sie die Bestellungen jedes Kunden: customer_id und die Summe."}),
    ("join", "SELECT c.name, o.amount FROM orders o JOIN customers c ON c.id = o.customer_id", False, {
        "ru": "Выведите имя клиента и сумму каждого его заказа.",
        "en": "List the customer's name and the amount of each of their orders.",
        "de": "Geben Sie den Kundennamen und den Betrag jeder seiner Bestellungen aus."}),
    ("window", """WITH totals AS (SELECT c.city, c.name, SUM(o.amount) AS total
                FROM customers c JOIN orders o ON o.customer_id = c.id GROUP BY c.city, c.name),
ranked AS (SELECT *, RANK() OVER (PARTITION BY city ORDER BY total DESC) AS r FROM totals)
SELECT city, name, total FROM ranked WHERE r = 1 ORDER BY city""", True, {
        "ru": "Для каждого города — клиент с наибольшей общей суммой заказов: город, имя, сумма. Отсортируйте по городу.",
        "en": "For each city, the customer with the highest order total: city, name, total. Sort by city.",
        "de": "Für jede Stadt der Kunde mit der höchsten Bestellsumme: Stadt, Name, Summe. Nach Stadt sortiert."}),
]
EXERCISE_ITEMS = [(cid, ":exercise", {loc: {"stem": stem, "setup": SETUP, "solution": sol,
                                             "schema": SCHEMA, "ordered?": ordered}
                                       for loc, stem in stems.items()})
                  for cid, sol, ordered, stems in EXERCISES]


COURSES = [("sql-analyst",
          [["select", ":apprentice"], ["where", ":apprentice"], ["group-by", ":apprentice"],
           ["having", ":apprentice"], ["join", ":apprentice"], ["agg", ":apprentice"]],
          {"ru": "SQL для аналитиков: отчёты из одной базы",
           "en": "SQL for analysts: reports from a single database",
           "de": "SQL für Analysten: Berichte aus einer Datenbank"},
          80, 12, 18),
          # the compounding demo: its closure reaches back through aggregates,
          # grouping, filtering and joins — all proven by the first course, so
          # this one opens PRE-SHRUNK for anyone who took it
          ("sql-reporting",
           [["window", ":professional"], ["subquery", ":apprentice"],
            ["cte", ":apprentice"], ["left-join", ":apprentice"]],
           {"ru": "SQL: отчёты посложнее — окна, подзапросы, CTE",
            "en": "SQL: harder reports — windows, subqueries, CTEs",
            "de": "SQL: anspruchsvollere Berichte — Fenster, Unterabfragen, CTEs"},
           75, 10, 14)]


def build(locale):
    events = []
    ev = make_ev(events)

    for sid, ref, titles in SOURCES:
        ev("source-added", source={"id": sid, "title": pick(titles, locale, sid), "ref": ref})

    for cid, kind, src, texts in C:
        title, definition = pick(texts, locale, cid)
        ev("concept-accepted", concept={"id": cid, "title": title, "definition": definition, "kind": kind, "source-id": src})

    for f, to in REQ:
        ev("edge-accepted", edge={"from": f, "to": to, "type": ":requires", "provenance": ":authored"})
    for f, to, etype, just in EDGES_EXTRA:
        edge = {"from": f, "to": to, "type": etype, "provenance": ":authored"}
        if just is not None:
            edge["justification"] = pick(just, locale, f"{f}->{to} edge")
        ev("edge-accepted", edge=edge)

    for i, (cid, kind, level, anchor, texts) in enumerate(OBJ):
        oid = f"o-{cid}-{i}"
        ev("object-added", object={"id": oid, "concept-id": cid, "kind": kind, "level": level,
                                    "locale": locale, "origin": ":authored", "text": pick(texts, locale, oid),
                                    "grounded-in": "src-pg-docs" if cid in ("limit", "left-join", "cte", "window") else "src-sql-book",
                                    "anchor": anchor})

    by_concept = {}

    def add_items(items):
      for cid, kind, specs in items:
        n = by_concept.get(cid, 0) + 1
        by_concept[cid] = n
        iid = f"i-{cid}-{n}"
        s = pick(specs, locale, iid)
        if kind == ":single-choice":
            spec = {"stem": s["stem"], "options": s["options"], "key": s["key"]}
        elif kind == ":exercise":
            spec = s
        else:
            spec = {"text": s["text"], "key": s["key"]}
        ev("item-added", item={"id": iid, "primary-concept-id": cid, "level": ":professional" if cid == "window" else ":apprentice",
                               "kind": kind, "locale": locale, "spec": spec})
        ev("item-accepted", id=iid)

    def publish():
        for ccid, targets, ctitles, thresh, diag, final in COURSES:
            ev("course-published", course={"id": ccid, "title": pick(ctitles, locale, ccid),
                                           "targets": targets, "locale": locale, "pass-threshold": thresh,
                                           "diagnostic-items": diag, "final-items": final})

    add_items(ITEMS)

    qid, qcid, qtexts = QUESTION
    ev("question-raised", **{"id": qid, "concept-id": qcid, "question": pick(qtexts, locale, qid)})

    publish()
    # APPENDED, never inserted: demos load additively and idempotently by
    # :ev/id, so earlier events keep their ids. The courses are republished
    # after the exercises — a publication is a cut, and learners fold up to it.
    add_items(EXERCISE_ITEMS)
    publish()
    return events


events_ru = build("ru")
events_de = build("de")
events_en = build("en")

assert len(events_ru) == len(events_de) == len(events_en), "locale event counts diverged"
ids_ru = [e["ev/id"] for e in events_ru]
assert ids_ru == [e["ev/id"] for e in events_de] == [e["ev/id"] for e in events_en], "locale ev/id sequences diverged"

HEADER = ";; Demo knowledge graph — SQL for analysts. GENERATED by build.py; edit that, not this.\n"


def write(events, fname):
    with open(__file__.replace("build.py", fname), "w") as f:
        f.write(HEADER)
        for e in events:
            f.write(edn(e) + "\n")


write(events_ru, "log.edn")
# legacy names (tests parse log.edn / fold-test the trio) + slug names the
# app fetches now that there are several demo graphs
for fname, ev in [("log.ru.edn", events_ru), ("log.de.edn", events_de), ("log.en.edn", events_en),
                  ("sql.ru.edn", events_ru), ("sql.de.edn", events_de), ("sql.en.edn", events_en)]:
    write(ev, fname)

n_edges = len(REQ) + len(EDGES_EXTRA)
print(f"log.edn, log.ru.edn, log.de.edn, log.en.edn: {len(events_ru)} events each, "
      f"{len(C)} concepts, {n_edges} edges, {len(OBJ)} objects, {len(ITEMS)} items")

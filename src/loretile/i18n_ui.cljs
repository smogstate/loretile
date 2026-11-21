(ns loretile.i18n-ui
  "Editor strings (:ui/…). Every entry carries ru, de AND en — test/i18n-check.js fails otherwise.")

(def strings
  {;; window chrome (win-tools)
   :ui/minimize {:ru "Свернуть" :de "Minimieren" :en "Minimize"}
   :ui/restore {:ru "Восстановить" :de "Wiederherstellen" :en "Restore"}
   :ui/maximize {:ru "Развернуть" :de "Maximieren" :en "Maximize"}
   :ui/close {:ru "Закрыть" :de "Schließen" :en "Close"}

   ;; map / node
   :ui/node-sub-counts {:ru "{n} матер. · {m} вопр." :de "{n} Mat. · {m} Fragen" :en "{n} mat. · {m} questions"}
   :ui/node-stale-suffix {:ru " · устарело" :de " · veraltet" :en " · stale"}
   :ui/ghost-hint {:ru "предложение — в чате" :de "Vorschlag — im Chat" :en "proposal — in chat"}
   :ui/empty-title {:ru "Граф знаний — карта того, что нужно понять"
                    :de "Ein Wissensgraph — die Karte dessen, was zu verstehen ist"
                    :en "A knowledge graph — the map of what there is to understand"}
   :ui/empty-or {:ru "…или посмотрите на готовый:" :de "…oder sehen Sie sich einen fertigen an:" :en "…or look at a finished one:"}
   :ui/empty-map-text {:ru "Каждая тема — одна проверяемая идея; связи говорят, что понадобится раньше. Напишите первую тему сами или отдайте документ ассистенту — он предложит темы карточками."
                        :de "Noch leer. Legen Sie das erste Thema an oder übergeben Sie Material dem Assistenten."
                        :en "Every topic is one testable idea; the edges say what is needed first. Write the first topic yourself, or hand the assistant a document and it will propose topics as cards."}
   :ui/topic {:ru "Тема" :de "Thema" :en "Topic"}
   :ui/assistant {:ru "Ассистент" :de "Assistent" :en "Assistant"}

   ;; new-concept-form
   :ui/new-topic-heading {:ru " Новая тема" :de " Neues Thema" :en " New topic"}
   :ui/topic-name-placeholder {:ru "Название темы" :de "Themenname" :en "Topic name"}
   :ui/definition-placeholder {:ru "Определение (одна проверяемая идея)"
                                :de "Definition (eine prüfbare Idee)"
                                :en "Definition (one testable idea)"}
   :ui/topic-added {:ru "«{title}» добавлена" :de "„{title}“ hinzugefügt" :en "“{title}” added"}
   :ui/add {:ru "Добавить" :de "Hinzufügen" :en "Add"}
   :ui/cancel {:ru "Отмена" :de "Abbrechen" :en "Cancel"}
   :ui/save {:ru "Сохранить" :de "Speichern" :en "Save"}
   :ui/saved {:ru "Сохранено" :de "Gespeichert" :en "Saved"}
   :ui/edit {:ru "Изменить" :de "Bearbeiten" :en "Edit"}
   ;; --- several graphs side by side ---
   :ui/graphs {:ru "Графы" :de "Graphen" :en "Graphs"}
   :ui/graph-first {:ru "Мой граф" :de "Mein Graph" :en "My graph"}
   :ui/new-graph {:ru "Новый граф" :de "Neuer Graph" :en "New graph"}
   ;; the ontology as its own graph (wiki/Ontology-Graph.md)
   ;; graphs and ontologies managed in their own views (2026-11-19)
   ;; node properties and the ontology edited on its map (wiki/Property-Graph.md)
   ;; one set of graphs, each with an ontology (2026-11-19)
   :ui/new-type-heading {:ru "Новый тип" :de "Neuer Typ" :en "New type"}
   :ui/type-name-placeholder {:ru "Название типа — например, «процесс»" :de "Name des Typs — z. B. «Prozess»" :en "Type name — e.g. «process»"}
   :ui/type-def-placeholder {:ru "Что это такое — одно предложение" :de "Was das ist — ein Satz" :en "What it is — one sentence"}
   :ui/type-word {:ru "Тип" :de "Typ" :en "Type"}
   :ui/new-node-heading {:ru "Новый узел" :de "Neuer Knoten" :en "New node"}
   :ui/node-name-placeholder {:ru "Название" :de "Name" :en "Name"}
   :ui/node-def-placeholder {:ru "Описание" :de "Beschreibung" :en "Description"}
   :ui/node-word {:ru "Узел" :de "Knoten" :en "Node"}
   :ui/empty-onto-title {:ru "Онтология — типы и связи между ними" :de "Eine Ontologie — Typen und ihre Beziehungen" :en "An ontology — types and how they relate"}
   :ui/empty-onto-text {:ru "Добавьте типы — «процесс», «роль», «система»; потом соедините их: связь между типами станет связью, которой пользуются графы с этой онтологией." :de "Fügen Sie Typen hinzu — «Prozess», «Rolle», «System»; verbinden Sie sie dann: eine Verbindung zwischen Typen wird eine Beziehung für die Graphen mit dieser Ontologie." :en "Add types — «process», «role», «system»; then connect them: a link between types becomes a relation the graphs using this ontology can draw."}
   :ui/empty-graph-title {:ru "Пустой граф" :de "Ein leerer Graph" :en "An empty graph"}
   :ui/empty-graph-text {:ru "Добавьте узел — его тип берётся из онтологии графа; или отдайте документ ассистенту — он предложит узлы и связи карточками." :de "Fügen Sie einen Knoten hinzu — sein Typ kommt aus der Ontologie des Graphen; oder geben Sie dem Assistenten ein Dokument." :en "Add a node — its type comes from the graph's ontology; or give the assistant a document and it proposes nodes and links as cards."}
   :ui/ontology-graph-hint {:ru "Этот граф — онтология: его узлы — типы, связи между ними — связи, которыми пользуются графы с этой онтологией." :de "Dieser Graph ist eine Ontologie: seine Knoten sind Typen, seine Verbindungen die Beziehungen der Graphen, die sie verwenden." :en "This graph is an ontology: its nodes are types, its links the relations the graphs using it can draw."}
   :ui/ontology-bound-hint {:ru "Словарь этого графа — копия его онтологии: типы узлов и связи. Изменения приходят сюда сами." :de "Das Vokabular dieses Graphen ist eine Kopie seiner Ontologie. Änderungen kommen von selbst." :en "This graph's vocabulary is a copy of its ontology: node types and relations. Changes arrive by themselves."}
   :ui/create {:ru "Создать" :de "Erstellen" :en "Create"}
   :ui/ontology-learning-builtin {:ru "Обучение (встроенная)" :de "Lernen (eingebaut)" :en "Learning (built in)"}
   :ui/ontology-types-builtin {:ru "Типы и связи (встроенная)" :de "Typen und Beziehungen (eingebaut)" :en "Types and relations (built in)"}
   :ui/ontology-types-choice {:ru "Типы и связи (встроенная) — граф станет онтологией" :de "Typen und Beziehungen (eingebaut) — der Graph wird eine Ontologie" :en "Types and relations (built in) — the graph becomes an ontology"}
   :ui/graph-props {:ru "Граф" :de "Graph" :en "Graph"}
   :ui/color-scheme {:ru "цветовая схема карты" :de "Farbschema der Karte" :en "map colour scheme"}
   :ui/scheme-default {:ru "обычная" :de "Standard" :en "default"}
   :ui/scheme-blueprint {:ru "чертёж" :de "Blaupause" :en "blueprint"}
   :ui/scheme-warm {:ru "тёплая" :de "warm" :en "warm"}
   :ui/properties {:ru "Свойства" :de "Eigenschaften" :en "Properties"}
   :ui/prop-key {:ru "свойство" :de "Eigenschaft" :en "property"}
   :ui/prop-value {:ru "значение" :de "Wert" :en "value"}
   :ui/cq-checked-in-graphs {:ru "проверяется в графах с этой онтологией" :de "wird in den Graphen mit dieser Ontologie geprüft" :en "checked in the graphs using this ontology"}
   :ui/cq-inherited {:ru "из онтологии" :de "aus der Ontologie" :en "from the ontology"}
   :ui/migration-heading {:ru "Новая версия онтологии ждёт" :de "Eine neue Version der Ontologie wartet" :en "A new ontology version is waiting"}
   :ui/migration-lead {:ru "В новой версии «{onto}» нет того, чем пользуются данные этого графа. Выберите, чем это станет, — граф перейдёт на новую версию одним изменением." :de "Der neuen Version von „{onto}“ fehlt, was die Daten dieses Graphen verwenden. Wählen Sie, was daraus wird — der Graph wechselt in einer Änderung." :en "The new version of «{onto}» lacks what this graph's data uses. Choose what it becomes — the graph moves over in one change."}
   :ui/migration-type {:ru "тип «{type}» — узлов: {n} → станет" :de "Typ „{type}“ — Knoten: {n} → wird" :en "type «{type}» — {n} nodes → becomes"}
   :ui/migration-relation {:ru "связь «{rel}» — связей: {n} → станет" :de "Beziehung „{rel}“ — Kanten: {n} → wird" :en "relation «{rel}» — {n} links → becomes"}
   :ui/migration-remove-links {:ru "удалить эти связи" :de "diese Kanten entfernen" :en "remove these links"}
   :ui/migration-go {:ru "Перейти на новую версию" :de "Zur neuen Version wechseln" :en "Move to the new version"}
   :ui/migration-done {:ru "Граф на новой версии онтологии" :de "Der Graph ist auf der neuen Version" :en "The graph is on the new version"}
   :ui/prop-filter {:ru "Фильтр по свойству" :de "Nach Eigenschaft filtern" :en "Filter by property"}
   :ui/prop-filter-any {:ru "— любое значение —" :de "— jeder Wert —" :en "— any value —"}
   :ui/prop-filter-clear {:ru "Сбросить" :de "Zurücksetzen" :en "Clear"}
   :ui/map-prop {:ru "свойство под названием на карте" :de "Eigenschaft unter dem Namen auf der Karte" :en "property shown under titles on the map"}
   :ui/map-prop-none {:ru "— не показывать —" :de "— keine —" :en "— none —"}
   :ui/undo {:ru "Отменить последнее изменение" :de "Letzte Änderung rückgängig" :en "Undo the last change"}
   :ui/redo {:ru "Вернуть отменённое" :de "Wiederherstellen" :en "Redo"}
   :ui/undone {:ru "Отменено" :de "Rückgängig gemacht" :en "Undone"}
   :ui/redone {:ru "Возвращено" :de "Wiederhergestellt" :en "Redone"}
   :ui/nothing-to-undo {:ru "Нечего отменять" :de "Nichts rückgängig zu machen" :en "Nothing to undo"}
   :ui/nothing-to-redo {:ru "Нечего возвращать" :de "Nichts wiederherzustellen" :en "Nothing to redo"}
   :ui/server-graphs {:ru "На сервере" :de "Auf dem Server" :en "On the server"}
   :ui/server-graphs-loading {:ru "Смотрю, что есть на сервере…" :de "Frage den Server…" :en "Asking the server…"}
   :ui/server-graphs-offline {:ru "Сервер сейчас не отвечает — графы на этом устройстве работают как обычно." :de "Der Server antwortet nicht — die Graphen auf diesem Gerät funktionieren wie immer." :en "The server is not answering — the graphs on this device work as usual."}
   :ui/server-graphs-none {:ru "Все графы сервера уже на этом устройстве." :de "Alle Graphen des Servers sind schon auf diesem Gerät." :en "Every graph on the server is already on this device."}
   :ui/server-graph-meta {:ru "событий: {n}" :de "Ereignisse: {n}" :en "events: {n}"}
   :ui/open {:ru "Открыть" :de "Öffnen" :en "Open"}
   :ui/peer-conflicts-1 {:ru "конфликт" :de "Konflikt" :en "conflict"}
   :ui/peer-conflicts-2 {:ru "конфликта" :de "Konflikte" :en "conflicts"}
   :ui/peer-conflicts-5 {:ru "конфликтов" :de "Konflikte" :en "conflicts"}
   :ui/peer-nodes-1 {:ru "новый узел" :de "neuer Knoten" :en "new node"}
   :ui/peer-nodes-2 {:ru "новых узла" :de "neue Knoten" :en "new nodes"}
   :ui/peer-nodes-5 {:ru "новых узлов" :de "neue Knoten" :en "new nodes"}
   :ui/peer-links-1 {:ru "новая связь" :de "neue Verbindung" :en "new link"}
   :ui/peer-links-2 {:ru "новые связи" :de "neue Verbindungen" :en "new links"}
   :ui/peer-links-5 {:ru "новых связей" :de "neue Verbindungen" :en "new links"}
   :ui/peer-props-1 {:ru "свойство изменено" :de "Eigenschaft geändert" :en "property changed"}
   :ui/peer-props-2 {:ru "свойства изменены" :de "Eigenschaften geändert" :en "properties changed"}
   :ui/peer-props-5 {:ru "свойств изменено" :de "Eigenschaften geändert" :en "properties changed"}
   :ui/peer-removed-1 {:ru "удаление" :de "Entfernung" :en "removal"}
   :ui/peer-removed-2 {:ru "удаления" :de "Entfernungen" :en "removals"}
   :ui/peer-removed-5 {:ru "удалений" :de "Entfernungen" :en "removals"}
   :ui/conflict-arrived {:ru "Нужно ваше решение: изменения разошлись — см. ассистента" :de "Ihre Entscheidung ist nötig: Änderungen kollidieren — siehe Assistent" :en "Your decision is needed: changes collided — see the assistant"}
   :ui/prop-icon {:ru "значок" :de "Symbol" :en "icon"}
   :ui/icon-none {:ru "без значка" :de "ohne Symbol" :en "no icon"}
   :ui/style-reset {:ru "вернуть унаследованный" :de "auf geerbt zurücksetzen" :en "back to inherited"}
   :ui/type-styles {:ru "Вид типов в этом графе" :de "Aussehen der Typen in diesem Graphen" :en "How types look in this graph"}
   :ui/rel-delete-confirm {:ru "Удалить связь «{label}» целиком — все её рёбра в онтологии?" :de "Die Beziehung «{label}» ganz löschen — alle ihre Kanten in der Ontologie?" :en "Delete the relation «{label}» entirely — all its edges in the ontology?"}
   :ui/prop-color {:ru "цвет" :de "Farbe" :en "colour"}
   :ui/ontology-type {:ru "тип" :de "Typ" :en "type"}
   :ui/new-relation {:ru "Новая связь" :de "Neue Beziehung" :en "New relation"}
   :ui/new-relation-placeholder {:ru "как читается, например «выполняет»" :de "wie sie sich liest, z. B. «führt aus»" :en "how it reads, e.g. «performs»"}
   :ui/ontology-edit-on-map {:ru "Онтология правится на её карте: «+» — новый тип, связь между типами — новая связь, свойства — во всплывающем окне типа." :de "Die Ontologie wird auf ihrer Karte bearbeitet: «+» ist ein neuer Typ, eine Verbindung zwischen Typen eine neue Beziehung, Eigenschaften im Fenster des Typs." :en "The ontology is edited on its map: «+» is a new type, a link between types a new relation, properties in the type's popup."}
   :ui/ontologies {:ru "Онтологии" :de "Ontologien" :en "Ontologies"}
   :ui/ontologies-none {:ru "Онтологий пока нет. Начните новую." :de "Noch keine Ontologien. Beginnen Sie eine neue." :en "No ontologies yet. Start a new one."}
   :ui/used-by {:ru "графов с ней: {n}" :de "Graphen damit: {n}" :en "graphs using it: {n}"}
   :ui/open-now {:ru "открыт" :de "geöffnet" :en "open"}
   :ui/rename {:ru "Переименовать" :de "Umbenennen" :en "Rename"}
   :ui/rename-prompt {:ru "Новое название:" :de "Neuer Name:" :en "New name:"}
   :ui/delete {:ru "Удалить" :de "Löschen" :en "Delete"}
   :ui/delete-confirm {:ru "Удалить «{title}» с этого устройства? Это не отменить — сначала экспортируйте, если он нужен." :de "«{title}» von diesem Gerät löschen? Das lässt sich nicht rückgängig machen — vorher exportieren, wenn Sie ihn brauchen." :en "Delete «{title}» from this device? This cannot be undone — export it first if you need it."}
   :ui/error-ontology-in-use {:ru "Эту онтологию используют графы на этом устройстве." :de "Diese Ontologie wird von Graphen auf diesem Gerät verwendet." :en "Graphs on this device use this ontology."}
   :ui/ontology-graph {:ru "онтология" :de "Ontologie" :en "ontology"}
   :ui/ontology-of {:ru "Онтология:" :de "Ontologie:" :en "Ontology:"}
   :ui/new-ontology {:ru "Новая онтология" :de "Neue Ontologie" :en "New ontology"}
   :ui/new-ontology-prompt {:ru "Название онтологии:" :de "Name der Ontologie:" :en "Ontology name:"}
   :ui/bound-to {:ru "Онтология этого графа живёт в своём графе:" :de "Die Ontologie dieses Graphen lebt in einem eigenen Graphen:" :en "This graph's ontology lives in its own graph:"}
   :ui/sync-refused {:ru "Последнюю версию онтологии граф не принял — она сломала бы данные: {detail}" :de "Die neueste Version der Ontologie hat dieser Graph nicht übernommen — sie würde Daten brechen: {detail}" :en "This graph did not take the newest version of the ontology — it would break data: {detail}"}
   :ui/ontology-users {:ru "Графы с этой онтологией" :de "Graphen mit dieser Ontologie" :en "Graphs using this ontology"}
   :ui/ontology-users-none {:ru "На этом устройстве её пока не использует ни один граф." :de "Auf diesem Gerät verwendet sie noch kein Graph." :en "No graph on this device uses it yet."}
   :ui/new-graph-from-ontology {:ru "Новый граф с этой онтологией" :de "Neuer Graph mit dieser Ontologie" :en "New graph with this ontology"}
   :ui/error-ontology-graph-only {:ru "В графе онтологии живут только типы и связи." :de "Ein Ontologie-Graph enthält nur Typen und Beziehungen." :en "An ontology graph holds only types and relations."}
   :ui/error-ontology-elsewhere {:ru "Онтология этого графа правится в графе «{title}»." :de "Die Ontologie dieses Graphen wird im Graphen «{title}» bearbeitet." :en "This graph's ontology is edited in the graph «{title}»."}
   :ui/error-ontology-breaks-data {:ru "Эта версия онтологии убрала бы то, чем пользуются данные: {detail}" :de "Diese Version der Ontologie entfernte, was die Daten verwenden: {detail}" :en "This version of the ontology would remove what the data uses: {detail}"}
   :ui/error-no-whole-ontology {:ru "Онтология ещё не готова: нужен хотя бы один тип." :de "Die Ontologie ist noch nicht fertig: mindestens ein Typ nötig." :en "The ontology is not ready yet: it needs at least one type."}
   :ui/new-graph-prompt {:ru "Название нового графа — например, тема учебника, который вы загрузите"
                         :de "Name des neuen Graphen, etwa das Thema des Dokuments, das Sie hochladen"
                         :en "Name the new graph, for instance the subject of the document you will upload"}
   ;; --- ontology profiles (wiki/Profiles.md): a graph speaks its own vocabulary ---
   :ui/new-graph-ontology {:ru "Новый граф по онтологии…" :de "Neuer Graph aus einer Ontologie…" :en "New graph from an ontology…"}
   :ui/new-graph-interview {:ru "Новый граф с ассистентом…" :de "Neuer Graph mit dem Assistenten…" :en "New graph with the assistant…"}
   :ui/new-graph-interview-prompt {:ru "Название нового графа — ассистент спросит, для чего он, и предложит словарь"
                                   :de "Name des neuen Graphen — der Assistent fragt, wofür er ist, und schlägt ein Vokabular vor"
                                   :en "Name the new graph — the assistant will ask what it is for and propose a vocabulary"}
   :ui/ontology {:ru "Онтология" :de "Ontologie" :en "Ontology"}
   :ui/ontology-hint {:ru "Словарь этого графа: типы узлов и связи, заданные файлом при создании. Ассистент и карта говорят на нём; движок обучения эти связи не читает."
                      :de "Das Vokabular dieses Graphen: Knotentypen und Relationen aus der Datei bei der Erstellung. Assistent und Karte sprechen es; die Lern-Engine liest diese Relationen nicht."
                      :en "This graph's vocabulary: node types and relations from the file it was created with. The assistant and the map speak it; the learning engine never reads these relations."}
   :ui/ontology-learning-hint {:ru "Учебный словарь: одна тема — одна проверяемая идея; планировщик читает только «требует»."
                               :de "Das Lernvokabular: ein Thema ist eine prüfbare Idee; der Planer liest nur „erfordert“."
                               :en "The learning vocabulary: a topic is one testable idea; the scheduler reads only “requires”."}
   :ui/node-types {:ru "Типы узлов" :de "Knotentypen" :en "Node types"}
   :ui/relations {:ru "Связи" :de "Relationen" :en "Relations"}
   :ui/learnable {:ru "изучается" :de "lernbar" :en "learnable"}
   :ui/rel-label {:ru "читается" :de "liest sich" :en "reads"}
   :ui/rel-inverse {:ru "обратно" :de "umgekehrt" :en "inverse"}
   :ui/rel-from-to {:ru "от → к" :de "von → zu" :en "from → to"}
   :ui/rel-to-source {:ru "узел → источник" :de "Knoten → Quelle" :en "node → source"}
   :ui/rel-ordering {:ru "порядок слоёв" :de "Schichtordnung" :en "layer order"}
   :ui/rel-acyclic {:ru "без циклов" :de "zyklenfrei" :en "acyclic"}
   :ui/rel-symmetric {:ru "симметрична" :de "symmetrisch" :en "symmetric"}
   :ui/edge-grounded-in {:ru "основано на" :de "gestützt auf" :en "grounded in"}
   :ui/edge-grounds {:ru "основание для" :de "Grundlage für" :en "grounds"}
   :ui/edge-unlocks {:ru "открывает" :de "eröffnet" :en "unlocks"}
   :ui/no-relation-fits {:ru "Ни одна связь онтологии не соединяет узлы этих типов." :de "Keine Relation der Ontologie verbindet Knoten dieser Typen." :en "No relation of the ontology joins nodes of these types."}
   :ui/export-agent {:ru "Экспорт для агента (.md)" :de "Export für den Agenten (.md)" :en "Export for an agent (.md)"}
   :ui/error-unknown-relation {:ru "Такой связи нет в онтологии графа: {detail}" :de "Diese Relation kennt die Ontologie nicht: {detail}" :en "The graph's ontology has no such relation: {detail}"}
   :ui/error-relation-misfit {:ru "Связь не соединяет узлы этих типов: {detail}" :de "Die Relation verbindet Knoten dieser Typen nicht: {detail}" :en "The relation does not join nodes of these types: {detail}"}
   :ui/error-unknown-node-type {:ru "Такого типа узла нет в онтологии: {detail}" :de "Diesen Knotentyp kennt die Ontologie nicht: {detail}" :en "The ontology has no such node type: {detail}"}
   :ui/error-profile-locked {:ru "Онтология задаётся один раз, для пустого графа." :de "Die Ontologie wird einmal festgelegt, für einen leeren Graphen." :en "The ontology is set once, for an empty graph."}
   :ui/error-bad-profile {:ru "Файл онтологии не принят: {detail}" :de "Ontologie-Datei abgelehnt: {detail}" :en "Ontology file refused: {detail}"}
   :ui/pitfalls {:ru "Проверка" :de "Prüfung" :en "Checks"}
   :ui/pitfalls-none {:ru "Движок ничего подозрительного не видит: нет узлов без связей, нет связей в обе стороны, каждый тип и каждая связь в деле." :de "Die Engine sieht nichts Verdächtiges: keine Knoten ohne Relationen, keine Relation in beide Richtungen, jeder Typ und jede Relation in Gebrauch." :en "The engine sees nothing suspicious: no node without edges, no relation used both ways, every type and relation in use."}
   :ui/pit-symmetric {:ru "«{a}» и «{b}» связаны «{rel}» в обе стороны — направление неясно" :de "„{a}“ und „{b}“ sind über „{rel}“ in beide Richtungen verbunden — die Richtung ist unklar" :en "“{a}” and “{b}” are linked by “{rel}” both ways — the direction is unclear"}
   :ui/pit-isolated {:ru "Узел без единой связи:" :de "Knoten ohne eine einzige Relation:" :en "A node with no edge at all:"}
   :ui/pit-unused-type {:ru "Тип «{t}» не используется ни одним узлом" :de "Typ „{t}“ wird von keinem Knoten verwendet" :en "Type “{t}” is used by no node"}
   :ui/pit-unused-relation {:ru "Связь «{rel}» не используется ни одной парой узлов" :de "Relation „{rel}“ wird von keinem Knotenpaar verwendet" :en "Relation “{rel}” is used by no pair of nodes"}
   :ui/probe-ontology {:ru "Спросить ассистента: чего словарь не отвечает (ИИ)" :de "Den Assistenten fragen: was das Vokabular nicht beantwortet (KI)" :en "Ask the assistant: what the vocabulary cannot answer (AI)"}
   :ui/cqs {:ru "Вопросы компетенции" :de "Kompetenzfragen" :en "Competency questions"}
   :ui/cqs-hint {:ru "На что граф обязан отвечать. Вопрос с шаблоном проверяется по графу: у каждого узла типа «от» должна быть такая связь — узлы без неё и есть пробелы."
                 :de "Was der Graph beantworten muss. Eine Frage mit Muster wird am Graphen geprüft: jeder Knoten des Von-Typs braucht diese Relation — Knoten ohne sie sind die Lücken."
                 :en "What the graph must be able to answer. A question with a pattern is checked over the graph: every node of the from-type needs that relation — nodes without it are the gaps."}
   :ui/cq-answered {:ru "отвечено {a} из {n}" :de "{a} von {n} beantwortet" :en "answered {a} of {n}"}
   :ui/cq-no-pattern {:ru "без шаблона — судит человек" :de "ohne Muster — ein Mensch urteilt" :en "no pattern — a person judges"}
   :ui/cq-gaps {:ru "пробелы: " :de "Lücken: " :en "gaps: "}
   :ui/cq-question-placeholder {:ru "Вопрос, на который граф должен отвечать" :de "Frage, die der Graph beantworten muss" :en "A question the graph must answer"}
   :ui/cq-pattern {:ru "отвечает связь" :de "beantwortet Relation" :en "answered by relation"}
   :ui/cq-dir-out {:ru "узел — связь →" :de "Knoten — Relation →" :en "node — relation →"}
   :ui/cq-dir-in {:ru "→ связь — узел" :de "→ Relation — Knoten" :en "→ relation — node"}
   :ui/cq-any-type {:ru "любой тип" :de "jeder Typ" :en "any type"}
   :ui/cq-no-pattern-opt {:ru "без шаблона" :de "ohne Muster" :en "no pattern"}
   :ui/cq-added {:ru "Вопрос записан" :de "Frage gespeichert" :en "Question recorded"}
   :ui/add-cq {:ru "+ вопрос компетенции" :de "+ Kompetenzfrage" :en "+ competency question"}
   :ui/rel-unjustified {:ru "ни один вопрос не требует" :de "keine Frage braucht sie" :en "no question needs it"}
   :ui/add-relation {:ru "+ связь" :de "+ Relation" :en "+ relation"}
   :ui/add-node-type {:ru "+ тип узла" :de "+ Knotentyp" :en "+ node type"}
   :ui/rel-id-placeholder {:ru "id (латиницей)" :de "id (lateinisch)" :en "id (latin)"}
   :ui/rel-label-placeholder {:ru "читается как…" :de "liest sich als…" :en "reads as…"}
   :ui/rel-inverse-placeholder {:ru "обратно читается как…" :de "umgekehrt liest sich als…" :en "inverse reads as…"}
   :ui/type-label-placeholder {:ru "название типа" :de "Typname" :en "type name"}
   :ui/relation-added {:ru "Связь добавлена в онтологию" :de "Relation zur Ontologie hinzugefügt" :en "Relation added to the ontology"}
   :ui/node-type-added {:ru "Тип узла добавлен" :de "Knotentyp hinzugefügt" :en "Node type added"}
   :ui/node-gaps {:ru "Без ответа" :de "Unbeantwortet" :en "Unanswered"}
   :ui/cq-tracker {:ru "вопросы: {a} / {n}" :de "Fragen: {a} / {n}" :en "questions: {a} / {n}"}
   ;; --- what the vocabulary means, who owns the graph, RDF out (2026-10-21) ---
   :ui/describe {:ru "описать" :de "beschreiben" :en "describe"}
   :ui/describe-placeholder {:ru "Одно предложение: что это значит и в какую сторону читается"
                             :de "Ein Satz: was es bedeutet und in welche Richtung es sich liest"
                             :en "One sentence: what it means and which way it reads"}
   :ui/description-saved {:ru "Описание записано" :de "Beschreibung gespeichert" :en "Description saved"}
   :ui/vocab-hint {:ru "Описание читает модель — и ассистент, и агент через MCP. Одно предложение на тип и на связь: что это и в какую сторону читается."
                   :de "Die Beschreibung liest das Modell — der Assistent und der Agent über MCP. Ein Satz je Typ und Relation: was es ist und in welche Richtung es sich liest."
                   :en "A model reads the description — the assistant and an agent over MCP. One sentence per type and relation: what it is and which way it reads."}
   :ui/owner {:ru "Владелец графа" :de "Verantwortlich für den Graphen" :en "Graph owner"}
   :ui/owner-hint {:ru "Кто отвечает за граф и как с ними связаться. Агент, читающий граф, видит это первым."
                   :de "Wer für den Graphen verantwortlich ist und wie man sie erreicht. Ein Agent, der den Graphen liest, sieht das zuerst."
                   :en "Who answers for the graph and how to reach them. An agent reading the graph sees this first."}
   :ui/owner-team-placeholder {:ru "команда" :de "Team" :en "team"}
   :ui/owner-contact-placeholder {:ru "почта, канал или телефон" :de "E-Mail, Kanal oder Telefon" :en "email, channel or phone"}
   :ui/owner-save {:ru "Записать" :de "Speichern" :en "Save"}
   :ui/owner-saved {:ru "Владелец записан" :de "Verantwortliche gespeichert" :en "Owner saved"}
   :ui/error-bad-ontology {:ru "Онтология не прочитана: {detail}" :de "Ontologie nicht gelesen: {detail}" :en "Ontology not read: {detail}"}
   :ui/onto-imported {:ru "Онтология перенесена: типов узлов — {t}, связей — {r}; порядок слоёв — «{o}»."
                      :de "Ontologie übernommen: {t} Knotentypen, {r} Relationen; Schichtordnung — „{o}“."
                      :en "Ontology imported: {t} node types, {r} relations; layer order — “{o}”."}
   :ui/onto-order-added {:ru "Файл не задаёт порядок, поэтому добавлена связь «comes after» — её можно описать или не использовать."
                         :de "Die Datei gibt keine Ordnung vor, daher wurde die Relation „comes after“ ergänzt — beschreiben oder ungenutzt lassen."
                         :en "The file sets no order, so a “comes after” relation was added — describe it or leave it unused."}
   :ui/onto-renamed {:ru "Переименованы (эти id заняты учебной онтологией): {list}" :de "Umbenannt (diese IDs gehören der Lern-Ontologie): {list}" :en "Renamed (these ids belong to the learning ontology): {list}"}
   :ui/onto-skipped {:ru "Не перенесено — Loretile хранит типы и связи, не значения:" :de "Nicht übernommen — Loretile hält Typen und Relationen, keine Werte:" :en "Not imported — Loretile keeps types and relations, not values:"}
   :ui/onto-skipped-data-properties {:ru "свойства-значения" :de "Dateneigenschaften" :en "data properties"}
   :ui/onto-skipped-individuals {:ru "экземпляры" :de "Individuen" :en "individuals"}
   :ui/onto-skipped-skos-concepts {:ru "понятия SKOS" :de "SKOS-Begriffe" :en "SKOS concepts"}
   :ui/onto-skipped-restrictions {:ru "ограничения OWL" :de "OWL-Einschränkungen" :en "OWL restrictions"}
   :ui/onto-skipped-enumerations {:ru "перечисления" :de "Aufzählungen" :en "enumerations"}
   :ui/authorship {:ru "Добавлено: {a} · изменено: {b}, {d}" :de "Hinzugefügt von {a} · zuletzt geändert von {b} am {d}" :en "Added by {a} · last changed by {b} on {d}"}
   :ui/author-heading {:ru "Автор" :de "Autor·in" :en "Author"}
   :ui/author-lead {:ru "Ваше имя на каждом изменении с этого устройства. Оно записывается в граф, который вы меняете, — экспорт и агент видят, кто что добавил."
                    :de "Ihr Name an jeder Änderung von diesem Gerät. Er wird in den Graphen geschrieben, den Sie ändern — Export und Agent sehen, wer was hinzugefügt hat."
                    :en "Your name on every change from this device. It is written into each graph you change, so the export and an agent see who added what."}
   :ui/author-placeholder {:ru "имя" :de "Name" :en "name"}
   :ui/author-saved {:ru "Имя записано" :de "Name gespeichert" :en "Name saved"}
   :ui/export-turtle {:ru "Экспорт в RDF (.ttl)" :de "Export als RDF (.ttl)" :en "Export as RDF (.ttl)"}
   ;; --- the topic's structural moves and the expert's answers (2026-10-14) ---
   :ui/removed-material {:ru "Убранные материалы ({n})" :de "Entfernte Materialien ({n})" :en "Removed material ({n})"}
   :ui/restore-material {:ru "вернуть" :de "zurückholen" :en "restore"}
   :ui/restored {:ru "Материал возвращён" :de "Material zurückgeholt" :en "Material restored"}
   :ui/expert-heading {:ru " Вопросы к эксперту (открыто: {n})" :de " Fragen an den Experten (offen: {n})" :en " Questions for the expert (open: {n})"}
   :ui/expert-answer-placeholder {:ru "Ответ эксперта" :de "Antwort des Experten" :en "The expert's answer"}
   :ui/answer-as-material {:ru "добавить ответ как материал темы" :de "Antwort als Material des Themas hinzufügen" :en "add the answer as material on this topic"}
   :ui/answer-save {:ru "Ответить" :de "Antworten" :en "Answer"}
   :ui/answered {:ru "Ответ записан" :de "Antwort gespeichert" :en "Answer recorded"}
   :ui/split-open {:ru "Разделить тему…" :de "Thema aufteilen…" :en "Split the topic…"}
   :ui/split-heading {:ru "Разделить тему" :de "Thema aufteilen" :en "Split the topic"}
   :ui/split-hint {:ru "Одна тема станет несколькими. Материалы и вопросы уйдут туда, куда вы их отправите; попытки учеников идут за своими вопросами, так что их история сохранится."
                   :de "Aus einem Thema werden mehrere. Materialien und Fragen gehen dorthin, wohin Sie sie schicken; die Versuche der Lernenden folgen ihren Fragen, ihre Geschichte bleibt erhalten."
                   :en "One topic becomes several. Material and questions go where you send them; learners' attempts follow their questions, so their record is kept."}
   :ui/split-add-row {:ru "+ ещё тема" :de "+ weiteres Thema" :en "+ another topic"}
   :ui/split-assign {:ru "Куда что идёт" :de "Was wohin geht" :en "What goes where"}
   :ui/split-inactive-first {:ru "Убранные материалы и снятые вопросы уйдут в первую тему." :de "Entfernte Materialien und zurückgezogene Fragen gehen ins erste Thema." :en "Removed material and retired questions go to the first topic."}
   :ui/split-edges-dropped {:ru "Связи старой темы не переносятся, нарисуйте их заново: " :de "Die Verknüpfungen des alten Themas werden nicht übernommen, zeichnen Sie sie neu: " :en "The old topic's links are not carried over, redraw them: "}
   :ui/split-id-taken {:ru "Тема «{id}» уже есть в графе — назовите иначе." :de "Das Thema „{id}“ gibt es schon — anders benennen." :en "A topic “{id}” already exists — name it differently."}
   :ui/split-dup {:ru "Две новые темы называются одинаково." :de "Zwei neue Themen heißen gleich." :en "Two new topics have the same name."}
   :ui/split-save {:ru "Разделить" :de "Aufteilen" :en "Split"}
   :ui/split-done {:ru "Тема разделена на {n}" :de "Thema in {n} aufgeteilt" :en "Topic split into {n}"}
   :ui/deprecate {:ru "Пометить устаревшей" :de "Als veraltet markieren" :en "Mark deprecated"}
   :ui/deprecated-done {:ru "Тема помечена устаревшей" :de "Thema als veraltet markiert" :en "Topic marked deprecated"}
   :ui/deprecated-note {:ru "Устарела: остаётся на карте и в истории, в курсы больше не публикуется." :de "Veraltet: bleibt auf der Karte und in der Historie, wird nicht mehr in Kurse veröffentlicht." :en "Deprecated: kept on the map and in history, no longer published in courses."}
   :ui/publish-blocked-deprecated {:ru "Устаревшие темы не публикуются: {list}." :de "Veraltete Themen werden nicht veröffentlicht: {list}." :en "Deprecated topics are not published: {list}."}
   :ui/error-bad-split {:ru "Разделение не прошло: {detail}" :de "Aufteilen abgelehnt: {detail}" :en "Split refused: {detail}"}
   :ui/error-missing {:ru "Этого уже нет в графе: {detail}" :de "Das gibt es im Graphen nicht mehr: {detail}" :en "That is no longer in the graph: {detail}"}
   :ui/split-why-fewer-than-two {:ru "нужно хотя бы две темы" :de "mindestens zwei Themen nötig" :en "at least two topics are needed"}
   :ui/split-why-duplicate-ids {:ru "две темы с одним именем" :de "zwei Themen mit demselben Namen" :en "two topics with one name"}
   :ui/split-why-id-taken {:ru "такая тема уже есть" :de "dieses Thema gibt es schon" :en "such a topic already exists"}
   :ui/split-why-blank-title {:ru "у темы нет названия" :de "einem Thema fehlt der Name" :en "a topic has no title"}
   :ui/split-why-unknown-node-type {:ru "тип узла не из онтологии" :de "Knotentyp nicht in der Ontologie" :en "node type not in the ontology"}
   :ui/split-why-item-unassigned {:ru "не каждому вопросу назначена тема" :de "nicht jeder Frage ist ein Thema zugewiesen" :en "not every question has a topic"}
   :ui/split-why-object-unassigned {:ru "не каждому материалу назначена тема" :de "nicht jedem Material ist ein Thema zugewiesen" :en "not every piece of material has a topic"}
   ;; --- the two hats + the course catalog ---
   :ui/hat-author {:ru "Автор" :de "Autor" :en "Author"}
   :ui/hat-learn {:ru "Обучение" :de "Lernen" :en "Learning"}
   :ui/courses {:ru "Курсы" :de "Kurse" :en "Courses"}
   :ui/cat-search {:ru "Поиск по курсам и темам" :de "Kurse und Themen durchsuchen" :en "Search courses and topics"}
   :ui/cat-none {:ru "Опубликованных курсов пока нет. Соберите первый на карте."
                 :de "Noch keine veröffentlichten Kurse. Stellen Sie den ersten auf der Karte zusammen."
                 :en "No published courses yet. Compose the first one on the map."}
   :ui/cat-targets {:ru "целей" :de "Ziele" :en "targets"}
   :ui/cat-state-new {:ru "не начат" :de "nicht begonnen" :en "not started"}
   :ui/cat-state-open {:ru "идёт" :de "läuft" :en "in progress"}
   :ui/cat-state-passed {:ru "сдан" :de "bestanden" :en "passed"}
   :ui/cat-fresh {:ru "{m} тем вместе с пререквизитами. Диагностика вычтет то, что вы уже знаете."
                  :de "{m} Themen samt Voraussetzungen. Die Diagnose zieht ab, was Sie schon können."
                  :en "{m} topics with prerequisites. The diagnostic subtracts what you already know."}
   ;; counted: «на 1 тему», «на 4 темы», «на 5 тем» (learning review 2026-11-20 B3)
   :ui/cat-credit-1 {:ru "Короче на {n} тему: вы уже доказали её в другом курсе. Осталось {left} из {m}."
                     :de "Um {n} Thema kürzer: Sie haben es in einem anderen Kurs bereits bewiesen. Es bleiben {left} von {m}."
                     :en "Shorter by {n} topic: you already proved it in another course. {left} of {m} left."}
   :ui/cat-credit-2 {:ru "Короче на {n} темы: вы уже доказали их в другом курсе. Осталось {left} из {m}."
                     :de "Um {n} Themen kürzer: Sie haben sie in einem anderen Kurs bereits bewiesen. Es bleiben {left} von {m}."
                     :en "Shorter by {n} topics: you already proved them in another course. {left} of {m} left."}
   :ui/cat-credit-5 {:ru "Короче на {n} тем: вы уже доказали их в другом курсе. Осталось {left} из {m}."
                     :de "Um {n} Themen kürzer: Sie haben sie in einem anderen Kurs bereits bewiesen. Es bleiben {left} von {m}."
                     :en "Shorter by {n} topics: you already proved them in another course. {left} of {m} left."}
   :ui/cat-progress {:ru "закрыто {n} из {m} тем · раунд {r}"
                     :de "{n} von {m} Themen geschlossen · Runde {r}"
                     :en "{n} of {m} topics closed · round {r}"}
   :ui/cat-passed {:ru "сдан с результатом {pct}%" :de "bestanden mit {pct}%" :en "passed with {pct}%"}
   :ui/cat-start {:ru "Начать" :de "Starten" :en "Start"}
   :ui/cat-continue {:ru "Продолжить" :de "Fortsetzen" :en "Continue"}
   :ui/cat-threshold {:ru "порог {n}%" :de "Schwelle {n}%" :en "pass mark {n}%"}
   :ui/cat-graph-title {:ru "Граф, в котором живёт курс" :de "Der Graph, in dem der Kurs liegt" :en "The graph this course lives in"}
   :ui/cat-history {:ru "Прошлые сессии" :de "Frühere Sitzungen" :en "Past sessions"}
   :ui/show-tour {:ru "Показать тур" :de "Tour zeigen" :en "Show the tour"}
   ;; --- closed vocabularies (i18n/enum-label) ---
   :ui/level-novice {:ru "новичок" :de "Einsteiger" :en "novice"}
   :ui/level-apprentice {:ru "практик" :de "Fortgeschritten" :en "apprentice"}
   :ui/level-professional {:ru "профессионал" :de "Profi" :en "professional"}
   :ui/okind-explanation {:ru "объяснение" :de "Erklärung" :en "explanation"}
   :ui/okind-worked-example {:ru "разбор примера" :de "durchgerechnetes Beispiel" :en "worked example"}
   :ui/okind-diagram {:ru "схема" :de "Schaubild" :en "diagram"}
   :ui/okind-video {:ru "видео" :de "Video" :en "video"}
   :ui/okind-audio {:ru "аудио" :de "Audio" :en "audio"}
   :ui/okind-interactive {:ru "интерактив" :de "interaktiv" :en "interactive"}
   :ui/okind-reading {:ru "чтение" :de "Lektüre" :en "reading"}
   :ui/ikind-single-choice {:ru "один верный ответ" :de "eine richtige Antwort" :en "single choice"}
   :ui/ikind-multi-choice {:ru "несколько ответов" :de "mehrere Antworten" :en "multiple choice"}
   :ui/ikind-order {:ru "порядок" :de "Reihenfolge" :en "ordering"}
   :ui/ikind-match {:ru "соответствие" :de "Zuordnung" :en "matching"}
   :ui/ikind-cloze {:ru "пропуск в тексте" :de "Lückentext" :en "fill the blank"}
   :ui/ikind-numeric {:ru "число" :de "Zahl" :en "numeric"}
   :ui/ikind-scenario {:ru "сценарий" :de "Szenario" :en "scenario"}
   :ui/ikind-explain-back {:ru "объясните сами" :de "selbst erklären" :en "explain back"}
   :ui/ikind-exercise {:ru "упражнение с запуском" :de "Übung mit Ausführung" :en "runnable exercise"}
   :ui/exercise-solution {:ru "Эталонное решение" :de "Musterlösung" :en "Reference solution"}
   :ui/exercise-check-output {:ru "сверить вывод с эталоном" :de "Ausgabe mit der Musterlösung vergleichen" :en "compare output with the reference"}
   :ui/exercise-check-assert {:ru "скрытые проверки (assert)" :de "versteckte Prüfungen (assert)" :en "hidden checks (assert)"}
   :ui/exercise-py-setup-placeholder {:ru "Подготовка (необязательно): данные, импорты — выполняется перед кодом ученика" :de "Vorbereitung (optional): Daten, Importe — läuft vor dem Code des Lernenden" :en "Setup (optional): data, imports — runs before the learner's code"}
   :ui/exercise-test-placeholder {:ru "Скрытые проверки: assert … — ученик их не видит" :de "Versteckte Prüfungen: assert … — der Lernende sieht sie nicht" :en "Hidden checks: assert … — the learner never sees them"}
   :ui/exercise-no-output {:ru "Эталонное решение ничего не выводит — сверять не с чем." :de "Die Musterlösung gibt nichts aus — es gibt nichts zu vergleichen." :en "The reference solution prints nothing, so there is nothing to compare."}
   :ui/exercise-accepts-wrong {:ru "Принимает неверный ответ: " :de "Akzeptiert eine falsche Antwort: " :en "Accepts a wrong answer: "}
   :ui/budget-spent {:ru "Дневной лимит токенов исчерпан ({used} из {budget}) — запрос не отправлен. Поднимите лимит в Настройках или продолжите завтра." :de "Das Tageslimit an Tokens ist erreicht ({used} von {budget}) — die Anfrage wurde nicht gesendet. Erhöhen Sie das Limit in den Einstellungen oder machen Sie morgen weiter." :en "Today's token budget is spent ({used} of {budget}) — the request was not sent. Raise the budget in Settings or continue tomorrow."}
   :ui/budget-unrecorded {:ru "Расход не удаётся записать — хранилище браузера заполнено. Освободите место (удалите ненужный граф) — до тех пор вызовы модели остановлены." :de "Der Verbrauch lässt sich nicht speichern — der Browserspeicher ist voll. Schaffen Sie Platz (löschen Sie einen nicht benötigten Graphen) — bis dahin sind Modellaufrufe gestoppt." :en "Usage cannot be recorded — the browser storage is full. Free some space (delete a graph you do not need); until then model calls are stopped."}
   :ui/graph-recovered {:ru "Восстановленный граф" :de "Wiederhergestellter Graph" :en "Recovered graph"}
   :ui/budget-corrupt {:ru "Запись лимита токенов на этом устройстве повреждена, поэтому лимит считается исчерпанным — запрос не отправлен. Сохраните лимит в Настройках; повреждённый счёт дня также обнулится в следующие сутки UTC." :de "Der Token-Limit-Eintrag auf diesem Gerät ist beschädigt, daher gilt das Limit als erreicht — die Anfrage wurde nicht gesendet. Speichern Sie ein Limit in den Einstellungen; ein beschädigter Tageszähler setzt sich außerdem am nächsten UTC-Tag zurück." :en "The token budget record on this device is damaged, so the budget counts as spent — the request was not sent. Save a budget in Settings; a damaged day's count also clears at the next UTC day."}
   :ui/budget-heading {:ru "Лимит токенов в день" :de "Token-Limit pro Tag" :en "Tokens per day"}
   :ui/budget-lead {:ru "Ключ ваш — и счёт ваш. Запрос, который может превысить лимит, не отправляется." :de "Der Schlüssel ist Ihrer — die Rechnung auch. Eine Anfrage, die das Limit überschreiten könnte, wird nicht gesendet." :en "The key is yours, and so is the bill. A request that could cross the budget is not sent."}
   :ui/budget-usage {:ru "Сегодня: {used} из {budget} токенов · запросов: {calls}" :de "Heute: {used} von {budget} Tokens · Anfragen: {calls}" :en "Today: {used} of {budget} tokens · requests: {calls}"}
   :ui/exercise-empty-passes {:ru "Пустой ответ уже даёт ожидаемый вывод — его печатает подготовка. Тогда засчитается что угодно: пусть результат печатает решение." :de "Eine leere Antwort ergibt schon die erwartete Ausgabe — die Vorbereitung druckt sie. Dann zählt alles: Lassen Sie die Lösung das Ergebnis ausgeben." :en "An empty answer already produces the expected output — the setup prints it. Then anything would pass: let the solution print the result."}
   :ui/exercise-vacuous {:ru "Скрытые проверки проходят и без ответа — тогда засчитается что угодно. Проверьте то, что должен написать ученик." :de "Die versteckten Prüfungen bestehen auch ohne Antwort — dann zählt alles. Prüfen Sie, was der Lernende schreiben soll." :en "The hidden checks pass with no answer at all, so anything would count. Check what the learner has to write."}
   :ui/exercise-task-placeholder {:ru "Задача: что должен вернуть запрос" :de "Aufgabe: was die Abfrage liefern soll" :en "Task: what the query should return"}
   :ui/exercise-setup-placeholder {:ru "Данные (SQL): create table …; insert into …" :de "Daten (SQL): create table …; insert into …" :en "Data (SQL): create table …; insert into …"}
   :ui/exercise-ordered {:ru "Порядок строк важен (задача просит сортировку)" :de "Zeilenreihenfolge zählt (die Aufgabe verlangt eine Sortierung)" :en "Row order matters (the task asks for a sort)"}
   :ui/exercise-checking {:ru "Проверяем решение…" :de "Lösung wird geprüft…" :en "Checking the solution…"}
   :ui/exercise-setup-failed {:ru "Подготовка не выполняется: " :de "Die Vorbereitung läuft nicht: " :en "The setup does not run: "}
   :ui/exercise-solution-failed {:ru "Решение не выполняется: " :de "Die Lösung läuft nicht: " :en "The solution does not run: "}
   :ui/exercise-empty {:ru "Решение возвращает пустой результат — тогда засчитается любой запрос, который ничего не вернёт. Добавьте данные или измените задачу." :de "Die Lösung liefert kein Ergebnis — dann zählt jede Abfrage, die nichts liefert. Ergänzen Sie Daten oder ändern Sie die Aufgabe." :en "The solution returns no rows, so any query that returns nothing would pass. Add data or change the task."}
   :ui/ename-requires {:ru "требует" :de "erfordert" :en "requires"}
   :ui/ename-generalizes {:ru "обобщает" :de "verallgemeinert" :en "generalizes"}
   :ui/ename-is-confused-with {:ru "путают с" :de "verwechselt mit" :en "confused with"}
   :ui/ename-part-of {:ru "часть" :de "Teil von" :en "part of"}
   :ui/ename-see-also {:ru "см. также" :de "siehe auch" :en "see also"}
   :ui/ckind-fact {:ru "факт" :de "Fakt" :en "fact"}
   :ui/ckind-definition {:ru "определение" :de "Definition" :en "definition"}
   :ui/ckind-procedure {:ru "процедура" :de "Verfahren" :en "procedure"}
   :ui/ckind-principle {:ru "принцип" :de "Prinzip" :en "principle"}
   :ui/ckind-judgment {:ru "суждение" :de "Urteil" :en "judgment"}
   ;; --- tours (tour.cljs) ---
   :tour/next {:ru "Дальше" :de "Weiter" :en "Next"}
   :tour/prev {:ru "Назад" :de "Zurück" :en "Back"}
   :tour/done {:ru "Готово" :de "Fertig" :en "Done"}
   :ui/tracker-open {:ru "Какие темы ещё не готовы" :de "Welche Themen noch nicht bereit sind" :en "Which topics are not ready yet"}
   :ui/tracker-missing {:ru "Ещё не готовы к публикации:" :de "Noch nicht veröffentlichbar:" :en "Not ready to publish yet:"}
   :learn/intro-length {:ru "Диагностика — до {n} вопросов, по одному на тему; обычно несколько минут." :de "Die Diagnose: bis zu {n} Fragen, eine pro Thema; meist ein paar Minuten." :en "The diagnostic: up to {n} questions, one per topic; usually a few minutes."}
   :ui/layout-to-free {:ru "Свободная раскладка" :de "Freie Anordnung" :en "Free layout"}
   :ui/layout-to-layers {:ru "Раскладка по пререквизитам: основы слева" :de "Nach Voraussetzungen: Grundlagen links" :en "Prerequisite layout: foundations on the left"}
   :ui/olink-example-of {:ru "пример к" :de "Beispiel zu" :en "example of"}
   :ui/olink-simpler-of {:ru "проще, чем" :de "einfacher als" :en "simpler version of"}
   :ui/olink-follows {:ru "читать после" :de "lesen nach" :en "read after"}
   :ui/link-add {:ru "связать с другим материалом" :de "mit anderem Material verknüpfen" :en "link to other material"}
   :ui/link-save {:ru "Связать" :de "Verknüpfen" :en "Link"}
   :learn/simpler {:ru "Объяснить проще" :de "Einfacher erklären" :en "Explain more simply"}
   :learn/simpler-hide {:ru "Скрыть упрощённое" :de "Vereinfachung ausblenden" :en "Hide the simpler version"}
   :learn/example-tag {:ru "пример к материалу выше" :de "Beispiel zum Material oben" :en "example of the material above"}
   :ui/legend {:ru "Что значат цвета и значки" :de "Was Farben und Symbole bedeuten" :en "What the colours and glyphs mean"}
   :tour/progress {:ru "{{current}} из {{total}}" :de "{{current}} von {{total}}" :en "{{current}} of {{total}}"}
   :tour/map-t {:ru "Карта знаний" :de "Die Wissenskarte" :en "The knowledge map"}
   :tour/map {:ru "Каждый круг — одна маленькая проверяемая тема; стрелка ведёт к её пререквизиту. Тяните темы, колесо и щипок — масштаб, пустое место — панорама. Один тап выделяет тему, второй тап по выделенной — открывает её."
              :de "Jeder Kreis ist ein kleines, prüfbares Thema; der Pfeil zeigt auf seine Voraussetzung. Themen ziehen, Rad und Pinch zoomen, freie Fläche schwenkt. Ein Tipp wählt ein Thema aus, ein zweiter auf das ausgewählte öffnet es."
              :en "Every circle is one small testable topic; the arrow points at its prerequisite. Drag topics, wheel and pinch zoom, empty space pans. One tap selects a topic, a second tap on the selected one opens it."}
   :tour/legend-t {:ru "Цвета и значки" :de "Farben und Symbole" :en "Colours and glyphs"}
   :tour/legend {:ru "Зелёная тема с листом — готова к публикации (1 объект + 3 вопроса). Коричневая с карандашом — принята, наполняется. С плюсом — пока пустая. Янтарная с часами — источник изменился, нужна проверка. Пунктирное кольцо — предложение ассистента, ещё не принято."
                 :de "Grün mit Blatt: veröffentlichbar (1 Objekt + 3 Fragen). Braun mit Stift: angenommen, wird gefüllt. Mit Plus: noch leer. Bernstein mit Uhr: Quelle geändert, Prüfung nötig. Gestrichelter Ring: Vorschlag des Assistenten, noch nicht angenommen."
                 :en "Green with a sheet: ready to publish (1 object + 3 questions). Brown with a pencil: accepted, being filled. With a plus: still empty. Amber with a clock: its source changed, needs review. Dashed ring: an assistant proposal, not yet accepted."}
   :tour/course-t {:ru "Курс" :de "Kurs" :en "Course"}
   :tour/course {:ru "Соберите курс из целей с уровнями — и пройдите его сами кнопкой «Пройти курс»." :de "Stellen Sie einen Kurs aus Zielen mit Niveaus zusammen — und absolvieren Sie ihn selbst." :en "Compose a course from targets with levels — and take it yourself from the course panel."}
   :tour/chat-t {:ru "Ассистент" :de "Assistent" :en "Assistant"}
   :tour/chat {:ru "Бросьте текст или PDF — ассистент предложит темы и связи карточками. Ничего не попадает в граф без вашего принятия." :de "Text oder PDF hineingeben — der Assistent schlägt Themen und Verknüpfungen als Karten vor. Nichts landet ohne Ihre Annahme im Graphen." :en "Drop text or a PDF — the assistant proposes topics and links as cards. Nothing enters the graph without your accept."}
   :tour/hats-t {:ru "Две роли" :de "Zwei Rollen" :en "Two hats"}
   :tour/hats {:ru "«Автор» — карта, где курсы создают. «Обучение» — каталог, где их проходят: прогресс, поиск по темам и ваши прошлые сессии."
               :de "„Autor“ ist die Karte, auf der Kurse entstehen. „Lernen“ ist der Katalog, in dem man sie absolviert: Fortschritt, Themensuche und Ihre früheren Sitzungen."
               :en "“Author” is the map where courses are made. “Learning” is the catalog where they are taken: progress, topic search and your past sessions."}
   ;; --- intake-cards tour ---
   :ctour/cards-t {:ru "Карточки-предложения" :de "Vorschlagskarten" :en "Proposal cards"}
   :ctour/cards {:ru "Ассистент ничего не добавляет сам: каждая тема, связь и вопрос — карточка. На карте они видны пунктирными призраками." :de "Der Assistent fügt nichts selbst hinzu: jedes Thema, jede Verknüpfung, jede Frage ist eine Karte. Auf der Karte erscheinen sie als gestrichelte Geister." :en "The assistant adds nothing itself: every topic, link and question is a card. On the map they appear as dashed ghosts."}
   :ctour/accept-t {:ru "Принять или отклонить" :de "Annehmen oder verwerfen" :en "Accept or discard"}
   :ctour/accept {:ru "Принятое становится частью графа; отклонённое не вернётся при повторной загрузке того же материала." :de "Angenommenes wird Teil des Graphen; Verworfenes kommt beim erneuten Einlesen desselben Materials nicht wieder." :en "Accepted becomes part of the graph; discarded will not come back when the same material is ingested again."}
   :ctour/more-t {:ru "Продолжайте" :de "Weiter so" :en "Keep going"}
   :ctour/more {:ru "Скормите следующий документ или спросите — контекст беседы сохраняется между визитами." :de "Das nächste Dokument hineingeben oder nachfragen — der Gesprächskontext bleibt über Besuche erhalten." :en "Feed the next document or ask a question — the conversation is kept across visits."}
   ;; --- learn tour ---
   :ltour/feed-t {:ru "Учебная лента" :de "Der Lernfeed" :en "The learning feed"}
   :ltour/feed {:ru "Курс идёт лентой: рассказ, материалы и карточки с вопросами. Всё пройденное остаётся выше." :de "Der Kurs läuft als Feed: Erzählung, Materialien und Fragekarten. Alles Erledigte bleibt oben stehen." :en "The course runs as a feed: narration, materials and question cards. Everything done stays above."}
   :ltour/check-t {:ru "Вопрос" :de "Frage" :en "Question"}
   :ltour/check {:ru "Живая карточка всегда последняя; отвеченные гаснут, но остаются в ленте." :de "Die aktive Karte ist immer die letzte; beantwortete werden grau, bleiben aber im Feed." :en "The live card is always the last one; answered cards grey out but stay in the feed."}
   :ltour/locked-t {:ru "Проверки — без ассистента" :de "Prüfungen ohne Assistent" :en "Checks without the assistant"}
   :ltour/locked {:ru "В диагностике и на итоговой ассистент выключен — это ваша работа. Между проверками он открыт." :de "In Diagnose und Abschlussprüfung ist der Assistent aus — das ist Ihre Arbeit. Zwischen den Prüfungen ist er offen." :en "In the diagnostic and the final the assistant is off — this is your own work. Between checks it is open."}
   :ltour/tutor-t {:ru "Репетитор" :de "Der Tutor" :en "The tutor"}
   :ltour/tutor {:ru "На шагах обучения спрашивайте здесь: объяснит иначе, приведёт пример. Ответы на проверку не выдаёт." :de "Bei den Lernschritten hier fragen: erklärt anders, bringt Beispiele. Die Prüfungsantworten verrät er nicht." :en "During learning steps ask here: it explains another way, gives examples. It never gives away the check."}
   :ltour/restart-t {:ru "Начать заново" :de "Neu beginnen" :en "Start over"}
   :ltour/restart {:ru "Текущая сессия уйдёт в историю — любую прошлую можно открыть из поиска (Ctrl+K)." :de "Die aktuelle Sitzung wandert in die Historie — jede frühere lässt sich über die Suche (Strg+K) öffnen." :en "The current session goes to history — reopen any past one from search (Ctrl+K)."}
   :ui/republish {:ru "Издать обновление ({n})" :de "Update veröffentlichen ({n})" :en "Publish update ({n})"}
   :ui/republish-none {:ru "Обновлений нет" :de "Keine Änderungen" :en "No changes to publish"}
   :ui/republish-hint {:ru "{n} изменений после публикации. Пока они не изданы, учащиеся видят прежнюю версию."
                       :de "{n} Änderungen seit der Veröffentlichung. Bis sie erscheinen, sehen Lernende die alte Fassung."
                       :en "{n} changes since publishing. Until they are released, learners see the previous version."}
   :ui/republished {:ru "Издано: {n} изменений теперь видны новым учащимся"
                    :de "Veröffentlicht: {n} Änderungen sind für neue Lernende sichtbar"
                    :en "Published: {n} changes are now visible to new learners"}
   :ui/take-course {:ru "Пройти курс" :de "Kurs absolvieren" :en "Take the course"}
   ;; --- the learner loop (learn.cljs) ---
   :learn/intro {:ru "Сначала короткая диагностика по {n} целям курса и их пререквизитам. Потом — только то, чего не хватает, и итоговая проверка."
                 :de "Zuerst eine kurze Diagnose über die {n} Kursziele und ihre Voraussetzungen. Dann nur das, was fehlt — und die Abschlussprüfung."
                 :en "First a short diagnostic over the course's {n} targets and their prerequisites. Then only what is missing, and a final check."}
   :learn/start {:ru "Начать" :de "Starten" :en "Start"}
   :learn/round {:ru "раунд {n}" :de "Runde {n}" :en "round {n}"}
   ;; the folded feed and the header's progress (learning review 2026-11-20 D1)
   :learn/fold-diagnostic {:ru "Диагностика · верно {ok} из {n}" :de "Diagnose · {ok} von {n} richtig" :en "Diagnostic · {ok} of {n} right"}
   :learn/fold-plan-1 {:ru "План · {n} тема" :de "Plan · {n} Thema" :en "Plan · {n} topic"}
   :learn/fold-plan-2 {:ru "План · {n} темы" :de "Plan · {n} Themen" :en "Plan · {n} topics"}
   :learn/fold-plan-5 {:ru "План · {n} тем" :de "Plan · {n} Themen" :en "Plan · {n} topics"}
   :learn/fold-topic {:ru "{title} · верно {ok} из {n}" :de "{title} · {ok} von {n} richtig" :en "{title} · {ok} of {n} right"}
   :learn/fold-final-passed {:ru "Итоговая проверка · {ok} из {n} ({pct}%) — сдано" :de "Abschlussprüfung · {ok} von {n} ({pct} %) — bestanden" :en "Final check · {ok} of {n} ({pct}%) — passed"}
   :learn/fold-final-failed {:ru "Итоговая проверка · {ok} из {n} ({pct}%) — не сдано" :de "Abschlussprüfung · {ok} von {n} ({pct} %) — nicht bestanden" :en "Final check · {ok} of {n} ({pct}%) — not passed"}
   :learn/fold-final-open {:ru "Итоговая проверка" :de "Abschlussprüfung" :en "Final check"}
   :learn/intro-targets {:ru "Что в курсе" :de "Was der Kurs umfasst" :en "What the course covers"}
   :learn/restart-yes {:ru "Начать заново" :de "Neu beginnen" :en "Start over"}
   :learn/head-diagnostic {:ru "диагностика" :de "Diagnose" :en "diagnostic"}
   :learn/head-plan {:ru "план" :de "Plan" :en "plan"}
   :learn/head-learn {:ru "тема {n} из {m}" :de "Thema {n} von {m}" :en "topic {n} of {m}"}
   :learn/head-final {:ru "итоговая проверка" :de "Abschlussprüfung" :en "final check"}
   :learn/head-result {:ru "итог" :de "Ergebnis" :en "result"}
   :learn/abandon {:ru "Оставить этот прогон" :de "Diesen Durchlauf verlassen" :en "Leave this run"}
   :learn/restart {:ru "Начать заново" :de "Neu beginnen" :en "Start over"}
   :learn/restart-confirm {:ru "Начать курс заново? Текущая сессия останется в истории, ответы — в журнале."
                           :de "Kurs neu beginnen? Die aktuelle Sitzung bleibt in der Historie, die Antworten im Protokoll."
                           :en "Start the course over? The current session stays in history, the answers in the log."}
   ;; a run pinned to an older publication, while the author retired topics in it
   :learn/retired-note {:ru "Автор с тех пор вывел из обращения: {list}. Вы проходите версию курса, на которой начали; когда выйдет новая, следующий раунд начнётся по ней."
                        :de "Der Autor hat inzwischen ausgemustert: {list}. Sie lernen die Kursversion, mit der Sie begonnen haben; erscheint eine neue, beginnt die nächste Runde mit ihr."
                        :en "The author has since retired: {list}. You are on the course version you started with; once a new one is published, your next round starts on it."}
   :learn/retired-newer {:ru "Автор с тех пор вывел из обращения: {list} — и опубликовал новую версию курса. Этот раунд идёт по версии, на которой вы начали; следующий (или «Начать заново» сейчас) — по новой."
                         :de "Der Autor hat inzwischen ausgemustert: {list} — und eine neue Kursversion veröffentlicht. Diese Runde bleibt auf der Version, mit der Sie begonnen haben; die nächste (oder „Neu beginnen“ jetzt) läuft auf der neuen."
                         :en "The author has since retired: {list} — and published a new version of the course. This round stays on the version you started with; the next one (or “Start over” now) runs on the new one."}
   :learn/topic-closed {:ru "Тема «{title}» закрыта." :de "Thema „{title}“ abgeschlossen." :en "Topic “{title}” closed."}
   :learn/session-word {:ru "учёба" :de "Lernen" :en "learning"}
   :learn/session-status-open {:ru "идёт" :de "läuft" :en "in progress"}
   :learn/session-status-passed {:ru "сдано" :de "bestanden" :en "passed"}
   :learn/session-status-failed {:ru "не сдано" :de "nicht bestanden" :en "not passed"}
   :learn/diagnostic-t {:ru "Диагностика" :de "Diagnose" :en "Diagnostic"}
   :learn/diagnostic-lead {:ru "Без подсказок и без обратной связи — так видно, что вы уже знаете. Ошибка — не беда: это ровно то, что войдёт в ваш курс."
                           :de "Ohne Hinweise und ohne Rückmeldung — so zeigt sich, was Sie schon können. Ein Fehler ist kein Problem: genau das kommt in Ihren Kurs."
                           :en "No hints and no feedback — that is how we see what you already know. A miss is fine: it is exactly what goes into your course."}
   :learn/progress {:ru "{n} из {m}" :de "{n} von {m}" :en "{n} of {m}"}
   :learn/cloze-placeholder {:ru "Ваш ответ" :de "Ihre Antwort" :en "Your answer"}
   :learn/answer {:ru "Ответить" :de "Antworten" :en "Answer"}
   :learn/ex-run {:ru "Выполнить" :de "Ausführen" :en "Run"}
   :learn/ex-run-hint {:ru "Посмотреть, что выдаст ваш код (Ctrl+Enter). Это не ответ." :de "Ansehen, was Ihr Code ausgibt (Strg+Enter). Das ist keine Antwort." :en "See what your code produces (Ctrl+Enter). This is not an answer."}
   :learn/scratch-open {:ru "Песочница · {rt}" :de "Sandkasten · {rt}" :en "Scratchpad · {rt}"}
   :learn/scratch-t {:ru "Песочница · {rt}" :de "Sandkasten · {rt}" :en "Scratchpad · {rt}"}
   :learn/scratch-lead {:ru "пробуйте что угодно — это не проверка и никуда не записывается" :de "probieren Sie alles aus — das ist keine Prüfung und wird nirgends festgehalten" :en "try anything — it is not a check and nothing is recorded"}
   :learn/scratch-close {:ru "Закрыть песочницу" :de "Sandkasten schließen" :en "Close the scratchpad"}
   :learn/scratch-ask {:ru "Спросить репетитора, почему не работает" :de "Den Tutor fragen, warum es nicht geht" :en "Ask the tutor why it fails"}
   :learn/scratch-ask-text {:ru "Почему этот код не работает?\n{code}\n\nРезультат:\n{out}" :de "Warum funktioniert dieser Code nicht?\n{code}\n\nErgebnis:\n{out}" :en "Why does this code fail?\n{code}\n\nResult:\n{out}"}
   :learn/mode-tutor {:ru "режим репетитора · в рамках курса" :de "Tutormodus · im Rahmen des Kurses" :en "tutor mode · within the course"}
   :learn/tutor-looked {:ru "🔎 репетитор смотрит: {name} {args}" :de "🔎 der Tutor sieht nach: {name} {args}" :en "🔎 the tutor looks up: {name} {args}"}
   :learn/tutor-empty {:ru "Репетитор ничего не ответил — спросите ещё раз." :de "Der Tutor hat nichts geantwortet — fragen Sie noch einmal." :en "The tutor gave no answer — ask again."}
   :learn/tutor-tool-limit {:ru "Репетитор долго искал и не успел ответить — спросите иначе или короче." :de "Der Tutor hat lange gesucht und nicht geantwortet — fragen Sie anders oder kürzer." :en "The tutor kept looking and did not answer — ask differently or more briefly."}
   :learn/marks-toggle {:ru "Подсветка сути и примеров" :de "Kern und Beispiele hervorheben" :en "Highlight the essence and examples"}
   :learn/marks-essence {:ru "суть" :de "Kern" :en "essence"}
   :learn/marks-example {:ru "пример" :de "Beispiel" :en "example"}
   :ui/marks-hint {:ru "Разметка для ученика: ==суть== — одно главное предложение, ++пример++." :de "Markierung für Lernende: ==Kern== — ein zentraler Satz, ++Beispiel++." :en "Reader marks: ==essence== — one key sentence, ++example++."}
   :learn/no-items-left {:ru "У темы «{title}» не осталось вопросов, которые можно задать честно, — она отмечена как непроверенная. Идём дальше." :de "Für „{title}“ ist keine Frage übrig, die sich ehrlich stellen lässt — das Thema gilt als ungeprüft. Weiter." :en "“{title}” has no question left that can be asked honestly — it is marked untested. Moving on."}
   :learn/ex-no-output {:ru "Код ничего не вывел." :de "Der Code hat nichts ausgegeben." :en "The code printed nothing."}
   :learn/ex-timeout {:ru "Код работал дольше 10 секунд и был остановлен — возможно, бесконечный цикл." :de "Der Code lief länger als 10 Sekunden und wurde gestoppt — vielleicht eine Endlosschleife." :en "The code ran longer than 10 seconds and was stopped — maybe an endless loop."}
   :learn/ex-empty {:ru "Запрос вернул пустой результат." :de "Die Abfrage lieferte ein leeres Ergebnis." :en "The query returned no rows."}
   :learn/ex-more {:ru "Показаны первые 20 из {n} строк." :de "Die ersten 20 von {n} Zeilen." :en "Showing the first 20 of {n} rows."}
   :learn/ex-no-tx {:ru "Управлять транзакцией здесь нельзя: каждый запуск идёт на чистых данных." :de "Transaktionssteuerung ist hier nicht erlaubt: jeder Lauf beginnt mit frischen Daten." :en "Transaction control is not allowed here: every run starts from fresh data."}
   :learn/ex-engine-failed {:ru "Среда выполнения в браузере не запустилась: " :de "Die Laufzeitumgebung im Browser ist nicht gestartet: " :en "The in-browser runtime did not start: "}
   :learn/plan-t {:ru "Ваш курс: {n} из {m} тем" :de "Ihr Kurs: {n} von {m} Themen" :en "Your course: {n} of {m} topics"}
   :learn/plan-lead {:ru "Только пробелы, в порядке пререквизитов."
                     :de "Nur die Lücken, in Reihenfolge der Voraussetzungen."
                     :en "Only the gaps, prerequisites first."}
   :learn/untested-list {:ru "Пропущено, вопросов пока нет: {list}. Эти темы никто не проверял — и пройденными мы их не считаем."
                         :de "Übersprungen, es gibt noch keine Fragen: {list}. Diese Themen wurden nicht geprüft — und gelten nicht als gekonnt."
                         :en "Skipped, no questions yet: {list}. Nobody checked these, and we do not count them as known."}
   :learn/unexamined-one {:ru "Одну цель курса проверить нечем — по ней не осталось вопросов, которых вы ещё не видели."
                          :de "Ein Kursziel lässt sich nicht prüfen — dafür ist keine Frage übrig, die Sie noch nicht gesehen haben."
                          :en "One course target cannot be checked: no question you have not already seen is left for it."}
   :learn/unexamined-few {:ru "{n} цели курса проверить нечем — по ним не осталось вопросов, которых вы ещё не видели."
                          :de "{n} Kursziele lassen sich nicht prüfen — dafür ist keine Frage übrig, die Sie noch nicht gesehen haben."
                          :en "{n} course targets cannot be checked: no question you have not already seen is left for them."}
   :learn/unexamined-many {:ru "{n} целей курса проверить нечем — по ним не осталось вопросов, которых вы ещё не видели."
                           :de "{n} Kursziele lassen sich nicht prüfen — dafür ist keine Frage übrig, die Sie noch nicht gesehen haben."
                           :en "{n} course targets cannot be checked: no question you have not already seen is left for them."}
   :learn/unexamined-result {:ru "Не проверено целей: {n} — {list}. Для них не осталось вопросов, которых вы ещё не видели, поэтому курс не может считаться сданным."
                             :de "Nicht geprüfte Ziele: {n} — {list}. Für sie ist keine ungesehene Frage übrig, daher kann der Kurs nicht als bestanden gelten."
                             :en "Targets not examined: {n} — {list}. No question you had not already seen was left for them, so the course cannot count as passed."}
   :learn/self-checked {:ru "Самопроверка: курс собран из вашего материала, вопросы проверяли вы сами. Прогресс засчитывается только вам и не служит подтверждением."
                        :de "Selbstprüfung: Der Kurs entstand aus Ihrem Material, die Fragen haben Sie selbst geprüft. Der Fortschritt zählt nur für Sie und ist kein Nachweis."
                        :en "Self-checked: this course was built from your material and you reviewed its questions yourself. Progress counts for you only and certifies nothing."}
   :ui/cat-self-checked {:ru "самопроверка" :de "Selbstprüfung" :en "self-checked"}
   :learn/plan-lead-again {:ru "Новый раунд: только то, что не сошлось на проверке."
                           :de "Neue Runde: nur das, was in der Prüfung nicht saß."
                           :en "New round: only what did not hold in the final."}
   :learn/known-list {:ru "Уже знаете: {list}" :de "Sie können schon: {list}" :en "Already known: {list}"}
   :learn/start-learning {:ru "Учиться" :de "Lernen" :en "Learn"}
   :learn/learn-t {:ru "Тема {n} из {m}" :de "Thema {n} von {m}" :en "Topic {n} of {m}"}
   :learn/check-t {:ru "Проверка" :de "Prüfung" :en "Check"}
   :learn/check-me {:ru "Проверить себя" :de "Mich prüfen" :en "Check me"}
   :learn/why-fallback {:ru "Верный ответ: {a}." :de "Richtige Antwort: {a}." :en "The correct answer is {a}."}
   :learn/ex-solution-was {:ru "Эталонное решение:" :de "Musterlösung:" :en "Reference solution:"}
   :learn/tutor-during-check {:ru "На время проверки репетитор выключен — ответ должен быть ваш. Сразу после ответа он вернётся."
                              :de "Während der Prüfung ist der Tutor aus — die Antwort muss Ihre sein. Gleich danach ist er wieder da."
                              :en "The tutor is off while the question is live — the answer must be yours. It returns as soon as you answer."}
   :learn/check-lead {:ru "Готовы? Два верных ответа подряд закрывают тему." :de "Zwei richtige Antworten in Folge schließen das Thema." :en "Two correct in a row close the topic."}
   :learn/right {:ru "Верно" :de "Richtig" :en "Correct"}
   :learn/wrong {:ru "Неверно" :de "Falsch" :en "Wrong"}
   :learn/streak {:ru "подряд: {n}" :de "in Folge: {n}" :en "in a row: {n}"}
   :learn/next {:ru "Дальше" :de "Weiter" :en "Next"}
   :learn/final-t {:ru "Итоговая проверка" :de "Abschlussprüfung" :en "Final check"}
   :learn/final-lead {:ru "По одному вопросу на каждую цель курса, без подсказок. Порог — {n}%."
                      :de "Eine Frage pro Kursziel, ohne Hinweise. Schwelle: {n}%."
                      :en "One question per course target, no hints. Threshold: {n}%."}
   :learn/start-final {:ru "Начать проверку" :de "Prüfung starten" :en "Start the check"}
   :learn/assistant-locked {:ru "Ассистент выключен: это ваша работа. Он вернётся сразу после проверки."
                            :de "Der Assistent ist aus: das ist Ihre Arbeit. Gleich nach der Prüfung ist er wieder da."
                            :en "The assistant is off: this is your own work. It returns right after the check."}
   :learn/passed {:ru "Сдано" :de "Bestanden" :en "Passed"}
   :learn/failed {:ru "Пока не сдано" :de "Noch nicht bestanden" :en "Not passed yet"}
   :learn/score {:ru "{ok} из {n} верно — {pct}% при пороге {t}%" :de "{ok} von {n} richtig — {pct}% bei Schwelle {t}%" :en "{ok} of {n} correct — {pct}% against a {t}% threshold"}
   :learn/debrief-t {:ru "Разбор ошибок" :de "Fehleranalyse" :en "Debrief"}
   :learn/correct-was {:ru "Верный ответ: {a}" :de "Richtige Antwort: {a}" :en "Correct answer: {a}"}
   :learn/done {:ru "Все курсы" :de "Alle Kurse" :en "All courses"}
   :learn/recap {:ru "Теперь вы умеете: {list}." :de "Jetzt können Sie: {list}." :en "You can now: {list}."}
   :learn/tutor-t {:ru "Спросить репетитора" :de "Den Tutor fragen" :en "Ask the tutor"}
   :learn/tutor-lead {:ru "Объяснит иначе, приведёт пример, ответит на вопрос по теме. Ответы на проверку не выдаёт."
                      :de "Erklärt anders, bringt ein Beispiel, beantwortet Fragen zum Thema. Die Prüfungsantworten verrät er nicht."
                      :en "Explains another way, gives an example, answers questions on the topic. It will not give away the check."}
   :learn/tutor-nokey {:ru "Репетитор работает с вашим ключом модели — задайте его в настройках:"
                       :de "Der Tutor arbeitet mit Ihrem Modellschlüssel — in den Einstellungen eintragen:"
                       :en "The tutor runs on your own model key — set it in settings:"}
   :learn/tutor-placeholder {:ru "Не понял(а)… объясни на примере" :de "Ich verstehe nicht … erkläre es an einem Beispiel" :en "I don't get it… explain with an example"}
   :learn/tutor-send {:ru "Спросить" :de "Fragen" :en "Ask"}
   :learn/tutor-thinking {:ru "…думаю…" :de "…denke nach…" :en "…thinking…"}
   :learn/tutor-error {:ru "Ошибка: " :de "Fehler: " :en "Error: "}
   :learn/next-round {:ru "Раунд {n}: закрыть пробелы" :de "Runde {n}: Lücken schließen" :en "Round {n}: close the gaps"}
   :ui/ego {:ru "Окружение выбранной темы" :de "Umfeld des gewählten Themas" :en "Neighbourhood of the selected topic"}
   :ui/tidy {:ru "Разложить по кругам от выбранной темы" :de "Ringförmig um das gewählte Thema anordnen" :en "Rearrange in rings around the selected topic"}
   :ui/material-change {:ru "Смысл изменился — зависимые темы на проверку"
                        :de "Bedeutung geändert — abhängige Themen zur Prüfung"
                        :en "Meaning changed — flag dependent topics for review"}

   ;; edge-type chooser
   :ui/edge-requires {:ru "requires — нельзя понять без"
                       :de "requires — kann ohne das nicht verstanden werden"
                       :en "requires — can't be understood without"}
   :ui/edge-generalizes {:ru "generalizes — обобщает" :de "generalizes — verallgemeinert" :en "generalizes — generalizes"}
   :ui/edge-confused {:ru "is-confused-with — путают"
                       :de "is-confused-with — wird verwechselt mit"
                       :en "is-confused-with — often confused with"}
   :ui/edge-part-of {:ru "part-of — раздел темы" :de "part-of — Abschnitt des Themas" :en "part-of — section of the topic"}
   :ui/edge-see-also {:ru "see-also — смотри также" :de "see-also — siehe auch" :en "see-also — see also"}
   :ui/link-added {:ru "Связь добавлена" :de "Verknüpfung hinzugefügt" :en "Link added"}

   ;; add-object-form
   :ui/learning-object {:ru "учебный объект" :de "Lernobjekt" :en "learning object"}
   :ui/audience-placeholder {:ru "Для кого (роли через запятую; пусто = для всех)"
                              :de "Für wen (Rollen durch Komma getrennt; leer = für alle)"
                              :en "For whom (roles comma-separated; empty = for everyone)"}
   :ui/no-source {:ru "без источника" :de "ohne Quelle" :en "no source"}
   :ui/anchor-placeholder {:ru "p12 / раздел" :de "S. 12 / Abschnitt" :en "p12 / section"}
   :ui/object-text-placeholder {:ru "Текст учебного объекта…" :de "Text des Lernobjekts…" :en "Learning object text…"}
   :ui/lo-added {:ru "Учебный объект добавлен" :de "Lernobjekt hinzugefügt" :en "Learning object added"}
   :ui/add-lo {:ru "Добавить учебный объект" :de "Lernobjekt hinzufügen" :en "Add learning object"}

   ;; add-item-form
   :ui/question {:ru "вопрос" :de "Frage" :en "question"}
   :ui/single-choice-option {:ru "один вариант" :de "eine Auswahl" :en "single choice"}
   :ui/cloze-option {:ru "пропуск (cloze)" :de "Lücke (Cloze)" :en "gap (cloze)"}
   :ui/cloze-text-placeholder {:ru "Текст с ___ пропуском" :de "Text mit ___ Lücke" :en "Text with ___ gap"}
   :ui/question-placeholder {:ru "Вопрос" :de "Frage" :en "Question"}
   :ui/option-placeholder {:ru "Вариант ответа" :de "Antwortoption" :en "Answer option"}
   :ui/option-right {:ru "Верный ответ" :de "Richtige Antwort" :en "The right answer"}
   :ui/option-add {:ru "вариант" :de "Option" :en "option"}
   :ui/option-hint {:ru "Нужно минимум два разных варианта; отметьте верный." :de "Mindestens zwei verschiedene Optionen; markieren Sie die richtige." :en "At least two different options; mark the right one."}
   :ui/answer-placeholder {:ru "Ответ" :de "Antwort" :en "Answer"}
   :ui/question-added {:ru "Вопрос добавлен" :de "Frage hinzugefügt" :en "Question added"}
   :ui/add-question {:ru "Добавить вопрос" :de "Frage hinzufügen" :en "Add question"}

   ;; concept panel
   :ui/topic-not-found {:ru "Тема не найдена." :de "Thema nicht gefunden." :en "Topic not found."}
   :ui/back-to-map {:ru "← карта" :de "← Karte" :en "← map"}
   :ui/status-ready {:ru "готова к публикации" :de "veröffentlichbar" :en "ready to publish"}
   :ui/status-accepted {:ru "принята" :de "angenommen" :en "accepted"}
   :ui/status-stale {:ru "устарела" :de "veraltet" :en "stale"}
   :ui/status-deprecated {:ru "выведена" :de "ausgemustert" :en "deprecated"}
   :ui/status-draft {:ru "черновик" :de "Entwurf" :en "draft"}
   :ui/opens-arrow {:ru "открывает → {title}" :de "eröffnet → {title}" :en "unlocks → {title}"}
   :ui/lo-heading {:ru " Учебные объекты ({n})" :de " Lernobjekte ({n})" :en " Learning objects ({n})"}
   :ui/audience-prefix {:ru " · для: " :de " · für: " :en " · for: "}
   :ui/origin-drafted {:ru " · черновик ИИ, принят куратором"
                        :de " · KI-Entwurf, vom Kurator angenommen"
                        :en " · AI draft, accepted by the curator"}
   :ui/origin-generated {:ru " · сгенерировано, не проверено" :de " · generiert, nicht geprüft" :en " · generated, unverified"}
   :ui/source-word {:ru "источник" :de "Quelle" :en "source"}
   :ui/sources-heading {:ru " Источники" :de " Quellen" :en " Sources"}
   :ui/questions-heading {:ru " Вопросы ({n})" :de " Fragen ({n})" :en " Questions ({n})"}

   ;; source panel
   :ui/version-word {:ru "версия" :de "Version" :en "version"}
   :ui/chars-suffix {:ru " знаков" :de " Zeichen" :en " characters"}
   :ui/download-original {:ru "скачать оригинал" :de "Original herunterladen" :en "download original"}
   :ui/topics-on-source-heading {:ru " Темы на этом источнике" :de " Themen zu dieser Quelle" :en " Topics on this source"}
   :ui/lo-heading-plain {:ru " Учебные объекты" :de " Lernobjekte" :en " Learning objects"}
   :ui/text-heading {:ru " Текст" :de " Text" :en " Text"}
   :ui/loading-text {:ru "…читаю…" :de "…lese…" :en "…reading…"}
   :ui/no-extracted-text {:ru "Извлечённый текст не сохранён (документ приложен в другом браузере?)."
                           :de "Kein extrahierter Text gespeichert (Dokument in einem anderen Browser angehängt?)."
                           :en "No extracted text saved (was the document attached in another browser?)."}
   :ui/source-not-found {:ru "Источник не найден." :de "Quelle nicht gefunden." :en "Source not found."}

   ;; course forms
   :ui/ready {:ru "готова" :de "bereit" :en "ready"}
   :ui/not-ready {:ru "не готова" :de "nicht bereit" :en "not ready"}
   :ui/new-course {:ru "Новый курс" :de "Neuer Kurs" :en "New course"}
   :ui/course-name-placeholder {:ru "Название курса" :de "Kursname" :en "Course name"}
   :ui/pass-threshold-label {:ru "Порог сдачи, %" :de "Bestehensschwelle, %" :en "Pass threshold, %"}
   :ui/language-label {:ru "Язык" :de "Sprache" :en "Language"}
   :ui/course-goals-heading {:ru " Цели курса — тема и уровень" :de " Kursziele — Thema und Niveau" :en " Course goals — topic and level"}
   :ui/add-topics-first {:ru "Сначала добавьте темы." :de "Fügen Sie zuerst Themen hinzu." :en "Add topics first."}
   :ui/course-summary {:ru "{n} целей, готовы {m}" :de "{n} Ziele, bereit {m}" :en "{n} goals, ready {m}"}
   :ui/course-summary-prereqs {:ru " · план захватит ещё {n} пререквизит(ов), готовы {m}"
                                :de " · der Plan erfasst zusätzlich {n} Voraussetzung(en), bereit {m}"
                                :en " · the plan also pulls in {n} prerequisite(s), ready {m}"}
   :ui/course-published {:ru "Курс «{title}» опубликован" :de "Kurs „{title}“ veröffentlicht" :en "Course “{title}” published"}
   :ui/publish-blocked {:ru "Нельзя издать: по этим целям нет вопросов нужного уровня — {list}. Курс, который не может их проверить, выдаст неполный результат."
                        :de "Nicht veröffentlichbar: für diese Ziele fehlen Fragen auf dem nötigen Niveau — {list}. Ein Kurs, der sie nicht prüfen kann, liefert ein unvollständiges Ergebnis."
                        :en "Cannot publish: these targets have no questions at the required level — {list}. A course that cannot check them can only return an incomplete result."}
   :ui/publish-blocked-stale {:ru "Нельзя издать: материал этих целей изменился и ещё не просмотрен — {list}."
                              :de "Nicht veröffentlichbar: das Material dieser Ziele hat sich geändert und ist noch nicht durchgesehen — {list}."
                              :en "Cannot publish: the material of these targets changed and has not been reviewed yet — {list}."}
   :ui/republish-blocked {:ru "Нельзя издать обновление: не все цели готовы. Прежняя версия остаётся у учащихся."
                          :de "Update nicht veröffentlichbar: nicht alle Ziele sind bereit. Lernende behalten die bisherige Fassung."
                          :en "Cannot publish an update: not every target is ready. Learners keep the previous version."}
   :ui/publish-course {:ru "Опубликовать курс" :de "Kurs veröffentlichen" :en "Publish course"}
   :ui/course-ready-count {:ru "{n} / {m} готовы" :de "{n} / {m} bereit" :en "{n} / {m} ready"}
   :ui/any {:ru "любой" :de "beliebig" :en "any"}
   :ui/course-meta {:ru "порог сдачи {n}% · язык {lang}" :de "Bestehensschwelle {n}% · Sprache {lang}" :en "pass threshold {n}% · language {lang}"}
   :ui/goals-heading {:ru " Цели" :de " Ziele" :en " Goals"}
   :ui/prereqs-heading {:ru " Пререквизиты вне курса ({n})" :de " Voraussetzungen außerhalb des Kurses ({n})" :en " Prerequisites outside the course ({n})"}
   :ui/prereqs-note {:ru "Попадут в план учащегося, но не в экзамен. Уровень — как у зависимой темы, если связь не задаёт свой."
                      :de "Landen im Lernplan, aber nicht in der Prüfung. Das Niveau folgt dem der abhängigen Thema, sofern die Verknüpfung kein eigenes vorgibt."
                      :en "These land in the learner's plan but not in the exam. The level follows the dependent topic's unless the link sets its own."}
   :ui/course-not-found {:ru "Курс не найден." :de "Kurs nicht gefunden." :en "Course not found."}

   ;; command palette
   :ui/map {:ru "Карта" :de "Karte" :en "Map"}
   :ui/assistant-intake {:ru "Ассистент" :de "Assistent" :en "Assistant"}
   :ui/new-topic {:ru "Новая тема" :de "Neues Thema" :en "New topic"}
   :ui/new-link {:ru "Новая связь" :de "Neue Verknüpfung" :en "New link"}
   :ui/export-log {:ru "Экспорт log.edn" :de "log.edn exportieren" :en "Export log.edn"}
   :ui/save-sources-to-folder {:ru "Сохранить источники в папку…" :de "Quellen in einen Ordner speichern…" :en "Save sources to a folder…"}
   :ui/files-saved {:ru "Сохранено файлов: {n}" :de "Gespeicherte Dateien: {n}" :en "Files saved: {n}"}
   :ui/course-word {:ru "курс" :de "Kurs" :en "course"}
   :ui/model-label {:ru "Модель: {label}" :de "Modell: {label}" :en "Model: {label}"}
   :ui/current-hint {:ru "текущая" :de "aktuell" :en "current"}
   :ui/topic-lower {:ru "тема" :de "Thema" :en "topic"}
   :ui/palette-title {:ru "Найти тему, курс или команду — ↑↓, Enter" :de "Thema, Kurs oder Befehl finden — ↑↓, Enter" :en "Find a topic, course or command — ↑↓, Enter"}
   :ui/palette-placeholder {:ru "например: название темы или «настройки»" :de "z. B. ein Thema oder «Einstellungen»" :en "e.g. a topic name or “settings”"}
   :ui/nothing-found {:ru "Ничего не найдено" :de "Nichts gefunden" :en "Nothing found"}

   ;; shell
   :ui/tracker {:ru "{n} / {m} готовы к публикации" :de "{n} / {m} veröffentlichbar" :en "{n} / {m} ready to publish"}
   ;; the relation is in {detail} — a custom ontology's ordering relation is not `requires`
   :ui/error-cycle {:ru "Связь замкнула бы цикл: {detail} — отклонена"
                     :de "Die Verknüpfung würde einen Zyklus schließen: {detail} — abgelehnt"
                     :en "This link would close a loop: {detail} — rejected"}
   :ui/error-discarded {:ru "Эта тема уже была отклонена куратором"
                         :de "Dieses Thema wurde bereits vom Kurator abgelehnt"
                         :en "This topic was already discarded by the curator"}
   :ui/error-missing-endpoint {:ru "Связь между несуществующими темами — сначала примите обе"
                                :de "Verknüpfung zwischen nicht existierenden Themen — zuerst beide annehmen"
                                :en "Link between topics that don't exist — accept both first"}
   :ui/error-exists {:ru "Тема «{detail}» уже есть — правки идут через новую версию"
                      :de "Thema „{detail}“ gibt es schon — Änderungen laufen über eine neue Version"
                      :en "Topic “{detail}” already exists — edits go through a new version"}
   :ui/error-in-course {:ru "Эту тему изучают в курсе — сначала уберите её из целей курса"
                        :de "Dieses Thema ist Ziel eines Kurses — erst aus den Kurszielen entfernen"
                        :en "A course teaches this topic — remove it from the course targets first"}
   :ui/error-bad-import {:ru "Файл не импортирован: событие в нём не прошло проверку — граф не изменился. {detail}" :de "Datei nicht importiert: ein Ereignis darin hat die Prüfung nicht bestanden — der Graph ist unverändert. {detail}" :en "File not imported: an event in it failed the check — the graph is unchanged. {detail}"}
   :ui/error-bad-edn {:ru "Файл не прочитан: это не log.edn — граф не изменился." :de "Datei nicht gelesen: das ist kein log.edn — der Graph ist unverändert." :en "File not read: this is not a log.edn — the graph is unchanged."}
   :ui/error-graph-switched {:ru "В другой вкладке открыт граф «{title}» — здесь теперь он." :de "In einem anderen Tab wurde der Graph „{title}“ geöffnet — er ist jetzt auch hier offen." :en "Another tab opened the graph “{title}” — it is open here now too."}
   :ui/error-demo-unavailable {:ru "Демо-граф не загрузился — проверьте соединение и попробуйте ещё раз." :de "Der Demo-Graph wurde nicht geladen — Verbindung prüfen und erneut versuchen." :en "The demo graph did not load — check the connection and try again."}
   :ui/error-bad-object-link {:ru "Связать можно только два разных материала одной темы." :de "Verknüpfen lassen sich nur zwei verschiedene Materialien desselben Themas." :en "Only two different materials of the same topic can be linked."}
   :ui/rel-id {:ru "Идентификатор связи (его читают экспорт и агент)" :de "Kennung der Beziehung (Export und Agent lesen sie)" :en "The relation's id (exports and the agent read it)"}
   :ui/error-relation-exists {:ru "Связь «{id}» уже есть — выберите другой идентификатор" :de "Die Beziehung „{id}“ gibt es schon — wählen Sie eine andere Kennung" :en "A relation «{id}» already exists — choose another id"}
   :ui/cat-open-result {:ru "Открыть результат" :de "Ergebnis öffnen" :en "Open the result"}
   :ui/cat-take-again {:ru "Пройти заново" :de "Neu beginnen" :en "Take it again"}
   :ui/cat-ask-guide {:ru "Спросить помощника" :de "Den Helfer fragen" :en "Ask the helper"}
   :ui/palette-to-author {:ru "Карта графа (автор)" :de "Graphkarte (Autor)" :en "Graph map (author)"}
   :ui/error-unknown-route {:ru "Нет такой страницы: {route} — открыта карта." :de "Diese Seite gibt es nicht: {route} — die Karte ist geöffnet." :en "No such page: {route} — the map is open."}
   :ui/error-old-format {:ru "Графов в старом формате: {n} — их больше не открыть (формат событий сменился 2026-11-20). Они отложены, не удалены; загрузите демо заново или импортируйте граф, экспортированный в новом формате."
                         :de "Graphen im alten Format: {n} — sie lassen sich nicht mehr öffnen (das Ereignisformat hat sich am 2026-11-20 geändert). Sie sind beiseitegelegt, nicht gelöscht; laden Sie die Demos neu oder importieren Sie einen Graphen im neuen Format."
                         :en "Graphs in the old format: {n} — they can no longer be opened (the event format changed on 2026-11-20). They were set aside, not deleted; reload the demos or import a graph exported in the new format."}
   :ui/error-storage-full {:ru "Хранилище браузера переполнено — последнее изменение не сохранилось. Экспортируйте граф или удалите старые беседы"
                           :de "Browser-Speicher voll — die letzte Änderung wurde nicht gespeichert. Graph exportieren oder alte Gespräche löschen"
                           :en "Browser storage is full — the last change was not saved. Export the graph or remove old conversations"}
   :ui/remove {:ru "Удалить" :de "Entfernen" :en "Remove"}
   :ui/removed {:ru "Удалено" :de "Entfernt" :en "Removed"}
   :ui/error-duplicate {:ru "Уже предложено или уже есть" :de "Bereits vorgeschlagen oder schon vorhanden" :en "Already proposed or already exists"}
   :ui/error-invalid-schema {:ru "Событие не прошло схему: {detail}"
                              :de "Ereignis hat das Schema nicht bestanden: {detail}"
                              :en "Event failed schema validation: {detail}"}
   :ui/import {:ru "Импорт" :de "Import" :en "Import"}
   :ui/fullscreen {:ru "Во весь экран" :de "Vollbild" :en "Fullscreen"}

   ;; settings page
   :ui/settings {:ru "Настройки" :de "Einstellungen" :en "Settings"}
   :ui/general {:ru "Общие" :de "Allgemein" :en "General"}
   :ui/color-theme {:ru "Тема" :de "Design" :en "Theme"}
   :ui/language {:ru "Язык" :de "Sprache" :en "Language"}
   :ui/demos-title {:ru "Демо-графы" :de "Demo-Graphen" :en "Demo graphs"}
   :ui/demo-sql-t {:ru "SQL для аналитиков" :de "SQL für Analysten" :en "SQL for analysts"}
   :ui/demo-sql {:ru "навык: отчёты из одной базы" :de "Skill: Berichte aus einer Datenbank" :en "a skill: reports from one database"}
   :ui/demo-infosec-t {:ru "Информационная безопасность" :de "Informationssicherheit" :en "Information security"}
   :ui/demo-infosec {:ru "онбординг: цифровая гигиена сотрудника" :de "Onboarding: digitale Hygiene" :en "onboarding: digital hygiene"}
   :ui/demo-vineyard-t {:ru "Основы виноградарства" :de "Grundlagen des Weinbaus" :en "Vineyard basics"}
   :ui/demo-vineyard {:ru "ремесло: сезон на винограднике" :de "Handwerk: eine Saison im Weinberg" :en "a craft: a season in the vineyard"}
   :ui/load-demo {:ru "Загрузить демо-граф" :de "Demo-Graph laden" :en "Load the demo graph"}
   :ui/demo-confirm {:ru "Заменить текущий граф демо-графом? Текущий пропадёт — сначала сделайте экспорт."
                     :de "Den aktuellen Graphen durch den Demo-Graphen ersetzen? Der aktuelle geht verloren — vorher exportieren."
                     :en "Replace the current graph with the demo? The current one is lost — export it first."}
   :ui/session-word {:ru "беседа" :de "Gespräch" :en "conversation"}
   :ui/session-now {:ru "сейчас открыта" :de "gerade offen" :en "open now"}
   :ui/theme-light {:ru "Светлая" :de "Hell" :en "Light"}
   :ui/theme-dark {:ru "Тёмная" :de "Dunkel" :en "Dark"}
   :ui/theme-system {:ru "Как в системе" :de "Systemeinstellung" :en "System default"}
   :ui/assistant-heading {:ru " Ассистент" :de " Assistent" :en " Assistant"}

   ;; drawer
   :ui/export {:ru "Экспорт" :de "Export" :en "Export"}
   :ui/theme-title {:ru "Тема оформления" :de "Design-Thema" :en "Color theme"}

   ;; rail
   :ui/link {:ru "Связь" :de "Verknüpfung" :en "Link"}
   :ui/course {:ru "Курс" :de "Kurs" :en "Course"}
   :ui/search {:ru "Найти тему, курс или команду (Ctrl+K)" :de "Thema, Kurs oder Befehl finden (Strg+K)" :en "Find a topic, course or command (Ctrl+K)"}
   :ui/menu {:ru "Меню" :de "Menü" :en "Menu"}

   ;; merge files (loretile.merge)
   :ui/merges {:ru "Слияния" :de "Zusammenführungen" :en "Merges"}
   :ui/merge-badge {:ru "К слиянию: {n}" :de "Zusammenführen: {n}" :en "To merge: {n}"}
   :ui/merges-lead {:ru "Адрес бакета S3 с файлами изменений. Файлы для графа лежат в merges/{id графа}/ и ждут в меню, пока не слиты; слитый файл становится источником графа."
                    :de "Adresse des S3-Buckets mit Änderungsdateien. Dateien für einen Graphen liegen unter merges/{Graph-ID}/ und warten im Menü, bis sie zusammengeführt sind; eine zusammengeführte Datei wird zur Quelle des Graphen."
                    :en "Address of the S3 bucket with change files. Files for a graph live under merges/{graph id}/ and wait in the menu until merged; a merged file becomes a source of the graph."}
   :ui/merge-url-placeholder {:ru "https://storage.yandexcloud.net/бакет" :de "https://storage.yandexcloud.net/bucket" :en "https://storage.yandexcloud.net/bucket"}
   :ui/merge-error {:ru "Не удалось прочитать слияния: {e}" :de "Zusammenführungen konnten nicht gelesen werden: {e}" :en "Could not read merges: {e}"}
   :ui/merge-count {:ru "Ждут слияния: {n}" :de "Warten auf Zusammenführung: {n}" :en "Waiting to merge: {n}"}

   ;; the graph as it stood (bitemporal cut)
   :ui/asof {:ru "Состояние на дату" :de "Stand zu einem Zeitpunkt" :en "The graph as it stood"}
   :ui/asof-lead {:ru "Две даты над одним журналом: «состояние на» — что говорили сами изменения; «известно к» — что этот граф уже принял. Пока дата задана, правка отключена."
                  :de "Zwei Zeitpunkte über demselben Protokoll: „Stand am“ — was die Änderungen selbst sagten; „bekannt bis“ — was dieser Graph schon aufgenommen hatte. Solange ein Zeitpunkt gesetzt ist, ist Bearbeiten aus."
                  :en "Two clocks over one log: “as of” — what the changes themselves said; “as known by” — what this graph had taken in. While a time is set, editing is off."}
   :ui/asof-valid {:ru "Состояние на" :de "Stand am" :en "As of"}
   :ui/asof-recorded {:ru "Известно к" :de "Bekannt bis" :en "As known by"}
   :ui/asof-now {:ru "Сейчас" :de "Jetzt" :en "Now"}
   :ui/error-past-view {:ru "Вы смотрите прошлое состояние графа — изменения отключены. Нажмите «Сейчас»."
                        :de "Sie sehen einen früheren Stand des Graphen — Bearbeiten ist aus. Drücken Sie „Jetzt“."
                        :en "You are looking at a past state of the graph — editing is off. Press “Now”."}

   ;; link mode hint
   :ui/link-mode-hint {:ru "Режим связи: кликните тему-источник (кто требует), затем тему-требование."
                        :de "Verknüpfungsmodus: Klicken Sie zuerst auf das abhängige Thema (wer benötigt), dann auf das Voraussetzungsthema."
                        :en "Link mode: click the dependent topic (who requires) first, then the prerequisite topic."}})

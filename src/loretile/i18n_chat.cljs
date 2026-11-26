(ns loretile.i18n-chat
  "Intake chat strings (:chat/…). Every entry carries ru, de AND en — test/i18n-check.js fails otherwise. LLM PROMPTS are not here: they stay in chat.cljs, the model reads them, not the user.")

(def strings
  {;; documents into an ontology graph (wiki/Ontology-Graph.md slice 3)
   :chat/accept-all-types {:ru "Принять все типы ({n})" :de "Alle Typen annehmen ({n})" :en "Accept all types ({n})"}
   :chat/accept-all-relations {:ru "Принять все связи ({n})" :de "Alle Beziehungen annehmen ({n})" :en "Accept all relations ({n})"}
   :chat/triage-derive {:ru "вывести онтологию из этого документа" :de "die Ontologie aus diesem Dokument ableiten" :en "derive the ontology from this document"}
   :chat/derive-tag {:ru "дальше" :de "weiter" :en "next"}
   :chat/derive-lead {:ru "Когда типы и связи разобраны — прочитать «{doc}» как содержимое графа «{graph}» по этой онтологии." :de "Wenn Typen und Beziehungen entschieden sind — „{doc}“ als Inhalt des Graphen „{graph}“ nach dieser Ontologie lesen." :en "Once the types and relations are decided — read \"{doc}\" as the content of «{graph}» on this ontology."}
   :chat/derive-wait {:ru "Ещё не разобрано карточек: {n}." :de "Noch {n} Karten offen." :en "{n} cards still open."}
   :chat/derive-go {:ru "Прочитать как содержимое" :de "Als Inhalt lesen" :en "Read as content"}
   :chat/type-draft-tag {:ru "нет такого типа" :de "Typ fehlt" :en "missing type"}
   :chat/type-draft-q {:ru "Документ называет вид «{type}», которого нет в онтологии «{onto}». Добавить?" :de "Das Dokument nennt die Art „{type}“, die in der Ontologie „{onto}“ fehlt. Hinzufügen?" :en "The document names a kind «{type}» the ontology «{onto}» lacks. Add it?"}
   :chat/type-draft-examples {:ru "например: {list}" :de "z. B.: {list}" :en "e.g.: {list}"}
   :chat/type-draft-waiting {:ru "Ждут этого типа: {n}" :de "Warten auf diesen Typ: {n}" :en "Waiting for this type: {n}"}
   :chat/type-draft-add {:ru "Добавить в онтологию" :de "Zur Ontologie hinzufügen" :en "Add to the ontology"}
   :chat/relation-draft-tag {:ru "нет такой связи" :de "Beziehung fehlt" :en "missing relation"}
   :chat/relation-draft-q {:ru "Документ называет связь «{rel}», которой нет в онтологии «{onto}». Добавить?" :de "Das Dokument nennt die Beziehung „{rel}“, die in der Ontologie „{onto}“ fehlt. Hinzufügen?" :en "The document names a relation «{rel}» the ontology «{onto}» lacks. Add it?"}
   :chat/relation-draft-waiting {:ru "Ждут этой связи: {n}" :de "Warten auf diese Beziehung: {n}" :en "Waiting for this relation: {n}"}
   :chat/triage-tag {:ru "что это за документ?" :de "Was ist das für ein Dokument?" :en "what is this document?"}
   :chat/triage-q {:ru "«{name}» — это…" :de "„{name}“ ist…" :en "\"{name}\" is…"}
   :chat/triage-ontology {:ru "онтология — виды вещей и как они связаны" :de "eine Ontologie — Arten von Dingen und wie sie zusammenhängen" :en "an ontology — kinds of things and how they relate"}
   :chat/triage-content {:ru "содержимое графа — сами вещи и факты" :de "Inhalt für einen Graphen — die Dinge selbst und Fakten" :en "content for a graph — the things themselves and facts"}
   :chat/triage-which {:ru "по какой онтологии" :de "nach welcher Ontologie" :en "on which ontology"}
   :chat/triage-learning {:ru "Обучение (встроенная)" :de "Lernen (eingebaut)" :en "Learning (built in)"}
   :chat/triage-go {:ru "Продолжить" :de "Weiter" :en "Continue"}
   :chat/type-tag {:ru "тип" :de "Typ" :en "type"}
   :chat/relation-tag {:ru "связь онтологии" :de "Beziehung der Ontologie" :en "ontology relation"}
   :chat/vocab-exists {:ru "Такой тип уже есть — принять значит обновить его слова." :de "Diesen Typ gibt es schon — annehmen aktualisiert seine Wörter." :en "This type exists — accepting updates its words."}
   :chat/vocab-proposed {:ru "Предложено типов: {t}, связей: {r}. Примите нужные — связь приводит свои типы с собой." :de "Vorgeschlagen: {t} Typen, {r} Beziehungen. Nehmen Sie an, was passt — eine Beziehung bringt ihre Typen mit." :en "Proposed: {t} types, {r} relations. Accept what fits — a relation brings its types along."}
   :chat/vocab-file-elsewhere {:ru "Это файл онтологии — откройте граф-онтологию (или создайте его) и отдайте файл туда." :de "Das ist eine Ontologie-Datei — öffnen Sie einen Ontologie-Graphen (oder legen Sie einen an) und geben Sie die Datei dort ab." :en "This is an ontology file — open an ontology graph (or make one) and drop the file there."}
   :chat/doc-read {:ru "Прочитан «{name}»: " :de "„{name}“ gelesen: " :en "Read \"{name}\": "}
   :chat/doc-pages {:ru "{n} стр., " :de "{n} S., " :en "{n} pg., "}
   :chat/doc-chars {:ru "{n} знаков" :de "{n} Zeichen" :en "{n} characters"}
   :chat/doc-truncated {:ru "; в модель уйдут первые {n}"
                         :de "; an das Modell gehen nur die ersten {n}"
                         :en "; only the first {n} will go to the model"}
   :chat/file-read-error {:ru "Не смог прочитать файл: "
                           :de "Datei konnte nicht gelesen werden: "
                           :en "Could not read the file: "}
   :chat/course-targets-note {:ru "Цели предложены." :de "Ziele vorgeschlagen." :en "Targets proposed."}
   :chat/unmatched-prefix {:ru "Нет в базе: " :de "Nicht in der Basis: " :en "Not in the base: "}
   :chat/unmatched-suffix {:ru " — это работа для интейк-чата."
                            :de " — das ist Arbeit für den Assistenten."
                            :en " — that's a job for the intake chat."}
   :chat/error-prefix {:ru "Ошибка: " :de "Fehler: " :en "Error: "}
   :chat/finding-targets {:ru "…подбираю темы…" :de "…suche passende Themen…" :en "…finding topics…"}
   :chat/target-tag {:ru "цель" :de "Ziel" :en "target"}
   :chat/add-to-course {:ru "В курс" :de "In den Kurs" :en "Add to course"}
   :chat/no {:ru "Нет" :de "Nein" :en "No"}
   :chat/course-goal-placeholder {:ru "Опишите курс: для кого, о чём, сколько времени…"
                                   :de "Beschreiben Sie den Kurs: für wen, worüber, wie viel Zeit…"
                                   :en "Describe the course: for whom, about what, how much time…"}
   :chat/pick-targets {:ru "Подобрать цели" :de "Ziele vorschlagen" :en "Suggest targets"}
   :chat/file-tag {:ru "файл: " :de "Datei: " :en "file: "}
   :chat/proposals-note {:ru "Предложения готовы." :de "Vorschläge sind fertig." :en "Proposals are ready."}
   :chat/dropped-prefix {:ru "Пропущено (дубли/уже отклонённое): "
                          :de "Übersprungen (Duplikate/bereits verworfen): "
                          :en "Skipped (duplicates/already discarded): "}
   :chat/byok-note {:ru "Нужен ваш собственный ключ к модели. Он хранится только в этом браузере и отправляется только провайдеру."
                     :de "Sie brauchen einen eigenen Schlüssel für das Modell. Er bleibt nur in diesem Browser und geht nur an den Anbieter."
                     :en "You need your own key to the model. It stays in this browser and is sent only to the provider."}
   :chat/url-label {:ru "URL" :de "URL" :en "URL"}
   :chat/model-label {:ru "Модель" :de "Modell" :en "Model"}
   :chat/api-key-label {:ru "API-ключ" :de "API-Schlüssel" :en "API key"}
   :chat/vectors-note {:ru "Векторы тем (поиск по смыслу; пусто = поиск по словам)"
                        :de "Themenvektoren (Sinnsuche; leer = Wortsuche)"
                        :en "Topic vectors (semantic search; empty = keyword search)"}
   :chat/embed-url-label {:ru "URL /embeddings" :de "URL /embeddings" :en "URL /embeddings"}
   :chat/embed-model-label {:ru "Модель эмбеддингов" :de "Embedding-Modell" :en "Embedding model"}
   :chat/save {:ru "Сохранить" :de "Speichern" :en "Save"}
   :chat/saved {:ru "сохранено" :de "gespeichert" :en "saved"}
   :chat/concept-tag {:ru "концепт" :de "Konzept" :en "concept"}
   :chat/accept {:ru "Принять" :de "Annehmen" :en "Accept"}
   :chat/discard {:ru "Отклонить" :de "Verwerfen" :en "Discard"}
   :chat/edge-tag {:ru "связь" :de "Verknüpfung" :en "link"}
   :chat/wrong-graph {:ru "Ответ пришёл, но вы уже в другом графе — предложения не добавлены. Вернитесь в тот граф и отправьте материал ещё раз."
                      :de "Die Antwort kam, aber Sie sind bereits in einem anderen Graphen — die Vorschläge wurden nicht übernommen. Wechseln Sie zurück und senden Sie das Material erneut."
                      :en "The answer came back, but you have moved to another graph, so nothing was added. Switch back and send the material again."}
   :chat/engine-prefix {:ru "движок: " :de "Engine: " :en "engine: "}
   :chat/sig-earlier {:ru "«{title}» упоминается раньше (стр. {p1} → {p2})"
                      :de "„{title}“ wird früher erwähnt (S. {p1} → {p2})"
                      :en "“{title}” is mentioned earlier (p. {p1} → {p2})"}
   :chat/sig-step {:ru "«{title}» — шаг {n1}, этот — шаг {n2} того же списка" :de "„{title}“ ist Schritt {n1}, dieser Schritt {n2} derselben Liste" :en "“{title}” is step {n1}, this one step {n2} of the same list"}
   :chat/sig-in-def {:ru "«{inner}» входит в определение «{outer}»"
                     :de "„{inner}“ kommt in der Definition von „{outer}“ vor"
                     :en "“{inner}” appears in the definition of “{outer}”"}
   :chat/accept-draft {:ru "Принять (черновик)" :de "Annehmen (Entwurf)" :en "Accept (draft)"}
   :chat/question-tag {:ru "вопрос эксперту → " :de "Frage an den Experten → " :en "question for the expert → "}
   :chat/object-tag {:ru "учебный объект" :de "Lernobjekt" :en "learning object"}
   :chat/key-where {:ru "Где взять ключ:" :de "Wo es einen Schlüssel gibt:" :en "Where to get a key:"}
   :chat/key-get {:ru "страница ключей {p}" :de "Schlüsselseite von {p}" :en "{p} key page"}
   :chat/key-cost {:ru "Платите вы — по тарифу провайдера, за то, что отправлено. Дневной лимит токенов ниже не даст счёту вырасти неожиданно."
                   :de "Sie zahlen — nach dem Tarif des Anbieters, für das, was gesendet wird. Das Tageslimit unten verhindert eine überraschende Rechnung."
                   :en "You pay — at the provider's rate, for what is sent. The daily token limit below keeps the bill from surprising you."}
   :chat/key-check {:ru "Проверить ключ" :de "Schlüssel prüfen" :en "Check the key"}
   :chat/key-checking {:ru "Проверяю — один короткий запрос…" :de "Prüfe — eine kurze Anfrage…" :en "Checking — one short request…"}
   :chat/key-ok {:ru "Ключ работает: провайдер ответил. Ассистент готов." :de "Der Schlüssel funktioniert: der Anbieter hat geantwortet. Der Assistent ist bereit." :en "The key works: the provider answered. The assistant is ready."}
   :chat/key-rejected {:ru "Провайдер не принял ключ — проверьте, что он скопирован целиком и не отозван." :de "Der Anbieter hat den Schlüssel abgelehnt — prüfen Sie, ob er vollständig kopiert und nicht widerrufen ist." :en "The provider rejected the key — check it was copied whole and has not been revoked."}
   :chat/key-not-found {:ru "Адрес или модель не найдены — проверьте поля выше." :de "Adresse oder Modell nicht gefunden — prüfen Sie die Felder oben." :en "Address or model not found — check the fields above."}
   :chat/key-http {:ru "Провайдер ответил ошибкой {s}." :de "Der Anbieter antwortete mit Fehler {s}." :en "The provider answered with error {s}."}
   :chat/key-unreachable {:ru "Не удалось связаться с провайдером: нет сети, или адрес не разрешает запросы из браузера." :de "Der Anbieter war nicht erreichbar: kein Netz, oder die Adresse erlaubt keine Anfragen aus dem Browser." :en "Could not reach the provider: no network, or the address does not allow requests from a browser."}
   :chat/lead {:ru "Вставьте текст или прикрепите PDF, Word, веб-страницу, .md или .txt — я предложу темы, связи между ними и черновики материалов карточками; ничего не попадёт в граф без вашего «Принять». Можно и спросить: «чего не хватает в этой теме?»"
               :de "Fügen Sie Text ein oder hängen Sie PDF, Word, eine Webseite, .md oder .txt an — ich schlage Themen, ihre Verknüpfungen und Materialentwürfe als Karten vor; nichts landet ohne Ihr «Annehmen» im Graphen. Sie können auch fragen: «Was fehlt in diesem Thema?»"
               :en "Paste text or attach a PDF, Word file, web page, .md or .txt — I'll propose topics, the links between them and draft material as cards; nothing enters the graph until you accept it. You can also ask: “what is missing in this topic?”"}
   :chat/enqueue {:ru "В очередь" :de "In die Warteschlange" :en "To the queue"}
   :chat/hide {:ru "Скрыть" :de "Ausblenden" :en "Hide"}
   :chat/title {:ru "Ассистент" :de "Assistent" :en "Assistant"}
   :chat/model-prefix {:ru "модель: " :de "Modell: " :en "model: "}
   :chat/no-key {:ru "ключ не настроен" :de "kein Schlüssel konfiguriert" :en "key not configured"}
   :chat/new-conversation {:ru "Новый разговор" :de "Neues Gespräch" :en "New conversation"}
   :chat/settings {:ru "Настройки" :de "Einstellungen" :en "Settings"}
   :chat/minimize {:ru "Свернуть" :de "Minimieren" :en "Minimize"}
   :chat/restore {:ru "Восстановить" :de "Wiederherstellen" :en "Restore"}
   :chat/maximize {:ru "Развернуть" :de "Maximieren" :en "Maximize"}
   :chat/close {:ru "Закрыть" :de "Schließen" :en "Close"}
   :chat/thinking {:ru "…думаю над материалом…" :de "…denke über das Material nach…" :en "…thinking about the material…"}
   :chat/pages-suffix {:ru " стр." :de " S." :en " pg."}
   :chat/remove-file {:ru "Убрать файл" :de "Datei entfernen" :en "Remove file"}
   :chat/composer-placeholder {:ru "Материал или вопрос ассистенту…"
                                :de "Material oder eine Frage an den Assistenten…"
                                :en "Material or a question for the assistant…"}
   :chat/attach-title {:ru "Прикрепить файл (md/txt/pdf)" :de "Datei anhängen (md/txt/pdf)" :en "Attach a file (md/txt/pdf)"}
   :chat/part-pages {:ru "стр. {from}–{to}" :de "S. {from}–{to}" :en "pp. {from}–{to}"}
   :chat/part-n {:ru "часть {i} из {n}" :de "Teil {i} von {n}" :en "part {i} of {n}"}
   :chat/inferred {:ru "вывод, не цитата" :de "Schlussfolgerung, kein Zitat" :en "inference, not a quote"}
   :chat/looks-like {:ru "Похоже на «{title}» — не дубликат ли?" :de "Ähnelt «{title}» — ein Duplikat?" :en "Looks like «{title}» — a duplicate?"}
   :chat/cq-like {:ru "Похоже на вопрос «{q}»" :de "Ähnelt der Frage «{q}»" :en "Looks like the question «{q}»"}
   :chat/accept-all-held {:ru "{n} — посмотрите сами: похоже на дубликат или вывод" :de "{n} — selbst prüfen: mögliches Duplikat oder Schlussfolgerung" :en "{n} to check yourself: a possible duplicate or an inference"}
   :chat/accept-all-topics {:ru "Принять все темы ({n})" :de "Alle Themen annehmen ({n})" :en "Accept all topics ({n})"}
   :chat/accept-all-links {:ru "Принять все связи ({n})" :de "Alle Verknüpfungen annehmen ({n})" :en "Accept all links ({n})"}
   :chat/missing-steps-tag {:ru "Пропущенные шаги" :de "Fehlende Schritte" :en "Missing steps"}
   :chat/missing-steps-lead {:ru "В списке «{list}» у этих шагов нет узла — ни связать, ни упорядочить их нельзя:" :de "In der Liste „{list}“ haben diese Schritte keinen Knoten — sie lassen sich weder verknüpfen noch ordnen:" :en "In the list “{list}” these steps have no node, so nothing can link or order them:"}
   :chat/missing-steps-read {:ru "Прочитать эти шаги ещё раз" :de "Diese Schritte noch einmal lesen" :en "Read these steps again"}
   :chat/missing-steps-skip {:ru "Не нужно" :de "Nicht nötig" :en "Not needed"}
   :chat/missing-steps-reading {:ru "Читаю ещё раз шаги {steps}." :de "Ich lese die Schritte {steps} noch einmal." :en "Reading steps {steps} again."}
   :chat/missing-steps-none {:ru "У шагов этого списка уже есть узлы — читать нечего." :de "Die Schritte dieser Liste haben schon Knoten — nichts zu lesen." :en "The steps of this list already have nodes — nothing to read."}
   :chat/missing-steps-done {:ru "Шаги прочитаны, предложено узлов: {c}." :de "Schritte gelesen, vorgeschlagene Knoten: {c}." :en "Steps read, nodes proposed: {c}."}
   :chat/reply-not-json {:ru "ответ модели не в нужном виде (нет JSON)" :de "die Antwort des Modells hat nicht die erwartete Form (kein JSON)" :en "the model's reply is not in the expected form (no JSON)"}
   :chat/part-split {:ru "{part}: ответ не уместился — читаю эту часть двумя половинами." :de "{part}: Die Antwort war zu lang — ich lese diesen Teil in zwei Hälften." :en "{part}: the reply did not fit — reading this part in two halves."}
   :chat/step-topics {:ru "ищу темы" :de "suche Themen" :en "finding topics"}
   :chat/step-links {:ru "оцениваю связи" :de "prüfe Verknüpfungen" :en "judging links"}
   :chat/stop {:ru "Стоп" :de "Stopp" :en "Stop"}
   :chat/parts-done {:ru "Материал прочитан (частей: {n}), предложено тем: {c}."
                     :de "Material gelesen (Teile: {n}), vorgeschlagene Themen: {c}."
                     :en "Material read (parts: {n}), topics proposed: {c}."}
   :chat/parts-stopped {:ru "Остановлено: прочитано частей {i} из {n}, предложено тем: {c}. Остальное можно прислать заново."
                        :de "Nach {i} von {n} Teilen angehalten, vorgeschlagene Themen: {c}. Den Rest erneut senden."
                        :en "Stopped after {i} of {n} parts, topics proposed: {c}. Send the rest again when ready."}
   :chat/reviewed {:ru "Разобрано {d} из {n}" :de "{d} von {n} erledigt" :en "{d} of {n} reviewed"}
   :chat/more-cards {:ru "ещё {n} — появятся по мере разбора" :de "noch {n} — erscheinen nach und nach" :en "{n} more — they appear as you go"}
   :chat/links-waiting {:ru "связей ждут принятия своих тем: {n}" :de "Verknüpfungen warten auf ihre Themen: {n}" :en "links waiting for their topics: {n}"}
   :chat/question-short {:ru "вопрос эксперту" :de "Frage an den Experten" :en "question for the expert"}
   :chat/cards-other-graph {:ru "Эти предложения — для графа «{title}». Откройте его, чтобы разобрать."
                            :de "Diese Vorschläge gehören zum Graphen „{title}“. Öffnen Sie ihn, um sie zu prüfen."
                            :en "These proposals belong to the graph “{title}”. Open it to review them."}
   :chat/switch-graph {:ru "Открыть граф" :de "Graph öffnen" :en "Open graph"}
   :chat/new-with-cards {:ru "Не разобрано предложений: {n}." :de "Nicht geprüfte Vorschläge: {n}." :en "Undecided proposals: {n}."}
   :chat/keep-for-later {:ru "Отложить" :de "Für später aufheben" :en "Keep for later"}
   :chat/discard-rest {:ru "Отклонить все" :de "Alle verwerfen" :en "Discard all"}
   :chat/cancel {:ru "Отмена" :de "Abbrechen" :en "Cancel"}
   :chat/send {:ru "Отправить" :de "Senden" :en "Send"}
   :chat/draft-exercises {:ru "Предложить упражнения (ИИ)" :de "Übungen vorschlagen (KI)" :en "Draft exercises (AI)"}
   :chat/draft-exercises-request {:ru "Предложи упражнения с запуском по теме «{title}»" :de "Schlage ausführbare Übungen zum Thema „{title}“ vor" :en "Draft runnable exercises on “{title}”"}
   :chat/exercises-result {:ru "Предложено {n}, прошли проверку запуском {k}. Каждое — на карточке ниже: решение выполнено, неверные ответы отвергнуты." :de "{n} vorgeschlagen, {k} haben die Prüfung durch Ausführen bestanden. Jede steht unten auf einer Karte: Lösung ausgeführt, falsche Antworten abgelehnt." :en "{n} drafted, {k} passed the run check. Each is on a card below: solution executed, wrong answers rejected."}
   :chat/exercise-dropped {:ru "Отброшено «{stem}»: " :de "Verworfen „{stem}“: " :en "Dropped “{stem}”: "}
   :chat/exercise-tag {:ru "Упражнение" :de "Übung" :en "Exercise"}
   :chat/check-rows {:ru "сверка строк" :de "Zeilenvergleich" :en "row comparison"}
   :chat/exercise-verified {:ru "Проверено запуском: решение проходит свою проверку, неверных ответов отвергнуто — {n}." :de "Durch Ausführen geprüft: die Lösung besteht ihre Prüfung, abgelehnte falsche Antworten: {n}." :en "Checked by running it: the solution passes its own check, wrong answers rejected: {n}."}
   :chat/tool-used {:ru "🔎 смотрю: {name} {args}" :de "🔎 sehe nach: {name} {args}" :en "🔎 looking up: {name} {args}"}
   :chat/tool-limit {:ru "Ассистент хотел смотреть дальше, но два круга поиска — предел; ответ собран из того, что найдено." :de "Der Assistent wollte weiter suchen, aber zwei Suchrunden sind die Grenze; die Antwort beruht auf dem Gefundenen." :en "The assistant wanted to keep looking, but two rounds of lookups is the limit; the answer uses what was found."}
   :chat/version-tag {:ru "Новая версия?" :de "Neue Version?" :en "A new version?"}
   :chat/version-q {:ru "«{new}» — новая версия «{old}», который уже в базе?" :de "Ist „{new}“ eine neue Version von „{old}“, das schon im Graphen ist?" :en "Is “{new}” a new version of “{old}”, already in the graph?"}
   :chat/version-yes {:ru "Да, новая версия" :de "Ja, neue Version" :en "Yes, a new version"}
   :chat/version-no {:ru "Нет, отдельный документ" :de "Nein, ein eigenes Dokument" :en "No, a separate document"}
   :chat/reversion-made {:ru "По старой версии «{old}» написано тем: {n}. По каждой решите — только правки или изменился смысл." :de "Aus der alten Version von „{old}“ stammen {n} Themen. Entscheiden Sie für jedes: nur Korrekturen oder geänderter Sinn." :en "{n} topics were written from the old version of “{old}”. Decide for each: just corrections, or the meaning changed."}
   :chat/reversion-none {:ru "По старой версии «{old}» ничего не написано — помечать нечего." :de "Aus der alten Version von „{old}“ stammt nichts — es gibt nichts zu markieren." :en "Nothing was written from the old version of “{old}” — nothing to flag."}
   :chat/reversion-sorted {:ru "По старой версии «{old}» написано тем: {n}. Цитата изменилась у {c}, осталась прежней у {k}, проверить нечем у {u}. Начните с изменившихся."
                           :de "Aus der alten Version von „{old}“ stammen {n} Themen. Bei {c} hat sich das Zitat geändert, bei {k} ist es unverändert, {u} lassen sich nicht prüfen. Beginnen Sie mit den geänderten."
                           :en "{n} topics were written from the old version of “{old}”. For {c} the quoted passage changed, for {k} it is unchanged, {u} cannot be checked. Start with the changed ones."}
   :chat/reversion-changed-quote {:ru "Этого места в новой версии нет: «{q}»" :de "Diese Stelle fehlt in der neuen Version: „{q}“" :en "This passage is gone from the new version: “{q}”"}
   :chat/reversion-kept {:ru "Тем, чья цитата осталась в новой версии без изменений: {n}." :de "Themen, deren Zitat in der neuen Version unverändert steht: {n}." :en "Topics whose quoted passage is unchanged in the new version: {n}."}
   :chat/reversion-kept-patch {:ru "Все — только правки" :de "Alle — nur Korrekturen" :en "All — just corrections"}
   :chat/reversion-kept-split {:ru "Разобрать по одной" :de "Einzeln prüfen" :en "Review one by one"}
   :chat/reversion-tag {:ru "Новая версия источника" :de "Neue Quellversion" :en "New source version"}
   :chat/reversion-q {:ru "Изменился ли смысл того, что написано по старой версии?" :de "Hat sich der Sinn dessen geändert, was aus der alten Version geschrieben wurde?" :en "Did the meaning of what was written from the old version change?"}
   :chat/reversion-patch {:ru "Только правки" :de "Nur Korrekturen" :en "Just corrections"}
   :chat/reversion-material {:ru "Изменился смысл" :de "Sinn geändert" :en "The meaning changed"}
   :chat/action-tag {:ru "Предлагаю" :de "Vorschlag" :en "Suggested"}
   :chat/action-hide {:ru "Скрыть" :de "Ausblenden" :en "Hide"}
   :chat/action-go-topic {:ru "Открыть тему «{title}»" :de "Thema „{title}“ öffnen" :en "Open the topic “{title}”"}
   :chat/action-start-course {:ru "Открыть курс «{title}» как ученик" :de "Kurs „{title}“ als Lernende öffnen" :en "Open the course “{title}” as a learner"}
   :chat/action-create-graph {:ru "Создать граф «{title}» · {ontology}" :de "Graph „{title}“ anlegen · {ontology}" :en "Create the graph “{title}” · {ontology}"}
   :chat/action-open-graph {:ru "Открыть граф «{title}»" :de "Graph „{title}“ öffnen" :en "Open the graph “{title}”"}
   :chat/graph-opened {:ru "Открыт граф «{title}»." :de "Graph „{title}“ ist geöffnet." :en "The graph “{title}” is open."}
   :chat/action-draft {:ru "Предложить упражнения по теме «{title}»" :de "Übungen zum Thema „{title}“ vorschlagen" :en "Draft exercises for “{title}”"}
   ;; --- the ontology interview (wiki/Profiles.md §CQ) ---
   :chat/mode-ontology {:ru "режим онтологии" :de "Ontologiemodus" :en "ontology mode"}
   :chat/onto-opening {:ru "Соберём словарь этого графа. Для чего он: кто будет им пользоваться — агент-программист, новые сотрудники, поддержка? Опишите в двух словах, и я задам пару уточняющих вопросов."
                       :de "Bauen wir das Vokabular dieses Graphen. Wofür ist er: wer wird ihn nutzen — ein Coding-Agent, neue Mitarbeiter, der Support? Beschreiben Sie es kurz, dann stelle ich ein paar Rückfragen."
                       :en "Let's build this graph's vocabulary. What is it for: who will use it — a coding agent, new hires, support? Describe it briefly and I will ask a few follow-up questions."}
   :chat/onto-doc-later {:ru "Документы — после онтологии: сначала договоримся о словаре, потом разберу материал." :de "Dokumente kommen nach der Ontologie: erst das Vokabular, dann das Material." :en "Documents come after the ontology: the vocabulary first, then the material."}
   :chat/cq-tag {:ru "вопрос компетенции" :de "Kompetenzfrage" :en "competency question"}
   :chat/cq-asks-from {:ru "спрашивают о:" :de "gefragt über:" :en "asked about:"}
   :chat/profile-next-tag {:ru "Онтология" :de "Ontologie" :en "Ontology"}
   :chat/profile-next-lead {:ru "Принято вопросов: {n}. Из принятых соберётся словарь: типы узлов и связи, каждая — ради вопроса."
                            :de "Angenommene Fragen: {n}. Aus ihnen entsteht das Vokabular: Knotentypen und Relationen, jede für eine Frage."
                            :en "Questions accepted: {n}. The vocabulary is built from them: node types and relations, each for a question."}
   :chat/relation-ordering {:ru "задаёт порядок шагов" :de "ordnet die Schritte" :en "orders the steps"}
   :chat/sig-sentence {:ru "оба названы в одном предложении" :de "beide im selben Satz genannt" :en "both named in one sentence"}
   :chat/onto-decide-rest {:ru "Осталось решить вопросов: {n} — примите или отклоните их, тогда онтологию можно записать." :de "Noch offene Fragen: {n} — übernehmen oder verwerfen Sie sie, dann lässt sich die Ontologie festlegen." :en "Questions still to decide: {n} — accept or discard them, then the ontology can be written."}
   :chat/compose-profile {:ru "Записать онтологию" :de "Ontologie festlegen" :en "Write the ontology"}
   :chat/onto-accept-first {:ru "Сначала примите хотя бы один вопрос." :de "Nehmen Sie zuerst mindestens eine Frage an." :en "Accept at least one question first."}
   :chat/onto-problems {:ru "Онтология пока не сходится: " :de "Die Ontologie geht noch nicht auf: " :en "The ontology does not hold together yet: "}
   :chat/onto-done {:ru "Онтология «{title}» создана: типов узлов — {t}, связей — {r}, вопросов компетенции — {q}. Граф теперь на ней; ваши ответы читаю как его содержимое — узлы и связи придут карточками."
                    :de "Ontologie „{title}“ angelegt: {t} Knotentypen, {r} Relationen, {q} Kompetenzfragen. Der Graph nutzt sie jetzt; Ihre Antworten lese ich als seinen Inhalt — Knoten und Verbindungen kommen als Karten."
                    :en "Ontology «{title}» created: {t} node types, {r} relations, {q} competency questions. The graph now uses it; I'm reading your answers as its content — nodes and links will come as cards."}
   :chat/interview-door {:ru "Построить граф в разговоре" :de "Den Graphen im Gespräch aufbauen" :en "Build the graph by talking"}
   :chat/interview-answers {:ru "Ответы в разговоре" :de "Antworten im Gespräch" :en "Answers in the conversation"}
   ;; --- gaps the competency questions leave (wiki/Profiles.md §CQ) ---
   :chat/gaps-tag {:ru "Пробелы" :de "Lücken" :en "Gaps"}
   :chat/gaps-lead {:ru "Узлов без ответа: {n}, вопросов: {m}. Считает граф, не модель; ассистент может поискать ответ в источнике узла или среди существующих узлов."
                    :de "Knoten ohne Antwort: {n}, Fragen: {m}. Der Graph zählt, nicht das Modell; der Assistent kann die Antwort in der Quelle des Knotens oder unter vorhandenen Knoten suchen."
                    :en "Nodes without an answer: {n}, questions: {m}. The graph counts, not the model; the assistant can look for the answer in the node's source or among existing nodes."}
   :chat/gaps-more {:ru "…и ещё {n}" :de "…und {n} weitere" :en "…and {n} more"}
   :chat/find-answer {:ru "найти ответ" :de "Antwort suchen" :en "find the answer"}
   :chat/gap-ask {:ru "«{node}»: {q}" :de "„{node}“: {q}" :en "“{node}”: {q}"}
   :chat/gap-found {:ru "Предложение — карточками ниже." :de "Vorschlag — als Karten unten." :en "A proposal — as cards below."}
   :chat/gap-nothing {:ru "Ответа не нашлось: ни в источнике, ни среди существующих узлов." :de "Keine Antwort gefunden: weder in der Quelle noch unter den vorhandenen Knoten." :en "No answer found: neither in the source nor among existing nodes."}
   :chat/probe-ask {:ru "Проверь онтологию: каких вопросов она ещё не может ответить, что смоделировано неверно?" :de "Prüfe die Ontologie: welche Fragen kann sie noch nicht beantworten, was ist falsch modelliert?" :en "Check the ontology: which questions can it not answer yet, what is modelled wrong?"}
   :chat/probe-pitfalls {:ru "Что модель считает ошибками моделирования (ничего не меняется само):" :de "Was das Modell für Modellierungsfehler hält (nichts ändert sich von selbst):" :en "What the model sees as modelling pitfalls (nothing changes by itself):"}
   :chat/cq-new-relation {:ru "новая связь" :de "neue Relation" :en "new relation"}
   :chat/mode-author {:ru "режим автора" :de "Autorenmodus" :en "author mode"}
   :guide/title {:ru "Помощник" :de "Assistent" :en "Assistant"}
   :guide/mode {:ru "режим ученика" :de "Lernmodus" :en "learner mode"}
   :guide/lead {:ru "Расскажите, чему хотите научиться, — найду курс среди всех графов на этом устройстве." :de "Sagen Sie, was Sie lernen möchten — ich suche einen Kurs in allen Graphen auf diesem Gerät." :en "Tell me what you want to learn — I'll look for a course across every graph on this device."}
   :guide/placeholder {:ru "Хочу научиться…" :de "Ich möchte lernen…" :en "I want to learn…"}
   :guide/nokey {:ru "Помощник работает с вашим ключом модели — задайте его в настройках:" :de "Der Assistent arbeitet mit Ihrem Modellschlüssel — legen Sie ihn in den Einstellungen fest:" :en "The assistant works with your model key — set it in Settings:"}
   :chat/questions-drafted {:ru "Составлено вопросов: {n} (отброшено с ошибкой формы: {bad}). Примите подходящие." :de "{n} Fragen entworfen ({bad} mit Formfehlern verworfen). Nehmen Sie die passenden an." :en "{n} questions drafted ({bad} dropped as malformed). Accept the ones that fit."}
   :chat/question-draft-tag {:ru "Вопрос" :de "Frage" :en "Question"}
   :chat/build-tag {:ru "Ваш курс" :de "Ihr Kurs" :en "Your course"}
   :chat/build-lead {:ru "Цель: «{goal}». Примите темы и материалы выше, потом — следующий шаг." :de "Ziel: „{goal}“. Nehmen Sie die Themen und Materialien oben an, dann den nächsten Schritt." :en "Goal: “{goal}”. Accept the topics and material above, then the next step."}
   :chat/build-questions {:ru "Составить проверочные вопросы для тем: {n}" :de "Prüffragen für {n} Themen entwerfen" :en "Draft check questions for {n} topics"}
   :chat/build-course {:ru "Собрать курс из готовых тем ({n}) и начать" :de "Kurs aus {n} fertigen Themen bauen und starten" :en "Build the course from {n} ready topics and start"}
   :chat/build-accept-first {:ru "Сначала примите хотя бы одну тему с материалом." :de "Nehmen Sie zuerst mindestens ein Thema mit Material an." :en "First accept at least one topic with material."}
   :chat/build-default-title {:ru "Мой курс" :de "Mein Kurs" :en "My course"}
   :guide/action-build {:ru "Собрать курс «{goal}» из своего материала" :de "Kurs „{goal}“ aus eigenem Material bauen" :en "Build “{goal}” from your own material"}
   :guide/building {:ru "Это ваш личный граф «{goal}». Ассистент читает материал — примите темы, затем шаг «Ваш курс»." :de "Das ist Ihr eigener Graph „{goal}“. Der Assistent liest das Material — nehmen Sie die Themen an, dann den Schritt „Ihr Kurs“." :en "This is your own graph “{goal}”. The assistant is reading the material — accept the topics, then the «Your course» step."}
   :chat/mode-course {:ru "режим курса" :de "Kursmodus" :en "course mode"}
   :chat/claim-refused {:ru "В этой беседе есть неразобранные предложения для графа «{title}». Сначала разберите их («Открыть граф») или начните новую беседу (✎)." :de "Dieses Gespräch enthält offene Vorschläge für den Graphen „{title}“. Bearbeiten Sie sie zuerst („Graph öffnen“) oder beginnen Sie ein neues Gespräch (✎)." :en "This conversation holds undecided proposals for the graph “{title}”. Review them first (“Open graph”) or start a new conversation (✎)."}
   :chat/file-unsupported {:ru "этот формат не читается — подойдут PDF, Word (.docx), веб-страница (.html), .md или .txt; старый .doc сохраните как .docx" :de "dieses Format ist nicht lesbar — es gehen PDF, Word (.docx), Webseite (.html), .md oder .txt; ein altes .doc bitte als .docx speichern" :en "this format can't be read — PDF, Word (.docx), a web page (.html), .md or .txt will do; save an old .doc as .docx"}
   :chat/docx-broken {:ru "файл Word повреждён или это не .docx — откройте его и сохраните заново" :de "die Word-Datei ist beschädigt oder kein .docx — bitte öffnen und neu speichern" :en "the Word file is damaged or not a .docx — open it and save it again"}
   :chat/busy-wait {:ru "Подождите, пока закончится текущий шаг." :de "Warten Sie, bis der aktuelle Schritt fertig ist." :en "Wait for the current step to finish."}
   :chat/reversion-next {:ru "«{title}» устарела. Дальше: примите материал из новой версии, затем снимите старый материал этой темы — иначе тема так и останется устаревшей." :de "„{title}“ ist veraltet. Als Nächstes: Material aus der neuen Version annehmen, dann das alte Material dieses Themas zurückstufen — sonst bleibt das Thema veraltet." :en "“{title}” is stale. Next: accept the new version's material, then demote the old material on this topic — or the topic stays stale."}
   :chat/step-questions {:ru "составляю вопросы" :de "entwerfe Fragen" :en "drafting questions"}
   :chat/question-topic-gone {:ru "Темы этого вопроса больше нет в графе — вопрос не добавлен." :de "Das Thema dieser Frage gibt es im Graphen nicht mehr — die Frage wurde nicht hinzugefügt." :en "This question's topic is no longer in the graph — the question was not added."}
   :guide/build-empty {:ru "В файле «{name}» нет текста — курс не из чего собрать. Ничего не создано." :de "Die Datei „{name}“ enthält keinen Text — daraus lässt sich kein Kurs bauen. Nichts wurde angelegt." :en "The file “{name}” has no text — there is nothing to build a course from. Nothing was created."}
   ;; merge-file cards (loretile.merge)
   :chat/merge-summary {:ru "Файл «{name}»: карточек — {n}. Новые темы и связи — пунктиром на карте, изменения и удаления подсвечены на своих темах. Принимайте по одной."
                        :de "Datei „{name}“: {n} Karten. Neue Themen und Verknüpfungen gestrichelt auf der Karte, Änderungen und Entfernungen an ihren Themen markiert. Eine nach der anderen annehmen."
                        :en "File “{name}”: {n} cards. New topics and links are dashed on the map; edits and removals are marked on their topics. Accept them one by one."}
   :chat/item-tag {:ru "вопрос" :de "Frage" :en "question"}
   :chat/change-tag {:ru "изменение" :de "Änderung" :en "edit"}
   :chat/remove-tag {:ru "удаление" :de "Entfernung" :en "removal"}
   :chat/other-tag {:ru "событие" :de "Ereignis" :en "event"}
   :chat/change-patch {:ru " · правка" :de " · Korrektur" :en " · patch"}
   :chat/change-material {:ru " · по существу" :de " · wesentlich" :en " · material"}
   :chat/apply {:ru "Применить" :de "Anwenden" :en "Apply"}
   :guide/build-unreadable {:ru "Не смог прочитать «{name}»: {error}. Ничего не создано." :de "„{name}“ konnte nicht gelesen werden: {error}. Nichts wurde angelegt." :en "Could not read “{name}”: {error}. Nothing was created."}})

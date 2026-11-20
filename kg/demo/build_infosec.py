#!/usr/bin/env python3
"""Builds kg/demo/infosec.{ru,de,en}.edn — the «Информационная безопасность»
demo graph (corporate onboarding pitch): 14 topics, 2 sources, 20 objects,
28 items, one published course. Same architecture as build.py: translations
live inline next to the Russian original, a missing one fails the build,
the three locales share event ids and timestamps. Regenerate:
python3 kg/demo/build_infosec.py"""
import json
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from changes import as_change  # noqa: E402

T0 = 1757000000000
LOCS = ("ru", "en", "de")


def tr(d, what):
    for l in LOCS:
        assert l in d and d[l], f"missing {l} translation: {what}"
    return d


def edn(v):
    if isinstance(v, dict):
        return "{" + " ".join(f":{k} {edn(x)}" for k, x in v.items()) + "}"
    if isinstance(v, list):
        return "[" + " ".join(edn(x) for x in v) + "]"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, str):
        return v if v.startswith(":") else json.dumps(v, ensure_ascii=False)
    raise TypeError(v)


SOURCES = [
    ("src-policy", tr({"ru": "Политика информационной безопасности", "en": "Information security policy",
                       "de": "Richtlinie zur Informationssicherheit"}, "src-policy"), "security-policy.pdf"),
    ("src-memo", tr({"ru": "Памятка нового сотрудника", "en": "New employee memo",
                     "de": "Merkblatt für neue Mitarbeitende"}, "src-memo"), "newcomer-memo.pdf"),
]

# (id, kind, title{}, definition{}, source)
C = [
    ("passwords", ":procedure",
     {"ru": "Надёжные пароли и парольные фразы", "en": "Strong passwords and passphrases", "de": "Starke Passwörter und Passphrasen"},
     {"ru": "Длина важнее сложности: фраза из четырёх случайных слов надёжнее, чем P@ssw0rd. Один сервис — один пароль, повторное использование превращает одну утечку в десять.",
      "en": "Length beats complexity: a phrase of four random words is stronger than P@ssw0rd. One service, one password — reuse turns a single leak into ten.",
      "de": "Länge schlägt Komplexität: eine Phrase aus vier zufälligen Wörtern ist stärker als P@ssw0rd. Ein Dienst, ein Passwort — Wiederverwendung macht aus einem Leck zehn."}, "src-policy"),
    ("password-manager", ":procedure",
     {"ru": "Менеджер паролей", "en": "Password manager", "de": "Passwort-Manager"},
     {"ru": "Хранит уникальные пароли за вас; помнить нужно только мастер-фразу. Корпоративный менеджер — единственное разрешённое место для рабочих паролей.",
      "en": "Keeps unique passwords for you; you memorise only the master phrase. The corporate manager is the only approved place for work passwords.",
      "de": "Verwahrt einzigartige Passwörter für Sie; Sie merken sich nur die Master-Phrase. Der Firmen-Manager ist der einzige zugelassene Ort für Arbeitspasswörter."}, "src-policy"),
    ("mfa", ":principle",
     {"ru": "Двухфакторная аутентификация (2FA)", "en": "Two-factor authentication (2FA)", "de": "Zwei-Faktor-Authentifizierung (2FA)"},
     {"ru": "Второй фактор — то, что у вас ЕСТЬ (приложение, ключ), поверх того, что вы ЗНАЕТЕ. Украденный пароль без второго фактора бесполезен.",
      "en": "The second factor is something you HAVE (an app, a key) on top of something you KNOW. A stolen password is useless without it.",
      "de": "Der zweite Faktor ist etwas, das Sie HABEN (App, Schlüssel), zusätzlich zu etwas, das Sie WISSEN. Ein gestohlenes Passwort ist ohne ihn nutzlos."}, "src-policy"),
    ("social-engineering", ":principle",
     {"ru": "Социальная инженерия", "en": "Social engineering", "de": "Social Engineering"},
     {"ru": "Атака на человека, а не на технику: срочность, авторитет и страх выключают проверку. Любое «срочно, только никому не говорите» — сигнал остановиться.",
      "en": "An attack on the person, not the machine: urgency, authority and fear switch off scrutiny. Any “urgent, tell no one” is the cue to stop.",
      "de": "Ein Angriff auf den Menschen, nicht auf die Technik: Dringlichkeit, Autorität und Angst schalten die Prüfung aus. Jedes „dringend, sagen Sie es niemandem“ ist das Signal anzuhalten."}, "src-memo"),
    ("phishing", ":definition",
     {"ru": "Фишинг", "en": "Phishing", "de": "Phishing"},
     {"ru": "Письмо или сообщение, маскирующееся под доверенного отправителя, чтобы выманить пароль или заставить открыть вложение. Проверяйте адрес отправителя и домен ссылки, не текст.",
      "en": "A message impersonating a trusted sender to harvest a password or make you open an attachment. Check the sender address and the link's domain, not the wording.",
      "de": "Eine Nachricht, die einen vertrauten Absender vortäuscht, um ein Passwort abzugreifen oder einen Anhang öffnen zu lassen. Prüfen Sie Absenderadresse und Link-Domain, nicht den Wortlaut."}, "src-policy"),
    ("attachments", ":procedure",
     {"ru": "Ссылки и вложения", "en": "Links and attachments", "de": "Links und Anhänge"},
     {"ru": "Наведите курсор до клика: настоящий адрес виден внизу. Неожиданное вложение — даже «от коллеги» — сначала переспросить по другому каналу.",
      "en": "Hover before you click: the real address shows at the bottom. An unexpected attachment — even “from a colleague” — gets confirmed through another channel first.",
      "de": "Erst zeigen, dann klicken: die echte Adresse erscheint unten. Ein unerwarteter Anhang — auch „von einer Kollegin“ — wird zuerst über einen anderen Kanal bestätigt."}, "src-memo"),
    ("updates", ":procedure",
     {"ru": "Обновления ПО", "en": "Software updates", "de": "Software-Updates"},
     {"ru": "Обновления закрывают известные дыры; отложенное обновление — открытая дверь с опубликованным чертежом замка. Автообновление включено — не выключайте его.",
      "en": "Updates close known holes; a postponed update is an open door with the lock's blueprint published. Auto-update is on — leave it on.",
      "de": "Updates schließen bekannte Lücken; ein aufgeschobenes Update ist eine offene Tür mit veröffentlichtem Bauplan des Schlosses. Auto-Update ist an — lassen Sie es an."}, "src-policy"),
    ("device-lock", ":procedure",
     {"ru": "Блокировка и шифрование устройства", "en": "Device lock and encryption", "de": "Gerätesperre und Verschlüsselung"},
     {"ru": "Экран блокируется сам через 5 минут, но выходя — блокируйте руками. Диск зашифрован: потерянный ноутбук — потеря железа, не данных.",
      "en": "The screen locks itself after 5 minutes, but lock it yourself when you step away. The disk is encrypted: a lost laptop loses hardware, not data.",
      "de": "Der Bildschirm sperrt sich nach 5 Minuten selbst — beim Aufstehen sperren Sie ihn trotzdem selbst. Die Platte ist verschlüsselt: ein verlorener Laptop kostet Hardware, keine Daten."}, "src-policy"),
    ("public-wifi", ":procedure",
     {"ru": "Публичный Wi-Fi и VPN", "en": "Public Wi-Fi and VPN", "de": "Öffentliches WLAN und VPN"},
     {"ru": "В кафе и аэропорту сеть видит ваш трафик. Рабочие системы — только через корпоративный VPN; без VPN — только то, что не жалко показать всем.",
      "en": "In a café or airport the network sees your traffic. Work systems only through the corporate VPN; without it, only what you would show to anyone.",
      "de": "Im Café oder Flughafen sieht das Netz Ihren Verkehr. Arbeitssysteme nur über das Firmen-VPN; ohne VPN nur, was alle sehen dürften."}, "src-memo"),
    ("data-classes", ":definition",
     {"ru": "Классификация данных", "en": "Data classification", "de": "Datenklassifizierung"},
     {"ru": "Публичное, внутреннее, конфиденциальное, персональное — класс определяет, куда данные можно класть и кому показывать. Не уверены в классе — считайте конфиденциальным.",
      "en": "Public, internal, confidential, personal — the class decides where data may live and who may see it. Unsure of the class — treat it as confidential.",
      "de": "Öffentlich, intern, vertraulich, personenbezogen — die Klasse bestimmt, wo Daten liegen dürfen und wer sie sehen darf. Im Zweifel gilt: vertraulich."}, "src-policy"),
    ("personal-data", ":procedure",
     {"ru": "Работа с персональными данными", "en": "Handling personal data", "de": "Umgang mit personenbezogenen Daten"},
     {"ru": "Имя с телефоном — уже персональные данные. Собираем минимум, храним только в учтённых системах, не пересылаем в мессенджеры и личную почту.",
      "en": "A name with a phone number is already personal data. Collect the minimum, store only in registered systems, never forward to messengers or private email.",
      "de": "Ein Name mit Telefonnummer ist bereits personenbezogen. Minimum erheben, nur in registrierten Systemen speichern, nie an Messenger oder private Mail weiterleiten."}, "src-policy"),
    ("incident", ":procedure",
     {"ru": "Сообщение об инциденте", "en": "Incident reporting", "de": "Vorfallsmeldung"},
     {"ru": "Кликнули на фишинг — сообщите в первые 10 минут: смена пароля и отзыв сессий обгоняют атакующего. Сообщивших не наказывают; наказуемо молчание.",
      "en": "Clicked a phish — report within the first 10 minutes: a password change and session revocation outrun the attacker. Reporters are never punished; silence is.",
      "de": "Auf Phishing geklickt — melden Sie es in den ersten 10 Minuten: Passwortwechsel und Sitzungs-Widerruf überholen den Angreifer. Wer meldet, wird nicht bestraft; Schweigen schon."}, "src-memo"),
    ("backups", ":procedure",
     {"ru": "Резервные копии", "en": "Backups", "de": "Datensicherung"},
     {"ru": "Рабочие файлы живут в синхронизируемых папках — их бэкапит компания. Файл только на рабочем столе не существует: один пролитый кофе — и его нет.",
      "en": "Work files live in synced folders — the company backs those up. A file that exists only on your desktop does not exist: one spilled coffee and it is gone.",
      "de": "Arbeitsdateien liegen in synchronisierten Ordnern — die sichert die Firma. Eine Datei nur auf dem Desktop existiert nicht: ein verschütteter Kaffee, und sie ist weg."}, "src-memo"),
    ("clean-desk", ":procedure",
     {"ru": "Чистый стол и экран", "en": "Clean desk and screen", "de": "Clean Desk und Bildschirm"},
     {"ru": "Распечатки с конфиденциальным — в шредер, не в корзину; пароль на стикере — подарок гостю офиса. В переговорной стирайте доску после встречи.",
      "en": "Confidential printouts go to the shredder, not the bin; a password on a sticky note is a gift to any office visitor. Wipe the meeting-room whiteboard when you leave.",
      "de": "Vertrauliche Ausdrucke in den Schredder, nicht in den Papierkorb; ein Passwort auf einem Zettel ist ein Geschenk an jeden Bürogast. Wischen Sie das Whiteboard nach dem Termin ab."}, "src-policy"),
]

REQ = [  # (dependent, prerequisite)
    ("password-manager", "passwords"), ("mfa", "passwords"),
    ("phishing", "social-engineering"), ("attachments", "phishing"),
    ("incident", "phishing"), ("public-wifi", "device-lock"),
    ("personal-data", "data-classes"), ("clean-desk", "data-classes"),
    ("backups", "device-lock"),
]
EXTRA = [
    ("phishing", "social-engineering", ":is-confused-with",
     tr({"ru": "Фишинг — конкретное письмо-приманка; социальная инженерия — весь класс атак на человека.",
         "en": "Phishing is the concrete bait message; social engineering is the whole class of attacks on people.",
         "de": "Phishing ist die konkrete Köder-Nachricht; Social Engineering die ganze Klasse von Angriffen auf Menschen."}, "conf-1")),
    ("personal-data", "data-classes", ":is-confused-with",
     tr({"ru": "Персональные данные — один из классов; классификация — правило, по которому классы назначаются.",
         "en": "Personal data is one of the classes; classification is the rule that assigns them.",
         "de": "Personenbezogene Daten sind eine der Klassen; die Klassifizierung ist die Regel, die sie zuweist."}, "conf-2")),
    ("mfa", "password-manager", ":see-also", None),
    ("backups", "incident", ":see-also", None),
]

# (concept, kind, level, text{}, source, anchor)
OBJ = [
    ("passwords", ":explanation", ":novice",
     {"ru": "«кит-фонарь-тёплый-зонт» взламывается веками, P@ssw0rd1 — за минуты: словари знают все замены букв на символы. Четыре случайных слова, которых нет в одной фразе.",
      "en": "“whale-lantern-warm-umbrella” takes centuries to crack, P@ssw0rd1 minutes: dictionaries know every letter-for-symbol swap. Four random words that never share a phrase.",
      "de": "„Wal-Laterne-warm-Schirm“ zu knacken dauert Jahrhunderte, P@ssw0rd1 Minuten: Wörterbücher kennen jeden Buchstaben-Symbol-Tausch. Vier zufällige Wörter, die nie in einem Satz stehen."}, "src-policy", "p4"),
    ("passwords", ":worked-example", ":apprentice",
     {"ru": "Утёк пароль от форума, тот же пароль в почте: за час атакующий в почте, а из почты сбрасываются все остальные пароли. Поэтому повторное использование — главный запрет политики.",
      "en": "A forum password leaks, the same password guards your email: within an hour the attacker is in the mailbox, and from the mailbox every other password can be reset. That is why reuse is the policy's first prohibition.",
      "de": "Ein Forum-Passwort leckt, dasselbe schützt Ihre Mail: binnen einer Stunde ist der Angreifer im Postfach, und vom Postfach aus lässt sich jedes andere Passwort zurücksetzen. Darum ist Wiederverwendung das erste Verbot der Richtlinie."}, "src-policy", "p5"),
    ("password-manager", ":explanation", ":apprentice",
     {"ru": "Мастер-фраза открывает сейф; всё остальное менеджер генерирует и подставляет сам. Ещё и фишинг ловит: на поддельном домене автозаполнение молчит.",
      "en": "The master phrase opens the vault; everything else the manager generates and fills in itself. It even catches phishing: on a fake domain, autofill stays silent.",
      "de": "Die Master-Phrase öffnet den Tresor; alles andere erzeugt und trägt der Manager selbst ein. Er fängt sogar Phishing: auf einer gefälschten Domain bleibt das Autofill stumm."}, "src-policy", "p7"),
    ("mfa", ":explanation", ":apprentice",
     {"ru": "Пароль знают двое — вы и, после утечки, атакующий. Код из приложения есть только у вашего телефона. Push-усталость — тоже атака: не подтверждайте вход, которого не начинали.",
      "en": "A password is known by two — you and, after a leak, the attacker. The app code exists only on your phone. Push fatigue is an attack too: never approve a sign-in you did not start.",
      "de": "Ein Passwort kennen zwei — Sie und, nach einem Leck, der Angreifer. Den App-Code hat nur Ihr Telefon. Push-Müdigkeit ist auch ein Angriff: bestätigen Sie nie eine Anmeldung, die Sie nicht begonnen haben."}, "src-policy", "p9"),
    ("mfa", ":worked-example", ":apprentice",
     {"ru": "Ночью приходит push «Подтвердить вход?». Вы не входили — значит, пароль уже у кого-то. Отклонить, сменить пароль, сообщить: три шага, две минуты.",
      "en": "A push arrives at night: “Approve sign-in?”. You were not signing in — so someone already has the password. Decline, change the password, report: three steps, two minutes.",
      "de": "Nachts kommt ein Push: „Anmeldung bestätigen?“. Sie melden sich nicht an — also hat jemand bereits das Passwort. Ablehnen, Passwort ändern, melden: drei Schritte, zwei Minuten."}, "src-memo", "p3"),
    ("social-engineering", ":explanation", ":apprentice",
     {"ru": "«Это финдиректор, платёж нужен до 15:00, совету не говорите» — срочность, авторитет, секретность в одном сообщении. Настоящий директор переживёт проверку по телефону.",
      "en": "“This is the CFO, the payment must go out by 3 pm, don't tell the board” — urgency, authority and secrecy in one message. A real CFO survives a phone check.",
      "de": "„Hier der CFO, die Zahlung muss bis 15 Uhr raus, sagen Sie dem Vorstand nichts“ — Dringlichkeit, Autorität und Geheimhaltung in einer Nachricht. Ein echter CFO übersteht den Rückruf."}, "src-memo", "p5"),
    ("phishing", ":explanation", ":apprentice",
     {"ru": "Имя отправителя пишет сам отправитель; правду говорит только адрес. «IT Support <it-support@c0mpany-hr.net>» — читайте домен справа налево: главный — последний.",
      "en": "The display name is written by the sender; only the address tells the truth. “IT Support <it-support@c0mpany-hr.net>” — read the domain right to left: the last part is the real one.",
      "de": "Den Anzeigenamen schreibt der Absender selbst; die Wahrheit sagt nur die Adresse. „IT Support <it-support@c0mpany-hr.net>“ — lesen Sie die Domain von rechts: der letzte Teil ist der echte."}, "src-policy", "p11"),
    ("phishing", ":diagram", ":apprentice",
     {"ru": "Схема письма с четырьмя метками: чужой домен отправителя, безличное обращение, счётчик срочности, ссылка, чей видимый текст не совпадает с адресом под курсором.",
      "en": "An annotated email with four flags: foreign sender domain, impersonal greeting, urgency timer, and a link whose visible text differs from the hover address.",
      "de": "Eine kommentierte Mail mit vier Markern: fremde Absender-Domain, unpersönliche Anrede, Dringlichkeits-Zähler und ein Link, dessen sichtbarer Text nicht zur Hover-Adresse passt."}, "src-policy", "p12"),
    ("attachments", ":explanation", ":apprentice",
     {"ru": "Счёт-фактура.pdf.exe — исполняемый файл с нарисованной иконкой PDF. Расширений два — верить последнему. Макросы в офисных файлах не включаем по просьбе документа.",
      "en": "Invoice.pdf.exe is an executable wearing a PDF icon. Two extensions — trust the last one. Never enable office-file macros because the document asks you to.",
      "de": "Rechnung.pdf.exe ist eine ausführbare Datei mit PDF-Symbol. Zwei Endungen — die letzte zählt. Makros in Office-Dateien nie aktivieren, weil das Dokument darum bittet."}, "src-memo", "p7"),
    ("attachments", ":worked-example", ":apprentice",
     {"ru": "Письмо «от бухгалтерии» со ссылкой «портал зарплат». Наводим курсор: pay-roll.company.example.ru.attacker.net. Домен читается справа: attacker.net. Не кликаем, пересылаем в ИБ.",
      "en": "An email “from payroll” with a “salary portal” link. Hover: pay-roll.company.example.com.attacker.net. Read from the right: attacker.net. No click; forward to security.",
      "de": "Eine Mail „von der Lohnbuchhaltung“ mit Link „Gehaltsportal“. Hover: pay-roll.company.example.de.attacker.net. Von rechts gelesen: attacker.net. Nicht klicken; an die IT-Sicherheit weiterleiten."}, "src-memo", "p8"),
    ("updates", ":explanation", ":novice",
     {"ru": "«Напомнить завтра» семь дней подряд — неделя с известной всем дырой. Перезагрузка стоит пять минут; разбор взлома — недели.",
      "en": "“Remind me tomorrow” seven days in a row is a week with a publicly known hole. The reboot costs five minutes; incident cleanup costs weeks.",
      "de": "Sieben Tage „Morgen erinnern“ ist eine Woche mit öffentlich bekannter Lücke. Der Neustart kostet fünf Minuten; die Aufarbeitung eines Einbruchs Wochen."}, "src-policy", "p14"),
    ("device-lock", ":explanation", ":novice",
     {"ru": "Win+L или Ctrl+Cmd+Q — привычка на выходе из-за стола. Незаблокированный экран в опенспейсе — это ваша почта, ваши доступы и письмо «от вас» любому адресату.",
      "en": "Win+L or Ctrl+Cmd+Q — the habit for every time you stand up. An unlocked screen in an open office is your email, your access, and a message “from you” to anyone.",
      "de": "Win+L oder Ctrl+Cmd+Q — die Gewohnheit bei jedem Aufstehen. Ein entsperrter Bildschirm im Großraum ist Ihre Mail, Ihr Zugang und eine Nachricht „von Ihnen“ an beliebige Empfänger."}, "src-policy", "p16"),
    ("public-wifi", ":explanation", ":apprentice",
     {"ru": "Точка «Airport_Free_WiFi» может стоять в рюкзаке соседа. VPN заворачивает весь трафик в шифрованный туннель — тогда неважно, чья сеть.",
      "en": "The “Airport_Free_WiFi” hotspot may live in the next seat's backpack. The VPN wraps all traffic in an encrypted tunnel — then it no longer matters whose network it is.",
      "de": "Der Hotspot „Airport_Free_WiFi“ kann im Rucksack des Nachbarn stecken. Das VPN legt den gesamten Verkehr in einen verschlüsselten Tunnel — dann ist egal, wessen Netz es ist."}, "src-memo", "p10"),
    ("data-classes", ":explanation", ":apprentice",
     {"ru": "Четыре класса — четыре места хранения. Публичное — куда угодно; внутреннее — корпоративные системы; конфиденциальное — только допущенным; персональное — только учтённые системы и законное основание.",
      "en": "Four classes, four homes. Public — anywhere; internal — corporate systems; confidential — cleared people only; personal — registered systems and a legal basis only.",
      "de": "Vier Klassen, vier Orte. Öffentlich — überallhin; intern — Firmensysteme; vertraulich — nur Berechtigte; personenbezogen — nur registrierte Systeme mit Rechtsgrundlage."}, "src-policy", "p19"),
    ("personal-data", ":worked-example", ":apprentice",
     {"ru": "Нужно поделиться списком участников с подрядчиком: не Excel в мессенджер, а доступ к таблице в учтённой системе, по ролям, с датой окончания.",
      "en": "Sharing an attendee list with a contractor: not an Excel file in a messenger but role-based access to the table in a registered system, with an expiry date.",
      "de": "Eine Teilnehmerliste mit einem Dienstleister teilen: kein Excel im Messenger, sondern rollenbasierter Zugriff auf die Tabelle im registrierten System, mit Ablaufdatum."}, "src-policy", "p21"),
    ("personal-data", ":explanation", ":apprentice",
     {"ru": "Минимизация — главный принцип: не собирали — нечему утекать. Каждое поле анкеты должно отвечать на вопрос «зачем».",
      "en": "Minimisation is the core principle: what you never collected cannot leak. Every form field must answer the question “what for”.",
      "de": "Minimierung ist das Kernprinzip: was nie erhoben wurde, kann nicht lecken. Jedes Formularfeld muss die Frage „wozu“ beantworten."}, "src-policy", "p20"),
    ("incident", ":explanation", ":apprentice",
     {"ru": "Порядок: 1) отключиться от сети, 2) позвонить в ИБ, 3) ничего не удалять — следы нужны разбору. Стыд — худший советчик: счёт идёт на минуты.",
      "en": "The order: 1) disconnect from the network, 2) call security, 3) delete nothing — the traces feed the investigation. Shame is the worst advisor: the clock runs in minutes.",
      "de": "Die Reihenfolge: 1) vom Netz trennen, 2) die IT-Sicherheit anrufen, 3) nichts löschen — die Spuren braucht die Analyse. Scham ist der schlechteste Ratgeber: es zählt jede Minute."}, "src-memo", "p12"),
    ("incident", ":worked-example", ":apprentice",
     {"ru": "9:02 клик по фишинговой ссылке, 9:05 звонок в ИБ, 9:08 пароль сменён, сессии отозваны. Итог: ноль ущерба. Тот же клик, о котором молчали до вечера, — скомпрометированная почта.",
      "en": "9:02 a phishing link is clicked, 9:05 security is called, 9:08 the password is changed and sessions revoked. Result: zero damage. The same click kept quiet until evening: a compromised mailbox.",
      "de": "9:02 Klick auf den Phishing-Link, 9:05 Anruf bei der IT-Sicherheit, 9:08 Passwort geändert, Sitzungen widerrufen. Ergebnis: kein Schaden. Derselbe Klick, bis abends verschwiegen: ein kompromittiertes Postfach."}, "src-memo", "p13"),
    ("backups", ":explanation", ":novice",
     {"ru": "Правило простое: важное лежит там, где его бэкапит компания. Проверка одна — «если ноутбук утонет сейчас, что я потеряю?» Ответ должен быть: «ничего».",
      "en": "The rule is simple: anything important lives where the company backs it up. One test — “if this laptop drowned right now, what would I lose?” The answer must be “nothing”.",
      "de": "Die Regel ist einfach: Wichtiges liegt dort, wo die Firma es sichert. Ein Test — „wenn dieser Laptop jetzt ertrinkt, was verliere ich?“ Die Antwort muss „nichts“ sein."}, "src-memo", "p15"),
    ("clean-desk", ":explanation", ":novice",
     {"ru": "Вечером на столе — ничего с данными: распечатки в шкаф или шредер, носители в ящик под ключ. Экран к проходу — поставьте фильтр приватности.",
      "en": "By evening the desk holds nothing with data on it: printouts to the cabinet or shredder, media locked in the drawer. Screen facing the aisle — add a privacy filter.",
      "de": "Abends liegt nichts mit Daten auf dem Tisch: Ausdrucke in den Schrank oder Schredder, Datenträger in die abschließbare Schublade. Bildschirm zum Gang — Sichtschutzfilter anbringen."}, "src-policy", "p23"),
]

def sc(stem, opts, key): return (":single-choice", {"stem": stem, "options": opts, "key": key})
def cz(text, key): return (":cloze", {"text": text, "key": key})

# (concept, kind, spec{ru,en,de each -> spec dict})
ITEMS = [
    ("passwords", {
        "ru": sc("Какой пароль надёжнее?", ["P@ssw0rd2026!", "кит-фонарь-тёплый-зонт", "qwerty123456"], 1),
        "en": sc("Which password is stronger?", ["P@ssw0rd2026!", "whale-lantern-warm-umbrella", "qwerty123456"], 1),
        "de": sc("Welches Passwort ist stärker?", ["P@ssw0rd2026!", "Wal-Laterne-warm-Schirm", "qwerty123456"], 1)}),
    ("passwords", {
        "ru": sc("Почему нельзя использовать один пароль в двух сервисах?", ["Это замедляет вход", "Одна утечка открывает оба сервиса", "Сервисы это запрещают"], 1),
        "en": sc("Why must a password never serve two services?", ["It slows down sign-in", "One leak opens both services", "Services forbid it"], 1),
        "de": sc("Warum darf ein Passwort nie zwei Dienste schützen?", ["Es verlangsamt die Anmeldung", "Ein Leck öffnet beide Dienste", "Die Dienste verbieten es"], 1)}),
    ("passwords", {
        "ru": cz("В надёжной парольной фразе важнее всего ___", "длина"),
        "en": cz("The property that matters most in a passphrase is ___", "length"),
        "de": cz("Die wichtigste Eigenschaft einer Passphrase ist ihre ___", "Länge")}),
    ("password-manager", {
        "ru": sc("Что нужно помнить при работе с менеджером паролей?", ["Все пароли", "Только мастер-фразу", "Ничего"], 1),
        "en": sc("What do you memorise when using a password manager?", ["Every password", "Only the master phrase", "Nothing"], 1),
        "de": sc("Was merken Sie sich beim Passwort-Manager?", ["Jedes Passwort", "Nur die Master-Phrase", "Nichts"], 1)}),
    ("mfa", {
        "ru": sc("Второй фактор — это…", ["Второй пароль", "То, что у вас есть: приложение или ключ", "Секретный вопрос"], 1),
        "en": sc("The second factor is…", ["A second password", "Something you have: an app or a key", "A secret question"], 1),
        "de": sc("Der zweite Faktor ist…", ["Ein zweites Passwort", "Etwas, das Sie haben: App oder Schlüssel", "Eine Sicherheitsfrage"], 1)}),
    ("mfa", {
        "ru": sc("Ночью пришёл push «Подтвердить вход?», вы не входили. Что делать?", ["Подтвердить, чтобы отстало", "Отклонить, сменить пароль, сообщить", "Игнорировать"], 1),
        "en": sc("A night push asks “Approve sign-in?” — you were not signing in. You…", ["Approve it to stop the noise", "Decline, change the password, report", "Ignore it"], 1),
        "de": sc("Nachts fragt ein Push „Anmeldung bestätigen?“ — Sie melden sich nicht an. Sie…", ["Bestätigen, damit Ruhe ist", "Ablehnen, Passwort ändern, melden", "Ignorieren"], 1)}),
    ("mfa", {
        "ru": cz("Украденный пароль без второго фактора ___", "бесполезен"),
        "en": cz("Without the second factor, a stolen password is ___", "useless"),
        "de": cz("Ohne zweiten Faktor ist ein gestohlenes Passwort ___", "nutzlos")}),
    ("social-engineering", {
        "ru": sc("Три рычага социальной инженерии — это…", ["Срочность, авторитет, страх", "Шифрование, сеть, код", "Логин, пароль, токен"], 0),
        "en": sc("The three levers of social engineering are…", ["Urgency, authority, fear", "Encryption, network, code", "Login, password, token"], 0),
        "de": sc("Die drei Hebel des Social Engineering sind…", ["Dringlichkeit, Autorität, Angst", "Verschlüsselung, Netz, Code", "Login, Passwort, Token"], 0)}),
    ("phishing", {
        "ru": sc("Что в письме говорит правду об отправителе?", ["Имя отправителя", "Адрес и его домен", "Подпись в конце"], 1),
        "en": sc("What in an email tells the truth about the sender?", ["The display name", "The address and its domain", "The signature"], 1),
        "de": sc("Was in einer Mail sagt die Wahrheit über den Absender?", ["Der Anzeigename", "Die Adresse und ihre Domain", "Die Signatur"], 1)}),
    ("phishing", {
        "ru": sc("Ссылка ведёт на portal.company.example.ru.evil.net. Чей это сайт?", ["company.example.ru", "portal", "evil.net"], 2),
        "en": sc("A link goes to portal.company.example.com.evil.net. Whose site is it?", ["company.example.com", "portal", "evil.net"], 2),
        "de": sc("Ein Link führt zu portal.company.example.de.evil.net. Wessen Seite ist das?", ["company.example.de", "portal", "evil.net"], 2)}),
    ("phishing", {
        "ru": cz("Домен ссылки читают справа ___", "налево"),
        "en": cz("A link's domain is read from the ___", "right"),
        "de": cz("Die Domain eines Links liest man von ___", "rechts")}),
    ("attachments", {
        "ru": sc("Файл «Счёт.pdf.exe» — это…", ["PDF-документ", "Исполняемый файл под маской PDF", "Архив"], 1),
        "en": sc("A file named “Invoice.pdf.exe” is…", ["A PDF document", "An executable disguised as a PDF", "An archive"], 1),
        "de": sc("Eine Datei „Rechnung.pdf.exe“ ist…", ["Ein PDF-Dokument", "Eine als PDF getarnte ausführbare Datei", "Ein Archiv"], 1)}),
    ("attachments", {
        "ru": sc("Неожиданное вложение «от коллеги». Ваш первый шаг?", ["Открыть — коллега же", "Переспросить коллегу по другому каналу", "Переслать себе на личную почту"], 1),
        "en": sc("An unexpected attachment “from a colleague”. Your first move?", ["Open it — it's a colleague", "Confirm with the colleague via another channel", "Forward it to your private email"], 1),
        "de": sc("Ein unerwarteter Anhang „von einer Kollegin“. Ihr erster Schritt?", ["Öffnen — ist ja eine Kollegin", "Über einen anderen Kanal nachfragen", "An die private Mail weiterleiten"], 1)}),
    ("attachments", {
        "ru": cz("Настоящий адрес ссылки виден, если ___ курсор до клика", "навести"),
        "en": cz("The real link address shows when you ___ before clicking", "hover"),
        "de": cz("Die echte Link-Adresse erscheint, wenn Sie vor dem Klick mit der Maus darauf ___", "zeigen")}),
    ("updates", {
        "ru": sc("Чем опасно «напомнить завтра» неделю подряд?", ["Ничем", "Неделя с публично известной уязвимостью", "Обновление станет платным"], 1),
        "en": sc("Why is a week of “remind me tomorrow” dangerous?", ["It is not", "A week with a publicly known vulnerability", "The update becomes paid"], 1),
        "de": sc("Warum ist eine Woche „Morgen erinnern“ gefährlich?", ["Gar nicht", "Eine Woche mit öffentlich bekannter Lücke", "Das Update wird kostenpflichtig"], 1)}),
    ("device-lock", {
        "ru": sc("Выходите из-за стола на минуту. Экран…", ["Сам заблокируется — можно идти", "Блокируем руками: Win+L", "Достаточно свернуть окна"], 1),
        "en": sc("Stepping away for a minute. The screen…", ["Locks itself — just go", "Gets locked by hand: Win+L", "Minimising windows is enough"], 1),
        "de": sc("Sie stehen kurz auf. Der Bildschirm…", ["Sperrt sich selbst — einfach gehen", "Wird selbst gesperrt: Win+L", "Fenster minimieren genügt"], 1)}),
    ("device-lock", {
        "ru": sc("Потерян зашифрованный ноутбук. Что потеряно?", ["Данные и железо", "Только железо", "Только данные"], 1),
        "en": sc("An encrypted laptop is lost. What is lost?", ["Data and hardware", "Hardware only", "Data only"], 1),
        "de": sc("Ein verschlüsselter Laptop geht verloren. Was ist verloren?", ["Daten und Hardware", "Nur die Hardware", "Nur die Daten"], 1)}),
    ("device-lock", {
        "ru": cz("Незаблокированный экран в опенспейсе — это доступ к вашей ___", "почте"),
        "en": cz("An unlocked screen in an open office grants access to your ___", "email"),
        "de": cz("Ein entsperrter Bildschirm im Großraum ist Zugriff auf Ihre ___", "Mail")}),
    ("public-wifi", {
        "ru": sc("Рабочая почта из кафе — как правильно?", ["Через корпоративный VPN", "Через сеть кафе напрямую", "Никогда и никак"], 0),
        "en": sc("Work email from a café — the right way?", ["Through the corporate VPN", "Directly over the café network", "Never at all"], 0),
        "de": sc("Arbeitsmail im Café — der richtige Weg?", ["Über das Firmen-VPN", "Direkt über das Café-Netz", "Gar nicht, niemals"], 0)}),
    ("data-classes", {
        "ru": sc("Не уверены, к какому классу относятся данные. Считаем их…", ["Публичными", "Внутренними", "Конфиденциальными"], 2),
        "en": sc("Unsure which class data belongs to. Treat it as…", ["Public", "Internal", "Confidential"], 2),
        "de": sc("Unsicher, zu welcher Klasse Daten gehören. Behandeln Sie sie als…", ["Öffentlich", "Intern", "Vertraulich"], 2)}),
    ("personal-data", {
        "ru": sc("Что из этого — персональные данные?", ["Имя с номером телефона", "Название отдела", "Номер версии ПО"], 0),
        "en": sc("Which of these is personal data?", ["A name with a phone number", "A department name", "A software version"], 0),
        "de": sc("Was davon sind personenbezogene Daten?", ["Ein Name mit Telefonnummer", "Ein Abteilungsname", "Eine Softwareversion"], 0)}),
    ("personal-data", {
        "ru": sc("Список участников нужен подрядчику. Как поделиться?", ["Excel в мессенджер", "Доступ по ролям в учтённой системе", "Фото списка в чат"], 1),
        "en": sc("A contractor needs the attendee list. How to share it?", ["Excel via messenger", "Role-based access in a registered system", "A photo of the list in the chat"], 1),
        "de": sc("Ein Dienstleister braucht die Teilnehmerliste. Wie teilen?", ["Excel per Messenger", "Rollenbasierter Zugriff im registrierten System", "Ein Foto der Liste im Chat"], 1)}),
    ("personal-data", {
        "ru": cz("Главный принцип работы с персональными данными — ___", "минимизация"),
        "en": cz("The core principle of handling personal data is ___", "minimisation"),
        "de": cz("Das Kernprinzip beim Umgang mit personenbezogenen Daten ist die ___", "Minimierung")}),
    ("incident", {
        "ru": sc("Кликнули на фишинговую ссылку. Первое действие?", ["Удалить письмо и молчать", "Отключиться от сети и позвонить в ИБ", "Выключить ноутбук до завтра"], 1),
        "en": sc("You clicked a phishing link. First action?", ["Delete the email and stay quiet", "Disconnect from the network and call security", "Power off until tomorrow"], 1),
        "de": sc("Sie haben auf einen Phishing-Link geklickt. Erste Handlung?", ["Mail löschen und schweigen", "Vom Netz trennen und die IT-Sicherheit anrufen", "Laptop bis morgen ausschalten"], 1)}),
    ("incident", {
        "ru": sc("Почему нельзя ничего удалять после инцидента?", ["Жалко труда", "Следы нужны для разбора", "Удаление запрещено технически"], 1),
        "en": sc("Why delete nothing after an incident?", ["The work is precious", "The traces feed the investigation", "Deletion is technically impossible"], 1),
        "de": sc("Warum nach einem Vorfall nichts löschen?", ["Zu schade um die Arbeit", "Die Spuren braucht die Analyse", "Löschen ist technisch unmöglich"], 1)}),
    ("incident", {
        "ru": cz("Сообщение об инциденте в первые ___ минут обгоняет атакующего", "10"),
        "en": cz("Reporting within the first ___ minutes outruns the attacker", "10"),
        "de": cz("Eine Meldung in den ersten ___ Minuten überholt den Angreifer", "10")}),
    ("backups", {
        "ru": sc("Где должен жить важный рабочий файл?", ["На рабочем столе", "В синхронизируемой папке", "На флешке в ящике"], 1),
        "en": sc("Where must an important work file live?", ["On the desktop", "In a synced folder", "On a USB stick in a drawer"], 1),
        "de": sc("Wo muss eine wichtige Arbeitsdatei liegen?", ["Auf dem Desktop", "In einem synchronisierten Ordner", "Auf einem USB-Stick in der Schublade"], 1)}),
    ("clean-desk", {
        "ru": sc("Куда идут распечатки с конфиденциальными данными?", ["В корзину под столом", "В шредер", "В стопку на столе"], 1),
        "en": sc("Where do confidential printouts go?", ["The bin under the desk", "The shredder", "The pile on the desk"], 1),
        "de": sc("Wohin gehören vertrauliche Ausdrucke?", ["In den Papierkorb", "In den Schredder", "Auf den Stapel am Tisch"], 1)}),
]

QUESTION = ("q-mfa-sms", "mfa",
            tr({"ru": "Считается ли SMS-код вторым фактором для подрядчиков, у которых нет корпоративных телефонов? Политика молчит.",
                "en": "Does an SMS code count as a second factor for contractors without corporate phones? The policy is silent.",
                "de": "Zählt ein SMS-Code als zweiter Faktor für Dienstleister ohne Firmentelefon? Die Richtlinie schweigt."}, "question"))

COURSE = {"id": "infosec-onboarding",
          "title": tr({"ru": "Онбординг: цифровая гигиена", "en": "Onboarding: digital hygiene",
                       "de": "Onboarding: digitale Hygiene"}, "course-title"),
          "targets": ["passwords", "mfa", "phishing", "attachments", "personal-data", "incident"]}


def build(loc):
    events = []

    def ev(type_, **kv):
        e = {"ev/id": f"inf-{len(events):03d}", "ev/ns": "demo", "ev/actor": "demo-author",
             "ev/ts": T0 + len(events) * 60000, "ev/type": ":" + type_}
        as_change(e, type_, kv)
        events.append(e)

    for sid, title, ref in SOURCES:
        ev("source-added", source={"id": sid, "title": title[loc], "ref": ref})
    for cid, kind, title, definition, src in C:
        ev("concept-accepted", concept={"id": cid, "title": tr(title, cid)[loc],
                                        "definition": tr(definition, cid)[loc], "kind": kind, "source-id": src})
    for f, to in REQ:
        ev("edge-accepted", edge={"from": f, "to": to, "type": ":requires", "provenance": ":authored"})
    for f, to, typ, just in EXTRA:
        e = {"from": f, "to": to, "type": typ, "provenance": ":authored"}
        if just: e["justification"] = just[loc]
        ev("edge-accepted", edge=e)
    counts = {}
    for i, (cid, kind, level, text, src, anchor) in enumerate(OBJ):
        ev("object-added", object={"id": f"o-inf-{cid}-{i}", "concept-id": cid, "kind": kind,
                                   "level": level, "locale": loc, "origin": ":authored",
                                   "text": tr(text, f"obj {cid} {i}")[loc], "grounded-in": src, "anchor": anchor})
    for cid, spec3 in ITEMS:
        n = counts[cid] = counts.get(cid, 0) + 1
        kind, spec = tr(spec3, f"item {cid}")[loc]
        iid = f"i-inf-{cid}-{n}"
        ev("item-added", item={"id": iid, "primary-concept-id": cid, "level": ":apprentice",
                               "kind": kind, "locale": loc, "spec": spec})
        ev("item-accepted", id=iid)
    qid, qcid, qtext = QUESTION
    ev("question-raised", **{"id": qid, "concept-id": qcid, "question": qtext[loc]})
    ev("course-published", course={"id": COURSE["id"], "title": COURSE["title"][loc],
                                   "targets": [[t, ":apprentice"] for t in COURSE["targets"]],
                                   "locale": loc, "pass-threshold": 80,
                                   "diagnostic-items": 12, "final-items": 18})
    return events


out = {loc: build(loc) for loc in LOCS}
ids = [[e["ev/id"] for e in out[l]] for l in LOCS]
assert ids[0] == ids[1] == ids[2], "locale ev/id sequences diverged"
for loc in LOCS:
    with open(__file__.replace("build_infosec.py", f"infosec.{loc}.edn"), "w") as f:
        f.write(";; Demo KG — information security onboarding. GENERATED by build_infosec.py.\n")
        for e in out[loc]:
            f.write(edn(e) + "\n")
print(f"infosec.{{ru,de,en}}.edn: {len(out['ru'])} events each, {len(C)} concepts, "
      f"{len(REQ) + len(EXTRA)} edges, {len(OBJ)} objects, {len(ITEMS)} items")

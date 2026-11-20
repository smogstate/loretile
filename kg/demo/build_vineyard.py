#!/usr/bin/env python3
"""Builds kg/demo/vineyard.{ru,de,en}.edn — the «Основы виноградарства»
demo graph (a craft, not IT — the platform is domain-agnostic): 14 topics,
2 sources, 20 objects, 28 items, one published course. Same architecture
as build.py / build_infosec.py. Regenerate: python3 kg/demo/build_vineyard.py"""
import json
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from changes import as_change  # noqa: E402

T0 = 1757100000000
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
    ("src-handbook", tr({"ru": "Справочник виноградаря", "en": "The vineyard handbook",
                         "de": "Handbuch des Winzers"}, "src-handbook"), "vineyard-handbook.pdf"),
    ("src-notes", tr({"ru": "Полевые заметки агронома", "en": "The agronomist's field notes",
                      "de": "Feldnotizen des Agronomen"}, "src-notes"), "field-notes.pdf"),
]

C = [
    ("vine-anatomy", ":definition",
     {"ru": "Строение куста", "en": "Vine anatomy", "de": "Aufbau des Rebstocks"},
     {"ru": "Штамб несёт рукава, рукава — плодовые лозы, на лозах — глазки, из которых растут зелёные побеги. Урожай этого года рождается из глазков прошлогодней лозы.",
      "en": "The trunk carries the cordons, the cordons carry the fruiting canes, the canes carry the buds from which green shoots grow. This year's crop is born from last year's buds.",
      "de": "Der Stamm trägt die Kordons, die Kordons die Fruchtruten, die Ruten die Augen, aus denen die grünen Triebe wachsen. Die Ernte dieses Jahres entsteht aus den Augen der vorjährigen Rute."}, "src-handbook"),
    ("rootstock", ":definition",
     {"ru": "Подвой и прививка", "en": "Rootstock and grafting", "de": "Unterlage und Veredelung"},
     {"ru": "Европейский сорт прививают на американский подвой — его корни не по зубам филлоксере. Подвой выбирают под почву: известь, засуху, засоление.",
      "en": "A European variety is grafted onto American rootstock — its roots are beyond phylloxera's reach. The rootstock is chosen for the soil: lime, drought, salinity.",
      "de": "Eine europäische Sorte wird auf eine amerikanische Unterlage veredelt — deren Wurzeln kann die Reblaus nichts anhaben. Die Unterlage wählt man nach dem Boden: Kalk, Trockenheit, Versalzung."}, "src-handbook"),
    ("terroir", ":principle",
     {"ru": "Терруар", "en": "Terroir", "de": "Terroir"},
     {"ru": "Сумма места: почва, рельеф, климат и рука хозяйства. Один сорт на двух склонах даёт два разных вина — это терруар, а не ошибка винодела.",
      "en": "The sum of a place: soil, relief, climate and the estate's hand. One variety on two slopes gives two different wines — that is terroir, not the winemaker's error.",
      "de": "Die Summe eines Ortes: Boden, Relief, Klima und die Hand des Betriebs. Eine Sorte an zwei Hängen ergibt zwei verschiedene Weine — das ist Terroir, kein Fehler des Winzers."}, "src-handbook"),
    ("soil", ":definition",
     {"ru": "Почва виноградника", "en": "Vineyard soil", "de": "Weinbergsboden"},
     {"ru": "Лоза любит бедные, дренированные почвы: на жирном чернозёме она гонит листья вместо гроздей. Камень днём греется — ночью отдаёт тепло ягоде.",
      "en": "The vine likes poor, well-drained soils: on rich black earth it grows leaves instead of clusters. Stones warm up by day and give the heat back to the berries at night.",
      "de": "Die Rebe liebt karge, durchlässige Böden: auf fetter Schwarzerde treibt sie Blätter statt Trauben. Steine erwärmen sich tags und geben die Wärme nachts an die Beeren ab."}, "src-handbook"),
    ("climate", ":definition",
     {"ru": "Климат и микроклимат", "en": "Climate and microclimate", "de": "Klima und Mikroklima"},
     {"ru": "Макроклимат — регион, мезоклимат — склон, микроклимат — воздух внутри кроны. Виноградарь управляет только последним: листьями, рядами, травой в междурядье.",
      "en": "Macroclimate is the region, mesoclimate the slope, microclimate the air inside the canopy. The grower controls only the last one: leaves, rows, the grass between them.",
      "de": "Makroklima ist die Region, Mesoklima der Hang, Mikroklima die Luft in der Laubwand. Der Winzer steuert nur das letzte: Blätter, Zeilen, das Gras der Fahrgasse."}, "src-handbook"),
    ("phenology", ":definition",
     {"ru": "Фазы вегетации", "en": "Growth stages", "de": "Entwicklungsstadien"},
     {"ru": "Плач лозы, распускание почек, цветение, завязь, рост ягоды, верезон, созревание, листопад. Каждая операция в сезоне привязана к фазе, а не к дате.",
      "en": "Bleeding, budbreak, flowering, fruit set, berry growth, veraison, ripening, leaf fall. Every operation in the season is tied to a stage, not to a date.",
      "de": "Bluten, Austrieb, Blüte, Fruchtansatz, Beerenwachstum, Véraison, Reife, Laubfall. Jede Arbeit der Saison hängt am Stadium, nicht am Datum."}, "src-handbook"),
    ("trellis", ":definition",
     {"ru": "Шпалера и формировка", "en": "Trellis and training", "de": "Spalier und Reberziehung"},
     {"ru": "Шпалера держит крону в вертикальной плоскости: листья на солнце, грозди в полутени, проветривание сверху донизу. Формировка — скелет куста, который шпалера несёт.",
      "en": "The trellis holds the canopy in a vertical plane: leaves in the sun, clusters in half-shade, airflow top to bottom. Training is the vine's skeleton the trellis carries.",
      "de": "Das Spalier hält die Laubwand in einer senkrechten Ebene: Blätter in der Sonne, Trauben im Halbschatten, Durchlüftung von oben bis unten. Die Erziehung ist das Gerüst, das das Spalier trägt."}, "src-handbook"),
    ("winter-pruning", ":procedure",
     {"ru": "Зимняя обрезка", "en": "Winter pruning", "de": "Rebschnitt"},
     {"ru": "Обрезка решает урожай будущего года: оставить мало глазков — недобор, много — перегруз и мелкая ягода. Считают глазки на куст, а не лозы.",
      "en": "Pruning decides next year's crop: too few buds means a short harvest, too many means overload and small berries. You count buds per vine, not canes.",
      "de": "Der Rebschnitt entscheidet die nächste Ernte: zu wenige Augen — Mindermenge, zu viele — Überlast und kleine Beeren. Gezählt werden Augen pro Stock, nicht Ruten."}, "src-handbook"),
    ("canopy", ":procedure",
     {"ru": "Зелёные операции", "en": "Canopy management", "de": "Laubarbeit"},
     {"ru": "Обломка волчков, подвязка, чеканка верхушек, удаление листьев у гроздей. Цель одна: свет и воздух ягоде, силы куста — в урожай, а не в ботву.",
      "en": "Removing water shoots, tying, trimming the tops, pulling leaves around the clusters. One goal: light and air for the berries, the vine's strength into fruit, not foliage.",
      "de": "Ausbrechen der Wasserschosser, Heften, Gipfeln, Entblättern der Traubenzone. Ein Ziel: Licht und Luft für die Beeren, die Kraft des Stocks in die Frucht, nicht ins Laub."}, "src-notes"),
    ("veraison", ":definition",
     {"ru": "Верезон", "en": "Veraison", "de": "Véraison"},
     {"ru": "Начало созревания: красные сорта окрашиваются, белые становятся полупрозрачными, ягода мягчеет. Рост окончен — сахар пошёл вверх, кислота вниз.",
      "en": "The onset of ripening: red varieties colour up, whites turn translucent, the berry softens. Growth is over — sugar starts climbing, acid falling.",
      "de": "Der Reifebeginn: rote Sorten färben sich, weiße werden durchscheinend, die Beere wird weich. Das Wachstum ist vorbei — Zucker steigt, Säure fällt."}, "src-handbook"),
    ("ripeness", ":principle",
     {"ru": "Зрелость: сахар и кислота", "en": "Ripeness: sugar and acid", "de": "Reife: Zucker und Säure"},
     {"ru": "Зрелость — это баланс: сахар в °Bx растёт, титруемая кислотность в г/л падает. Для игристого снимают раньше, для десертного — позже; «спелая на вкус» ягода — не аргумент без цифр.",
      "en": "Ripeness is a balance: sugar in °Bx rises while titratable acidity in g/L falls. For sparkling you pick earlier, for dessert wine later; a berry that “tastes ripe” is no argument without the numbers.",
      "de": "Reife ist eine Balance: der Zucker in °Oechsle steigt, die titrierbare Säure in g/L fällt. Für Sekt liest man früher, für Süßwein später; eine Beere, die „reif schmeckt“, zählt ohne Zahlen nicht."}, "src-notes"),
    ("harvest", ":procedure",
     {"ru": "Сбор урожая", "en": "Harvest", "de": "Weinlese"},
     {"ru": "Дату диктуют цифры зрелости и прогноз: дождь перед сбором разбавляет сок и рвёт ягоду. Собирают утром, по холодку — тёплая ягода окисляется по дороге.",
      "en": "The date is dictated by ripeness numbers and the forecast: rain before picking dilutes the juice and splits berries. Pick in the cool of the morning — warm fruit oxidises on the way.",
      "de": "Das Datum diktieren die Reifezahlen und die Vorhersage: Regen vor der Lese verwässert den Most und lässt Beeren platzen. Gelesen wird morgens in der Kühle — warmes Lesegut oxidiert unterwegs."}, "src-notes"),
    ("mildew", ":definition",
     {"ru": "Милдью и оидиум", "en": "Downy and powdery mildew", "de": "Falscher und echter Mehltau"},
     {"ru": "Милдью — ложная мучнистая роса: маслянистые пятна сверху листа, белый пушок снизу, любит влагу. Оидиум — настоящая: серый налёт «пылью» по обеим сторонам и по ягоде, работает и в сушь.",
      "en": "Downy mildew: oily spots on top of the leaf, white down underneath, thrives in the wet. Powdery mildew: a grey dust-like coating on both sides and on berries, active even in dry weather.",
      "de": "Falscher Mehltau: ölige Flecken oben am Blatt, weißer Flaum darunter, liebt Nässe. Echter Mehltau: grauer, staubartiger Belag beidseitig und auf der Beere, arbeitet auch bei Trockenheit."}, "src-handbook"),
    ("irrigation", ":procedure",
     {"ru": "Полив", "en": "Irrigation", "de": "Bewässerung"},
     {"ru": "Умеренный дефицит воды до верезона — союзник качества: ягода мельче, вкус плотнее. Поливать перед сбором — разбавлять будущее вино.",
      "en": "Mild water deficit before veraison is quality's ally: smaller berries, denser flavour. Irrigating before harvest waters down the future wine.",
      "de": "Ein leichtes Wasserdefizit vor der Véraison ist ein Verbündeter der Qualität: kleinere Beeren, dichterer Geschmack. Bewässern vor der Lese verdünnt den künftigen Wein."}, "src-notes"),
]

REQ = [
    ("rootstock", "vine-anatomy"), ("winter-pruning", "vine-anatomy"),
    ("trellis", "vine-anatomy"), ("terroir", "soil"), ("terroir", "climate"),
    ("canopy", "phenology"), ("canopy", "trellis"), ("veraison", "phenology"),
    ("ripeness", "veraison"), ("harvest", "ripeness"), ("mildew", "canopy"),
    ("irrigation", "climate"),
]
EXTRA = [
    ("veraison", "ripeness", ":is-confused-with",
     tr({"ru": "Верезон — начало пути (ягода красится), зрелость — его конец (цифры сахара и кислоты сошлись).",
         "en": "Veraison is the start of the road (the berry colours), ripeness its end (sugar and acid numbers meet).",
         "de": "Die Véraison ist der Anfang des Weges (die Beere färbt sich), die Reife sein Ende (Zucker- und Säurewerte passen)."}, "conf-1")),
    ("terroir", "soil", ":is-confused-with",
     tr({"ru": "Почва — один ингредиент; терруар — вся сумма места, включая климат и руку хозяйства.",
         "en": "Soil is one ingredient; terroir is the whole sum of a place, climate and the estate's hand included.",
         "de": "Der Boden ist eine Zutat; Terroir ist die ganze Summe des Ortes, samt Klima und Hand des Betriebs."}, "conf-2")),
    ("irrigation", "ripeness", ":see-also", None),
    ("mildew", "climate", ":see-also", None),
]

OBJ = [
    ("vine-anatomy", ":diagram", ":novice",
     {"ru": "Куст в разрезе: штамб, два рукава, плодовая лоза с восемью глазками и сучок замещения на два глазка. Подписи — что даст урожай в этом году, что — в следующем.",
      "en": "A vine in cross-section: trunk, two cordons, a fruiting cane with eight buds and a two-bud renewal spur. Labels mark what fruits this year and what next year.",
      "de": "Ein Stock im Schnittbild: Stamm, zwei Kordons, eine Fruchtrute mit acht Augen und ein Zapfen mit zwei Augen. Beschriftet ist, was dieses Jahr trägt und was nächstes."}, "src-handbook", "p6"),
    ("vine-anatomy", ":explanation", ":apprentice",
     {"ru": "==Зелёный побег из глазка несёт и листья, и грозди.== Всё, что вырастает из старой древесины, — волчок: гроздей почти не даёт, силы ест.",
      "en": "==The green shoot from a bud carries both leaves and clusters.== Anything growing from old wood is a water shoot: almost no fruit, plenty of appetite.",
      "de": "==Der grüne Trieb aus dem Auge trägt Blätter und Trauben.== Was aus altem Holz wächst, ist ein Wasserschosser: kaum Frucht, großer Appetit."}, "src-handbook", "p7"),
    ("rootstock", ":explanation", ":apprentice",
     {"ru": "Филлоксера съела корнесобственную Европу в XIX веке; с тех пор европейский сорт живёт на американских корнях. Прививка — место сращения двух растений, видно кольцо.",
      "en": "Phylloxera ate own-rooted Europe in the 19th century; since then European varieties live on American roots. The graft is where two plants fused — the ring is visible.",
      "de": "Die Reblaus fraß das wurzelechte Europa im 19. Jahrhundert; seither stehen europäische Sorten auf amerikanischen Wurzeln. Die Veredelungsstelle ist der sichtbare Ring, wo zwei Pflanzen verwuchsen."}, "src-handbook", "p9"),
    ("terroir", ":explanation", ":apprentice",
     {"ru": "Южный склон получает больше суммы активных температур, верх склона уходит от заморозка: холодный воздух стекает вниз, как вода. Поэтому лучшие участки — середина склона.",
      "en": "A south slope collects more heat over the season, and the upper slope escapes frost: cold air flows downhill like water. That is why mid-slope sites are the prized ones.",
      "de": "Ein Südhang sammelt über die Saison mehr Wärme, und der obere Hang entgeht dem Frost: kalte Luft fließt talwärts wie Wasser. Darum sind die Mittellagen die besten."}, "src-handbook", "p12"),
    ("soil", ":explanation", ":apprentice",
     {"ru": "Дренаж важнее плодородия: стоящая у корней вода душит лозу и зовёт болезни. Галька, известняк, сланец — три классических «бедных» дома лозы.",
      "en": "Drainage beats fertility: water standing at the roots suffocates the vine and invites disease. Gravel, limestone, slate — the three classic “poor” homes of the vine.",
      "de": "Drainage schlägt Fruchtbarkeit: Wasser an den Wurzeln erstickt die Rebe und lädt Krankheiten ein. Kies, Kalkstein, Schiefer — die drei klassischen „kargen“ Häuser der Rebe."}, "src-handbook", "p14"),
    ("climate", ":explanation", ":apprentice",
     {"ru": "Открытая листьям гроздь на утреннем солнце и в тени в полдень — управляемый микроклимат. Загущённая крона держит росу до обеда — готовый инкубатор милдью.",
      "en": "A cluster open to morning sun and shaded at noon is managed microclimate. A dense canopy holds dew until lunch — a ready incubator for downy mildew.",
      "de": "Eine Traube in der Morgensonne und im Mittagsschatten ist gesteuertes Mikroklima. Eine dichte Laubwand hält den Tau bis mittags — ein fertiger Brutkasten für falschen Mehltau."}, "src-handbook", "p16"),
    ("phenology", ":explanation", ":apprentice",
     {"ru": "Цветение — самая уязвимая фаза: дождь и холод в эти десять дней срезают завязь, и никакая агротехника позже этого не вернёт.",
      "en": "Flowering is the most fragile stage: rain and cold in those ten days cut fruit set, and no later fieldwork brings it back.",
      "de": "Die Blüte ist das empfindlichste Stadium: Regen und Kälte in diesen zehn Tagen kappen den Fruchtansatz, und keine spätere Arbeit holt ihn zurück."}, "src-handbook", "p18"),
    ("trellis", ":explanation", ":apprentice",
     {"ru": "Вертикальное ведение прироста: нижняя проволока держит плодовую лозу, пары верхних ловят побеги. Стена листьев в метр — солнечная батарея куста.",
      "en": "Vertical shoot positioning: the bottom wire carries the fruiting cane, the paired upper wires catch the shoots. A metre of leaf wall is the vine's solar panel.",
      "de": "Vertikale Triebführung: der untere Draht trägt die Fruchtrute, die Doppeldrähte darüber fangen die Triebe. Ein Meter Laubwand ist das Sonnenkraftwerk des Stocks."}, "src-handbook", "p20"),
    ("winter-pruning", ":worked-example", ":apprentice",
     {"ru": "Куст на 20 глазков: плодовая лоза на 8 глазков + сучок замещения на 2, второй рукав так же. Побеги из сучка станут лозой следующего года — так куст не уезжает от штамба.",
      "en": "A 20-bud vine: an 8-bud fruiting cane plus a 2-bud renewal spur, the second cordon the same. The spur's shoots become next year's cane — so the vine never walks away from its trunk.",
      "de": "Ein Stock auf 20 Augen: eine Fruchtrute mit 8 Augen plus ein Zapfen mit 2, der zweite Kordon ebenso. Die Triebe des Zapfens werden die Rute des nächsten Jahres — so wandert der Stock nicht vom Stamm weg."}, "src-handbook", "p23"),
    ("winter-pruning", ":explanation", ":apprentice",
     {"ru": "Режут в покое, после листопада и до плача. В морозные утра секатор не берут: древесина хрупкая, срезы рвутся.",
      "en": "Prune during dormancy, after leaf fall and before bleeding. Leave the secateurs home on frosty mornings: the wood is brittle, cuts tear.",
      "de": "Geschnitten wird in der Ruhe, nach dem Laubfall und vor dem Bluten. An Frostmorgen bleibt die Schere zu Hause: das Holz ist spröde, Schnitte reißen aus."}, "src-handbook", "p22"),
    ("canopy", ":worked-example", ":apprentice",
     {"ru": "Июнь: обломать волчки на штамбе, заправить побеги за проволоку, у гроздей снять листья с восточной стороны — утреннее солнце сушит росу, полуденное не жжёт.",
      "en": "June: break the water shoots off the trunk, tuck shoots behind the wires, pull leaves on the east side of the clusters — morning sun dries the dew, noon sun cannot scorch.",
      "de": "Juni: Wasserschosser am Stamm ausbrechen, Triebe hinter die Drähte heften, in der Traubenzone ostseitig entblättern — die Morgensonne trocknet den Tau, die Mittagssonne verbrennt nicht."}, "src-notes", "p4"),
    ("canopy", ":explanation", ":apprentice",
     {"ru": "Чеканка — укоротить верхушки, когда побеги переросли шпалеру: куст перестаёт гнать рост и переключается на ягоду.",
      "en": "Trimming means shortening the tops once shoots outgrow the trellis: the vine stops pushing growth and switches to the fruit.",
      "de": "Gipfeln heißt die Triebspitzen kappen, wenn sie über das Spalier hinauswachsen: der Stock hört auf zu schieben und arbeitet für die Frucht."}, "src-notes", "p5"),
    ("veraison", ":explanation", ":apprentice",
     {"ru": "С верезона начинается отсчёт до сбора: у ранних сортов 30–40 дней, у поздних до 70. С этого же дня ягода — приманка для ос и птиц, пора вешать сетки.",
      "en": "Veraison starts the countdown to harvest: 30–40 days for early varieties, up to 70 for late ones. From that day the berry also baits wasps and birds — time for the nets.",
      "de": "Mit der Véraison beginnt der Countdown zur Lese: 30–40 Tage bei frühen Sorten, bis 70 bei späten. Ab diesem Tag lockt die Beere auch Wespen und Vögel — Zeit für die Netze."}, "src-handbook", "p27"),
    ("ripeness", ":worked-example", ":apprentice",
     {"ru": "Проба из 100 ягод с разных кустов и сторон ряда: ++22 °Bx при 6 г/л кислоты — для сухого красного пора планировать сбор++; 18 °Bx при 9 г/л — ждать, но для игристого уже готово.",
      "en": "A 100-berry sample from different vines and both row sides: ++22 °Bx at 6 g/L acid — time to plan picking for a dry red++; 18 °Bx at 9 g/L — keep waiting, though for sparkling it is ready now.",
      "de": "Eine 100-Beeren-Probe von verschiedenen Stöcken und beiden Zeilenseiten: ++85 °Oechsle bei 6 g/L Säure — für einen trockenen Roten die Lese planen++; 75 °Oechsle bei 9 g/L — warten, für Sekt aber schon reif."}, "src-notes", "p8"),
    ("ripeness", ":explanation", ":apprentice",
     {"ru": "Кроме цифр есть физиология: ++коричневые косточки, лёгкий отрыв ягоды, вкус мякоти без травянистости++. ==Цифры решают, физиология подтверждает.==",
      "en": "Beyond the numbers there is physiology: ++brown seeds, a berry that detaches easily, flesh with no green taste++. ==The numbers decide, physiology confirms.==",
      "de": "Neben den Zahlen steht die Physiologie: ++braune Kerne, eine leicht lösbare Beere, Fruchtfleisch ohne grünen Ton++. ==Die Zahlen entscheiden, die Physiologie bestätigt.=="}, "src-notes", "p9"),
    ("harvest", ":worked-example", ":apprentice",
     {"ru": "Прогноз обещает дождь через два дня, сахар на месте, кислота чуть высока: снимаем сейчас — лёгкая свежесть лучше, чем разбавленный дождём сок и серая гниль после.",
      "en": "The forecast promises rain in two days, sugar is on target, acid a touch high: pick now — a little extra freshness beats rain-diluted juice and grey rot afterwards.",
      "de": "Die Vorhersage meldet Regen in zwei Tagen, der Zucker stimmt, die Säure ist etwas hoch: jetzt lesen — etwas mehr Frische ist besser als regenverwässerter Most und Graufäule danach."}, "src-notes", "p11"),
    ("harvest", ":explanation", ":apprentice",
     {"ru": "Ящики мелкие, по 15–20 кг: под собственным весом ягода давится, и сок окисляется до пресса. Гнилые грозди отбраковывают в ряду, не на сортировке.",
      "en": "Shallow crates of 15–20 kg: under its own weight the fruit crushes and the juice oxidises before the press. Rotten clusters are culled in the row, not at the sorting table.",
      "de": "Flache Kisten mit 15–20 kg: unter dem eigenen Gewicht quetscht die Frucht, und der Most oxidiert vor der Presse. Faule Trauben bleiben in der Zeile, nicht erst am Sortiertisch."}, "src-notes", "p12"),
    ("mildew", ":diagram", ":apprentice",
     {"ru": "Лист в два окна: слева милдью — маслянистое пятно сверху, белый пух строго снизу; справа оидиум — серая «пыль» с обеих сторон. Подпись: пух снизу — ложная, пыль везде — настоящая.",
      "en": "A leaf in two panes: left, downy mildew — an oily spot on top, white down strictly underneath; right, powdery — grey “dust” on both sides. Caption: down below means downy, dust everywhere means powdery.",
      "de": "Ein Blatt in zwei Fenstern: links falscher Mehltau — öliger Fleck oben, weißer Flaum nur unten; rechts echter — grauer „Staub“ beidseitig. Merksatz: Flaum unten — falscher, Staub überall — echter."}, "src-handbook", "p30"),
    ("mildew", ":explanation", ":apprentice",
     {"ru": "Профилактика бьёт лечение: проветриваемая крона, обработка до дождя, а не после. Увидели пятна — считайте, что неделя уже проиграна.",
      "en": "Prevention beats cure: an airy canopy, spraying before the rain rather than after. Once you see the spots, assume a week is already lost.",
      "de": "Vorbeugen schlägt Heilen: eine luftige Laubwand, gespritzt vor dem Regen, nicht danach. Sind die Flecken da, ist eine Woche schon verloren."}, "src-handbook", "p31"),
    ("irrigation", ":explanation", ":apprentice",
     {"ru": "Капля к корню на рассвете: меньше испарения, ни капли на листе. Признак жажды — усики поникли и верхушки побегов остановились, а не «земля сухая на вид».",
      "en": "Drip at the root at dawn: less evaporation, not a drop on the leaves. Thirst shows as drooping tendrils and stalled shoot tips — not as “the ground looks dry”.",
      "de": "Tropfen an die Wurzel im Morgengrauen: weniger Verdunstung, kein Tropfen aufs Blatt. Durst zeigt sich an hängenden Ranken und stehengebliebenen Triebspitzen — nicht daran, dass „der Boden trocken aussieht“."}, "src-notes", "p14"),
]

def sc(stem, opts, key): return (":single-choice", {"stem": stem, "options": opts, "key": key})
def cz(text, key): return (":cloze", {"text": text, "key": key})

ITEMS = [
    ("vine-anatomy", {
        "ru": sc("Из чего растёт урожай этого года?", ["Из глазков прошлогодней лозы", "Из штамба", "Из корней подвоя"], 0),
        "en": sc("Where does this year's crop grow from?", ["Buds on last year's cane", "The trunk", "The rootstock's roots"], 0),
        "de": sc("Woraus wächst die Ernte dieses Jahres?", ["Aus den Augen der vorjährigen Rute", "Aus dem Stamm", "Aus den Wurzeln der Unterlage"], 0)}),
    ("vine-anatomy", {
        "ru": sc("Побег из старой древесины называется…", ["Плодовая лоза", "Волчок", "Сучок замещения"], 1),
        "en": sc("A shoot growing from old wood is called…", ["A fruiting cane", "A water shoot", "A renewal spur"], 1),
        "de": sc("Ein Trieb aus altem Holz heißt…", ["Fruchtrute", "Wasserschosser", "Zapfen"], 1)}),
    ("vine-anatomy", {
        "ru": cz("Штамб несёт рукава, рукава — плодовые ___", "лозы"),
        "en": cz("The trunk carries cordons, the cordons carry the fruiting ___", "canes"),
        "de": cz("Der Stamm trägt Kordons, die Kordons die ___", "Fruchtruten")}),
    ("rootstock", {
        "ru": sc("Зачем европейский сорт прививают на американский подвой?", ["Для вкуса", "Корни подвоя не по зубам филлоксере", "Для морозостойкости кроны"], 1),
        "en": sc("Why graft a European variety onto American rootstock?", ["For flavour", "Its roots resist phylloxera", "For canopy frost-hardiness"], 1),
        "de": sc("Warum veredelt man europäische Sorten auf amerikanische Unterlagen?", ["Für den Geschmack", "Ihre Wurzeln widerstehen der Reblaus", "Für die Frosthärte der Laubwand"], 1)}),
    ("terroir", {
        "ru": sc("Почему лучшие участки — середина склона?", ["Там плодороднее почва", "Холодный воздух стекает вниз, верх продувается", "Туда проще доехать"], 1),
        "en": sc("Why are mid-slope sites prized?", ["The soil is richer there", "Cold air drains downhill and the top is wind-worn", "They are easier to reach"], 1),
        "de": sc("Warum sind Mittellagen die besten?", ["Der Boden ist dort fetter", "Kalte Luft fließt talwärts, oben zehrt der Wind", "Man kommt leichter hin"], 1)}),
    ("terroir", {
        "ru": sc("Один сорт на двух склонах дал два разных вина. Это…", ["Ошибка винодела", "Терруар", "Случайность"], 1),
        "en": sc("One variety on two slopes gave two different wines. That is…", ["The winemaker's error", "Terroir", "Chance"], 1),
        "de": sc("Eine Sorte an zwei Hängen ergab zwei verschiedene Weine. Das ist…", ["Ein Fehler des Winzers", "Terroir", "Zufall"], 1)}),
    ("terroir", {
        "ru": cz("Терруар — это сумма места: почва, рельеф, ___ и рука хозяйства", "климат"),
        "en": cz("Terroir is the sum of a place: soil, relief, ___ and the estate's hand", "climate"),
        "de": cz("Terroir ist die Summe des Ortes: Boden, Relief, ___ und die Hand des Betriebs", "Klima")}),
    ("soil", {
        "ru": sc("Что важнее всего в почве для лозы?", ["Плодородие", "Дренаж", "Тёмный цвет"], 1),
        "en": sc("What matters most in vineyard soil?", ["Fertility", "Drainage", "A dark colour"], 1),
        "de": sc("Was zählt beim Weinbergsboden am meisten?", ["Fruchtbarkeit", "Drainage", "Eine dunkle Farbe"], 1)}),
    ("climate", {
        "ru": sc("Каким уровнем климата управляет виноградарь?", ["Макроклиматом региона", "Микроклиматом внутри кроны", "Никаким"], 1),
        "en": sc("Which climate level does the grower control?", ["The region's macroclimate", "The microclimate inside the canopy", "None"], 1),
        "de": sc("Welche Klimaebene steuert der Winzer?", ["Das Makroklima der Region", "Das Mikroklima in der Laubwand", "Keine"], 1)}),
    ("phenology", {
        "ru": sc("К чему привязана каждая операция сезона?", ["К календарной дате", "К фазе вегетации", "К фазе луны"], 1),
        "en": sc("Every seasonal operation is tied to…", ["A calendar date", "A growth stage", "A moon phase"], 1),
        "de": sc("Jede Arbeit der Saison hängt an…", ["Einem Kalenderdatum", "Einem Entwicklungsstadium", "Einer Mondphase"], 1)}),
    ("phenology", {
        "ru": sc("Дождь и холод в цветение — что под ударом?", ["Окраска ягод", "Завязь, и позже её не вернуть", "Осенний листопад"], 1),
        "en": sc("Rain and cold at flowering endanger…", ["Berry colour", "Fruit set, gone for good", "Autumn leaf fall"], 1),
        "de": sc("Regen und Kälte in der Blüte gefährden…", ["Die Beerenfarbe", "Den Fruchtansatz, unwiederbringlich", "Den Laubfall im Herbst"], 1)}),
    ("phenology", {
        "ru": cz("Начало созревания ягоды называется ___", "верезон"),
        "en": cz("The onset of berry ripening is called ___", "veraison"),
        "de": cz("Der Beginn der Beerenreife heißt ___", "Véraison")}),
    ("trellis", {
        "ru": sc("Зачем шпалера держит крону в вертикальной плоскости?", ["Красиво в ряду", "Листья на солнце, грозди проветриваются", "Чтобы куст рос медленнее"], 1),
        "en": sc("Why hold the canopy in a vertical plane?", ["It looks tidy", "Leaves in the sun, clusters ventilated", "To slow the vine down"], 1),
        "de": sc("Warum hält das Spalier die Laubwand senkrecht?", ["Es sieht ordentlich aus", "Blätter in der Sonne, Trauben durchlüftet", "Damit der Stock langsamer wächst"], 1)}),
    ("winter-pruning", {
        "ru": sc("Что считают при обрезке?", ["Лозы на куст", "Глазки на куст", "Рукава на ряд"], 1),
        "en": sc("What do you count when pruning?", ["Canes per vine", "Buds per vine", "Cordons per row"], 1),
        "de": sc("Was zählt man beim Rebschnitt?", ["Ruten pro Stock", "Augen pro Stock", "Kordons pro Zeile"], 1)}),
    ("winter-pruning", {
        "ru": sc("Оставили слишком много глазков. Что получим?", ["Крупную ягоду", "Перегруз и мелкую ягоду", "Ничего не изменится"], 1),
        "en": sc("Too many buds were left. The result?", ["Bigger berries", "Overload and small berries", "No change"], 1),
        "de": sc("Es wurden zu viele Augen gelassen. Die Folge?", ["Größere Beeren", "Überlast und kleine Beeren", "Keine Änderung"], 1)}),
    ("winter-pruning", {
        "ru": sc("Побеги из сучка замещения станут…", ["Волчками", "Плодовой лозой следующего года", "Рукавами"], 1),
        "en": sc("The renewal spur's shoots become…", ["Water shoots", "Next year's fruiting cane", "Cordons"], 1),
        "de": sc("Die Triebe des Zapfens werden…", ["Wasserschosser", "Die Fruchtrute des nächsten Jahres", "Kordons"], 1)}),
    ("canopy", {
        "ru": sc("С какой стороны снимают листья у гроздей?", ["С южной", "С восточной", "Со всех"], 1),
        "en": sc("On which side of the clusters do you pull leaves?", ["The south side", "The east side", "All sides"], 1),
        "de": sc("Auf welcher Seite der Traubenzone entblättert man?", ["Auf der Südseite", "Auf der Ostseite", "Auf allen Seiten"], 1)}),
    ("canopy", {
        "ru": sc("Чеканка — это…", ["Удаление гроздей", "Укорачивание верхушек побегов", "Рыхление почвы"], 1),
        "en": sc("Trimming means…", ["Removing clusters", "Shortening the shoot tops", "Loosening the soil"], 1),
        "de": sc("Gipfeln heißt…", ["Trauben entfernen", "Die Triebspitzen kappen", "Den Boden lockern"], 1)}),
    ("canopy", {
        "ru": cz("Побег из старой древесины, который обламывают летом, — ___", "волчок"),
        "en": cz("The old-wood shoot broken off in summer is a ___ shoot", "water"),
        "de": cz("Der im Sommer ausgebrochene Trieb aus altem Holz ist der ___", "Wasserschosser")}),
    ("veraison", {
        "ru": sc("Как выглядит верезон у белых сортов?", ["Ягода краснеет", "Ягода становится полупрозрачной и мягчеет", "Ягода осыпается"], 1),
        "en": sc("What does veraison look like on white varieties?", ["Berries turn red", "Berries turn translucent and soften", "Berries drop"], 1),
        "de": sc("Wie zeigt sich die Véraison bei weißen Sorten?", ["Die Beeren röten sich", "Die Beeren werden durchscheinend und weich", "Die Beeren rieseln"], 1)}),
    ("veraison", {
        "ru": sc("Что происходит с сахаром и кислотой после верезона?", ["Оба растут", "Сахар растёт, кислота падает", "Оба падают"], 1),
        "en": sc("After veraison, sugar and acid…", ["Both rise", "Sugar rises, acid falls", "Both fall"], 1),
        "de": sc("Nach der Véraison…", ["Steigen Zucker und Säure", "Steigt der Zucker, fällt die Säure", "Fallen beide"], 1)}),
    ("veraison", {
        "ru": cz("С верезона до сбора у ранних сортов проходит 30–40 ___", "дней"),
        "en": cz("From veraison to harvest, early varieties take 30–40 ___", "days"),
        "de": cz("Von der Véraison bis zur Lese vergehen bei frühen Sorten 30–40 ___", "Tage")}),
    ("ripeness", {
        "ru": sc("Для игристого виноград снимают…", ["Позже, чем для сухого", "Раньше: кислота нужна выше", "Только по вкусу"], 1),
        "en": sc("For sparkling wine, grapes are picked…", ["Later than for dry wine", "Earlier: more acid is wanted", "By taste alone"], 1),
        "de": sc("Für Sekt liest man die Trauben…", ["Später als für trockenen Wein", "Früher: man will mehr Säure", "Nur nach Geschmack"], 1)}),
    ("ripeness", {
        "ru": sc("Как берут пробу на зрелость?", ["Три ягоды с крайнего куста", "100 ягод с разных кустов и сторон ряда", "Одну гроздь целиком"], 1),
        "en": sc("How is a ripeness sample taken?", ["Three berries off the end vine", "100 berries from different vines and both row sides", "One whole cluster"], 1),
        "de": sc("Wie zieht man eine Reifeprobe?", ["Drei Beeren vom Randstock", "100 Beeren von verschiedenen Stöcken und beiden Seiten", "Eine ganze Traube"], 1)}),
    ("ripeness", {
        "ru": sc("Коричневые косточки и лёгкий отрыв ягоды — это…", ["Признак болезни", "Физиологическое подтверждение зрелости", "Сигнал перегруза"], 1),
        "en": sc("Brown seeds and an easily detaching berry indicate…", ["Disease", "Physiological confirmation of ripeness", "Overload"], 1),
        "de": sc("Braune Kerne und eine leicht lösbare Beere zeigen…", ["Krankheit", "Die physiologische Bestätigung der Reife", "Überlast"], 1)}),
    ("harvest", {
        "ru": sc("Почему собирают утром?", ["Днём жарко работникам", "Тёплая ягода окисляется по дороге", "Утром слаще сок"], 1),
        "en": sc("Why pick in the morning?", ["Midday is hard on the crew", "Warm fruit oxidises on the way", "The juice is sweeter at dawn"], 1),
        "de": sc("Warum liest man morgens?", ["Mittags ist es hart für die Helfer", "Warmes Lesegut oxidiert unterwegs", "Morgens ist der Most süßer"], 1)}),
    ("harvest", {
        "ru": sc("Дождь через два дня, сахар на месте. Решение?", ["Ждать неделю после дождя", "Снимать сейчас", "Полить, раз всё равно дождь"], 1),
        "en": sc("Rain in two days, sugar on target. The call?", ["Wait a week after the rain", "Pick now", "Irrigate, rain is coming anyway"], 1),
        "de": sc("Regen in zwei Tagen, der Zucker stimmt. Die Entscheidung?", ["Eine Woche nach dem Regen warten", "Jetzt lesen", "Bewässern, es regnet ohnehin"], 1)}),
    ("harvest", {
        "ru": cz("Ящики для сбора мелкие, по 15–20 ___", "кг"),
        "en": cz("Harvest crates are shallow, 15–20 ___ each", "kg"),
        "de": cz("Lesekisten sind flach, je 15–20 ___", "kg")}),
    ("mildew", {
        "ru": sc("Белый пушок строго снизу листа — это…", ["Оидиум", "Милдью", "Солнечный ожог"], 1),
        "en": sc("White down strictly on the leaf's underside is…", ["Powdery mildew", "Downy mildew", "Sunburn"], 1),
        "de": sc("Weißer Flaum nur auf der Blattunterseite ist…", ["Echter Mehltau", "Falscher Mehltau", "Sonnenbrand"], 1)}),
    ("mildew", {
        "ru": sc("Когда обрабатывать против милдью?", ["После дождя", "До дождя", "Только по пятнам"], 1),
        "en": sc("When do you spray against downy mildew?", ["After the rain", "Before the rain", "Only once spots appear"], 1),
        "de": sc("Wann spritzt man gegen falschen Mehltau?", ["Nach dem Regen", "Vor dem Regen", "Erst bei Flecken"], 1)}),
    ("irrigation", {
        "ru": sc("Умеренная нехватка воды до верезона даёт…", ["Крупную водянистую ягоду", "Мелкую ягоду с плотным вкусом", "Гибель куста"], 1),
        "en": sc("Mild water deficit before veraison gives…", ["Big watery berries", "Small berries with dense flavour", "A dead vine"], 1),
        "de": sc("Ein leichtes Wasserdefizit vor der Véraison ergibt…", ["Große wässrige Beeren", "Kleine Beeren mit dichtem Geschmack", "Einen toten Stock"], 1)}),
]

# --- exercises: answers that are EXECUTED (wiki/Exercises.md) --------------
# Python in a craft course: the platform is not about one subject or one
# language. :output compares what the code prints; :assert runs hidden checks.
def ex(stem, solution, check, test=None):
    spec = {"runtime": ":python", "check": check, "stem": stem, "setup": "", "solution": solution}
    if test:
        spec["test"] = test
    return (":exercise", spec)


POTENTIAL_ALCOHOL = "brix = 22\nprint(round(brix * 0.59, 1))"
READY = "def ready(brix, acid):\n    return brix >= 21 and acid <= 7"
READY_TEST = "assert ready(22, 6.5)\nassert ready(21, 7)\nassert not ready(20, 6)\nassert not ready(23, 8)"
EXERCISES = [
    ("ripeness", {
        "ru": ex("Сусло показывает 22 °Bx. Потенциальный спирт ≈ °Bx × 0.59. Напечатайте его, округлив до одного знака.",
                 POTENTIAL_ALCOHOL, ":output"),
        "en": ex("The must reads 22 °Bx. Potential alcohol ≈ °Bx × 0.59. Print it, rounded to one decimal.",
                 POTENTIAL_ALCOHOL, ":output"),
        "de": ex("Der Most hat 22 °Brix. Potenzieller Alkohol ≈ °Brix × 0,59. Geben Sie ihn auf eine Nachkommastelle gerundet aus.",
                 POTENTIAL_ALCOHOL, ":output")}),
    ("harvest", {
        "ru": ex("Напишите функцию ready(brix, acid): True, когда сахар не ниже 21 °Bx, а кислотность не выше 7 г/л — пора собирать.",
                 READY, ":assert", READY_TEST),
        "en": ex("Write a function ready(brix, acid): True when sugar is at least 21 °Bx and acidity at most 7 g/L — time to pick.",
                 READY, ":assert", READY_TEST),
        "de": ex("Schreiben Sie eine Funktion ready(brix, acid): True, wenn der Zucker mindestens 21 °Brix und die Säure höchstens 7 g/L beträgt — Zeit zur Lese.",
                 READY, ":assert", READY_TEST)}),
]

QUESTION = ("q-late-pruning", "winter-pruning",
            tr({"ru": "Оправдывает ли риск весенних заморозков в нашей зоне позднюю обрезку — по набухшим почкам? Нужен ответ агронома.",
                "en": "Does our zone's spring-frost risk justify late pruning, at swollen buds? An agronomist's call is needed.",
                "de": "Rechtfertigt das Spätfrostrisiko unserer Lage einen späten Schnitt, bei geschwollenen Augen? Das muss der Agronom entscheiden."}, "question"))

COURSE = {"id": "vineyard-season",
          "title": tr({"ru": "Сезон на винограднике", "en": "A season in the vineyard",
                       "de": "Eine Saison im Weinberg"}, "course-title"),
          "targets": ["vine-anatomy", "winter-pruning", "canopy", "veraison", "ripeness", "harvest"]}


def build(loc):
    events = []

    def ev(type_, **kv):
        e = {"ev/id": f"vin-{len(events):03d}", "ev/ns": "demo", "ev/actor": "demo-author",
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
        ev("object-added", object={"id": f"o-vin-{cid}-{i}", "concept-id": cid, "kind": kind,
                                   "level": level, "locale": loc, "origin": ":authored",
                                   "text": tr(text, f"obj {cid} {i}")[loc], "grounded-in": src, "anchor": anchor})
    def add_items(items):
        for cid, spec3 in items:
            n = counts[cid] = counts.get(cid, 0) + 1
            kind, spec = tr(spec3, f"item {cid}")[loc]
            iid = f"i-vin-{cid}-{n}"
            ev("item-added", item={"id": iid, "primary-concept-id": cid, "level": ":apprentice",
                                   "kind": kind, "locale": loc, "spec": spec})
            ev("item-accepted", id=iid)

    def publish():
        ev("course-published", course={"id": COURSE["id"], "title": COURSE["title"][loc],
                                       "targets": [[t, ":apprentice"] for t in COURSE["targets"]],
                                       "locale": loc, "pass-threshold": 80,
                                       "diagnostic-items": 12, "final-items": 18})

    add_items(ITEMS)
    qid, qcid, qtext = QUESTION
    ev("question-raised", **{"id": qid, "concept-id": qcid, "question": qtext[loc]})
    publish()
    # APPENDED, never inserted (demos load idempotently by :ev/id), then
    # republished: a publication is a cut, learners fold up to it
    add_items(EXERCISES)
    publish()
    return events


out = {loc: build(loc) for loc in LOCS}
ids = [[e["ev/id"] for e in out[l]] for l in LOCS]
assert ids[0] == ids[1] == ids[2], "locale ev/id sequences diverged"
for loc in LOCS:
    with open(__file__.replace("build_vineyard.py", f"vineyard.{loc}.edn"), "w") as f:
        f.write(";; Demo KG — vineyard basics. GENERATED by build_vineyard.py.\n")
        for e in out[loc]:
            f.write(edn(e) + "\n")
print(f"vineyard.{{ru,de,en}}.edn: {len(out['ru'])} events each, {len(C)} concepts, "
      f"{len(REQ) + len(EXTRA)} edges, {len(OBJ)} objects, {len(ITEMS)} items")

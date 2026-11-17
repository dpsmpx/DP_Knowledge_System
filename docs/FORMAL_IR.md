# FORMAL_IR — формальное промежуточное представление

Код: `ksys/ir/` (`terms.py`, `nodes.py`, `ksf.py`, `validate.py`). Версия IR: `IR_VERSION = 1`.

IR — стабильная внутренняя модель. Она **не знает** о Markdown, UI, SQLite и журнале. Узел описывает *содержание*: эпистемический статус, provenance и решения человека хранятся уровнем выше (`store`, см. `DATA_MODEL.md`).

## 1. Термы
| Терм | Python | KSF | Пример |
|---|---|---|---|
| символ (сущность или понятие) | `Const("sym", key)` | `alex`, `язык_программирован` | ключ после нормализации |
| строка | `Const("str", …)` | `"текст"` | дословный текст |
| число | `Const("num", "10.5")` | `10.5`, `-3` | каноническая десятичная запись (`10.50` → `10.5`) |
| дата | `Const("date", "2025-01-01")` | `2025-01-01` | ISO, проверяется календарём |
| логическое значение | `Const("bool", "true")` | `true` / `false` | |
| переменная | `Var("X")` | `X`, `Person`, `?x` | только в правилах, ограничениях и вопросах |

Нормализация ключей символов **инъецируется** вызывающим слоем: в CNL и KSF работает один и тот же нормализатор (`semantics.normalize.entity_key`), поэтому `язык_программирования` в KSF и «языком программирования» в тексте дают один ключ.

## 2. Атом, литерал, сравнение, интервал
- `Atom(predicate, args)` — n-арное отношение, `uses(alex, python)`. Значение атрибута записывается как `value(subject, attribute, value)`.
- `Literal(atom, negated)` — **сильное отрицание**: `not p` означает «известно, что не p», а не «p неизвестно» (принцип UNKNOWN FIRST).
- `Comparison(op, l, r)`, где op ∈ `< <= > >= = !=`. Несравнимые типы дают «не выполнено», а не исключение и не «истину».
- `Interval(start, end)` — замкнутый интервал дат, `None` — неограниченная сторона. Операции: пересечение, принадлежность, покрытие.

## 3. Утверждение (Assertion)
```
Assertion = atom + negated + modality(asserted|usually|possible) + context + interval
ID: A:<sha256(canonical_json)[:16]>
```
Одно и то же содержание всегда даёт один и тот же `A:`-ID. Полярность, модальность, контекст и интервал входят в идентичность.

## 4. Узлы
| Тип | Префикс ID | Содержание | Идентичность |
|---|---|---|---|
| `Fact` | `F:` | Assertion + knowledge_type | `F:h` = `A:h` (тот же хеш) |
| `Claim` | `C:` | Assertion + author | хеш(assertion, author) — заявление ≠ факт |
| `Hypothesis` | `H:` | Assertion | `H:h` = `A:h` |
| `Rule` | `R:` | head (литерал) ← body (литералы + сравнения), имя | хеш(head, body); имя — только ярлык |
| `Constraint` | `K:` | `:- body` (запрет), сообщение | хеш(body) |
| `Definition` | `DEF:` | термин, род, определяющий текст, дифференциальные литералы | хеш всего |
| `Event` | `EVT:` | тип, участники (роль: терм), интервал, описание | хеш всего |
| `Question` | `Q:` | конъюнкция литералов/сравнений; переменные = wh-вопрос | хеш |
| `Context` | `CTX:key` | ключ, ярлык, родитель | ключ |
| `Entity` / `Concept` | `ENT:key` / `CON:key` | ключ, ярлык, (broader) | ключ |
| `Evidence` | `EV:` | target, stance (support/attack), premises, note | хеш |
| `Derivation` | `DRV:` | заключение (`A:`), правило (`R:`), посылки | хеш |
| `Decision` | `DEC:` | action, because (ссылки на узлы), expected_effect | хеш |

Факт, заявление и гипотеза с одним содержанием имеют одинаковый хеш и разные префиксы (`F:h`, `H:h`). Так прямо видно, что гипотеза `H:h` стала фактом `F:h` после решения человека.

## 5. Синтаксис KSF (Knowledge System Formal)
```
% комментарий
developer(alex).                                     % факт
not likes(alex, java).                               % явное отрицание
claim by ivan: increases(coffee, performance).        % заявление с автором
hypothesis causes(stress, insomnia).                  % гипотеза
value(alex, age, 30) @[2025-01-01, 2025-06-01] in work ~usually.
born(alex, 1990-05-01) @1990-05-01.                  % точечный интервал
rule r1: uses(X, Y) :- developer(X), works_with(X, Y).
older(X, Y) :- value(X, age, A), value(Y, age, B), A > B.
not safe(X) :- has_risk(X, high).                     % правило с отрицательной головой
constraint "возраст ≥ 0": :- value(X, age, A), A < 0.
?- uses(alex, X).                                    % вопрос
decision "проверить рынок" because F:0123456789abcdef expect "ясность спроса".
define язык as формальная_система "система записи".
event visit(who: alex, where: moscow) @[2025-01-01, 2025-01-03] "поездка".
#transitive part_of.   #symmetric married_to.   #inverse parent_of, child_of.
#subclass кот, животное.   #disjoint кот, собака.   #functional born.
```
Переменные — с заглавной латинской буквы или `?x`. Кириллица с заглавной буквы — это символ (`Алекс` → `алекс`).

Директивы разворачиваются в правила детерминированно (`expand_directive`). `#functional` — ограничение схемы (кардинальность ≤ 1), а не правило.

Свойство (проверяется тестом на 300 случайных утверждениях): `parse(format(node)) == node` и `node_from_dict(node.to_dict()) == node`.

## 6. Структурная проверка (`ir/validate.py`)
- безопасность правил: каждая переменная головы и сравнений связана литералом тела;
- непустое тело правил, ограничений и вопросов;
- одна арность на предикат (иначе предупреждение `TYPE_ERROR`);
- формат ссылок в `Decision.because` и `Evidence` (`PROVENANCE_ERROR`).

Ошибки типизированы (`core/errors.py`): `LEXICAL_ERROR`, `PARSE_ERROR`, `LOGIC_ERROR`, `TYPE_ERROR`, `PROVENANCE_ERROR` и т. д.

## 7. JSON-форма
`node.to_dict()` → `{"type": "fact", "id": "F:…", "assertion": {...}, "knowledge_type": "OBJECTIVE_FACT"}`.
Термы: `{"k": "sym", "v": "python"}`, переменные: `{"var": "X"}`, атом: `{"p": "uses", "args": [...]}`. Каноническая сериализация — `core.ids.canonical_json` (сортированные ключи, без пробелов).

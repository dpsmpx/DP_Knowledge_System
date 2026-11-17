# ARCHITECTURE

```
Human Input (CLI / chat)                         Obsidian vault (неизменяемый источник)
      │                                                     │ read-only
      ▼                                                     ▼
dialogue/intents ─► dialogue/session          importer/vault ─► source/markdown ─► source/coverage
      │  (операция)       │                          │ (каждый файл → статус, каждый лист → статус)
      ▼                   ▼                          ▼
language: tokenize · stem · lexicon · cues · segment       importer/classify ─► importer/language_analyzer
      ▼
parser/cnl  ─► LF (все прочтения | NO_PARSE с причиной)
      ▼
semantics/build ─► IR (ir/nodes) + semantics/paraphrase (канонический пересказ)
      ▼
store/kb.preview ─► validation/schema + ir/validate + конфликты  ──►  ЧЕЛОВЕК: accept / edit / split / merge /
      │                                                                 reject / defer / mark as claim|hypothesis
      ▼  (только явным решением)
store/journal (append-only, hash chain)  ──replay──►  store/state (записи, статусы, решения, Resolver)
      ▼
inference/engine: auth (принятое)  ·  full (+ заявления, лениво)   — полунаивный Datalog + DRed TMS
      ▼
query/answers (Белнап)  ·  query/hypothesis + argumentation/grounded  ·  query/smt  ·  temporal/allen
      ▼
provenance/proof (дерево доказательства, трассировка до фрагмента)
      ▼
Human-readable Answer (ANSWER · STATUS · EVIDENCE · DERIVATION · CONTRADICTIONS · UNKNOWNS)
      ▼
export/canonical (JSONL · RDF 1.2 Turtle · Obsidian · stale)   export/reports (7 отчётов)
```

## Слои и их контракты
| Слой | Пакет | API (вход → выход) | Не знает о |
|---|---|---|---|
| ядро | `core` | ID, ошибки, часы, перечисления | всём остальном |
| IR | `ir` | `Node`, `Assertion`, `parse_ksf`, `format_node`, `check_node` | Markdown, БД, языке, UI |
| источник | `source` | `parse_document(path, text) → ParsedDocument`, `check_coverage` | смысле текста, БД, выводе |
| язык | `language` | токены, основы, лексикон, маркеры, предложения | IR-хранилище, выводе |
| грамматика | `parser` | `CNLParser.parse(text) → ParseOutcome(LF…)` | ключах, БД, журнале |
| семантика | `semantics` | `Formalizer.formalize(text, origin) → Formalization` (IR + пересказ) | журнале (только читает решения через `Resolver`) |
| импорт | `importer` | `import_vault(ws, vault, clock, analyzer) → manifest` | выводе, UI |
| хранилище | `store` | `Journal`, `KnowledgeState`, `KnowledgeBase` (workflow + слои вывода) | UI, импорте |
| схема | `validation` | `Schema`, `Validator.validate(items) → [Diagnostic]` | Markdown |
| вывод | `inference` | `Engine` (`add_base`, `add_rule`, `remove_*`, `bulk_load`, `match`) | Markdown, языке, журнале |
| provenance | `provenance` | `ProofBuilder.build(aid)`, `render`, `trace_sources` | Markdown, UI |
| аргументация | `argumentation` | `Framework.grounded()` | всём остальном |
| время | `temporal` | `relation(i1, i2)`, `state_at(engine, day)` | языке, журнале |
| запросы | `query` | `Answerer`, `HypothesisEngine`, `smt.*` | Markdown, UI |
| диалог | `dialogue` | `Dialogue.handle(text, yes) → Response` | печати/UI |
| экспорт | `export` | канонические форматы, отчёты | UI |
| CLI | `cli` | разбор аргументов и печать | логике вывода (её там нет) |

Направление зависимостей проверяется автоматически (`tests/test_layering.py`). Например: `inference` не импортирует `source`/`language`, `parser` и `semantics` не пишут в журнал, ядро не импортирует сетевые и LLM-библиотеки.

## Потоки
- **Импорт:** файл → статус → блоки → листы (покрытие) → класс → грамматика → кандидаты-*заявления* → SQLite + манифест + отчёты. Журнал не меняется.
- **Утверждение:** текст → предпросмотр (ORIGINAL → FORMAL → CANONICAL + проверки + конфликты) → **решение человека** → журнал → `state.apply` → инкрементальный TMS → дельта (что добавилось или удалилось).
- **Вопрос:** текст → операция → формализация → ответ движка (auth) + аргументы (full) → дерево доказательства → ответ.
- **Отзыв / пересмотр:** журнал → `state` (OBSOLETE, supersedes) → DRed → затронутые выводы → решения, потерявшие опору.
- **История:** `KnowledgeBase(at=J:…)` — replay префикса журнала, только чтение.

## Хранение
| Артефакт | Где | Авторитетен? | Пересобирается |
|---|---|---|---|
| журнал решений | `data/formal/journal.jsonl` (git) | **да** | нет (это источник) |
| vault | корень репозитория | источник текста | нет |
| индекс | `var/ks.sqlite` | нет | `knowledge rebuild` |
| кеш производного состояния | `var/engine.pickle` | нет | автоматически |
| манифест и отчёты | `reports/` (git) | нет | `knowledge import`, `knowledge reports` |
| экспорт | `data/formal/export/<формат>/` | нет | `knowledge export` |

## Детерминизм
- Контентные ID (sha256 от однозначной кодировки), никаких автоинкрементов.
- Время только при записи решения (`KS_NOW` для тестов и демо). Производное состояние время не читает.
- Неподвижная точка вывода — множество; порядок обхода на результат не влияет. Печать всегда отсортирована.
- Кеш используется, только если его голова — префикс журнала, и он эквивалентен полному replay (проверено).

## Точки расширения
- `lexicon.json` в рабочем пространстве — новые глаголы → предикаты (с тестами в golden-наборе).
- `schemas/*.json` и решения `schema` в журнале — типы, кардинальности, ограничения.
- `rules/*.ks` — библиотеки правил на KSF. Загружаются только явной командой `knowledge rules load` (решение человека).
- **Необязательный AI-адаптер (вне ядра, roadmap):** может предлагать *прочтения* текста в виде IR-кандидатов. Они проходят тот же путь предпросмотра и принятия человеком. Адаптер не имеет доступа к журналу, а ядро от него не зависит (`test_core_has_no_llm_or_network_dependencies`).

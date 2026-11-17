# DATA_MODEL — модель знания и хранения

Три слоя данных с разной степенью авторитетности:

```
ИСТОЧНИК (vault, неизменяем)  →  КАНДИДАТЫ (машина предлагает)  →  ЖУРНАЛ РЕШЕНИЙ (человек утверждает)
     source/                         importer + parser                 data/formal/journal.jsonl
                                                                              │ replay (детерминированно)
                                                                              ▼
                                         АВТОРИТЕТНОЕ СОСТОЯНИЕ: записи знаний + вывод + TMS (SQLite-индекс)
```

Единственный авторитетный артефакт — **журнал решений человека**. Всё остальное (SQLite, отчёты, экспорт) пересобирается командой `knowledge rebuild` из `vault + journal`.

## 1. Источник
- **Document**: `path` (относительно корня vault, NFC), `sha256` содержимого, `size`, `kind` (`markdown` | зарегистрированный файл без разбора), флаги (`sync_conflict`, `template`, `ai_generated_hint`).
- **Fragment**: листовой фрагмент — минимальная единица provenance (см. `IMPORT.md`).
  - `id = f_<hash(doc_path, kind, нормализованный текст, номер вхождения)>` — стабилен при правках в другой части документа;
  - `locator` — человекочитаемый адрес `path::b17.s03` (блок 17, предложение 3);
  - `span = [start, end)` — смещения в символах исходного текста;
  - `text`, `text_hash` (повторная привязка после переноса файла);
  - `kind` (FragmentKind) и `formalization` (FULL / PARTIAL / AMBIGUOUS / NONE / NON_KNOWLEDGE / HUMAN_REJECTED).

## 2. Кандидат (Candidate)
Результат разбора фрагмента или ввода человека. **Не знание.**
```
Candidate { id: CAND:<hash(origin, reading, nodes)>, origin: fragment_id | input:<hash>,
            reading_id, nodes: [IR-узлы], paraphrase, diagnostics, ambiguity }
```

## 3. Журнал решений (authoritative, append-only)
Строка JSONL = одно решение = один **knowledge commit**:
```json
{"seq": 7, "id": "J:…", "prev": "J:…", "ts": "2026-10-05T10:00:00Z", "actor": "human:me",
 "op": "accept", "data": {…}, "note": "…"}
```
`id = J:hash(prev, seq, ts, actor, op, data, note)`. Цепочка хешей защищает историю от незаметной правки: `knowledge validate` её проверяет.

| op | data | смысл |
|---|---|---|
| `accept` | `node`, `role`, `knowledge_type`, `confidence`, `provenance`, `candidates[]` | человек утверждает узел (факт/правило/заявление/гипотезу/определение/решение/событие/свидетельство). split = несколько `accept` с одним кандидатом; merge = один `accept` с несколькими |
| `reject` | `candidates[]` или `target`, `reason` | отклонить кандидата/узел. Отклонённый узел не может стать авторитетным без нового решения |
| `defer` | `candidates[]`, `status` (`UNKNOWN`) | «пометить как неизвестное»: фрагмент остаётся UNRESOLVED по решению человека |
| `retract` | `target`, `reason` | отозвать знание (запись → OBSOLETE, история сохраняется) |
| `revise` | `target`, `node`, `provenance`, `reason` | заменить: старое → OBSOLETE, `superseded_by` → новое |
| `resolve` | `input_hash`, `reading_id` | выбор прочтения неоднозначного текста; переиспользуется при повторной встрече того же текста |
| `bind` | `input_hash`, `slot`, `entity` | разрешение ссылки («он» → `alex`) |
| `label` / `alias` | `key`, `label` / `alias`, `canonical` | ярлыки понятий и склейка ключей |
| `schema` | `schema` | пользовательское расширение схемы |

## 4. Запись знания (Record) — результат детерминированного replay журнала
```
Record {
  id                  F:/C:/H:/R:/K:/DEF:/DEC:/EVT:/EV:
  node                IR-узел
  role                fact | claim | hypothesis | rule | constraint | definition | decision | event | evidence
  epistemic_status    HUMAN_ACCEPTED | CLAIMED | HUMAN_REJECTED | OBSOLETE | …
  knowledge_type      OBJECTIVE_FACT | PERSONAL_OBSERVATION | PERSONAL_PREFERENCE | PERSONAL_DECISION |
                      PERSONAL_OPINION | EXTERNAL_CLAIM | HYPOTHESIS | PLAN
  confidence          high | medium | low | unrated (назначает человек)
  provenance[]        см. §5 (одно знание может иметь несколько источников)
  created_at          ts первого решения;   updated_at — ts последнего
  journal[]           J:-ID решений, затронувших запись
  supersedes[] / superseded_by
  — вычисляемые связи —
  supports[] / contradicts[] / derived_from[] / dependents[]
}
```
Статусы вывода (DERIVED, SUPPORTED, REFUTED, CONTESTED) — свойства *ответов* движка, они вычисляются и не записываются в журнал.

## 5. Provenance (ответ на вопрос «почему это в базе?»)
```
Provenance {
  source_type   vault_note | human_input | rules_file | demo
  document, document_sha, fragment_id, locator, span, text, text_hash
  extraction    { method: cnl | ksf | structural | manual_edit,
                  grammar_version, parser_version, reading_id }
  author        "me" | внешний автор (для заявлений)
  verifier      actor решения;  verified_at — ts;  journal_entry — J:-ID
}
```
Цепочка восстановления: `факт → J:-решение → кандидат/прочтение → фрагмент → документ`. Для вывода: `D:вывод → R:правило → посылки → … → решения человека → фрагменты`.

## 6. Уверенность
Качественная шкала `high > medium > low > unrated` назначается человеком при принятии. Для выводов — **самое слабое звено** по всем посылкам выбранного обоснования, с учётом лучшего из альтернативных обоснований. Это детерминированный минимум/максимум по порядку, а не вероятность и не AI-score.

## 7. SQLite-индекс (производный, `var/ks.sqlite`)
Таблицы: `meta`, `documents`, `fragments`, `candidates`, `journal`, `records`, `items` (вычисленные утверждения), `justifications`, `labels`, `aliases`. Индексы: по `predicate`, по первому и второму аргументу, по `fragment.document`, по `justification.premise`. БД можно удалить в любой момент: `knowledge rebuild` восстановит её байт-в-байт эквивалентной по содержанию.

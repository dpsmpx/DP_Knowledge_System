# PROVENANCE — «почему это в базе?»

Код: `ksys/provenance/proof.py`, записи provenance — `ksys/store/state.py`, `ksys/store/kb.py`.

## 1. Цепочка для принятого факта
```
факт F:h ──► решение человека J:… (actor, ts, confidence)
         ──► provenance: source_type, document, document_sha, fragment_id, locator, span, text, text_hash
         ──► extraction: method (cnl | ksf | manual_edit), grammar_version, parser_version, reading_id
         ──► (если был выбор прочтения/привязка ссылки) решения resolve/bind в журнале
```
Провенанс хранится **внутри журнала** — копия текста фрагмента и его координаты. Поэтому знание объяснимо даже после изменения или удаления заметки. Сверка с текущим vault показывает, существует ли фрагмент сейчас (по `fragment_id`), перемещён ли он (по `text_hash`) или отсутствует (`PROVENANCE_REPORT.md`).

Если одно утверждение принято из нескольких источников, это одна запись с несколькими provenance, без дубликатов. MERGE кандидатов сохраняет все источники (`merged_sources`).

## 2. Цепочка для вывода
```
D:h  ── по правилу R:… (принятому решением J:…, источник правила) ── посылки
посылка — либо D:… (рекурсивно), либо F:… (→ §1)
```
Инвариант 3: выведенный элемент без обоснования невозможен. При построении дерева это проверяется, нарушение даёт `PROVENANCE_ERROR`.

## 3. Формат proof trace
Текстовый (для человека):
```
D:5b… uses(алекс, python)  [DERIVED, уверенность=medium]
└─ по правилу R:9c…: uses(?X, python) :- is_a(?X, разработчик), works_with(?X, python).
   ├─ F:1a… is_a(алекс, разработчик)  [ASSERTED, уверенность=high]
   │     ← F:1a… HUMAN_ACCEPTED решение J:… human:me 2026-10-05T10:00:00Z; источник: human_input «Алекс является разработчиком.»
   └─ F:7e… works_with(алекс, python)  [ASSERTED, уверенность=medium]
         ← F:7e… HUMAN_ACCEPTED решение J:… …; источник: notes/alex.md::b2.1 «Алекс работает с Python.»
```
JSON (`ProofNode.to_dict`): `{id, aid, ksf, kind, confidence, sources[], derivations[{justification, rule, rule_ksf, rule_source, premises[]}], repeated, truncated}`.

Обход ограничен: глубина 25, 400 узлов, до 3 альтернативных обоснований на узел. Общие подграфы печатаются один раз, повторы помечаются «см. выше».

## 4. Плоская трассировка (`trace_sources`)
`{conclusion, base_facts[], rules[], sources[{record, journal_entry, source_type, document, locator, fragment_id, text, verifier}]}` — все решения человека и исходные фрагменты, на которых держится ответ. Это критерий качества из задачи: от любого ответа можно пройти назад до утверждений человека, правил и фрагментов Markdown.

## 5. Уверенность
Качественная (`high > medium > low > unrated`), её назначает человек. Для вывода — минимум по посылкам и правилу (самое слабое звено), максимум по альтернативным обоснованиям. Неоценённое (`unrated`) — самое слабое звено. Неизвестность не повышается.

## 6. Канонический экспорт и устаревание представлений
Экспорт (`export/`) фиксирует `knowledge commit` (голову журнала) и `basis hash` — хеш множества утверждений, на которых построен артефакт. `knowledge stale <manifest>` сравнивает его с текущим состоянием и перечисляет изменившиеся основания. Так README, отчёты и сайт, построенные из знания, не расходятся с ним молча.

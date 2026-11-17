# IMPORT — компилятор Obsidian vault без потерь

Код: `ksys/source/` (модель, разбор Markdown, покрытие), `ksys/importer/` (обход, классификация, языковой анализатор, манифест), `ksys/language/segment.py`, `ksys/language/cues.py`.

## 1. Конвейер
```
файл vault ──read-only──► Document (path, sha256, kind, flags)
   └─ Markdown ─► блоки ─► листовые фрагменты (предложения / строки / строки таблиц / блоки целиком)
                                 └─► классификация (FragmentKind + Formalization + cues)
                                 └─► языковой анализ (CNL) ─► кандидаты IR      [Phase 3]
SQLite-индекс (documents, fragments, candidates)  +  IMPORT_MANIFEST.json  +  отчёты
```
Исходные файлы только читаются. Импорт ничего не пишет в vault (это проверяет тест `test_every_file_has_status_and_vault_is_untouched` — хеш дерева, включая mtime).

## 2. Учёт файлов (каждый файл → статус)
| Статус | Что это | Содержимое читается |
|---|---|---|
| `PARSED` | `.md` (Markdown), `.txt`/`.org` (простой текст), код и текстовые данные (`.py .pas .js .css .html .json .sh …`, один лист CODE на файл) | да |
| `REGISTERED_BINARY` | бинарные и прочие файлы (`.jpg`, `.docx`, `.zip`, без расширения) — зарегистрированы с хешем | только для хеша |
| `SKIPPED_UNSUPPORTED_FORMAT` | `.pwb` — 7-zip-архивы PureWriter (в stdlib нет распаковщика); причина записана | только для хеша |
| `EXCLUDED_SERVICE` | служебные папки плагинов и синхронизации: `.obsidian`, `.makemd`, `.space`, `.stversions`, `.stfolder*`, `.trash` | нет |
| `EXCLUDED_IGNORED` | пути из корневого `.gitignore` (секреты) | **нет** — даже хеш не считается |

Не обходятся только `.git` и сама подсистема `KNOWLEDGE_SYSTEM/`.

## 3. Блоки и листы
Блоки: `frontmatter`, `heading` (ATX и setext), `paragraph`, `list_item`, `task_item`, `quote`, `callout`, `code` (```` ``` ````/`~~~`), `math` (`$$`), `comment` (`%%`), `html`, `table`, `hr`; для `.txt` — абзацы; для кода — `code_file`.

Листы (минимальные единицы provenance):
- текстовые блоки → **предложения**. Лист, первый на своей строке, начинается с начала строки, поэтому маркеры `- `, `> `, `1. ` входят в лист. Строка без предложений (`>` или `-`) становится листом-маркером;
- таблица → строки (строка-разделитель — отдельный лист `delimiter`), ячейки и заголовок колонок сохраняются в `meta`;
- frontmatter, html → строки; код, math, комментарий, заголовок, hr → блок целиком.

Сегментация предложений детерминирована (`language/segment.py`): терминаторы `.!?…`, сокращения (т.е., т.д., г., e.g. …), инициалы, десятичные числа; перевод строки всегда граница.

## 4. Идентичность фрагментов
- `id = f_<sha256(путь, тип_блока, NFC(текст), номер_вхождения)[:16]>` — стабилен при правках в других местах документа. Одинаковые строки (в том числе в разных Unicode-формах NFC/NFD) различаются номером вхождения.
- `locator = путь::b<блок>.<лист>` — человекочитаемый адрес, например `PLANNER/DAILY/x.md::b3.2`.
- `text_hash` = хеш NFC-текста — для повторной привязки provenance, если файл переместили (при реорганизации каталогов).

## 5. Инвариант «ничего не потеряли» (`source/coverage.py`)
Для каждого разобранного документа:
1. листы не пересекаются;
2. текст каждого листа равен срезу источника по его `span`;
3. между листами — только пробельные символы;
4. число непробельных символов внутри листов = их числу в документе.

Нарушение → `IMPORT_ERROR` в манифесте и в `coverage_failures`. Проверяется на синтетических данных в автоматических тестах.
Свойство также проверяет property-тест на 400 случайных смесях конструкций Markdown.

## 6. Классификация (каждый фрагмент → статус)
`importer/classify.py` применяет таблицу правил сверху вниз; сработавшее правило пишется в `cues`.

| Условие | FragmentKind | Formalization |
|---|---|---|
| разделитель, маркер, `hr`, пустой текст | STRUCTURAL | NON_KNOWLEDGE |
| `code`, `math`, файл кода, строка, похожая на код | CODE | NON_KNOWLEDGE |
| frontmatter `ключ: значение` | METADATA | FULL |
| заголовок, подпись вставки | CONTEXT | PARTIAL |
| строка таблицы (ячейки ↔ заголовки) | CONTEXT/RELATION | PARTIAL |
| задача `- [ ]` | TASK | PARTIAL |
| только ссылки / только теги | REFERENCE / METADATA | FULL |
| `?` в конце | QUESTION | NONE |
| цитата `>` | QUOTE | NONE |
| маркер AI-текста | METADATA | NONE |
| глоссарий `**Термин**: …`, «X — это Y», «называется» | DEFINITION | NONE |
| маркеры решения / предпочтения / мнения / правила («если…то», «должен») / гипотезы / чужого заявления | DECISION / PREFERENCE / OPINION / RULE / HYPOTHESIS / CLAIM | NONE |
| повелительное наклонение | PROCEDURE | NONE |
| «нужно», «буду», инфинитив в начале | PLAN | NONE |
| строка, оканчивающаяся на `:` | CONTEXT | NONE |
| заголовок шаблона ежедневника («Сегодня я сделал», «За что я благодарен» …) | EVENT / OPINION / PLAN | NONE |
| дата или маркер события | EVENT | NONE |
| утвердительное предложение (связка, тире, глагольная форма) | CLAIM | NONE |
| короткий именной пункт списка (`пункт ∈ перечень раздела`) | RELATION | PARTIAL |
| иначе | **UNRESOLVED** | NONE |

`FULL` и `AMBIGUOUS` для смыслового текста появляются только из грамматики контролируемого языка (Phase 3, `LANGUAGE_MODEL.md`). Классификация — не знание: любой CLAIM из vault остаётся заявлением источника, пока человек не примет его.

## 7. Идемпотентность и инкрементальность
- Документ перепарсивается, только если изменился его `sha256` или версии конвейера (`fragmenter`, `classifier`, `grammar`, `parser`, `schema`, `analyzer`).
- `import_id = IMP:<хеш(версии, итоги, файлы)>` не зависит от времени: повторный импорт без изменений даёт тот же `import_id` и те же ID фрагментов (тест `test_idempotent_and_incremental`; одинаковый набор файлов и версия анализатора дают одинаковый `import_id`).
- Удалённые из vault документы удаляются из индекса. Provenance принятых знаний, ссылавшихся на них, помечается в `PROVENANCE_REPORT.md` как «источник отсутствует» (привязка по `text_hash`).

## 8. IMPORT_MANIFEST
`reports/IMPORT_MANIFEST.json`:
```
import_id, generated_at,
versions {ks, fragmenter, classifier, grammar, parser, schema, analyzer},
totals {files, by_status, fragments, by_kind, by_formalization, candidates, unresolved, ambiguous, documents_with_errors},
files [{path, size, sha256, status, kind, flags, fragments, by_kind, by_formalization, candidates, unresolved, ambiguous, errors}]
```
Флаги документа: `sync_conflict`, `template`, `non_nfc_name`, `ai_generated_hint`.

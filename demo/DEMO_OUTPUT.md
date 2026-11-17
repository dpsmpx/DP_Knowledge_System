# DEMO — детерминированные сценарии 1–10

Сгенерировано командой `knowledge demo` (фиксированные часы, временные рабочие пространства).

## Сценарий 1. Два факта → правило → вывод

```text
# Два факта + правило → выведенный факт (с деревом доказательства).

> Добавь факт: Алекс является разработчиком.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: F:7df073a6c23964d3

PREVIEW:
{
  "original": "Алекс является разработчиком.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:7335699e00473f82",
      "labels": [],
      "formal": [
        "is_a(алекс, разработчик)."
      ],
      "canonical": "Алекс является «разработчиком».",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:8c6b184a5841aee8

> Добавь факт: Алекс работает с Python.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: F:5b41e6de85db004a

PREVIEW:
{
  "original": "Алекс работает с Python.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:aa0a5c57f2eb9697",
      "labels": [],
      "formal": [
        "works_with(алекс, python)."
      ],
      "canonical": "Алекс работает с Python.",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:4a94dc0e14f63d50

> Добавь правило: Если X является разработчиком и X работает с Python, то X использует Python.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: R:50604b69803649dc

PREVIEW:
{
  "original": "Если X является разработчиком и X работает с Python, то X использует Python.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:72129812ab627048",
      "labels": [],
      "formal": [
        "uses(?X, python) :- is_a(?X, разработчик), works_with(?X, python)."
      ],
      "canonical": "Если X является «разработчиком» и X работает с Python, то X использует Python.",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:adda326f5a192c77

DERIVED_ADDED:
uses(алекс, python)

> Использует ли Алекс Python?
OPERATION: ASK (question)
STATUS:    PROVEN
ANSWER:    Да — доказано из принятого знания.

EVIDENCE:
  {"id": "D:6e0ea968bceae563", "ksf": "uses(алекс, python)", "kind": "DERIVED", "paraphrase": "Алекс использует Python.", "rules": ["R:50604b69803649dc"]}

DERIVATION:
D:6e0ea968bceae563 uses(алекс, python)  [DERIVED, уверенность=unrated]
└─ по правилу R:50604b69803649dc: uses(?X, python) :- is_a(?X, разработчик), works_with(?X, python).
   ├─ F:7df073a6c23964d3 is_a(алекс, разработчик)  [ASSERTED, уверенность=unrated]
   │     ← F:7df073a6c23964d3 HUMAN_ACCEPTED решение J:8c6b184a5841aee8 human:me 2026-01-01T00:00:00Z; источник: human_input «Алекс является разработчиком.»
   └─ F:5b41e6de85db004a works_with(алекс, python)  [ASSERTED, уверенность=unrated]
         ← F:5b41e6de85db004a HUMAN_ACCEPTED решение J:4a94dc0e14f63d50 human:me 2026-01-01T00:00:01Z; источник: human_input «Алекс работает с Python.»
```

## Сценарий 2. Противоречивые факты

```text
# Противоречивые факты: BOTH локально, база не становится тривиальной.

> Добавь факт: Кофе полезен.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: F:274410bcfa86c56c

PREVIEW:
{
  "original": "Кофе полезен.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:7797738c4691c77d",
      "labels": [],
      "formal": [
        "is_a(коф, полезн)."
      ],
      "canonical": "Кофе является «полезен».",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:d16d846d7dcd795a

> Добавь факт: Кофе не полезен.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: F:942cc5335f912bc0

PREVIEW:
{
  "original": "Кофе не полезен.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:9f7c7f74b279acbb",
      "labels": [],
      "formal": [
        "not is_a(коф, полезн)."
      ],
      "canonical": "Кофе не является «полезен».",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [
        {
          "node": "F:942cc5335f912bc0",
          "conflicts_with": "A:274410bcfa86c56c",
          "layer": "auth",
          "ksf": "is_a(коф, полезн)"
        },
        {
          "node": "F:942cc5335f912bc0",
          "conflicts_with": "A:274410bcfa86c56c",
          "layer": "full",
          "ksf": "is_a(коф, полезн)"
        }
      ],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:13430045d69b3a1a

> Добавь факт: Чай полезен.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: F:c537b6b6af94d76e

PREVIEW:
{
  "original": "Чай полезен.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:dda0fab841ea74d0",
      "labels": [],
      "formal": [
        "is_a(ча, полезн)."
      ],
      "canonical": "Чай является «полезен».",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:af109cd716279f0a

> Верно ли, что кофе полезен?
OPERATION: VERIFY (verify)
STATUS:    CONTESTED
ANSWER:    Спорно: есть равносильные аргументы за и против.

EVIDENCE:
  {"argument": "pro:A:274410bcfa86c56c", "label": "UNDEC", "strength": "auth", "basis": ["F:274410bcfa86c56c"], "ksf": "is_a(коф, полезн)", "id": "F:274410bcfa86c56c"}

COUNTER_EVIDENCE:
  {"argument": "con:A:942cc5335f912bc0", "label": "UNDEC", "strength": "auth", "basis": ["F:942cc5335f912bc0"], "ksf": "not is_a(коф, полезн)", "id": "F:942cc5335f912bc0"}

CONFLICTS:
  {"pro": "pro:A:274410bcfa86c56c", "con": "con:A:942cc5335f912bc0"}

DERIVATION:
F:942cc5335f912bc0 not is_a(коф, полезн)  [ASSERTED, уверенность=unrated]
   ← F:942cc5335f912bc0 HUMAN_ACCEPTED решение J:13430045d69b3a1a human:me 2026-01-01T00:00:01Z; источник: human_input «Кофе не полезен.»
F:274410bcfa86c56c is_a(коф, полезн)  [ASSERTED, уверенность=unrated]
   ← F:274410bcfa86c56c HUMAN_ACCEPTED решение J:d16d846d7dcd795a human:me 2026-01-01T00:00:00Z; источник: human_input «Кофе полезен.»

> Верно ли, что чай полезен?
OPERATION: VERIFY (verify)
STATUS:    PROVEN
ANSWER:    Доказано из принятого знания.

EVIDENCE:
  {"argument": "pro:A:c537b6b6af94d76e", "label": "IN", "strength": "auth", "basis": ["F:c537b6b6af94d76e"], "ksf": "is_a(ча, полезн)", "id": "F:c537b6b6af94d76e"}

DERIVATION:
F:c537b6b6af94d76e is_a(ча, полезн)  [ASSERTED, уверенность=unrated]
   ← F:c537b6b6af94d76e HUMAN_ACCEPTED решение J:af109cd716279f0a human:me 2026-01-01T00:00:02Z; источник: human_input «Чай полезен.»

> Верно ли, что чай вреден?
OPERATION: VERIFY (verify)
STATUS:    UNKNOWN
ANSWER:    Неизвестно: нет ни подтверждений, ни опровержений (это не «ложь»).

> Найди противоречия
OPERATION: FIND_CONTRADICTIONS (contradictions)
STATUS:    CONTRADICTIONS_FOUND
ANSWER:    прямых противоречий: 1; нарушений схемы: 0

CONTRADICTIONS:
  {"positive": {"id": "F:274410bcfa86c56c", "ksf": "is_a(коф, полезн)", "kind": "ASSERTED", "paraphrase": "Кофе является «полезен».", "records": ["F:274410bcfa86c56c"]}, "negative": {"id": "F:942cc5335f912bc0", "ksf": "not is_a(коф, полезн)", "kind": "ASSERTED", "paraphrase": "Кофе не является «полезен».", "records": ["F:942cc5335f912bc0"]}, "overlap": {"from": null, "to": null}, "context": null, "positive_basis": [{"record": "F:274410bcfa86c56c", "status": "HUMAN_ACCEPTED", "confidence": "unrated", "knowledge_type": "OBJECTIVE_FACT", "provenance": [{"source_type": "human_input", "text": "Кофе полезен.", "verifier": "human:me", "verified_at": "2026-01-01T00:00:00Z", "journal_entry": "J:d16d846d7dcd795a"}]}], "negative_basis": [{"record": "F:942cc5335f912bc0", "status": "HUMAN_ACCEPTED", "confidence": "unrated", "knowledge_type": "OBJECTIVE_FACT", "provenance": [{"source_type": "human_input", "text": "Кофе не полезен.", "verifier": "human:me", "verified_at": "2026-01-01T00:00:01Z", "journal_entry": "J:13430045d69b3a1a"}]}], "dependents": [], "layer": "auth"}
```

## Сценарий 3. Гипотеза поддержана несколькими фактами

```text
# Гипотеза, поддержанная несколькими фактами и заявлением.

> Добавь факт: Алекс является разработчиком.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: F:7df073a6c23964d3

PREVIEW:
{
  "original": "Алекс является разработчиком.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:7335699e00473f82",
      "labels": [],
      "formal": [
        "is_a(алекс, разработчик)."
      ],
      "canonical": "Алекс является «разработчиком».",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:8c6b184a5841aee8

> Добавь факт: Алекс работает с Python.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: F:5b41e6de85db004a

PREVIEW:
{
  "original": "Алекс работает с Python.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:aa0a5c57f2eb9697",
      "labels": [],
      "formal": [
        "works_with(алекс, python)."
      ],
      "canonical": "Алекс работает с Python.",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:4a94dc0e14f63d50

> Добавь правило: Если X является разработчиком и X работает с Python, то X использует Python.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: R:50604b69803649dc

PREVIEW:
{
  "original": "Если X является разработчиком и X работает с Python, то X использует Python.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:72129812ab627048",
      "labels": [],
      "formal": [
        "uses(?X, python) :- is_a(?X, разработчик), works_with(?X, python)."
      ],
      "canonical": "Если X является «разработчиком» и X работает с Python, то X использует Python.",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:adda326f5a192c77

DERIVED_ADDED:
uses(алекс, python)

> Добавь факт: Если X работает с Python, то X использует Python.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: R:f23f5ffa53c46815

PREVIEW:
{
  "original": "Если X работает с Python, то X использует Python.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:e40c64685cba0aaf",
      "labels": [],
      "formal": [
        "uses(?X, python) :- works_with(?X, python)."
      ],
      "canonical": "Если X работает с Python, то X использует Python.",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:c380874de0327e6c

> Добавь факт: По словам Анны, Алекс использует Python.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: C:f00adcc1f7fc1dfa

PREVIEW:
{
  "original": "По словам Анны, Алекс использует Python.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:d1cf7f40066f52c7",
      "labels": [],
      "formal": [
        "claim by ан: uses(алекс, python)."
      ],
      "canonical": "По утверждению Анны: Алекс использует Python.",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:a1bf480d06ac811f

> Проверь гипотезу, что Алекс использует Python.
OPERATION: HYPOTHESIZE (hypothesize)
STATUS:    PROVEN
ANSWER:    Доказано из принятого знания.

EVIDENCE:
  {"argument": "pro:A:6e0ea968bceae563", "label": "IN", "strength": "auth", "basis": ["F:5b41e6de85db004a", "F:7df073a6c23964d3"], "ksf": "uses(алекс, python)", "id": "D:6e0ea968bceae563"}

DERIVATION:
D:6e0ea968bceae563 uses(алекс, python)  [DERIVED, уверенность=unrated]
├─ по правилу R:f23f5ffa53c46815: uses(?X, python) :- works_with(?X, python).
│  └─ F:5b41e6de85db004a works_with(алекс, python)  [ASSERTED, уверенность=unrated]
│        ← F:5b41e6de85db004a HUMAN_ACCEPTED решение J:4a94dc0e14f63d50 human:me 2026-01-01T00:00:01Z; источник: human_input «Алекс работает с Python.»
└─ по правилу R:50604b69803649dc: uses(?X, python) :- is_a(?X, разработчик), works_with(?X, python).
   ├─ F:7df073a6c23964d3 is_a(алекс, разработчик)  [ASSERTED, уверенность=unrated]
   │     ← F:7df073a6c23964d3 HUMAN_ACCEPTED решение J:8c6b184a5841aee8 human:me 2026-01-01T00:00:00Z; источник: human_input «Алекс является разработчиком.»
   └─ F:5b41e6de85db004a works_with(алекс, python)  [ASSERTED, уверенность=unrated] (см. выше)
```

## Сценарий 4. Гипотеза опровергнута

```text
# Гипотеза, опровергнутая принятым фактом (заявление не может победить факт).

> Добавь факт: Шум не вызывает бессонницу.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: F:b040ebe456b36e68

PREVIEW:
{
  "original": "Шум не вызывает бессонницу.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:9c702b6218efe07d",
      "labels": [],
      "formal": [
        "not causes(шум, бессонниц)."
      ],
      "canonical": "Шум не вызывает «бессонницу».",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:21005843d7086777

> Добавь факт: По словам Ивана, шум вызывает бессонницу.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: C:9b463c9ffeb8b9d7

PREVIEW:
{
  "original": "По словам Ивана, шум вызывает бессонницу.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:66ae12cfdddb01aa",
      "labels": [],
      "formal": [
        "claim by ива: causes(шум, бессонниц)."
      ],
      "canonical": "По утверждению Ивана: Шум вызывает «бессонницу».",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [
        {
          "node": "C:9b463c9ffeb8b9d7",
          "conflicts_with": "A:b040ebe456b36e68",
          "layer": "auth",
          "ksf": "not causes(шум, бессонниц)"
        },
        {
          "node": "C:9b463c9ffeb8b9d7",
          "conflicts_with": "A:b040ebe456b36e68",
          "layer": "full",
          "ksf": "not causes(шум, бессонниц)"
        }
      ],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:9ce488214f4fa19f

> Проверь гипотезу, что шум вызывает бессонницу.
OPERATION: HYPOTHESIZE (hypothesize)
STATUS:    REFUTED
ANSWER:    Опровергнуто: принятое знание (или неопровергнутые аргументы) говорят обратное.

EVIDENCE:
  {"argument": "pro:A:43a596a40398ffcc", "label": "OUT", "strength": "claim", "basis": ["C:9b463c9ffeb8b9d7"], "ksf": "causes(шум, бессонниц)", "id": "F:43a596a40398ffcc"}

COUNTER_EVIDENCE:
  {"argument": "con:A:b040ebe456b36e68", "label": "IN", "strength": "auth", "basis": ["F:b040ebe456b36e68"], "ksf": "not causes(шум, бессонниц)", "id": "F:b040ebe456b36e68"}

CONFLICTS:
  {"pro": "pro:A:43a596a40398ffcc", "con": "con:A:b040ebe456b36e68"}

DERIVATION:
F:b040ebe456b36e68 not causes(шум, бессонниц)  [ASSERTED, уверенность=unrated]
   ← F:b040ebe456b36e68 HUMAN_ACCEPTED решение J:21005843d7086777 human:me 2026-01-01T00:00:00Z; источник: human_input «Шум не вызывает бессонницу.»
F:43a596a40398ffcc causes(шум, бессонниц)  [ASSERTED, уверенность=unrated]
   ← C:9b463c9ffeb8b9d7 CLAIMED решение J:9ce488214f4fa19f human:me 2026-01-01T00:00:01Z; источник: human_input «По словам Ивана, шум вызывает бессонницу.»
```

## Сценарий 5. Неизвестная гипотеза

```text
# Неизвестная гипотеза: UNKNOWN (не FALSE) + недостающие посылки.

> Добавь правило: Если X является разработчиком и X работает с Python, то X использует Python.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: R:50604b69803649dc

PREVIEW:
{
  "original": "Если X является разработчиком и X работает с Python, то X использует Python.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:72129812ab627048",
      "labels": [],
      "formal": [
        "uses(?X, python) :- is_a(?X, разработчик), works_with(?X, python)."
      ],
      "canonical": "Если X является «разработчиком» и X работает с Python, то X использует Python.",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:50b8e18933bc5977

> Добавь факт: Анна является разработчиком.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: F:733f6bd9e5eda26c

PREVIEW:
{
  "original": "Анна является разработчиком.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:92c42579e94d54f5",
      "labels": [],
      "formal": [
        "is_a(ан, разработчик)."
      ],
      "canonical": "Анна является «разработчиком».",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:db6e30f41eacf072

> Проверь гипотезу, что Анна использует Python.
OPERATION: HYPOTHESIZE (hypothesize)
STATUS:    UNKNOWN
ANSWER:    Неизвестно: нет ни подтверждений, ни опровержений (это не «ложь»).

UNKNOWNS:
  {"rule": "R:50604b69803649dc", "rule_ksf": "uses(?X, python) :- is_a(?X, разработчик), works_with(?X, python).", "missing": ["works_with(ан, python)"]}

> Проверь гипотезу, что Python лучше Java.
OPERATION: HYPOTHESIZE (hypothesize)
STATUS:    UNKNOWN
ANSWER:    Неизвестно: нет ни подтверждений, ни опровержений (это не «ложь»).
```

## Сценарий 6. Пересмотр факта инвалидирует выводы

```text
# Пересмотр факта инвалидирует зависимые выводы (TMS, история сохраняется).

> Добавь факт: Возраст Алекса равен 12.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: F:927d49f61acd4662

PREVIEW:
{
  "original": "Возраст Алекса равен 12.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:51c86b61e5140b1d",
      "labels": [],
      "formal": [
        "value(алекс, возраст, 12)."
      ],
      "canonical": "Значение «Возраст» у Алекса равно 12.",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:5a89f3c055406dfc

> Добавь факт: Возраст Анны равен 11.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: F:fbc160413436e646

PREVIEW:
{
  "original": "Возраст Анны равен 11.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:221530bbe3550a92",
      "labels": [],
      "formal": [
        "value(ан, возраст, 11)."
      ],
      "canonical": "Значение «Возраст» у Анны равно 11.",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:3d50541268add6ae

> Добавь правило: older_than(X, Y) :- value(X, возраст, A), value(Y, возраст, B), A > B.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: R:95e068bf243ed69b

PREVIEW:
{
  "original": "older_than(X, Y) :- value(X, возраст, A), value(Y, возраст, B), A > B.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:ksf",
      "labels": [],
      "formal": [
        "older_than(?X, ?Y) :- value(?X, возраст, ?A), value(?Y, возраст, ?B), ?A > ?B."
      ],
      "canonical": "Если значение «Возраст» у X равно A и значение «Возраст» у Y равно B и A больше B, то X старше, чем Y.",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:f73d26e09ddae6b3

DERIVED_ADDED:
older_than(алекс, ан)

> Добавь: Решение: поручить задачу Алексу, потому что Алекс старше Анны.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: DEC:951e5b4ca4b75263

PREVIEW:
{
  "original": "Решение: поручить задачу Алексу, потому что Алекс старше Анны.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:bed082823dfd10cb",
      "labels": [],
      "formal": [
        "decision \"поручить задачу Алексу\" because F:6e478ed258cb6121."
      ],
      "canonical": "Решение: поручить задачу Алексу (основание: F:6e478ed258cb6121).",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:bb15405b1b2835fe

> Верно ли, что Алекс старше Анны?
OPERATION: VERIFY (verify)
STATUS:    PROVEN
ANSWER:    Доказано из принятого знания.

EVIDENCE:
  {"argument": "pro:A:6e478ed258cb6121", "label": "IN", "strength": "auth", "basis": ["F:927d49f61acd4662", "F:fbc160413436e646"], "ksf": "older_than(алекс, ан)", "id": "D:6e478ed258cb6121"}

DERIVATION:
D:6e478ed258cb6121 older_than(алекс, ан)  [DERIVED, уверенность=unrated]
└─ по правилу R:95e068bf243ed69b: older_than(?X, ?Y) :- value(?X, возраст, ?A), value(?Y, возраст, ?B), ?A > ?B.
   ├─ F:927d49f61acd4662 value(алекс, возраст, 12)  [ASSERTED, уверенность=unrated]
   │     ← F:927d49f61acd4662 HUMAN_ACCEPTED решение J:5a89f3c055406dfc human:me 2026-01-01T00:00:00Z; источник: human_input «Возраст Алекса равен 12.»
   └─ F:fbc160413436e646 value(ан, возраст, 11)  [ASSERTED, уверенность=unrated]
         ← F:fbc160413436e646 HUMAN_ACCEPTED решение J:3d50541268add6ae human:me 2026-01-01T00:00:01Z; источник: human_input «Возраст Анны равен 11.»

> Исправление: возраст Алекса было 10, а не 12.
OPERATION: REVISE (revise)
STATUS:    PREVIEW
ANSWER:    value(алекс, возраст, 12). → value(алекс, возраст, 10). (не записано; подтвердите --yes).

WOULD_INVALIDATE:
older_than(алекс, ан)
value(алекс, возраст, 12)

> Исправление: возраст Алекса было 10, а не 12.   [подтверждено: --yes]
OPERATION: REVISE (revise)
STATUS:    COMMITTED
ANSWER:    F:927d49f61acd4662 SUPERSEDED_BY F:a52f28c1d1e58435

INVALIDATED:
A:6e478ed258cb6121
A:927d49f61acd4662

ADDED:
A:23bbc2408e8b36f2
A:a52f28c1d1e58435

DECISION_IMPACT:
DEC:951e5b4ca4b75263 «поручить задачу Алексу»: основание F:6e478ed258cb6121 больше не поддерживается

> Верно ли, что Алекс старше Анны?
OPERATION: VERIFY (verify)
STATUS:    UNKNOWN
ANSWER:    Неизвестно: нет ни подтверждений, ни опровержений (это не «ложь»).

UNKNOWNS:
  {"rule": "R:95e068bf243ed69b", "rule_ksf": "older_than(?X, ?Y) :- value(?X, возраст, ?A), value(?Y, возраст, ?B), ?A > ?B.", "missing": []}

> Верно ли, что Анна старше Алекса?
OPERATION: VERIFY (verify)
STATUS:    PROVEN
ANSWER:    Доказано из принятого знания.

EVIDENCE:
  {"argument": "pro:A:23bbc2408e8b36f2", "label": "IN", "strength": "auth", "basis": ["F:a52f28c1d1e58435", "F:fbc160413436e646"], "ksf": "older_than(ан, алекс)", "id": "D:23bbc2408e8b36f2"}

DERIVATION:
D:23bbc2408e8b36f2 older_than(ан, алекс)  [DERIVED, уверенность=unrated]
└─ по правилу R:95e068bf243ed69b: older_than(?X, ?Y) :- value(?X, возраст, ?A), value(?Y, возраст, ?B), ?A > ?B.
   ├─ F:fbc160413436e646 value(ан, возраст, 11)  [ASSERTED, уверенность=unrated]
   │     ← F:fbc160413436e646 HUMAN_ACCEPTED решение J:3d50541268add6ae human:me 2026-01-01T00:00:01Z; источник: human_input «Возраст Анны равен 11.»
   └─ F:a52f28c1d1e58435 value(алекс, возраст, 10)  [ASSERTED, уверенность=unrated]
         ← F:a52f28c1d1e58435 HUMAN_ACCEPTED решение J:28b796a6ee8a46ce human:me 2026-01-01T00:00:04Z; источник: human_input

> Какие неизвестные остаются?
OPERATION: UNKNOWNS (unknowns)
STATUS:    OPEN
ANSWER:    открытых вопросов: 5

UNKNOWNS:
{
  "open_hypotheses": [],
  "unsupported_decision_bases": [
    {
      "decision": "DEC:951e5b4ca4b75263",
      "unsupported_basis": "F:6e478ed258cb6121"
    }
  ],
  "incomplete": [],
  "unrated_confidence": [
    "DEC:951e5b4ca4b75263",
    "F:a52f28c1d1e58435",
    "F:fbc160413436e646",
    "R:95e068bf243ed69b"
  ],
  "claims_not_reviewed": [],
  "deferred_candidates": []
}
```

## Сценарий 7. Неоднозначный текст и решение человека

```text
# Неоднозначный текст требует решения человека; решение сохраняется и переиспользуется.

> Добавь факт: Алекс держит магазин.
OPERATION: ASSERT (assert)
STATUS:    PREVIEW_AMBIGUOUS
ANSWER:    Предпросмотр. В базу ничего не записано; подтвердите явно.

PREVIEW:
{
  "original": "Алекс держит магазин.",
  "status": "AMBIGUOUS",
  "reason": "several readings",
  "options": [
    {
      "reading_id": "RD:7ba1c114bc29d571",
      "labels": [
        "sense=owns"
      ],
      "formal": [
        "owns(алекс, магазин)."
      ],
      "canonical": "Алекс владеет «магазин».",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    },
    {
      "reading_id": "RD:425d135df0477f36",
      "labels": [
        "sense=holds"
      ],
      "formal": [
        "holds(алекс, магазин)."
      ],
      "canonical": "Алекс удерживает «магазин».",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

> Добавь факт: Алекс держит магазин.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: F:c872400955fff0d4

PREVIEW:
{
  "original": "Алекс держит магазин.",
  "status": "AMBIGUOUS",
  "reason": "several readings",
  "options": [
    {
      "reading_id": "RD:7ba1c114bc29d571",
      "labels": [
        "sense=owns"
      ],
      "formal": [
        "owns(алекс, магазин)."
      ],
      "canonical": "Алекс владеет «магазин».",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    },
    {
      "reading_id": "RD:425d135df0477f36",
      "labels": [
        "sense=holds"
      ],
      "formal": [
        "holds(алекс, магазин)."
      ],
      "canonical": "Алекс удерживает «магазин».",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:b0a0ebced3ec5e4f

> Добавь факт: Алекс держит магазин.
OPERATION: ASSERT (assert)
STATUS:    PREVIEW_FORMALIZED
ANSWER:    Предпросмотр. В базу ничего не записано; подтвердите явно.

PREVIEW:
{
  "original": "Алекс держит магазин.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:7ba1c114bc29d571",
      "labels": [
        "sense=owns"
      ],
      "formal": [
        "owns(алекс, магазин)."
      ],
      "canonical": "Алекс владеет «магазин».",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [
        "F:c872400955fff0d4"
      ],
      "rejected_before": []
    },
    {
      "reading_id": "RD:425d135df0477f36",
      "labels": [
        "sense=holds"
      ],
      "formal": [
        "holds(алекс, магазин)."
      ],
      "canonical": "Алекс удерживает «магазин».",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

> Добавь факт: Он использует Git.
OPERATION: ASSERT (assert)
STATUS:    PREVIEW_UNRESOLVED
ANSWER:    Предпросмотр. В базу ничего не записано; подтвердите явно.

PREVIEW:
{
  "original": "Он использует Git.",
  "status": "UNRESOLVED",
  "reason": "unresolved reference",
  "options": [
    {
      "reading_id": "RD:f92745420324c86d",
      "labels": [],
      "formal": [],
      "canonical": "НЕ РАЗРЕШЕНО: Кто или что обозначено словом «Он»?",
      "unresolved": [
        {
          "slot": "c0.0",
          "text": "Он",
          "question": "Кто или что обозначено словом «Он»?"
        }
      ],
      "notes": [],
      "diagnostics": [
        {
          "kind": "UNRESOLVED_REFERENCE",
          "severity": "warning",
          "message": "Кто или что обозначено словом «Он»?",
          "location": "c0.0"
        }
      ],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

# человек отвечает: «Он» = Алекс  → журнал: bind input:190333fefef47ae6 c0.0 алекс

> Добавь факт: Он использует Git.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: F:6062fdea6d3fab7d

PREVIEW:
{
  "original": "Он использует Git.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:f92745420324c86d",
      "labels": [],
      "formal": [
        "uses(алекс, git)."
      ],
      "canonical": "Алекс использует Git.",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:6968e3801a6cd869

> Кто использует Git?
OPERATION: ASK (question)
STATUS:    PROVEN
ANSWER:    WHO=алекс

BINDINGS:
  {"binding": {"WHO": "алекс"}, "evidence": ["F:6062fdea6d3fab7d"]}
```

## Сценарий 8. Разные слова — одно знание

```text
# Одно знание разными словами → одно представление и один ID.
вызывает     → ['causes(стресс, бессонниц).'] ['F:2ac49ea28732c74c']
приводит к   → ['causes(стресс, бессонниц).'] ['F:2ac49ea28732c74c']
KSF          → ['causes(стресс, бессонниц).'] ['F:2ac49ea28732c74c']
одинаковые ID: True

> Добавь факт: Стресс вызывает бессонницу.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: F:2ac49ea28732c74c

PREVIEW:
{
  "original": "Стресс вызывает бессонницу.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:6ee8371e7fbd40ed",
      "labels": [],
      "formal": [
        "causes(стресс, бессонниц)."
      ],
      "canonical": "Стресс вызывает «бессонницу».",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:03ee937a8a893ef1

> Добавь факт: Стресс приводит к бессоннице.
OPERATION: ASSERT (assert)
STATUS:    PREVIEW_FORMALIZED
ANSWER:    Предпросмотр. В базу ничего не записано; подтвердите явно.

PREVIEW:
{
  "original": "Стресс приводит к бессоннице.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:50f1f76e9b53f47a",
      "labels": [],
      "formal": [
        "causes(стресс, бессонниц)."
      ],
      "canonical": "Стресс вызывает «бессонницу».",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [
        "F:2ac49ea28732c74c"
      ],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer
```

## Сценарий 9. Временной факт

```text
# Временной факт: истинен только в интервале действия.

> Добавь факт: С 2025-01-01 по 2025-06-01 Алекс работает в Google.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: F:5b736a08901e5794

PREVIEW:
{
  "original": "С 2025-01-01 по 2025-06-01 Алекс работает в Google.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:6b63b20d8fcfd361",
      "labels": [],
      "formal": [
        "works_at(алекс, google) @[2025-01-01, 2025-06-01]."
      ],
      "canonical": "Алекс работает в Google (в период с 2025-01-01 по 2025-06-01).",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:de4952c1b7123f7c
на 2025-03-01: PROVEN — Да — доказано из принятого знания.
на 2025-07-01: UNKNOWN — Неизвестно: нет ни доказательства, ни опровержения (это не «нет»).
отношение Аллена [2025-01-01..2025-06-01] к [2025-03-01..2025-12-31]: overlaps
```

## Сценарий 10. Provenance до фрагмента Markdown

```text
# Трассировка ответа до исходного фрагмента Markdown (demo/vault/people/alex.md).
импорт demo/vault: файлов 2, фрагментов 11, кандидатов 8, по формализации {'AMBIGUOUS': 2, 'FULL': 5, 'NONE': 1, 'PARTIAL': 3}
кандидат CAND:94bc06e70b1e2bcd ← people/alex.md::b2.1 «Алекс является разработчиком.»
кандидат CAND:7cb351c6fd1c20f0 ← people/alex.md::b2.2 «Алекс работает с Python.»
кандидат CAND:ebdf3dea42578786 ← people/alex.md::b3.1 «Возраст Алекса равен 12.»
кандидат CAND:c85403d9d9761e92 ← people/alex.md::b4.1 «Возраст Анны равен 11.»
человек принимает CAND:94bc06e70b1e2bcd как факт
человек принимает CAND:7cb351c6fd1c20f0 как факт

> Добавь правило: Если X является разработчиком и X работает с Python, то X использует Python.   [подтверждено: --yes]
OPERATION: ASSERT (assert)
STATUS:    COMMITTED
ANSWER:    Принято: R:50604b69803649dc

PREVIEW:
{
  "original": "Если X является разработчиком и X работает с Python, то X использует Python.",
  "status": "FORMALIZED",
  "reason": "",
  "options": [
    {
      "reading_id": "RD:72129812ab627048",
      "labels": [],
      "formal": [
        "uses(?X, python) :- is_a(?X, разработчик), works_with(?X, python)."
      ],
      "canonical": "Если X является «разработчиком» и X работает с Python, то X использует Python.",
      "unresolved": [],
      "notes": [],
      "diagnostics": [],
      "conflicts": [],
      "known": [],
      "rejected_before": []
    }
  ]
}

ACTIONS:
ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]
EDIT: assert "<KSF>" --yes
REJECT: review reject <candidate|node>
MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis
MARK AS UNKNOWN: review defer

COMMITTED:
J:bb0e07bcd46797d1

DERIVED_ADDED:
uses(алекс, python)

> Почему ты считаешь, что Алекс использует Python?
OPERATION: EXPLAIN (explain)
STATUS:    PROVEN
ANSWER:    D:6e0ea968bceae563 uses(алекс, python): выведено; дерево доказательства ниже.

DERIVATION:
D:6e0ea968bceae563 uses(алекс, python)  [DERIVED, уверенность=unrated]
└─ по правилу R:50604b69803649dc: uses(?X, python) :- is_a(?X, разработчик), works_with(?X, python).
   ├─ F:7df073a6c23964d3 is_a(алекс, разработчик)  [ASSERTED, уверенность=high]
   │     ← F:7df073a6c23964d3 HUMAN_ACCEPTED решение J:fc530a359801d5b2 human:me 2026-01-01T00:00:01Z; источник: people/alex.md::b2.1 «Алекс является разработчиком.»
   └─ F:5b41e6de85db004a works_with(алекс, python)  [ASSERTED, уверенность=high]
         ← F:5b41e6de85db004a HUMAN_ACCEPTED решение J:391f3617ff4c6a34 human:me 2026-01-01T00:00:02Z; источник: people/alex.md::b2.2 «Алекс работает с Python.»

> Откуда взялся этот вывод?
OPERATION: TRACE (trace)
STATUS:    TRACED
ANSWER:    2 принятых фактов, 1 правил, 3 исходных записей provenance.

TRACE:
{
  "conclusion": "A:6e0ea968bceae563",
  "base_facts": [
    "F:5b41e6de85db004a",
    "F:7df073a6c23964d3"
  ],
  "rules": [
    "R:50604b69803649dc"
  ],
  "sources": [
    {
      "record": "F:5b41e6de85db004a",
      "journal_entry": "J:391f3617ff4c6a34",
      "source_type": "vault_note",
      "document": "people/alex.md",
      "locator": "people/alex.md::b2.2",
      "fragment_id": "f_1e35c133fe901203",
      "text": "Алекс работает с Python.",
      "verifier": "human:me"
    },
    {
      "record": "F:7df073a6c23964d3",
      "journal_entry": "J:fc530a359801d5b2",
      "source_type": "vault_note",
      "document": "people/alex.md",
      "locator": "people/alex.md::b2.1",
      "fragment_id": "f_1515a76e50cd86da",
      "text": "Алекс является разработчиком.",
      "verifier": "human:me"
    },
    {
      "record": "R:50604b69803649dc",
      "journal_entry": "J:bb0e07bcd46797d1",
      "source_type": "human_input",
      "document": null,
      "locator": null,
      "fragment_id": null,
      "text": "Если X является разработчиком и X работает с Python, то X использует Python.",
      "verifier": "human:me"
    }
  ]
}
```


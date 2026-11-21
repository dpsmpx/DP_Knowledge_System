# Stage 18 — manual dated-source UI acceptance (synthetic notes)

This is a reproducible **physical touch/UI** scenario for signed preview #28
or later, not a substitute for the separate quick Device QA report. The two
sentences below are fictional and are not assertions about a real person.
Do not upload personal notes or the owner journal to GitHub.

## Before starting

- Test in a separate disposable notebook or Android app profile if possible.
  **The DPKS owner journal is append-only:** after a manual review and its
  withdrawal, both events remain in its history. Merely deleting test Markdown
  will not erase those events. If this is your main library and you do not want
  test events in your permanent journal, stop after the read-only preview.
- Back up any existing knowledge journal before confirming a manual decision.
- Create the following *two separate* Markdown files in the selected notebook.
  No manual SQLite modifications are necessary. The date phrases intentionally
  use the supported controlled Russian grammar.

`DPKS-QA-Stage18/01-before.md`:

```markdown
# Stage 18 · Earlier reading
С 2024-01-01 по 2024-12-31 Алекс работает в Google.
```

`DPKS-QA-Stage18/02-after.md`:

```markdown
# Stage 18 · Later reading
С 2025-01-01 по 2025-12-31 Алекс не работает в Google.
```

## Device walkthrough

1. Open **Processes → Analysis and search → Analyze library** (in Russian,
   **Процессы → Анализ и поиск → Анализ библиотеки**) and wait until it completes.
   A mere file scan or quick Device QA does not rebuild all semantic candidates.
2. Open **Library**; search for `DPKS-QA-Stage18` to find the two files.
   Tap **Related claims — choose a note** and select `01-before.md`.
3. Expect a dated/separated relationship to `02-after.md` with opposite polarity.
   It is a *candidate for human review*, not an automatically accepted conflict
   or a verified real-world change. Open the relation and inspect both original
   excerpts and SHA statuses.
4. Tap **Review this date relationship**. Choose **Unresolved** and enter a
   short explanation such as `Synthetic Stage 18 device check`. Inspect the
   two-source preview. **Confirm only if test journal events are acceptable.**
5. Inspect the relation again and open **Decisions → Manual version reviews**.
   The active interpretation, explanation and exact journal decision ID should
   be visible. Navigate from the second note to verify symmetric provenance.
6. If you confirmed, switch off mobile data/Wi-Fi, open the same history entry,
   and **withdraw** it. The active entry must disappear, while both the review
   and withdrawal events remain in read-only history.
7. Optional source-freshness check: on a disposable copy, modify the second
   Markdown file *after* confirmation, without running semantic analysis.
   The old relation must become historical/unverified, not silently current.
   For stale-preview rejection, change a synthetic source between preview and
   confirmation; the confirmation must fail without a journal append.
8. Test Back, rotation, large font and TalkBack where available. Report any
   unreadable or unreachable confirmation, excerpt or history control.

After testing, the two synthetic Markdown files may be removed manually. Their
withdrawn journal events intentionally remain, by design. Do not reset the
app or erase a real notebook merely to remove test history.

## What to return

- App version, Android version and whether the **Library → Related claims**
  picker and **Processes → Go to device tests** shortcut are visible.
- Whether the dated pair, excerpts, SHA states, manual preview, history and
  withdrawal behaved as described; exact step number if not.
- A redacted quick QA summary (PASS/WARN/FAIL/SKIP) if run. Do not send source
  paths, note text, keys or unredacted app logs.

### Automation boundary

`tests/test_android_relations.py` separately imports these same two controlled
Russian statements as ordinary Markdown, then exercises the real relation,
manual preview, symmetric journal annotation and offline withdrawal. The
synthetic on-device `VERSION-18-SUITE` also passes independent checks, but
**neither automates touch, font, screen-reader or process-lifecycle acceptance**.

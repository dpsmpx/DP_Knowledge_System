# Stage 18 — acceptance and completeness gate

Stage 18 refines **unapproved cross-note interpretations**. It is not an
automatic fact resolver. A successful check means the index describes a
reviewable candidate accurately, not that the original assertion is true.

## Functional delivery

| Area | Implemented contract | Evidence / remaining gate |
|---|---|---|
| Context and exact identity | Strict predicate/arguments, context and precise interval; no absent-field wildcard | Python relation and whole-library audit regressions |
| Manual entity identity | Direct owner-approved alias, exact source fragments, dual live SHA, preview/journal-head guard and reversible withdrawal | Python tests; Android physical acceptance still required |
| Historical interpretation | Distinct overlap, separated full intervals, partly open uncertainty, modality contrasts and earlier/later polarity; not mixed with factual conflicts | Python temporal tests and EN/RU view labels |
| Cross-source evidence | Same/opposite/mixed polarity per file, copy-family hints, repeated-document hashes, capped excerpt batches and source navigation | Provenance and query-bound tests |
| Source lineage | Direct manually reviewed common origin, revocation, current/historical source state; no transitive independence inference | Journal and UI tests |
| Source provenance type | Human-selected primary/secondary/republication/unclassified; historical after source changes, no reliability score | Journal and UI tests |
| Manual version-pair review | Exact pair selection, two original excerpts and SHA, user explanation, human confirmation, symmetric review ID and offline withdrawal | New `version_review`/`version_unreview` tests; Android CI/device QA pending |
| Partial results | 100-relation response cap, shared 16-file live SHA budget, `truncated` true when SQL caps precede integrity filtering | 62-corrupt-row regression |
| Bridge compatibility | All Python response protocol IDs explicitly allowed by Java | Cross-language protocol-enumeration regression plus Android JVM CI |
| On-device synthetic version regression | Six count-only checks in `VERSION-18-SUITE` using app-private temporary Markdown/journal only | Python regression and unsigned Android CI; actual device execution pending |

## Mandatory physical Android sign-off — pending

This sign-off cannot be inferred from Python tests, a successful APK build or
GitHub Actions. Run with a **synthetic notebook** only; no private vault data
needs to be exported or uploaded.

1. On Android 15 and an Android 11 device, open **Library → Related claims**;
   review exact, opposite, modality, overlap, gap and partly open hints.
2. For two dated notes, open both original sources, record each manual version
   choice in turn, verify the saved explanation in Library and editor, withdraw
   it, and confirm old events remain in history. Verify withdrawal while the
   notebook is disconnected.
3. Modify one synthetic note after approval. The old decision must become
   *historical/unverified*, not silently current. A fresh confirmation with an
   obsolete preview token or journal head must fail without appending.
4. Interrupt preview and confirmation (back, rotation, process death); verify
   no incomplete/duplicate entry, note mutation or unintended approval.
5. Check 200% font, TalkBack reading order, scrollable excerpt comparison,
   minimum touch target and screen-reader labels on both languages.
6. Run the existing 20k synthetic fixture with source verification caps and
   inspect latency, memory, cancellation and completeness warnings.

## Explicit epistemic boundaries

- *Possible historical change* is an owner's interpretation of two statements,
  not an accepted historical fact or a repair of the knowledge base.
- Distinct notes are not necessarily independent sources. A primary source
  classification is descriptive, not a source quality or reliability metric.
- Numerical confidence calibration requires independently labeled truth and
  source-independence benchmarks. The current implementation **does not**
  manufacture confidence percentages or promote majority votes. This is a
  deliberate safety boundary, not a successful calibration test.
- Free-text synonymy/paraphrase equivalence is not automatically asserted.
  Manual aliases are explicit, direct and individually reversible.
- Actual device storage/SAF/Keystore, transfer and Stage 21 verification debts
  remain in their existing backlog; none are silently closed by Stage 18.

**Current sign-off state:** functional implementation and synthetic tests are
available; Stage 18 physical-device/accessibility/endurance acceptance remains
**OPEN** until owner-observed evidence is recorded. Do not mark a full
production release or merge the parent draft PR solely on green unit tests.

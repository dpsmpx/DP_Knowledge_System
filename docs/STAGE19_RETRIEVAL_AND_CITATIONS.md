# Stage 19 — hybrid evidence and citation checks (first engineering slice)

Status: **first engineering slice implemented and Android CI passed; physical
acceptance and broader Stage 19 work pending**.

This increment does not change the authoritative knowledge journal or any
original Markdown. A model response remains an **unverified interpretation**,
even if every reference and quotation passes the checks below.

## Delivered

1. Explicit user choice: lexical retrieval (no embedding request), or **hybrid
   lexical + semantic retrieval**. A semantic query still requires its own
   exact-question consent; the subsequent chat request still has its own
   exact-evidence consent. No background model calls or automatic authority.
2. Bounded reciprocal-rank fusion (RRF K=60) of up to 24 lexical and 24
   semantic hits. Identical source paths count once per input list. Sources
   occurring in both lists receive a higher rank. Ties are deterministic by
   path; at most six excerpts are selected for a chat request.
3. Before **and after** reading a file, each selected source must match the
   indexed modification time and byte count. This applies to lexical *and*
   semantic evidence. Stale/unreadable results are excluded; no automatic
   reread of uncertain old index entries or source modification.
4. For untrusted model output, validate **every** bracketed numeric reference
   against the exact 1..N sent source set. Detect out-of-range, zero or
   malformed numeric markers and show a warning. For `«...»` and `“...”`
   passages, require exact text to appear in the excerpt identified by the
   **nearby citation group in the same clause**. A reference elsewhere in the
   answer cannot verify an unrelated quotation; a mixed nearby `[1][2]`
   group additionally warns when one of the cited sources lacks the quoted
   words. Missing/misattributed quotes are flagged separately, with no claim
   that syntactic verification proves the underlying assertion. The answer
   retains its prominent unverified/not-a-fact banner.
5. Prompt previews now prefix every line of a Markdown excerpt (including
   empty lines) as untrusted source data. Apparent `--- END UNTRUSTED SOURCE ---`
   markers, fake `[2]` headers or instruction text within a note cannot mimic
   the **outer prompt structure**. The source path rejects control characters
   and Unicode line separators. This is delimiter hardening only, **not** a
   guarantee that a model will ignore malicious source instructions.

6. Fixed SQLite FTS4 multi-term OR syntax: prefix `*` belongs **inside**
   each quoted term. The previous expression could silently return zero
   matching notes. Synthetic `RECONCILE-16` now asserts actual multi-term
   evidence hits before and after a partial scan, while still excluding
   unverified source files. Android device re-run is pending.
7. Lexical evidence search now performs **two bounded, read-only FTS4 passes**:
   documents containing every query token first, then a de-duplicated OR
   fallback to fill remaining slots. Both passes exclude oversized and
   unverified index entries, with deterministic pathname tie-breaking; the
   original 24-result path-ordered OR search could crowd out every multi-term
   match behind earlier filenames. This improves literal token coverage but
   is not a language-aware relevance scorer.
8. Before any lexical **or hybrid** excerpt reaches the consent preview,
   hash its current decoded UTF-8 text with SHA-256 and compare it to the
   decoded text already stored in the disposable FTS4 index for that exact
   path, mtime, size and current scan generation. A same-length edit followed
   by restoring the original mtime now fails closed even if the cheap metadata
   check succeeds. Empty, oversized, absent or unverified index rows are
   refused; neither source Markdown nor the approval journal is changed.
9. Lexical FTS4 evidence now uses **three bounded passes** for queries with
   2–4 informative tokens: an adjacent phrase match first, then all-term
   matches, then OR fallback. Common English/Russian interrogative framing
   words are removed only if other searchable terms remain. Both explicit
   opposing statements are kept as sources; no conclusion is inferred. The
   path/scan-generation, oversized and SHA-256 gates remain in force. There
   is **no** automatic synonym expansion or semantic relevance claim.
10. Verbatim quote audit now uses a bounded, clause-local citation group
    rather than **all references anywhere** in the generated answer. A quote
    followed by an incorrect `[2]` is not laundered by a valid `[1]` in a
    later sentence. Adjacent mixed citations are also checked individually,
    and localized warnings identify the unsupported attribution. This is a
    deterministic syntax/provenance check, not semantic entailment. JVM and
    cache-free `CITE-LOCAL-19` synthetic device regressions are included.
11. Model-output citation coverage now counts **substantive prose spans**
    without a valid `[N]` in that same sentence or line. A single `[1]` in
    one sentence cannot silence the warning for a different uncited claim.
    Markdown fenced/inline code, quoted `[1]` labels, and `[1](url)` links do
    not count as source citations. The Android UI displays a localized warning
    with the count, while a new synthetic `CITE-COVER-19` device check and JVM
    regressions exercise the parser. This is a **conservative formatting
    heuristic** (minimum 16 letters/three words); it can miss short claims,
    skip headings/uncertainty, flag disclaimers and cannot establish that a
    cited sentence is actually entailed by its source. It does not block or
    modify the displayed model response or any journal decision.

## Reproducible synthetic retrieval benchmark

Run `python scripts/benchmark_stage19_retrieval.py --notes 20000 --repeats 5`.
The script creates **only an in-memory SQLite FTS4 database** with generated
text and known synthetic relevance labels. It reads no vault, uses no network,
and prints aggregate metrics, not source paths. Eight regression tests cover
FTS4 query syntax, phrase/strict/OR ordering, bounds, labeled recall,
opposing synthetic claims and the absence of unproven synonym expansion.

Updated Linux host measurement on 24 Nov 2026, SQLite **3.53.1**: 20,000
artificial notes, four adversarial two-word queries and four known relevant
documents per query. Early distractors contain both words **separated**,
while four late labeled files contain the adjacent phrase and include
explicitly opposing synthetic claims. Both the original path-ordered OR and
prior path-ordered two-pass baseline had **recall@24 = 0/4** for every query;
the three-pass variant had **recall@6 = 4/4**, **precision@6 = 4/6** and
**recall@24 = 4/4**. Query timing across 20 host trials was approximately
**p50 = 0.27 ms** and **p95 = 0.39 ms**, but varies by machine and build.
The deliberately adversarial corpus is not a
representative search evaluation, and this measures a Python SQL mirror,
**not** Android, embeddings, battery/RAM, provider quality or semantic
grounding. Device and broader human-labeled benchmarks remain open.

## Repeatable engineering acceptance

- JVM tests: RRF tie/duplicate/empty/bounds; changed source metadata;
  valid, fabricated, malformed and missing citation numbers; exact vs invented
  quotation, clause-local attribution, mixed valid/invalid source labels,
  uncited prose spans and fake citation markers in code/links/quoted text;
  untrusted instruction-like source content; forged delimiter,
  source-label and Unicode line-break injection. The synthetic reconciliation
  selftest now also asserts strict multi-token ranking ahead of an earlier
  partial-match pathname, correct indexed text, same-length changed text
  rejection, and exclusion of unverified source content. New JVM token/phrase
  syntax checks, Python label-based opposing-statement and synonym-negative
  tests, and Android synthetic `LEX-PHRASE-19` are included.
- Python suite: `python -m unittest discover -s tests -q`.
- Android CI: Java tests, standalone `com.dpsmpx.dpks` APK compilation and
  embedded core checks. No external LLM credentials, personal vault content,
  outbound model calls or real knowledge journal are used by tests.

## Deliberately still open

- RRF and the three-pass lexical priority are **relevance heuristics**, not
  learned rerankers or proof. Results are still pathname-ordered **within
  each pass**, with no synonym expansion. The adversarial FTS4 fixture does not close the
  wider `RET-15` gate: representative labeled precision/recall, synonyms,
  semantic model behavior and Android 20k latency remain unmeasured.
- Schema v4 additionally stores a **SHA-256 of the raw indexed Markdown bytes**
  in the *disposable* local index. Both lexical and hybrid LLM evidence paths
  now verify that digest against the **same bytes read for the excerpt**, as
  well as the existing FTS4 decoded text and pre/post mtime/size checks.
  Distinct malformed UTF-8 byte sequences that decode identically cannot
  authorize disclosure. A v1-v3 index is upgraded without modifying vault
  files; missing seals **fail closed** until synchronization verifies the
  original FTS text and backfills the digest, or reindexes changed content.
  Synthetic QA exercises the v3 upgrade, unverified scans and changed-byte
  refusal. Java regression tests exercise the malformed-UTF-8 edge case.
- Schema v5 additionally pins **each embedding checkpoint** to the source-byte
  SHA-256 already stored with the indexed note. Reuse, resume, counts and
  semantic retrieval require matching non-null hashes; both beginning an
  embedding and committing a returned vector batch reject a mismatched
  source revision. The UI re-reads and verifies the source **before and after
  each manually consented network embedding batch**, including edits that
  preserved file size and mtime. An isolated synthetic device QA step covers
  valid vectors, stale-batch rejection and exclusion of unsealed legacy rows.
  Existing v1-v4 vector rows remain on disk but are **not silently trusted**;
  users must explicitly approve generating replacement vectors. No model
  request or destructive migration is initiated automatically.
- Incremental sync still uses mtime/size to reuse *already sealed* rows and
  does not proactively hash every unchanged file on every scan. Thus external
  same-size/same-mtime edits can leave stale **search and embedding rankings**
  until a changed-metadata rescan or rebuild, although they cannot pass the
  raw-byte LLM disclosure gate or a new embedding upload. The local embedding
  digest protects **local revision association**; it cannot authenticate the
  external model's returned vector or prove semantic correctness. Independent
  provider attestation and representative semantic evaluation remain future
  work.
- An exact quote appearing somewhere in the cited excerpts does not prove
  the model's surrounding assertion. Unquoted hallucinations, prompt injection
  (including semantic manipulation *inside* delimited source lines), mixed
  citations and paraphrase entailment cannot be ruled out by syntax. A
  substantive-span citation warning is not a sentence classifier: it may miss
  unsupported claims or flag harmless explanatory text.
- No automatic rejection of the answer, fact promotion or journal change;
  users retain final judgment. Real provider, network/privacy, rotation,
  TalkBack and OEM/lifecycle QA remain unverified on physical devices.

Stage 19 is **partial**. The broader `RET-15` and `LLM-16` debts are not closed
until the benchmark and grounding/security gates pass.

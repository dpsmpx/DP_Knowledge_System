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
   passages, require exact text to appear in at least one referenced excerpt
   or display an additional quotation warning. The answer always retains its
   prominent unverified/not-a-fact banner.
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

## Reproducible synthetic retrieval benchmark

Run `python scripts/benchmark_stage19_retrieval.py --notes 20000 --repeats 5`.
The script creates **only an in-memory SQLite FTS4 database** with generated
text and known synthetic relevance labels. It reads no vault, uses no network,
and prints aggregate metrics, not source paths. Six regression tests exercise
the query syntax, FTS4 semantics, all-term priority, OR fallback, bounds and
label-based recall.

Example Linux host measurement on 24 Nov 2026, SQLite **3.53.1**: 20,000
artificial notes, four adversarial two-word queries and four known relevant
documents per query. Old path-ordered OR baseline: **recall@24 = 0/4** for
every query. Two-pass retrieval: **recall@6 = 4/4**, **precision@6 = 4/6**
and **recall@24 = 4/4** for each. Measured local query time across 20 runs:
approximately **p50 = 0.20 ms** and **p95 = 0.24 ms**; these timings vary by
machine and SQLite build. The deliberately adversarial corpus is not a
representative search evaluation, and this measures a Python SQL mirror,
**not** Android, embeddings, battery/RAM, provider quality or semantic
grounding. Device and broader human-labeled benchmarks remain open.

## Repeatable engineering acceptance

- JVM tests: RRF tie/duplicate/empty/bounds; changed source metadata;
  valid, fabricated, malformed and missing citation numbers; exact vs invented
  quotation; untrusted instruction-like source content; forged delimiter,
  source-label and Unicode line-break injection. The synthetic reconciliation
  selftest now also asserts strict multi-token ranking ahead of an earlier
  partial-match pathname, correct indexed text, same-length changed text
  rejection, and exclusion of unverified source content.
- Python suite: `python -m unittest discover -s tests -q`.
- Android CI: Java tests, standalone `com.dpsmpx.dpks` APK compilation and
  embedded core checks. No external LLM credentials, personal vault content,
  outbound model calls or real knowledge journal are used by tests.

## Deliberately still open

- RRF and the two-pass lexical priority are **relevance heuristics**, not
  learned rerankers or proof. Results are still pathname-ordered **within
  each pass**. The reproducible adversarial FTS4 fixture does not close the
  wider `RET-15` gate: representative labeled precision/recall, synonyms,
  semantic model behavior and Android 20k latency remain unmeasured.
- The new SHA-256 gate compares **decoded text** against the existing FTS4
  snapshot, not the original raw file bytes. Invalid UTF-8 byte variants can
  decode to identical replacement characters. There is no persistent raw-byte
  digest in the schema, nor a cryptographic seal for embeddings; an externally
  modified file with unchanged mtime/size can remain stale in the index until
  a rebuild, but cannot silently pass the LLM text-content comparison if its
  decoded text differs. End-to-end immutable source hashes are still future work.
- An exact quote appearing somewhere in the cited excerpts does not prove
  the model's surrounding assertion. Unquoted hallucinations, prompt injection
  (including semantic manipulation *inside* delimited source lines), mixed
  citations and paraphrase entailment cannot be ruled out by syntax.
- No automatic rejection of the answer, fact promotion or journal change;
  users retain final judgment. Real provider, network/privacy, rotation,
  TalkBack and OEM/lifecycle QA remain unverified on physical devices.

Stage 19 is **partial**. The broader `RET-15` and `LLM-16` debts are not closed
until the benchmark and grounding/security gates pass.

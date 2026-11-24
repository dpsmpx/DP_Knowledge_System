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

5. Fixed SQLite FTS4 multi-term OR syntax: prefix `*` belongs **inside**
   each quoted term. The previous expression could silently return zero
   matching notes. Synthetic `RECONCILE-16` now asserts actual multi-term
   evidence hits before and after a partial scan, while still excluding
   unverified source files. Android device re-run is pending.

## Repeatable engineering acceptance

- JVM tests: RRF tie/duplicate/empty/bounds; changed source metadata;
  valid, fabricated, malformed and missing citation numbers; exact vs invented
  quotation; untrusted instruction-like source content; forged delimiter,
  source-label and Unicode line-break injection.
- Python suite: `python -m unittest discover -s tests -q`.
- Android CI: Java tests, standalone `com.dpsmpx.dpks` APK compilation and
  embedded core checks. No external LLM credentials, personal vault content,
  outbound model calls or real knowledge journal are used by tests.

## Deliberately still open

- RRF is a **relevance heuristic**, not a learned reranker or proof. The
  lexical FTS shortlist is path-ordered, so a calibrated 20k corpus benchmark
  with measured recall, precision and latency remains Stage 19 work.
- File modification time and size do **not** prove a cryptographic match to
  the indexed bytes. A same-size rewrite with deliberately restored mtime can
  escape this check; immutable indexed content hashes remain future work.
- An exact quote appearing somewhere in the cited excerpts does not prove
  the model's surrounding assertion. Unquoted hallucinations, prompt injection
  (including semantic manipulation *inside* delimited source lines), mixed
  citations and paraphrase entailment cannot be ruled out by syntax.
- No automatic rejection of the answer, fact promotion or journal change;
  users retain final judgment. Real provider, network/privacy, rotation,
  TalkBack and OEM/lifecycle QA remain unverified on physical devices.

Stage 19 is **partial**. The broader `RET-15` and `LLM-16` debts are not closed
until the benchmark and grounding/security gates pass.

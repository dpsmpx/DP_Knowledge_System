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
12. Exact chat consent now binds the **original previewed source bytes** of
    every lexical or hybrid excerpt to an ephemeral in-memory SHA-256 seal,
    indexed mtime/size and scan generation. Immediately before the approved
    network chat request, all selected files are re-read and compared with
    both those original seals and the disposable index's raw-byte provenance.
    A changed, missing, oversized or unverified source cancels the request
    with a localized "not sent" message; the app never silently substitutes
    new excerpts or reuses the old consent. Synthetic `CITE-SEND-19` device QA
    exercises a same-byte-count rewrite with restored mtime, and JVM tests
    verify that the original approval seal rejects the new bytes. This closes
    the *known dialog-delay exposure* but does not make file reads and socket
    writes an atomic filesystem transaction; final race/lifecycle behavior
    still requires physical QA.
13. The pre-existing **20k synthetic device QA** now also checks end-to-end
    *Android* FTS4 retrieval quality on an intentionally adversarial fixture:
    48 earlier separate-token distractors and four late adjacent-phrase
    sources with mutually opposing synthetic statements. After indexing it
    asserts deterministic top-four source order within a six-hit list and
    reports recall@6 = 4/4 and precision@6 = 4/6 as fixed-label assertions.
    It warms the query twice, then reports microsecond p50/p95 over 21 local
    executions, without a hard speed SLA. This is only **instrumentation**:
    the updated 20k button has not yet run on a physical Android device.
    No real vault, provider, model or network is involved in the fixture.

14. Fail closed on **explicitly incomplete model output**. The chat adapter
    now rejects response `finish_reason` values other than `stop` (while
    permitting older compatible providers that omit the field), and refuses
    responses exceeding the 12,000-character UI audit bound instead of
    silently truncating the text or losing trailing citations. The UI shows
    a localized incomplete-response warning; no result is presented as a
    finished answer and no retry/resend is automatic. Pure JVM tests and a
    cache-free `CITE-RESPONSE-19` synthetic device QA step exercise this
    with fabricated JSON-independent response values. This only catches
    **reported** interruption and local length overflow: a provider falsely
    reporting `stop` cannot be independently authenticated, and malformed
    or empty responses still follow the existing generic error path.
15. The **same original source seals** are checked again *after* a completed
    provider response and *before* displaying the answer. A source that was
    valid at send time may have changed or become unavailable while the
    network request was running. In that case the answer remains an explicitly
    unverified interpretation, but a prominent EN/RU warning says its source
    links refer to the **previously sent excerpts**, not necessarily the
    current vault. It never silently re-labels the answer as current evidence,
    automatically resends the question, or changes Markdown/journal entries.
    This second local check uses the exact same raw-byte seals, mtime/size,
    scan generation and index provenance as the pre-send gate. It does not
    give atomic post-display freshness or semantic truth; on-device
    concurrency/lifecycle behavior remains a separate QA requirement.
16. Limited common **English and Russian question framing** is now discarded
    before constructing the bounded FTS4 phrase/all-term/OR query: for example,
    "Tell me about amber violet" and "Расскажи, что известно про память
    синтез?" select the same local evidence ranking as their corresponding
    bare two-term queries. A query consisting only of discarded framing words
    falls back to its original sanitized tokens; the app never invents
    synonyms, changes the source Markdown or infers either side of a conflict.
    Pure JVM and Python fixture assertions cover this, and the cache-only
    `LEX-PHRASE-19` device QA fixture now includes *both* language variants
    with opposing synthetic claims. The updated device fixture has not yet
    been run on a physical phone.
17. The **excerpt** cut from a long lexical source now uses the *same* bounded
    question terms as FTS4. For 2–4 informative terms it prefers their
    adjacent token sequence (including ordinary punctuation and prefix
    suffixes) before falling back to the earliest individual token. A long
    note mentioning one query word near the beginning no longer hides its
    later complete matching phrase outside the 900-character consent preview.
    Token boundaries also prevent a substring within an unrelated word from
    moving the preview window. This applies to the lexical side of hybrid
    retrieval; semantic results still use their indexed chunk offsets. New
    English/Russian JVM regressions and cache-free `CITE-EXCERPT-19` device
    QA use artificial long notes; the new device step is not yet physically
    verified. The excerpt is still a heuristic, not proof that the complete
    note supports the model's answer, and user consent remains mandatory.
18. For longer **5–8-term questions** (where FTS4 intentionally does not use
    an adjacent-phrase pass), or when a short phrase is absent, the excerpt
    selector now prefers a bounded window containing the most **distinct**
    query terms, rather than an unrelated earliest single word. It examines
    the verified note text once, retains at most eight recent term positions,
    counts a source token only once even if it matches overlapping prefixes,
    and favors a denser then earlier window on ties. The 900-character window
    retains 180 characters of leading context and at least 80 after the last
    matched term. A true adjacent 2–4-term phrase still takes precedence.
    English/JVM regressions cover long and nonadjacent queries, tie density
    and overlapping prefixes; `CITE-EXCERPT-19` also tests a synthetic
    multi-term question in memory. This is **local lexical coverage**, not
    entailment or a guarantee that every relevant part of a long note fits
    in one excerpt. Physical Android QA remains pending.
19. A **token-adjacent FTS4 phrase may still be far apart in source bytes**:
    for example, two terms separated by over 900 punctuation characters.
    Phrase-based excerpt selection now rejects spans beyond its bounded
    900-character preview window (including reserved context), then uses
    ordinary coverage selection or the isolated-token fallback. A late compact
    phrase can no longer lose to an early unshowable one. Before network
    consent, the user sees a count of the distinct question terms actually
    present in the **exact selected excerpts**, ignoring filenames and
    word-internal substrings. Missing terms produce an explicit warning both
    before sending and above the returned answer, without silently changing
    the approved payload or refusing a potentially valid semantic result.
    This is a *lexical visibility diagnostic*, not a measure of retrieval
    recall, source trust, contradiction handling or logical entailment.
    EN/RU UI text, JVM tests and synthetic `CITE-EXCERPT-19` assertions cover
    the change; physical acceptance remains outstanding.
20. The **lexical-only LLM evidence** path can inspect up to twelve already
    ranked, hash-verified local excerpts before choosing the six to display
    for exact user consent. It keeps the top two ranked notes (including
    contradictory phrase matches), then greedily favors additional DISTINCT
    question terms with original retrieval rank breaking ties. The selected
    excerpts are shown in their original ranked order, and only their original
    matched raw-byte consent seals proceed to the network gate. Hybrid RRF
    ordering is deliberately unchanged: semantic similarity cannot be
    fairly represented by literal term counts. This doubles the worst-case
    local source reads during lexical preview preparation (6 to 12); no
    extra network calls, automatic consent, note writes or journal promotion
    are introduced. Tests use synthetic opposing/duplicate/complementary
    excerpts, not real vault data. The heuristic may still miss a relevant
    source outside the bounded candidate pool and does not establish
    entailment, accuracy, or representative recall/precision.
21. Citation inspection now warns when a substantive attributed claim shares
    **fewer than two distinct 4+ character words** with its individual cited
    excerpt, and separately flags the narrow, explicit `true` vs `false`
    polarity mismatch. It also checks the six supplied excerpts for pairs
    sharing two content words but expressing opposite unambiguous boolean
    labels. Code fences, inline code and Markdown link labels retain their
    existing citation-masking rules; warning counts are local and do not
    transmit the answer or private excerpts elsewhere. JVM and isolated
    `CITE-ANCHOR-19` device regressions include correct citations,
    misattribution and opposing fabricated statements. These checks are
    **not semantic entailment or general contradiction detection**:
    synonyms, negation beyond the explicit labels, modal verbs, multiple
    claims in a source, and valid paraphrases remain open. A warning never
    auto-rejects an answer or changes an authoritative journal decision.
22. Hybrid LLM evidence now examines at most twelve **already byte-verified**
    RRF candidates before choosing six. The first two ranked sources remain
    intact; if neither selected source represents a retrieval channel, the
    next available verified source from that channel is included. Remaining
    slots follow the original fused order and final citation indices preserve
    that order. This avoids accidental loss of a semantic-only or lexical-only
    representative while retaining original SHA-256 approval seals. Local
    reading may double (6 to 12 notes), but there are no extra provider
    requests or automatic consent. JVM and isolated `HYBRID-BALANCE-19`
    synthetic checks exercise modality loss and rank preservation; genuine
    semantic recall/precision and device latency/energy remain unevaluated.
23. To make the previous claim-to-source counts actionable, the answer dialog
    now lists up to **eight suspect model-answer spans**, each with its exact
    numbered source and a single-line snippet capped at 140 characters.
    Possible weak text overlap and explicit true/false disagreement are
    distinguished; if more than eight flags exist, the hidden count is shown.
    This is only an ephemeral, immutable audit snapshot of the model answer.
    It does **not** copy private Markdown source text into a new report,
    journal or log, change the original answer, automatically assert truth,
    or send another network request. JVM and `CITE-ANCHOR-19` synthetic
    checks cover offsets, numbering, truncation and caps. Actual accessibility
    and readability in a long answer remain open under QA-76.
24. The citation syntax audit now excludes escaped `\[1]` markers, Markdown
    reference-style links (`[1][label]` and `[1][]`), link definitions
    (`[1]: URL`), footnote labels and tab/four-column-indented code (including
    mixed whitespace). This
    preserves the supported adjacent numeric source format `[1][2]` and
    avoids treating sample Markdown as evidence. The audit also rejects source
    excerpts above the existing **900-character** evidence cap and tokenizes
    each bounded source only once for repeated claim-overlap and boolean
    polarity checks; answers retain the existing **12,000-character** limit.
    Synthetic JVM tests cover these syntax boundaries, 340 repeated citations
    and oversized source rejection. No semantic truth check, journal write,
    provider call or real-device latency measurement is implied.
25. Hidden Markdown surfaces can no longer supply citation numbers or verbatim
    quotations: multi-line HTML comments (including an unclosed comment),
    reference-link definition targets, `<https://...>` autolinks, and
    parenthesized inline-link destinations (including nested or escaped
    parentheses) are masked without changing answer offsets. The visible
    `[1](url)` label remains a link, while an independent `[1]` outside the
    link remains a real source marker. Regressions cover misleading `[2]`
    inside a link with only one approved source, quote-looking URL components,
    and 90 repeated mixed lines under the existing 12k answer bound. This
    improves **syntax** and false-positive resistance only; source truth,
    paraphrase entailment and real device behavior remain unverified.
26. Sentence boundaries now keep trailing source clusters attached to their
    **preceding** claim: in `Statement A. [1] Statement B.`, `[1]` cannot
    launder the next uncited sentence. Adjacent and comma-separated clusters
    retain their source numbering and offsets; a subsequent independently
    cited sentence is checked against its own source rather than against both
    clauses combined. ATX headings with one through six hashes are excluded
    consistently from prose-attribution warnings (seven hashes are ordinary
    Markdown text). The code/quote/citation audit also recognizes CR, CRLF,
    Unicode NEL, line separator and paragraph separator as distinct line
    boundaries, preserving those characters when masking hidden Markdown.
    Synthetic JVM tests cover both genuine and fabricated claims across these
    boundaries. These remain conservative structural diagnostics, **not**
    factuality or entailment detection, and Android OEM/lifecycle QA remains
    open.
27. Claim-to-excerpt warnings now distinguish **informative anchor terms**
    from a small, fixed English/Russian set of generic connective words.
    The two-shared-anchor heuristic also warns if a long (8+ distinct anchor)
    claim overlaps on fewer than a quarter of its terms. Opposing-source
    boolean pair hints require two shared informative terms rather than
    agreement on generic words. A new, separately localized **possible
    number mismatch** warning appears only if an attributed claim and its
    excerpt share at least two distinctive terms, both contain explicit
    unsigned written numbers, and at least one number in the attributed claim
    is **absent from the cited excerpt**. A correctly shared number no longer
    hides another altered number. Model citation numbers `[1]` are excluded; simple decimal
    comma/dot and trailing zeros normalize deterministically. The UI lists
    per-source suspect model-answer snippets under the existing eight-detail
    cap; it does not quote private source contents or alter any journal state.
    These are deliberately weak heuristics: inflection, synonyms, arithmetic,
    units, dates and number formatting can lead to false positives or missed
    warnings. They do not verify semantic entailment, computed quantities or
    truth; all decisions stay with the user.
28. Lexical selection now uses an **exact-excerpt diversity tie-break** after
    preserving the top two already byte-verified sources. When two remaining
    candidates add the same question-term coverage, prefer the one whose
    excerpt is not text-identical to any chosen source; preserve the original
    rank on further ties, sort final source indices back into their original
    order, and fill from duplicates if no alternatives remain. This does not
    treat paraphrases as identical or change SHA-256 consent seals, the 12-note
    local read cap, network consent or authoritative knowledge. A public
    evidence query above the existing **1,200-character UI limit** now fails
    closed before tokenization; the offline SQLite benchmark mirror enforces
    the same bound. JVM/host regressions cover exact duplicates, preservation
    of the top two, stable numbering, the duplicate-only fallback and both
    sides of the question-length boundary. Broad relevance and device latency
    remain unmeasured.
29. **Bounded title-aware tie-break within each lexical FTS4 pass**: retain
    exact phrase before ALL-term before partial OR results, but score at most
    `2 × limit` path-first candidates per pass for distinct query-token
    prefixes at complete word boundaries in the first 256 UTF-16 title units.
    Promote candidates with more title matches; equal scores preserve the
    original SQLite `NOCASE`/binary path order. A single title word cannot
    satisfy two query terms, and truncated title words earn no credit.
    Metadata-only scoring reads no additional note content and makes no model
    request, consent change, or journal update. For the normal 24-result
    evidence shortlist, no more than 48 rows per pass (144 across three)
    are examined; the API maximum of 40 caps that at 240 rows total.
    An important limitation is that relevant notes *outside* those bounded
    windows still cannot be promoted. Newly added JVM, Python and synthetic
    English/Russian Android QA cases check token boundaries, stable ties,
    phrase precedence and within-pass promotion, including rejected
    oversized/stale notes from pre-existing regressions.
30. Citation and quotation checks now also mask **HTML tag syntax and
    attributes**, plus the non-rendered bodies of `<script>`, `<style>` and
    `<template>` elements. Thus a fabricated `[1]` or `«quote»` inside a
    `title`/`data-*` attribute, hidden script or CSS cannot silently serve
    as a citation, whereas a marker in visible content between ordinary
    `<span>` tags remains checkable. The scanner respects `>` inside quoted
    attributes, preserves original offsets and all line breaks, bounds
    individual tag scans to 1,024 characters and uses the existing 12,000-
    character answer cap. A missing raw-section close conservatively masks
    the remainder, just like an unclosed comment or code fence. JVM tests
    include uppercase closing tags, multiline attributes, numeric markers,
    quotation strings, literal `<` comparisons and repeated mixed HTML;
    `CITE-COVER-19` synthetic Android QA now asserts hidden-versus-visible
    attribution. This is a **bounded Markdown/HTML heuristic**, not a full
    HTML parser or a proof that the model answer is factual.

31. Citation markers that **look numeric but use non-ASCII Unicode digits**
    are now counted as invalid references instead of being silently skipped.
    Only canonical ASCII `[1]` through `[N]` may refer to the exact previewed
    source set; examples `[１]` (full-width), `[١]` (Arabic-Indic) and `[𝟙]`
    (supplementary mathematical digit) generate the existing invalid-citation
    warning. The bounded scanner iterates Unicode code points, so surrogate
    pairs cannot evade the check. Adjacent or space-separated numeric
    clusters are distinguished from genuine Markdown reference links such
    as `[1][bibliography]`. Markdown link labels, inline code and masked
    HTML attributes still cannot create false references. JVM tests
    and cache-only `CITE-LOCAL-19` synthetic Android QA assert both the
    warning counts and nonverification of quotes attributed only to such
    markers. This enforces source *label syntax*, not factual grounding.
32. The hybrid chat preview now **chooses an evidence excerpt for sources
    returned by both lexical and semantic retrieval**, instead of always
    showing the vector chunk. It compares the two already bounded excerpts
    cut from the **same byte-verified note**: use the lexical excerpt when
    it visibly contains more distinct sanitized question terms, or when both
    contain the same number but only the lexical one displays the adjacent
    2–4-term phrase. Otherwise keep the vector passage and its ranking.
    Semantic-only sources are unchanged. Source ordering, the 12-note read
    cap, six-source chat cap, original raw-byte consent seals and pre-/post-
    source verification stay intact; no second file read or model call occurs.
    An invalid/oversized question or excerpt is refused by the bounded
    selector. JVM regressions exercise both preference directions, equal
    scores, Cyrillic morphology, overlapping query prefixes and length
    gates; cache-only `HYBRID-BALANCE-19` Android QA exercises two passage
    candidates from one artificial Markdown note. This is a local visibility
    heuristic, not a learned cross-encoder or semantic support proof.
33. The citation audit no longer misreads **soft-wrapped or whitespace-separated
    Markdown reference links** such as `[1]  [bibliography]`, `[1]\t[reference]`
    or `[1]` followed by a single line break and `[bibliography]` as an approved
    citation. It bounds the look-ahead to 64 characters and never crosses a
    blank line; escaped opening brackets in the following label do not make
    reference links, and numeric citation groups like `[1] [2]` (including
    malformed Unicode digit labels) retain their existing audit path. **Escaped
    backticks** are also ignored when opening or closing inline code spans:
    a visible `[1]` next to a literal escaped backtick no longer disappears into a false
    code span, while a source marker inside genuine code remains masked.
    JVM cases and the cache-only `CITE-COVER-19` Android fixture exercise
    these two distinct Markdown edges without provider calls, private notes
    or changes to consent. This is conservative bounded syntax parsing, not
    a complete CommonMark renderer or semantic grounding.
34. The existing **possible number mismatch** warning now examines *every*
    explicitly written number in a cited claim (after the current decimal
    normalization), instead of warning only when the claim and excerpt have
    zero numeric overlap. In a synthetic statement claiming `60` minutes and
    `42` degrees from an excerpt containing `60` minutes and `24` degrees,
    the altered `42` is now flagged; numbers present in the source may still
    be mentioned selectively. Existing lexical overlap, source-size, answer
    bounds, citation-number exclusion, eight-warning-detail cap and explicit
    per-request consent are unchanged. JVM and cache-only `CITE-ANCHOR-19`
    checks cover both partial overlap and compatible subsets. This is only
    a local written-number discrepancy heuristic, not unit conversion,
    arithmetic verification or a semantic truth judgment.
35. **Contrastive clauses cannot borrow the other clause's source marker.**
    Within a single Markdown answer line, a comma followed by the complete
    English `but`/`however` or Russian `но`/`однако` connector starts a new
    local claim span. Thus `Statement A [1], but unsupported statement B`
    receives an uncited-claim warning; a quote before `, но` cannot be
    attributed to a `[1]` appearing only after the contrasting claim.
    Source markers explicitly supplied for both clauses continue to count.
    The check uses a bounded 32-character whitespace look-ahead and complete
    word boundaries, leaving ordinary comma lists, `butter` and unrelated
    conjunctions unchanged. JVM table cases cover EN/RU variants, correctly
    cited contrasts, ordinary commas and quotation provenance. Cache-only
    `CITE-LOCAL-19`/`CITE-COVER-19` device checks mirror both failure modes.
    It does **not** parse every clause, infer truth or reject an answer; it
    extends only the existing conservative source-format warnings.
36. **RRF scoring now uses each unique source's rank**, not the raw position
    inflated by earlier repeated rows or rejected empty/null paths. Previously,
    a duplicate was correctly prevented from casting a second vote but still
    pushed later unique sources down the rank denominator; a stable path-order
    tie could flip. Inserting or removing duplicates now leaves the fused
    ranking unchanged. The existing K=60, 40-entry input gates, 24-hit output
    bound, deterministic tie-break, six-source approval and byte-verification
    rules are untouched. An independent **five-entry-per-channel artificial
    ranking fixture** labels two shared opposing sources, one lexical-only
    and one semantic-only source as relevant. Its union has **recall@6 = 4/4**
    and **precision@6 = 4/6**, versus 3/4 labeled recall for either separate
    list. JVM cases assert repeated/invalid-row invariance; cache-only
    `HYBRID-BALANCE-19` QA asserts the same properties and top-two retention.
    These are hand-crafted source rankings, **not actual embeddings, a model
    comparison, semantic relevance labels or representative RET-15 evidence**.
37. **Android hybrid QA now exercises the actual SQLite retrieval pipeline**
    on six throwaway Markdown notes in the app cache. After scanning the
    synthetic vault, it commits six source-SHA-256-bound, 8-dimensional
    **hand-assigned** vectors through the production embedding checkpoint and
    LSH posting-list writer. `HYBRID-INDEX-19` retrieves both real FTS4 and
    LSH/cosine candidate lists, fuses them with production RRF, and selects
    byte-verified preview paths through the production balance heuristic.
    The fixture contains two shared *opposing* artificial statements, one
    lexical-only source, one vector-only source, a partial-word distractor,
    and a sixth source whose bytes are deliberately changed **without
    changing its size or mtime** after indexing. The latter must be rejected
    by the raw-byte disclosure gate despite still appearing in stale index
    rankings. Its predetermined four-source set must achieve **verified
    recall@4 = 4/4** after that exclusion. Semantic query latency is logged
    as **p50/p95 in microseconds across 21 measured calls**, following two
    warmups, without a timing pass/fail threshold; this is a tiny SQLite
    integration fixture, not the 20k stress corpus. The QA runs in quick and
    stress modes without network requests, provider model credentials, or
    access to the selected real vault. Artificial equal/opposite vectors
    **do not measure embedding quality, real semantic recall, energy use or
    performance on a physical device**; `RET-15` remains open.
38. **A second host-only 20k benchmark compares FTS4, approximate LSH/cosine
    and RRF on the *same* synthetic notes.** The Java 4-table/10-bit/24-sample
    hash, 44 one-bit probes, 45k candidate limit, complete-note/model/generation
    gates and K=60 unique-source RRF are mirrored with 8-dimensional
    **manually assigned one-hot vectors**. A cross-language golden signature
    runs in JVM and Python regression tests. Five artificial topics have
    four labeled sources each (including opposing statements) and two
    deliberately mislabeled vector hits. Two extra cases expose both a
    *potential* gain (the synthetic vector maps `sofa` to a note containing
    `couch` where FTS4 has zero recall) and a serious risk (a no-relevant-
    document query returns two false vector positives). Labels, not model
    semantics, decide the result. SQL tests also exclude obsolete scan
    generations, oversized notes, incomplete checkpoints and wrong models.
    This host schema has one chunk per note and does **not** mirror the
    Android raw-byte seal: `HYBRID-INDEX-19` tests that separately. The
    benchmark logs distinct lexical, LSH/cosine and fusion p50/p95 timings
    but cannot establish on-device performance, real synonym quality,
    provider reliability or the broader `RET-15` acceptance gate.
39. **Multi-chunk and candidate-cap regressions are now explicit.** The
    isolated Android `EMBED-CHUNKS-19` QA inserts two chunks through the
    production checkpoint path: one weaker but LSH-visible vector and one
    later exact-match vector. A partially written embedding must produce
    **no** results; once both vectors are committed, the note must appear
    exactly once, with the better later excerpt offset and a valid source
    byte seal. A separate host SQL probe generates **360 artificial notes ×
    128 chunks = 46,080** chunk vectors (184,320 LSH posting rows). Under
    the original 45,000-*chunk* query
    cap and an observed path-first SQLite grouping order, an artificial
    exact-match note near the end **is absent**, although it ranks first
    when the cap is lifted in the diagnostic comparison. This exposes a
    bounded-retrieval recall risk: 45k chunks are **not** 45k distinct notes,
    and grouping without an explicit sort does not guarantee stable source
    diversity. The host SQL mirror now correctly deduplicates by note and
    retains only its best cosine score. This baseline is preserved as an
    adversarial comparison for the adaptive candidate policy below.
    An additional deterministic angular-noise probe rotates a fabricated
    8D vector from cosine 1.00 to 0.80 against the query: exact/one-bit
    LSH probes cover four, two, one, two and finally **zero** tables,
    respectively. The non-monotonic bucket count and total dropout at
    still-positive cosine 0.80 show why approximate candidate retrieval
    cannot promise nearest-neighbor recall, even before RRF ranking.
40. **Adaptive exact-bucket-first retrieval mitigates cap starvation without
    penalizing the common small index.** Before searching, Android uses a
    read-only `COUNT(*)` of its disposable vector chunks. When the *whole*
    index has at most 45,000 chunks, it keeps the original single 44-bucket
    SQL: the cap cannot truncate a complete candidate set. Otherwise it
    queries the **four exact signature buckets first**, then uses the
    remaining portion of the same 45,000-chunk budget to query the **40
    one-bit neighbor buckets**. Java keeps the best cosine hit per document
    across both passes and the original deterministic final path tie-break;
    source SHA-256, scan generation, model and completed-checkpoint gates
    remain in *both* SQL queries. A package-private budget override is
    restricted to isolated `dpks-device-qa-*` SQLite fixtures, allowing
    `EMBED-CHUNKS-19` to verify that the later exact-match chunk wins even
    under a **one-chunk test budget**. The 46,080-chunk host counterexample
    now recovers the late exact source within 45k while the historical
    single-pass SQL still loses it. No global vector scan, expensive SQL
    `ORDER BY` on millions of chunks, cap increase, model call or vault write
    is added. This is a **best-effort approximation, not source fairness or
    nearest-neighbor completeness**: many exact-bucket chunks can still
    monopolize the budget. Device RAM/latency and a broader human-labeled
    retrieval benchmark remain outstanding.
41. **Do not spend overloaded LSH candidate budget on the same chunk twice.**
    A vector can match an exact signature in one table and a one-bit neighbor
    in another. The original two-pass query counted it once in each pass,
    potentially using the final candidate slot without inspecting a different
    neighboring document. The overloaded-only second query now excludes
    `(path, chunk)` pairs that have *any* of the four exact-bucket postings,
    using a correlated `NOT EXISTS` lookup against the existing
    `(bucket, path, chunk)` SQLite primary key. The total bound stays **45k
    distinct inspected chunks**; no index migration, full vector scan or
    model call is introduced. The unchanged fast path runs one SQL query for
    indices at or below the cap. A two-slot SQL regression reproduces the
    dropped neighbor before the exclusion, and `EMBED-OVERLAP-19` exercises
    the same three-note, float32-safe fixture in a disposable Android QA
    index, including SHA-256 source checks. The JVM LSH test locks in the
    bucket-overlap geometry, while the Python mirror validates query behavior.
    **This only eliminates cross-pass duplication**: exact buckets may still
    consume the entire cap, and a zero-collision true neighbor remains
    unreachable. Device/OEM multi-chunk latency and broader human-labeled
    retrieval quality remain acceptance debts.
42. **Exact-bucket saturation is now measured before selecting a fairer
    production policy.** `scripts/benchmark_stage19_lsh_fairness.py` creates a
    distinct, in-memory corpus in which 359 sources each have 128 weaker but
    exact-bucket-colliding chunks and a late 360th source contains the perfect
    query vector. At 45k candidates, the current exact-first policy still
    misses it (352/360 sources visited on this host SQLite build). The
    comparison measures one-chunk-per-source grouping, chunk-number ordering,
    and equal exact-bucket quotas with the *same* raw 45k ceiling. A second
    scenario gives the late source an earlier weaker exact-bucket chunk:
    grouping once per source then fails to retain the better later chunk,
    whereas chunk-number ordering recovers it. Equal bucket quotas reach the
    target but see only 89/360 sources in this fixture, so an apparent speed
    improvement is not a fair comparison. These alternative SQL queries are
    **host-only experiments**; production Android SQL is unchanged, pending
    evidence about representative relevance, available memory and device
    p50/p95 under overloaded 20k-note multi-chunk indexing.
43. **Index-ordered four-way merge removes global SQLite sorting in a host
    experiment, but is not yet a production win.** Four separate exact-bucket
    cursors use an additional disposable `(bucket,chunk,path)` covering index
    and `ORDER BY chunk,path`, then a four-element heap merges the streams,
    deduplicating cross-table chunks. The 90×16 regression verifies that its
    bounded candidate sequence exactly matches the expensive globally sorted
    SQL, and `EXPLAIN QUERY PLAN` shows index traversal with no temporary
    `ORDER BY` B-tree. The 360×128 host probe retrieves the late best source
    in all three adversarial cases while inspecting no more than 45k unique
    chunks. Yet observed lookup p50 was around 104–105 ms, comparable to the
    global sort, and the auxiliary index consumed approximately **5,968 KiB**
    for only 46,080 chunks, with another ~75–83 ms to build in memory. Index
    maintenance during writes, migration and the much larger 20k-note storage
    envelope have not been measured on Android. **Do not add this index or
    merge path to the shipping app without device and model-quality evidence.**
44. **Source sampling across four exact buckets is insufficient as a general
    replacement.** One candidate per source *per bucket* visits all 360
    sources and recovers a later perfect vector when it is visible in another
    exact bucket, with only 360–363 raw rows. A third synthetic case changes
    the later vector to cosine 0.999 so it shares the same sole exact bucket
    as an earlier cosine-0.99 chunk. Source-sampling and fixed bucket quotas
    then lose the relevant late vector despite the small SQL time; the
    interleaved strategies still recover it. This exposes a concrete recall
    cost from arbitrary `GROUP BY path` selections and is now regression
    tested without model or device calls.

## Reproducible synthetic retrieval benchmark

Run `python scripts/benchmark_stage19_retrieval.py --notes 20000 --repeats 5`.
The script creates **only in-memory SQLite FTS4 databases** with generated
text and known synthetic relevance labels. It reads no vault, uses no network,
and prints aggregate metrics, not source paths. The regression tests cover
FTS4 query syntax, phrase/strict/OR ordering, bounds, labeled recall,
opposing synthetic claims, exclusion of oversized/stale index rows, stable
casefold tie-breaking, title-aware bounded promotion and the absence of
unproven synonym expansion.

Updated Linux host measurement on 24 Nov 2026, SQLite **3.53.1**: 20,000
artificial notes, five adversarial two-word queries (including one Cyrillic
pair) and four known relevant documents per query. Early distractors contain
both words **separated**, while four late labeled files contain the adjacent
phrase and include explicitly opposing synthetic claims. Both the original
path-ordered OR and prior path-ordered two-pass baseline had **recall@24 =
0/4** for every bare query; the three-pass variant had **recall@6 = 4/4**,
**precision@6 = 4/6** and **recall@24 = 4/4** for all five. Five corresponding
natural English/Russian question forms produced identical shortlists and
the same labeled metrics. Query timing across 25 host trials was approximately
**p50 = 0.23 ms** and **p95 = 0.28 ms**, but varies by machine and build.
With the title tie-break on 25 Nov 2026, the **same five synthetic topics**
still achieved recall@6 = 4/4, precision@6 = 4/6, recall@24 = 4/4 and
unchanged natural-question shortlists. On the same Linux host the updated
SQL + metadata scoring mirror measured **p50 = 0.456 ms**, **p95 = 0.625 ms**
over 25 trials. This is a separate host run, not a paired device performance
comparison. The corpus has generic titles, so its unchanged recall does
**not** establish representative improvement: dedicated adversarial fixtures
demonstrate promotion of a matching title among scanned candidates instead.
The standalone 10-note probe reported that the previous two-pass baseline
missed a later titled source, while the new windowed ranking promoted it to
**rank 3**, behind both opposing exact-phrase sources (eight scanned
candidates per pass); only boolean/count summaries are printed.
The new **14-note, eight-query diverse synthetic probe** separately tests
English phrases and natural wording, Russian inflected prefixes, title matches,
five-term questions, overlapping prefixes, a missing synonym and a query
with **zero** relevant documents. Its six positive lexical cases recover all
of their manually labeled targets at six results, but the synonym-only case
still has **recall@6 = 0/1**, and overlapping prefixes yield **five
false-positive results among six**. For the no-relevant-document case recall
is reported as `null`, rather than falsely treating it as perfect recall.
The fixture therefore makes known lexical limitations observable instead
of implying that five two-word topic scores represent broad relevance.
The deliberately adversarial corpus is not a
representative search evaluation, and this measures a Python SQL mirror,
**not** Android, embeddings, battery/RAM, provider quality or semantic
grounding. Device and broader human-labeled benchmarks remain open.

### Experimental host hybrid/LSH benchmark (artificial vectors only)

Run `python scripts/benchmark_stage19_hybrid.py --notes 20000 --repeats 5`.
Both the lexical and approximate vector channels query one in-memory SQLite
fixture, followed by source-level RRF fusion. Every query is warmed up twice;
the report contains only per-case counts and aggregate timing, not file paths.
The synthetic vector assignments are intentionally transparent and
unrealistic, so reported "semantic" recall is **fixture plumbing recall**,
**not** learned relevance.

On the Linux host on 26 Nov 2026 (SQLite 3.53.1), all five artificial
two-word topics attained recall@6 = 4/4 and precision@6 = 4/6 in each of
the three channels. For the hand-coded `sofa`→`couch` case, FTS4 scored
recall@6 = 0/1; the vector and fused channels each scored 1/1, with **two
false positives**. For the query with zero labeled relevant documents,
FTS4 returned nothing but vectors and fusion each returned **two false
positives**, with recall deliberately reported as undefined (`null`).
Across 35 timed host queries, the approximate p50/p95 was **0.63/1.49 ms**
for FTS4, **7.35/10.85 ms** for LSH/SQLite + cosine and **0.018/0.025 ms**
for RRF alone. These are separate, unpaired host timings, sensitive to
machine, cache and corpus; no latency threshold is claimed. They should
**never** be used to claim a real embedding model handles synonyms or is
fast on Android. Device acceptance, 20k real embedding storage behavior,
multi-chunk retrieval and representative human-labeled precision/recall
remain open.

The same hybrid benchmark also prints `multichunk_cap_probe`: 360 sources,
128 chunks each, 46,080 total artificial vector chunks. In the **original
single-pass SQL**, the late exact-match source is absent at six when the
query is capped at 45k; it is present with the cap lifted. The **adaptive
exact-first policy now recovers it at the unchanged 45k cap**. This is an
observed host regression improvement, **not** Android timing or a universal
SQLite ordering guarantee. The Android `EMBED-CHUNKS-19` QA separately
exercises the later exact chunk at a one-chunk synthetic budget and ensures
incomplete checkpoints remain excluded. All inputs are generated and
model-free.

The new `EMBED-OVERLAP-19` fixture separately uses three cache-only synthetic
notes: cosine 0.99 with one exact and one neighbor signature, cosine 0.98
reached *only* by neighbor probes, and cosine 0.80 outside all query probes.
With a two-chunk diagnostic cap, both reachable sources are retained. The
unmodified two-pass implementation lost the neighbor-only note because it
revisited the first chunk in its second pass. This narrow fixture demonstrates
candidate-budget accounting, not true semantic recall or device speed.

The `angular_noise_probe` similarly reports the five artificial cosine
values and which (if any) of the four LSH tables still reach a query probe.
It is a fixed geometry probe, not a claim about real model noise or a
calibrated relevance threshold.

In the updated paired 20k one-chunk host test on 26 Nov 2026, the old SQL
and adaptive policy had semantic lookup p50/p95 of **8.20/10.23 ms** and
**8.12/10.18 ms**, respectively (35 timed calls each). The adaptive policy
uses the fast path for this corpus because 20k is below its 45k chunk cap;
the difference is measurement noise, **not** a speedup claim. Android/OEM
testing and overloaded 20k multi-chunk latency remain an explicit gate.

### Experimental exact-bucket saturation and source diversity

Run `python scripts/benchmark_stage19_lsh_fairness.py --sources 360 --chunks 128
--cap 45000 --trials 2` (the command may be entered on one line). The entire
index lives in memory and has 46,080 generated vectors. Unlike the ordinary
20k benchmark, **all earlier sources collide with an exact LSH signature**,
so the initial exact pass alone exceeds its candidate cap. A follow-up
scenario changes the late source's other chunks to weaker exact-colliding
vectors, so selecting an arbitrary first chunk per source can miss its best
one. A third case keeps the later best chunk in the *same sole exact bucket*
as an earlier weaker chunk. The original Android-style exact-first SQL is a
cross-checked baseline.

One observed host run on 26 Nov 2026 (two measured calls per policy per
scenario; p50 is descriptive only):

| Experimental SQL policy | Unique sources seen (of 360) | Late best source at top 6: lone exact / prior weaker exact / same exact bucket | Lookup p50, ms (first case) |
| --- | ---: | --- | ---: |
| Current exact-first `GROUP BY path,chunk` | 352 | No / No / No | 83.7 |
| One arbitrary candidate `GROUP BY path` | 360 | Yes / No / No | 45.2 |
| Interleaved `ORDER BY chunk,path` | 360 | Yes / Yes / Yes | 105.4 |
| Equal quotas for four exact buckets | 88–89 | Yes / Yes / No | 19.2 |
| One candidate per source per exact bucket | 360 | Yes / Yes / No | 37.6 |
| Four indexed exact streams, four-way heap merge | 360 | Yes / Yes / Yes | 104.1 |

The source-first option inspects only one candidate per source; quotas also
underuse capacity and may double-count chunks between exact tables, making
their apparently lower CPU times incomparable to the full-cap baseline.
Interleaving sorts far more candidates and can be costly for Android SQLite.
The four-way merge instead uses a small cursor heap and an additional covering
index, but on this host its lookup time was not convincingly better, even
before index maintenance and storage costs. The portable Android query plan,
insertion overhead and device memory cost have **not** been accepted.
These values are deliberately adversarial host measurements, not real model
precision/recall or a production tuning recommendation. Regression tests use
a smaller but structurally equivalent 90-source, 16-chunk fixture to protect
the failure and tradeoff cases in CI without 46k-vector allocations.

## Repeatable engineering acceptance

- JVM tests: RRF tie/duplicate/empty/bounds; changed source metadata;
  unique-rank invariance under duplicate/empty results and complementary
  synthetic lexical/semantic source labels (including opposing statements);
  post-consent raw-byte source revision and missing approval seals;
  valid, fabricated, malformed and missing citation numbers; exact vs invented
  quotation, clause-local attribution, mixed valid/invalid source labels,
  uncited prose spans and fake citation markers in code/links/quoted text;
  escaped/reference-style Markdown and indented-code masking, repeated
  citation source-lexicon reuse and oversized source rejection;
  non-prose HTML comments, link targets and autolinks, including quoted URL
  text and a repeated-citation mixed Markdown answer;
  trailing sentence-end citation clusters, all six ATX heading levels and
  CR/CRLF/Unicode line separator isolation from following uncited claims;
  distinctive-term overlap, low fractional overlap in long claims, generic-
  word-only false source conflicts and optional per-number disagreement warnings,
  including a changed number hidden by another matching number;
  exact-excerpt diversity tie-breaking with stable top-two provenance and
  fail-closed overlong evidence questions, mirrored in the offline benchmark;
  title-token prefix boundaries, one-token-one-term credit, Cyrillic matching,
  bounded title-rank windows and phrase-first precedence over title rank;
  HTML tag attributes, quoted `>` characters, raw script/style/template
  bodies and long mixed HTML without allowing hidden source references;
  full-width, Arabic-Indic and supplementary Unicode digit lookalikes in
  malformed citations, while preserving ordinary Markdown labels;
  hybrid shared-note excerpt choice with stronger lexical word coverage,
  compact phrases, semantic tie retention and bounded EN/RU regressions;
  soft-wrapped/full-reference Markdown links and escaped inline-code backticks,
  including numeric citation cluster retention and code-span false positives;
  EN/RU contrastive comma-clause boundaries, independent citation groups and
  quote attribution without laundering from a later contrasting statement;
  untrusted instruction-like source content; forged delimiter,
  source-label and Unicode line-break injection. The synthetic reconciliation
  selftest now also asserts strict multi-token ranking ahead of an earlier
  partial-match pathname, correct indexed text, same-length changed text
  rejection, and exclusion of unverified source content. New JVM token/phrase
  syntax checks, Python label-based opposing-statement and synonym-negative
  tests, and Android synthetic `LEX-PHRASE-19` are included. Phrase-aligned
  lexical excerpt selection has separate English/Russian JVM and
  `CITE-EXCERPT-19` synthetic device regressions, including multi-term
  coverage versus an earlier isolated hit.
- Python suite: `python -m unittest discover -s tests -q`.
- Android CI: Java tests, standalone `com.dpsmpx.dpks` APK compilation and
  embedded core checks. No external LLM credentials, personal vault content,
  outbound model calls or real knowledge journal are used by tests.
- Deferred physical acceptance: run `CITE-ANCHOR-19` and
  `HYBRID-BALANCE-19`, `HYBRID-INDEX-19` and `EMBED-CHUNKS-19` from the
  synthetic device QA
  screen, then verify
  accessibility/scrolling, cancellation and exact consent content with a
  synthetic local library. Compare lexical and hybrid preview p50/p95,
  perceived UI responsiveness, peak RAM and battery cost before/after the
  6-to-12 local-read change; do not use private notes for benchmark evidence
  or call a real model without separate opt-in. CI PASS does not close this.

## Deliberately still open

- RRF, title-match scores and the three-pass lexical priority are **relevance
  heuristics**, not learned rerankers or proof. Results are now title-ranked
  only **within each bounded path-ordered candidate window**, with stable
  pathname ties and no synonym expansion. The adversarial FTS4 fixture does not close the
  wider `RET-15` gate: representative labeled precision/recall, synonyms,
  semantic model behavior and Android 20k latency remain unmeasured until a
  future physical run of the new synthetic lookup timing fixture.
  The six-note Android `HYBRID-INDEX-19` fixture is a **structural regression**
  for posting-list search, cosine ranking, RRF diversity and source-byte
  disclosure guards; its artificial vectors and bounded-query timing must
  not be presented as representative learned semantic quality or 20k latency.
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
- HTML tag and raw-element masking only recognizes bounded common syntax.
  Malformed tags, escaped text and nonstandard HTML/Markdown renderers may
  still disagree with the heuristic about whether text is visible; it does
  not sanitize model content for display or replace HTML rendering security.
- No automatic rejection of the answer, fact promotion or journal change;
  users retain final judgment. A detected **post-consent source change** does
  reject the pending *network request* before any model call, with a prompt to
  review the new excerpt. Real provider, network/privacy, rotation,
  TalkBack and OEM/lifecycle QA remain unverified on physical devices.

Stage 19 is **partial**. The broader `RET-15` and `LLM-16` debts are not closed
until the benchmark and grounding/security gates pass.

# DP Knowledge System — Android integration roadmap

Status: in progress. Each numbered stage is a reviewable PR against the
long-lived integration branch `feature/knowledge-android`. A stage is merged
into that branch only after CI and review. `main` retains the released Markor
snapshot until the integrated application is ready.

## Non-negotiable contracts

- Original Markdown files remain authoritative and locally owned by the user.
- Reading/indexing never renames, moves, deletes, rewrites, or silently
  normalizes a note. Reorganization must be a separate approved dry-run with
  collision checks, logging, and rollback.
- Private notes, imported fragments, local index and journal do not enter Git
  or telemetry. Cloud processing needs explicit informed permission.
- An extracted statement is a **claim**, not an accepted fact. The user
  confirms all authoritative changes through preview/accept/edit/reject.
- `UNKNOWN` is not `FALSE`. Contradictions, provenance, and decisions
  remain visible. A language model never grants factual authority.
- Rebuildable search/semantic indexes must be distinct from source notes and
  from the append-only, user-authorized knowledge journal.
- Project/subject/function/document type are distinct metadata axes.
  Embedding similarity and old folder names are evidence, not ground truth.

## Incremental PR sequence

| Stage | Product increment | Acceptance |
|---|---|---|
| 01 | Native Knowledge Library entry point, read-only local vault discovery, bounded filename search, open note in existing Markor editor | Testable on Android; no vault mutation, path escape, or secrets; CI |
| 02 | Persistent private Markdown index (paths, headings, candidate links/backlinks, full text), metadata-based incremental reconciliation, safe cancellation | Search in app, no source writes, parser tests, incomplete scan does not infer deletion |
| 03 | Formal `ksys` integration with deterministic Python/native bridge; reproduce existing fixtures rather than silently reimplement logic | Android ↔ core contract tests, offline execution, parity against Python golden cases |
| 04 | Human-in-the-loop knowledge intake: CLAIM/FACT/HYPOTHESIS, preview, approval, editable interpretations, journal, provenance | No unapproved fact, journal integrity, source trace and undo-safe decisions |
| 05 | Knowledge search, relationships, contradictions, explanations, hypotheses, and temporal queries | Inspectable evidence trees; `UNKNOWN` and `CONTESTED` correctly distinguished |
| 06 | Explicit, encrypted journal export/import before wider release | User-chosen backup/restore, source hash-chain verified, existing journal never overwritten |
| 07 | Automatic, local, whole-library semantic classification, candidate extraction, incremental indexing and review queue | All accessible Markdown notes processed without authorizing facts; cancellation, size bounds and partial-scan safety tested |
| 08 | Conservative cross-note semantic relationships between independently extracted claims | Exact predicate/argument matching, opposite-negation candidates, paired provenance, no automatic factual authority |
| 09 | Whole-library audit of contradictions, ambiguous and unresolved text, source errors, approved-knowledge gaps and journal integrity | Fresh local import, exact severity-separated counts, bounded source-provenanced pages, explicit completeness limits; no automatic repair |
| 10 | Conversational assistant with explicit grounding and configurable optional local/remote LLM | Source-cited answers, no fabricated proofs, opt-in network and privacy controls |
| 11 | Reliable large-vault UX: persistent background jobs, change notifications, diagnostics, Android tests and signed prereleases | Long-running realistic synthetic stress tests, crash-safe recovery and release QA |

The stages are medium-sized deliverables; if a stage proves too broad it is
split into separate reviewable PRs rather than bypassing a review gate.

## Stage 01 scope and limits

The new menu item opens the Knowledge Library for the notebook folder already
configured in Markor. A cancellable background scanner discovers `.md` and
`.markdown` entries. Hidden files/directories and symlink escapes are ignored.
Up to 200,000 notes may be discovered; the first 250 matching relative paths
are displayed, with explicit truncation/unreadable-folder warnings. Clicking
an entry delegates to Markor's existing document editor.

This first stage **does not** index note contents, run the formal knowledge
engine, synchronize external storage, change the Android package ID, or
publish a new signed application release. Those capabilities have explicit
subsequent stages. No actual private vault is a test fixture.

## Stage 02 scope and limits

The Knowledge Library maintains an **entirely disposable** SQLite FTS4 search
index inside Android's app-private, no-backup directory. The content of
Markdown notes is indexed locally; the Markdown files remain authoritative,
and nothing is uploaded or written back to them. Users can search titles and
note contents, and long-press a result for *possible* incoming links. Links
are lexical candidates, not established relationships or facts.

On refresh, metadata (size + modification time) controls incremental
reindexing. Index reconciliation removes missing entries only after a
complete scan. A cancelled update rolls back its transaction. Notes larger
than 2 MB receive filename-only entries, and results are capped at 250 per
query. Frontmatter semantics, rigorous link resolution, background observers,
index tamper recovery, live-update detection and large-vault performance
validation remain open follow-up work. Android UI search is not yet connected
to the deterministic Python `ksys` engine. A private index contains private
note text: it must never be copied to Git, debug reports, or releases.

## Stage 03 scope and limits

The Android fork bundles the repository's existing `ksys/` Python sources
through a build-time generated directory (not a maintained second copy).
The Python interpreter is embedded with Chaquopy 17.2, with Python 3.11
for 32-bit and 64-bit devices. The new Java-to-Python transport runs the
original `ksys.source.markdown`, coverage and classifier/language-analyzer
functions on a selected note, and shows fragment kinds, candidate counts,
provenance locators and coverage as a **read-only preview**. The bridge
contract is `dpksys.inspect.v1`; host Python tests compare exact fragment
identities with the same code paths as Android.

The native interface is intentionally a diagnostic first step: it does not
yet expose authoritative journal acceptance, multi-note semantic inference
or conversational retrieval. Those require the explicit user decisions and
provenance UI in later PRs. Original sources are read via canonical-path
checks, and plaintext is never included in the app logs or GitHub Actions.
For offline embedding, the fork's minimum Android version changes from
API 21 to **API 24** (Android 7.0). Android 11 and Android 15 remain supported.
Chaquopy adds native runtime dependencies; APK size and device runtime
performance need measurement during release QA.

## Stage 04 scope and limits

From an inspection of a selected note, the user can now select a fragment,
review the formalization and explicitly choose one proposed reading. A second
confirmation dialog includes the unmodified source, canonical KSF, possible
conflicts, validation diagnostic count and the intended epistemic role.
Only the explicit confirm action can record a **claim**, **hypothesis** or
**fact**, or **reject** or **defer** a reading. There is no automatic promotion
of extracted content. The original Python `KnowledgeBase` validates the
candidate and appends the decision to its hash-chained journal.

The journal is inside Android's private **no-backup** folder, separate from
both the vault and the disposable FTS index. Every write checks the original
note's content hash, provenance fragment ID, interpretation reading ID and
prior journal head; tampered chains are rejected. A generated Python source
tree includes the engine's public schema JSON files for validation parity.

**Important limitation:** no journal export, import, or uninstall recovery is
available yet. Users receive an explicit warning before confirming a decision.
This branch is **not a production release** and must not be used for
irreplaceable authoritative records before backup/recovery work is complete.
Manual editing of an interpretation, bulk intake and retraction UX remain
future milestones. Inference and question-answering are likewise not yet
present in the Android interface.

## Stage 05 scope and limits

The Knowledge Library now exposes a native **Ask knowledge core** dialog and
a **Knowledge state** report. Both call the actual offline Python `Dialogue`,
`Answerer` and inference/provenance engine through a constrained read-only
bridge. Only query operations are admitted, and `Dialogue.handle` is always
called with `yes=False`. There is no implicit knowledge acceptance or
background reclassification.

Responses retain epistemic status, evidence, derivations, contradictions,
unknowns and provenance, with a bounded on-screen representation. A dedicated
state panel summarizes approved records, claims, open hypotheses and
contradictions. The authoritative layer remains separate from unconfirmed
claims: unsupported questions return `UNKNOWN`, and contradictory accepted
evidence can return `BOTH`. Synthetic tests verify all three properties and
that questions do not modify the journal.

This is not yet free-form AI chat. It is deterministic, limited by the
existing controlled-language parser and the body of user-approved knowledge.
No LLM invocation or external connection is part of this milestone.

## Stage 06 — encrypted journal portability (safety priority)

Because a human-approved knowledge journal is authoritative and irreplaceable,
the next integration PR prioritizes portable backup/recovery before optional
LLM transport. The Knowledge Library exposes two Android Storage Access
Framework actions: **Encrypted export** and **Restore backup**. Only the user
chooses the destination/source document.

The export verifies the existing Python journal's hash chain and compares its
raw SHA-256 digest immediately before encryption. The DP Knowledge Journal
`.dpks` container has a versioned header, random 128-bit salt, random 96-bit
AES-GCM nonce, 256-bit AES key derived from a user passphrase by
PBKDF2-HMAC-SHA1 (600,000 iterations, for Android API 24 compatibility) and
a 128-bit authentication tag. Neither passphrase nor key is stored in the
repository, journal or app settings. The selected backup content is encrypted
before leaving the app. Tampered ciphertext and incorrect passwords are
rejected. API 26+ could use PBKDF2-HMAC-SHA256; a future versioned container
may adopt a memory-hard KDF.

Recovery decrypts locally, verifies every sequential journal entry and hash
link against the original `ksys.store.journal` algorithm, displays the entry
count and head for user confirmation, and then installs the journal from a
temporary file. **An existing nonempty journal is never replaced or merged.**
There is an 8 MB plaintext limit for this initial portable format. User
passphrases must contain at least 12 characters, and users are responsible
for keeping both the encrypted archive and the passphrase safe.

**Limitations:** no scheduled automatic backup, password recovery, key
rotation, multi-device merge, atomic guarantees across an external cloud
provider's document write, or large-journal streaming yet. Complete migration
and realistic device recovery testing remain a release gate.

## Stage 07 — automatic semantic processing of the library

The Knowledge Library can now process the **entire selected local Markdown
notebook** using the original repository-root Python
`ksys.importer.vault.import_vault` and `LanguageAnalyzer` through
`dpksys.bulk.v1`. It segments notes into source-linked fragments, classifies
them, runs deterministic controlled-language formalization, counts unresolved
or ambiguous statements, and creates candidate interpretations in a private,
disposable SQLite database. The UI shows a progress counter, aggregate
statistics, and a paginated candidate review list. Selecting a candidate
reopens the current Markdown and uses the existing two-step human approval
and provenance checks. **No imported suggestion automatically becomes an
approved claim, hypothesis or fact.**

Users can start/cancel processing at any time; a separate, explicitly enabled
setting automatically reruns the semantic importer after the ordinary library
refresh. Unchanged notes reuse their prior indexed fragments and candidates.
Both search and bulk interpretation run away from the UI thread on independent
executors. No web API, model service, telemetry, personal notes, or processed
data enter GitHub.

The Android import deliberately skips hidden metadata, non-Markdown and
Git-ignored files; it prevents symlink escapes, caps individual Markdown
sources at 2 MB and processing at 200,000 Markdown files per run, and reports
unreadable, oversized, truncated or coverage-failed sources. A cancelled run
rolls back its in-progress derived-index transaction. **Missing file entries
are retained** because external storage access and partial scans cannot prove
that a source was deleted. This conservative approach can leave old candidates
in the private index; reopening a candidate always checks its current source
and rejects stale fragment identities. An explicit complete reconciliation
strategy is planned for a later stage.

Limitations: this stage performs deterministic language interpretation,
classification and extraction — **not LLM-driven semantic understanding,
embeddings, cross-document entity disambiguation, automatic knowledge
authorization, an unattended OS-managed WorkManager job, or reliable real-time
change observation**. Automatic reprocessing runs on manual library refresh
while the Knowledge Library screen is active and opt-in is enabled. Candidate
pagination includes previously reviewed candidates; review-state filtering
and priority ranking remain future work. APK/device validation and large
real-world vault load testing remain required before production use.

## Stage 08 — source-backed cross-note claim relationships

The whole-library import now also builds a **disposable semantic atom index**
from formalized candidates. For each unapproved assertion it records the
canonical structured predicate and argument values, negation, source document,
fragment ID and locator. This works incrementally alongside the Stage 07
import, without a second walk of the vault. The private derived SQLite schema
is upgraded from v3 to v4 (the disposable index rebuilds; authoritative
journals and Markdown sources are not migrated or modified).

Long-press any indexed note and choose **Related claims across notes**. The
knowledge core finds other notes with *exactly the same structured predicate
and arguments*, distinguishing a candidate **agreement** (same negation)
from a candidate **conflict** (opposite negation). The results contain both
source locators, both stable fragment IDs, the other relative Markdown path
and the predicate. Tapping a result opens the referenced source with the
original canonical-path safety check. At most the first 60 results are shown;
the interface explicitly marks truncated results. All work remains local.

This is intentionally **not entity resolution, semantic similarity or
automated contradiction proof**. Only fully specified asserted claim atoms
are compared; rules, unresolved variables, nonasserted modalities and
unparsed text are excluded. Different names, paraphrases, context or hidden
synonymy cannot be equated safely. Conflicts between two unapproved claims
do not imply that either is false. Changed or temporarily missing sources
may leave old candidate relations until the next successful semantic refresh;
users must inspect current notes and explicitly approve any authoritative
knowledge through the preexisting journal flow. No network service is used.

## Stage 09 — whole-library contradiction and uncertainty audit

The native **Audit the whole knowledge library** action first reruns the
read-only Markdown semantic import, then assesses the entire private derived
index and the separately verified human-approval journal. A cancellable worker
reports progress. After completion, a seven-category summary and paginated
issue browser let users open the original source without rewriting it:

1. Contradictions among *human-approved* knowledge, using the existing
   `Answerer.contradictions("auth")` engine and proof provenance.
2. Potential opposing claims anywhere in the imported sources, grouped by
   exact canonical predicate/arguments rather than enumerating quadratic
   numbers of pairs; each polarity has up to three source/fragment locators.
3. Ambiguous controlled-language interpretations.
4. Unresolved references and incomplete bindings.
5. Prose that the current grammar cannot formalize (reported, not discarded).
6. Skipped/inaccessible source documents and import/coverage errors.
7. Knowledge-journal validation problems, open hypotheses, deferred claims,
   unreviewed human claims, missing decision bases and unassessed confidence.

The summary also reports indexed documents and fragments, incomplete scans,
skipped files, previously indexed paths not observed during the latest scan,
and the numerical consistency backend/status where supported. Every result
states that **no detected contradiction does not prove consistency**.
Corrupted or unreadable journals are marked invalid; their authoritative
counts become **unknown**, never silently zero. Source-level findings remain
inspectable even if the journal fails verification. Individual issue pages
show at most 40 entries and signal when more exist.

Switching notebook roots now clears only the *disposable source projection*
before analyzing the new vault, avoiding accidental cross-notebook mixing.
The authoritative journal is neither cleared nor changed. Cancellation
rolls back the in-progress import, including any root-scoping changes.

**Limits:** this is a complete audit of what the bounded deterministic parser
can represent in the selected and accessible Markdown library, **not** proof
of semantic consistency for arbitrary natural language. Incomplete storage
scans, the 200,000-file / 2 MB limits, old entries conservatively retained
after missing files, unresolved entity references and missing LLM semantics
are explicitly disclosed. No automatic approval, repair, deletion, cloud
request, cleartext export or scheduled background job is introduced. Large
real-device vault stress tests and signed prerelease validation are pending.

## Integration branch and PR process

1. Maintain `feature/knowledge-android` as the continuous integration line.
2. Branch one numbered milestone from its current verified tip.
3. Run its Python and Android checks and open a PR **targeting the integration branch**.
4. Merge the PR after checks, then branch the next stage from the updated
   integration branch. Do not overlap milestones or silently push feature code
   to `main`.
5. Open a separate final PR from integration to `main` only after release QA.

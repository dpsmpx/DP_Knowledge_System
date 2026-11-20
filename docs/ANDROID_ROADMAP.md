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
| 11 | Optional embedding-based semantic search and LLM evidence selection; bounded incremental vector index | Ranked passage-level matches, strict per-action upload consent, local-only vector search, privacy-safe cache invalidation |
| 12 | Heading/paragraph-aware overlapping Markdown passages, resumable fine-grained embedding checkpoints and indexed approximate vector candidates | Expanded long-note coverage, source-accurate spans, migrations that preserve old vectors, bounded candidate reranking |
| 13 | Large-vault scan/index progress, private SQLite consistency diagnostics, user-authorized rebuild, reproducible cancellation/stress tests | Bounded count-only progress, transactional success markers, read-only health report, destructive derived-index repair requires consent |
| 14 | Opt-in WorkManager local scan jobs, long-running foreground processing, opt-in count-only change notifications, on-device endurance fixture and signed prerelease automation | Durable scheduler and retries, safe note-only source traversal, notification permission handling, isolated signed test package; actual device endurance evidence remains an acceptance gate |
| 15 | Real-device endurance signoff and adaptive scheduling at 20k–200k notes | Measured latency, energy, interruptions, restore behavior, Android 11/15 compatibility and performance fixes before promoting integration to main |
| 16 | Conservative reconciliation of moved/deleted sources, partial-scan uncertainty and backlinks/evidence freshness | Only complete scans delete derived rows; incomplete scans preserve but explicitly label unverified rows and exclude them from AI evidence, semantic retrieval and confirmed-recent backlinks; synthetic device regression |
| 17 | Decision lifecycle: revision, split/merge, retraction, batch approvals and strict versioned policies | **Partial:** human retraction/revision, atomic one-to-two split and two-to-one merge; bounded 2–8 claim-only batch with one journal event and hash-checked preview; synthetic QA `DECISION-17`, `REVISE-17`, `SPLIT-17`, `MERGE-17`, `BATCH-17`; broader batch, automation and device acceptance outstanding |
| 22 | Full independent Android UI/UX with Dynamic Color, efficient note browsing, process dashboards, human decision/diff review and accessible navigation | **Slices 1–13 integrated, 14–20 in PR #72 pending final CI/merge:** 4-tab Material 3 DPKS workspace, direct launcher, guarded review, user-controlled editor Save/Preview, exact-source context and cautious CNL comparisons; responsive phone/tablet dock, 60-note paged library, offline epistemic guide, code-only event history and one-button Java/Python synthetic tests. Markor's editing/rendering engine is deliberately reused. General-language semantic equivalence, real touch/TalkBack/200% font acceptance, device 20k benchmarks and full independent editor engine are **not** asserted. QA-59–74 remain OPEN/DEFERRED. See `docs/STAGE22_COMPLETION_GATE.md` and `docs/ANDROID_DEVICE_QA_STAGE22.md`. |

**Stage 22 delivery correction:** PR #66 / Preview #17 included the one-button
test, but the first real Android 15 run on 19 Nov 2026 returned
`EDITOR-22-SUITE: JSONException` (ENV PASS, UI SKIP). The Python bridge
protocol was absent from the Java transport allowlist. A minimal protocol
hotfix and direct Android regression test were merged in PR #68. Signed
Preview #18 was published after green CI, but the synthetic test must still
pass **on the replacement APK** before QA-67 can progress.
This does not close the deferred physical editor QA-59–66.

The stages are medium-sized deliverables; if a stage proves too broad it is
split into separate reviewable PRs rather than bypassing a review gate.

Stage 15's embedded synthetic checks passed on Android 15 (20k index built in
6.218 s), but SQLite read-only integrity warnings, provider consent, manual
process/lifecycle and backup checks remain explicitly open in the
[`main` technical-debt register](https://github.com/dpsmpx/DP_Knowledge_System/blob/main/docs/TECHNICAL_DEBT.md).
The product roadmap after 15 is defined in `main/docs/DEVELOPMENT_PLAN.md`;
Stage 16 is not approval to merge the draft integration PR into `main`.

## Stage 17 partial implementation: explicit retraction, revision and one-to-two split

The native Android UI now supports a bounded, read-only list of active
knowledge records, a preview of retraction consequences (`what_if_retract`),
and an explicit one-record confirmation. The Python core rechecks the current
journal head and record status before appending a `retract` entry to the
hash-chained human journal. The original markdown vault is untouched; neither
the approval nor the retraction is automatic. The one-button synthetic
`DECISION-17` check records count-only evidence within disposable app cache.
An additional manual revision path accepts only a newly typed, formally
validated one-node replacement of an active claim, hypothesis or fact; the
human chooses the interpretation, sees the previous/new canonical record and
conflict counts, and confirms the appended `revise`. The existing journal head
and replacement text hash are checked again at commit. The device test
`REVISE-17` uses only its own private-cache fixture and does not read or modify
personal notes or the actual decision journal.
The split flow accepts two independent human-authored statements, each with
its separately chosen formal reading, then shows both canonical representations,
diagnostic and potential conflict counts. **Only explicit confirmation** writes
one atomic `split` journal entry: the old record becomes obsolete, two distinct
new records become active, and each successor refers back to its original.
Neither successor is claimed to be a logically proven consequence of the old
statement. A stale journal head, duplicate successor or changed source text
blocks the append. `SPLIT-17` exercises this transition only on synthetic data.
The two-to-one merge flow starts from a human-selected first record and asks
the user to **independently select** another active record of the same role.
One newly typed statement is formally analyzed and previewed beside both
old canonical records. The user explicitly confirms a single atomic `merge`
journal event: both sources become obsolete, the new successor links to both
in history. Similarity never implies equivalence or automatically authorizes
a merge; the new statement is a distinct human decision. `MERGE-17` exercises
this exclusively on disposable synthetic fixtures.
The bounded batch flow lets the user select 2–8 candidates from one queue
page, inspect each current note-derived reading, and explicitly accept them
**only as claims**. It rejects duplicates, conflicts, invalid readings, stale
notes and journal changes before a single `batch_accept` event containing
separate provenance per claim. Choosing one candidate still opens the older
single-decision dialog. `BATCH-17` tests only disposable synthetic notes.
See `docs/ANDROID_DEVICE_QA_STAGE17.md`.

**Still open:** revision/split/merge edge cases, richer batch roles, versioned strict
automation policies, manual device lifecycle checks, full encrypted app backup
and cloud synchronization. Stage 17 is **not fully complete**.

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
Framework actions: **Export decisions journal** and **Restore backup**. Only the user
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

**Export regression found in Stage 18 Preview #24 (2026-11-20):** A newly
created SAF destination could remain **0 bytes** if the journal was empty or
encryption failed *after* the document picker had already created it. The
follow-up fix checks the journal, validates its SHA-256 and encrypts the
payload **before** asking Android to create a file. No decisions now means an
explicit message without launching the picker. Once saved, the entire closed
encrypted payload is read back and compared before showing success; a failed
provider may leave a partial file, and the user is warned to remove it
manually. The in-app synthetic quick QA includes `BACKUP-WRITE-01`, which
checks successful stream read-back and rejects a synthetic zero-byte provider;
real SAF providers, rotation/process death, and physical export/restore remain
unverified as a complete acceptance matrix. **Journal export does not include original Markdown notes or the
full Stage 21 portable backup.**

**Owner confirmation, signed Stage 18 Preview #26 (2026-11-20):** The owner
retested export and reported that it works. This closes the reported #24
zero-byte symptom for that device/test, not a complete SAF-provider matrix,
backup restore, interruption/rotation, or Stage 21 acceptance.

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

**Stage 18 precision hardening (first slice):** Cross-note agreement and
possible-conflict groups now also require the **same exact asserted context
and the same exact validity interval**. An absent context/interval is a
specified default, not a wildcard; malformed annotations are excluded. The
same constraint applies to the whole-library source-conflict audit because
both use the disposable `candidate_atoms.atom_key`. The projection version
changes to `exact-claim-context-interval-v2`, so a successful full semantic
refresh safely regenerates old derived keys without editing notes or the
human-approved journal. Read-only relation responses, editor context details
and audit findings expose those exact qualifiers for human inspection;
unapproved claims remain suggestions. Different or partly overlapping time
spans are not automatically treated as equivalent. This trades recall for reliable
precision; alias/entity resolution, interval-overlap truth inference, confidence
and cross-source epistemic reconciliation remain **open Stage 18 work**.

**Stage 18 precision hardening (second slice, awaiting Android acceptance):**
The local *derived* candidate index v5 also keeps an exact predicate/argument/
context scope key and validated ISO date bounds. It can now display a separate
`temporal_overlap` hint when two distinct explicitly bounded intervals intersect
(inclusive of a shared endpoint). It displays both intervals and their
intersection; different context, unrelated predicates/arguments, disjoint dates
and unspecified periods are excluded. An opposite polarity is shown only as
metadata, **not** automatically classified as a conflict. Exact agreement and
possible-conflict matching, plus the whole-library contradiction audit, retain
their original exact full-key requirement. The projection marker
`exact-claim-context-temporal-overlap-v3` forces a fresh derived refresh after
upgrade; neither Markdown files nor the human journal is migrated or modified.
Both the library relation list and editor context use one bounded query with an
index on the shared scope key. This is a comparison of source interpretations,
not temporal truth inference, entity merging or historical version resolution.
Android UI/SAF hardware QA remains separate; alias resolution and cross-source
confidence reconciliation are still open Stage 18 work.

**Stage 18 precision hardening (third slice, awaiting Android acceptance):**
Read-only cross-note comparison can now return a distinct `alias_candidate`
hint when **exactly one symbolic argument** differs under an alias already
explicitly recorded by the configured owner in the integrity-verified local
decision journal. The predicate, all other arguments, context, and exact
validity interval must still match. Both source locators and the specific
`J:` alias decision IDs appear in the result and editor/library UI; matching
opposite polarities is **not** upgraded into a conflict or agreement verdict.
This uses an indexed, bounded set of full-key alternatives at query time; no
Markdown, indexed atom key, or journal record is modified. Competing rebindings,
self-maps, chained/cyclic aliases, non-owner operations, invalid symbols and
excessive numbers of mappings are ignored or explicitly marked limited.
Unapproved textual similarity, wikilinks, and model-generated aliases do not
create equivalence. This does **not** implement arbitrary entity disambiguation,
an Android authoring/approval UI for new aliases, or evidence-based identity
proof. The existing CLI `alias` journal operation is the only source of this
slice's hints; full alias lifecycle, independent historical version resolution,
confidence calibration and cross-source reconciliation remain on the roadmap.
Synthetic Python and Android CI cover the code; real Android UI acceptance
is still open. The separately deferred Stage 21 hardware debt is unchanged.

**Stage 18 manual entity lifecycle (fourth slice, awaiting device acceptance):**
The Android **Decisions → Manual entity matching and withdrawal** entry now
provides an explicit workflow to enter two exact parsed entity symbols, browse
up to 24 indexed source fragments per symbol, inspect the original excerpts,
open either note, choose two distinct source fragments, enter a reason, inspect
a combined preview and consciously confirm. A successful approval appends an
owner-authored `alias` event to the private hash-chained human journal with the
exact symbol pair, two source paths/locators, source SHA-256 values and excerpts;
the original Markdown and disposable claim keys remain unchanged. Before both
preview and commit, the bridge checks the saved index's notebook root, exact
parsed symbols, source SHA-256 against the current files, unchanged journal
head, source selection and preview token. Stale or missing sources, rebinding,
chains, cycles and over-limit alias groups fail closed. These are decisions
*based on unapproved parser interpretations*, not objective identity proofs.
The same screen lists owner-approved mappings, recorded evidence and recent
journal events. A separate revocation preview plus confirmation appends an
`unalias` event that references the exact original alias decision; this stops
new alias-backed relationship hints without destroying the old record, and
revocation remains accessible even when the notebook is disconnected. Reapproval
after revocation requires a new full source review and new journal event.
Legacy CLI aliases without stored excerpts remain visible by symbol and journal
ID, but cannot retroactively acquire invented evidence. This slice does not
automatically reconcile old inferences, infer entity identities, prove source
truth, or implement a confidence model. Android on-device UX/accessibility,
process-death recovery and SAF hardware QA remain unverified; Stage 21 hardware
debt is still deliberately deferred.

**Stage 18 evidence-pattern review (fifth slice, awaiting device acceptance):**
For every exact, indexed candidate claim signature in an inspected note, the
shared library/editor read-only query now reports a bounded cross-file evidence
overview (at most 16 distinct signatures and 32 *other files* per signature).
It deduplicates repeated fragments/readings within the same Markdown file,
separates same versus opposite polarity, and treats a file with both parser
polarities as **mixed** rather than counting it on both sides. When the source
note itself has mixed readings, comparison counts are suppressed instead of
guessing its position. Exact predicates, all arguments, context and interval
must match; temporal-overlap and alias hints remain separate and never add
votes to the exact evidence. The bounded source paths and locators are
inspectable from the relation list and from the editor context panel.

These are **counts of indexed documents, not independent witnesses**: copied
notes, uncertain parser readings and stale snapshots can mislead. Every group
therefore explicitly says confidence is **not assessed**, authority remains
`unapproved_candidates`, and freshness is an unverified index snapshot. Caps
are labeled as incomplete, not silently presented as exhaustive counts. No
factual reconciliation, authority promotion, numerical confidence or direct
journal/source writes occur. Physical
Android UI, latency, TalkBack and large-vault acceptance remain open.

**Stage 18 live evidence provenance (sixth slice, awaiting device acceptance):**
When the selected notebook matches the import's recorded root, both the Hub
relation overview and editor context now rehash a bounded selection of original
Markdown files, read-only, before presenting cross-note evidence. Each displayed
source is explicitly marked **current hash**, **changed**, **unavailable** or
**not checked**. At most 16 distinct file hashes are inspected per request
(each eligible file at most 2 MiB; approximately 32 MiB upper I/O bound).
The checked subset receives separate same/opposite/mixed counts only if **both**
the inspected note and the other file match their indexed SHA-256. Original
indexed counts remain visibly labeled as unverified snapshots. Files outside
the check budget and root mismatches are **not checked**, not presumed valid;
limits are surfaced in both UIs. Missing or oversized files and rejected paths
are not silently counted as current.

The disposable index also exposes the representative indexed fragment hash
for each matched document, enabling a **possible repeated excerpt** warning
only among currently verified files; when two complete file hashes match,
the UI can say **identical file content**. Fragment hashing normalizes Unicode
and trims surrounding whitespace, so this is a textual-repeat signal, not
proof of shared authorship or independent provenance. Distinct hashes do **not**
establish independence. A hash match proves the file was unchanged relative to
indexing, not that the semantic interpretation or claim is true. No journal
decision, Markdown edit, automatic merge, confidence number or authorization
promotion is produced. Next Stage 18 work: more robust human-reviewed entity
identity, versioned temporal reasoning, and calibrated source reliability.
Device UI, lifecycle, 20k-note performance, TalkBack and SAF checks remain
unverified; Stage 21 physical QA is still separately deferred.

**Stage 18 source-copy families (seventh slice, awaiting device acceptance):**
The same bounded live-provenance query now groups **all** current checked
files by equal representative normalized fragment hash (`R1`, `R2`, …) and,
separately, by identical full-document SHA-256 (`D1`, `D2`, …). Every member,
including the originally inspected note and the first alphabetically ordered
copy, receives its group label. Two other source files with identical content
are identified even when the inspected file itself differs. Group membership
and sorted member paths appear in the Hub and editor source disclosures;
related files remain individually navigable. Repeat labels are deterministic
per displayed claim and never reused as global source identities.

The UI also distinguishes the **number of hash-current files with a selected
fragment** from **the number of distinct representative normalized excerpts**.
Neither measure is an estimate of independent witnesses, reliability or
truth: it samples one indexed parser fragment per exact claim signature and
cannot recognize paraphrased copying, common upstream citations, fabricated
claims or independent people who happened to write the same words. Stale,
missing, unchecked and over-budget notes cannot join a copy family. No
new database schema, automatic source authority, human decision, journal
operation, external service or Markdown write is involved. Calibrated and
explicitly human-reviewed source lineage remains a separate open Stage 18
task. Device UX, accessibility and the deferred Stage 21 physical QA remain
unverified.

**Stage 18 human-reviewed common-upstream lineage (eighth slice, in progress):**
Two original Markdown files may be explicitly marked by the owner as sharing
an upstream source, with a human-provided reason. Preview shows the exact
current SHA-256 and a bounded original indexed excerpt with locator for each
file, and is read-only; confirmation rechecks both SHA-256 values, notebook
root, and hash-chained journal head before appending `source_link`. The
independent `source_unlink` event references the exact original decision ID
and can be confirmed after notebook disconnection. Both operations are
**source lineage annotations only**: they never affect fact/claim authority,
entity aliases, inferences, Markdown, or confidence/independence ratings.
Only direct owner decisions are active; no transitive link inference, automatic
matching, network metadata lookup or merging is performed. The owner journal
operation list is bounded, and prior decisions remain in history on revocation.
The Android Decisions screen now offers a dedicated manual common-origin
workflow: two exact relative paths and reason, current excerpt/SHA review,
source navigation, confirmation, active/history browser and a separate
withdrawal confirmation. The relation evidence disclosure links directly to
the same workflow with the inspected note path filled in. No action is
automatic; changed notes fail closed, and withdrawal remains reachable
without an active notebook. Physical UI, lifecycle, TalkBack, 20k-note
performance and SAF acceptance remain unverified.

**Stage 18 reviewed lineage in evidence (ninth slice, awaiting device QA):**
The Hub and editor source-evidence lists now display *direct* owner-reviewed
common-upstream decisions with their journal ID and recorded reason. A manual
link is marked current only when **both** original Markdown files still match
their indexed hashes **and** those hashes equal the values saved at the time
of the owner's decision. Otherwise the link remains visible, but is labeled
**historical/unverified**, including after reindexing a modified file. The
checked and historical link counts are separate from indexed claim polarity
counts and do not enter copy-family detection, truth inference, confidence or
independence estimates. No chained/transitive human lineage is inferred.
Revocation immediately removes the active link from new relation views while
preserving its journal history. Excessive/corrupt source-link journal coverage
fails closed with an explicit incompleteness warning. No additional source
I/O or index writes are introduced. Android hardware acceptance remains open.

**Stage 18 source-link navigation (tenth slice, awaiting device QA):**
The Hub's evidence disclosure offers an explicit choice of the *other* source
from already hash-current indexed candidates, then pre-fills both exact note
paths in the human common-origin review dialog. Stale, missing and unchecked
files are not offered as verified candidates; a manual-path fallback remains
available and the backend still validates both sources again at preview and
commit. Saved active-link excerpts are separately labeled **historical**
because their original hashes may no longer match current Markdown. This is
navigation and disclosure only, not automatic source matching or approval.

**Stage 18 manual source-type annotations (eleventh slice, journal API):**
An owner may explicitly classify a single original Markdown note as
`primary_record`, `secondary_summary`, `republication`, or `unclassified`,
with a human explanation. These are **descriptive provenance types, not
reliability scores or correctness rankings**. The read-only preview displays
the selected original excerpt, locator, and verified file SHA-256. The
confirmation rechecks the notebook root, current file hash, preview token,
active decision and journal head before appending the distinct `source_assess`
event. `source_unassess` references that exact event and works offline;
historical records are never deleted. Neither event modifies authority,
claims, aliases, inferences, Markdown or the source index. The active/history
API and operation cap are separate from the common-upstream link workflow.
The Android Decisions screen now exposes a separate source-type review button.
It offers a localized descriptive-type choice, original excerpt/SHA preview,
explicit confirmation, source navigation, owner history and a separate offline
withdrawal confirmation. These are UI/journal metadata only. No automatic
epistemic authority, source ranking or hidden classification is introduced;
physical Android, accessibility and SAF acceptance remain unverified.

**Stage 18 descriptive source-type evidence (twelfth slice, awaiting Android QA):**
The Hub's and saved-editor's unapproved cross-note evidence displays an
owner-recorded source-type category, exact decision ID and shortened explanation
beside the original and each other source. It distinguishes **current** (the
live file hash matches the indexed document and the SHA saved at manual approval)
from **historical/unverified** (changed, absent, unchecked or wrong notebook).
Counts of classified *other files* are shown separately; they never add to
independence, reliability or truth scores. A withdrawal removes the active
annotation from future read-only evidence queries while the append-only journal
still holds history. Journal bounds and unavailable verification have explicit
UI warnings; no extra files are hashed, no source is auto-classified, and no
Markdown/index/knowledge claims are modified by evidence browsing.

**Stage 18 source-type review navigation (thirteenth slice, awaiting Android QA):**
The evidence file picker lists the inspected note as well as other evidence
sources. Selecting a hash-current note offers **open original** or **manual
source-type review**, with the exact relative path prefilled after choosing a
descriptive type. An existing manual type instead offers its decision/history
screen; stale, missing and unchecked evidence is only offered for original
inspection. Prefill never counts as confirmation: the existing backend still
checks the selected root, live SHA, fragment, preview token and journal head.
This adds no automatic classification or source authority.

**Stage 18 direct review and withdrawal (fourteenth slice, awaiting Android QA):**
Selecting a currently annotated source from the evidence file picker opens
that **exact active owner decision** instead of requiring a search through
all decisions. The panel displays stored reasons, source snapshot and journal
ID, and offers the existing independent withdrawal preview and confirmation.
If the decision changed between evidence display and selection, the panel
falls back to the normal review/history menu; it does not act on a stale ID.
Every mutation remains guarded by the original journal-head and decision-ID
checks, with no changes to Markdown or automatic claim status.

**Stage 18 paired evidence excerpts (fifteenth slice, awaiting Android QA):**
Cross-note evidence now carries an explicitly bounded (up to 240 characters)
snapshot excerpt and fragment ID per original file, selected deterministically
from a single source-owned fragment row. The representative locator and
excerpt hash come from **that same row**, instead of potentially unrelated
`MIN` aggregates over multiple parser readings. Invalid cross-document
fragment references cannot leak a different note's excerpt or enter copied
excerpt warnings. The Hub compares the inspected and selected excerpts
before offering source navigation or human source-type review, and the editor
context dock shows the same comparison before opening the other note. Both
views explicitly warn that indexed snapshots may be stale and that live SHA
freshness validates bytes, **not** the interpretation or truth of a claim.
No additional Markdown reads, network access, source edits, automatic
confidence scores or journal writes are introduced by comparison.

**Stage 18 bounded excerpt query optimization (sixteenth slice, awaiting Android QA):**
The source-owned representative selection now uses a single parameterized
SQLite window query for the inspected note and up to 32 other files per
evidence group, replacing a separate lookup for each file. Thus at most
33 SQL reads are needed for 16 groups (one overview + one file-count + one
excerpt batch per group), rather than hundreds of individual reads. Text
substrings are still loaded only for the ranked fragment per document, with
the same 240-character cap, exact path ownership and stable ordering.
Tests compare the displayed excerpts and verify the per-group query budget;
this is a bounded implementation improvement, **not** a measured Android
20k-vault latency claim or a change of evidence authority.

**Stage 18 nonasserted modality contrasts (seventeenth slice, awaiting Android QA):**
An independent disposable `candidate_modal_atoms` table now projects only
explicit `possible`/`usually` claim readings and hypothesis readings, with
the same strict ground-atom validation, exact context and interval as the
existing asserted-claim index. Asserted `claim` rows remain only in
`candidate_atoms`; neither modal nor hypothetical rows may enter its exact
agreement/conflict, evidence-file counts, copied-excerpt families or
whole-library conflict audit. Index schema v6 and relation projection v4
trigger a **derived-only** rebuild on refresh; private Markdown and the
append-only human knowledge journal remain untouched.

The Hub and editor now show a distinct **modality contrast** hint when the
same strict atom signature is reported with different degrees/kinds of
certainty (asserted, usually, possible, explicitly hypothetical). Both
modalities, node kinds, source locators, context, exact period and any opposite
polarity are displayed; the latter is **not** a factual conflict verdict.
Queries are bounded by the existing relation page limit and one additional
SQL index. Missing/pre-migration modal tables or capped coverage report
incompleteness. No natural-language semantic equivalence, numerical
confidence, independence estimate, user approval or truth propagation is
inferred from a source modality. Physical Android QA remains separate.

**Stage 18 modal source freshness (eighteenth slice, awaiting Android QA):**
Modal/hypothesis comparison rows now show the current saved-file SHA status
of both original Markdown sources: current, stale, unavailable or not checked.
An indexed modal contrast is described as a live-source pair only if **both**
file hashes match the versions in the disposable index; even then parser
readings remain unapproved. Without a matching selected notebook root, all
checks stay explicitly unchecked. The Hub and editor show historical-only
warnings, including when checks are capped. Modal comparisons reuse the same
16-unique-file budget as exact-evidence checks (maximum 32 MiB of live
Markdown reads per query), with no duplicate hashing of shared files.
Original notes, approved decisions and the append-only human journal remain
unchanged. No automatic conflict vote, source reliability or independence
assessment is introduced; physical Android QA remains open.

**Stage 18 time-separated source readings (nineteenth slice, awaiting Android QA):**
The bounded cross-note relation query now adds a distinct, read-only
`temporal_separation` hint for two asserted, fully closed, non-overlapping
date intervals describing the **same exact predicate, arguments and context**.
The UI shows both precise date ranges, which is earlier and the number of
calendar days strictly between them (0 for adjacent periods). These are
source-supplied validity intervals, **not** observations of when a fact changed.
Unspecified/open bounds never yield a separation hint; a shared endpoint is
still an overlap, not a gap. Opposite polarity remains metadata only. Neither
this hint nor any date order participates in the exact-key agreement/conflict
audit, source evidence counts or accepted knowledge. Library and editor expose
the same bounded results with EN/RU warnings against inferred version truth.
No index migration, original Markdown change or human journal write occurs.

**Stage 18 live provenance for dated hints (twentieth slice, awaiting Android QA):**
Both temporal hint types (`temporal_overlap` and `temporal_separation`) now
expose **current/stale/unavailable/not_checked** verification for *each*
original Markdown file against the exact SHA stored in the disposable index.
Only two currently matching file hashes justify the display label
`live_source_pair`; this verifies the saved source bytes, not date truth,
assertion correctness, author independence or a historical change. Wrong
notebook roots and exhausted budgets always leave hints marked historical.
Temporal checks reuse the **same 16-distinct-file, maximum-32-MiB** per-query
SHA budget as exact evidence and modal hints (existing checks are cached),
with explicit incompleteness warnings in Hub and editor. Changes affect
read-only API annotations, EN/RU UI, tests and documentation only. Original
Markdown, derived knowledge authority and human decision journal are untouched.

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

## Stage 10 — explicitly approved LLM assistance

The native **Ask LLM about notes** action adds a deliberately optional
conversational layer above the existing *local* Markdown search. The user
enters a question, an OpenAI-compatible chat endpoint, the exact model ID,
and optionally an API key. A local FTS4 recall query proposes at most 24
existing notes; the app reads at most six source files (respecting the
existing canonical-root and 2 MiB per-note limits) and extracts at most 900
characters from each. The prompt labels every numbered excerpt as **untrusted,
unapproved source material** and requests citations such as `[1]`.

**No excerpts or question leave the device without a separate confirmation
for every request.** The confirmation dialog displays the destination host,
model, and the *complete outgoing user message*, including Markdown source
paths and excerpts. Cancel means no network request. Indexing, full-vault
processing, deterministic Q&A, auditing and knowledge-journal operations do
not call the LLM. Only endpoint/model preferences are saved; API keys are
entered per request, are never persisted, and no prompt, response or key is
written into application logs or the knowledge journal.

Configure a service supporting `POST /v1/chat/completions` with a text
`choices[0].message.content` response:

- On the same Android device: `http://127.0.0.1:1234/v1`, when an appropriate
  server actually runs there.
- On your home PC: `http://192.168.x.y:1234/v1` after enabling local-network
  listening in a compatible service such as LM Studio. `localhost` on Android
  **does not mean the home PC**. A private Tailscale IPv4 address
  (`100.64.0.0/10`) is also allowed.
- A remote service: `https://example.org/v1`, with an optional session API
  key. The app does not ship a hosted model or create an account.

The endpoint is normalized to `/v1/chat/completions`. Cleartext HTTP is
restricted to numeric RFC1918, loopback and Tailscale-CGNAT IPv4 addresses
or `localhost`/IPv6 loopback; arbitrary public HTTP and credential-bearing
URLs are rejected. Redirects are disabled, timeouts and request/response
sizes are bounded, and the dialog explicitly warns when transport is
unencrypted. Network authorization is **per question**, not a global opt-in.
The selected server still receives the shown note contents and may retain
them according to its own policies. Prefer a trusted local service for
particularly private notes, secure and authenticate any network listener,
and do not expose your local model port to the public Internet.

The response is displayed as **unverified AI text** with the six candidate
source paths. Missing citation markers trigger an additional warning; even
valid-looking citations are **not verified as logically sound**. There are
no automatic knowledge approvals, note rewrites, arbitrary tool execution,
agent actions, model downloads, on-device inference engine, semantic-vector
retrieval, reranking, streaming or autonomous network traffic in this stage.
Lexical retrieval will miss paraphrases; prompts embedded in notes may still
influence an LLM despite being clearly marked as untrusted. Android-device
integration tests against actual local and remote models remain necessary.

## Stage 11 — optional semantic embeddings for Markdown retrieval

**Build embeddings** in the Android knowledge screen now creates a disposable,
local vector projection for **the selected notebook only**. Users separately
choose an OpenAI-compatible embeddings endpoint and an *embedding model*
(which is different from the chat completion model), and optionally enter
an API key. Compatible servers implement `POST /v1/embeddings` and return
indexed, numeric vectors of equal length. For instance, with a loaded
embedding-capable model in LM Studio, a PC service can be reached from Android
through `http://192.168.x.y:1234/v1`; remote endpoints require HTTPS. The
same private-network HTTP restrictions and disabled redirect policy as Stage
10 apply to embeddings. Local model IDs must be obtained from the selected
service; the project does not silently download or bundle any model.

**Privacy and explicit permission:**

- Indexing is **manual and off by default**. Before a batch, the app lists
  up to 40 selected note paths, the total note count (up to 120 per run),
  the destination host/model and a clear warning that selected private
  excerpts will be sent in multiple network requests, and may be retained
  by that service. Cancel means **no upload**. No endpoint is contacted
  merely by scanning a vault, opening the app or performing the deterministic
  knowledge audit.
- A note contributes at most **three excerpts of 850 characters**. This
  is a *sample* across longer notes, not full-content semantic coverage.
  Paths and journal data are not included as embedding inputs. Up to six
  excerpts are sent per request; runs resume from still-unindexed notes
  when the user starts the next batch. There is no autonomous background
  continuation after the user leaves the screen.
- The **question itself** is sent to the embedding service only after an
  additional per-query dialog showing its exact text and destination.
  Search compares the resulting vector with local embeddings, so no note
  text is uploaded while querying. In **Ask LLM**, the user can explicitly
  choose semantic rather than lexical source selection. The existing second
  confirmation still displays *every exact excerpt* before the chat model
  receives it. API keys are never stored; endpoint/model preferences are.

Vectors are float32, length-normalized, checked for finite values,
stored in the app-private `noBackupFilesDir` SQLite database, and ranked
by actual cosine similarity against the query. The best chunk per document
is shown with a score; the source note can be opened. The index retains
the model/endpoint fingerprint, source timestamp, size, and excerpt offsets.
Changing/deleting notes or switching notebook roots invalidates relevant
cached vectors. A **long press on Build embeddings** clears the entire
disposable semantic projection with an extra confirmation, leaving Markdown
files and the authoritative knowledge journal untouched.

**Current bounds:** batches are capped at 120 notes; Android reads at
most 2 MiB per eligible Markdown file; each note contributes up to 3 chunks;
up to 60,000 vector rows are compared in a search. Very large libraries
may require multiple authorized indexing runs, and all runs/queries are
subject to the local or remote embedding server's performance, quotas
and privacy policies. No vector ANN index, on-device inference engine,
semantic proof, learned re-ranker, verified citations or zero-knowledge
remote processing is claimed. Similarity is a **candidate relevance
signal**, not evidence that source claims are true. End-to-end tests
against actual Android hardware and embedding servers remain pending.

## Stage 12 — broader passage coverage and indexed vector retrieval

**Markdown chunker v2:** Instead of three sparse snippets sampled across a
note, the manual embedding operation now walks its text in order. Each
source passage is at most **850 characters**, with up to 80 characters of
overlap. Headings and paragraph/line boundaries are preferred, and the
segmenter avoids treating fenced-code newlines as semantic breakpoints.
Original character offsets and exact passage lengths are preserved for
later source inspection and for the LLM disclosure dialog.

The per-document cap is **128 passages** (roughly 100k characters for long
unbroken text); documents above that cap are **explicitly reported as
partially covered**. The existing 2 MiB Markdown read bound remains.
Large files therefore have much broader coverage than Stage 11, but a
2 MiB file is **not guaranteed** complete semantic coverage. Empty or
inaccessible notes are skipped. Running Build embeddings again continues
with additional eligible notes, not magically beyond the 128-passage cap.

The app uploads **at most six passages per network request** after the
existing explicit batch confirmation. Within a single document, each
successful network reply is committed to the disposable SQLite index
in a transaction. Cancellation, application exit or a transient server
error leaves an incomplete document unsearchable but **resumable from
the last saved chunk** on the next authorized run. A complete document
becomes searchable only when all its planned passages have been saved.
No automatic background restart or unsanctioned uploads are introduced.
The manual authorization UI now warns that a single document may disclose
up to 128 separate passages; it shows the selected destination and first
40 local source paths.

**Faster retrieval:** Each normalized embedding is indexed into four
deterministic, 10-bit locality-sensitive hash (LSH) tables. A question
probes 44 buckets (exact plus one-bit neighbors) in SQLite and scores
the resulting shortlist with **exact cosine similarity**; the best passage
per Markdown note wins. Up to 45,000 candidates are evaluated. This avoids
a hard alphabetical scan of the first 60,000 vectors but is still
**approximate nearest-neighbor retrieval**. LSH can miss relevant neighbors,
especially for dissimilar or poorly calibrated embedding vectors, and
scores are never evidence that the underlying assertions are facts.
Neither exhaustive recall nor performance on millions of passages has
been demonstrated on real hardware yet.

**Non-destructive migration:** Existing Stage 11 three-slice embeddings
are retained during the private SQLite v2→v3 migration; their LSH buckets
are reconstructed locally, without contacting a model server. Their
chunker version remains v1 and they continue to be searchable. Rebuilding
a document with chunker v2 requires the user to initiate and authorize
another embedding batch. Only the selected note's obsolete derived
vectors are replaced, not its Markdown source or approval journal.
Clearing the vector index still requires deliberate long-press confirmation.

The batch/text-size network bounds, HTTPS/private-network rules, API-key
non-persistence, query-specific permission, and mandatory exact LLM
chat-context disclosure from Stages 10–11 remain unchanged.

## Stage 13 — large-library progress, diagnostics, safe index recovery

**Count-only progress.** The read-only vault walker and local SQLite
full-text synchronization now report bounded progress approximately every
512 files (and at completion). The Knowledge Library status distinguishes
discovery from private-index updates. Neither paths nor note contents are
passed through progress callbacks or written to logs. Both operations
honor the previous cancellation generation; no half-completed sync can
claim to be successful.

**Committed-scan checkpoint.** Only inside the successful source-index
SQLite transaction, metadata records the timestamp, number of discovered
notes, truncation/incompleteness and unreadable-directory count. A process
death or cancelled transaction rolls back these markers together with the
FTS/index edits. This is a **consistency checkpoint**, not a claim that a
200,000-note scan can resume mid-file or survive process termination as
a running job.

The existing Diagnostics button now offers **Knowledge state** or
**Index health and recovery**. Index health runs on a worker thread and
performs SQLite `PRAGMA quick_check(1)` plus independent count checks:

- source rows, FTS rows, missing/orphan FTS entries and candidate links;
- embedding documents, chunks, incomplete/truncated embeddings and LSH
  posting totals (four per valid indexed vector);
- stale semantic documents and orphan vector/LSH records;
- last committed scan status and source-coverage warnings.

The report is deliberately count-only, offline, and read-only. It can
identify structural inconsistencies but **does not prove semantic validity**
or detect every conceivable stale filesystem write. On an unreadable index,
the UI also offers a separate recovery action.

**Explicit repair:** the user must pass a second confirmation before the
app removes only its disposable `knowledge_index_v1.sqlite` and SQLite
journal/auxiliary files from `noBackupFilesDir`. The app cancels
competing scans and embedding work first, then rescans the selected vault
offline. **No Markdown note or append-only authoritative knowledge
journal is modified or deleted.** Previously generated embeddings are
lost on a rebuild and can only be recreated via the earlier manual
consent procedure and the user's chosen embedding service.

**Synthetic verification:** Android JUnit creates 1200 fake Markdown
files across directories, checks deterministic discovery, progress,
coverage and unchanged source files; a second 700-note scenario triggers
cancellation after 512 discovered notes and checks preservation. These
fixtures contain no personal content and run in CI with the Python core
suite and Android APK build.

**Limits:** no WorkManager-style persisted background execution, Android
change notifications, device-wide monitoring, automatic LLM requests,
signed prerelease publication or real-device 200k-vault benchmark is
introduced in this milestone. These require a separate reviewable
stage and hardware-based performance/permission testing.

## Stage 14 — optional durable local jobs, notification controls and preview QA

**User-controlled scheduling.** Android uses `androidx.work:work-runtime:2.11.2`
for unique, persisted checks about every six hours, *only after the user
explicitly enables them*. Periodic runs require a charger and non-low battery;
Android may delay them. The user can also queue a single check and disable
periodic checks at any time. Settings contain only operation status, timestamps
and counts. A worker resolves the current user-selected Markor notebook
at execution time and is never passed a plaintext vault path via WorkManager
data. No code invokes LLMs, embedding servers, knowledge-approval actions or
network requests during these jobs.

The worker performs the same bounded Markdown scan and transactional SQLite
FTS update as the foreground library, reporting count-only progress. It uses
WorkManager's foreground execution for long operations, the `dataSync` service
type and a low-priority in-progress notification; cancellation causes rollback
of unfinished SQLite transactions. After process termination, WorkManager
retries from the **beginning of the scan**, not a per-file durable checkpoint.
Foreground/background concurrent writes may be serialized by SQLite, and
temporarily unavailable files lead to retry or incomplete status, never
silently to deleting Markdown files. The separate authoritative knowledge
journal is not accessed by these jobs.

**Change alerts are a separate opt-in**, with Android 13+ notification
permission requested in context. An alert is sent only for a *complete*
traversal with a previous successful baseline of the same vault and a nonzero
sum of inserted/updated/deleted indexed note rows. It displays a count only,
no note title, folder, quoted content, model prompt or graph inference.
The first scan, truncated scans and unreadable directories cannot produce
change notifications. Denying notification permission never prevents a
local scan; progress notification behavior is controlled by Android for
foreground work.

**Reproducible hardware QA:** `scripts/generate_android_stress_vault.py` safely
creates a *new* disposable folder with 20k Markdown fixtures by default (up
to the 200k scanner cap). It refuses to touch an existing directory and
emits reproducible sample SHA-256 hashes. Its safety rules have Python unit
tests. `docs/ANDROID_DEVICE_TEST_STAGE14.md` defines manual Redmi Note 13
and Android 11 scenarios for first scan, offline mode, periodic scheduling,
process kill, notification permissions, memory/battery measurements and
post-test checksums. **These are not claims of successful physical-device
testing:** device results and tuning remain the Stage 15 acceptance gate.

**Signed prerelease:** `.github/workflows/knowledge-signed-prerelease.yml`
tests the Python core and Android build, builds `flavorAtestRelease` with
the separate `com.dpsmpx.dpks` Android application ID, signs only after receiving
repository signing secrets, verifies with `apksigner`, and attaches the
APK plus its SHA-256 to a GitHub *prerelease*. The workflow is restricted to
the `feature/knowledge-android` integration ref and is triggered once when
introduced there; later runs are manually dispatched on that ref. No
keystore or credentials are committed and no notes are bundled.

**Limits:** OS battery restrictions and OEM policy can delay background work.
`dataSync` foreground service behavior and permission dialogs require
physical-device QA. Periodic checks are not real-time file watchers and
do not run semantic re-embedding automatically. The proof-of-concept release
must remain a prerelease until device tests and a data-safety review pass.

## Integration branch and PR process

1. Maintain `feature/knowledge-android` as the continuous integration line.
2. Branch one numbered milestone from its current verified tip.
3. Run its Python and Android checks and open a PR **targeting the integration branch**.
4. Merge the PR after checks, then branch the next stage from the updated
   integration branch. Do not overlap milestones or silently push feature code
   to `main`.
5. Open a separate final PR from integration to `main` only after release QA.

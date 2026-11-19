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
| 22 | Full independent Android UI/UX with Dynamic Color, efficient note browsing, process dashboards, human decision/diff review and accessible navigation | **First seven slices integrated:** Material 3, direct launcher, Decisions workspace, guarded selection, manual reformulation/Save, read-only contextual knowledge panel, and separate saved-Markdown/accepted-KSF comparison with exact journal-provenance span verification and Unicode-safe navigation. Owner confirmed Preview #14 Decisions and Preview #16 improved review flow, without full QA signoff. **Eighth slice (CI pending, device QA deferred):** ephemeral, role-colored inline highlights for verified active journal provenance only; no indexed candidates or source writes. Native editor replacement, semantic diff, 20k performance, accessibility and full device acceptance remain open; **QA-61–63 device checks deferred**. See `docs/ANDROID_UI_UX_STAGE22.md` and `docs/STAGE22_EDITOR_QA.md`. |

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

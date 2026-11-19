# Android UI/UX Stage 22 — workspace, editor chrome and decision previews

Status: **thirteen slices integrated; full Stage 22 completion work in progress**. The owner confirmed ordinary decision
review in Preview #14 and reported two observations in Preview #15:
formalization of a selected fragment returned `unsupported punctuation`;
the editor/saved-file mismatch guard fired on an unsaved edit. These are
**partial, scenario-specific device observations**, not a full QA-54/QA-59
signoff. The owner subsequently confirmed that Preview #16's updated flow
works, but not the full QA-60 matrix. The sixth-slice read-only contextual
knowledge panel is integrated; its physical QA-61 remains deferred.
Markor still owns actual Markdown editing.

## Scope

- The DP Knowledge System screen has its own **Material 3 DayNight** theme
  with optional Android 12+ wallpaper-derived Dynamic Color. The legacy Markor
  editor's theme, editor functionality and file handling remain unchanged.
- Fixed bottom navigation separates **Library**, **Processes** and **Manage**.
  Switching sections only changes view visibility, **not** activity/workspace
  identity or background worker lifetime.
- The default **Library** screen shows selected vault identity, scan/index
  state, search, grounded Q&A actions and a bounded recycled note list. The
  long vertical stack of operational buttons no longer pushes search results
  offscreen. An empty-state message and 56dp minimum note-row height are added.
- **Processes** groups semantic analysis, embedding operations, optional
  background work and the two one-button device QA flows. In-progress jobs
  are indicated by a numerical badge on the Processes destination; analysis,
  embedding and device QA reports remain viewable without blocking navigation.
- **Manage** groups accepted decision review, knowledge audit, index
  diagnostics and *journal-only* encrypted export/import. The backup card
  explicitly warns that this is not a full app/vault backup.
- The existing actions and IDs are preserved and covered by static resource
  tests. Cancelling an embedding job no longer cancels an unrelated device QA
  run.

## Second slice — direct launch and source/decision surfaces

- In `com.dpsmpx.dpks` the launch icon opens **Knowledge Library directly**,
  through a flavor-controlled activity alias. MainActivity no longer owns the
  launcher intent. Non-DPKS Markor flavors retain their original launcher
  through the opposite alias flags. The DPKS launcher label is now
  **DP Knowledge System**, not the inherited test label.
- Opening a note from the library starts `KnowledgeDocumentActivity`. It hosts
  the existing Markor Markdown editor (editing, saving and preview functions
  are unchanged) under a dedicated Material 3 toolbar with explicit **back to
  library** navigation. Closing the note uses `finish()` so the library's
  Android task is not removed by the inherited editor's `finishAndRemoveTask`.
  A source-warning strip distinguishes *manual changes to Markdown* from
  *knowledge-journal decisions*.
- Confirmations for **revision, split, merge and retraction** now use a
  scrollable Material 3 review with individually labeled **before/after**
  blocks, selected text, change highlighting for one-to-one comparisons,
  diagnostics, warnings and explicit confirmation. No preview itself writes
  the source or the journal. The existing fingerprint, stale-head and
  concurrency validation still runs at commit time.

This is a UX integration layer, **not** yet a fully independent DPKS editor,
an audit-ready source-span diff or a replacement for the decision list dialogs.

## Third slice — first full-screen Decisions destination (initial feedback positive)

- Four bottom destinations: **Library / Decisions / Processes / Settings**.
  The editor, background jobs, and the local-first journal policy are unchanged.
- **Decisions** is accessible on every launch, even with an empty journal or
  no analysis index. It has eight searchable, bounded, count-labeled lists:
  pending interpretations, active records, fact records, hypothesis records,
  rejection events, deferral events, obsolete records and journal history.
- An interpretation counts as pending only if its source fragment has not
  already produced a saved record/deferral, and its formalized nodes have not
  all been explicitly rejected. Other readings of a partly rejected fragment
  can still be pending. Because old rejection events do not persist a full
  source snapshot, the **rejected/deferred tabs show journal events** rather
  than pretending they are full accepted knowledge records.
- Tapping an item opens a **full-height, scrollable, read-only detail screen**:
  source excerpt (labeled snapshot), machine interpretation or human record,
  formalized statement, available provenance, local diagnostics, available
  conflicts and related journal events. Missing evidence is visibly missing,
  not silently counted as zero. The original source is re-read and the journal
  head revalidated before any later commit.
- Existing guarded actions remain available via **Review interpretation** or
  **Revise / Split / Merge / Retract**. Batch approval remains explicitly
  accessible for 2–8 proposals; no journal append is made by browsing.
- Python core tests use only disposable synthetic Markdown and assert the
  journal/source invariant; resource tests assert four destinations and detail
  sections. The owner confirmed the ordinary decision review works after
  Preview #14, but comprehensive physical Android validation, large-list
  performance and TalkBack remain open. See `docs/STAGE22_DECISIONS_QA.md`.

## Fourth slice — note editor intelligence and context (Preview #15)

- The dedicated DPKS editor shell adds an explicit **Edit / Preview**
  control, retaining Markor's renderer, formatting support and proven
  file-save path. The control reflects existing Markor mode changes.
- **Analyze** runs the local Python parser on the editor buffer without
  source or journal writes, showing bounded fragment previews, semantic
  candidate counts and coverage status; these are machine interpretations,
  *not* accepted knowledge.
- **Review selection** maps Android UTF-16 selection offsets to Python
  Unicode code points, matches exactly one source fragment and opens the
  existing guarded decision preview. Selections spanning multiple fragments
  are refused rather than guessed.
- **Save-before-review guard:** a selected fragment can only be passed to
  decision review when the current editor text exactly equals the saved
  Markdown file. The receiving library rechecks the SHA-256 of the saved
  source before generating its normal preview, followed by existing
  source/journal freshness checks at commit. The UI never silently saves,
  changes the note or accepts an interpretation.
- **Links** displays only *possible lexical backlinks* from the last
  complete, vault-matched private index scan, opened read-only. Missing or
  stale scans do not claim an absence of links; navigation rechecks that the
  target stays inside the selected vault. This is not semantic backlink proof.
- Analysis and backlink queries run off the UI thread with stale-result
  suppression. No cloud request or telemetry is added. Synthetic Python
  and resource tests assert selection mapping, boundaries and UI guardrails.
  See `docs/STAGE22_EDITOR_QA.md` for the mandatory device checklist.

## Fifth slice — guided interpretation for unsupported source syntax

- The local Russian CNL formalizer intentionally returns **NO_PARSE** for
  syntax outside its strict grammar (e.g., internal semicolons/parentheses).
  This must never be converted into a guessed, automatically accepted fact.
- Instead of exposing only the opaque diagnostic `unsupported punctuation`,
  the review screen explains the limitation and offers **Your statement**:
  a separate editable one-line interpretation, visibly distinguished from
  the read-only original Markdown fragment. A failed manual attempt can be
  edited again. These are user assertions, not source quotations.
- The manual preview is **read-only** and remains unpersisted until the
  user selects an epistemic role, sees source/proposal/risks, and explicitly
  confirms. Commit checks the exact manual-text SHA, current source SHA,
  fragment identity, formal reading and journal head before appending.
  The provenance is labeled `human_interpretation` with extraction method
  `manual_reformulation`, retaining the original source span/hash/text.
- An explicit **Save** action in the DPKS editor reuses Markor's existing
  writer, then verifies that the persisted Markdown matches the editor
  snapshot. Knowledge analysis never triggers saving by itself.
- Synthetic tests cover unsupported punctuation, successful manual CNL
  wording, preserved Markdown, absent journal during preview, rejected stale
  source/manual phrase/journal and explicit append-only approval.

## Sixth slice — contextual knowledge panel (integrated, device QA deferred)

- An always-reachable **Knowledge** toggle expands a bounded, scrollable
  panel directly above the Markor editing surface; no extra hub navigation
  is needed for the first contextual read.
- The panel keeps **active journal records** visibly separate from
  **unapproved candidate relations**. Human claims, hypotheses, accepted
  facts and stale provenance have distinct labels. Candidate agreements and
  conflicts are explicitly *possible*, never proven evidence.
- The bridge reads journal state and an *existing* semantic index without
  writing or migrating anything. Tentative cross-note links are suppressed
  if the selected note's index hash does not equal the saved Markdown hash.
  Other notes' freshness is not guaranteed and is labeled as such.
- The editor buffer is never silently saved: when it differs from the saved
  source, the panel visibly states it is showing the **saved snapshot**.
  Clicking a journal record reveals its source excerpt/formal statement;
  navigation to a local excerpt requires an identical saved/editor hash
  and an unambiguous text match. Clicking a candidate opens a selected-vault
  Markdown note only after canonical path containment checks.
- At most 24 active records and 20 tentative relations are shown. The panel
  reports truncation, missing/stale indexes, obsolete-history counts and
  unverified provenance. Workers run off the UI thread; obsolete responses
  and dialogs are invalidated on navigation. No cloud API or telemetry.
- This remains an initial contextual surface, **not** a full source-span
  semantic diff, independent editor engine, or exhaustive contradiction
  audit. Large-library speed and accessibility are recorded as QA-61 debt.

## Seventh slice — verified source-span alignment in the editor (integrated, QA deferred)

- The accepted-record detail now presents two separate selectable blocks:
  **saved Markdown source** and **accepted canonical KSF statement**. Their
  appearance does not assert semantic equivalence or change either text.
- The Python bridge verifies the whole saved-note SHA, the stored provenance
  Unicode span and its exact raw substring before reporting line/column and
  source offsets. Missing, stale or mismatched legacy spans remain visible
  as unverified, but cannot be jumped to. Repeated identical excerpts are
  located by provenance offsets, not the first text occurrence.
- Navigation converts Python Unicode code-point offsets to Android UTF-16
  editor selections, then rechecks canonical vault membership, saved file
  hash and unchanged editor buffer off the UI thread. A changed source or
  unsaved edit refuses the jump. Long excerpt/KSF displays are bounded and
  clearly labeled as truncated; the original Markdown is not rewritten.
- Synthetic tests cover duplicate sentences, emoji offset conversion,
  missing/malformed spans, stale sources, and the read-only contract.
  Python and Android CI passed on PR #51. **QA-62 physical checks are deferred**
  at the owner's request.
- This is an evidence-location comparison, **not** a word-level semantic
  diff, automated truth verification or a general-purpose diff editor.

## Eighth slice — temporary inline source highlights (integrated, QA deferred)

- A **Highlight knowledge** toggle in the DPKS editor temporarily marks
  Markdown ranges referenced by active, human-approved journal records.
  Fact, hypothesis, claim and mixed-role ranges have separate Material theme
  colors and a text legend. Mixed/overlapping spans are deliberately grouped
  and never arbitrarily colored as a single epistemic role.
- The new `editor_note_highlights` Python bridge opens no index and reuses
  strict saved-note SHA, exact Unicode provenance span and raw-text validation.
  Unapproved analysis candidates and uncertain historical source locations
  are **not** colored as knowledge. At most 128 distinct ranges are rendered;
  truncation and the count of unverified origins are disclosed.
- Decorations use transient Android `BackgroundColorSpan` objects, not
  Markdown text replacements. They are removed on the next text edit, mode
  switch to Preview, explicit Save, toggle-off or editor lifecycle stop.
  Unsaved changes or a stale saved-file snapshot refuse highlighting.
- Java converts Python code-point positions to UTF-16 before attaching spans.
  Synthetic tests cover duplicate excerpts, non-BMP prefixes, the no-journal
  case, unapproved-index separation, mixed overlaps and output bounds.
  Python and Android CI passed on PR #54. **QA-63 physical UI acceptance is
  deferred** at the owner's request.
- Highlighting marks *where approved assertions came from*, not whether
  those statements are true. It is not a live semantic annotation engine,
  independent Markdown renderer or automatic proposal editor.

## Ninth slice — source selection → knowledge journal inspection (integrated, QA deferred)

- The editor provides **Knowledge at cursor**: place the caret inside a
  verified journal source or select text fully contained within one. The
  detail links directly to matching active and historical/obsolete records,
  with separate status labels, original Markdown vs KSF, provenance, possible
  contradiction counts and up to eight associated journal events.
- Python `editor_source_records` reuses strict saved-file SHA and raw
  provenance span verification. It never guesses a record from a repeated
  phrase or stale source, and never includes unapproved semantic candidates.
  Results are bounded to 16 records and count excluded, unverified origins.
- Android maps UTF-16 caret/selection to Unicode code points, rejects cuts
  through surrogate pairs, checks canonical vault membership and compares
  the **unsaved buffer** to the saved note before showing local journal data.
  Re-entering the note's source from detail reuses the existing disk/buffer
  freshness check. Neither preview nor navigation appends to the journal.
- This is a read-only contextual drill-down. Full decision action flows,
  exhaustive evidence graphs, semantic proof and independent editor engine
  remain separate work. Python and Android CI passed on PR #57;
  physical QA-64 is deferred at owner request.

## Tenth slice — evidence and journal-linked navigation (integrated, QA deferred)

- **Evidence, related knowledge and decision timeline** is reachable from a
  journal-backed record in the editor. The new `editor_record_evidence` bridge
  reads only the accepted/historical journal: up to 12 recorded origins,
  16 related journal records (explicit predecessors/successors and possible
  opposite active assertions), and 16 journal events. Unapproved semantic
  index candidates are excluded from authoritative links.
- An origin remains **unverified** in the evidence graph. On selection, Android
  checks canonical containment inside the selected vault, current saved-note
  SHA-256, Unicode source span and full source-text SHA before moving the
  editor selection. Cross-note links open another DPKS editor and verify the
  destination a second time *after* its content loads; stale, missing or
  unsaved text refuses the jump. Back returns to the previous editor.
- Historical decisions and human-authored revisions may lack a navigable
  Markdown span; the UI keeps them visible without inventing one. Possible
  conflicts remain warnings, not certified logical proofs or facts. Reading
  the evidence never appends to the journal or rewrites Markdown.
- Exact source navigation, nested-dialog Back behavior, large-journal
  performance and TalkBack require later **QA-65** on synthetic Android data.
  This is not a full interactive evidence graph, a semantic diff or a
  replacement for the Markor editing engine. Python and Android CI passed on
  [PR #60](https://github.com/dpsmpx/DP_Knowledge_System/pull/60);
  physical QA-65 is OPEN/DEFERRED at the owner's request.

## Eleventh slice — persistent in-editor knowledge dock (integrated, QA deferred)

- The editor has a **permanent, compact knowledge rail** with saved-note
  counts/status and explicit Expand, Collapse and Refresh actions. It stays
  visible even when details are collapsed, preserving Markdown editing space.
  The read-only context snapshot is loaded locally after the editor becomes
  ready; it does not save, analyze with an external LLM or authorize knowledge.
- Inside its bounded 200dp scroll area, the dock shows the separate accepted
  journal and *unapproved tentative-index* lists, exact original Markdown vs
  canonical KSF, verified source navigation, historical event timelines,
  predecessor/successor links and possible conflicts. Selecting a source
  record, indexed relation or linked journal record now opens an **inline
  detail page**, not a succession of record/evidence confirmation dialogs.
  The cursor-linked inspector also routes its results to the dock.
- Back navigates a maximum of eight detached read-only detail pages and then
  returns to the note context; a system Back press traverses this history
  before leaving the editor. Refreshed context resets old page history. Source
  jumps still require the previously implemented vault, SHA and full Unicode
  span checks, collapsing only the dock body—not the permanent rail.
- Text changes make the snapshot visibly **stale** until explicit Refresh;
  even when unsaved, the displayed knowledge is labeled as originating from
  the **saved note**. Dock loading has its own worker generation so it cannot
  invalidate a concurrent editor source jump; stopped editors invalidate
  pending dock results and detach text observers.
- QA-66 is deferred to the later batched real-device test: viewport size,
  keyboard/rotation, TalkBack/200% text, nested navigation, large vaults and
  source/journal invariants remain unverified physically. This remains a
  bounded dock, not a free-resizing independent Markdown IDE or proof engine.
  Python and Android CI passed on
  [PR #63](https://github.com/dpsmpx/DP_Knowledge_System/pull/63);
  physical QA-66 is OPEN/DEFERRED at the owner's request.

## Twelfth slice — one-button isolated editor self-test and signed Preview #17 (device regression found)

- The **Processes → QA** card gains a dedicated **Test Stage 22 editor**
  action alongside existing quick diagnostics and 20k synthetic stress.
  The button runs on a cancellable background worker and copies one redacted
  PASS/WARN/FAIL/SKIP report, with a clear separate SKIP for physical UI QA.
- Python `stage22_editor_selftest` creates a **temporary isolated journal and
  artificial Markdown only under the app-private cache**. It exercises ten
  separate contracts: empty journal, explicit acceptance, Unicode source
  span/duplicate text, contained selection, role highlights, evidence origin,
  opposing claims, stale-source refusal, historical retraction and read-only
  hash integrity. The temporary directory is cleaned after every run.
- The self-test **never receives the selected vault path, real journal or
  derived index**. Java validates the machine-readable contract and prints
  fixed codes/counts, not text/paths/exceptions. The existing real-index
  diagnostics remain separate and read-only.
- GitHub Actions runs Python and Android tests, builds the isolated
  `com.dpsmpx.dpks` release, signs it only using configured GitHub secrets,
  verifies the APK with `apksigner`, checks SHA-256 and publishes the signed
  APK/checksum in a prerelease. No signing keys or passwords are committed.
- A PASS means the *named synthetic bridge regression* passed, not that UI
  tapping, orientation, IME, TalkBack, background lifecycle, performance or
  original note persistence was physically accepted. These remain **QA-67
  OPEN/DEFERRED** until batched on-device verification; do not use the
  automatic test as a substitute for the existing QA-59–66 matrix.
- **Observed on Android 15 in Preview #17, 19 Nov 2026:** the first one-button
  report was `PASS=1 WARN=0 FAIL=1 SKIP=1`; `ENV-01` passed, while
  `EDITOR-22-SUITE` failed with `JSONException` before individual checks.
  The Python self-test emitted `dpksys.stage22_editor_selftest.v1`, but
  `KnowledgePythonBridge.invoke` rejected that name because its Java protocol
  allowlist omitted it. The protocol hotfix adds the explicit allowlist entry
  and regression coverage in both Android unit tests and Python wiring tests.
  **Do not mark QA-67 PASS until the replacement APK is observed on-device.**

## Thirteenth slice — bounded literal source/KSF difference preview (in development)

- The persistent editor knowledge dock now marks **literal word and punctuation
  differences** between the saved Markdown excerpt and accepted canonical KSF
  statement in two separately labeled, selectable, read-only blocks. Matched
  tokens remain uncolored. This highlights differences in *representation*;
  neither matches nor differences certify agreement, contradiction or truth.
- The deterministic Java comparison walks Unicode code points and returns
  **UTF-16-safe offsets**, using an ordered longest-common-subsequence of at
  most 128 tokens per side and at most 1024 UTF-16 units per side. Larger
  material, missing text, source spans lacking verified provenance and
  explicitly truncated strings receive **no guessed highlighting** and a
  visible explanation. No Python bridge call or source write is added.
- Material theme container/on-container colors plus bold text distinguish
  changes in both light/dark themes. The underlying journal and Markdown
  strings are not modified. Local JUnit cases cover Cyrillic negation,
  duplicate words, non-BMP emoji, punctuation, identical text and bounds.
- **QA-68 OPEN/DEFERRED:** phone verification for contrast, selection/copy,
  large fonts, RTL/TalkBack, rotation, stale and truncated records is pooled
  with QA-59–67. This is a *lexical* comparison, not the still-open semantic
  source-span diff or standalone Markdown editing engine.

## Fourteenth slice — explicit CNL/IR structure comparison (in review)

- The dock now compares a *uniquely formalized* source sentence with the
  human-approved journal assertion using the same deterministic ksys IR.
  Predicate, typed arguments, negation, modality, context and time interval
  differences are shown separately from the existing **literal** token diff.
- A missing/stale source span, unsupported punctuation, ambiguous readings,
  sources longer than 320 Unicode code points, non-assertion nodes or a
  parser failure give **unavailable**.
  There is no guessed equivalence, automatic verdict on truth or promotion of
  machine interpretation to accepted fact. The source role/provenance remain
  separately labeled.
- Comparison runs in memory during the existing off-main-thread context load,
  without new index/journal/Markdown writes or network requests. Synthetic
  unit tests cover matching IR, inserted negation, unsupported grammar,
  stale provenance and source size bounds. **QA-69 physical UI signoff is
  deferred** until the owner's combined device test.

## Fifteenth slice — responsive editor dock and keyboard safety (in review)

- The Markdown surface and permanent DPKS knowledge rail share one workspace.
  Phones use a bounded vertical panel; tablets with at least 600dp smallest
  width, 720dp available width and 400dp height get a 280–360dp side dock.
  Multi-window/landscape phones remain protected against narrow editors.
- On phones, showing the keyboard collapses expanded knowledge details while
  retaining the compact status/actions. Orientation recreation preserves the
  user's expanded/collapsed choice and reapplies responsive measurements.
- Pure Java layout policy tests cover narrow devices, landscape phones,
  tablets and zero-size multi-window cases. Real keyboard, rotation, scroll,
  large-font/TalkBack and tablet accessibility remain **QA-70 OPEN/DEFERRED**
  for a single end-of-stage device acceptance run.

**Still incomplete:** Stage 22 is not a complete independent Markdown
editor or inline evidence-linked diff. Indexed snippets are not a guarantee
that original notes are still current; history displays journal events but
does not yet model every historical source span. Deep audit findings must be
opened through Settings. The old per-operation confirmation dialogs and
Markor Markdown editing engine are retained during the migration.

There are **no changes to authority, knowledge journal semantics, Markdown
sources, LLM permissions, APIs or the public data shipped with the APK**.

## One-device manual acceptance checklist (not automated PASS)

On an Android 15 device with test notes (avoid private journal mutations):

1. Open **DP Knowledge System**. Confirm the four bottom destinations,
   readable labels, color contrast in light/dark mode and wallpaper-driven
   color on Android 12+.
2. In **Library**, verify 760-note search (or another disposable collection),
   scroll, open note, long-press actions, search keyboard behavior, no toolbar
   or system-navigation overlap, and blank-query empty state.
3. Move to **Processes**, start the existing synthetic **Run device QA + copy**,
   switch to Library while it runs and back. The process badge must appear,
   the job must continue, status must update, and clipboard must contain the
   redacted report. Never run real provider requests for this check.
4. Repeat the tab switch during synthetic 20k stress only if storage and
   battery allow. Verify cancellation uses its original action and the UI
   remains responsive. Note cold/warm navigation and memory if possible.
5. In **Settings**, verify all review/audit/diagnostics/export/import controls
   are reachable. **Cancel** destructive or restorative dialogs. Do not
   import backup data into a real journal as a UI smoke test.
6. Rotate the device or change system theme, check selected tab restoration,
   text scaling, keyboard dismissal, contrast and readable scrolling on a
   small screen. Check accessibility labels with TalkBack if available.
7. Re-run the original synthetic Stage 15/16/17 reports; previous SQLite
   WARN and unfinished external/device checks must not be recast as PASS.
8. Cold-launch the **DP Knowledge System** icon, verify that **Library** opens
   immediately and no Markor-first activity or second launcher icon appears.
   Open an artificial `.md` note, switch Markdown edit/preview, save a manual
   edit in the disposable file, navigate back to the **same** DPKS library and
   confirm that Android Recents still contains DPKS. Test system Back and the
   editor's toolbar back; preserve and back up private notes beforehand.
9. In a **separate disposable knowledge journal**, review the Stage 17 revise,
   split, merge and retract *preview* dialogs. Check all before/after cards,
   long text, conflict warnings, Russian labels, font scaling, rotation and
   TalkBack. Cancel without committing for the UX smoke test. Test actual
   journal changes only with a verified backup and deliberately synthetic data.

## Remaining UI/UX work

- A full independent DPKS editor and intelligent workspace replacing the
  remaining Markor note-editing engine/menu chrome, while retaining reliable
  import/export and edit/preview workflows. The sixth slice adds a bounded
  context panel, verified source navigation, cursor-based journal inspection and temporary inline source marks,
  but not an inline semantic diff, persistent tablet
  sidebar or separate Markdown renderer.
- Accessible large-list navigation beyond the current 250 visible results,
  filtering/chips, meaningful progress history and pause/resume controls.
- Inline semantic diff, richer evidence, selective approval and reversible
  proposal preview; the Decisions screen covers navigation and provenance,
  but explicit confirmation still uses the established dialogs. The thirteenth
  slice highlights only literal token differences, not semantic equivalence.
- Screen-reader audit, reduced motion, large-font layouts, adaptive tablets,
  gesture navigation/edge-to-edge insets, instrumented UI tests and measured
  scrolling/rendering under 20k notes.
- Complete portable app backup, secure secret management and controlled sync.

No UI work changes the owner-confirmed rule: original Markdown is never
rewritten by autonomous analysis, and human confirmation is required for
authoritative knowledge changes outside a separately approved strict policy.

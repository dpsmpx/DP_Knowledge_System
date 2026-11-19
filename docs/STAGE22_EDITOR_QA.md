# Stage 22 — DPKS editor intelligence acceptance debt (QA-59)

Status: **OPEN**. This fourth slice has automated *synthetic* contract
checks, not physical-device signoff. It does **not** replace the Markor
editing engine or implement full independent Markdown rendering.

Owner feedback on Preview #15 (19 November 2026): a selected fragment
reached the formalizer but failed with **unsupported punctuation**; an
unsaved edit produced **editor text differs from saved note**. The second
result is an intended guard, not a failed attempt to write the journal.
The first is strict CNL behavior with inadequate guidance. Both are
scenario-specific observations, not a complete editor QA verdict.
After Preview #16, the owner confirmed the updated scenario works.
This does not close the untested lifecycle, accessibility or stress cases.
The owner explicitly requested that future device checks be batched and
deferred rather than required after every incremental PR.

## Expected scope

- Separate DPKS editor toolbar with Edit / Preview, Analyze, Review selection
  and Links. Inherited Markdown edit, preview, formatting and save behavior
  stays in Markor.
- Local source-fragment analysis and Unicode-aware selection mapping use
  the bundled Python ksys parser without transmitting note content.
- A review handoff only uses the *saved* Markdown source and refuses unsaved
  buffers. The library revalidates its source SHA before interpretation;
  the existing core validates provenance and journal head again on commit.
- Incoming links come from an existing **complete** local index scanned
  for the **same vault**. They are lexical possibilities, not verified facts.
  This read-only editor query must not create/migrate/rebuild an index.

## Required manual acceptance on Android 15 (synthetic files only)

1. Launch DPKS directly, open a disposable Markdown note. Confirm the editor
   shows all new controls without covering the keyboard, content or
   Markor's save/format menus on a Redmi Note 13 and with large system text.
2. Alternate **Правка/Просмотр**. Confirm edits, markdown rendering
   (headers, tables, links, code), cursor/selection and save still work.
   Change mode through a legacy Markor action; the DPKS toggle must reflect
   the state. Return to Library and verify the DPKS task survives.
3. Press **Анализ** on a synthetic note. Check fragment locators/counts,
   scrollability, Russian/English labels, explicit interpretation wording
   and that opening/canceling the list does **not** change the journal or note.
4. Select text containing an emoji or other non-BMP character and review.
   Check that the correct complete source fragment is displayed, not an
   offset-shifted neighbor. A cross-fragment selection and an empty selection
   must refuse to guess. Test a large note with more than 24 fragments.
5. Type an **unsaved** edit, then select/review: the editor must request a
   save, never authorize a different saved source or silently write the
   journal. Save manually and repeat with a disposable journal: a normal
   review preview appears; cancel it without commit. Edit/rewrite the
   source before confirmation and verify the freshness guard rejects it.
6. Check **Ссылки** with a complete local index: possible backlink list,
   opening a target note, back navigation. Repeat with no index, incomplete
   scan, a switched vault, ambiguous aliases and a missing target. No
   query may create an index or claim semantic proof.
7. Rotate the screen, switch apps during analysis, tap multiple times and
   use Back. No stale modal, frozen UI, unintended journal write or leaked
   private text in logs/reports should appear.
8. Check offline behavior, dark/light/dynamic themes, TalkBack, font scale
   200%, gesture navigation, battery/process death and 20k-note performance.
   Record device model/Android build, APK hash, timing and redacted results.

## Fifth-slice manual interpretation and Save regression (pending device test)

1. Select one sentence from a disposable note with parentheses or a semicolon,
   e.g. a statement the strict CNL cannot parse. The review must explain
   why the formal interpretation is unavailable and offer the separate
   human-authored interpretation action. The original Markdown must remain
   visible and unchanged.
2. Enter a simple valid one-line Russian CNL statement, for example
   `Марс является планетой.`. Preview it: the original source and your
   own statement must carry **different labels**. Cancel to confirm no
   journal or Markdown changes. An invalid statement should allow revision,
   not claim to have been formalized.
3. On disposable backed-up journal only, explicitly accept one manually
   reformulated claim. Confirm its provenance uses
   `human_interpretation/manual_reformulation`, retains source identity
   and journal replay succeeds. Test stale source and stale journal head.
4. Edit a disposable note but do not save; selection review must continue to
   refuse a mismatch. Press the new **Save** action deliberately, confirm
   the saved-file success status, then retry review. This action must
   preserve Markor's empty-file protection and must **never** auto-accept a
   knowledge decision.
5. Repeat after rotate/process stop, dark theme, font scale 200%, TalkBack,
   and with more than one paragraph. No personal data in logs/screenshots.

## Known omissions, not PASS

- **QA-59 / OPEN:** no complete hardware verification of the fourth slice.
- **QA-60 / OPEN:** fifth-slice guided manual interpretation, separate
  provenance, explicit Save and stale-source resistance received positive
  owner feedback in Preview #16, but full device checks remain open.
- **QA-61 / OPEN, DEFERRED:** sixth-slice editor context panel must be checked
  in a later batched device acceptance run; automated tests are not a UI PASS.
- **QA-62 / OPEN, DEFERRED:** seventh-slice source-span alignment and separate
  source/KSF comparison have only synthetic CI coverage; no device PASS.
- **QA-63 / OPEN, DEFERRED:** eighth-slice temporary inline highlights have
  synthetic checks only; the Markor editor lifecycle and colors need device QA.
- **QA-64 / OPEN, DEFERRED:** ninth-slice cursor-linked accepted/historical
  journal drill-down has synthetic tests only, not physical-device signoff.
- **QA-65 / OPEN, DEFERRED:** tenth-slice evidence sources, linked journal
  records and cross-note verified jumps await batched Android acceptance.
- **QA-66 / OPEN, DEFERRED:** eleventh-slice persistent inline knowledge dock,
  unsaved-stale reporting and bounded Back navigation await device checks.
- **QA-67 / OPEN, DEFERRED:** twelfth-slice one-button Stage 22 synthetic
  self-test had a concrete Preview #17 device failure: `EDITOR-22-SUITE`
  threw `JSONException` while `ENV-01` passed. The Java protocol allowlist
  omitted `dpksys.stage22_editor_selftest.v1`. A regression fix is in review;
  the replacement APK still needs one follow-up button check, and the full UI
  matrix remains deferred.
- **QA-68 / OPEN, DEFERRED:** thirteenth-slice literal Markdown/KSF diff has
  Java unit coverage but no observed on-device contrast, selectable text,
  long-text or TalkBack verification. It is not a semantic equivalence test.
- **QA-69 / OPEN, DEFERRED:** fourteenth-slice deterministic CNL/IR structural
  comparison has synthetic tests, not an observed Android UI signoff.
  Ambiguous/unparsed/stale sources must remain *unavailable*, even when a
  human-approved KSF assertion exists. Verify category wording, contrast,
  source/journal immutability and large-font/screen-reader behavior in the
  combined hardware QA.
- **QA-70 / OPEN, DEFERRED:** fifteenth-slice side-dock policy and IME-aware
  phone panel are covered by pure Java tests and static layout checks, but
  actual Android tablet/foldable multi-window, keyboard, rotation, font scale
  and TalkBack behavior require observation in the final combined run.
- **QA-71 / OPEN, DEFERRED:** sixteenth-slice library pagination queries are
  SQL-bounded and exercised by synthetic 20k index QA; real 760/20k scrolling,
  stale/partial index distinctions, keyboard, TalkBack and 200% fonts remain
  untested on device. Validate that tapping Next never opens/writes a note.
- **QA-72 / OPEN, DEFERRED:** seventeenth-slice first-open guide is local, skippable,
  and can be reopened from Settings. Synthetic XML wiring checks cover its five
  epistemic categories, private seen-state, scrollable large-font content and
  touch targets; no actual TalkBack reading order, rotation focus or 200% font
  acceptance has occurred. Decision details now identify their accessibility
  pane and lists reset without smooth-scroll motion. Test these on the final APK.
- **EDITOR-58 / PARTIAL:** an explicit editor mode control, selection analysis
  and possible-link browser now exist. Markor still owns actual Markdown
  editing/rendering and file persistence; a bounded contextual panel and
  separate source/KSF view, bounded temporary provenance highlights and a
  cursor-linked journal inspector exist,
  but semantic backlinks, editable inline review and full diff are not implemented.
- **QA-54 / PARTIAL USER FEEDBACK:** owner reports working decision review in
  Preview #14. This does not close unperformed TalkBack, stress or lifecycle
  acceptance checks in docs/STAGE22_DECISIONS_QA.md.
- **SQLITE-46, QA-52, QA-53, MERGE-17, BATCH-17 / OPEN:** existing unresolved
  physical-device or read-only SQLite integrity warnings are unaffected.

Use only disposable notes/journals for state-changing validation. Never
include private text, filesystem paths or credentials in public CI/QA logs.

## QA-61 — deferred context-panel acceptance checklist

1. On an empty synthetic vault with no journal or semantic index, open a
   note, tap **Знания**, and verify separate empty states for accepted
   records and possible links; no index or journal file may be created.
2. With a disposable approved claim, open its note and inspect the panel:
   role and original source excerpt must be distinct from formal KSF,
   provenance path and source-freshness state. Tap the record and jump to
   the exact source in Edit mode. Repeated/changed excerpts must refuse
   navigation rather than guessing.
3. With two synthetic opposing claims in the disposable semantic index,
   verify **possible conflict** is marked unapproved and can open the other
   Markdown note. Accepted journal contradictions must appear in the
   *separate* approved section.
4. Edit a note without saving. Open the context panel and confirm the
   banner identifies **saved source**, not the buffer; changing the saved
   file or invalidating its index must suppress stale candidate links.
5. Switch vaults, rotate, close/open the panel, navigate back, force-stop
   during the worker, and verify no stale modal appears, no path escapes
   the selected vault and no personal content reaches logs.
6. Defer 200% text, TalkBack, dark/dynamic theme, 760/20k-note latency,
   memory/battery and rapid repeated navigation measurements to a single
   later device QA session, with redacted evidence only.

## QA-62 — deferred exact-source alignment and comparison checklist

1. Accept the **second** of two identical synthetic source sentences in a
   disposable journal. Open the in-editor knowledge panel, inspect its two
   separately labeled source/KSF blocks and jump to the second occurrence,
   not the first. Ensure the original Markdown is not edited by navigation.
2. Put an emoji or another non-BMP Unicode character before that sentence.
   Confirm the selection boundaries remain exact on Android (Python code
   points mapped to Java UTF-16 offsets); long source/KSF blocks must declare
   truncation and remain selectable for copying.
3. Replace the source on disk, make an unsaved editor edit, or open a record
   with missing/mismatched historical span. The view must show unverified
   provenance, hide or refuse the jump, and never guess from matching text.
4. Check back navigation, rotation, app stop/resume, font scale 200%, TalkBack,
   light/dark theme and slow source reads. No unexpected journal/source write,
   no personal text in logs; keep QA-62 OPEN until completed on-device.

## QA-63 — deferred inline highlighting checklist

1. On a disposable note with two identical sentences and emoji preceding the
   second, accept **only the second** as a claim. Press **Подсветить знания**:
   the exact second source must be tinted without changing its characters or
   the note SHA. Toggle off and confirm all decorations vanish.
2. Create approved fact and hypothesis origins in different synthetic ranges.
   Confirm the legend/contrast in light/dark/dynamic theme. Overlapping
   different epistemic roles must appear as **mixed**, never a proven fact.
3. Run analysis producing only unapproved index candidates and no journal:
   **no source is highlighted**. Repeat with stale/missing provenance,
   an unsaved edit, changed disk source, and a journal with more than 128
   distinct ranges. Check count, truncation and refusal, not false negatives.
4. While highlights are visible, type, change to Preview via both DPKS and
   Markor actions, save, switch apps, rotate or close editor: decorations must
   disappear and must never enter persisted Markdown. Return to Edit and
   activate again explicitly. Assess keyboard, scrolling, TalkBack/200% text,
   20k performance and no personal content in telemetry or public QA logs.

## QA-64 — deferred source cursor and journal history checklist

1. In a disposable note with two identical sentences (preceded by emoji),
   accept **only the second** and place the caret inside it. **Знания
   фрагмента** must show exactly the linked accepted record with source/KSF,
   provenance and history; caret inside the first sentence must not match.
2. Select within the same sentence, then across two fragments. Only the
   contained selection may match. Test cursor on source end boundary and
   Unicode surrogate boundaries. Do not infer a record by lexical similarity.
3. Retract a disposable claim explicitly through the existing Decisions UI:
   the cursor inspector must show the historical/obsolete status with linked
   journal events. Test a source changed on disk, unsaved edit, absent journal
   and candidate-only index: no stale or unapproved match is treated as fact.
4. Confirm graceful truncation beyond 16 records, eight journal events,
   accessibility at 200% font, light/dark theme, long text selection and
   rapid open/cancel/rotation/Back. Keep notes/journal hashes unchanged while
   browsing; never include private content in reports or public CI.

## QA-65 — deferred evidence-graph and cross-note navigation checklist

1. In isolated synthetic notes `a.md` and `b.md`, accept opposing statements.
   From the editor's journal record tap **Основания, связанные знания и
   история решений**; see source origin, labeled *possible* conflicting
   accepted record and its independent history. No index candidate is
   displayed as an approved record. Verify the journal SHA stays constant.
2. Tap the other record and its source: DPKS must open the actual source note
   and select the exact Unicode span, including emoji prefixes and repeated
   paragraphs. Back must return to the original editor; no Markdown may be
   automatically saved or rewritten.
3. Change the target file on disk, change its source text, remove it, switch
   the selected vault, add an unsaved editor edit, and try a legacy record
   missing span/hash. Refuse each unverified jump, never guess a matching
   excerpt or follow a symlink outside the vault. Also change the target
   between initial validation and its activity opening.
4. Revise then retract synthetic records. Test lineage predecessors/successors,
   event ordering, 12+ origins, 16+ links, 16+ journal events, repeated drill
   downs, Back/rotation/process death, TalkBack and 200% font in light/dark
   themes. Distinguish truncation from absence. Keep QA-65 OPEN until actual
   device evidence exists.

## QA-66 — deferred persistent in-editor dock checklist

1. Open a synthetic Markdown note: the knowledge header must be visible even
   while collapsed and must not obscure the editing surface. Expand, collapse
   and refresh. On empty journal/index, show honest empty states, not facts.
   Test portrait/landscape, IME keyboard, Edit/Preview and 200% font.
2. Accept isolated synthetic claim/fact/hypothesis records. The expanded dock
   must label roles and show accepted journal entries separately from
   unapproved lexical/semantic suggestions. Tap a journal record, see separate
   source/KSF blocks, tap Evidence, visit an opposite active record and return
   through inline Back without opening a stack of modal dialogs.
3. From the cursor inspector choose an exact Unicode provenance, then browse
   source/evidence and return with system Back. Test more than eight nested
   links, refresh mid-navigation, and a cross-note verified source jump.
   Do not infer source equivalence from a repeated sentence.
4. Type without saving: the summary must become visibly stale, while source
   jumps refuse to use the buffer. Refresh must display *saved-note* context
   explicitly. Check background/stop/resume, vault switch, disk changes and
   long-running workers do not restore obsolete views or cancel a pending
   source jump. Notes and journal hashes must remain unchanged by inspection.
5. Repeat with 760/20k synthetic notes, slow storage, TalkBack, light/dark
   and dynamic theme, large text, long statements and source-history limits.
   Record counts and timing without personal note text, paths or secrets.
   Keep QA-66 OPEN until observed on an Android device.

## QA-67 — deferred one-button Stage 22 self-test and signed APK checklist

1. Install the signed GitHub prerelease on Redmi Note 13 after independent
   `apksigner` verification and compare SHA-256 with the release checksum.
   Check package `com.dpsmpx.dpks`, cold launch and navigation to Processes.
2. Press **Проверить редактор Stage 22 и скопировать**. Expect ten named
   synthetic `EDITOR-*-22` subchecks and a suite check, plus an explicit
   **SKIP for real UI interactions**. Confirm redacted report auto-copy,
   cancel/retry behavior, no sensitive text/path, no network request, and
   temporary cache fixtures removed after completion.
3. With real private notes unopened, verify the selected vault/index/journal
   timestamps and hashes are unchanged by the Stage 22-only self-test. A
   successful synthetic report does **not** validate actual touch/IME,
   orientation, TalkBack, background/OEM lifecycle, UI performance or
   every historical provenance edge case. Those stay in QA-59–66.
4. Test on API 24 and Android 11 when possible. Do not turn QA-67 PASS from
   Python/Android CI alone; record observed device behavior later.

## QA-68 — deferred literal source/KSF comparison checklist

1. In an isolated synthetic journal, accept a short source statement and open
   its record in the editor dock. Confirm the two separate selectable blocks
   display the exact saved Markdown and accepted KSF text without mutation.
2. Test an inserted `не`, repeated identical words, emoji and punctuation.
   Highlighted UTF-16 spans must stay on the correct symbols, preserve copy
   and keep the explicit **literal comparison is not semantic proof** text.
3. For an unverified/stale provenance span, truncated source/statement or
   oversized record, ensure highlighting is absent and the reason visible;
   no fallback to a guessed repeated occurrence is permitted.
4. Use 200% text, TalkBack, rotation, dark/light/dynamic theme and keyboard.
   Measure contrast, scroll, source SHA and journal head without including
   private contents in the report. Keep QA-68 OPEN until actually observed.
